package se.grynna.pager.ui;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * A styled character range {@code [start, end)} within a row's message text (0-based, after the timestamp preamble).
 * The colour is supplied lazily so theme switches take effect on the next paint without rebuilding rows.
 */
public record StyleSpan(int start, int end, Supplier<Color> foreground, boolean bold) {

    public StyleSpan {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid style span [" + start + ", " + end + ")");
        }
    }
}
