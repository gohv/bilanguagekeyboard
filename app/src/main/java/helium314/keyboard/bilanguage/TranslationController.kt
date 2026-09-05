// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import helium314.keyboard.bilanguage.engine.LanguageIdentifier
import helium314.keyboard.bilanguage.engine.TranslationEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var previewJob: Job? = null

    /**
     * The pending Mode 2 preview: the field snapshot it was computed from, and the translation.
     * The snapshot is kept so [commitPreview] can refuse to commit a translation of text the user has
     * since changed, which is the difference between a preview and a stale write.
     */
    private var preview: PendingPreview? = null

    /** A computed but uncommitted Mode 2 preview. */
    data class PendingPreview(
        val snapshot: FieldText,
        val translated: String,
        val from: String,
        val to: String,
    )

    /** True when translation is allowed to run at all in the current field. */
    private var enabled = false

    /** The one undo step. Cleared once used or once the field is replaced by something else. */
    private var undo: UndoSnapshot? = null

    val canUndo get() = undo != null

    data class UndoSnapshot(val original: FieldText, val committedText: String)

    /** Called when a new field is opened: previous undo no longer refers to anything real. */
    fun onFieldChanged(allowed: FieldGate.Verdict) {
        cancelInFlight()
        previewJob?.cancel()
        previewJob = null
        preview = null
        undo = null
        enabled = allowed is FieldGate.Verdict.Allowed
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

    // ---------------- Mode 2: live preview (spec section 5) ----------------

    /**
     * Called on every text-changing keystroke when live preview is enabled.
     *
     * Debounced by [DEBOUNCE_MS], and additionally fired immediately on sentence-terminal punctuation,
     * because that is the point at which a translation is most likely to be worth showing.
     *
     * A single [previewJob] is cancelled and replaced on every call. That is what stops a stale result
     * landing: not a sequence number or a timestamp check, just the guarantee that at most one preview
     * computation is alive.
     */
    fun onTextChanged(io: FieldIo, source: String, target: String, immediate: Boolean) {
        if (!enabled) return
        // Any in-flight commit translation is now about text the user has edited: drop it.
        cancelInFlight()
        previewJob?.cancel()
        preview = null
        previewJob = mainScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            try {
                computePreview(io, source, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                BiLog.e("preview failed", e)
                setState(TranslateUiState.Idle)
            }
        }
    }

    private suspend fun computePreview(io: FieldIo, source: String, target: String) {
        if (source.equals(target, ignoreCase = true)) return
        val snapshot = io.read() ?: return
        val text = snapshot.text
        if (text.isBlank() || text.length < MIN_PREVIEW_CHARS) {
            setState(TranslateUiState.Idle)
            return
        }

        val eng = ensureEngine()
        if (!eng.isPairReady(source, target)) {
            // Never download because someone typed a letter. Mode 2 stays silent about missing
            // models; the explicit translate key is where that gets surfaced.
            return
        }
        val ident = ensureIdentifier()
        val detected = withContext(ioDispatcher) { ident.identify(text) }
        if (detected != null && detected.equals(target, ignoreCase = true)) {
            setState(TranslateUiState.Idle)
            return
        }

        val result = withTimeoutOrNull(timeoutMs) {
            withContext(ioDispatcher) { eng.translate(text, source, target) }
        } ?: return
        val translated = result.getOrNull() ?: return
        if (translated == text) return

        preview = PendingPreview(snapshot, translated, source, target)
        setState(TranslateUiState.Preview(translated, source, target))
    }

    /**
     * Commit the previewed translation, using the same atomic replace as Mode 1.
     *
     * Refuses if the field no longer matches what was translated. Without that check, tapping a
     * preview that arrived just before another keystroke would overwrite newer text with a
     * translation of older text.
     */
    fun commitPreview(io: FieldIo) {
        val pending = preview ?: return
        previewJob?.cancel()
        io.finishComposing()
        val current = io.read()
        if (current == null) {
            setState(TranslateUiState.Failed("No text field"))
            return
        }
        if (current.text != pending.snapshot.text) {
            BiLog.w("preview rejected: field changed since it was computed")
            preview = null
            setState(TranslateUiState.Failed("Text changed, translate again"))
            return
        }
        val ok = io.replace(current.cursor, current.text.length - current.cursor, pending.translated)
        if (!ok) {
            setState(TranslateUiState.Failed("Could not update the text field"))
            return
        }
        undo = UndoSnapshot(current, pending.translated)
        preview = null
        BiLog.i("preview committed ${BiLog.redact(pending.translated)}")
        setState(TranslateUiState.Committed(pending.from, pending.to))
    }

    /**
     * Mode 1, commit-time translation (spec section 5).
     *
     * [source] is the active keyboard subtype's language; [target] comes from [LanguagePairStore].
     */
    fun translateNow(io: FieldIo, source: String, target: String) {
        if (_state.value is TranslateUiState.Disabled) {
            BiLog.w("translate requested in a blocked field, ignoring")
            return
        }
        cancelInFlight()
        previewJob?.cancel()
        preview = null
        inFlight = mainScope.launch {
            try {
                runTranslation(io, source, target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Never swallow: a failed translation must fail visibly (spec section 11).
                BiLog.e("translate failed", e)
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
            // Deliberately does NOT start a download. Spec section 11: never download silently. The UI
            // turns this state into something tappable that opens the model management screen.
            BiLog.i("model missing for $source->$target, not downloading without being asked")
            setState(TranslateUiState.ModelMissing(source, target))
            return
        }

        // Spec 6.2 idempotence. Undetermined (null) means proceed with the configured source.
        val detected = withContext(ioDispatcher) { ident.identify(sourceText) }
        if (detected != null && detected.equals(target, ignoreCase = true)) {
            BiLog.i("6.2 no-op: already in $target (${BiLog.redact(sourceText)})")
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
            BiLog.w("translate timed out after ${timeoutMs}ms, field left untouched")
            setState(TranslateUiState.Failed("Translation timed out"))
            return
        }

        val translated = result.getOrElse { e ->
            BiLog.e("engine ${eng.id} failed after ${elapsedMs}ms", e)
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
        BiLog.i(
            "translated $source->$target via ${eng.id} in ${elapsedMs}ms " +
                "src=${BiLog.redact(sourceText)} out=${BiLog.redact(translated)}"
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
        BiLog.i("undo restored ${BiLog.redact(snap.original.text)}")
    }

    private suspend fun ensureEngine(): TranslationEngine =
        engine ?: withContext(ioDispatcher) {
            engineFactory().also {
                engine = it
                BiLog.i("engine ${it.id} initialised lazily")
            }
        }

    private suspend fun ensureIdentifier(): LanguageIdentifier =
        identifier ?: withContext(ioDispatcher) {
            identifierFactory().also { identifier = it }
        }

    /** Drop native resources. Safe to call repeatedly; the engine is re-created on next use. */
    fun close() {
        cancelInFlight()
        previewJob?.cancel()
        previewJob = null
        preview = null
        engine?.close()
        engine = null
        identifier?.close()
        identifier = null
        undo = null
        setState(TranslateUiState.Idle)
        BiLog.i("controller closed, engine dropped")
    }

    companion object {
        // Includes first-load latency for the optional 398 MB local llama.cpp model. ML Kit usually
        // returns much sooner; stale results are still prevented by cancellation and snapshot checks.
        const val TRANSLATE_TIMEOUT_MS = 30_000L

        /** Spec section 8: non-negotiable. Mode 2 is ~10x the inference calls of Mode 1. */
        const val DEBOUNCE_MS = 400L

        /** Below this, a preview is noise rather than information. */
        const val MIN_PREVIEW_CHARS = 2

        /** Sentence-terminal punctuation also triggers a preview immediately. */
        fun isSentenceTerminal(codePoint: Int): Boolean =
            codePoint == '.'.code || codePoint == '!'.code || codePoint == '?'.code ||
                codePoint == '\u061F'.code || codePoint == '\u3002'.code // Arabic, CJK full stop

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
