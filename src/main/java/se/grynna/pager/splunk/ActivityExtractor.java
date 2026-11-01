package se.grynna.pager.splunk;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristically finds the {@link Activity} (machine, process, thread, request) that emitted an event. Pure and
 * thread-safe.
 * Structured fields are matched first by normalised name, then by the last segment of a dotted path; text sniffers
 * on the first line fill whatever is still unknown.
 */
public final class ActivityExtractor {

    private static final int MAX_VALUE_LENGTH = 200;
    private static final int MAX_SNIFF_LENGTH = 1024;
    private static final Set<String> PLACEHOLDERS = Set.of("-", "null", "none", "unknown", "n/a", "undefined");
    private static final Set<String> SPLUNK_INTERNAL = Set.of("_raw", "_time", "_cd", "_bkt", "_indextime", "_serial",
            "_si", "_sourcetype", "_kv", "splunk_server", "index", "linecount", "punct", "timestartpos", "timeendpos");
    private static final Set<String> WEAK_SPLUNK_FIELDS = Set.of("host", "source", "sourcetype");

    /** K8s coordinates first (pod over node), then infra host names; the weak Splunk {@code host} is only a fallback. */
    private static final List<String> MACHINE = List.of("kubernetespodname", "k8spodname", "podname", "pod",
            "kubernetespoduid", "k8spoduid", "poduid", "kubernetespodnodename", "k8snodename", "kubernetesnodename",
            "hostname", "host", "machinename", "computername", "computer", "hostid", "cloudinstanceid", "ec2instanceid",
            "servername", "nodename", "node");
    private static final List<String> PROCESS_ID = List.of("containerid", "kubernetescontainerid", "dockercontainerid",
            "ecstaskarn", "taskarn", "ecstaskid", "taskid", "serviceinstanceid", "logstream", "processpid", "pid",
            "processid", "executionprocessid", "procid");
    private static final List<String> PROCESS_NAME = List.of("syslogidentifier", "comm", "processname", "programname",
            "program", "kubernetescontainername", "k8scontainername", "containername", "appname", "servicename",
            "service", "applicationname", "application", "app", "workload");
    private static final List<String> THREAD = List.of("processthreadname", "processthreadid", "threadname", "thread",
            "threadid", "executionthreadid", "tid", "lwp", "goroutine", "goroutineid", "fiber", "coroutine", "workerid",
            "worker");
    private static final List<String> REQUEST = List.of("traceid", "requestid", "xrequestid", "reqid",
            "correlationid", "xcorrelationid", "operationid", "activityid");

