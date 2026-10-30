package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchHit;

/**
 * Pure anchor selection for entering the Matches view.
 * <p>
 * The anchor is the event that stays under the eye when the view flips. Precedence:
 * the F3 match while its navigation highlight is active, otherwise the cursor
 * line's event, otherwise the first visible event. The screen row is the line's
 * position in the log viewport (line minus page start); a value outside the page
 * is clamped by {@link CursorManager#gotoLine(int, int)}.
 * <p>
 * Free of Swing and providers so the precedence is unit-testable; the wiring
 * lives in {@code LogViewer.enterMatches()}.
 */
final class MatchesAnchor {

    private MatchesAnchor() {}

    /** The chosen anchor event and its screen row in the log view. */
    record Choice(SearchHit hit, int screenRow) {
        static final Choice NONE = new Choice(null, 0);
    }

    /**
     * Chooses the anchor and computes its screen row.
     *
     * @param highlightActive  whether the temporary navigation highlight is showing
     * @param currentMatch     the F3-selected match (may be null)
     * @param navHighlightLine the highlighted log line (non-null while the highlight shows)
     * @param cursorHit        the cursor line's event, or null without a cursor
     * @param cursorLine       the cursor line, or null
     * @param firstVisibleHit  the first visible event, or null
     * @param pageStart        the log viewport's first visible line
     */
    static Choice choose(boolean highlightActive, SearchHit currentMatch, Integer navHighlightLine,
            SearchHit cursorHit, Integer cursorLine, SearchHit firstVisibleHit, int pageStart) {
        if (highlightActive && currentMatch != null && navHighlightLine != null) {
            return new Choice(currentMatch, navHighlightLine - pageStart);
        }
        if (cursorHit != null && cursorLine != null) {
            return new Choice(cursorHit, cursorLine - pageStart);
        }
        if (firstVisibleHit != null) {
            return new Choice(firstVisibleHit, 0);
        }
        return Choice.NONE;
    }
}
