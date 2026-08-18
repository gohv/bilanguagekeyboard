// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Spec 6.1: which fields translation is allowed to touch at all.
 *
 * Getting this wrong once destroys trust permanently, so it is a pure function over an
 * [EditorInfo], separated from everything else, and unit tested against real captured values (see
 * docs/field-types-observed.md).
 *
 * The gate is deliberately **deny-by-default on anything unrecognised**: an input class we do not
 * know about is not translated. A missed translation is an inconvenience; translating a password
 * into a chat box is not.
 */
object FieldGate {

    /** Why translation is not available. [Allowed] means it is. */
    sealed interface Verdict {
        data object Allowed : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    @JvmStatic
    fun verdict(editorInfo: EditorInfo?): Verdict {
        if (editorInfo == null) return Verdict.Blocked("no editorInfo")

        // An app that says "do not learn from this content" is telling us not to process it.
        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            return Verdict.Blocked("IME_FLAG_NO_PERSONALIZED_LEARNING")

        val inputType = editorInfo.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        return when (cls) {
            // TYPE_NULL means the field takes no IME text at all (Chromium date pickers, for
            // example). Nothing to translate, and no field to safely write back into.
            InputType.TYPE_NULL -> Verdict.Blocked("TYPE_NULL")

            InputType.TYPE_CLASS_NUMBER -> Verdict.Blocked("TYPE_CLASS_NUMBER")
            InputType.TYPE_CLASS_PHONE -> Verdict.Blocked("TYPE_CLASS_PHONE")
            InputType.TYPE_CLASS_DATETIME -> Verdict.Blocked("TYPE_CLASS_DATETIME")

            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD -> Verdict.Blocked("TEXT_VARIATION_PASSWORD")
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> Verdict.Blocked("TEXT_VARIATION_VISIBLE_PASSWORD")
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> Verdict.Blocked("TEXT_VARIATION_WEB_PASSWORD")
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> Verdict.Blocked("TEXT_VARIATION_EMAIL_ADDRESS")
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Verdict.Blocked("TEXT_VARIATION_WEB_EMAIL_ADDRESS")
                InputType.TYPE_TEXT_VARIATION_URI -> Verdict.Blocked("TEXT_VARIATION_URI")
                else -> Verdict.Allowed
            }

            // Deny by default: an unknown input class is not translated.
            else -> Verdict.Blocked("unknown input class " + FieldInfo.hex(cls))
        }
    }

    @JvmStatic
    fun isAllowed(editorInfo: EditorInfo?): Boolean = verdict(editorInfo) is Verdict.Allowed
}
