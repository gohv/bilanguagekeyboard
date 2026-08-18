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

    /**
     * @param allowIncognitoApps when true, a field is still translatable if the *only* thing against
     *   it is `IME_FLAG_NO_PERSONALIZED_LEARNING`. See [INCOGNITO_RATIONALE].
     */
    @JvmStatic
    @JvmOverloads
    fun verdict(editorInfo: EditorInfo?, allowIncognitoApps: Boolean = false): Verdict {
        if (editorInfo == null) return Verdict.Blocked("no editorInfo")

        val inputType = editorInfo.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        val hardVerdict: Verdict = when (cls) {
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

        // Checked LAST, and deliberately so. If a field is a password, email, URI or number field it
        // is already blocked above for a reason that this setting cannot override. Only a field that
        // is otherwise perfectly ordinary can be unblocked here.
        if (hardVerdict is Verdict.Blocked) return hardVerdict
        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 && !allowIncognitoApps)
            return Verdict.Blocked("IME_FLAG_NO_PERSONALIZED_LEARNING")

        return Verdict.Allowed
    }

    @JvmStatic
    @JvmOverloads
    fun isAllowed(editorInfo: EditorInfo?, allowIncognitoApps: Boolean = false): Boolean =
        verdict(editorInfo, allowIncognitoApps) is Verdict.Allowed

    /**
     * Why `IME_FLAG_NO_PERSONALIZED_LEARNING` is overridable while password gating is not.
     *
     * The flag means "do not learn from this content" — do not fold it into dictionaries, prediction
     * or personalisation. PolyBoard never persists typed text at all (spec 6.4: no history, no
     * on-disk cache, and the log carries lengths and hashes rather than text), so the flag's actual
     * request is honoured unconditionally whether or not translation runs.
     *
     * A password field is a different kind of thing. There the *content* is a secret, so translating
     * it would move a secret into a translation engine and then into a message box. No setting
     * unblocks that.
     *
     * Spec 6.1's last clause says to disable translation on this flag outright. That is the stricter
     * reading and it is what the default was. It is now user-overridable because it makes the
     * keyboard useless in exactly the apps it is most wanted in: Signal and its forks, including
     * Molly, set this flag on their message box, and that box is an ordinary multiline text field in
     * every other respect. See DECISIONS.md.
     */
    const val INCOGNITO_RATIONALE = "no-personalized-learning is about learning, which PolyBoard never does"
}
