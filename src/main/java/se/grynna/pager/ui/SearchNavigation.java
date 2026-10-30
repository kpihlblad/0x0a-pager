package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;

import java.util.Optional;

/**
 * Chooses the next or previous search hit for F3 / Shift-F3.
 * <p>
 * While the temporary navigation highlight is active, advance from the selected
 * hit. Centering that hit in a multi-row viewport places it below the first
 * visible line, so anchoring on the viewport top reselects it on the next F3
 * and makes Shift-F3 skip hits that sit between the highlight and the top.
 * Manual scrolling or cursor movement clears the highlight; the next step then
 * anchors on the first visible event.
 * <p>
 * Navigation does not wrap. Trying to move past the last or first match stays
 * put and reports that edge. The report says "matches", not "logs": the log
 * stream may continue, and "no more logs" already means "extend the time range".
 * While the search is still running the report says "yet", because a later
 * match can still arrive.
 */
final class SearchNavigation {

    static final String NO_MORE_LATER_MATCHES = "no more later matches";
    static final String NO_MORE_EARLIER_MATCHES = "no more earlier matches";
    static final String NO_LATER_MATCHES_YET = "no later matches yet";
    static final String NO_EARLIER_MATCHES_YET = "no earlier matches yet";

    enum Edge {
        NONE, LATER, EARLIER
    }

    private SearchHit current;
    private Edge stuck = Edge.NONE;
    private Edge declinedExtend = Edge.NONE;
    private int stuckHitCount;

    void clear() {
        current = null;
        releaseStuck();
    }

    SearchHit current() {
        return current;
    }

    Edge stuckEdge() {
        return stuck;
    }

    Edge declinedExtend() {
        return declinedExtend;
    }

    /**
     * Make {@code hit} the current match without moving the view, e.g. the
     * automatic first-match jump right after a search. F3 then continues from it.
     */
    void select(SearchHit hit) {
        current = hit;
        releaseStuck();
    }

    /** The user said no to widening the range. Don't ask again until they leave this edge. */
    void declineExtend(Edge edge) {
        declinedExtend = edge == null ? Edge.NONE : edge;
    }

    /** Drop the end-of-matches note. Scrolling away from the highlighted hit does this. */
    void releaseStuck() {
        stuck = Edge.NONE;
        stuckHitCount = 0;
        declinedExtend = Edge.NONE;
    }

    /**
     * A match arrived beyond the edge we were stuck on, or the search gained hits
     * while we had not selected one.
     */
    void releaseIfMovedPast(SearchSession session) {
        if (stuck == Edge.NONE || session == null) {
            return;
        }
        if (current == null) {
            if (session.hitCount() > stuckHitCount) {
                releaseStuck();
            }
            return;
        }
        boolean opened = stuck == Edge.LATER
                ? session.nextHit(current).isPresent()
                : session.previousHit(current).isPresent();
        if (opened) {
            releaseStuck();
        }
    }

    /**
     * Phrase for the status bar. Lowercase so it can follow "12 of 12 · ".
     * Null when navigation is not stuck.
     */
    String edgeNote(SearchSession session) {
        if (stuck == Edge.NONE || session == null) {
            return null;
        }
        if (session.getState() == SearchSession.State.CANCELLED) {
            return "search stopped";
        }
        boolean later = stuck == Edge.LATER;
        if (session.isTruncated(later ? SearchSession.Side.LATER : SearchSession.Side.EARLIER)) {
            return truncatedNote(later);
        }
        boolean stillSearching = session.getState() == SearchSession.State.RUNNING;
        return edgeNote(stillSearching, stuck == Edge.LATER);
    }

    /** The side stopped at the match limit; matches beyond its covered window were never searched. */
    static String truncatedNote(boolean later) {
        return "match limit reached — " + (later ? "later" : "earlier") + " matches not searched";
    }

    static String edgeNote(boolean stillSearching, boolean later) {
        if (stillSearching) {
            return later ? NO_LATER_MATCHES_YET : NO_EARLIER_MATCHES_YET;
        }
        return later ? NO_MORE_LATER_MATCHES : NO_MORE_EARLIER_MATCHES;
    }

    /**
     * Select the hit to visit next.
     *
     * @param navigationHighlightActive true while the temporary goto highlight is showing
     * @param firstVisible              first loaded event in the viewport, or null if none
     * @param forward                   true for F3, false for Shift-F3
     */
    Step advance(SearchSession session, boolean navigationHighlightActive, SearchHit firstVisible, boolean forward) {
        if (session == null || session.hitCount() == 0) {
            return Step.none();
        }
        if (current != null && !navigationHighlightActive) {
            current = null;
            releaseStuck();
        }

        SearchHit anchor = current != null ? current : firstVisible;
        if (anchor == null) {
            SearchHit selected = forward ? session.firstHit() : session.lastHit();
            current = selected;
            releaseStuck();
            return Step.to(selected);
        }

        Optional<SearchHit> hit = forward ? session.nextHit(anchor) : session.previousHit(anchor);
        if (hit.isEmpty()) {
            stuck = forward ? Edge.LATER : Edge.EARLIER;
            stuckHitCount = session.hitCount();
            return new Step(null, stuck);
        }
        current = hit.get();
        releaseStuck();
        return Step.to(current);
    }

    /**
     * @param hit  the hit to center, or null when navigation should stay put
     * @param edge {@link Edge#NONE} when a hit was selected; otherwise the direction that ran out
     */
    record Step(SearchHit hit, Edge edge) {
        static Step none() {
            return new Step(null, Edge.NONE);
        }

        static Step to(SearchHit hit) {
            return new Step(hit, Edge.NONE);
        }
    }
}
