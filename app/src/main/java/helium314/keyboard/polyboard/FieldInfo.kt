// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Decodes an [EditorInfo] into something a human can read, for the phase 1 debug overlay and for
 * the single-tag log.
 *
 * The decode functions take plain Ints so they are unit-testable without Robolectric. Phase 2's
 * sensitivity predicate (spec 6.1) will be built on the same masks, which is the point of pulling
 * the decoding out here first: the overlay and the gate read the field the same way, so what you
 * see while debugging is what the gate actually decided.
 *
 * Reference values captured from a real device, useful as a sanity check:
 *  - WhatsApp compose box: inputType 0x0002C001, imeOptions 0x40000006
 *    -> TEXT/NORMAL +CAP_SENTENCES +AUTO_CORRECT +MULTI_LINE, action DONE +NO_ENTER_ACTION
 */
object FieldInfo {

    /** Human-readable summary of a whole [EditorInfo]. Contains no field *contents*, only metadata. */
    @JvmStatic
    fun describe(editorInfo: EditorInfo?): String {
        if (editorInfo == null) return "editorInfo=null"
        return buildString {
            append("pkg=").append(editorInfo.packageName ?: "?")
            append("  inputType=").append(hex(editorInfo.inputType))
            append(" [").append(describeInputType(editorInfo.inputType)).append(']')
            append("  imeOptions=").append(hex(editorInfo.imeOptions))
            append(" [").append(describeImeOptions(editorInfo.imeOptions)).append(']')
            if (!editorInfo.privateImeOptions.isNullOrEmpty())
                append("  privateImeOptions=").append(editorInfo.privateImeOptions)
        }
    }

    /** Compact two-line form for the debug overlay, which has very little room. */
    @JvmStatic
    fun describeCompact(editorInfo: EditorInfo?): String {
        if (editorInfo == null) return "editorInfo = null"
        return (editorInfo.packageName ?: "?") + "\n" +
                describeInputType(editorInfo.inputType) + "\n" +
                "ime: " + describeImeOptions(editorInfo.imeOptions)
    }

    fun hex(value: Int): String = "0x" + value.toUInt().toString(16).uppercase().padStart(8, '0')

    /** e.g. `TEXT/NORMAL +CAP_SENTENCES +AUTO_CORRECT +MULTI_LINE` */
    @JvmStatic
    fun describeInputType(inputType: Int): String {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val flags = inputType and InputType.TYPE_MASK_FLAGS

        val className = when (cls) {
            InputType.TYPE_NULL -> return "NULL"
            InputType.TYPE_CLASS_TEXT -> "TEXT"
            InputType.TYPE_CLASS_NUMBER -> "NUMBER"
            InputType.TYPE_CLASS_PHONE -> "PHONE"
            InputType.TYPE_CLASS_DATETIME -> "DATETIME"
            else -> "CLASS($cls)"
        }

        val variationName = when (cls) {
            InputType.TYPE_CLASS_TEXT -> textVariationName(variation)
            InputType.TYPE_CLASS_NUMBER -> numberVariationName(variation)
            InputType.TYPE_CLASS_DATETIME -> dateTimeVariationName(variation)
            // TYPE_CLASS_PHONE defines no variations
            else -> null
        }

        val flagNames = when (cls) {
            InputType.TYPE_CLASS_TEXT -> textFlagNames(flags)
            InputType.TYPE_CLASS_NUMBER -> numberFlagNames(flags)
            else -> emptyList()
        }

        return buildString {
            append(className)
            if (variationName != null) append('/').append(variationName)
            flagNames.forEach { append(" +").append(it) }
        }
    }

