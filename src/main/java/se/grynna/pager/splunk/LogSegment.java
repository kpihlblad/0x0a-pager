package se.grynna.pager.splunk;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Represents a contiguous segment of log lines with time boundaries.
 * <p>
 * <b>INTERVAL SEMANTICS:</b>
 * <ul>
 *   <li><b>contains()</b> methods use <b>half-open interval [start, end)</b>
 *       <ul>
 *         <li>Start boundary is INCLUSIVE</li>
 *         <li>End boundary is EXCLUSIVE</li>
 *         <li>Line range: [startLine, startLine+size)</li>
 *         <li>Time range: [startTime, endTime)</li>
 *       </ul>
 *   </li>
 *   <li><b>near()</b> methods use <b>closed interval [start-delta, end+delta]</b>
 *       <ul>
 *         <li>Both boundaries are INCLUSIVE</li>
 *         <li>Symmetric proximity check</li>
 *         <li>Line range: [startLine-NEAR_DELTA_LINES, startLine+size+NEAR_DELTA_LINES]</li>
 *         <li>Time range: [startTime-NEAR_DELTA_TIME, endTime+NEAR_DELTA_TIME]</li>
 *       </ul>
 *   </li>
 * </ul>
 * <p>
 * <b>ADJACENT SEGMENTS:</b><br>
 * Half-open intervals ensure adjacent segments don't overlap or have gaps:
 * <pre>
 * Segment A: [100, 150)  contains lines 100-149
 * Segment B: [150, 200)  contains lines 150-199
 * Line 150 belongs to B only (no gap, no overlap)
 * </pre>
 * <p>
 * <b>CONSISTENCY WITH SPLUNK API:</b><br>
 * This interval convention matches Splunk's time-based search semantics where
 * the start time is inclusive and queries use half-open intervals.
 * <p>
 * Thread-safety: Segment modification methods should be synchronized}.
 */
@Getter
@EqualsAndHashCode
@Slf4j
public class LogSegment implements Comparable<LogSegment> {

    // NEAR_DELTA is the maximum time difference between two log segments to be considered "near"
    // Betting on that log events are not billions events per second
    public static final Duration NEAR_DELTA_TIME = Duration.ofMinutes(5);
    // NEAR_DELTA is the maximum line difference between two log segments to be considered "near"
    public static final int NEAR_DELTA_LINES = 5000;
    private final String shortid;

    private String id;
    private Instant startTime; // Nanosecond-precision timestamp
    private Instant endTime; // Nanosecond-precision timestamp
    private int startLine; // Start row number
    private int size;

    // Initial target timestamp to carry over when the first fetch did not initialize the
    // segment (empty result or a failed/truncated query).
    private Instant initialTargetTimestamp;
    private SearchHit initialTargetHit;

    public LogSegment(Instant timestamp) {
        this.startTime = timestamp;
        this.endTime = timestamp;
        this.startLine = 0;
        this.id = segmentId(startTime);
        this.shortid = Long.toHexString(startTime.toEpochMilli());
        this.size = 0;
    }

