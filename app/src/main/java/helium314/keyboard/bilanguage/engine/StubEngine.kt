// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.engine

import helium314.keyboard.bilanguage.BiLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * Phase 2 engine. Uppercases the text and prefixes the target language tag, so `hello` with target
 * `fr` becomes `[FR]HELLO`.
 *
 * The point of shipping a deliberately fake engine is that the entire keyboard UX — the toolbar key,
 * the atomic replace, undo, the field gating, the state machine — can be built and tested before any
 * real model exists, and can be re-tested later without a model present.
 *
 * It sleeps [latencyMs] before returning. That is not padding: a zero-latency engine hides every
 * ordering bug in the working/cancel/timeout paths, which are the parts most likely to be wrong.
 */
class StubEngine(private val latencyMs: Long = 120L) : TranslationEngine {

    override val id = "stub"

    override suspend fun supportedLanguages(): List<String> = STUB_LANGUAGES

    override suspend fun isPairReady(source: String, target: String) = true

    override fun preparePair(source: String, target: String): Flow<PrepareState> = flow {
        emit(PrepareState.Ready)
    }

    override suspend fun translate(text: String, source: String, target: String): Result<String> {
        // delay() is cancellable, which is exactly what we want to exercise
        delay(latencyMs)
        return Result.success(marker(target) + text.uppercase(Locale.ROOT))
    }

    override fun close() {
        BiLog.d("StubEngine.close()")
    }

    companion object {
        /** Not the real ML Kit list; enough languages to exercise the pair picker and swap. */
        @JvmField
        val STUB_LANGUAGES = listOf("en", "fr", "bg", "de", "es")

        fun marker(target: String) = "[" + target.uppercase(Locale.ROOT) + "]"
    }
}

/**
 * Language identification that understands [StubEngine]'s own output, and nothing else.
 *
 * This exists so the 6.2 idempotence rule runs through the real code path in phase 2 instead of
 * being special-cased. Re-tapping translate on `[FR]HELLO` genuinely goes: identify -> "fr",
 * compare against target "fr", no-op. When the ML Kit identifier replaces this, that logic does not
 * change.
 *
 * Anything that is not stub output is reported as undetermined (null), which per spec 6.2 means
 * "fall back to the configured source language and proceed".
 */
class StubLanguageIdentifier : LanguageIdentifier {

    override val id = "stub"

    override suspend fun identify(text: String): String? {
        val match = MARKER.matchEntire(text.trim()) ?: return null
        val tag = match.groupValues[1].lowercase(Locale.ROOT)
        val body = match.groupValues[2]
        // only claim a language if the body also looks like stub output, so a human-written
        // "[FR] bonjour tout le monde" is not mistaken for an already-translated string
        return if (body == body.uppercase(Locale.ROOT)) tag else null
    }

    override fun close() = Unit

    private companion object {
        val MARKER = Regex("""\[([A-Za-z]{2})]([\s\S]*)""")
    }
}
