package se.grynna.pager.ui.details;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Everything the details view shows about one event: the header facts, the message and the fields in display order.
 * Immutable and free of Swing; build it with {@link #of}.
 *
 * @param level       the event's level, null when unknown
 * @param displayTime the time shown in the log (the source {@code @timestamp}, else the Splunk time)
 * @param splunkTime  Splunk's {@code _time}
 * @param indexTime   Splunk's {@code _indextime}
 * @param activity    who logged the event, null when unknown
 * @param msgType     whether the event is JSON or raw
 * @param timesDiffer whether the display time and the Splunk time are not the same instant
 * @param message     the event's message, never null
 * @param fields      the fields: FIELDS, then SPLUNK, then RAW
 */
public record EventDetails(Level level, Instant displayTime, Instant splunkTime, Instant indexTime, Activity activity,
        MsgType msgType, boolean timesDiffer, String message, List<Field> fields) {

    /** The part of the details a field is listed in. */
    public enum Section {
        /** Splunk-extracted fields and the flattened JSON of {@code _raw}. */
        FIELDS,
        /** Internal and metadata fields: {@code _}-fields, index, host, source, sourcetype, … */
        SPLUNK,
        /** The {@code _raw} text. */
        RAW
    }

    /** Whether a field is part of the scope as a field term: kept (narrow) or dropped (exclude). */
    public enum ScopeMark {
        NONE, NARROW, EXCLUDE
    }

    /**
     * One row of the details.
     *
     * @param section    where the field is listed
     * @param name       the field name; a dot path for nested JSON ({@code a.b}, {@code a[0].b})
     * @param value      the value, never null (empty when absent); may hold newlines
     * @param inMessage  whether the field is one of the index's message fields
     * @param mark       whether the field is in the scope as a field term
     * @param filterable whether {@code field="value"} can be folded into the scope ({@link #isFilterable})
     */
    public record Field(Section section, String name, String value, boolean inMessage, ScopeMark mark,
            boolean filterable) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SCOPE_FIELDS = Set.of("index", "host", "source", "sourcetype");
    private static final Set<String> SPLUNK_FIELDS = Set.of("index", "host", "source", "sourcetype", "linecount",
            "splunk_server", "splunk_server_group", "punct", "timestartpos", "timeendpos", "eventtype", "tag");
    private static final Comparator<Field> BY_NAME = Comparator.comparing(Field::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Field::name);

    public EventDetails {
        message = message == null ? "" : message;
        fields = List.copyOf(fields);
    }

    /**
     * Builds the details of an event-level line (the whole message).
     *
     * @param event     the event; its event map and raw may be null, and the raw need not be JSON
     * @param inMessage tells whether a field name is one of the index's message fields
     * @param scopeMark tells whether a field name is a field term of the scope
     */
    public static EventDetails of(LoadedLogLine event, Predicate<String> inMessage,
            Function<String, ScopeMark> scopeMark) {
        Map<String, String> eventMap = event.getEventMap();
        JsonNode json = parseObject(event.getRaw());

        List<Field> fields = new ArrayList<>();
        List<Field> splunk = new ArrayList<>();
        Map<String, String> union = new LinkedHashMap<>();
        if (eventMap != null) {
            for (Map.Entry<String, String> entry : eventMap.entrySet()) {
                String name = entry.getKey();
                if (name == null || "_raw".equals(name)) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (isInternal(name)) {
                    splunk.add(newField(Section.SPLUNK, name, value, inMessage, scopeMark));
                } else {
                    union.put(name, value);
                }
            }
        }
        if (json != null) {
            Map<String, String> flat = new LinkedHashMap<>();
            flatten(json, "", flat);
            for (Map.Entry<String, String> entry : flat.entrySet()) {
                if (eventMap == null || !eventMap.containsKey(entry.getKey())) {
                    union.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        for (Map.Entry<String, String> entry : union.entrySet()) {
            fields.add(newField(Section.FIELDS, entry.getKey(), entry.getValue(), inMessage, scopeMark));
        }
        fields.sort(BY_NAME);
        splunk.sort(BY_NAME);
        fields.addAll(splunk);
        String raw = event.getRaw();
        if (raw != null) {
            fields.add(newField(Section.RAW, "_raw", json != null ? pretty(json, raw) : raw, inMessage, scopeMark));
        }

        Instant display = event.getDisplayTime();
        Instant splunkTime = event.getSplunkTime();
        boolean differ = display != null && splunkTime != null && !display.equals(splunkTime);
        return new EventDetails(event.getLevel(), display, splunkTime, event.getIndextime(), event.resolvedActivity(),
                event.getMsgType(), differ, event.getMessage(), fields);
    }

    /**
     * Whether {@code name} is Splunk metadata rather than event content: {@code _}-fields other than {@code _raw},
     * index, host, source, sourcetype, linecount, splunk_server, punct, timestartpos, timeendpos, {@code date_*} and the
     * eventtype and tag fields.
     */
    public static boolean isInternal(String name) {
        if (name == null) {
            return false;
        }
        if (name.startsWith("_")) {
            return !"_raw".equals(name);
        }
        return SPLUNK_FIELDS.contains(name) || name.startsWith("date_") || name.startsWith("tag::");
    }

    /**
     * Whether {@code field="value"} may be folded into the scope: a FIELDS field or index, host, source or sourcetype,
     * not inside a JSON array, with a value that is not empty and has no newline.
     */
    public static boolean isFilterable(Field field) {
        return isFilterable(field.section(), field.name(), field.value());
    }

    private static boolean isFilterable(Section section, String name, String value) {
        boolean scopable = section == Section.FIELDS || (section == Section.SPLUNK && SCOPE_FIELDS.contains(name));
        // a[0].b is a 0x0A Pager path, not a Splunk field name (Splunk says a{}.b)
        return scopable && name.indexOf('[') < 0 && !value.isEmpty() && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0;
    }

    /** The field called {@code name}, if there is one. */
    public Optional<Field> field(String name) {
        int index = indexOf(name);
        return index < 0 ? Optional.empty() : Optional.of(fields.get(index));
    }

    /** The position of the field called {@code name} in {@link #fields()}, or -1. */
    public int indexOf(String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The number of fields listed in {@code section}. */
    public int count(Section section) {
        int count = 0;
        for (Field field : fields) {
            if (field.section() == section) {
                count++;
            }
        }
        return count;
    }

    private static Field newField(Section section, String name, String value, Predicate<String> inMessage,
            Function<String, ScopeMark> scopeMark) {
        boolean message = section == Section.FIELDS && inMessage != null && inMessage.test(name);
        ScopeMark mark = scopeMark == null ? null : scopeMark.apply(name);
        return new Field(section, name, value, message, mark == null ? ScopeMark.NONE : mark,
                isFilterable(section, name, value));
    }

    private static JsonNode parseObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String pretty(JsonNode json, String fallback) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json).replace("\r\n", "\n");
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void flatten(JsonNode node, String path, Map<String, String> out) {
        if (node.isObject()) {
            if (node.isEmpty() && !path.isEmpty()) {
                out.put(path, "{}");
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> children = node.fields();
            while (children.hasNext()) {
                Map.Entry<String, JsonNode> child = children.next();
                flatten(child.getValue(), path.isEmpty() ? child.getKey() : path + "." + child.getKey(), out);
            }
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                out.put(path, "[]");
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                flatten(node.get(i), path + "[" + i + "]", out);
            }
        } else if (!path.isEmpty()) {
            out.put(path, node.asText());
        }
    }
}