    /**
     * TEST ONLY CONSTRUCTOR
     *
     * @param startTime the segment's start timestamp (inclusive)
     * @param endTime   the segment's end timestamp (exclusive)
     * @param startLine the global row number of the segment's first line
     * @param size      the number of log lines the segment covers
     */
    public LogSegment(Instant startTime, Instant endTime, int startLine, int size) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.startLine = startLine;
        this.id = segmentId(startTime);
        this.shortid = Long.toHexString(startTime.toEpochMilli());
        this.size = size;
        log.warn("Using TEST ONLY LogSegment constructor {}", this);
    }

    /**
     * Builds the segment ID: zero-padded epoch milliseconds plus the full
     * nanosecond-of-second, e.g. {@code 0000001684041600000-123456789}.
     * <p>
     * <b>ID contract:</b> the ID is a durable, opaque key — never a timestamp
     * encoding to be parsed. {@link se.grynna.pager.db.DatabaseManager} persists
     * log rows in H2 MVStore maps named {@code segment<id>}, so the exact string
     * format must not change (existing databases key their rows by it), and
     * nothing parses the ID back into a timestamp; segment ordering uses
     * {@link #compareTo} on {@code startTime}, not the ID.
     * <p>
     * The millis + nano-of-second pair is redundant (the milliseconds are
     * contained in the nano-of-second) but lossless and unique: the pair
     * determines the start {@link Instant} exactly, at nanosecond precision,
     * and the fixed-width zero-padded fields make lexicographic order match
     * chronological order. The ID is computed once from the initial start time
     * and stays stable while {@link #init} and {@link #add} extend the segment's
     * time bounds, so cached rows keep their key as the segment grows.
     *
     * @param startTime the segment's initial start timestamp
     * @return the stable segment ID used as the H2 log-cache key
     */
    @SuppressWarnings("JavaInstantGetSecondsGetNano") // intentional: the ID retains nanosecond precision alongside epoch millis;
    // see Javadoc
    private static String segmentId(Instant startTime) {
        return String.format("%019d-%09d", startTime.toEpochMilli(), startTime.getNano());
    }

    /**
     * Preserves the timestamp this segment is centered on before its initial fetch.
     * <p>
     * If the first fetch fails (for example, when Splunk truncates the result), the
     * still-empty segment is initialized later by {@link #add(LoadDecision, LoadedLogLines)}.
     * That retry needs the original jump target so the event closest to the target is
     * assigned row zero, which is why the target must be recorded before fetching.
     *
     * @param target the initial jump target; must not be null
     */
    public void preserveInitialTarget(Instant target) {
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        this.initialTargetTimestamp = target;
        this.initialTargetHit = null;
    }

    /**
     * Preserves the exact search hit used for initialization. When several events
     * share the same Splunk time, this identity is used to place the requested
     * event on row zero.
     */
    public void preserveInitialTarget(SearchHit targetHit) {
        if (targetHit == null) {
            throw new IllegalArgumentException("targetHit cannot be null");
        }
        this.initialTargetTimestamp = targetHit.splunkTime();
        this.initialTargetHit = targetHit;
    }

    public int init(Instant target, LoadDecision decision, LoadedLogLines loadedLogLines) {
        return init(target, null, decision, loadedLogLines);
    }

    int init(Instant target, SearchHit targetHit, LoadDecision decision, LoadedLogLines loadedLogLines) {
        if (this.size != 0) {
            throw new IllegalStateException("Segment already initialized");
        }

        if (loadedLogLines == null) {
            throw new IllegalArgumentException("loadedLogLines cannot be null");
        }

        if (decision == null) {
            throw new IllegalArgumentException("decision cannot be null");
        }

        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }

        initialTargetTimestamp = target;
        initialTargetHit = targetHit;

        var logLines = loadedLogLines.logLines();

        if (!logLines.isEmpty()) {

            int closestIndex = getClosestIndex(target, targetHit, logLines);
            if (excludesHit(targetHit, decision, logLines)) {
                // The hit is a known event at its splunk time, but the half-open window
                // [start, end) ends at or before it (an earlier-direction retry after a
                // truncated initial load). Row zero is left just past the loaded lines
                // so the next later-direction load places the hit there.
                closestIndex = logLines.size();
            }

            // make sure that startLine = 0;
            int startLine = -closestIndex;
            this.startLine = startLine;
            this.size = logLines.size();
        }

        // extend timestamp to full decision range
        if (decision.start().isBefore(startTime)) {
            var oldStart = this.startTime;
            this.startTime = decision.start();
            log.debug("segment init: extended START with {} to {}", Duration.between(this.startTime, oldStart), this);
        }
        if (decision.end().isAfter(endTime)) {
            var oldEnd = this.endTime;
            this.endTime = decision.end();
            log.debug("segment init: extended END with {} to {}", Duration.between(oldEnd, this.endTime), this);
        }
        return this.startLine;
    }

    @Override
    public int compareTo(LogSegment o) {

        // Compare by start timestamp first
        int cmp = this.startTime.compareTo(o.getStartTime());

        // If start timestamps are equal, compare by end timestamp
        if (cmp == 0) {
            cmp = this.endTime.compareTo(o.getEndTime());
        }

        return cmp;
    }

    /**
     * Checks if a timestamp is contained within this segment.
     * Uses half-open interval [startTime, endTime) with INCLUSIVE start and EXCLUSIVE end.
     * This matches the standard convention and ensures adjacent segments don't overlap.
     *
     * @param timestamp the timestamp to check
     * @return true if startTime &lt;= timestamp &lt; endTime
     */
    public boolean contains(Instant timestamp) {
        // Half-open interval [startTime, endTime): inclusive start, exclusive end
        // Consistent with contains(int line) which uses [startLine, startLine+size)
        return !timestamp.isBefore(startTime) && timestamp.isBefore(endTime);
    }

    /**
     * Checks if a line number is contained within this segment.
     * Uses half-open interval [startLine, startLine+size) with INCLUSIVE start and EXCLUSIVE end.
     * This matches the standard convention and ensures adjacent segments don't overlap.
     *
     * @param line the line number to check
     * @return true if startLine &lt;= line &lt; startLine+size
     */
    public boolean contains(int line) {
        // Half-open interval [startLine, startLine+size): inclusive start, exclusive end
        return line >= startLine && line < (startLine + size);
    }

    // offset from the edges of the segment.
    // negative values are before the segment, zero is inside the segment, positive values are after the segment.
    // 1 = first line after the segment (endLineExl)
    public int offset(int index) {

        if (index < startLine) {
            return index - startLine;
        }

        if (index == startLine) {
            if (size == 0) {
                // empty segments are always "after" whatever line we ask for
                // triggering prepending loads
                return -1;
            }
        }

        int endLineExl = getEndLineExl();
        if (index < endLineExl) {
            return 0;
        }

        return index - (endLineExl - 1);
    }

    public int size() {
        return size;
    }

    public int getEndLineExl() {
        return startLine + size;
    }

    public int add(LoadDecision decision, LoadedLogLines sortedEvents) {

        if (size == 0) {
            // Retry initialization: the first fetch did not initialize this segment, so
            // the target preserved before that fetch is used to locate the closest event.
            if (initialTargetTimestamp == null) {
                throw new IllegalStateException(
                        "Cannot initialize empty segment: initial target timestamp must be preserved before loading");
            }
            return init(initialTargetTimestamp, initialTargetHit, decision, sortedEvents);
        }

        // Validate that the loadDecision range is contiguous with the segment
        if (decision.end().compareTo(startTime) > 0 && decision.start().compareTo(getEndTime()) < 0) {
            throw new IllegalStateException(
                    "LoadDecision range is not contiguous with segment: " + decision + ", " + this);
        }

        // validate that the LoadedLogLines start/end is within the decision range
        if (sortedEvents.start().isBefore(decision.start()) || sortedEvents.end().isAfter(decision.end())) {
            throw new IllegalStateException(
                    "LoadedLogLines range is outside LoadDecision range: " + sortedEvents + ", " + decision);
        }

        boolean isPrepending = decision.end().compareTo(startTime) <= 0;

        var numLogs = sortedEvents.logLines().size();

        int lineNumber;
        if (isPrepending) {

            if (decision.end().isBefore(startTime)) {
                throw new IllegalStateException(
                        "LoadDecision end time is before segment start time: " + decision + ", " + this);
            }
            if (!decision.end().equals(startTime)) {
                throw new IllegalStateException("LoadDecision end time must equal segment start time when prepending: "
                        + decision + ", " + this);
            }

            // Prepending earlier logs

            lineNumber = startLine - numLogs;
            var oldStart = this.startTime;
            startTime = decision.start();
            startLine = lineNumber;
            log.info("segment add: extended START with {} to {} numLogs={}, decision={}",
                    Duration.between(this.startTime, oldStart), this, numLogs, decision);

        } else {

            if (decision.start().isAfter(endTime)) {
                throw new IllegalStateException(
                        "LoadDecision start time is after segment end time: " + decision + ", " + this);
            }
            if (!decision.start().equals(endTime)) {
                throw new IllegalStateException("LoadDecision start time must equal segment end time when appending: "
                        + decision + ", " + this);
            }

            // Appending later logs
            lineNumber = getEndLineExl();
            var oldEnd = this.endTime;
            endTime = decision.end();
            log.info("segment add: extended END with {} to {} numLogs={}, decision={}",
                    Duration.between(oldEnd, this.endTime), this, numLogs, decision);
        }

        size += numLogs;
        log.debug("logsegment add result: {}", this);

        return lineNumber;
    }

    /**
     * Whether the load decision excludes {@code hit}: the hit lies at or after the
     * decision's end and no line in {@code logLines} already carries the same event
     * identity, so the hit still needs to be fetched.
     */
    private static boolean excludesHit(SearchHit hit, LoadDecision decision, List<LoadedLogLine> logLines) {
        return hit != null && hit.hasIdentity() && !decision.end().isAfter(hit.splunkTime())
                && logLines.stream().noneMatch(line -> EventOrder.sameIdentity(line, hit));
    }

    /**
     * Find the index of the log line closest to the target timestamp using binary search.
     *
     * @param target       the timestamp to search for
     * @param preferredHit a search hit to prefer when it is present in {@code logLines}, or {@code null}
     * @param logLines     the loaded log lines to search, ordered by timestamp
     * @return the index of the closest line, or 0 when {@code logLines} is empty
     */
    private static int getClosestIndex(Instant target, SearchHit preferredHit, List<LoadedLogLine> logLines) {
        if (preferredHit != null && preferredHit.hasIdentity()) {
            for (int i = 0; i < logLines.size(); i++) {
                if (EventOrder.sameIdentity(logLines.get(i), preferredHit)) {
                    return i;
                }
            }
        }

        int closestIndex = 0;
        Duration closestDiff = null;

        int left = 0;
        int right = logLines.size() - 1;
        while (left <= right) {

            int mid = left + (right - left) / 2;
            Instant midTime = logLines.get(mid).getSplunkTime();
            if (midTime.equals(target)) {
                return preferEventStartForExactTime(target, mid, logLines);
            }

            Duration diff = Duration.between(midTime, target).abs();
            if (closestDiff == null || diff.compareTo(closestDiff) < 0) {
                closestDiff = diff;
                closestIndex = mid;
            }
            if (midTime.isBefore(target)) {
                left = mid + 1;
            } else if (midTime.isAfter(target)) {
                right = mid - 1;
            }
        }
        return closestIndex;
    }

    /**
     * Within an exact timestamp group, prefer the first non-continuation row so
     * row-zero placement targets event starts rather than continuation lines.
     */
    private static int preferEventStartForExactTime(Instant target, int mid, List<LoadedLogLine> logLines) {
        int start = mid;
        while (start > 0 && logLines.get(start - 1).getSplunkTime().equals(target)) {
            start--;
        }

        int end = mid;
        while (end < logLines.size() - 1 && logLines.get(end + 1).getSplunkTime().equals(target)) {
            end++;
        }

        for (int i = start; i <= end; i++) {
            if (!logLines.get(i).isContinuation()) {
                return i;
            }
        }

        return start;
    }

    @Override
    public String toString() {
        Duration duration = Duration.between(startTime, endTime);
        return "segment: %d-%d (%d) %s-%s (%s)".formatted(startLine, getEndLineExl(), size, startTime, endTime,
                duration);
    }

    public int distanceToStart(int lineNumber) {
        if (size == 0) {
            return 0;
        }
        return lineNumber - startLine;
    }

    public int distanceToEnd(int lineNumber) {
        if (size == 0) {
            return 0;
        }
        int endLineExl = getEndLineExl();
        return endLineExl - lineNumber;
    }
}
