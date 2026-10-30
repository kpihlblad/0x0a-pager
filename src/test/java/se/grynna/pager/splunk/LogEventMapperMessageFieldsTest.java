package se.grynna.pager.splunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogEventMapperMessageFieldsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }

    private static Event event(String raw) {
        return TestEvents.event(Map.of("_time", "2023-01-01T12:00:00Z", "_raw", raw, "_cd", "cb1", "_bkt", "bkt1",
                "_indextime", "1672574400"));
    }

    @Test
    void defaultListIsTheOriginalFieldsFollowedByTheErrorFields() {
        assertThat(LogEventMapper.DEFAULT_MESSAGE_FIELDS).containsExactly("msg", "log", "message",
                "events.RenderedMessage", "error", "stacktrace", "stack_trace", "stackTrace", "exception", "trace");
        assertThat(new LogEventMapper().getMessageFields()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
    }

    @Test
    void defaultFormatJoinsPresentFieldsInOrder() throws Exception {
        var mapper = new LogEventMapper();
        JsonNode node = json("""
                {"trace":"t", "msg":"hello", "events":{"RenderedMessage":"rendered"}, "error":"", "exception":"e",
                 "log":null, "stackTrace":"st"}""");

        assertThat(mapper.format(node)).isEqualTo("hello\nrendered\n\nst\ne\nt");
    }

    @Test
    void customListUsesOnlyItsFieldsInItsOrder() throws Exception {
        var mapper = new LogEventMapper();
        mapper.setMessageFields(List.of("title", "msg"));
        JsonNode node = json("""
                {"msg":"hello", "title":"first", "log":"ignored", "trace":"ignored"}""");

        assertThat(mapper.format(node)).isEqualTo("first\nhello");
        assertThat(mapper.getMessageFields()).containsExactly("title", "msg");
    }

    @Test
    void nestedDotPathIsResolved() throws Exception {
        var mapper = new LogEventMapper();
        mapper.setMessageFields(List.of("payload.text", "/meta/note"));
        JsonNode node = json("""
                {"payload":{"text":"nested"}, "meta":{"note":"pointer"}, "msg":"ignored"}""");

        assertThat(mapper.format(node)).isEqualTo("nested\npointer");
    }

    @Test
    void nullResetsToTheDefaultFields() throws Exception {
        var mapper = new LogEventMapper();
        mapper.setMessageFields(List.of("title"));
        mapper.setMessageFields(null);

        assertThat(mapper.getMessageFields()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
        assertThat(mapper.format(json("{\"msg\":\"back\"}"))).isEqualTo("back");
    }

    @Test
    void emptyListGivesNoMessageAndJsonEventFallsBackToItsRawRow() {
        var mapper = new LogEventMapper();
        mapper.setMessageFields(List.of());
        String raw = "{\"@timestamp\":\"2023-01-01T12:00:00Z\",\"msg\":\"not used\"}";

        LoadedLogLine line = mapper.mapEventSingleLine(event(raw));
        assertThat(line.getMessage()).isEmpty();

        var rows = LogEventMapper.splitLines(line);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getMsgType()).isEqualTo(LoadedLogLine.MsgType.RAW);
        assertThat(rows.getFirst().getMessage()).isEqualTo(raw);
        assertThat(rows.getFirst().isContinuation()).isFalse();
    }

    @Test
    void mappingUsesTheFieldsSetOnTheMapper() {
        var mapper = new LogEventMapper();
        mapper.setMessageFields(List.of("msg"));
        String raw = "{\"@timestamp\":\"2023-01-01T12:00:00Z\",\"msg\":\"shown\",\"log\":\"hidden\"}";

        assertThat(mapper.mapEvent(event(raw)).toList()).singleElement()
                .extracting(LoadedLogLine::getMessage).isEqualTo("shown");
    }
}
