package se.grynna.pager.splunk;

import java.time.Instant;

/**
 * Represents the progress of a Splunk search job.
 * Used for monitoring and debugging search execution.
 */
public record SearchProgress(String jobId, Instant start, Instant end, boolean indeterminate, boolean isReady,
        boolean isDone, boolean isFailed, int scanCount, int eventCount, int resultCount, int resultPreviewCount,
        float doneProgress, float runDuration, String statusText, boolean isFinalized, int direction) {
    private static final String WAITING_FOR_SPLUNK = "Waiting for Splunk";

    // Compatibility constant replacing the previous Lombok-built FAIL instance
    public static final SearchProgress FAIL = new SearchProgress("N/A", null, null, true, false, true, true, 0, 0, 0, 0,
            0.0f, 0.0f, "Search failed", true, 0);

    /** A pending load waiting for capacity, before a Splunk job has been started. */
    public static SearchProgress waitingForSplunk(LoadDecision decision) {
        return new SearchProgress("N/A", decision.start(), decision.end(), true, false, false, false, 0, 0, 0, 0,
                0.0f, 0.0f, WAITING_FOR_SPLUNK, false, decision.direction());
    }

    public boolean isWaitingForSplunk() {
        return !isDone && indeterminate && WAITING_FOR_SPLUNK.equals(statusText);
    }

    @Override
    public String toString() {
        return "SearchProgress{" + "doneProgress=" + doneProgress + ", jobId='" + jobId + '\'' + ", start=" + start
                + ", end=" + end + ", indeterminate=" + indeterminate + ", isReady=" + isReady + ", isDone=" + isDone
                + ", isFailed=" + isFailed + ", scanCount=" + scanCount + ", eventCount=" + eventCount
                + ", resultCount=" + resultCount + ", resultPreviewCount=" + resultPreviewCount + ", runDuration="
                + runDuration + ", statusText='" + statusText + '\'' + ", isFinalized=" + isFinalized + ", direction="
                + direction + '}';
    }
}
