package se.grynna.pager.ui;

import se.grynna.pager.splunk.BoundaryExpansionHelper;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.SearchSession;

import java.time.Instant;

/**
 * Whether F3 / Shift-F3 should ask to widen the text-search window.
 * <p>
 * Only a finished search that still has time outside the window it already
 * queried gets the question. A running search may still deliver matches.
 * A stopped search did not finish this window, so widening it would skip
 * the gap the user just aborted.
 */
final class SearchRangeOffer {

    /** The indexing-lag margin that already keeps the "now" limit behind the clock; newer time is not asked for. */
    static final java.time.Duration LATER_SLACK = se.grynna.pager.splunk.LoadingStrategy.NO_LATER_THAN_OFFSET;

    private SearchRangeOffer() {}

    enum Kind {
        NONE, EXTEND, RECENTRE
    }

    record Decision(boolean ask, BoundaryType direction, Instant suggested, Kind kind) {
        static Decision quiet() {
            return new Decision(false, null, null, Kind.NONE);
        }

        static Decision extend(BoundaryType direction, Instant suggested) {
            return new Decision(true, direction, suggested, Kind.EXTEND);
        }

        static Decision recentre(BoundaryType direction, Instant suggested) {
            return new Decision(true, direction, suggested, Kind.RECENTRE);
        }

        public Decision(boolean ask, BoundaryType direction, Instant suggested) {
            this(ask, direction, suggested, ask && direction != null ? Kind.EXTEND : Kind.NONE);
        }

        public boolean ask() {
            return ask;
        }
    }

    public static Decision decide(SearchNavigation.Edge edge, SearchSession.State state, Instant windowStart,
            Instant windowEnd, Instant earliestPossible, Instant latestPossible, int expansionCount,
            SearchNavigation.Edge declined) {
        return decide(edge, state, windowStart, windowEnd, earliestPossible, latestPossible, expansionCount, declined,
                false, false);
    }

    /** Matches edges can resume a stopped run, or a side finished before its peer. */
    static Decision matchesEdge(SearchNavigation.Edge edge, SearchSession.Snapshot snapshot,
            Instant earliestPossible, Instant latestPossible, int expansionCount, SearchNavigation.Edge declined) {
        boolean later = edge == SearchNavigation.Edge.LATER;
        boolean done = later
                ? snapshot.laterDone() || snapshot.truncatedLater()
                : snapshot.earlierDone() || snapshot.truncatedEarlier();
        SearchSession.State state = snapshot.state();
        if (state == SearchSession.State.RUNNING && !done) return Decision.quiet();
        if (state == SearchSession.State.CANCELLED || state == SearchSession.State.RUNNING)
            state = SearchSession.State.COMPLETED;
        return decide(edge, state, snapshot.windowStart() != null ? snapshot.windowStart() : snapshot.origin(),
                snapshot.windowEnd() != null ? snapshot.windowEnd() : snapshot.origin(), earliestPossible,
                latestPossible,
                expansionCount, declined, snapshot.truncatedEarlier(), snapshot.truncatedLater());
    }

    /** G deliberately always re-centres outside the half-open covered window. */
    static Decision matchesGoto(SearchSession.Snapshot snapshot, Instant target) {
        Instant start = snapshot.windowStart();
        Instant end = snapshot.windowEnd();
        if (start != null && end != null && !target.isBefore(start) && target.isBefore(end)) return Decision.quiet();
        return Decision.recentre(start != null && target.isBefore(start) ? BoundaryType.BEFORE : BoundaryType.AFTER,
                target);
    }

    public static Decision decide(SearchNavigation.Edge edge, SearchSession.State state, Instant windowStart,
            Instant windowEnd, Instant earliestPossible, Instant latestPossible, int expansionCount,
            SearchNavigation.Edge declined, boolean truncatedEarlier, boolean truncatedLater) {
        if (edge == null || edge == SearchNavigation.Edge.NONE) {
            return Decision.quiet();
        }
        if (state == SearchSession.State.RUNNING) {
            return Decision.quiet();
        }
        if (edge == declined) {
            return Decision.quiet();
        }
        boolean later = edge == SearchNavigation.Edge.LATER;
        boolean truncated = later ? truncatedLater : truncatedEarlier;
        if (truncated && (state == SearchSession.State.COMPLETED || state == SearchSession.State.CANCELLED)) {
            Instant suggested = later ? windowEnd : windowStart;
            if (suggested == null) {
                return Decision.quiet();
            }
            BoundaryType direction = later ? BoundaryType.AFTER : BoundaryType.BEFORE;
            return Decision.recentre(direction, suggested);
        }
        if (state != SearchSession.State.COMPLETED) {
            return Decision.quiet();
        }
        Instant from = later ? windowEnd : windowStart;
        if (from == null || earliestPossible == null || latestPossible == null) {
            return Decision.quiet();
        }
        // The "now" limit moves by the minute while the user reads: a window that ended at the limit when
        // the search began has no later time worth asking for, however long the search took since.
        if (later && !from.isBefore(latestPossible.minus(LATER_SLACK))) {
            return Decision.quiet();
        }
        if (!later && !from.isAfter(earliestPossible)) {
            return Decision.quiet();
        }

        BoundaryType direction = later ? BoundaryType.AFTER : BoundaryType.BEFORE;
        int count = Math.max(1, expansionCount);
        Instant suggested = BoundaryExpansionHelper.calculateExpansion(direction, from, count, latestPossible);
        if (suggested == null || !isFurther(later, from, suggested, earliestPossible, latestPossible)) {
            return Decision.quiet();
        }
        return Decision.extend(direction, suggested);
    }

    private static boolean isFurther(boolean later, Instant from, Instant suggested, Instant earliestPossible,
            Instant latestPossible) {
        if (later) {
            return suggested.isAfter(from) && !suggested.isAfter(latestPossible);
        }
        return suggested.isBefore(from) && !suggested.isBefore(earliestPossible);
    }
}
