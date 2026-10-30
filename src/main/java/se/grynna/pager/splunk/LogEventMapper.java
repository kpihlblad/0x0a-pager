package se.grynna.pager.splunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.splunk.Event;
import lombok.SneakyThrows;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.trimToEmpty;

public class LogEventMapper {

    /**
     * Core event parsing — extracts message, timestamp, and level from an event.
     * Shared by both mapEvent (multi-line) and mapEventSingleLine (single-line for search).
     */
    private record ParsedEvent(Instant time, String raw, String cb, String bkt, Instant indextime, String message,
            Instant msgTimestamp, Level level, MsgType msgType, Activity activity) {}

    private ParsedEvent parseEventCore(Event event) {
        var time = Instant.parse(event.get("_time"));
        var raw = event.get("_raw");
        var cb = event.get("_cd");
        var bkt = event.get("_bkt");
        var indextime = Instant.ofEpochSecond(Long.parseLong(event.get("_indextime")));

        String message;
        Level level;
        Instant msgTimestamp;
        MsgType msgType;
        JsonNode json = LoadedLogLine.parseObjectOrNull(raw);

        if (json != null) {
            msgType = MsgType.JSON;
            msgTimestamp = getMsgTimestamp(json.get("@timestamp"), time);
            level = calculateLevel(event, json);
            message = format(json);
        } else {
            msgType = MsgType.RAW;
            message = raw;
            level = Level.INFO;
            msgTimestamp = time;
        }

        var activity = ActivityExtractor.extract(event, json, msgType == MsgType.JSON ? message : raw, raw);

        return new ParsedEvent(time, raw, cb, bkt, indextime, message, msgTimestamp, level, msgType, activity);
    }

    /**
     * Map a Splunk event to a single LoadedLogLine without splitting multi-line messages.
     * Used by search to get the message timestamp without the overhead of line splitting.
     */
    @SneakyThrows
    public LoadedLogLine mapEventSingleLine(Event event) {
        ParsedEvent parsed = parseEventCore(event);

        var line = new LoadedLogLine(parsed.msgType, null, 0, // set later
                parsed.cb, parsed.bkt, parsed.time, parsed.raw, parsed.indextime, parsed.msgTimestamp, parsed.message,
                false, parsed.level);
        line.setEvent(new TreeMap<>(event));
        line.setActivity(parsed.activity);
        return line;
    }

    /**
     * Map a Splunk event to a stream of LoadedLogLines, splitting multi-line messages.
     * Used by the main log viewer.
     */
    public Stream<LoadedLogLine> mapEvent(Event event) {
        return splitLines(mapEventSingleLine(event)).stream();
    }

    /**
     * Split a single-line mapped event into the rows the log viewer shows: one row
     * per line of the event's message, the first row carrying the event preamble
     * and the rest marked as continuation rows. Events without message lines map
     * to a single raw row.
     * <p>
     * This is the one implementation of the log's row-splitting rule; both
     * {@link #mapEvent(Event)} and the Matches view row model use it, so a match
     * looks exactly like it does in the log.
     *
     * @param eventLine an event as produced by {@link #mapEventSingleLine(Event)}
     * @return the event's rows, with line numbers left at 0 for the caller to set
     */
    public static List<LoadedLogLine> splitLines(LoadedLogLine eventLine) {
        var messages = eventLine.getMessage().lines()
                .map(messageLine -> new LoadedLogLine(eventLine.getMsgType(), null, 0, // set later
                        eventLine.getCb(), eventLine.getBkt(), eventLine.getTime(), eventLine.getRaw(),
                        eventLine.getIndextime(), eventLine.getMsgTime(), trimToEmpty(messageLine), true,
                        eventLine.getLevel()))
                .peek(row -> row.setActivity(eventLine.getActivity()))
                .toList();

        if (messages.isEmpty()) {
            var fallback = new LoadedLogLine(MsgType.RAW, null, 0, // set later
                    eventLine.getCb(), eventLine.getBkt(), eventLine.getTime(), eventLine.getRaw(),
                    eventLine.getIndextime(), eventLine.getMsgTime(), eventLine.getRaw(), false, eventLine.getLevel());
            fallback.setActivity(eventLine.getActivity());
            messages = List.of(fallback);
        } else {
            messages.getFirst().setContinuation(false);
            messages.getFirst().setEvent(eventLine.getEventMap());
        }

        return messages;
    }

