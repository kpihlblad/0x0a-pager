package se.grynna.pager.ui.details;

import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** Builds event-level lines for the details tests. */
final class DetailsFixtures {

    static final Instant SPLUNK_TIME = Instant.parse("2017-03-04T10:11:12.123Z");
    static final Instant DISPLAY_TIME = Instant.parse("2017-03-04T10:11:12.123456789Z");
    static final Instant INDEX_TIME = Instant.parse("2017-03-04T10:11:14Z");

    private DetailsFixtures() {}

    static LoadedLogLine event(String raw, Map<String, String> eventMap, String message, Level level) {
        LoadedLogLine line = new LoadedLogLine(raw != null && raw.startsWith("{") ? MsgType.JSON : MsgType.RAW, "seg",
                7, "cd", "bkt", SPLUNK_TIME, raw, INDEX_TIME, DISPLAY_TIME, message, false, level);
        line.setEvent(eventMap == null ? null : new TreeMap<>(eventMap));
        return line;
    }

    static EventDetails details(LoadedLogLine event) {
        return EventDetails.of(event, name -> false, name -> EventDetails.ScopeMark.NONE);
    }

    /** A JSON event with {@code count} fields {@code f000, f001, …}, each with a short value. */
    static EventDetails manyFields(int count) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < count; i++) {
            json.append(i == 0 ? "" : ",").append(String.format("\"f%03d\":\"value %d\"", i, i));
        }
        json.append('}');
        return details(event(json.toString(), null, "many fields", Level.INFO));
    }

    /** An event with {@code count} Splunk fields {@code f000, f001, …} and no raw: the last field is a single line. */
    static EventDetails manyMapFields(int count) {
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            map.put(String.format("f%03d", i), "value " + i);
        }
        return details(event(null, map, "many fields", Level.INFO));
    }

    /** A JSON event with the given top-level string fields, in the order given. */
    static EventDetails ofFields(String... namesAndValues) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            json.append(i == 0 ? "" : ",").append('"').append(namesAndValues[i]).append("\":\"")
                    .append(namesAndValues[i + 1]).append('"');
        }
        json.append('}');
        return details(event(json.toString(), null, "msg", Level.INFO));
    }
}
