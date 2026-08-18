// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.common.Constants

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

    override fun read(): FieldText? = readExtracted() ?: readFallback()

    private fun readExtracted(): FieldText? {
        val extracted = conn.fullFieldText ?: return null
        val text = extracted.text?.toString() ?: return null
        // selectionStart/End are relative to the extracted window; startOffset locates that window.
        // With an unbounded request the window is the whole field, but clamp anyway rather than
        // trusting every InputConnection in the wild to be consistent.
        val start = (extracted.startOffset + extracted.selectionStart).coerceIn(0, text.length)
        val end = (extracted.startOffset + extracted.selectionEnd).coerceIn(0, text.length)
        return FieldText(text, minOf(start, end), maxOf(start, end))
    }

    /**
     * For the apps whose `InputConnection` returns null from `getExtractedText`. Without this,
     * translation is simply impossible in those apps, which is unacceptable for a keyboard that is
     * supposed to work everywhere.
     *
     * The catch is that these reads come from a cache capped at
     * [Constants.EDITOR_CONTENTS_CACHE_SIZE] characters. If either side comes back at the cap we
     * cannot tell a full read from a truncated one, so this **refuses** rather than guessing:
     * translating half a message and then computing a replace from the wrong length is far worse than
     * declining. Short messages, which is nearly all of them, work fine.
     */
    private fun readFallback(): FieldText? {
        val cap = Constants.EDITOR_CONTENTS_CACHE_SIZE
        val before = conn.getTextBeforeCursor(cap, 0) ?: return null
        val after = conn.getTextAfterCursor(cap, 0) ?: ""
        if (before.length >= cap || after.length >= cap) {
            PolyLog.w("field too long to read safely without getExtractedText; refusing to translate")
            return null
        }
        PolyLog.d("using fallback field read (no getExtractedText); len=${before.length + after.length}")
        val text = before.toString() + after.toString()
        return FieldText(text, before.length, before.length)
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
