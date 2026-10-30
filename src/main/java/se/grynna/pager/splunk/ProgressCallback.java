package se.grynna.pager.splunk;

/**
 * Generalized callback interface for reporting progress of various load operations
 * (index lookup, field lookup, log load).
 */
@FunctionalInterface
public interface ProgressCallback {
    /**
     * Called periodically as the operation progresses.
     *
     * @param progress Current progress information
     */
    void onProgress(SearchProgress progress);
}
