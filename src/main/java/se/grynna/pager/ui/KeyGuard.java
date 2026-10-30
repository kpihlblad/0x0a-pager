package se.grynna.pager.ui;

/**
 * Whether a binding may fire while an editable text field has focus.
 * <p>
 * Mirrors {@code InputHandler.isEditableFieldFocused()}: bare characters,
 * Space and Enter are guarded so they do not steal keystrokes from the search,
 * goto or login fields; Esc, Ctrl-G and the function keys are unguarded because
 * they must work even while typing.
 */
public enum KeyGuard {
    /** Skipped while an editable text field has focus. */
    GUARDED,

    /**
     * Fires while the log or the search input has focus; skipped while another editable
     * text field (login, index panel, preferences) has focus.
     */
    SEARCH_INPUT_OK,

    /** Always fires, regardless of which component has focus. */
    UNGUARDED
}
