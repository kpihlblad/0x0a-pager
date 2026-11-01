package se.grynna.pager.splunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

@Data
@Slf4j
public final class LoadedLogLine implements LogLine, Serializable {

    /**
     * Supports in-memory Java serialization (tests and any plain-object copies).
     * Rows in the log store are written with Kryo by {@code LoadedLogLineDataType}.
     */
    private static final long serialVersionUID = 8649534943184513075L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Persisted by ordinal in the log store rows (see {@code LoadedLogLineSerializer}). Append new constants at the end
     * only; reordering, inserting or removing one requires bumping {@code RowCodec.FORMAT_VERSION}.
     */
    public enum MsgType {
        JSON, RAW
    }

    private String segmentId; // Segment ID
    private int lineNumber; // Line number for the start of the log entry
    private String bkt; // _bkt field
    private String cb;
    private Instant time; // Splunk _time: query, ordering, and navigation coordinate
    private Instant msgTime; // Display timestamp from the log object, falling back to _time
    private Instant indextime; // _indextime field

    Level level;
    private String message; // Extracted message from the log entry
    private boolean continuation;
    private String raw;
    private TreeMap<String, String> eventMap;
    private MsgType msgType;
    /** Who emitted the event; computed once per event at mapping time, null in rows stored by older versions. */
    private Activity activity;

    @Override
    public int line() {
        return lineNumber;
    }

    /**
     * Splunk's indexed event time. This is the coordinate used by Splunk time
     * windows and by 0x0A Pager's row ordering/navigation.
     */
    public Instant getSplunkTime() {
        return time;
    }

    /**
     * Timestamp shown to the user. For JSON events this is a valid
     * {@code @timestamp}; otherwise it falls back to {@link #getSplunkTime()}.
     */
    public Instant getDisplayTime() {
        return msgTime;
    }

    /**
     * The row's activity. Uses the stored {@link #getActivity()} when present; otherwise extracts it from the event
     * map and the raw text (rows stored by older versions) and keeps the result.
     */
    public Activity resolvedActivity() {
        if (activity == null) {
            activity = ActivityExtractor.extract(eventMap, parseObjectOrNull(raw),
                    msgType == MsgType.JSON ? message : raw, raw);
        }
        return activity;
    }

    /**
     * The field filters and text terms that identify the row's activity at {@code level}; see
     * {@link ActivityFields#termsFor}.
     */
    public ActivityFields.Terms activityTerms(Activity.Level level) {
        return ActivityExtractor.sourceFields(eventMap, parseObjectOrNull(raw)).termsFor(resolvedActivity(), level);
    }

    /** The parsed {@code _raw} when it is a JSON object, else null. */
    static JsonNode parseObjectOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.trace("raw is not JSON: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("segmentId    : ").append(segmentId).append("\n");
        sb.append("lineNumber   : ").append(lineNumber).append("\n");
        sb.append("bkt          : ").append(bkt).append("\n");
        sb.append("cb           : ").append(cb).append("\n");
        sb.append("time         : ").append(time).append("\n");
        sb.append("msgTime      : ").append(msgTime).append("\n");
        sb.append("indextime    : ").append(indextime).append("\n");
        sb.append("level        : ").append(level).append("\n");
        sb.append("message      : ").append(message).append("\n");
        sb.append("continuation : ").append(continuation).append("\n");
        sb.append("activity     : ").append(activity).append("\n");
        sb.append("raw          : ");
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(raw);
            String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            sb.append("\n").append(prettyJson);
        } catch (Exception e) {
            // If raw is not valid JSON, just append it as-is
            sb.append(raw);
        }
        return sb.toString();
    }

    public LoadedLogLine() {}

    public LoadedLogLine(MsgType msgType, String segmentId, int lineNumber, String cb, String bkt, Instant time,
            String raw, Instant indextime, Instant msgTime, String message, boolean continuation, Level level) {
        this.msgType = msgType;
        this.segmentId = segmentId;
        this.lineNumber = lineNumber;
        this.time = time;
        this.cb = cb;
        this.indextime = indextime;
        this.bkt = bkt;
        this.raw = raw;
        this.msgTime = msgTime;
        this.message = message;
        this.continuation = continuation;
        this.level = level;
    }

    @Override
    public boolean equals(Object o2) {
        if (o2 instanceof LoadedLogLine logLine) {
            return this.segmentId.equals(logLine.getSegmentId()) && this.lineNumber == logLine.line()
                    && this.cb.equals(logLine.getCb()) && this.time.equals(logLine.getTime())
                    && this.raw.equals(logLine.getRaw()) && this.msgTime.equals(logLine.getMsgTime())
                    && this.message.equals(logLine.getMessage()) && this.continuation == logLine.isContinuation()
                    && this.level == logLine.level;
        }
        return false;
    }

    @Override
    public int hashCode() {
        // Derived from exactly the fields compared by equals(), excluding msgType.
        return Objects.hash(segmentId, lineNumber, cb, time, raw, msgTime, message, continuation, level);
    }

    //  @Override
    public int compare(LoadedLogLine a, LoadedLogLine b) {
        // Compare by segment ID first
        int cmp = a.getSegmentId().compareTo(b.getSegmentId());
        if (cmp != 0) {
            return cmp;
        }
        // If segment IDs are equal, compare by line number
        cmp = Integer.compare(a.line(), b.line());
        if (cmp != 0) {
            return cmp;
        }
        // If line numbers are equal, compare by time
        cmp = a.getTime().compareTo(b.getTime());
        if (cmp != 0) {
            return cmp;
        }
        // If time is equal, compare by xtimestamp
        cmp = a.getMsgTime().compareTo(b.getMsgTime());
        if (cmp != 0) {
            return cmp;
        }
        // If xtimestamp is equal, compare by raw
        cmp = a.getRaw().compareTo(b.getRaw());
        if (cmp != 0) {
            return cmp;
        }
        // continuation
        return Boolean.compare(a.isContinuation(), b.isContinuation());
    }

    public void setEvent(SortedMap<String, String> eventMap) {
        // Copy into a TreeMap so later mutation of the source cannot change a stored row.
        this.eventMap = eventMap == null ? null : new TreeMap<>(eventMap);
    }

    public SortedMap<String, String> getEventMap() {
        return eventMap;
    }
}
