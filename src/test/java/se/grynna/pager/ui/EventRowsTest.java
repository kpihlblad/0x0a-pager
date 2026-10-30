package se.grynna.pager.ui;

import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.LogEventMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRowsTest {

    private static final Instant TIME = Instant.parse("2023-01-01T12:00:00Z");

    @Test
    @DisplayName("first-line mode: one row per event showing the first line, the rest counted as hidden")
    void firstLineMode() {
        EventRows rows = EventRows.of(List.of(eventLine("single"), eventLine("first\nsecond\nthird"),
                eventLine("a\nb")), true);

        assertThat(rows.firstLineOnly()).isTrue();
        assertThat(rows.rowCount()).isEqualTo(3);
        assertThat(rows.rowsOf(1)).isEqualTo(1);
        assertThat(rows.hiddenLines(0)).isZero();
        assertThat(rows.hiddenLines(1)).isEqualTo(2);
        assertThat(rows.hiddenLines(2)).isEqualTo(1);
        assertThat(rows.eventAt(2)).isEqualTo(2);
        assertThat(rows.line(1).getMessage()).isEqualTo("first");
        assertThat(rows.line(1).isContinuation()).isFalse();
        assertThat(rows.offsetWithin(1)).isZero();
    }

    @Test
    @DisplayName("prefix sums cover a mix of one-line and multi-line events")
    void prefixSums() {
        EventRows rows = EventRows.of(List.of(eventLine("single"), eventLine("first\nsecond\nthird"),
                eventLine("another"), eventLine("a\nb")));

        assertThat(rows.eventCount()).isEqualTo(4);
        assertThat(rows.rowCount()).isEqualTo(7);
        assertThat(rows.rowsOf(0)).isEqualTo(1);
        assertThat(rows.rowsOf(1)).isEqualTo(3);
        assertThat(rows.rowsOf(2)).isEqualTo(1);
        assertThat(rows.rowsOf(3)).isEqualTo(2);

        assertThat(rows.rowOf(0, 0)).isZero();
        assertThat(rows.rowOf(1, 0)).isEqualTo(1);
        assertThat(rows.rowOf(2, 0)).isEqualTo(4);
        assertThat(rows.rowOf(3, 0)).isEqualTo(5);

        assertThat(rows.eventAt(0)).isZero();
        assertThat(rows.eventAt(1)).isEqualTo(1);
        assertThat(rows.eventAt(3)).isEqualTo(1);
        assertThat(rows.eventAt(4)).isEqualTo(2);
        assertThat(rows.eventAt(5)).isEqualTo(3);
        assertThat(rows.eventAt(6)).isEqualTo(3);

        assertThat(rows.offsetWithin(0)).isZero();
        assertThat(rows.offsetWithin(3)).isEqualTo(2);
        assertThat(rows.offsetWithin(4)).isZero();
        assertThat(rows.offsetWithin(6)).isEqualTo(1);
    }

    @Test
    @DisplayName("eventAt/offsetWithin and rowOf round-trip for every row")
    void rowEventRoundTrip() {
        EventRows rows = EventRows.of(List.of(eventLine("single"), eventLine("first\nsecond\nthird"),
                eventLine("another"), eventLine("a\nb")));

        for (int row = 0; row < rows.rowCount(); row++) {
            int eventIndex = rows.eventAt(row);
            assertThat(rows.rowOf(eventIndex, rows.offsetWithin(row))).isEqualTo(row);
        }

        for (int eventIndex = 0; eventIndex < rows.eventCount(); eventIndex++) {
            for (int k = 0; k < rows.rowsOf(eventIndex); k++) {
                int row = rows.rowOf(eventIndex, k);
                assertThat(rows.eventAt(row)).isEqualTo(eventIndex);
                assertThat(rows.offsetWithin(row)).isEqualTo(k);
            }
        }
    }

    @Test
    @DisplayName("rowOf clamps k into the event's rows")
    void rowOfClamps() {
        EventRows rows = EventRows.of(List.of(eventLine("a\nb\nc")));

        assertThat(rows.rowOf(0, -100)).isZero();
        assertThat(rows.rowOf(0, -1)).isZero();
        assertThat(rows.rowOf(0, 0)).isZero();
        assertThat(rows.rowOf(0, 2)).isEqualTo(2);
        assertThat(rows.rowOf(0, 3)).isEqualTo(2);
        assertThat(rows.rowOf(0, 100)).isEqualTo(2);
    }

    @Test
    @DisplayName("rows carry their own line number and continuation flag")
    void continuationRows() {
        EventRows rows = EventRows.of(List.of(eventLine("first\nsecond\nthird")));

        LoadedLogLine first = rows.line(0);
        LoadedLogLine second = rows.line(1);
        LoadedLogLine third = rows.line(2);

        assertThat(first.getLineNumber()).isZero();
        assertThat(first.getMessage()).isEqualTo("first");
        assertThat(first.isContinuation()).isFalse();

        assertThat(second.getLineNumber()).isEqualTo(1);
        assertThat(second.getMessage()).isEqualTo("second");
        assertThat(second.isContinuation()).isTrue();

        assertThat(third.getLineNumber()).isEqualTo(2);
        assertThat(third.getMessage()).isEqualTo("third");
        assertThat(third.isContinuation()).isTrue();
    }

    @Test
    @DisplayName("split rows match LogEventMapper.mapEvent field by field, apart from the line number")
    void matchesMapEventRows() {
        Event event = splunkEvent(
                "{\"@timestamp\":\"2023-01-01T12:00:00Z\",\"message\":\"first\",\"exception\":\"second\",\"stacktrace\":\"third\"}");
        LogEventMapper mapper = new LogEventMapper();

        List<LoadedLogLine> mapped = mapper.mapEvent(event).toList();
        EventRows rows = EventRows.of(List.of(mapper.mapEventSingleLine(event)));

        assertThat(rows.rowCount()).isEqualTo(mapped.size());
        assertThat(rows.rowCount()).isEqualTo(3);
        for (int row = 0; row < rows.rowCount(); row++) {
            LoadedLogLine expected = mapped.get(row);
            LoadedLogLine actual = rows.line(row);
            assertThat(actual.getLineNumber()).isEqualTo(row);
            assertSameFieldsApartFromLineNumber(expected, actual);
        }
    }

    @Test
    @DisplayName("an event without message lines maps to the mapper's single raw fallback row")
    void matchesMapEventFallbackRow() {
        Event event = splunkEvent("{\"host\":\"example\"}");
        LogEventMapper mapper = new LogEventMapper();

        List<LoadedLogLine> mapped = mapper.mapEvent(event).toList();
        EventRows rows = EventRows.of(List.of(mapper.mapEventSingleLine(event)));

        assertThat(mapped).hasSize(1);
        assertThat(rows.rowCount()).isEqualTo(1);
        LoadedLogLine actual = rows.line(0);
        assertThat(actual.getMessage()).isEqualTo(event.get("_raw"));
        assertThat(actual.getMsgType()).isEqualTo(MsgType.RAW);
        assertSameFieldsApartFromLineNumber(mapped.getFirst(), actual);
    }

    @Test
    @DisplayName("every row handed out keeps the event's activity, including continuation rows")
    void rowsKeepActivity() {
        Activity activity = new Activity("node-1", "pod-0", "worker-3", null, null);
        LoadedLogLine event = eventLine("first\nsecond\nthird");
        event.setActivity(activity);

        EventRows rows = EventRows.of(List.of(event));

        assertThat(rows.rowCount()).isEqualTo(3);
        for (int row = 0; row < rows.rowCount(); row++) {
            assertThat(rows.line(row).getActivity()).as("row %s", row).isEqualTo(activity);
        }
    }

    @Test
    @DisplayName("first-line mode and the empty fallback row keep the event's activity")
    void firstLineAndEmptyRowsKeepActivity() {
        Activity activity = new Activity(null, "pod-1", null, null, "idx:src");
        LoadedLogLine multi = eventLine("a\nb");
        multi.setActivity(activity);
        LoadedLogLine nullMessage = eventLine(null);
        nullMessage.setActivity(activity);

        EventRows firstLine = EventRows.of(List.of(multi), true);
        EventRows empty = EventRows.of(List.of(nullMessage));

        assertThat(firstLine.line(0).getActivity()).isEqualTo(activity);
        assertThat(empty.line(0).getActivity()).isEqualTo(activity);
    }

    @Test
    @DisplayName("a null message is one empty row")
    void nullMessageIsOneEmptyRow() {
        EventRows rows = EventRows.of(List.of(eventLine(null)));

        assertThat(rows.eventCount()).isEqualTo(1);
        assertThat(rows.rowCount()).isEqualTo(1);
        assertThat(rows.line(0).getMessage()).isEmpty();
        assertThat(rows.line(0).isContinuation()).isFalse();
    }

    @Test
    @DisplayName("line counts follow String.lines(), including trailing terminators")
    void lineCountsFollowStringLines() {
        for (String message : List.of("", "one", "a\nb", "a\nb\n", "a\r\nb", "a\rb", "\n", "a\n\nb")) {
            EventRows rows = EventRows.of(List.of(eventLine(message)));
            int expected = (int) message.lines().count();
            assertThat(rows.rowsOf(0)).as("rows of %s", message).isEqualTo(Math.max(1, expected));
        }
        // An empty message has no lines; the mapper's fallback row shows the raw text.
        assertThat(EventRows.of(List.of(eventLine(""))).line(0).getMessage()).isEqualTo("raw");
    }

    @Test
    @DisplayName("empty() has no events and no rows")
    void emptyRows() {
        EventRows rows = EventRows.empty();

        assertThat(rows.eventCount()).isZero();
        assertThat(rows.rowCount()).isZero();
        assertThatThrownBy(() -> rows.eventAt(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.line(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.rowsOf(0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("indices outside the model are rejected")
    void rejectsOutOfRangeIndices() {
        EventRows rows = EventRows.of(List.of(eventLine("a\nb")));

        assertThatThrownBy(() -> rows.eventAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.eventAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.offsetWithin(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.line(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.line(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.rowsOf(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.rowsOf(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.rowOf(-1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.rowOf(1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> rows.event(1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("inputs are never mutated and handed-out lines are copies")
    void inputsNotMutated() {
        LoadedLogLine input = eventLine("first\nsecond");
        EventRows rows = EventRows.of(List.of(input));

        LoadedLogLine row = rows.line(0);
        row.setMessage("changed");
        row.setLineNumber(99);
        row.setContinuation(true);

        assertThat(input.getMessage()).isEqualTo("first\nsecond");
        assertThat(input.getLineNumber()).isZero();
        assertThat(input.isContinuation()).isFalse();
        assertThat(rows.line(0).getMessage()).isEqualTo("first");
        assertThat(rows.line(0).getLineNumber()).isZero();
        assertThat(rows.line(0).isContinuation()).isFalse();
        assertThat(rows.line(1).getMessage()).isEqualTo("second");
        assertThat(rows.event(0)).isSameAs(input);
    }

    @Test
    @DisplayName("ten thousand events build and serve rows")
    void tenThousandEvents() {
        List<LoadedLogLine> events = IntStream.range(0, 10_000)
                .mapToObj(i -> eventLine(i % 2 == 0 ? "event " + i : "event " + i + "\nb\nc")).toList();

        EventRows rows = EventRows.of(events);

        assertThat(rows.eventCount()).isEqualTo(10_000);
        assertThat(rows.rowCount()).isEqualTo(20_000);
        assertThat(rows.line(0).getMessage()).isEqualTo("event 0");
        assertThat(rows.line(19_999).getMessage()).isEqualTo("c");
        assertThat(rows.eventAt(19_999)).isEqualTo(9_999);
    }

    private static LoadedLogLine eventLine(String message) {
        var line = new LoadedLogLine(MsgType.JSON, null, 0, "cb", "bkt", TIME, "raw", TIME, TIME, message, false,
                Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cb", "_bkt", "bkt")));
        return line;
    }

    private static Event splunkEvent(String raw) {
        return TestEvents.event(Map.of("_time", TIME.toString(), "_raw", raw, "_cd", "cb1", "_bkt", "bkt1",
                "_indextime", String.valueOf(TIME.getEpochSecond())));
    }

    private static void assertSameFieldsApartFromLineNumber(LoadedLogLine expected, LoadedLogLine actual) {
        assertThat(actual.getMsgType()).isEqualTo(expected.getMsgType());
        assertThat(actual.getSegmentId()).isEqualTo(expected.getSegmentId());
        assertThat(actual.getCb()).isEqualTo(expected.getCb());
        assertThat(actual.getBkt()).isEqualTo(expected.getBkt());
        assertThat(actual.getTime()).isEqualTo(expected.getTime());
        assertThat(actual.getRaw()).isEqualTo(expected.getRaw());
        assertThat(actual.getIndextime()).isEqualTo(expected.getIndextime());
        assertThat(actual.getMsgTime()).isEqualTo(expected.getMsgTime());
        assertThat(actual.getMessage()).isEqualTo(expected.getMessage());
        assertThat(actual.isContinuation()).isEqualTo(expected.isContinuation());
        assertThat(actual.getLevel()).isEqualTo(expected.getLevel());
        assertThat(actual.getEventMap()).isEqualTo(expected.getEventMap());
    }
}
