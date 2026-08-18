// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import java.util.Locale

/**
 * Spec 7: remembers the translation target per target app, keyed on `EditorInfo.packageName`.
 * In practice one contact or one app means one language, which is what makes this feel intelligent
 * rather than fiddly.
 *
 * Mirrors upstream's own per-app pattern (`Settings.saveSubtypeForApp`, key
 * `PREF_SAVED_APP_SUBTYPE_PREFIX + packageName`) so there is one convention in the codebase.
 *
 * **Only the target is stored.** The source language comes from the active keyboard subtype, which
 * HeliBoard already persists per app. Storing the source too would mean two sources of truth that
 * could disagree, and it would break the nice property that switching keyboard language switches
 * the translation source for free.
 */
class LanguagePairStore(private val context: Context) {

    /** Target language for [packageName], or null when nothing has been chosen for it yet. */
    fun targetFor(packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        return context.prefs().getString(PREF_TARGET_PREFIX + packageName, null)
    }

    fun setTargetFor(packageName: String?, target: String) {
        if (packageName.isNullOrEmpty()) return
        context.prefs().edit { putString(PREF_TARGET_PREFIX + packageName, target) }
        PolyLog.i("pair target for $packageName set to $target")
    }

    fun clearTargetFor(packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        context.prefs().edit { remove(PREF_TARGET_PREFIX + packageName) }
    }

    /**
     * Target to use for [packageName], falling back to the system locale per spec 7, and then to
     * English. [sourceLanguage] is passed so we never hand back a pair that translates a language
     * into itself: if the system locale equals the source, pick any other supported language.
     */
    fun effectiveTarget(packageName: String?, sourceLanguage: String, supported: List<String>): String {
        targetFor(packageName)?.let { if (it in supported) return it }

        val systemLocale = Locale.getDefault().language.lowercase(Locale.ROOT)
        if (systemLocale in supported && systemLocale != sourceLanguage) return systemLocale

        return supported.firstOrNull { it != sourceLanguage } ?: sourceLanguage
    }

    companion object {
        const val PREF_TARGET_PREFIX = "poly_target_for_app_"
    }
}
