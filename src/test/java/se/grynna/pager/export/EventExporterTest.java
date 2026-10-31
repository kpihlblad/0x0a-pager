package se.grynna.pager.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.LogEventMapper;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;
import se.grynna.pager.ui.DecoratedLogLine;
import se.grynna.pager.ui.clip.Copier;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.awt.datatransfer.DataFlavor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class EventExporterTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    private static final ExportOptions OPTIONS = new ExportOptions(null, ZONE);
    private static final Instant T1 = Instant.parse("2017-03-01T10:00:00.123456789Z");
    private static final Instant T2 = Instant.parse("2017-03-01T10:00:01.000000002Z");

    @BeforeAll
    static void initFormatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @Test
    void textEqualsWhatCtrlCCopiesForTheSameRows() throws Exception {
        var events = List.of(
                ExportEvent.of(event(T1, Level.ERROR, "first line\nsecond line", "{\"a\":1}", Map.of("host", "h1"))),
                ExportEvent.of(event(T2, Level.INFO, "single", "single", Map.of())));

        var rows = new java.util.ArrayList<LoadedLogLine>();
        for (LoadedLogLine eventLine : List.of(
                event(T1, Level.ERROR, "first line\nsecond line", "{\"a\":1}", Map.of("host", "h1")),
                event(T2, Level.INFO, "single", "single", Map.of()))) {
            rows.addAll(LogEventMapper.splitLines(eventLine));
        }
        List<String> copied = rows.stream().map(row -> new DecoratedLogLine(row).formattedLine()).toList();
        String expected = (String) Copier.transferable(copied)
                .getTransferData(new DataFlavor("text/plain;class=java.lang.String"));

        assertThat(EventExporter.exportText(events, ExportFormat.TEXT, OPTIONS)).isEqualTo(expected);
    }

    @Test
    void textIndentsContinuationLinesUnderTheMessage() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "a\nb", "a\nb", Map.of())));

        String text = EventExporter.exportText(events, ExportFormat.TEXT, OPTIONS);
        String[] lines = text.split("\n");

        assertThat(lines).hasSize(2);
        assertThat(lines[0]).endsWith("a").contains(DateTimeFormatterManager.formatLogTimestamp(T1))
                .doesNotContain("123456789");
        assertThat(lines[1]).endsWith("b").startsWith(" ".repeat(DecoratedLogLine.getPreambleLength()));
    }

    @Test
    void messageWritesEveryMessageLine() {
        var events = List.of(
                ExportEvent.of(event(T1, Level.INFO, "a\nb", "raw", Map.of())),
                ExportEvent.of(event(T2, Level.WARNING, "c", "raw", Map.of())));

        assertThat(EventExporter.exportText(events, ExportFormat.MESSAGE, OPTIONS)).isEqualTo("a\nb\nc\n");
    }

    @Test
    void rawKeepsNewlinesInsideOneEntry() {
        var events = List.of(
                ExportEvent.of(event(T1, Level.INFO, "x", "line one\nline two", Map.of())),
                ExportEvent.of(event(T2, Level.INFO, "y", "plain", Map.of())));

        assertThat(EventExporter.exportText(events, ExportFormat.RAW, OPTIONS))
                .isEqualTo("line one\nline two\nplain\n");
    }

    @Test
    void jsonlWritesOneObjectPerEventWithNanosecondUtcTime() throws Exception {
        var fields = new TreeMap<String, String>();
        fields.put("host", "h1");
        fields.put("msg", "quote \" <tag> & \n newline");
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "x", "x", fields)),
                ExportEvent.of(event(T2, Level.INFO, "y", "y", Map.of())));

        String text = EventExporter.exportText(events, ExportFormat.JSONL, OPTIONS);
        String[] lines = text.split("\n");

        assertThat(lines).hasSize(2);
        JsonNode first = new ObjectMapper().readTree(lines[0]);
        assertThat(first.get("host").asText()).isEqualTo("h1");
        assertThat(first.get("msg").asText()).isEqualTo("quote \" <tag> & \n newline");
        assertThat(first.get("_time").asText()).isEqualTo("2017-03-01T10:00:00.123456789Z");
        JsonNode second = new ObjectMapper().readTree(lines[1]);
        assertThat(second.get("_time").asText()).isEqualTo("2017-03-01T10:00:01.000000002Z");
    }

    @Test
    void csvHasHeaderAndRfc4180Quoting() {
        var events = List.of(
                ExportEvent.of(event(T1, Level.ERROR, "a,\"b\"\nc", "raw", Map.of())),
                ExportEvent.of(event(T2, Level.INFO, "plain", "raw", Map.of())));

        String csv = EventExporter.exportText(events, ExportFormat.CSV, OPTIONS);

        assertThat(csv).isEqualTo("time,level,message\r\n"
                + "2017-03-01T11:00:00.123456789+01:00,ERROR,\"a,\"\"b\"\"\nc\"\r\n"
                + "2017-03-01T11:00:01.000000002+01:00,INFO,plain\r\n");
    }

    @Test
    void csvKeepsUnicodeAndDoublesQuotes() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "Ünï ✓ 漢 \"q\"", "raw", Map.of())));

        String csv = EventExporter.exportText(events, ExportFormat.CSV, OPTIONS);

        assertThat(csv).endsWith(",INFO,\"Ünï ✓ 漢 \"\"q\"\"\"\r\n");
    }

    @Test
    void byteOrderMarkOnlyForCsv() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "x", "x", Map.of())));

        for (ExportFormat format : ExportFormat.values()) {
            byte[] bytes = EventExporter.export(events, format, OPTIONS);
            String text = EventExporter.exportText(events, format, OPTIONS);
            boolean bom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF;

            assertThat(bom).as("BOM for %s", format).isEqualTo(format == ExportFormat.CSV);
            byte[] withoutBom = format == ExportFormat.CSV
                    ? java.util.Arrays.copyOfRange(bytes, 3, bytes.length)
                    : bytes;
            assertThat(withoutBom).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void htmlEscapesAndColoursRows() {
        var events = List.of(
                ExportEvent.of(event(T1, Level.ERROR, "a < b & \"c\" 'd' ü", "raw", Map.of())),
                ExportEvent.of(event(T2, Level.INFO, "plain", "raw", Map.of())));

        String html = EventExporter.exportText(events, ExportFormat.HTML, OPTIONS);

        assertThat(html).startsWith("<table>").endsWith("</table>\n");
        assertThat(html).contains("a &lt; b &amp; &quot;c&quot; 'd' &uuml;");
        assertThat(html).doesNotContain("a < b");
        assertThat(html).contains("<tr style=\"color:#c02505\">");
        assertThat(html).contains("<td>2017-03-01T11:00:00.123456789+01:00</td><td>ERROR</td>");
        assertThat(html).contains("<tr><td>2017-03-01T11:00:01.000000002+01:00</td><td>INFO</td>");
    }

    @Test
    void htmlBreaksMultiLineMessages() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "one\ntwo", "raw", Map.of())));

        assertThat(EventExporter.exportText(events, ExportFormat.HTML, OPTIONS)).contains("one<br>two</td>");
    }

    @Test
    void htmlMarksSearchTermCaseInsensitivelyInsideEscapedText() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "Tom & Jerry <TOM>", "raw", Map.of())));

        String html = EventExporter.exportText(events, ExportFormat.HTML,
                new ExportOptions("tom", ZONE));

        assertThat(html).contains("<mark>Tom</mark> &amp; Jerry &lt;<mark>TOM</mark>&gt;");
    }

    @Test
    void highlightDoesNotBreakEntities() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "Tom & Jerry", "raw", Map.of())));

        String html = EventExporter.exportText(events, ExportFormat.HTML, new ExportOptions("amp", ZONE));

        assertThat(html).contains("Tom &amp; Jerry").doesNotContain("<mark>amp");
    }

    @Test
    void highlightMarksEscapedSearchCharacters() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "a < b", "raw", Map.of())));

        String html = EventExporter.exportText(events, ExportFormat.HTML, new ExportOptions("<", ZONE));

        assertThat(html).contains("a <mark>&lt;</mark> b");
    }

    @Test
    void emptyInputGivesHeaderOnlyCsvAndEmptyOtherFormats() {
        assertThat(EventExporter.exportText(List.of(), ExportFormat.CSV, OPTIONS))
                .isEqualTo("time,level,message\r\n");
        for (ExportFormat format : List.of(ExportFormat.TEXT, ExportFormat.MESSAGE, ExportFormat.RAW,
                ExportFormat.JSONL, ExportFormat.HTML)) {
            assertThat(EventExporter.exportText(List.of(), format, OPTIONS)).as(format.name()).isEmpty();
        }
    }

    @Test
    void nanosAreKeptInTheDataFormatsWhileTheCopiedTextShowsMillisAsTheViewDoes() {
        var events = List.of(ExportEvent.of(event(T1, Level.INFO, "x", "x", Map.of())));

        // TEXT is the log view's copy format: the time column as drawn (rounded to millis) ...
        assertThat(EventExporter.exportText(events, ExportFormat.TEXT, OPTIONS))
                .contains(DateTimeFormatterManager.formatLogTimestamp(T1))
                .doesNotContain("123456789");
        // ... while the data formats keep the full nanosecond time column.
        assertThat(EventExporter.exportText(events, ExportFormat.CSV, OPTIONS)).contains("00.123456789+01:00");
        assertThat(EventExporter.exportText(events, ExportFormat.JSONL, OPTIONS)).contains("123456789Z");
        assertThat(EventExporter.exportText(events, ExportFormat.HTML, OPTIONS)).contains("00.123456789+01:00");
    }

    @Test
    void factorySplitsMessageAndCopiesFields() {
        var fields = new TreeMap<String, String>();
        fields.put("b", "2");
        fields.put("a", "1");
        var source = event(T1, Level.WARNING, "one\ntwo", "raw", fields);

        ExportEvent exported = ExportEvent.of(source);

        assertThat(exported.displayTime()).isEqualTo(T1);
        assertThat(exported.splunkTime()).isEqualTo(T1);
        assertThat(exported.level()).isEqualTo(Level.WARNING);
        assertThat(exported.messageLines()).containsExactly("one", "two");
        assertThat(exported.raw()).isEqualTo("raw");
        assertThat(exported.eventMap()).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
        assertThat(exported.preamble()).hasSize(DecoratedLogLine.getPreambleLength())
                .contains(DateTimeFormatterManager.formatLogTimestamp(T1));
    }

    @Test
    void optionsRequireZone() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> new ExportOptions(null, null));
    }

    private static LoadedLogLine event(Instant time, Level level, String message, String raw,
            Map<String, String> fields) {
        var line = new LoadedLogLine(MsgType.JSON, "seg", 0, "cb", "bkt", time, raw, time, time, message, false,
                level);
        line.setEvent(new TreeMap<>(fields));
        return line;
    }
}
