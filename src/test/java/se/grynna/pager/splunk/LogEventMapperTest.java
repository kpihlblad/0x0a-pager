package se.grynna.pager.splunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class LogEventMapperTest {

    @org.junit.jupiter.api.Test
    public void mapEventShouldMapValidEventToLoadedLogLines() {
        Event event = TestEvents.event(Map.of("_time", "2023-01-01T12:00:00Z", "_raw",
                "{\"@timestamp\":\"2023-01-01T12:00:00Z\",\"message\":\"Test message\"}", "_cd", "cb1", "_bkt", "bkt1",
                "_indextime", "1672574400"));

        LogEventMapper mapper = new LogEventMapper();
        var result = mapper.mapEvent(event).toList();

        assertEquals(1, result.size());
        LoadedLogLine logLine = result.getFirst();
        assertEquals("cb1", logLine.getCb());
        assertEquals("bkt1", logLine.getBkt());
        assertEquals("Test message", logLine.getMessage());
        assertEquals(Level.INFO, logLine.getLevel());
    }

    @org.junit.jupiter.api.Test
    public void mapEventShouldHandleInvalidJsonGracefully() {
        Event event = TestEvents.event(Map.of("_time", "2023-01-01T12:00:00Z", "_raw", "Invalid JSON", "_cd", "cb1",
                "_bkt", "bkt1", "_indextime", "1672574400"));

        LogEventMapper mapper = new LogEventMapper();
        var result = mapper.mapEvent(event).toList();

        assertEquals(1, result.size());
        LoadedLogLine logLine = result.getFirst();
        assertEquals("Invalid JSON", logLine.getMessage());
        assertEquals(Level.INFO, logLine.getLevel());
    }

    @org.junit.jupiter.api.Test
    public void mapEventShouldSetErrorLevelForErrorTags() {
        Event event = TestEvents.event(Map.of("_time", "2023-01-01T12:00:00Z", "_raw",
                "{\"@timestamp\":\"2023-01-01T12:00:00Z\",\"message\":\"Error occurred\"}", "_cd", "cb1", "_bkt",
                "bkt1",
                "_indextime", "1672574400", "severity", "ERROR"));

        LogEventMapper mapper = new LogEventMapper();
        var result = mapper.mapEvent(event).toList();

        assertEquals(1, result.size());
        LoadedLogLine logLine = result.getFirst();
        assertEquals(Level.ERROR, logLine.getLevel());
    }

    @org.junit.jupiter.api.Test
    public void mapEventShouldSetWarningLevelForWarningTags() {
        Event event = TestEvents.event(Map.of("_time", "2023-01-01T12:00:00Z", "_raw",
                "{\"@timestamp\":\"2023-01-01T12:00:00Z\",\"message\":\"Warning occurred\"}", "_cd", "cb1", "_bkt",
                "bkt1", "_indextime", "1672574400", "severity", "WARN"));

        LogEventMapper mapper = new LogEventMapper();
        var result = mapper.mapEvent(event).toList();

        assertEquals(1, result.size());
        LoadedLogLine logLine = result.getFirst();
        assertEquals(Level.WARNING, logLine.getLevel());
    }

    @org.junit.jupiter.api.Test
    public void format_resolvesDeepDotPathRenderedMessage() throws Exception {
        ObjectMapper om = new ObjectMapper();
        LogEventMapper mapper = new LogEventMapper();

        String sample = "{\"log\":{\"event\":{\"action\":\"Button click\"}},\"host\":{\"ip\":\"apps.stg.example.com\"},\"source\":{\"ip\":\"192.168.234.47\"},\"user\":{\"id\":\"user-42(TEST CUSTOMER)\"},\"properties\":{\"application\":\"Accounts web\"},\"events\":{\"RenderedMessage\":\"Demo Shop; Orders page; Button click; opened details for next transaction in  transaction details/reserved amount modal.\"},\"url\":{\"full\":\"https://apps.stg.example.com/shop/orders/0b5c1d2e-0000-4000-8000-000000000001?dateFrom=2025-10-27&dateTo=2026-01-27&groupByDay=false\"}}";

        JsonNode json = om.readTree(sample);
        String out = mapper.format(json);
        String expected = "Demo Shop; Orders page; Button click; opened details for next transaction in  transaction details/reserved amount modal.";
        assertEquals(expected, out);
    }

    @org.junit.jupiter.api.Test
    public void format_emptyWhenNoFields() throws Exception {
        ObjectMapper om = new ObjectMapper();
        LogEventMapper mapper = new LogEventMapper();
        JsonNode json = om.readTree("{}");
        String out = mapper.format(json);
        assertEquals("", out);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("JSON stacktrace and exception fields are shown once")
    void mapperDoesNotDuplicateErrorFields() {
        // "stacktrace" and "exception" are in errorFields; they must not be duplicated
        // by also appearing in messageFields.
        Event event = createEvent(Instant.parse("2025-05-10T12:00:00Z"), "1:1", """
                {"@timestamp":"2025-05-10T12:00:00Z","message":"query failed",
                 "exception":"java.sql.SQLException: timeout","stacktrace":"at Dao.query(Dao.java:42)"}""");

        List<String> messages = new LogEventMapper().mapEvent(event).map(LoadedLogLine::getMessage).toList();

        assertThat(messages).containsExactlyInAnyOrder("query failed", "java.sql.SQLException: timeout",
                "at Dao.query(Dao.java:42)");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A search hit and the loaded log row of one raw Splunk event share the same Instant")
    void hitTimeEqualsLoadedLineTime() {
        // Splunk's JSON output: whole-second _time with an explicit offset, while the
        // log's own @timestamp carries milliseconds.
        Event event = TestEvents.event(Map.of("_time", "2015-07-29T19:25:10.000+00:00", "_raw",
                "{\"@timestamp\":\"2015-07-29T19:25:10.565Z\",\"message\":\"hello\"}", "_cd", "7:1234", "_bkt",
                "logsearch~7~ABC", "_indextime", "1438197910"));

        var mapper = new LogEventMapper();
        LoadedLogLine loaded = mapper.mapEvent(event).toList().getFirst();
        SearchHit hit = SearchHit.from(mapper.mapEventSingleLine(event));

        assertThat(hit.splunkTime()).isEqualTo(Instant.parse("2015-07-29T19:25:10Z"));
        assertThat(loaded.getSplunkTime()).isEqualTo(hit.splunkTime());
        assertThat(SearchHit.from(loaded)).isEqualTo(hit);
        assertThat(hit.displayTime()).isEqualTo(Instant.parse("2015-07-29T19:25:10.565Z"));
    }

    private static Event createEvent(Instant time, String cd, String raw) {
        return TestEvents.event(Map.of("_time", time.toString(), "_raw", raw, "_cd", cd, "_bkt", "bkt1", "_indextime",
                String.valueOf(time.getEpochSecond())));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a JSON object in _raw is a JSON event")
    void jsonObjectRawIsJsonMsgType() {
        Event event = createEvent(Instant.parse("2025-05-10T12:00:00Z"), "1:1",
                "{\"@timestamp\":\"2025-05-10T12:00:00Z\",\"message\":\"hello\"}");

        LoadedLogLine line = new LogEventMapper().mapEvent(event).toList().getFirst();

        assertThat(line.getMsgType()).isEqualTo(LoadedLogLine.MsgType.JSON);
        assertThat(line.getMessage()).isEqualTo("hello");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("plain text in _raw is a RAW event whose message is the raw text")
    void plainTextRawIsRawMsgType() {
        Event event = createEvent(Instant.parse("2025-05-10T12:00:00Z"), "1:1", "plain text line");

        LoadedLogLine line = new LogEventMapper().mapEvent(event).toList().getFirst();

        assertThat(line.getMsgType()).isEqualTo(LoadedLogLine.MsgType.RAW);
        assertThat(line.getMessage()).isEqualTo("plain text line");
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("JSON scalars and arrays in _raw are RAW events, mapped like plain text")
    void jsonScalarRawIsRawMsgType() {
        for (String raw : List.of("123", "true", "[1]", "\"quoted\"")) {
            Event event = createEvent(Instant.parse("2025-05-10T12:00:00Z"), "1:1", raw);

            LoadedLogLine line = new LogEventMapper().mapEvent(event).toList().getFirst();

            assertThat(line.getMsgType()).as(raw).isEqualTo(LoadedLogLine.MsgType.RAW);
            assertThat(line.getMessage()).as(raw).isEqualTo(raw);
            assertThat(line.getLevel()).as(raw).isEqualTo(Level.INFO);
            assertThat(line.getMsgTime()).as(raw).isEqualTo(Instant.parse("2025-05-10T12:00:00Z"));
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("every row of a multi-line event carries the same activity, equal to the extractor's")
    void allRowsOfEventShareActivity() throws Exception {
        String raw = "{\"@timestamp\":\"2025-05-10T12:00:00Z\",\"message\":\"first\\nsecond\\nthird\"}";
        Event event = createEvent(Instant.parse("2025-05-10T12:00:00Z"), "1:1", raw);
        event.putIfAbsent("host", "node-9");

        List<LoadedLogLine> rows = new LogEventMapper().mapEvent(event).toList();

        assertThat(rows).hasSize(3);
        JsonNode json = new ObjectMapper().readTree(raw);
        Activity expected = ActivityExtractor.extract(event, json, "first\nsecond\nthird", raw);
        assertThat(expected).isNotNull();
        for (LoadedLogLine row : rows) {
            assertThat(row.getActivity()).isNotNull();
            assertThat(row.getActivity()).isEqualTo(expected);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("single-line and empty-message fallback rows carry the event's activity (JSON host field)")
    void singleLineAndFallbackRowsCarryActivity() {
        Event event = createEvent(Instant.parse("2025-05-10T12:00:00Z"), "1:1", "{\"host\":\"example\"}");

        LogEventMapper mapper = new LogEventMapper();

        assertThat(mapper.mapEventSingleLine(event).getActivity().machine()).isEqualTo("example");
        List<LoadedLogLine> rows = mapper.mapEvent(event).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getActivity().machine()).isEqualTo("example");
    }
}
