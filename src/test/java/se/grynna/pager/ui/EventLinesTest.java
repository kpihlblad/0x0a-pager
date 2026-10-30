package se.grynna.pager.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.LogEventMapper;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.Placeholder;
import se.grynna.pager.splunk.SearchHit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EventLinesTest {

    private static final Instant TIME = Instant.parse("2023-01-01T12:00:00Z");

    @Test
    @DisplayName("first, middle and last row of a multi-line event give the same event-level line")
    void eventOfAnyRowOfMultilineEvent() {
        List<LoadedLogLine> rows = rows(eventLine("first\nsecond\nthird"));
        IntFunction<Optional<LogLine>> all = loadedRows(rows, 0, rows.size());

        for (int row = 0; row < rows.size(); row++) {
            LoadedLogLine event = EventLines.eventOf(all, row).orElseThrow();
            assertThat(event.getMessage()).as("row %s", row).isEqualTo("first\nsecond\nthird");
            assertThat(event.isContinuation()).isFalse();
            assertThat(event.getRaw()).isEqualTo("raw");
            assertThat(event.getMsgType()).isEqualTo(MsgType.JSON);
            assertThat(event.getLevel()).isEqualTo(Level.WARNING);
            assertThat(event.getEventMap()).containsEntry("_cd", "cb");
        }
    }

    @Test
    @DisplayName("first row carries the event map and identity; event-level line equals the first row's identity")
    void eventOfTakesFieldsFromFirstRow() {
        List<LoadedLogLine> rows = rows(eventLine("a\nb"));
        LoadedLogLine event = EventLines.eventOf(loadedRows(rows, 0, rows.size()), 1).orElseThrow();

        assertThat(SearchHit.from(event)).isEqualTo(SearchHit.from(rows.getFirst()));
        assertThat(event.getSplunkTime()).isEqualTo(TIME);
        assertThat(event.getDisplayTime()).isEqualTo(TIME.plusSeconds(1));
        assertThat(event.getEventMap()).isEqualTo(rows.getFirst().getEventMap());
    }

    @Test
    @DisplayName("the event-level line keeps the activity of the event's first row")
    void eventOfKeepsActivity() {
        Activity activity = new Activity("node-1", "pod-0", "worker-3", null, null);
        LoadedLogLine event = eventLine("first\nsecond");
        event.setActivity(activity);
        List<LoadedLogLine> rows = rows(event);

        for (int row = 0; row < rows.size(); row++) {
            LoadedLogLine eventLine = EventLines.eventOf(loadedRows(rows, 0, rows.size()), row).orElseThrow();
            assertThat(eventLine.getActivity()).as("row %s", row).isEqualTo(activity);
        }
    }

    @Test
    @DisplayName("single-line event is returned as its own line")
    void eventOfSingleLineEvent() {
        List<LoadedLogLine> rows = rows(eventLine("only"), eventLine("next"));
        LoadedLogLine event = EventLines.eventOf(loadedRows(rows, 0, rows.size()), 0).orElseThrow();

        assertThat(event.getMessage()).isEqualTo("only");
        assertThat(event.isContinuation()).isFalse();
    }

    @Test
    @DisplayName("walk stops at the next event's first row")
    void eventOfDoesNotRunIntoNextEvent() {
        List<LoadedLogLine> rows = rows(eventLine("a\nb"), eventLine("c\nd"));
        LoadedLogLine event = EventLines.eventOf(loadedRows(rows, 0, rows.size()), 1).orElseThrow();

        assertThat(event.getMessage()).isEqualTo("a\nb");
    }

    @Test
    @DisplayName("at a loaded-segment edge the loaded part of the event is used")
    void eventOfAtSegmentEdge() {
        List<LoadedLogLine> rows = rows(eventLine("first\nsecond\nthird\nfourth"));

        IntFunction<Optional<LogLine>> startLoaded = loadedRows(rows, 1, rows.size());
        assertThat(EventLines.eventOf(startLoaded, 1).orElseThrow().getMessage()).isEqualTo("second\nthird\nfourth");
        assertThat(EventLines.eventOf(startLoaded, 2).orElseThrow().getMessage()).isEqualTo("second\nthird\nfourth");

        IntFunction<Optional<LogLine>> endLoaded = loadedRows(rows, 0, 2);
        assertThat(EventLines.eventOf(endLoaded, 1).orElseThrow().getMessage()).isEqualTo("first\nsecond");
    }

    @Test
    @DisplayName("a non-loaded neighbour ends the walk; the row itself must be loaded")
    void eventOfNonLoadedNeighbour() {
        List<LoadedLogLine> rows = rows(eventLine("first\nsecond\nthird"));
        IntFunction<Optional<LogLine>> withPlaceholder = row -> row == 2
                ? Optional.of(Placeholder.from(row, ""))
                : loadedRows(rows, 0, rows.size()).apply(row);

        assertThat(EventLines.eventOf(withPlaceholder, 1).orElseThrow().getMessage()).isEqualTo("first\nsecond");
        assertThat(EventLines.eventOf(withPlaceholder, 2)).isEmpty();
    }

    @Test
    @DisplayName("placeholder or boundary rows give empty")
    void eventOfNotLoadedRow() {
        IntFunction<Optional<LogLine>> placeholder = row -> Optional.of(Placeholder.from(row, ""));
        assertThat(EventLines.eventOf(placeholder, 3)).isEmpty();
        assertThat(EventLines.eventOf(row -> Optional.empty(), 3)).isEmpty();
    }

    @Test
    @DisplayName("split rows of one event share identity and round-trip through splitLines")
    void roundTrip() {
        LoadedLogLine original = eventLine("first\n\nsecond line\nthird");
        List<LoadedLogLine> rows = LogEventMapper.splitLines(original);
        IntFunction<Optional<LogLine>> all = loadedRows(rows, 0, rows.size());

        for (int row = 0; row < rows.size(); row++) {
            assertThat(SearchHit.from(rows.get(row))).isEqualTo(SearchHit.from(rows.getFirst()));
        }

        LoadedLogLine event = EventLines.eventOf(all, 2).orElseThrow();
        List<LoadedLogLine> again = LogEventMapper.splitLines(event);

        assertThat(again).hasSameSizeAs(rows);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(again.get(i).getMessage()).as("message %s", i).isEqualTo(rows.get(i).getMessage());
            assertThat(again.get(i).isContinuation()).as("continuation %s", i)
                    .isEqualTo(rows.get(i).isContinuation());
            assertThat(SearchHit.from(again.get(i))).as("identity %s", i)
                    .isEqualTo(SearchHit.from(rows.get(i)));
        }
    }

    private static IntFunction<Optional<LogLine>> loadedRows(List<LoadedLogLine> rows, int from, int to) {
        return row -> row >= from && row < to && row < rows.size()
                ? Optional.of(rows.get(row))
                : Optional.empty();
    }

    private static List<LoadedLogLine> rows(LoadedLogLine... events) {
        return Stream.of(events).flatMap(event -> LogEventMapper.splitLines(event).stream()).toList();
    }

    private static LoadedLogLine eventLine(String message) {
        var line = new LoadedLogLine(MsgType.JSON, null, 0, "cb", "bkt", TIME, "raw", TIME, TIME.plusSeconds(1),
                message, false, Level.WARNING);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cb", "_bkt", "bkt")));
        return line;
    }
}
