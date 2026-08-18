// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

/** A snapshot of a text field: its full contents and where the selection is. */
data class FieldText(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    val hasSelection get() = selectionStart != selectionEnd
    val cursor get() = selectionEnd
}

/**
 * Everything translation needs to do to a text field, behind an interface so [TranslationController]
 * can be unit tested without an `InputConnection`. The real implementation wraps
 * `RichInputConnection`; see `RichInputConnectionFieldIo`.
 *
 * Implementations must keep the keyboard's cursor bookkeeping consistent. That is the whole reason
 * this is not "just call InputConnection": `RichInputConnection` maintains an expected selection and
 * a mirror of the committed text by hand, and reaching around it leaves those stale.
 */
interface FieldIo {
    /** Full field contents, not a 1024-character window. Null when the connection is unavailable. */
    fun read(): FieldText?

    /** Settle any composing region. Must be called before reading for a translation. */
    fun finishComposing()

    /** Collapse a selection to its end, so a following [replace] is not blocked by selected text. */
    fun collapseSelectionToEnd(): Boolean

    /**
     * Atomically delete [deleteBefore] characters before and [deleteAfter] after the cursor and
     * commit [newText] in their place. Must be wrapped in a single batch edit: unwrapped multi-step
     * edits flicker and race with app text watchers.
     */
    fun replace(deleteBefore: Int, deleteAfter: Int, newText: String): Boolean

    /** Best-effort cursor restore, used by undo. Failure is not fatal. */
    fun setCursor(position: Int)
}
