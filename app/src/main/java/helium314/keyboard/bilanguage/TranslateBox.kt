// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Translate-as-you-type box, shown above the suggestion strip.
 *
 * While it is open the keyboard types into this box instead of the app: [LatinIME] returns
 * [boxInputConnection] from `getCurrentInputConnection()`, so upstream's whole input pipeline
 * (composing, autocorrect, suggestions, backspace) works on the box unchanged. The app's field only
 * ever receives the translation, which replaces the span this box wrote last time.
 *
 * The system reports cursor moves only for the app's field, so the box reports its own through
 * [Host.onBoxSelectionChanged], posted and coalesced like a real `onUpdateSelection`, never re-entrant.
 * Reports about the app's field arrive in [onAppSelectionChanged] and are used for one thing: noticing
 * that the app cleared its field (message sent), so the box can clear too.
 */
class TranslateBox(private val context: Context, private val host: Host) {

    interface Host {
        /** The app's real connection, bypassing the box. */
        fun appInputConnection(): InputConnection?
        fun appEditorInfo(): EditorInfo?
        fun onBoxSelectionChanged(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                  composingStart: Int, composingEnd: Int)
        fun requestTranslation(text: String, immediate: Boolean, onResult: (String?) -> Unit)
        fun cancelTranslation()
        fun pairLabel(): String
        /** Label for the register switch (e.g. "Casual"), or null when it does not apply. */
        fun registerLabel(): String?
        fun toggleRegister()
        fun onCloseRequested()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var editText: BoxEditText? = null
    private var label: TextView? = null
    private var registerChip: TextView? = null
    private var boxConnection: InputConnection? = null

    var isOpen = false
        private set

    /** Where in the app's field our translation starts, or -1 before the first write. */
    private var anchor = -1
    /** Length of the translation currently in the app's field, starting at [anchor]. */
    private var written = 0
    private var suppressWatcher = false
    private var reportPending = false
    private var reportedStart = 0
    private var reportedEnd = 0
    /** After Enter in an action field: perform the app's action once this translation has landed. */
    private var submitAfterWrite = false

