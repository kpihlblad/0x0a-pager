package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Turns a row of the log (split rows, see {@link se.grynna.pager.splunk.LogEventMapper#splitLines})
 * back into the event-level line it belongs to.
 */
@Slf4j
public final class EventLines {

    private EventLines() {}

    /**
     * The event-level line of the event containing {@code row}.
     * <p>
     * Walks back over continuation rows to the event's first row and forward over the
     * following continuation rows, using only rows that are already loaded
     * ({@code loadedRow} must not trigger loads). The messages are joined with
     * {@code "\n"}; identity, times, {@code raw}, {@code eventMap}, msgType and level
     * come from the first row. If the event runs into a row that is not loaded, the
     * loaded part is used and that is logged at debug.
     *
     * @param loadedRow returns the line at a row if it is loaded, empty otherwise
     *                  (placeholders and boundaries count as not loaded)
     * @param row       the row to start from
     * @return the event-level line, or empty when {@code row} itself is not loaded
     */
    public static Optional<LoadedLogLine> eventOf(IntFunction<Optional<LogLine>> loadedRow, int row) {
        Optional<LoadedLogLine> start = loaded(loadedRow, row);
        if (start.isEmpty()) {
            return Optional.empty();
        }

        int firstRow = row;
        LoadedLogLine first = start.get();
        while (first.isContinuation()) {
            Optional<LoadedLogLine> previous = loaded(loadedRow, firstRow - 1);
            if (previous.isEmpty()) {
                log.debug("eventOf: row {} starts inside an event that is not fully loaded", row);
                break;
            }
            firstRow--;
            first = previous.get();
        }

        List<String> messages = new ArrayList<>();
        messages.add(first.getMessage());
        for (int next = firstRow + 1;; next++) {
            Optional<LoadedLogLine> line = loaded(loadedRow, next);
            if (line.isEmpty()) {
                log.debug("eventOf: row {} runs off the loaded lines at row {}", row, next);
                break;
            }
            if (!line.get().isContinuation()) {
                break;
            }
            messages.add(line.get().getMessage());
        }

        var event = new LoadedLogLine(first.getMsgType(), first.getSegmentId(), first.line(), first.getCb(),
                first.getBkt(), first.getSplunkTime(), first.getRaw(), first.getIndextime(), first.getDisplayTime(),
                String.join("\n", messages), false, first.getLevel());
        event.setEvent(first.getEventMap());
        event.setActivity(first.getActivity());
        return Optional.of(event);
    }

    private static Optional<LoadedLogLine> loaded(IntFunction<Optional<LogLine>> loadedRow, int row) {
        return loadedRow.apply(row).filter(LoadedLogLine.class::isInstance).map(LoadedLogLine.class::cast);
    }
}