    private static final String LEVEL = "(?:TRACE|DEBUG|INFO|NOTICE|WARNING|WARN|ERROR|SEVERE|FATAL|CRITICAL|CRIT)";
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");
    private static final Pattern RFC5424 = Pattern.compile("^<\\d{1,3}>\\d{1,3} (\\S+) (\\S+) (\\S+) (\\S+) \\S+");
    private static final Pattern BSD_SYSLOG = Pattern.compile("^(?:<\\d{1,3}>)?(?:[A-Z][a-z]{2} +\\d{1,2} "
            + "\\d{2}:\\d{2}:\\d{2}|\\d{4}-\\d{2}-\\d{2}T\\S+) +(\\S+) +([^\\s:\\[]+)(?:\\[(\\d+)\\])?: ");
    private static final Pattern SPRING = Pattern.compile("\\b" + LEVEL + "\\b +(\\d+) +--- +",
            Pattern.CASE_INSENSITIVE);
    /** Case-sensitive: layouts print the level upper-case; prose like "got error [timeout]" must not match. */
    private static final Pattern LEVEL_THEN_BRACKET = Pattern.compile("\\b" + LEVEL + "\\b\\s+(?:-\\s+)?");
    private static final Pattern TIME_BEFORE_BRACKET = Pattern.compile("\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?");
    private static final Pattern LEVEL_AFTER_BRACKET = Pattern.compile("\\s+" + LEVEL + "\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_SUFFIX = Pattern.compile("(?::+[\\w.$]+)?@\\d+$");
    private static final Pattern OSLO_PID = Pattern
            .compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)? +"
                    + "(\\d+) +" + LEVEL + "\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OSLO_REQUEST = Pattern.compile("\\[(req-[\\w-]+)");
    private static final Pattern GLOG = Pattern
            .compile("^[IWEF]\\d{4} +\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)? +(\\d+) +[^\\s\\]]+:\\d+\\]");
    private static final Pattern LOGCAT = Pattern
            .compile("^\\d{2}-\\d{2} +\\d{2}:\\d{2}:\\d{2}\\.\\d{3} +(\\d+) +(\\d+) +[VDIWEFAS] ");
    private static final Pattern NGINX = Pattern
            .compile("^\\d{4}/\\d{2}/\\d{2} +\\d{2}:\\d{2}:\\d{2} +\\[\\w+\\] +(\\d+)#(\\d+): ?(\\*\\d+)?");
    private static final Pattern APACHE_PID = Pattern.compile("\\[pid (\\d+)(?::tid (\\d+))?\\]");
    private static final Pattern APACHE_CLIENT = Pattern.compile("\\[client ([^\\]\\s]+?)(?::\\d+)?\\]");
    private static final Pattern PIPE_HEADER = Pattern.compile("^[^|]+\\|[^|]+\\|(\\d+)\\|");
    private static final Pattern ERLANG_PID = Pattern.compile("<\\d+\\.\\d+\\.\\d+>");
    private static final Pattern GO_GOROUTINE = Pattern.compile("\\bgoroutine (\\d+)\\b");
    private static final Pattern LOGFMT = Pattern
            .compile("(?:^|\\s)([A-Za-z_][\\w.-]*)=(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s\"]+))");

    private ActivityExtractor() {}

    /**
     * Like {@link #extract(Map, JsonNode, String, String)} with {@code text} also serving as the raw text, which is
     * right for raw events.
     */
    public static Activity extract(Map<String, String> splunkFields, JsonNode json, String text) {
        return extract(splunkFields, json, text, text);
    }

    /**
     * @param splunkFields the event's Splunk fields (may be null)
     * @param json         the parsed {@code _raw} when it is a JSON object, else null
     * @param text         raw text to sniff: {@code _raw} for raw events, the message for JSON events (may be null)
     * @param raw          the event's {@code _raw}, where the process term must occur literally (may be null)
     * @return the activity, never null ({@link Activity#NONE} when nothing is known)
     */
    public static Activity extract(Map<String, String> splunkFields, JsonNode json, String text, String raw) {
        FieldIndex fields = index(splunkFields, json);
        Slots slots = new Slots();
        slots.offerMachine(fields.find(MACHINE));
        slots.offerProcessName(fields.find(PROCESS_NAME));
        slots.offerProcessId(fields.find(PROCESS_ID));
        slots.offerThread(fields.find(THREAD));
        slots.offerRequest(fields.find(REQUEST));

        String line = firstLine(text);
        if (line != null) {
            sniffText(line, slots);
        }

        String machine = slots.machine;
        if (machine == null && splunkFields != null) {
            machine = clean(splunkFields.get("host"));
        }
        String process = processKey(slots.processName, slots.processId);
        String origin = machine == null && process == null && splunkFields != null ? origin(splunkFields) : null;
        String thread = slots.thread;
        String request = slots.request;
        if (machine == null && process == null && thread == null && request == null && origin == null) {
            return Activity.NONE;
        }
        String processTerm = processTerm(raw, process, slots.processId, machine, slots.processName);
        return new Activity(machine, process, thread, request, origin, processTerm);
    }

    /**
     * The structured field each activity slot was taken from, with the name as written; a slot sniffed from text has
     * none. Uses the same inputs and lookups as {@link #extract(Map, JsonNode, String, String)}. The machine falls
     * back to the Splunk {@code host} field.
     *
     * @param splunkFields the event's Splunk fields (may be null)
     * @param json         the parsed {@code _raw} when it is a JSON object, else null
     */
    public static ActivityFields sourceFields(Map<String, String> splunkFields, JsonNode json) {
        FieldIndex fields = index(splunkFields, json);
        ActivityFields.SourceField machine = fields.findField(MACHINE);
        if (machine == null && splunkFields != null) {
            String host = clean(splunkFields.get("host"));
            if (host != null) {
                machine = new ActivityFields.SourceField("host", host);
            }
        }
        return new ActivityFields(machine, fields.findField(PROCESS_NAME), fields.findField(PROCESS_ID),
                fields.findField(THREAD), fields.findField(REQUEST));
    }

    private static FieldIndex index(Map<String, String> splunkFields, JsonNode json) {
        FieldIndex fields = new FieldIndex();
        if (json != null && json.isObject()) {
            flatten(json, "", fields);
        }
        if (splunkFields != null) {
            addSplunkFields(splunkFields, fields);
        }
        return fields;
    }

    /**
     * The first of the composite process key, the process id, the machine (the pod) and the process name that occurs
     * literally (case-sensitive) in {@code raw}, so it can be searched for as text; null when none does.
     */
    private static String processTerm(String raw, String... candidates) {
        if (raw == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && raw.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Flattens nested objects into dotted paths. Arrays and nulls are ignored. */
    private static void flatten(JsonNode node, String prefix, FieldIndex fields) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                flatten(value, path, fields);
            } else if (value.isValueNode() && !value.isNull()) {
                fields.add(path, value.asText());
            }
        }
    }

