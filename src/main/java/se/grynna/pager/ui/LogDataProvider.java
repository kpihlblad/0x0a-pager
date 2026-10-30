package se.grynna.pager.ui;

import se.grynna.pager.LogStatistics;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SegmentInfo;

import java.time.Instant;
import java.util.Optional;

public interface LogDataProvider {

    int getMaxOobPerSide();

    /**
     * Listener for when search boundaries are reached and expansion might be needed.
     */
    @FunctionalInterface
    interface BoundaryExpansionListener {
        /**
         * Request user confirmation to expand a boundary.
         *
         * @param request the pending expansion: which boundary, the suggested new boundary,
         *                and the reply callback to accept or reject it (the UI must also call
         *                {@code setBeforeBoundary}/{@code setAfterBoundary} on the loading
         *                strategy after accepting).
         */
        void requestBoundaryExpansion(BoundaryRequest request);
    }

    LogLine getLine(int line, boolean edge) throws TemporarySearchException, SearchException, StorageException;

    /**
     * Return the line at {@code line} only if it is already loaded. Never schedules
     * a load, never reports boundaries or placeholders: empty when the line is not
     * available without loading. Safe to call from the EDT.
     */
    default Optional<LogLine> peekLine(int line) {
        return Optional.empty();
    }

    /** Schedule a background look-ahead around the visible rows, if needed. */
    default void preload(int firstVisible, int lastVisible) {}

    /**
     * Initialize the data provider, and jump to the latest log entry.
     *
     * @return The line number of the latest log entry.
     */
    int jumpToLatest() throws TemporarySearchException, SearchException, StorageException;

    /**
     * Jump to the log entry closest to the given timestamp.
     *
     * @param timestamp The timestamp to jump to.
     * @return The line number of the log entry closest to the given timestamp.
     * @throws TemporarySearchException recoverable search error
     * @throws SearchException          non-recoverable search error
     * @throws StorageException         storage error
     */
    int jump(Instant timestamp) throws TemporarySearchException, SearchException, StorageException;

    /**
     * Replace the browsing range with {@code [from, to)} and load at its start.
     * A null {@code to} follows now. Providers without a browsing range reject it.
     */
    default int jump(Instant target, Instant from, Instant to)
            throws TemporarySearchException, SearchException, StorageException {
        throw new UnsupportedOperationException("This provider has no browsing range");
    }

    /**
     * Jump to a specific search hit. Default implementation falls back to jump by
     * Splunk timestamp.
     */
    default int jump(SearchHit hit) throws TemporarySearchException, SearchException, StorageException {
        return jump(hit.splunkTime());
    }

    void unfreezeBeforeBoundary();

    void unfreezeAfterBoundary();

    boolean cancelSearch();

    /**
     * Get current log statistics.
     *
     * @return LogStatistics
     */
    LogStatistics getStatistics();

    /**
     * Indicate the current search boundaries.
     */
    Boundary getBeforeBoundary();

    void setBeforeBoundary(Instant t);

    Boundary getAfterBoundary();

    void setAfterBoundary(Instant t);

    Boundary getNowBoundary();

    /**
     * Set the listener for boundary expansion prompts.
     *
     * @param listener The listener to handle boundary expansion requests
     */
    void setBoundaryExpansionListener(BoundaryExpansionListener listener);

    /**
     * Get current segment information including timing, line counts, and log rate.
     * Returns SegmentInfo.uninitialized() if no segment is loaded.
     *
     * @return SegmentInfo with current segment statistics
     */
    SegmentInfo getSegmentInfo();
}
