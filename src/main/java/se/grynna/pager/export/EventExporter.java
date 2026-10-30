package se.grynna.pager.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.text.StringEscapeUtils;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.utils.DarculaColors;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders events in each {@link ExportFormat}. Pure: no Swing, no I/O beyond the returned bytes.
 * <p>
 * Every text format ends each event's lines with {@code \n}, except CSV, which uses RFC 4180 CRLF rows.
 */
public final class EventExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String CSV_HEADER = "time,level,message\r\n";
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX");
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private EventExporter() {}

    /**
     * The file content as bytes: UTF-8, with a byte order mark for CSV only.
     */
    public static byte[] export(List<ExportEvent> events, ExportFormat format, ExportOptions options) {
        byte[] text = exportText(events, format, options).getBytes(StandardCharsets.UTF_8);
        if (format != ExportFormat.CSV) {
            return text;
        }
        byte[] withBom = new byte[UTF8_BOM.length + text.length];
        System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
        System.arraycopy(text, 0, withBom, UTF8_BOM.length, text.length);
        return withBom;
    }

    /**
     * The content as a string, without a byte order mark for any format.
     */
    public static String exportText(List<ExportEvent> events, ExportFormat format, ExportOptions options) {
        return switch (format) {
            case TEXT -> text(events);
            case MESSAGE -> message(events);
            case RAW -> raw(events);
            case JSONL -> jsonl(events);
            case CSV -> csv(events, options);
            case HTML -> html(events, options);
        };
    }

    /** The log view's copy format: preamble on the first line of an event, indented continuation lines. */
    private static String text(List<ExportEvent> events) {
        var sb = new StringBuilder();
        for (ExportEvent event : events) {
            String indent = " ".repeat(event.preamble().length());
            for (int i = 0; i < event.messageLines().size(); i++) {
                sb.append(i == 0 ? event.preamble() : indent).append(event.messageLines().get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String message(List<ExportEvent> events) {
        var sb = new StringBuilder();
        for (ExportEvent event : events) {
            for (String line : event.messageLines()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String raw(List<ExportEvent> events) {
        var sb = new StringBuilder();
        for (ExportEvent event : events) {
            sb.append(event.raw() == null ? "" : event.raw()).append('\n');
        }
        return sb.toString();
    }

    private static String jsonl(List<ExportEvent> events) {
        var sb = new StringBuilder();
        for (ExportEvent event : events) {
            Map<String, Object> object = new LinkedHashMap<>(event.eventMap());
            object.put("_time", event.splunkTime() == null ? null : UTC_TIME.format(event.splunkTime()));
            sb.append(toJson(object)).append('\n');
        }
        return sb.toString();
    }

    private static String toJson(Map<String, Object> object) {
        try {
            return JSON.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event as JSON", e);
        }
    }

    private static String csv(List<ExportEvent> events, ExportOptions options) {
        var sb = new StringBuilder(CSV_HEADER);
        for (ExportEvent event : events) {
            sb.append(csvField(time(event.displayTime(), options))).append(',')
                    .append(csvField(event.level() == null ? "" : event.level().name())).append(',')
                    .append(csvField(String.join("\n", event.messageLines()))).append("\r\n");
        }
        return sb.toString();
    }

    private static String csvField(String value) {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String html(List<ExportEvent> events, ExportOptions options) {
        if (events.isEmpty()) {
            return "";
        }
        Pattern term = highlightPattern(options.highlightTerm());
        var sb = new StringBuilder("<table>\n");
        for (ExportEvent event : events) {
            String style = levelColour(event.level());
            sb.append("<tr");
            if (style != null) {
                sb.append(" style=\"color:").append(style).append("\"");
            }
            sb.append("><td>").append(escape(time(event.displayTime(), options))).append("</td><td>")
                    .append(escape(event.level() == null ? "" : event.level().name())).append("</td><td>");
            for (int i = 0; i < event.messageLines().size(); i++) {
                if (i > 0) {
                    sb.append("<br>");
                }
                sb.append(highlighted(event.messageLines().get(i), term));
            }
            sb.append("</td></tr>\n");
        }
        return sb.append("</table>\n").toString();
    }

    /** The level's text colour as in the log view; INFO keeps the default colour. */
    private static String levelColour(Level level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case ERROR -> hex(DarculaColors.RED);
            case WARNING -> hex(DarculaColors.ORANGE);
            case INFO -> null;
        };
    }

    private static String hex(Color color) {
        return "#%06x".formatted(color.getRGB() & 0xFFFFFF);
    }

    private static Pattern highlightPattern(String term) {
        if (term == null || term.isEmpty()) {
            return null;
        }
        return Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Escapes the line piece by piece, so the matched text is marked without touching entities. */
    private static String highlighted(String line, Pattern term) {
        if (term == null) {
            return escape(line);
        }
        var matcher = term.matcher(line);
        var sb = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            sb.append(escape(line.substring(last, matcher.start()))).append("<mark>")
                    .append(escape(matcher.group())).append("</mark>");
            last = matcher.end();
        }
        return sb.append(escape(line.substring(last))).toString();
    }

    private static String escape(String text) {
        return StringEscapeUtils.escapeHtml4(text);
    }

    private static String time(Instant instant, ExportOptions options) {
        return instant == null ? "" : LOCAL_TIME.withZone(options.zone()).format(instant);
    }
}
