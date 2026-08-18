// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

/** What the toolbar key and candidates strip should be showing. */
sealed interface TranslateUiState {
    /** Ready, nothing in flight. */
    data object Idle : TranslateUiState

    /** Translation is not available in this field. The key is greyed out. [reason] is for logs only. */
    data class Disabled(val reason: String) : TranslateUiState

    /** Engine is loading or a model is being fetched. Key shows a "preparing" state, not an error. */
    data object Preparing : TranslateUiState

    /** Inference in flight. Key shows a spinner. */
    data object Working : TranslateUiState

    /** Committed successfully; undo is available until the field changes again. */
    data class Committed(val from: String, val to: String) : TranslateUiState

    /** Nothing was changed. Must be visible to the user, never swallowed (spec section 11). */
    data class Failed(val message: String) : TranslateUiState

    /** 6.2 no-op: the text is already in the target language. */
    data class AlreadyInTarget(val language: String) : TranslateUiState

    /**
     * Mode 2: a translation is shown for review but is **not** in the field. Tapping commits it.
     *
     * Spec rule 5 is absolute: partial translations never reach the target field. Word order differs
     * between languages so partial output looks broken, and some apps fire send-on-text-change. This
     * state exists precisely so a preview has somewhere to live that is not the field.
     */
    data class Preview(val translated: String, val from: String, val to: String) : TranslateUiState

    /** The chosen pair has no model on disk. Must never silently trigger a download. */
    data class ModelMissing(val from: String, val to: String) : TranslateUiState

    /** The first-run disclosure has not been accepted, so nothing runs yet. */
    data object NeedsDisclosure : TranslateUiState
}