    /** Builds a fresh view; called whenever the keyboard view is recreated. */
    @SuppressLint("SetTextI18n")
    fun createView(): View {
        val colors = Settings.getValues().mColors
        val dp = context.resources.displayMetrics.density
        val textColor = colors.get(ColorType.EMOJI_SEARCH_TEXT)

        val pair = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            alpha = 0.75f
            setPadding((10 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = BoxEditText(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textColor)
            setHintTextColor(textColor and 0x00ffffff or 0x80000000.toInt())
            hint = context.getString(helium314.keyboard.latin.R.string.blk_box_hint)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
            showSoftInputOnFocus = false
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            onSelection = { scheduleSelectionReport() }
            setOnEditorActionListener { _, _, _ -> submit(); true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    scheduleSelectionReport()
                    if (suppressWatcher || !isOpen) return
                    val text = s?.toString().orEmpty()
                    translate(text, immediate = text.isNotEmpty() &&
                        TranslationController.isSentenceTerminal(text.codePointBefore(text.length)))
                }
            })
        }
        val register = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            background = GradientDrawable().apply {
                setStroke((1 * dp).toInt().coerceAtLeast(1), textColor and 0x00ffffff or 0x80000000.toInt())
                cornerRadius = 10 * dp
            }
            setOnClickListener {
                host.toggleRegister()
                refreshLabel()
                // Re-translate what is already there in the new register, without waiting for a keystroke.
                editText?.text?.toString()?.takeIf { it.isNotBlank() }?.let { translate(it, immediate = true) }
            }
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            contentDescription = context.getString(helium314.keyboard.latin.R.string.blk_box_close)
            setOnClickListener { host.onCloseRequested() }
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (isOpen) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                setColor(colors.get(ColorType.EMOJI_SEARCH_BACKGROUND))
                cornerRadius = 12 * dp
            }
            addView(pair, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(register, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            visibility = box.visibility
            colors.setBackground(this, ColorType.STRIP_BACKGROUND)
            addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // A recreated view while open carries the typed text over, so a theme change loses nothing.
        val previous = editText?.text?.toString()
        root = outer
        editText = input
        label = pair
        registerChip = register
        boxConnection = null
        if (isOpen) {
            suppressWatcher = true
            input.setText(previous.orEmpty())
            input.setSelection(input.length())
            suppressWatcher = false
            box.visibility = View.VISIBLE
            input.requestFocus()
        }
        refreshLabel()
        return outer
    }

    /** Height the box adds above the strip, for the IME's touchable insets. */
    val visibleHeight: Int get() = root?.takeIf { isOpen && it.visibility == View.VISIBLE }?.height ?: 0

    /** The connection upstream's input pipeline writes to while the box is open. */
    fun boxInputConnection(): InputConnection? {
        if (!isOpen) return null
        boxConnection?.let { return it }
        val input = editText ?: return null
        return input.onCreateInputConnection(EditorInfo()).also { boxConnection = it }
    }

    fun open(): Boolean {
        val outer = root ?: return false
        val input = editText ?: return false
        if (host.appInputConnection() == null) return false
        isOpen = true
        anchor = -1
        written = 0
        submitAfterWrite = false
        suppressWatcher = true
        input.setText("")
        suppressWatcher = false
        reportedStart = 0
        reportedEnd = 0
        outer.visibility = View.VISIBLE
        (outer.getChildAt(0)).visibility = View.VISIBLE
        input.requestFocus()
        refreshLabel()
        BiLog.i("translate box opened")
        return true
    }

    /** Close. What has already been written to the app stays there. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        host.cancelTranslation()
        handler.removeCallbacksAndMessages(null)
        reportPending = false
        boxConnection?.finishComposingText()
        boxConnection = null
        anchor = -1
        written = 0
        root?.visibility = View.GONE
        editText?.clearFocus()
        BiLog.i("translate box closed")
    }

    fun refreshLabel() {
        label?.text = host.pairLabel()
        val register = host.registerLabel()
        registerChip?.visibility = if (register == null) View.GONE else View.VISIBLE
        registerChip?.text = register.orEmpty()
    }

    /** Cursor in the app's field, for handing control back to the app on close. */
    fun appSelection(): IntArray? {
        val ic = host.appInputConnection() ?: return null
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        return intArrayOf(et.startOffset + et.selectionStart, et.startOffset + et.selectionEnd)
    }

    /**
     * The system reports the app's selection. Only interesting when the app has emptied its field
     * after we wrote to it, which is what sending a message looks like: start the next message.
     */
    fun onAppSelectionChanged(newSelStart: Int, newSelEnd: Int) {
        if (!isOpen || anchor < 0 || written == 0) return
        if (newSelStart != 0 || newSelEnd != 0) return
        val ic = host.appInputConnection() ?: return
        if (!ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return
        BiLog.i("app field cleared (sent?), clearing translate box")
        host.cancelTranslation()
        anchor = -1
        written = 0
        suppressWatcher = true
        editText?.setText("")
        suppressWatcher = false
    }

    private fun translate(text: String, immediate: Boolean) {
        if (text.isBlank()) {
            host.cancelTranslation()
            write("")
            setPending(false)
            return
        }
        setPending(true)
        host.requestTranslation(text, immediate) { result ->
            if (!isOpen) return@requestTranslation
            setPending(false)
            if (result != null) write(result)
            if (submitAfterWrite) {
                submitAfterWrite = false
                performAppAction()
            }
        }
    }

    /** Enter in an action field: translate now, then let the app send / search / go. */
    private fun submit() {
        val text = editText?.text?.toString().orEmpty()
        if (text.isBlank()) {
            performAppAction()
            return
        }
        submitAfterWrite = true
        translate(text, immediate = true)
    }

    private fun performAppAction() {
        val info = host.appEditorInfo() ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) return
        host.appInputConnection()?.performEditorAction(action)
    }

    /** Replace what the box wrote last time with [translation], in one batch edit. */
    private fun write(translation: String) {
        val ic = host.appInputConnection() ?: return
        if (anchor < 0) {
            if (translation.isEmpty()) return
            anchor = appSelection()?.get(0)
                ?: ic.getTextBeforeCursor(MAX_FIELD_READ, 0)?.length
                ?: return
        }
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            ic.setSelection(anchor, anchor + written)
            ic.commitText(translation, 1)
        } finally {
            ic.endBatchEdit()
        }
        written = translation.length
    }

    private fun setPending(pending: Boolean) {
        label?.text = host.pairLabel() + if (pending) " …" else ""
    }

    private fun scheduleSelectionReport() {
        if (!isOpen || reportPending) return
        reportPending = true
        handler.post {
            reportPending = false
            val input = editText ?: return@post
            if (!isOpen) return@post
            val start = input.selectionStart
            val end = input.selectionEnd
            val text = input.text
            host.onBoxSelectionChanged(
                reportedStart, reportedEnd, start, end,
                BaseInputConnection.getComposingSpanStart(text), BaseInputConnection.getComposingSpanEnd(text),
            )
            reportedStart = start
            reportedEnd = end
        }
    }

    private class BoxEditText(context: Context) : EditText(context) {
        var onSelection: (() -> Unit)? = null

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onSelection?.invoke()
        }
    }

    companion object {
        private const val MAX_FIELD_READ = 100_000
    }
}