    /** Splunk's own host/source/sourcetype are weak defaults and never structured candidates. */
    private static void addSplunkFields(Map<String, String> splunkFields, FieldIndex fields) {
        for (Map.Entry<String, String> entry : splunkFields.entrySet()) {
            String key = entry.getKey();
            if (key != null && !WEAK_SPLUNK_FIELDS.contains(key.toLowerCase(Locale.ROOT))) {
                fields.add(key, entry.getValue());
            }
        }
    }

    private static void sniffText(String line, Slots slots) {
        sniffRfc5424(line, slots);
        sniffBsdSyslog(line, slots);
        sniffSpring(line, slots);
        sniffLog4jAfterLevel(line, slots);
        sniffLog4jBeforeLevel(line, slots);
        sniffOslo(line, slots);
        sniffGlog(line, slots);
        sniffLogcat(line, slots);
        sniffNginx(line, slots);
        sniffApache(line, slots);
        sniffPipe(line, slots);
        sniffErlangAndGo(line, slots);
        sniffLogfmt(line, slots);
    }

    /** RFC 5424: {@code <pri>version ts host app procid msgid}. */
    private static void sniffRfc5424(String line, Slots slots) {
        Matcher m = RFC5424.matcher(line);
        if (m.find()) {
            slots.offerMachine(m.group(2));
            slots.offerProcessName(m.group(3));
            slots.offerProcessId(m.group(4));
        }
    }

    /** BSD (RFC 3164) syslog with a classic or ISO timestamp: {@code [<pri>]ts host prog[pid]: msg}. */
    private static void sniffBsdSyslog(String line, Slots slots) {
        Matcher m = BSD_SYSLOG.matcher(line);
        if (m.find()) {
            slots.offerMachine(m.group(1));
            slots.offerProcessName(m.group(2));
            slots.offerProcessId(m.group(3));
        }
    }

    /** Spring Boot 2 and 3 default pattern: {@code LEVEL pid --- [app]? [thread]}. */
    private static void sniffSpring(String line, Slots slots) {
        Matcher m = SPRING.matcher(line);
        if (!m.find()) {
            return;
        }
        slots.offerProcessId(m.group(1));
        Bracket first = bracketAt(line, m.end());
        if (first == null) {
            return;
        }
        Bracket second = bracketAt(line, first.end());
        slots.offerThread((second != null ? second : first).content());
    }

    /** log4j/logback text with the thread right after the level: {@code INFO [t]}, {@code INFO - [t]}. */
    private static void sniffLog4jAfterLevel(String line, Slots slots) {
        Matcher m = LEVEL_THEN_BRACKET.matcher(line);
        if (m.find()) {
            Bracket thread = bracketAt(line, m.end());
            if (thread != null) {
                slots.offerThread(stripLocation(thread.content()));
            }
        }
    }

