package se.grynna.pager.ui;

import java.awt.*;

/**
 * A text pattern to be visually highlighted across all visible log lines.
 * Used for selection-echo (matching text in other rows) and search results.
 *
 * @param pattern       the literal string to match
 * @param background    background colour for matched segments
 * @param foreground    foreground colour for matched segments, or null to keep the existing colour
 * @param caseSensitive whether matching is case-sensitive. Selection echo is case-sensitive;
 *                      search highlights are not, because Splunk search is case-insensitive.
 */
public record TextHighlight(String pattern, Color background, Color foreground, boolean caseSensitive) {

    /** Case-sensitive highlight — the default for selection echo. */
    public TextHighlight(String pattern, Color background, Color foreground) {
        this(pattern, background, foreground, true);
    }
}
