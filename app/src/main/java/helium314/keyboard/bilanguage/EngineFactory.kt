// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import android.content.Context
import helium314.keyboard.bilanguage.engine.LanguageIdentifier
import helium314.keyboard.bilanguage.engine.MlKitEngine
import helium314.keyboard.bilanguage.engine.MlKitLanguageIdentifier
import helium314.keyboard.bilanguage.engine.LocalLlmEngine
import helium314.keyboard.bilanguage.engine.StubEngine
import helium314.keyboard.bilanguage.engine.StubLanguageIdentifier
import helium314.keyboard.bilanguage.engine.TranslationEngine
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Chooses the engine from settings. Kept separate from the IME so swapping engines, including the
 * eventual phase 7 ONNX engine, is one edit in one file.
 */
object EngineFactory {

    fun engine(context: Context): TranslationEngine = when (BiPrefs.engine(context)) {
        BiPrefs.ENGINE_STUB -> StubEngine()
        BiPrefs.ENGINE_LOCAL_LLM -> LocalLlmEngine(context)
        else -> MlKitEngine(requireWifi = BiPrefs.unmeteredOnly(context))
    }

    fun identifier(context: Context): LanguageIdentifier = when (BiPrefs.engine(context)) {
        BiPrefs.ENGINE_STUB -> StubLanguageIdentifier()
        else -> MlKitLanguageIdentifier()
    }

    /**
     * Languages available for choosing a pair, resolved **without** creating an engine.
     *
     * This exists because the engine must stay lazily initialised (spec section 8): resolving the
     * language pair happens on every field open, and that must not be the thing that loads a model.
     * Both engines can answer statically, so nothing is lost.
     */
    fun supportedLanguages(context: Context): List<String> = when (BiPrefs.engine(context)) {
        BiPrefs.ENGINE_STUB -> StubEngine.STUB_LANGUAGES
        BiPrefs.ENGINE_LOCAL_LLM -> LocalLlmEngine.SUPPORTED_LANGUAGES
        else -> TranslateLanguage.getAllLanguages()
    }
}
