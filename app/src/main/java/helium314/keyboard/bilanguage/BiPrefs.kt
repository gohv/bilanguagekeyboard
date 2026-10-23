// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.bilanguage.cloud.CloudConfig
import helium314.keyboard.bilanguage.cloud.CloudProvider
import helium314.keyboard.bilanguage.cloud.Register
import helium314.keyboard.latin.utils.prefs

/**
 * BiLanguageKeyboard's own preferences, kept together so what the fork stores is auditable in one place.
 *
 * Note what is deliberately absent: anything containing typed text. Spec 6.4 allows an in-memory LRU
 * keyed on a hash but no persisted message content, so nothing here is a translation cache.
 */
object BiPrefs {

    /** Which [helium314.keyboard.bilanguage.engine.TranslationEngine] to use. */
    const val PREF_ENGINE = "blk_engine"
    const val ENGINE_MLKIT = "mlkit"
    const val ENGINE_LOCAL_LLM = "local-llm"
    /** The only engine that sends text off the device. Never the default. */
    const val ENGINE_CLOUD_LLM = "cloud-llm"
    const val ENGINE_STUB = "stub"
    const val DEFAULT_ENGINE = ENGINE_MLKIT

    /**
     * Whether the first-run disclosure has been shown and accepted. Until it is, translation does not
     * run and no model is ever fetched: spec section 7 requires the disclosure to come first, and
     * section 11 forbids downloading silently.
     */
    const val PREF_DISCLOSURE_ACCEPTED = "blk_disclosure_accepted"

    /** Download models on unmetered connections only. Default true, per spec section 7. */
    const val PREF_UNMETERED_ONLY = "blk_unmetered_only"

    /**
     * Translate in apps that set `IME_FLAG_NO_PERSONALIZED_LEARNING` (Signal, Molly, and other
     * "incognito keyboard" apps). Default **true**: without it the keyboard is dead in exactly the
     * apps it is most wanted in. See [FieldGate.INCOGNITO_RATIONALE] for why this is safe to override
     * while password gating is not.
     */
    const val PREF_ALLOW_INCOGNITO_APPS = "blk_allow_incognito_apps"

    fun allowIncognitoApps(context: Context): Boolean =
        context.prefs().getBoolean(PREF_ALLOW_INCOGNITO_APPS, true)

    fun setAllowIncognitoApps(context: Context, value: Boolean) =
        context.prefs().edit { putBoolean(PREF_ALLOW_INCOGNITO_APPS, value) }

    /** Mode 2 live preview. Default off: roughly ten times the inference calls of Mode 1. */
    const val PREF_LIVE_PREVIEW = "blk_live_preview"

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
    const val PREF_KNOWN_MODELS = "blk_known_models"

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

    /**
     * Whether live preview should actually run. Never with the cloud engine: preview translates
     * partial drafts on every pause, and sending drafts the user never asked to translate to a
     * provider is not acceptable whatever the setting says.
     */
    fun livePreviewActive(context: Context): Boolean =
        livePreview(context) && engine(context) != ENGINE_CLOUD_LLM

    /** Casual (tu) or polite (vous) French, for the cloud engine. */
    const val PREF_REGISTER = "blk_register"

    fun register(context: Context): Register = Register.fromId(context.prefs().getString(PREF_REGISTER, null))

    fun setRegister(context: Context, register: Register) =
        context.prefs().edit { putString(PREF_REGISTER, register.id) }

    /**
     * The translate key opens [TranslateBox] (type in a box, the translation appears in the app as
     * you type) instead of translating the field once. Default on.
     */
    const val PREF_TRANSLATE_BOX = "blk_translate_box"

    fun translateBox(context: Context): Boolean =
        context.prefs().getBoolean(PREF_TRANSLATE_BOX, true)

    fun setTranslateBox(context: Context, value: Boolean) =
        context.prefs().edit { putBoolean(PREF_TRANSLATE_BOX, value) }

    /**
     * Cloud LLM provider settings. The API key is deliberately not here: it is stored encrypted by
     * [helium314.keyboard.bilanguage.cloud.ApiKeyStore].
     */
    const val PREF_CLOUD_PROVIDER = "blk_cloud_provider"
    const val PREF_CLOUD_BASE_URL = "blk_cloud_base_url"
    const val PREF_CLOUD_MODEL = "blk_cloud_model"

    fun cloudConfig(context: Context): CloudConfig {
        val prefs = context.prefs()
        val provider = CloudProvider.fromId(prefs.getString(PREF_CLOUD_PROVIDER, null))
        return CloudConfig(
            provider = provider,
            baseUrl = prefs.getString(PREF_CLOUD_BASE_URL, null) ?: provider.defaultBaseUrl,
            model = prefs.getString(PREF_CLOUD_MODEL, null) ?: provider.defaultModel,
        )
    }

    fun setCloudConfig(context: Context, config: CloudConfig) = context.prefs().edit {
        putString(PREF_CLOUD_PROVIDER, config.provider.id)
        putString(PREF_CLOUD_BASE_URL, config.baseUrl.trim())
        putString(PREF_CLOUD_MODEL, config.model.trim())
    }
}
