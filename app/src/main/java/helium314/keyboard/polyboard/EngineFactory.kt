// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.content.Context
import helium314.keyboard.polyboard.engine.LanguageIdentifier
import helium314.keyboard.polyboard.engine.MlKitEngine
import helium314.keyboard.polyboard.engine.MlKitLanguageIdentifier
import helium314.keyboard.polyboard.engine.StubEngine
import helium314.keyboard.polyboard.engine.StubLanguageIdentifier
import helium314.keyboard.polyboard.engine.TranslationEngine
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Chooses the engine from settings. Kept separate from the IME so swapping engines, including the
 * eventual phase 7 ONNX engine, is one edit in one file.
 */
object EngineFactory {

    fun engine(context: Context): TranslationEngine = when (PolyPrefs.engine(context)) {
        PolyPrefs.ENGINE_STUB -> StubEngine()
        else -> MlKitEngine(requireWifi = PolyPrefs.unmeteredOnly(context))
    }

    fun identifier(context: Context): LanguageIdentifier = when (PolyPrefs.engine(context)) {
        PolyPrefs.ENGINE_STUB -> StubLanguageIdentifier()
        else -> MlKitLanguageIdentifier()
    }

    /**
     * Languages available for choosing a pair, resolved **without** creating an engine.
     *
     * This exists because the engine must stay lazily initialised (spec section 8): resolving the
     * language pair happens on every field open, and that must not be the thing that loads a model.
     * Both engines can answer statically, so nothing is lost.
     */
    fun supportedLanguages(context: Context): List<String> = when (PolyPrefs.engine(context)) {
        PolyPrefs.ENGINE_STUB -> StubEngine.STUB_LANGUAGES
        else -> TranslateLanguage.getAllLanguages()
    }
}