    private fun textVariationName(variation: Int) = when (variation) {
        InputType.TYPE_TEXT_VARIATION_NORMAL -> "NORMAL"
        InputType.TYPE_TEXT_VARIATION_URI -> "URI"
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "EMAIL_ADDRESS"
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "EMAIL_SUBJECT"
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "SHORT_MESSAGE"
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "LONG_MESSAGE"
        InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "PERSON_NAME"
        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "POSTAL_ADDRESS"
        InputType.TYPE_TEXT_VARIATION_PASSWORD -> "PASSWORD"
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> "VISIBLE_PASSWORD"
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "WEB_EDIT_TEXT"
        InputType.TYPE_TEXT_VARIATION_FILTER -> "FILTER"
        InputType.TYPE_TEXT_VARIATION_PHONETIC -> "PHONETIC"
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "WEB_EMAIL_ADDRESS"
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "WEB_PASSWORD"
        else -> "VARIATION(${hex(variation)})"
    }

    private fun numberVariationName(variation: Int) = when (variation) {
        InputType.TYPE_NUMBER_VARIATION_NORMAL -> "NORMAL"
        InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "PASSWORD"
        else -> "VARIATION(${hex(variation)})"
    }

    private fun dateTimeVariationName(variation: Int) = when (variation) {
        InputType.TYPE_DATETIME_VARIATION_NORMAL -> "NORMAL"
        InputType.TYPE_DATETIME_VARIATION_DATE -> "DATE"
        InputType.TYPE_DATETIME_VARIATION_TIME -> "TIME"
        else -> "VARIATION(${hex(variation)})"
    }

    private fun textFlagNames(flags: Int): List<String> {
        val out = ArrayList<String>(4)
        if (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) out += "CAP_CHARACTERS"
        if (flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0) out += "CAP_WORDS"
        if (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) out += "CAP_SENTENCES"
        if (flags and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0) out += "AUTO_CORRECT"
        if (flags and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0) out += "AUTO_COMPLETE"
        if (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) out += "MULTI_LINE"
        if (flags and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE != 0) out += "IME_MULTI_LINE"
        if (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) out += "NO_SUGGESTIONS"
        // API 33; referenced by value so the project still compiles against older platforms
        if (flags and 0x00100000 != 0) out += "ENABLE_TEXT_CONVERSION_SUGGESTIONS"
        return out
    }

    private fun numberFlagNames(flags: Int): List<String> {
        val out = ArrayList<String>(2)
        if (flags and InputType.TYPE_NUMBER_FLAG_SIGNED != 0) out += "SIGNED"
        if (flags and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0) out += "DECIMAL"
        return out
    }

    /** e.g. `DONE +NO_ENTER_ACTION` */
    @JvmStatic
    fun describeImeOptions(imeOptions: Int): String {
        val action = when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_UNSPECIFIED -> "UNSPECIFIED"
            EditorInfo.IME_ACTION_NONE -> "NONE"
            EditorInfo.IME_ACTION_GO -> "GO"
            EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
            EditorInfo.IME_ACTION_SEND -> "SEND"
            EditorInfo.IME_ACTION_NEXT -> "NEXT"
            EditorInfo.IME_ACTION_DONE -> "DONE"
            EditorInfo.IME_ACTION_PREVIOUS -> "PREVIOUS"
            else -> "ACTION(${imeOptions and EditorInfo.IME_MASK_ACTION})"
        }
        return buildString {
            append(action)
            // NO_PERSONALIZED_LEARNING is listed first because it is the one that gates
            // translation (spec 6.1), so it should be the flag you notice.
            if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) append(" +NO_PERSONALIZED_LEARNING")
            if (imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0) append(" +NO_FULLSCREEN")
            if (imeOptions and EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS != 0) append(" +NAVIGATE_PREVIOUS")
            if (imeOptions and EditorInfo.IME_FLAG_NAVIGATE_NEXT != 0) append(" +NAVIGATE_NEXT")
            if (imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0) append(" +NO_EXTRACT_UI")
            if (imeOptions and EditorInfo.IME_FLAG_NO_ACCESSORY_ACTION != 0) append(" +NO_ACCESSORY_ACTION")
            if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) append(" +NO_ENTER_ACTION")
            if (imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0) append(" +FORCE_ASCII")
        }
    }
}
