package se.grynna.pager.ui;

import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.EventOrder;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SegmentInfo;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static se.grynna.pager.splunk.BoundaryType.*;

/**
 * What the Matches view and the Bookmarks view share: an in-memory, fully loaded list of
 * whole events, shown through {@link EventRows} (one row per line, or one per event in
 * first-line mode), with the between-event {@link GapRule}s, the {@code ⏎+N} suffix,
 * identity based jumps and the edge boundaries.
 * <p>
 * A subclass owns where its events come from and what the rows outside the list say:
 * it feeds the events in through {@link #setEvents} and implements
 * {@link #getLine(int, boolean)} for the rows before the first and after the last event.
 * <p>
 * EDT-confined by design: no threads, no I/O.
 */
public abstract class EventListProvider implements LogDataProvider {

    static final String CONTINUED = "⏎+";

    private final boolean firstLineOnly;
    private EventRows rows = EventRows.empty();
    /** One hit per event, parallel to {@link EventRows#event(int)}; sorted by {@link EventOrder}. */
    private List<SearchHit> hits = List.of();
    /** Display time per event, parallel to {@link #rows}; the input of the gap rules. */
    private List<Instant> eventTimes = List.of();
    private Optional<Duration> gapThreshold = Optional.empty();
    private final GapThreshold.Tracker gapTracker = new GapThreshold.Tracker();

    private Boundary beforeBoundary = new Boundary(BEFORE, Instant.EPOCH, 0);
    private Boundary afterBoundary = new Boundary(AFTER, Instant.EPOCH, 0);
    private Boundary nowBoundary = new Boundary(NOW, Instant.EPOCH);

    /**
     * @param firstLineOnly show each event as its first line with a dim {@code ⏎+N}
     *                      suffix, instead of all of its lines
     */
    protected EventListProvider(boolean firstLineOnly) {
        this.firstLineOnly = firstLineOnly;
    }

