// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Phase 1 instrumentation: the debug overlay that shows what BiLanguageKeyboard thinks the current field is.
 *
 * Mounted through `SuggestionStripView.setExternalSuggestionView`, which is upstream's existing
 * mechanism for putting an arbitrary view in the candidates strip (emoji search and the clipboard
 * suggestion both use it). Nothing about the suggestion renderer has to change.
 *
 * Shows only field *metadata*, never field contents.
 *
 * It survives until the next suggestion update, which is the right lifetime: you open a field, read
 * the decode, then start typing and it gets out of the way.
 */
object FieldDebugView {

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        context.prefs().getBoolean(DebugSettings.PREF_BLK_FIELD_OVERLAY, Defaults.PREF_BLK_FIELD_OVERLAY)

    @JvmStatic
    fun create(context: Context, editorInfo: EditorInfo?): View {
        val text = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            maxLines = 3
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(false)
            setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
            setPadding(12, 0, 12, 0)
            // no haptics/sound: this is a passive readout, not a key
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
            setText(FieldInfo.describeCompact(editorInfo))
        }
        // Long decodes are wider than the strip. Scrolling horizontally beats truncating, because
        // the flags at the end of the line are exactly the ones that matter for gating.
        return HorizontalScrollView(context).apply {
            isFillViewport = true
            addView(
                text,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }
}
