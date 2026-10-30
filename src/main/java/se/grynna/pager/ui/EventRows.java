package se.grynna.pager.ui;

import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogEventMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Pure, immutable row model over an ordered list of events.
 * <p>
 * Each event is a single-line mapped {@link LoadedLogLine} (as produced by
 * {@link LogEventMapper#mapEventSingleLine}) representing a whole log entry. An
 * event with a multiline message occupies several rows: the splitting rule is
 * the one the log view uses ({@link LogEventMapper#splitLines}), so a match in
 * the Matches view looks exactly like the same event in the log — first row with
 * the preamble, following rows marked as continuations.
 * <p>
 * Row counts are computed eagerly into the prefix-sum array {@code rowStart};
 * the row lines themselves are materialised lazily per event and cached. All
 * row {@link LoadedLogLine}s handed out are copies, so callers can adjust them
 * without touching the stored events.
 * <p>
 * In first-line mode every event is one row (its first line); the rest of the
 * message is reported by {@link #hiddenLines(int)}.
 * <p>
 * This class is EDT-confined in practice (rebuilt from a search snapshot between
 * repaints); the lazy cache is idempotent and safe to publish afterwards.
 */
public final class EventRows {

    private static final EventRows EMPTY = new EventRows(List.of(), false);

    private final List<LoadedLogLine> events;
    private final boolean firstLineOnly;
    /** rowStart[i] = first row of event i; rowStart[eventCount()] = rowCount(). */
    private final int[] rowStart;
    /** Lazily materialised rows per event; null until first access. */
    private final List<List<LoadedLogLine>> rowsByEvent;

    private EventRows(List<LoadedLogLine> events, boolean firstLineOnly) {
        this.events = List.copyOf(events);
        this.firstLineOnly = firstLineOnly;
        int eventCount = this.events.size();

        this.rowStart = new int[eventCount + 1];
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            rowStart[eventIndex + 1] = rowStart[eventIndex]
                    + (firstLineOnly ? 1 : rowsIn(this.events.get(eventIndex)));
        }

        this.rowsByEvent = new ArrayList<>(eventCount);
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            rowsByEvent.add(null);
        }
    }

    /**
     * Rows over {@code events}, in event order.
     *
     * @throws NullPointerException if {@code events} or an element is null
     */
    public static EventRows of(List<LoadedLogLine> events) {
        return of(events, false);
    }

    /**
     * Rows over {@code events}; with {@code firstLineOnly} each event is a single row
     * showing its first line, and the remaining lines are {@link #hiddenLines(int)}.
     *
     * @throws NullPointerException if {@code events} or an element is null
     */
    public static EventRows of(List<LoadedLogLine> events, boolean firstLineOnly) {
        Objects.requireNonNull(events, "events");
        return events.isEmpty() ? EMPTY : new EventRows(events, firstLineOnly);
    }

    /** No events, no rows. */
    public static EventRows empty() {
        return EMPTY;
    }

    public int eventCount() {
        return events.size();
    }

    public int rowCount() {
        return rowStart[events.size()];
    }

    /**
     * The event containing {@code row}.
     *
     * @throws IndexOutOfBoundsException if {@code row} is outside {@code [0, rowCount())}
     */
    public int eventAt(int row) {
        checkRow(row);
        int found = Arrays.binarySearch(rowStart, 0, events.size(), row);
        return found >= 0 ? found : -found - 2;
    }

    /**
     * The row's position within its event, i.e. {@code row - rowStart[eventAt(row)]}.
     *
     * @throws IndexOutOfBoundsException if {@code row} is outside {@code [0, rowCount())}
     */
    public int offsetWithin(int row) {
        return row - rowStart[eventAt(row)];
    }

    /**
     * The row of line {@code k} of event {@code eventIndex}. {@code k} is clamped
     * into {@code [0, rowsOf(eventIndex) - 1]}.
     *
     * @throws IndexOutOfBoundsException if {@code eventIndex} is outside {@code [0, eventCount())}
     */
    public int rowOf(int eventIndex, int k) {
        checkEvent(eventIndex);
        int lastOffset = rowsOf(eventIndex) - 1;
        return rowStart[eventIndex] + Math.max(0, Math.min(k, lastOffset));
    }

    /**
     * The number of rows of event {@code eventIndex}; at least one.
     *
     * @throws IndexOutOfBoundsException if {@code eventIndex} is outside {@code [0, eventCount())}
     */
    public int rowsOf(int eventIndex) {
        checkEvent(eventIndex);
        return rowStart[eventIndex + 1] - rowStart[eventIndex];
    }

    /** True when each event is shown as its first line only. */
    public boolean firstLineOnly() {
        return firstLineOnly;
    }

    /**
     * The number of message lines of event {@code eventIndex} that are not shown: zero
     * in whole-events mode, otherwise the lines after the first one.
     *
     * @throws IndexOutOfBoundsException if {@code eventIndex} is outside {@code [0, eventCount())}
     */
    public int hiddenLines(int eventIndex) {
        checkEvent(eventIndex);
        return firstLineOnly ? rowsIn(events.get(eventIndex)) - 1 : 0;
    }

    /**
     * The stored event, unchanged.
     *
     * @throws IndexOutOfBoundsException if {@code eventIndex} is outside {@code [0, eventCount())}
     */
    public LoadedLogLine event(int eventIndex) {
        return events.get(eventIndex);
    }

    /**
     * A copy of the row's line with {@code lineNumber == row}. Never returns (or
     * mutates) a stored line.
     *
     * @throws IndexOutOfBoundsException if {@code row} is outside {@code [0, rowCount())}
     */
    public LoadedLogLine line(int row) {
        int eventIndex = eventAt(row);
        int offset = row - rowStart[eventIndex];
        return copy(rowsOfEvent(eventIndex).get(offset), row);
    }

    private List<LoadedLogLine> rowsOfEvent(int eventIndex) {
        LoadedLogLine event = events.get(eventIndex);
        List<LoadedLogLine> rows = rowsByEvent.get(eventIndex);
        if (rows == null) {
            if (event.getMessage() == null) {
                rows = List.of(copyWithoutMessage(event));
            } else {
                List<LoadedLogLine> all = LogEventMapper.splitLines(event);
                rows = firstLineOnly ? List.of(all.getFirst()) : all;
            }
            rowsByEvent.set(eventIndex, rows);
        }
        return rows;
    }

    /** An event without a message still shows as one (empty) row. */
    private static LoadedLogLine copyWithoutMessage(LoadedLogLine event) {
        LoadedLogLine row = copy(event, 0);
        row.setMessage("");
        row.setContinuation(false);
        return row;
    }

    private static LoadedLogLine copy(LoadedLogLine source, int lineNumber) {
        var copy = new LoadedLogLine(source.getMsgType(), source.getSegmentId(), lineNumber, source.getCb(),
                source.getBkt(), source.getTime(), source.getRaw(), source.getIndextime(), source.getMsgTime(),
                source.getMessage(), source.isContinuation(), source.getLevel());
        copy.setEvent(source.getEventMap());
        copy.setActivity(source.getActivity());
        return copy;
    }

    /** Rows an event will occupy; mirrors {@link LogEventMapper#splitLines}, including its empty fallback row. */
    private static int rowsIn(LoadedLogLine event) {
        String message = event.getMessage();
        if (message == null) {
            return 1;
        }
        int lines = countLines(message);
        return lines == 0 ? 1 : lines;
    }

    /** Counts lines exactly as {@link String#lines()} does, without allocating. */
    private static int countLines(String message) {
        int lines = 0;
        int length = message.length();
        int i = 0;
        while (i < length) {
            char c = message.charAt(i);
            if (c == '\n') {
                lines++;
                i++;
            } else if (c == '\r') {
                lines++;
                i += i + 1 < length && message.charAt(i + 1) == '\n' ? 2 : 1;
            } else {
                i++;
            }
        }
        if (length > 0) {
            char last = message.charAt(length - 1);
            if (last != '\n' && last != '\r') {
                lines++;
            }
        }
        return lines;
    }

    private void checkRow(int row) {
        if (row < 0 || row >= rowCount()) {
            throw new IndexOutOfBoundsException("Row " + row + " outside [0, " + rowCount() + ")");
        }
    }

    private void checkEvent(int eventIndex) {
        if (eventIndex < 0 || eventIndex >= eventCount()) {
            throw new IndexOutOfBoundsException(
                    "Event " + eventIndex + " outside [0, " + eventCount() + ")");
        }
    }
}
