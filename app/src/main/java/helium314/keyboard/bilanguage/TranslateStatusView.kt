// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * What the candidates strip shows after a translation: the result, plus the undo affordance.
 *
 * Spec section 5 requires undo to be right there, because users need it constantly and its absence
 * makes the feature feel dangerous. It also requires failures to be visible rather than swallowed
 * (section 11), so [forFailure] exists and deliberately looks different from success.
 *
 * Mounted with `setExternalSuggestionView`. Note that autofill and clipboard suggestions also use
 * that slot and run later on input start, so this view can legitimately lose the strip when a new
 * field opens; the state is also always logged.
 */
object TranslateStatusView {

    @SuppressLint("SetTextI18n")
    fun forState(
        context: Context,
        state: TranslateUiState,
        onUndo: () -> Unit,
        onCommitPreview: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ): View? = when (state) {
        // Mode 2: the translation is shown here and nowhere else. Tapping commits it.
        is TranslateUiState.Preview ->
            previewRow(context, state.translated, onCommitPreview)
        is TranslateUiState.ModelMissing ->
            row(
                context,
                if (BiPrefs.engine(context) == BiPrefs.ENGINE_CLOUD_LLM) {
                    context.getString(R.string.blk_cloud_not_configured)
                } else context.getString(
                    R.string.blk_model_missing,
                    state.from.uppercase(),
                    state.to.uppercase()
                ),
                null,
                isError = true,
                onWholeRowClick = onOpenSettings,
            )
        TranslateUiState.NeedsDisclosure ->
            row(context, context.getString(R.string.blk_needs_disclosure), null, onWholeRowClick = onOpenSettings)
        is TranslateUiState.Committed ->
            row(context, context.getString(R.string.blk_translated, state.from.uppercase(), state.to.uppercase()), onUndo)
        is TranslateUiState.AlreadyInTarget ->
            row(context, context.getString(R.string.blk_already_in, state.language.uppercase()), null)
        is TranslateUiState.Failed ->
            row(context, state.message, null, isError = true)
        TranslateUiState.Working ->
            row(context, context.getString(R.string.blk_working), null)
        TranslateUiState.Preparing ->
            row(context, context.getString(R.string.blk_preparing), null)
        // Idle and Disabled show nothing; the strip goes back to normal suggestions.
        else -> null
    }

    /**
     * The live-preview row. The whole row is the tap target, because the user's intent when they look
     * at a translation they asked for is "yes, use that", and a small button would be a worse target
     * on a strip this short.
     */
    private fun previewRow(context: Context, translated: String, onCommit: () -> Unit): View {
        val colors = Settings.getValues().mColors
        val label = TextView(context).apply {
            text = translated
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(colors.get(ColorType.KEY_TEXT))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.blk_tap_to_use)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(colors.get(ColorType.KEY_TEXT))
            alpha = 0.6f
            gravity = Gravity.CENTER
            setPadding(20, 0, 8, 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 12, 8)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(
                hint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnClickListener { onCommit() }
        }
    }

    private fun row(
        context: Context,
        message: String,
        onUndo: (() -> Unit)?,
        isError: Boolean = false,
        onWholeRowClick: (() -> Unit)? = null,
    ): View {
        val colors = Settings.getValues().mColors
        val textColor = colors.get(if (isError) ColorType.KEY_TEXT else ColorType.KEY_TEXT)

        val label = TextView(context).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor)
            // errors are italic as well as worded differently, so a failure is not mistaken for a
            // success at a glance
            if (isError) setTypeface(typeface, Typeface.ITALIC)
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 12, 8)
            // setExternalSuggestionView adds this with default params, which would be WRAP_CONTENT and
            // leave the row stuck to the top of the strip. Fill the strip so it centres.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            if (onWholeRowClick != null) setOnClickListener { onWholeRowClick() }
            if (onUndo != null) {
                val undo = TextView(context).apply {
                    text = context.getString(R.string.blk_undo)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(textColor)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(28, 0, 28, 0)
                    // Not clipboard_suggestion_background: that is a white rounded rect with 5dp
                    // insets, and at this row's height the insets collapse it into a sliver that
                    // draws straight through the label like a strikethrough.
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f
                        setColor(Color.TRANSPARENT)
                        setStroke((context.resources.displayMetrics.density * 1f).toInt(), textColor)
                    }
                    setOnClickListener { onUndo() }
                }
                addView(
                    undo,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
    }
}