    /**
     * Replace the events and rebuild everything derived from them.
     *
     * @param lines      event-level lines in event order
     * @param eventHits  one hit per line, same order
     * @param beforeTime the time the before edge reports
     * @param afterTime  the time the after edge (and "now") reports
     * @param finished   whether the producer of the events is done (settles the gap threshold)
     */
    protected final void setEvents(List<LoadedLogLine> lines, List<SearchHit> eventHits, Instant beforeTime,
            Instant afterTime, boolean finished) {
        List<Instant> times = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Instant time = lines.get(i).getDisplayTime();
            times.add(time != null ? time : eventHits.get(i).splunkTime());
        }
        this.rows = EventRows.of(lines, firstLineOnly);
        this.hits = List.copyOf(eventHits);
        this.eventTimes = List.copyOf(times);
        this.gapThreshold = gapTracker.update(eventTimes, finished);
        this.beforeBoundary = new Boundary(BEFORE, beforeTime, 0);
        this.afterBoundary = new Boundary(AFTER, afterTime, rows.rowCount());
        this.nowBoundary = new Boundary(NOW, afterTime);
    }

    /** True when events are shown as their first line only. */
    public boolean firstLineOnly() {
        return firstLineOnly;
    }

    /** The row model built from the events. */
    public EventRows rows() {
        return rows;
    }

    /** The hit of the event containing {@code row}. */
    public SearchHit hitAt(int row) {
        return hits.get(rows.eventAt(row));
    }

    /** First row of the event matching {@code hit} by exact identity, or -1 if absent. */
    public int rowOfHit(SearchHit hit) {
        if (hit == null) {
            return -1;
        }
        int eventIndex = Collections.binarySearch(hits, hit, EventOrder.searchHitComparator());
        return eventIndex < 0 ? -1 : rows.rowOf(eventIndex, 0);
    }

    /**
     * First row of the first event whose splunk time is at or after {@code time}; the last event's first row when
     * every event is earlier; empty without events. A binary search over the time-ordered hits: cheap enough for
     * every mouse event of a drag.
     */
    public OptionalInt rowAtOrAfter(Instant time) {
        if (hits.isEmpty()) {
            return OptionalInt.empty();
        }
        int low = 0;
        int high = hits.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (hits.get(mid).splunkTime().isBefore(time)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return OptionalInt.of(rows.rowOf(Math.min(low, hits.size() - 1), 0));
    }

    /** The row's position within its event. */
    public int offsetWithin(int row) {
        return rows.offsetWithin(row);
    }

    /** The adaptive gap threshold in use, empty while there are too few events to judge. */
    public Optional<Duration> gapThreshold() {
        return gapThreshold;
    }

    /**
     * The rule drawn above {@code row}: only the first row of an event can carry one; continuation
     * rows and out-of-range rows get {@link GapRule.None}. The first event gets its date so the list
     * says which day it starts on.
     */
    public GapRule ruleBefore(int row) {
        if (row < 0 || row >= rows.rowCount() || rows.offsetWithin(row) != 0) {
            return new GapRule.None();
        }
        int event = rows.eventAt(row);
        if (event == 0) {
            return new GapRule.Date(eventTimes.get(0).atZone(DateTimeFormatterManager.zoneId()).toLocalDate());
        }
        return GapRule.between(eventTimes.get(event - 1), eventTimes.get(event), gapThreshold,
                DateTimeFormatterManager.zoneId());
    }

    /**
     * The dim suffix for {@code row}, or null: {@code ⏎+N} when N lines of the event are
     * hidden (first-line mode), then whatever {@link #extraSuffix(int)} adds. Only the first row
     * of an event carries a suffix.
     */
    public String suffixAt(int row) {
        if (row < 0 || row >= rows.rowCount() || rows.offsetWithin(row) != 0) {
            return null;
        }
        int event = rows.eventAt(row);
        List<String> parts = new ArrayList<>(2);
        int hidden = rows.hiddenLines(event);
        if (hidden > 0) {
            parts.add(CONTINUED + hidden);
        }
        String extra = extraSuffix(event);
        if (extra != null) {
            parts.add(extra);
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** A further dim suffix part for the event, or null. The Matches view flags a match in hidden fields. */
    protected String extraSuffix(int eventIndex) {
        return null;
    }

    @Override
    public int getMaxOobPerSide() {
        return 2;
    }

    @Override
    public abstract LogLine getLine(int line, boolean edge);

    @Override
    public Optional<LogLine> peekLine(int line) {
        return Optional.ofNullable(getLine(line, false));
    }

    @Override
    public int jumpToLatest() {
        return rows.eventCount() == 0 ? 0 : rows.rowOf(rows.eventCount() - 1, 0);
    }

    @Override
    public int jump(Instant t) {
        int eventCount = rows.eventCount();
        if (eventCount == 0) {
            return 0;
        }
        int best = 0;
        Duration bestDistance = Duration.between(t, rows.event(0).getSplunkTime()).abs();
        for (int i = 1; i < eventCount; i++) {
            Duration distance = Duration.between(t, rows.event(i).getSplunkTime()).abs();
            if (distance.compareTo(bestDistance) < 0) {
                best = i;
                bestDistance = distance;
            }
        }
        return rows.rowOf(best, 0);
    }

    @Override
    public int jump(SearchHit hit) {
        return rowOfHit(hit);
    }

    @Override
    public void preload(int firstVisible, int lastVisible) {
        // Every row is already in memory; there is nothing to look ahead.
    }

    @Override
    public void unfreezeBeforeBoundary() {}

    @Override
    public void unfreezeAfterBoundary() {}

    @Override
    public boolean cancelSearch() {
        // Nothing runs inside this view to cancel.
        return false;
    }

    @Override
    public LogStatistics getStatistics() {
        if (hits.isEmpty()) {
            return new LogStatistics(LogStatistics.Status.UNINITIALISED, 0, Instant.EPOCH, Instant.EPOCH);
        }
        return new LogStatistics(LogStatistics.Status.LOADED, rows.rowCount(), hits.getFirst().splunkTime(),
                hits.getLast().splunkTime());
    }

    @Override
    public Boundary getBeforeBoundary() {
        return beforeBoundary;
    }

    @Override
    public void setBeforeBoundary(Instant t) {
        // The edges come from the events, not a mutable boundary.
    }

    @Override
    public Boundary getAfterBoundary() {
        return afterBoundary;
    }

    @Override
    public void setAfterBoundary(Instant t) {
        // The edges come from the events, not a mutable boundary.
    }

    @Override
    public Boundary getNowBoundary() {
        return nowBoundary;
    }

    @Override
    public void setBoundaryExpansionListener(BoundaryExpansionListener listener) {
        // Nothing here asks to expand; subclasses that can override this.
    }

    /** The start of the covered window, or null when the events alone describe the segment. */
    protected Instant windowStart() {
        return null;
    }

    /** The end of the covered window, or null when the events alone describe the segment. */
    protected Instant windowEnd() {
        return null;
    }

    @Override
    public SegmentInfo getSegmentInfo() {
        if (hits.isEmpty()) {
            return SegmentInfo.uninitialized();
        }
        Instant start = hits.getFirst().splunkTime();
        Instant end = hits.getLast().splunkTime();
        return new SegmentInfo(start, end, 0, rows.rowCount(), rows.rowCount(), 0.0, Duration.between(start, end),
                windowStart(), windowEnd(), null);
    }
}
