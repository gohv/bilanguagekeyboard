// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

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
        BiLog.i("pair target for $packageName set to $target")
    }

    fun clearTargetFor(packageName: String?) {
        if (packageName.isNullOrEmpty()) return
        context.prefs().edit { remove(PREF_TARGET_PREFIX + packageName) }
    }

    /**
     * Target to use for [packageName].
     *
     * Order: the stored choice for this app, then the system locale (spec section 7), then the
     * languages the user has actually enabled as keyboard layouts, then anything at all.
     *
     * [preferred] exists because of a real bug found on device: with ML Kit's full 58-language list
     * and an English keyboard on an English-locale phone, both of the first two rules are skipped
     * (the system locale *is* the source), and "anything at all" meant the alphabetically first
     * language — so the default pair came out as EN to Afrikaans. The user's own enabled keyboard
     * languages are a far better guess at which languages they actually speak.
     */
    fun effectiveTarget(
        packageName: String?,
        sourceLanguage: String,
        supported: List<String>,
        preferred: List<String> = emptyList(),
    ): String {
        targetFor(packageName)?.let { if (it in supported) return it }

        val systemLocale = Locale.getDefault().language.lowercase(Locale.ROOT)
        if (systemLocale in supported && systemLocale != sourceLanguage) return systemLocale

        preferred.forEach { candidate ->
            val tag = candidate.lowercase(Locale.ROOT)
            if (tag in supported && tag != sourceLanguage) return tag
        }

        // Last resort, and genuinely arbitrary. The user should pick a pair; this only keeps the chip
        // from being empty until they do.
        return supported.firstOrNull { it != sourceLanguage } ?: sourceLanguage
    }

    companion object {
        const val PREF_TARGET_PREFIX = "blk_target_for_app_"
    }
}
