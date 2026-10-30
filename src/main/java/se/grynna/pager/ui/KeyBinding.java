package se.grynna.pager.ui;

import javax.swing.KeyStroke;

/**
 * One keystroke bound to one {@link UiAction}.
 * <p>
 * Printable characters (for example {@code /}, {@code &}, {@code n}) are stored
 * as {@link java.awt.event.KeyEvent#KEY_TYPED} strokes built from the character,
 * so they work regardless of keyboard layout. Named keys (arrows, function keys,
 * Enter, Space, Esc) are {@code KEY_PRESSED} strokes built from
 * {@link java.awt.event.KeyEvent} VK codes plus modifiers.
 *
 * @param stroke  the keystroke that triggers the action
 * @param guard   whether the binding fires while a text field has focus
 * @param context where the binding applies; {@link Context#ANY} means everywhere
 * @param flavour the key tradition the binding belongs to (what the "Key hints" preference picks from)
 */
public record KeyBinding(KeyStroke stroke, KeyGuard guard, Context context, KeyFlavour flavour) {

    /** Where a binding is live. */
    public enum Context {
        /** Every context, including the views and prompts. */
        ANY,

        /** The normal (unfiltered) log view. */
        LOG_VIEW,

        /** The Matches view (filtered search results). */
        MATCHES_VIEW,

        /** The Bookmarks view (the session's bookmarked events). */
        BOOKMARKS_VIEW,

        /**
         * While the event details are open over a view. Wins over the view contexts (their keys do not fire) but
         * not over a prompt, and shadows the {@link #ANY} keys it rebinds (F3, Shift-F3).
         */
        DETAILS_VIEW,

        /** While a Y/N prompt is shown in the status bar. */
        PROMPT
    }

    public KeyBinding {
        if (stroke == null || guard == null || context == null || flavour == null) {
            throw new IllegalArgumentException("stroke, guard, context and flavour are required");
        }
    }

    /** A {@link KeyFlavour#DESKTOP} binding. */
    public KeyBinding(KeyStroke stroke, KeyGuard guard, Context context) {
        this(stroke, guard, context, KeyFlavour.DESKTOP);
    }

    /** The same binding, filed under another flavour. */
    public KeyBinding tagged(KeyFlavour flavour) {
        return new KeyBinding(stroke, guard, context, flavour);
    }

    /** True when this binding is live in {@code current} (an {@code ANY} binding always is). */
    public boolean appliesTo(Context current) {
        return context == Context.ANY || context == current;
    }
}