    private Level calculateLevel(Event event, JsonNode json) {

        // check if any of the error tags are present.
        var eventFields = event.keySet();

        if (matching(event, eventFields, errorTags)) {
            return Level.ERROR;
        }
        if (matching(json, errorTags)) {
            return Level.ERROR;
        }
        if (matching(event, eventFields, warningTags)) {
            return Level.WARNING;
        }
        if (matching(json, warningTags)) {
            return Level.WARNING;
        }
        return Level.INFO;
    }

    private static boolean matching(Event event, Set<String> eventFields, List<Pair<String, String>> tags) {

        for (Pair<String, String> tag : tags) {
            if (eventFields.contains(tag.getKey())) {
                if (tag.getRight() == null || event.get(tag.getKey()).equals(tag.getRight())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matching(JsonNode node, List<Pair<String, String>> tags) {
        for (Pair<String, String> tag : tags) {
            if (node.has(tag.getKey())) {
                if (tag.getValue() == null || tag.getValue().equals(node.get(tag.getKey()).asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Instant getMsgTimestamp(JsonNode tstamp, Instant time) {
        Instant msgTimestamp;
        if (tstamp == null) {
            msgTimestamp = time;
        } else {
            try {
                msgTimestamp = Instant.parse(tstamp.asText());
            } catch (DateTimeParseException e) {
                msgTimestamp = time;
            }
        }
        return msgTimestamp;
    }

    // cause, content, eventType=ALARM, severity=ERROR, severity=WARN, stream=stderr, tag=error, tag::eventType=error,
    // title
    private final List<Pair<String, String>> errorTags = List.of(new ImmutablePair<>("cause", null),
            new ImmutablePair<>("content", null), new ImmutablePair<>("eventType", "ALARM"),
            new ImmutablePair<>("severity", "ERROR"), new ImmutablePair<>("stream", "stderr"),
            new ImmutablePair<>("tag", "error"), new ImmutablePair<>("tag::eventType", "error"),
            new ImmutablePair<>("title", null));

    private final List<Pair<String, String>> warningTags = List.of(new ImmutablePair<>("severity", "WARN"),
            new ImmutablePair<>("tag", "warn"), new ImmutablePair<>("tag::eventType", "warn"));

    /** The message fields every JSON event uses unless an index saves its own. */
    public static final List<String> DEFAULT_MESSAGE_FIELDS = List.of("msg", "log", "message",
            "events.RenderedMessage", "error", "stacktrace", "stack_trace", "stackTrace", "exception", "trace");

    /** Read by the mapping threads and replaced by the UI; always an immutable copy. */
    private volatile List<String> messageFields = DEFAULT_MESSAGE_FIELDS;

    /**
     * Set the ordered JSON field paths whose values make up a JSON event's message.
     * {@code null} restores {@link #DEFAULT_MESSAGE_FIELDS}; an empty list means no field
     * contributes, so every JSON event's message is empty and it maps to its raw row.
     * Events mapped after the call use the new fields; loaded events are the caller's to drop.
     */
    public void setMessageFields(List<String> fields) {
        messageFields = fields == null ? DEFAULT_MESSAGE_FIELDS : List.copyOf(fields);
    }

    /** The ordered JSON field paths that make up a JSON event's message. */
    public List<String> getMessageFields() {
        return messageFields;
    }

    public String format(JsonNode json) {

        StringBuilder messageBuilder = new StringBuilder();
        for (String field : messageFields) {
            JsonNode valueNode = nodeByPath(json, field);
            if (valueNode != null && !valueNode.isMissingNode() && !valueNode.isNull()) {
                if (!messageBuilder.isEmpty()) {
                    messageBuilder.append("\n");
                }
                messageBuilder.append(valueNode.asText());
            }
        }

        return trimToEmpty(messageBuilder.toString());
    }

    /**
     * Resolve a field path from a JsonNode.
     * Supports:
     * - JSON Pointer syntax (if path starts with '/')
     * - Dot-separated paths like "events.RenderedMessage"
     * - Single top-level field names
     */
    private JsonNode nodeByPath(JsonNode root, String path) {
        if (root == null || path == null) return null;
        // If it's already a JSON Pointer use it directly
        if (path.startsWith("/")) {
            return root.at(path);
        }
        // If it contains dots, convert to JSON Pointer
        if (path.contains(".")) {
            String pointer = "/" + path.replace(".", "/");
            return root.at(pointer);
        }
        // Simple top-level field
        return root.get(path);
    }
}
