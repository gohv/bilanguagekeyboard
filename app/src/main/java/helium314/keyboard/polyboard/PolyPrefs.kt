// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs

/**
 * PolyBoard's own preferences, kept together so what the fork stores is auditable in one place.
 *
 * Note what is deliberately absent: anything containing typed text. Spec 6.4 allows an in-memory LRU
 * keyed on a hash but no persisted message content, so nothing here is a translation cache.
 */
object PolyPrefs {

    /** Which [helium314.keyboard.polyboard.engine.TranslationEngine] to use. */
    const val PREF_ENGINE = "poly_engine"
    const val ENGINE_MLKIT = "mlkit"
    const val ENGINE_STUB = "stub"
    const val DEFAULT_ENGINE = ENGINE_MLKIT

    /**
     * Whether the first-run disclosure has been shown and accepted. Until it is, translation does not
     * run and no model is ever fetched: spec section 7 requires the disclosure to come first, and
     * section 11 forbids downloading silently.
     */
    const val PREF_DISCLOSURE_ACCEPTED = "poly_disclosure_accepted"

    /** Download models on unmetered connections only. Default true, per spec section 7. */
    const val PREF_UNMETERED_ONLY = "poly_unmetered_only"

    /**
     * Translate in apps that set `IME_FLAG_NO_PERSONALIZED_LEARNING` (Signal, Molly, and other
     * "incognito keyboard" apps). Default **true**: without it the keyboard is dead in exactly the
     * apps it is most wanted in. See [FieldGate.INCOGNITO_RATIONALE] for why this is safe to override
     * while password gating is not.
     */
    const val PREF_ALLOW_INCOGNITO_APPS = "poly_allow_incognito_apps"

    fun allowIncognitoApps(context: Context): Boolean =
        context.prefs().getBoolean(PREF_ALLOW_INCOGNITO_APPS, true)

    fun setAllowIncognitoApps(context: Context, value: Boolean) =
        context.prefs().edit { putBoolean(PREF_ALLOW_INCOGNITO_APPS, value) }

    /** Mode 2 live preview. Default off: roughly ten times the inference calls of Mode 1. */
    const val PREF_LIVE_PREVIEW = "poly_live_preview"

    fun engine(context: Context): String =
        context.prefs().getString(PREF_ENGINE, DEFAULT_ENGINE) ?: DEFAULT_ENGINE

    fun setEngine(context: Context, engine: String) =
        context.prefs().edit { putString(PREF_ENGINE, engine) }

    fun disclosureAccepted(context: Context): Boolean =
        context.prefs().getBoolean(PREF_DISCLOSURE_ACCEPTED, false)

    fun setDisclosureAccepted(context: Context, accepted: Boolean) =
        context.prefs().edit { putBoolean(PREF_DISCLOSURE_ACCEPTED, accepted) }

    fun unmeteredOnly(context: Context): Boolean =
        context.prefs().getBoolean(PREF_UNMETERED_ONLY, true)

    fun setUnmeteredOnly(context: Context, value: Boolean) =
        context.prefs().edit { putBoolean(PREF_UNMETERED_ONLY, value) }

    /**
     * Languages whose models are on the device, cached by the model management screen.
     *
     * This is a *hint for choosing a default target*, not a source of truth: the engine is always
     * asked before translating. It exists because on an English keyboard on an all-English device
     * there is otherwise nothing to infer a target from, and "the language the user deliberately
     * downloaded" is by far the best available guess.
     */
    const val PREF_KNOWN_MODELS = "poly_known_models"

    fun knownModelLanguages(context: Context): List<String> =
        context.prefs().getString(PREF_KNOWN_MODELS, "")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setKnownModelLanguages(context: Context, languages: Collection<String>) =
        context.prefs().edit { putString(PREF_KNOWN_MODELS, languages.joinToString(",")) }

    fun livePreview(context: Context): Boolean =
        context.prefs().getBoolean(PREF_LIVE_PREVIEW, false)

    fun setLivePreview(context: Context, value: Boolean) =
        context.prefs().edit { putBoolean(PREF_LIVE_PREVIEW, value) }
}
