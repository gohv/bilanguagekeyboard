// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import helium314.keyboard.polyboard.engine.LanguageIdentifier
import helium314.keyboard.polyboard.engine.TranslationEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Orchestrates one translation: read the field, check it is allowed and not already translated,
 * translate with a timeout, replace atomically, keep one undo step.
 *
 * ## Ordering guarantees this class is responsible for
 *
 * - The engine is created on first use, never eagerly, so keyboard render is never blocked
 *   (spec section 8). [close] drops it again on memory pressure.
 * - Inference runs on [ioDispatcher], never on main.
 * - Exactly one translation is in flight; starting another cancels the first, so a stale result can
 *   never land in the field.
 * - **The field is only written after a successful translation.** So a timeout, a failure or a
 *   cancellation leaves the field byte-for-byte untouched, which is what spec section 8's "a timeout
 *   must restore the pre-translation state" requires — there is no half-edited state to roll back
 *   from, by construction.
 */
class TranslationController(
    private val engineFactory: () -> TranslationEngine,
    private val identifierFactory: () -> LanguageIdentifier,
    private val mainScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMs: Long = TRANSLATE_TIMEOUT_MS,
) {
    private val _state = MutableStateFlow<TranslateUiState>(TranslateUiState.Idle)
    val state: StateFlow<TranslateUiState> = _state

    /** Java-friendly state callback. A `fun interface` so LatinIME can pass a plain lambda. */
    fun interface StateListener { fun onState(state: TranslateUiState) }

    var stateListener: StateListener? = null

    private fun setState(next: TranslateUiState) {
        _state.value = next
        stateListener?.onState(next)
    }

    private var engine: TranslationEngine? = null
    private var identifier: LanguageIdentifier? = null
    private var inFlight: Job? = null

    /** The one undo step. Cleared once used or once the field is replaced by something else. */
    private var undo: UndoSnapshot? = null

    val canUndo get() = undo != null

    data class UndoSnapshot(val original: FieldText, val committedText: String)

    /** Called when a new field is opened: previous undo no longer refers to anything real. */
    fun onFieldChanged(allowed: FieldGate.Verdict) {
        cancelInFlight()
        undo = null
        setState(
            when (allowed) {
                is FieldGate.Verdict.Allowed -> TranslateUiState.Idle
                is FieldGate.Verdict.Blocked -> TranslateUiState.Disabled(allowed.reason)
            }
        )
    }

    fun cancelInFlight() {
        inFlight?.cancel()
        inFlight = null
    }

    /**
     * Mode 1, commit-time translation (spec section 5).
     *
     * [source] is the active keyboard subtype's language; [target] comes from [LanguagePairStore].
     */
    fun translateNow(io: FieldIo, source: String, target: String) {
        if (_state.value is TranslateUiState.Disabled) {
            PolyLog.w("translate requested in a blocked field, ignoring")
            return
        }
        cancelInFlight()
        inFlight = mainScope.launch {
            try {
                runTranslation(io, source, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Never swallow: a failed translation must fail visibly (spec section 11).
                PolyLog.e("translate failed", e)
                setState(TranslateUiState.Failed("Translation failed"))
            }
        }
    }

    private suspend fun runTranslation(io: FieldIo, source: String, target: String) {
        if (source.equals(target, ignoreCase = true)) {
            setState(TranslateUiState.AlreadyInTarget(target))
            return
        }

        // The composing region must be settled before reading, or the field, mWordComposer and what
        // we read can all disagree.
        io.finishComposing()

        var snapshot = io.read()
        if (snapshot == null) {
            setState(TranslateUiState.Failed("No text field"))
            return
        }
        if (snapshot.hasSelection) {
            // deleteSurroundingText does not remove selected text, so a selection would survive the
            // replace and duplicate content. Collapse first, then re-read to get honest offsets.
            io.collapseSelectionToEnd()
            snapshot = io.read() ?: snapshot
        }

        val original = snapshot
        val sourceText = original.text
        if (sourceText.isBlank()) {
            setState(TranslateUiState.Failed("Nothing to translate"))
            return
        }

        setState(TranslateUiState.Preparing)
        val eng = ensureEngine()
        val ident = ensureIdentifier()

        if (!eng.isPairReady(source, target)) {
            // Phase 3 will drive preparePair() with progress here. The stub is always ready, so
            // reaching this with the stub means a real bug, not a missing model.
            setState(TranslateUiState.Failed("Model not ready for $source to $target"))
            return
        }

        // Spec 6.2 idempotence. Undetermined (null) means proceed with the configured source.
        val detected = withContext(ioDispatcher) { ident.identify(sourceText) }
        if (detected != null && detected.equals(target, ignoreCase = true)) {
            PolyLog.i("6.2 no-op: already in $target (${PolyLog.redact(sourceText)})")
            setState(TranslateUiState.AlreadyInTarget(target))
            return
        }

        setState(TranslateUiState.Working)
        val started = System.nanoTime()
        val result = withTimeoutOrNull(timeoutMs) {
            withContext(ioDispatcher) { eng.translate(sourceText, source, target) }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        if (result == null) {
            PolyLog.w("translate timed out after ${timeoutMs}ms, field left untouched")
            setState(TranslateUiState.Failed("Translation timed out"))
            return
        }

        val translated = result.getOrElse { e ->
            PolyLog.e("engine ${eng.id} failed after ${elapsedMs}ms", e)
            setState(TranslateUiState.Failed("Translation failed"))
            return
        }

        if (translated == sourceText) {
            setState(TranslateUiState.AlreadyInTarget(target))
            return
        }

        val deleteBefore = original.cursor
        val deleteAfter = original.text.length - original.cursor
        val ok = io.replace(deleteBefore, deleteAfter, translated)
        if (!ok) {
            setState(TranslateUiState.Failed("Could not update the text field"))
            return
        }

        undo = UndoSnapshot(original, translated)
        PolyLog.i(
            "translated $source->$target via ${eng.id} in ${elapsedMs}ms " +
                "src=${PolyLog.redact(sourceText)} out=${PolyLog.redact(translated)}"
        )
        setState(TranslateUiState.Committed(source, target))
    }

    /**
     * Restore the text as it was before the last commit. Users need this constantly; without it the
     * feature feels dangerous.
     */
    fun undoLast(io: FieldIo) {
        val snap = undo ?: return
        cancelInFlight()
        io.finishComposing()
        val current = io.read()
        if (current == null) {
            setState(TranslateUiState.Failed("No text field"))
            return
        }
        // Replace whatever is there now with the original. Using the current length rather than the
        // remembered committed length means undo still works if the user edited after committing.
        val ok = io.replace(current.cursor, current.text.length - current.cursor, snap.original.text)
        if (!ok) {
            setState(TranslateUiState.Failed("Could not undo"))
            return
        }
        io.setCursor(snap.original.cursor)
        undo = null
        setState(TranslateUiState.Idle)
        PolyLog.i("undo restored ${PolyLog.redact(snap.original.text)}")
    }

    private suspend fun ensureEngine(): TranslationEngine =
        engine ?: withContext(ioDispatcher) {
            engineFactory().also {
                engine = it
                PolyLog.i("engine ${it.id} initialised lazily")
            }
        }

    private suspend fun ensureIdentifier(): LanguageIdentifier =
        identifier ?: withContext(ioDispatcher) {
            identifierFactory().also { identifier = it }
        }

    /** Drop native resources. Safe to call repeatedly; the engine is re-created on next use. */
    fun close() {
        cancelInFlight()
        engine?.close()
        engine = null
        identifier?.close()
        identifier = null
        undo = null
        setState(TranslateUiState.Idle)
        PolyLog.i("controller closed, engine dropped")
    }

    companion object {
        const val TRANSLATE_TIMEOUT_MS = 3_000L

        /**
         * Orchestration scope. **Must be main-dispatched**: [stateListener] updates views, and a bare
         * `CoroutineScope(SupervisorJob())` defaults to `Dispatchers.Default`, which throws
         * CalledFromWrongThreadException the moment the strip is touched. Inference still runs off
         * main via the ioDispatcher passed to the constructor.
         */
        fun newMainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** Language tag of a locale, lowercased, e.g. "en". */
        fun languageOf(locale: Locale): String = locale.language.lowercase(Locale.ROOT)
    }
}
