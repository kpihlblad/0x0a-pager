package se.grynna.pager.splunk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * How the filter fields of an index are sampled. Summarising every event in a fixed window is
 * slow on a busy index (minutes on a large cluster) and finds nothing on a quiet one, so the
 * summary runs over the newest {@link #SAMPLE_CAP} events only and looks further back while the
 * sample is small. The windows end at the index's newest event (the anchor, found with the cheap
 * {@link #latestEventQuery}), so an index without recent data is sampled where its data is; each
 * step reaches back {@link #LOOKBACKS} from there, and the last step starts at
 * {@link SearchBoundaries#MIN_MINIMUM_BOUNDARY} (epoch plus one year; Splunk does not accept a
 * search from the epoch). Splunk reads newest first and {@code head} stops the search once the cap
 * is reached, so a wide window costs
 * little on a busy index.
 * <p>
 * A depth from 0 to {@link #MAX_DEPTH} scales the sample: each level samples ten times more
 * events and widens the window until it has ten times more. Depth 0 is the automatic summary;
 * the user asks for the deeper ones.
 */
public final class FieldSampling {

    /** The deepest level: a sample of a million events. */
    public static final int MAX_DEPTH = 2;

    /** Values kept per field; the filter rows offer them in their drop-downs. */
    public static final int MAX_VALUES = 100;

    /** The window of each step, smallest first. */
    public static final List<Duration> LOOKBACKS = List.of(Duration.ofHours(1), Duration.ofDays(1),
            Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(365));

    // Fields that never make good filters: free text, timestamps and per-event identifiers.
    private static final String EXCLUDED_FIELDS = """
            "@timestamp", "TimeStamp", "log", "cause", "content", "message", "punct", "stackTrace", \
            "stack_trace", "stream", "threadID", "kubernetes.labels.controller-revision-hash", \
            "kubernetes.labels.controller-uid", "kubernetes.labels.pod-template-hash", \
            "kubernetes.pod_uid", "linecount\"""";

    private FieldSampling() {}

    /** The number of windows: one per {@link #LOOKBACKS} entry, then back to the search floor. */
    public static final int STEPS = LOOKBACKS.size() + 1;

    /** The column of {@link #latestEventQuery}'s single row: the newest event's epoch seconds. */
    public static final String LATEST_FIELD = "latest";

    /** The time of {@code index}'s newest event, from the index metadata (no events are read). */
    public static String latestEventQuery(String index) {
        return "| tstats max(_time) as " + LATEST_FIELD + " where " + IndexSelection.parse(index).predicate();
    }

    /**
     * Like {@link #latestEventQuery} but reads the newest event instead of the index metadata.
     * Used when Splunk rejects the {@code tstats} query: a role's search filter is ANDed into
     * its WHERE clause, and a filter that is not an exact indexed-field query fails it with
     * "WHERE clause is not an exact query".
     */
    public static String latestEventFallbackQuery(String index) {
        return "search " + IndexSelection.parse(index).predicate() + " | head 1 | eval " + LATEST_FIELD
                + "=_time | fields " + LATEST_FIELD;
    }

    /**
     * The start of {@code step}'s window, which ends at {@code anchor}. The last step, and any
     * lookback that would pass it, starts at {@link SearchBoundaries#MIN_MINIMUM_BOUNDARY}.
     */
    public static Instant windowStart(int step, Instant anchor) {
        Instant start = step < LOOKBACKS.size()
                ? anchor.minus(LOOKBACKS.get(step))
                : SearchBoundaries.MIN_MINIMUM_BOUNDARY;
        return start.isBefore(SearchBoundaries.MIN_MINIMUM_BOUNDARY) ? SearchBoundaries.MIN_MINIMUM_BOUNDARY : start;
    }

    /** The exclusive end of every window: just past the newest event at {@code anchor}. */
    public static Instant windowEnd(Instant anchor) {
        return anchor.plusSeconds(1);
    }

    /** {@code depth} clamped to {@code [0, MAX_DEPTH]}. */
    public static int clampDepth(int depth) {
        return Math.clamp(depth, 0, MAX_DEPTH);
    }

    /** The newest events per index that the field summary looks at: 10k, 100k, 1M. */
    public static int sampleCap(int depth) {
        return 10_000 * pow10(clampDepth(depth));
    }

    /** A sample of at least this many events is good enough; a smaller one widens the window. */
    public static int enoughEvents(int depth) {
        return 1_000 * pow10(clampDepth(depth));
    }

    /** The field-summary search for {@code index}, capped to the newest {@link #sampleCap} events. */
    public static String query(String index, int depth) {
        return query(Scope.ofValues(index, java.util.Map.of()), depth);
    }

    /**
     * The field-summary search for {@code scope}, capped to the newest {@link #sampleCap} matching events.
     * Filters by {@link SplQuery#scope(Scope)} before {@code head} and {@code fieldsummary}.
     */
    public static String query(Scope scope, int depth) {
        String base = scope == null || scope.indexSelection().isNone()
                ? "search " + IndexSelection.parse(scope == null ? null : scope.index()).predicate()
                : SplQuery.scope(scope);
        return base + " | head " + sampleCap(depth)
                + " | fieldsummary maxvals=" + MAX_VALUES + " | where NOT field IN (" + EXCLUDED_FIELDS + ")";
    }

    /**
     * Whether a step that sampled {@code sampledEvents} events ends the search: the sample is
     * big enough for {@code depth}, or {@code step} was the widest window.
     */
    public static boolean isDone(int step, long sampledEvents, int depth) {
        return sampledEvents >= enoughEvents(depth) || step >= STEPS - 1;
    }

    /** A short label of a sample size: {@code 10k}, {@code 100k}, {@code 1M}. */
    public static String sizeLabel(int events) {
        return events >= 1_000_000 && events % 1_000_000 == 0
                ? events / 1_000_000 + "M"
                : events >= 1_000 && events % 1_000 == 0 ? events / 1_000 + "k" : String.valueOf(events);
    }

    private static int pow10(int n) {
        int result = 1;
        for (int i = 0; i < n; i++) {
            result *= 10;
        }
        return result;
    }
}
