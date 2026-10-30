package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchHit;

/**
 * Pure placement arithmetic for flipping between the Matches and Log views.
 * <p>
 * Both views split an event with the same rule ({@link EventRows} /
 * {@link se.grynna.pager.splunk.LogEventMapper#splitLines}), so the cursor's row
 * within an event ({@code k}) maps to the log row {@code first + k} where
 * {@code first} is the event's first log row (the row an exact {@code jump(SearchHit)}
 * returns). Free of Swing and providers so the arithmetic is unit-testable; the
 * Swing wiring ({@code CursorManager.swapProvider} / {@code gotoLine},
 * {@code LogViewer.showMatchInLog}) is exercised manually because {@code LogViewer}
 * cannot be constructed headless.
 */
final class FlipPlacement {

    private FlipPlacement() {}

    /**
     * The log row to land on for a match whose event begins at
     * {@code eventFirstLogRow}: the event's first row plus the cursor's offset
     * within the event ({@code k} of a multiline match).
     */
    static int logRow(int eventFirstLogRow, int offsetWithinEvent) {
        return eventFirstLogRow + offsetWithinEvent;
    }

    /**
     * The Matches row of the anchor's nearest match: the anchor's own row when it
     * is itself a match, otherwise the match nearest its Splunk time.
     */
    static int matchesRow(EventListProvider matches, SearchHit anchor) {
        int row = matches.rowOfHit(anchor);
        return row >= 0 ? row : matches.jump(anchor.splunkTime());
    }
}
