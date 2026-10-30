package se.grynna.pager.ui.minimap;

import se.grynna.pager.splunk.EventOrder;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * Finds the row of each search hit inside a loaded segment, without loading anything.
 * <p>
 * Rows are ordered by {@link EventOrder#searchHitComparator()} on their {@link SearchHit} (time, display
 * time, cb, bkt, cd). A continuation row carries its event's identity, so it sorts with its event; a binary
 * search for the lower bound therefore lands on the event's first loaded row. Rows that are not loaded
 * (empty, boundaries, placeholders) are skipped: at each probe the nearest loaded row is used, so holes only
 * cost extra peeks, they never trigger a load. If a hit's event is not loaded, it is not found.
 * <p>
 * Pure: no Swing and no threads. The caller runs it on an executor and checks staleness itself.
 */
public final class HitRows {

    /**
     * Marks a hit that has no loaded row in the range (not found, outside the range, or cancelled). Not -1:
     * the rows of a segment are numbered around the jump target, so every row before it is negative.
     */
    public static final int NOT_FOUND = Integer.MIN_VALUE;

    private static final Comparator<SearchHit> ORDER = EventOrder.searchHitComparator();

    private HitRows() {}

    /**
     * The row of each hit within {@code [startLine, endLineExclusive)}.
     *
     * @param peekLine          non-loading row lookup ({@code LogDataProvider::peekLine})
     * @param startLine         first row of the segment (inclusive)
     * @param endLineExclusive  end of the segment (exclusive)
     * @param hits              hits to place; order does not matter
     * @param cancelled         polled between hits and probes; when true the remaining hits are reported as
     *                          {@link #NOT_FOUND}
     * @return one entry per hit, in the same order: the row, or {@link #NOT_FOUND}
     */
    public static int[] rowsOf(IntFunction<Optional<LogLine>> peekLine, int startLine, int endLineExclusive,
            List<SearchHit> hits, BooleanSupplier cancelled) {
        int[] rows = new int[hits.size()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = cancelled.getAsBoolean()
                    ? NOT_FOUND
                    : rowOf(peekLine, startLine, endLineExclusive, hits.get(i), cancelled);
        }
        return rows;
    }

    private static int rowOf(IntFunction<Optional<LogLine>> peekLine, int startLine, int endLineExclusive,
            SearchHit hit, BooleanSupplier cancelled) {
        if (hit == null) {
            return NOT_FOUND;
        }
        int candidate = firstRowAtOrAfter(peekLine, startLine, endLineExclusive, hit, cancelled);
        if (candidate == NOT_FOUND || !sameEvent(keyOf(peekLine, candidate), hit)) {
            return NOT_FOUND;
        }
        return candidate;
    }

    /**
     * The first loaded row at or after {@code probe} in event order within {@code [startLine, endLineExclusive)}:
     * the lower bound of {@code probe} among the loaded rows. {@link #NOT_FOUND} when there is none (or on
     * cancellation). Used for date changes (day bands), where the probe is a time ({@link SearchHit#at}), not an event.
     */
    public static int firstRowAtOrAfter(IntFunction<Optional<LogLine>> peekLine, int startLine,
            int endLineExclusive, SearchHit probe, BooleanSupplier cancelled) {
        if (probe == null || startLine >= endLineExclusive) {
            return NOT_FOUND;
        }
        int lo = startLine;
        int hi = endLineExclusive;
        // Invariant: the lower bound of probe among the loaded rows lies in [lo, hi].
        while (lo < hi) {
            if (cancelled.getAsBoolean()) {
                return NOT_FOUND;
            }
            int mid = lo + (hi - lo) / 2;
            int row = nearestLoaded(peekLine, mid, lo, hi);
            if (row == NOT_FOUND) {
                // Nothing loaded in [lo, hi): the lower bound is the first loaded row at or after lo.
                break;
            }
            if (ORDER.compare(keyOf(peekLine, row), probe) < 0) {
                lo = row + 1;
            } else {
                hi = row;
            }
        }
        if (cancelled.getAsBoolean()) {
            return NOT_FOUND;
        }
        return nearestLoaded(peekLine, lo, lo, endLineExclusive);
    }

    /** Identity match; a hit without identity (see {@link SearchHit#at}) matches on both times. */
    private static boolean sameEvent(SearchHit row, SearchHit hit) {
        if (hit.hasIdentity()) {
            return ORDER.compare(row, hit) == 0;
        }
        return row.splunkTime().equals(hit.splunkTime()) && row.displayTime().equals(hit.displayTime());
    }

    /**
     * The loaded row closest to {@code row} within {@code [lo, hi)}, preferring the row at or after {@code row}
     * when both sides are equally near. Returns {@link #NOT_FOUND} when no row in the range is loaded.
     */
    private static int nearestLoaded(IntFunction<Optional<LogLine>> peekLine, int row, int lo, int hi) {
        for (int distance = 0;; distance++) {
            int up = row + distance;
            int down = row - distance;
            if (up >= hi && down < lo) {
                return NOT_FOUND;
            }
            if (up >= lo && up < hi && isLoaded(peekLine, up)) {
                return up;
            }
            if (distance > 0 && down >= lo && down < hi && isLoaded(peekLine, down)) {
                return down;
            }
        }
    }

    private static boolean isLoaded(IntFunction<Optional<LogLine>> peekLine, int row) {
        return peekLine.apply(row).filter(LoadedLogLine.class::isInstance).map(LoadedLogLine.class::cast)
                .filter(line -> line.getSplunkTime() != null && line.getDisplayTime() != null).isPresent();
    }

    private static SearchHit keyOf(IntFunction<Optional<LogLine>> peekLine, int row) {
        return SearchHit.from((LoadedLogLine) peekLine.apply(row).orElseThrow());
    }
}
