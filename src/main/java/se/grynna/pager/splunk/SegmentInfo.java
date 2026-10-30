package se.grynna.pager.splunk;

import java.time.Duration;
import java.time.Instant;

/**
 * Snapshot of segment statistics for display purposes.
 * Contains timing, line count, and log rate information from the current segment.
 *
 * @param startTime Earliest timestamp in the loaded segment
 * @param endTime Latest timestamp in the loaded segment
 * @param startLine First line number in the segment
 * @param endLineExclusive Last line number (exclusive) in the segment
 * @param size Number of loaded lines in the segment
 * @param logRate Calculated log rate in logs per second (0 if undetermined)
 * @param timeSpan Duration covered by the segment
 * @param beforeBoundary Earliest search boundary
 * @param afterBoundary Latest search boundary
 * @param nowBoundary Current "now" boundary (now - offset for indexing delay)
 */
public record SegmentInfo(Instant startTime, Instant endTime, int startLine, int endLineExclusive, int size,
        double logRate, Duration timeSpan, Instant beforeBoundary, Instant afterBoundary, Instant nowBoundary) {

    /**
     * Create a SegmentInfo representing an uninitialized state.
     */
    public static SegmentInfo uninitialized() {
        return new SegmentInfo(null, null, 0, 0, 0, 0.0, Duration.ZERO, null, null, null);
    }

    /**
     * Check if this represents an uninitialized/empty state.
     */
    public boolean isInitialized() {
        return startTime != null && endTime != null && size > 0;
    }
}