    /** log4j/logback text with the thread before the level: {@code 12:00:00.123 [t] INFO}. */
    private static void sniffLog4jBeforeLevel(String line, Slots slots) {
        Matcher m = TIME_BEFORE_BRACKET.matcher(line);
        while (m.find()) {
            Bracket thread = bracketAt(line, m.end());
            if (thread != null && LEVEL_AFTER_BRACKET.matcher(line).region(thread.end(), line.length()).lookingAt()) {
                slots.offerThread(stripLocation(thread.content()));
                return;
            }
        }
    }

    /** Python logging and OpenStack oslo: {@code date time pid LEVEL ...}, with {@code [req-<uuid>} as fallback. */
    private static void sniffOslo(String line, Slots slots) {
        Matcher pid = OSLO_PID.matcher(line);
        if (pid.find()) {
            slots.offerProcessId(pid.group(1));
        }
        Matcher request = OSLO_REQUEST.matcher(line);
        if (request.find()) {
            slots.offerRequest(request.group(1));
        }
    }

    /** glog and klog: {@code I0525 10:12:13.123456   12345 server.go:123] msg}. */
    private static void sniffGlog(String line, Slots slots) {
        Matcher m = GLOG.matcher(line);
        if (m.find()) {
            slots.offerThread(m.group(1));
        }
    }

    /** Android logcat threadtime: {@code MM-DD hh:mm:ss.mmm pid tid L tag: msg}. */
    private static void sniffLogcat(String line, Slots slots) {
        Matcher m = LOGCAT.matcher(line);
        if (m.find()) {
            slots.offerProcessId(m.group(1));
            slots.offerThread(m.group(2));
        }
    }

    /** nginx error log: {@code yyyy/mm/dd hh:mm:ss [lvl] pid#tid: *conn msg}. */
    private static void sniffNginx(String line, Slots slots) {
        Matcher m = NGINX.matcher(line);
        if (m.find()) {
            slots.offerProcessId(m.group(1));
            slots.offerThread(m.group(2));
            slots.offerRequest(m.group(3));
        }
    }

    /** Apache httpd 2.4 {@code [pid N:tid M]} and 2.2/2.4 {@code [client ip(:port)]} (request, port dropped). */
    private static void sniffApache(String line, Slots slots) {
        Matcher pid = APACHE_PID.matcher(line);
        if (pid.find()) {
            slots.offerProcessId(pid.group(1));
            slots.offerThread(pid.group(2));
        }
        Matcher client = APACHE_CLIENT.matcher(line);
        if (client.find()) {
            slots.offerRequest(client.group(1));
        }
    }

    /** Pipe-delimited header: {@code ts|component|pid|...}. */
    private static void sniffPipe(String line, Slots slots) {
        Matcher m = PIPE_HEADER.matcher(line);
        if (m.find()) {
            slots.offerProcessId(m.group(1));
        }
    }

    /** Erlang/Elixir pids {@code <0.123.0>} and Go {@code goroutine N}. */
    private static void sniffErlangAndGo(String line, Slots slots) {
        Matcher erlang = ERLANG_PID.matcher(line);
        if (erlang.find()) {
            slots.offerThread(erlang.group());
        }
        Matcher go = GO_GOROUTINE.matcher(line);
        if (go.find()) {
            slots.offerThread(go.group(1));
        }
    }

