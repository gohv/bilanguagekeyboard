// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.engine

import kotlinx.coroutines.flow.Flow

/**
 * Translation. No implementation may send typed text off the device, with exactly one documented
 * exception: [CloudLlmEngine], which the user opts into by choosing it and saving their own API key.
 *
 * Every method is suspending or callback-based on purpose. A future large local model must not live
 * in the IME process — Android kills IME processes aggressively and they cannot afford multi-second
 * cold loads — so the implementation has to be movable behind a binder without touching call sites.
 * Nothing here may become synchronous.
 */
interface TranslationEngine {
    /** Stable id for settings and logging, e.g. "stub", "mlkit", "onnx-opus". */
    val id: String

    /** Language tags this engine can translate, BCP-47. */
    suspend fun supportedLanguages(): List<String>

    /** Is the model for this pair ready to use right now, with no network and no wait? */
    suspend fun isPairReady(source: String, target: String): Boolean

    /**
     * Make the pair usable. May download (ML Kit) or decompress (bundled Opus-MT).
     * Emits progress so the UI is never a frozen spinner.
     */
    fun preparePair(source: String, target: String): Flow<PrepareState>

    /** Translate. Must be cancellable. Must not throw on cancellation. */
    suspend fun translate(text: String, source: String, target: String): Result<String>

    /** Optional: get ready for an imminent translation (load a model). Must not throw. */
    suspend fun warmUp() {}

    /** Release native resources. Called from onDestroy and onTrimMemory. */
    fun close()
}

sealed interface PrepareState {
    data object Queued : PrepareState
    data class Progress(val fraction: Float) : PrepareState
    data object Ready : PrepareState
    data class Failed(val reason: String, val retryable: Boolean) : PrepareState
}

/**
 * Language identification, needed for the spec 6.2 idempotence check: do not translate French into
 * French when the user edits a committed translation and taps translate again.
 *
 * Separate from [TranslationEngine] because the two are separate ML Kit artifacts, and because a
 * bundled Opus-MT engine would need a completely different identifier.
 */
interface LanguageIdentifier {
    val id: String

    /** BCP-47 tag, or null when undetermined. Callers must treat null as "proceed with the configured source". */
    suspend fun identify(text: String): String?

    fun close()
}
