package se.grynna.pager.utils;

import com.splunk.Job;
import com.splunk.SplunkException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Supplier;

public class JobFormatter {
    /**
     * Formats a SplunkSDK Job object into a pretty, human-readable string.
     * The output is organized into sections, with properties displayed as key-value pairs.
     *
     * @param job The Job object to format.
     * @return A formatted string representation of the job.
     */
    public static String formatJob(Job job) {
        // Refresh the job to get the latest data
        job.refresh();
        StringBuilder sb = new StringBuilder();

        sb.append("Job Details:\n");

        // Job Identification
        sb.append("Job Identification:\n");
        appendProperty(sb, "SID", job.getSid());
        appendProperty(sb, "Name", job.getName());
        appendProperty(sb, "Label", getPropertySafely(() -> job.getLabel() != null ? job.getLabel() : "N/A"));

        // Search Query
        sb.append("\nSearch Query:\n");
        appendProperty(sb, "Search", getPropertySafely(() -> job.getSearch() != null ? job.getSearch() : "N/A"));
        appendProperty(sb, "Event Search",
                getPropertySafely(() -> job.getEventSearch() != null ? job.getEventSearch() : "N/A"));
        appendProperty(sb, "Report Search",
                getPropertySafely(() -> job.getReportSearch() != null ? job.getReportSearch() : "N/A"));
        appendProperty(sb, "Remote Search",
                getPropertySafely(() -> job.getRemoteSearch() != null ? job.getRemoteSearch() : "N/A"));

        // Time Ranges
        sb.append("\nTime Ranges:\n");
        appendProperty(sb, "Earliest Time", getPropertySafely(() -> formatDate(job.getEarliestTime())));
        appendProperty(sb, "Latest Time", getPropertySafely(() -> formatDate(job.getLatestTime())));
        appendProperty(sb, "Cursor Time", getPropertySafely(() -> formatDate(job.getCursorTime())));
        appendProperty(sb, "Search Earliest Time",
                getPropertySafely(() -> job.getSearchEarliestTime() != null ? job.getSearchEarliestTime() : "N/A"));
        appendProperty(sb, "Search Latest Time",
                getPropertySafely(() -> job.getSearchLatestTime() != null ? job.getSearchLatestTime() : "N/A"));

        // Status
        sb.append("\nStatus:\n");
        appendProperty(sb, "Dispatch State",
                getPropertySafely(() -> job.getDispatchState() != null ? job.getDispatchState() : "N/A"));
        appendProperty(sb, "Is Done", getPropertySafely(() -> job.isDone() ? "Yes" : "No"));
        appendProperty(sb, "Is Failed", getPropertySafely(() -> job.isFailed() ? "Yes" : "No"));
        appendProperty(sb, "Is Finalized", getPropertySafely(() -> job.isFinalized() ? "Yes" : "No"));
        appendProperty(sb, "Is Paused", getPropertySafely(() -> job.isPaused() ? "Yes" : "No"));
        appendProperty(sb, "Is Zombie", getPropertySafely(() -> job.isZombie() ? "Yes" : "No"));

        // Progress
        sb.append("\nProgress:\n");
        appendProperty(sb, "Done Progress", getPropertySafely(() -> String.format("%.2f", job.getDoneProgress())));
        appendProperty(sb, "Run Duration", getPropertySafely(() -> String.format("%.2f", job.getRunDuration())));

        // Event Information
        sb.append("\nEvent Information:\n");
        appendProperty(sb, "Event Count", getPropertySafely(() -> String.valueOf(job.getEventCount())));
        appendProperty(sb, "Event Count Long", getPropertySafely(() -> String.valueOf(job.getEventCountLong())));
        appendProperty(sb, "Event Available Count",
                getPropertySafely(() -> String.valueOf(job.getEventAvailableCount())));
        appendProperty(sb, "Event Field Count", getPropertySafely(() -> String.valueOf(job.getEventFieldCount())));
        appendProperty(sb, "Event Is Streaming", getPropertySafely(() -> job.getEventIsStreaming() ? "Yes" : "No"));
        appendProperty(sb, "Event Is Truncated", getPropertySafely(() -> job.getEventIsTruncated() ? "Yes" : "No"));
        appendProperty(sb, "Event Sorting",
                getPropertySafely(() -> job.getEventSorting() != null ? job.getEventSorting() : "N/A"));

        // Result Information
        sb.append("\nResult Information:\n");
        appendProperty(sb, "Result Count", getPropertySafely(() -> String.valueOf(job.getResultCount())));
        appendProperty(sb, "Result Count Long", getPropertySafely(() -> String.valueOf(job.getResultCountLong())));
        appendProperty(sb, "Result Is Streaming", getPropertySafely(() -> job.getResultIsStreaming() ? "Yes" : "No"));
        appendProperty(sb, "Result Preview Count",
                getPropertySafely(() -> String.valueOf(job.getResultPreviewCount())));

        // Other Properties
        sb.append("\nOther:\n");
        appendProperty(sb, "Delegate", getPropertySafely(() -> job.getDelegate() != null ? job.getDelegate() : "N/A"));
        appendProperty(sb, "Disk Usage", getPropertySafely(() -> String.valueOf(job.getDiskUsage())));
        appendProperty(sb, "Drop Count", getPropertySafely(() -> String.valueOf(job.getDropCount())));
        appendProperty(sb, "Keywords", getPropertySafely(() -> job.getKeywords() != null ? job.getKeywords() : "N/A"));
        appendProperty(sb, "Num Previews", getPropertySafely(() -> String.valueOf(job.getNumPreviews())));
        appendProperty(sb, "Priority", getPropertySafely(() -> String.valueOf(job.getPriority())));
        appendProperty(sb, "Scan Count", getPropertySafely(() -> String.valueOf(job.getScanCount())));
        appendProperty(sb, "Scan Count Long", getPropertySafely(() -> String.valueOf(job.getScanCountLong())));
        appendProperty(sb, "Search Providers", getPropertySafely(() -> {
            String[] providers = job.getSearchProviders();
            return providers != null && providers.length > 0 ? String.join(", ", providers) : "N/A";
        }));
        appendProperty(sb, "Status Buckets", getPropertySafely(() -> String.valueOf(job.getStatusBuckets())));
        appendProperty(sb, "TTL", getPropertySafely(() -> String.valueOf(job.getTtl())));
        appendProperty(sb, "Is Preview Enabled", getPropertySafely(() -> job.isPreviewEnabled() ? "Yes" : "No"));
        appendProperty(sb, "Is Real Time Search", getPropertySafely(() -> job.isRealTimeSearch() ? "Yes" : "No"));
        appendProperty(sb, "Is Remote Timeline", getPropertySafely(() -> job.isRemoteTimeline() ? "Yes" : "No"));
        appendProperty(sb, "Is Saved", getPropertySafely(() -> job.isSaved() ? "Yes" : "No"));
        appendProperty(sb, "Is Saved Search", getPropertySafely(() -> job.isSavedSearch() ? "Yes" : "No"));

        return sb.toString();
    }

    /**
     * Appends a property name and value to the StringBuilder.
     *
     * @param sb The StringBuilder to append to.
     * @param name The property name.
     * @param value The property value.
     */
    private static void appendProperty(StringBuilder sb, String name, String value) {
        sb.append(name).append(": ").append(value).append("\n");
    }

    /**
     * Safely retrieves a property value, handling exceptions if the job is not ready.
     *
     * @param supplier A supplier that provides the property value as a string.
     * @return The property value, or an appropriate message if unavailable.
     */
    private static String getPropertySafely(Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (SplunkException e) {
            if (e.getCode() == SplunkException.JOB_NOTREADY) {
                return "Not available (job not ready)";
            } else {
                throw e;
            }
        }
    }

    /**
     * Formats a Date object into a readable string.
     *
     * @param date The Date to format.
     * @return The formatted date string, or "N/A" if null.
     */
    private static String formatDate(Date date) {
        if (date == null) {
            return "N/A";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(date);
    }
}