    /** logfmt: only unambiguous keys; {@code host=} is excluded because it usually names a target. */
    private static void sniffLogfmt(String line, Slots slots) {
        Matcher m = LOGFMT.matcher(line);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "pod", "pod_name" -> slots.offerMachine(value);
                case "pid" -> slots.offerProcessId(value);
                case "hostname" -> slots.offerMachine(value);
                case "tid", "thread", "thread_id", "thread_name", "threadid" -> slots.offerThread(value);
                case "trace_id", "traceid", "request_id", "req_id" -> slots.offerRequest(value);
                default -> {
                    // not an activity key
                }
            }
        }
    }

    private static String stripLocation(String threadName) {
        return LOCATION_SUFFIX.matcher(threadName).replaceFirst("");
    }

    /**
     * Reads a balanced {@code [...]} group, skipping leading whitespace.
     *
     * @return the content and the index just after the closing bracket, or null when there is no balanced group
     */
    private static Bracket bracketAt(String s, int from) {
        int open = from;
        while (open < s.length() && Character.isWhitespace(s.charAt(open))) {
            open++;
        }
        if (open >= s.length() || s.charAt(open) != '[') {
            return null;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return new Bracket(s.substring(open + 1, i), i + 1);
            }
        }
        return null;
    }

    private record Bracket(String content, int end) {}

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        int end = Math.min(text.length(), MAX_SNIFF_LENGTH);
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                end = i;
                break;
            }
        }
        String line = text.substring(0, end).strip();
        return line.isEmpty() ? null : line;
    }

    private static String processKey(String name, String id) {
        if (name == null) {
            return id;
        }
        return id == null ? name : name + "[" + id + "]";
    }

    private static String origin(Map<String, String> splunkFields) {
        String sourcetype = clean(splunkFields.get("sourcetype"));
        String source = clean(splunkFields.get("source"));
        if (sourcetype == null) {
            return source;
        }
        return source == null ? sourcetype : sourcetype + ":" + source;
    }

    /** Trims, drops empty values and placeholders, and caps the length. */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (v.isEmpty() || PLACEHOLDERS.contains(v.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return v.length() > MAX_VALUE_LENGTH ? v.substring(0, MAX_VALUE_LENGTH) : v;
    }

    private static String normalize(String name) {
        return NON_ALNUM.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static boolean isSplunkInternal(String leaf) {
        String lower = leaf.toLowerCase(Locale.ROOT);
        return SPLUNK_INTERNAL.contains(lower) || lower.startsWith("date_");
    }

    /**
     * Structured values by normalised full path (first wins) and by normalised last segment of dotted paths. Full
     * paths always take precedence over last-segment matches.
     */
    private static final class FieldIndex {
        private final Map<String, ActivityFields.SourceField> byPath = new HashMap<>();
        private final Map<String, ActivityFields.SourceField> byLeaf = new HashMap<>();

        void add(String path, String value) {
            int dot = path.lastIndexOf('.');
            String leaf = path.substring(dot + 1);
            if (isSplunkInternal(leaf)) {
                return;
            }
            String cleaned = clean(value);
            if (cleaned == null) {
                return;
            }
            ActivityFields.SourceField field = new ActivityFields.SourceField(path, cleaned);
            byPath.putIfAbsent(normalize(path), field);
            if (dot >= 0) {
                byLeaf.putIfAbsent(normalize(leaf), field);
            }
        }

        String find(List<String> candidates) {
            ActivityFields.SourceField field = findField(candidates);
            return field == null ? null : field.value();
        }

        ActivityFields.SourceField findField(List<String> candidates) {
            for (String name : candidates) {
                ActivityFields.SourceField field = byPath.get(name);
                if (field != null) {
                    return field;
                }
            }
            for (String name : candidates) {
                ActivityFields.SourceField field = byLeaf.get(name);
                if (field != null) {
                    return field;
                }
            }
            return null;
        }
    }

    /** The five activity slots; each is filled once, so earlier (structured or earlier sniffer) hits win. */
    private static final class Slots {
        private String machine;
        private String processName;
        private String processId;
        private String thread;
        private String request;

        void offerMachine(String value) {
            if (machine == null) {
                machine = clean(value);
            }
        }

        void offerProcessName(String value) {
            if (processName == null) {
                processName = clean(value);
            }
        }

        void offerProcessId(String value) {
            if (processId == null) {
                processId = clean(value);
            }
        }

        void offerThread(String value) {
            if (thread == null) {
                thread = clean(value);
            }
        }

        void offerRequest(String value) {
            if (request == null) {
                request = clean(value);
            }
        }
    }
}
