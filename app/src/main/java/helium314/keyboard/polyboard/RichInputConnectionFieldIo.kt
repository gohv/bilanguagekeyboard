// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import helium314.keyboard.latin.RichInputConnection

/**
 * The real [FieldIo], over upstream's `RichInputConnection`.
 *
 * All reads go through `getFullFieldText()` rather than the cached before/after-cursor window,
 * because that window is capped at 1024 characters and silently truncating a long message would
 * produce a translation of part of it plus a replace sized from the wrong length. If the app does not
 * support `getExtractedText`, [read] returns null and the controller refuses to translate — that is
 * the intended behaviour, not a bug to work around.
 */
class RichInputConnectionFieldIo(private val conn: RichInputConnection) : FieldIo {

    override fun read(): FieldText? {
        val extracted = conn.fullFieldText ?: return null
        val text = extracted.text?.toString() ?: return null
        // selectionStart/End are relative to the extracted window; startOffset locates that window.
        // With an unbounded request the window is the whole field, but clamp anyway rather than
        // trusting every InputConnection in the wild to be consistent.
        val start = (extracted.startOffset + extracted.selectionStart).coerceIn(0, text.length)
        val end = (extracted.startOffset + extracted.selectionEnd).coerceIn(0, text.length)
        return FieldText(text, minOf(start, end), maxOf(start, end))
    }

    override fun finishComposing() {
        conn.finishComposingText()
    }

    override fun collapseSelectionToEnd(): Boolean {
        val current = read() ?: return false
        return conn.setSelection(current.selectionEnd, current.selectionEnd)
    }

    override fun replace(deleteBefore: Int, deleteAfter: Int, newText: String): Boolean {
        if (!conn.isConnected) return false
        // One batch edit, so the target app sees a single change. Unwrapped multi-step edits flicker
        // and race with app text watchers, which is where "it inserted the text twice" comes from.
        conn.beginBatchEdit()
        try {
            conn.deleteSurroundingText(deleteBefore, deleteAfter)
            conn.commitText(newText, 1)
        } finally {
            conn.endBatchEdit()
        }
        return true
    }

    override fun setCursor(position: Int) {
        conn.setSelection(position, position)
    }
}
