package se.grynna.pager.db;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.splunk.FieldSampling;
import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.JobTicket;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplQuery;
import se.grynna.pager.splunk.SplunkClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Manages search filters and indexes with async updates and caching.
 * Provides cached data immediately while refreshing in the background,
 * notifying listeners when fresh data is available.
 *
 * <h2>Refresh ownership</h2>
 * At most one index refresh and one field-summary refresh run at a time, both on this
 * manager's executor. Starting a new refresh supersedes (and interrupts) the running one
 * of the same kind. Only the current refresh may write to the database or notify
 * listeners; that check and the write happen under {@link #lock}, and {@link #shutdown()}
 * retires every refresh under the same lock, so nothing is written after shutdown.
 *
 * <h2>Freshness</h2>
 * Cached indexes, and cached field summaries per index, are considered fresh for
 * {@link #CACHE_TTL} after a successful refresh in this session. Data loaded from the
 * database without a refresh in this session is stale.
 *
 * <h2>Listener contract</h2>
 * Listeners are called on the refresh thread while holding the manager lock, so they
 * are delivered in order; they must not block (hand off to the EDT instead).
 */
@Slf4j
public class SearchFilterManager {

    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    static final int QUERY_CACHE_MAX_SIZE = 32;

    private record CachedQuerySummary(String index, int depth, SortedSet<FieldSummary> summaries,
            Instant refreshedAt) {}

    private final Object lock = new Object();

    private final DatabaseManager databaseManager;
    private final SearchManager searchManager;
    private final ExecutorService executorService;
    /** Serializes filter writes so an older snapshot can never overwrite a newer one. */
    private final ExecutorService filterWriter;

    // Ignored fields per index (fields to exclude from results)
    // TODO(roadmap): wire into "select exclusions from LogDetails view" / log filtering options
    @SuppressWarnings("UnusedVariable")
    private final Map<String, Set<String>> ignoredFields = new ConcurrentHashMap<>();

    /** Latest filter snapshot per index that is queued but not yet written. */
    private final Map<String, Map<String, List<String>>> pendingFilters = new ConcurrentHashMap<>();
    private final Map<String, ScopeTerms> pendingTerms = new ConcurrentHashMap<>();
    /** Empty means the default message fields; the map cannot hold null. */
    private final Map<String, Optional<List<String>>> pendingMessageFields = new ConcurrentHashMap<>();

    private final Clock clock;

    // Guarded by lock
    private RefreshTask<SortedSet<String>> indexTask;
    private RefreshTask<SortedSet<FieldSummary>> fieldTask;
    private Instant lastIndexRefresh;
    private final Map<String, Instant> lastFieldRefresh = new HashMap<>();
    // Sample depth per index (FieldSampling); raised by deepenFieldSummaries, kept for the session
    private final Map<String, Integer> fieldDepth = new HashMap<>();
    private final Map<String, CachedQuerySummary> queryFieldCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedQuerySummary> eldest) {
            return size() > QUERY_CACHE_MAX_SIZE;
        }
    };
    private boolean closed;

    public SearchFilterManager(DatabaseManager databaseManager, SearchManager searchManager, Clock clock) {
        this.clock = clock;
        this.databaseManager = databaseManager;
        this.searchManager = searchManager;
        this.executorService = Executors
                .newThreadPerTaskExecutor(Thread.ofVirtual().name("pager-metadata-", 0).factory());
        this.filterWriter = Executors.newSingleThreadExecutor();

        // Load cached indexes from database on startup
        initializeFromDatabase();
    }

    /**
     * Listener for index updates.
     */
    @FunctionalInterface
    public interface IndexUpdateListener {
        /**
         * Called when indexes are updated.
         *
         * @param indexes   The updated list of indexes
         * @param fromCache True if this was from cache, false if freshly fetched
         */
        void onIndexesUpdated(SortedSet<String> indexes, boolean fromCache);

        /**
         * Called when the current index refresh fails. Not called for superseded
         * refreshes or after shutdown.
         */
        default void onIndexRefreshFailed(Throwable error) {}
    }

    /**
     * Listener for field summary updates.
     */
    @FunctionalInterface
    public interface FieldSummaryUpdateListener {
        /**
         * Called when field summaries are updated for an index.
         *
         * @param index          The index name
         * @param fieldSummaries The updated field summaries
         * @param fromCache      True if this was from cache, false if freshly fetched
         */
        void onFieldSummariesUpdated(String index, SortedSet<FieldSummary> fieldSummaries, boolean fromCache);

        /**
         * Called when field summaries are updated for {@code scope}.
         */
        default void onScopeFieldSummariesUpdated(Scope scope, SortedSet<FieldSummary> fieldSummaries,
                boolean fromCache) {
            onFieldSummariesUpdated(scope == null ? null : scope.index(), fieldSummaries, fromCache);
        }

        /**
         * Called when the current field-summary refresh for {@code index} fails. Not
         * called for superseded refreshes or after shutdown.
         */
        default void onFieldSummaryRefreshFailed(String index, Throwable error) {}

        /**
         * Called when the current field-summary refresh for {@code scope} fails. Not
         * called for superseded refreshes or after shutdown.
         */
        default void onScopeFieldSummaryRefreshFailed(Scope scope, Throwable error) {
            onFieldSummaryRefreshFailed(scope == null ? null : scope.index(), error);
        }
    }

    /**
     * One background refresh. {@code worker} is the executor task (interrupted on
     * cancel), {@code ticket} its Splunk jobs (cancelled by SID); {@code result} is what
     * callers observe.
     */
    private static final class RefreshTask<T> {
        private final String index;
        private final Scope scope;
        private final String queryKey;
        private final boolean explicitScope;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final JobTicket ticket = new JobTicket();
        private Future<?> worker;

        private RefreshTask(String index) {
            this(index, Scope.ofValues(index, Map.of()), false);
        }

        private RefreshTask(String index, Scope scope, boolean explicitScope) {
            this.index = index;
            this.scope = scope;
            this.queryKey = scope == null || scope.indexSelection().isNone() ? "" : SplQuery.scope(scope);
            this.explicitScope = explicitScope;
        }

        private void cancel(SplunkClient client) {
            result.cancel(false);
            if (worker != null) {
                worker.cancel(true);
            }
            if (client != null) {
                client.cancelJobs(ticket);
            }
        }
    }

    /**
     * Initialize cache from database on startup.
     * This provides immediate data availability without waiting for Splunk.
     */
    private void initializeFromDatabase() {
        try {
            SortedSet<String> storedIndexes = databaseManager.loadIndexes();
            if (!storedIndexes.isEmpty()) {
                log.info("initializeFromDatabase: Loaded {} indexes from database (marked as stale)",
                        storedIndexes.size());
                return;
            }
            log.info("initializeFromDatabase: No stored indexes found in database");
        } catch (Exception e) {
            log.warn("initializeFromDatabase: Failed to load from database", e);
        }
    }

    private boolean isFresh(Instant lastRefresh) {
        return lastRefresh != null && clock.instant().isBefore(lastRefresh.plus(CACHE_TTL));
    }

    /**
     * Get the cached indexes and refresh them in the background when needed.
     * A refresh starts when the cache is empty or has expired, unless one is already
     * running. Use {@link #refreshIndexes()} to force a refresh.
     *
     * @return the cached indexes (possibly empty)
     */
    public SortedSet<String> loadIndexes() {
        var result = databaseManager.loadIndexes();

        synchronized (lock) {
            boolean running = indexTask != null;
            boolean fresh = isFresh(lastIndexRefresh);
            if (!running && (result.isEmpty() || !fresh)) {
                // Fire-and-forget: runIndexRefresh logs failures and notifies listeners,
                // but observe the future so an exceptional completion is never dropped.
                // Cancellation means the refresh was superseded or the manager shut down.
                startIndexRefresh().exceptionally(e -> {
                    if (!(e instanceof CancellationException)) {
                        log.warn("loadIndexes: Background index refresh failed", e);
                    }
                    return null;
                });
            }
            log.debug("loadIndexes: Returning {} cached indexes (fresh={}, running={})", result.size(), fresh, running);
        }
        return result;
    }

    /**
     * Explicitly refresh indexes from Splunk.
     * Use this when user clicks refresh button.
     *
     * @return CompletableFuture with the refreshed indexes
     */
    public CompletableFuture<SortedSet<String>> refreshIndexes() {
        log.info("refreshIndexes: Explicit refresh requested");
        synchronized (lock) {
            return startIndexRefresh();
        }
    }

    /**
     * Start an index refresh, superseding any running one. Caller holds {@link #lock}.
     */
    private CompletableFuture<SortedSet<String>> startIndexRefresh() {
        if (closed) {
            return CompletableFuture.failedFuture(new CancellationException("SearchFilterManager is shut down"));
        }
        if (indexTask != null) {
            log.info("startIndexRefresh: Superseding running index refresh");
            indexTask.cancel(searchManager.getSplunkClient());
        }
        var task = new RefreshTask<SortedSet<String>>(null);
        indexTask = task;
        task.worker = executorService.submit(() -> runIndexRefresh(task));
        return task.result;
    }

    private void runIndexRefresh(RefreshTask<SortedSet<String>> task) {
        try {
            log.info("job IndexRefresh: Fetching from Splunk");
            SortedSet<String> indexes = searchManager.getSplunkClient()
                    .loadIndexes(searchManager.getIndexProgressCallback(), task.ticket);
            synchronized (lock) {
                if (indexTask != task || closed) {
                    log.info("job IndexRefresh: Superseded, discarding {} indexes", indexes.size());
                    task.result.cancel(false);
                    return;
                }
                var mergedIndexes = new TreeSet<>(databaseManager.loadIndexes());
                mergedIndexes.addAll(indexes);
                databaseManager.storeIndexes(mergedIndexes);
                lastIndexRefresh = clock.instant();
                indexTask = null;
                log.info("job IndexRefresh: Fetched {} merged {} indexes", indexes.size(), mergedIndexes.size());
                notifyIndexesUpdated(mergedIndexes);
            }
            task.result.complete(indexes);
        } catch (Throwable e) {
            synchronized (lock) {
                if (indexTask == task && !closed) {
                    indexTask = null;
                    log.error("job IndexRefresh: Failed to fetch indexes", e);
                    notifyIndexRefreshFailed(e);
                } else {
                    log.info("job IndexRefresh: Superseded refresh ended: {}", e.toString());
                }
            }
            task.result.completeExceptionally(e);
        }
    }

    /**
     * Get the cached field summaries for an index and refresh them in the background
     * when needed. A refresh starts when {@code forceRefresh} is set, the cache is empty,
     * or the cache for this index has expired. An unforced call joins a refresh for the
     * same index that is already running; a refresh for another index is superseded.
     * Fresh data is delivered to the {@link FieldSummaryUpdateListener}.
     *
     * @param index        The index name
     * @param forceRefresh If true, always start a new refresh
     * @return the cached field summaries (possibly empty)
     */
    public SortedSet<FieldSummary> loadFieldSummaries(String index, boolean forceRefresh) {
        log.info("getFieldSummaries: index={}, forceRefresh={}", index, forceRefresh);
        if (index == null || index.trim().isEmpty()) {
            log.debug("getFieldSummaries: Empty index, returning empty list");
            return Collections.emptySortedSet();
        }
        return loadFieldSummaries(Scope.ofValues(index, Map.of()), forceRefresh, false);
    }

    /**
     * Get the cached field summaries for {@code scope} and refresh them in the background when needed.
     * Summaries are cached in memory per effective query string ({@link SplQuery#scope(Scope)}) at the index's
     * current sample depth and expire after {@link #CACHE_TTL}. For an unfiltered index scope, persisted database
     * summaries are returned immediately while a stale refresh runs.
     */
    public SortedSet<FieldSummary> loadFieldSummariesForScope(Scope scope, boolean forceRefresh) {
        if (scope == null || scope.index() == null || scope.index().trim().isEmpty()) {
            return Collections.emptySortedSet();
        }
        return loadFieldSummaries(scope, forceRefresh, true);
    }

    private SortedSet<FieldSummary> loadFieldSummaries(Scope scope, boolean forceRefresh, boolean explicitScope) {
        String index = scope.index();
        String queryKey = SplQuery.scope(scope);
        boolean unfiltered = isUnfiltered(scope);

        SortedSet<FieldSummary> dbCached = unfiltered
                ? databaseManager.loadFieldSummaries(index)
                : Collections.emptySortedSet();

        synchronized (lock) {
            int depth = fieldDepth.getOrDefault(index, 0);
            CachedQuerySummary memCached = queryFieldCache.get(queryKey);
            boolean memHit = memCached != null && memCached.depth() == depth && isFresh(memCached.refreshedAt());
            SortedSet<FieldSummary> result = memHit ? memCached.summaries() : dbCached;
            boolean fresh = memHit || (unfiltered && !dbCached.isEmpty() && isFresh(lastFieldRefresh.get(index)));
            boolean running = fieldTask != null && fieldTask.queryKey.equals(queryKey);
            if (forceRefresh || (!running && !fresh)) {
                log.debug("getFieldSummaries: Triggering refresh for query='{}' size={}", queryKey, result.size());
                startFieldSummaryRefresh(scope, explicitScope);
            }
            log.debug("getFieldSummaries: Returning {} cached items for query='{}'", result.size(), queryKey);
            return result;
        }
    }

    private static boolean isUnfiltered(Scope scope) {
        return scope.manualSpl() == null && scope.fieldFilters().isEmpty() && !scope.hasTerms();
    }

    /**
     * Sample the fields of {@code index} one level deeper than before (more events, further
     * back; see {@link FieldSampling}) and refresh them. The depth stays for later refreshes of
     * the index in this session.
     *
     * @return the cached field summaries (possibly empty)
     */
    public SortedSet<FieldSummary> deepenFieldSummaries(String index) {
        if (index == null || index.trim().isEmpty()) {
            return Collections.emptySortedSet();
        }
        return deepenFieldSummaries(Scope.ofValues(index, Map.of()), false);
    }

    /**
     * Sample the fields of {@code scope} one level deeper than before, invalidating cached query summaries for its
     * index selection so subsequent queries re-sample at the new depth.
     */
    public SortedSet<FieldSummary> deepenFieldSummariesForScope(Scope scope) {
        if (scope == null || scope.index() == null || scope.index().trim().isEmpty()) {
            return Collections.emptySortedSet();
        }
        return deepenFieldSummaries(scope, true);
    }

    private SortedSet<FieldSummary> deepenFieldSummaries(Scope scope, boolean explicitScope) {
        String index = scope.index();
        synchronized (lock) {
            int depth = FieldSampling.clampDepth(fieldDepth.getOrDefault(index, 0) + 1);
            fieldDepth.put(index, depth);
            invalidateFieldCacheForIndex(index);
            log.info("deepenFieldSummaries: index={} depth={} sample={}", index, depth,
                    FieldSampling.sampleCap(depth));
        }
        return loadFieldSummaries(scope, true, explicitScope);
    }

    /**
     * Drops cached query field summaries for {@code index} (or all indexes when {@code index} is null).
     */
    public void invalidateFieldCache(String index) {
        synchronized (lock) {
            if (index == null) {
                queryFieldCache.clear();
                lastFieldRefresh.clear();
            } else {
                invalidateFieldCacheForIndex(index);
                lastFieldRefresh.remove(index);
            }
        }
    }

    private void invalidateFieldCacheForIndex(String index) {
        queryFieldCache.values().removeIf(entry -> index.equals(entry.index()));
    }

    /** The number of newest events the fields of {@code index} are sampled from. */
    public int fieldSampleSize(String index) {
        synchronized (lock) {
            return FieldSampling.sampleCap(fieldDepth.getOrDefault(index, 0));
        }
    }

    /**
     * Start a field-summary refresh, superseding any running one. Caller holds {@link #lock}.
     */
    private void startFieldSummaryRefresh(Scope scope, boolean explicitScope) {
        if (closed) {
            return;
        }
        if (fieldTask != null) {
            log.info("startFieldSummaryRefresh: Superseding running refresh for {}", fieldTask.queryKey);
            fieldTask.cancel(searchManager.getSplunkClient());
        }
        var task = new RefreshTask<SortedSet<FieldSummary>>(scope.index(), scope, explicitScope);
        fieldTask = task;
        task.worker = executorService.submit(() -> runFieldSummaryRefresh(task));
    }

    private void runFieldSummaryRefresh(RefreshTask<SortedSet<FieldSummary>> task) {
        String index = task.index;
        Scope scope = task.scope;
        try {
            int depth;
            synchronized (lock) {
                depth = fieldDepth.getOrDefault(index, 0);
            }
            var newFieldSummaries = task.explicitScope
                    ? searchManager.getSplunkClient().loadFieldSummaryForScope(scope, depth,
                            searchManager.getFieldProgressCallback(), task.ticket)
                    : searchManager.getSplunkClient().loadFieldSummary(index, depth,
                            searchManager.getFieldProgressCallback(), task.ticket);
            SortedSet<FieldSummary> result;
            synchronized (lock) {
                if (fieldTask != task || closed) {
                    log.info("job FieldSummaryRefresh: Superseded, discarding {} summaries for {}",
                            newFieldSummaries.size(), task.queryKey);
                    task.result.cancel(false);
                    return;
                }
                Instant now = clock.instant();
                if (isUnfiltered(scope)) {
                    databaseManager.storeFieldSummaries(index, newFieldSummaries);
                    SortedSet<FieldSummary> loaded = databaseManager.loadFieldSummaries(index);
                    result = loaded.isEmpty() ? new TreeSet<>(newFieldSummaries) : loaded;
                    lastFieldRefresh.put(index, now);
                } else {
                    result = Collections.unmodifiableSortedSet(new TreeSet<>(newFieldSummaries));
                }
                queryFieldCache.put(task.queryKey, new CachedQuerySummary(index, depth,
                        Collections.unmodifiableSortedSet(new TreeSet<>(result)), now));
                fieldTask = null;
                log.debug("job FieldSummaryRefresh: Completed with {}({}) fields for {}", result.size(),
                        newFieldSummaries.size(), task.queryKey);
                notifyFieldSummariesUpdated(task, result);
            }
            task.result.complete(result);
        } catch (Throwable e) {
            synchronized (lock) {
                if (fieldTask == task && !closed) {
                    fieldTask = null;
                    log.error("job FieldSummaryRefresh: Failed for query {}", task.queryKey, e);
                    notifyFieldSummaryRefreshFailed(task, e);
                } else {
                    log.info("job FieldSummaryRefresh: Superseded refresh for {} ended: {}", task.queryKey,
                            e.toString());
                }
            }
            task.result.completeExceptionally(e);
        }
    }

    /**
     * Get field filters for an index. A snapshot that is queued for writing takes
     * precedence over the database, so a quick switch back to an index sees its latest
     * filters even before they are persisted.
     */
    public Map<String, List<String>> getFieldFilters(String index) throws StorageException {
        Map<String, List<String>> pending = pendingFilters.get(index);
        if (pending != null) {
            log.info("getFieldFilters: Using {} pending filters for index='{}'", pending.size(), index);
            return new TreeMap<>(pending);
        }
        Map<String, List<String>> filters = databaseManager.loadFieldFiltersForIndex(index);
        log.info("getFieldFilters: Loaded {} filters for index='{}'", filters.size(), index);
        return filters;
    }

    /**
     * Store field filters for an index. The write happens in the background, in the
     * order the snapshots were stored; {@link #getFieldFilters} sees the snapshot at once.
     *
     * @return completes when the snapshot is written, or exceptionally if it failed
     */
    public CompletableFuture<Void> storeFieldFilters(String index, Map<String, List<String>> filters) {
        Map<String, List<String>> snapshot = copyFilters(filters);
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new CancellationException("SearchFilterManager is shut down"));
            }
            pendingFilters.put(index, snapshot);
            return CompletableFuture.runAsync(() -> writeFieldFilters(index, snapshot), filterWriter);
        }
    }

    private static Map<String, List<String>> copyFilters(Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>(filters.size());
        filters.forEach((field, values) -> copy.put(field, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    private void writeFieldFilters(String index, Map<String, List<String>> snapshot) {
        try {
            databaseManager.storeFieldFiltersForIndex(index, snapshot);
            log.info("storeFieldFilters: Stored {} filters for index='{}'", snapshot.size(), index);
        } catch (StorageException e) {
            log.error("storeFieldFilters: Failed for index '{}'", index, e);
            throw new CompletionException(e);
        } finally {
            pendingFilters.remove(index, snapshot);
        }
    }

    /**
     * Get the saved narrowing and excluded terms of an index. Like field filters, a
     * snapshot that is queued for writing takes precedence over the database.
     */
    public ScopeTerms getScopeTerms(String index) throws StorageException {
        ScopeTerms pending = pendingTerms.get(index);
        if (pending != null) {
            return pending;
        }
        return databaseManager.loadScopeTermsForIndex(index);
    }

    /**
     * Store the narrowing and excluded terms of an index. Written in the background on
     * the same ordered writer as field filters; {@link #getScopeTerms} sees the snapshot
     * at once.
     *
     * @return completes when the snapshot is written, or exceptionally if it failed
     */
    public CompletableFuture<Void> storeScopeTerms(String index, ScopeTerms terms) {
        ScopeTerms snapshot = terms != null ? terms : ScopeTerms.EMPTY;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new CancellationException("SearchFilterManager is shut down"));
            }
            pendingTerms.put(index, snapshot);
            return CompletableFuture.runAsync(() -> writeScopeTerms(index, snapshot), filterWriter);
        }
    }

    private void writeScopeTerms(String index, ScopeTerms snapshot) {
        try {
            databaseManager.storeScopeTermsForIndex(index, snapshot);
            log.info("storeScopeTerms: Stored {} narrow and {} exclude terms for index='{}'",
                    snapshot.narrow().size(), snapshot.exclude().size(), index);
        } catch (StorageException e) {
            log.error("storeScopeTerms: Failed for index '{}'", index, e);
            throw new CompletionException(e);
        } finally {
            pendingTerms.remove(index, snapshot);
        }
    }

    /**
     * Get the message fields of an index: the ordered JSON field paths whose values make up
     * an event's message. Empty when the index uses the default fields. Like field filters,
     * a snapshot that is queued for writing takes precedence over the database.
     */
    public Optional<List<String>> getMessageFields(String index) throws StorageException {
        Optional<List<String>> pending = pendingMessageFields.get(index);
        if (pending != null) {
            return pending;
        }
        return databaseManager.loadMessageFieldsForIndex(index);
    }

    /**
     * Store the message fields of an index; null restores the default fields. Written in
     * the background on the same ordered writer as field filters; {@link #getMessageFields}
     * sees the snapshot at once.
     *
     * @return completes when the snapshot is written, or exceptionally if it failed
     */
    public CompletableFuture<Void> storeMessageFields(String index, List<String> fieldsOrNull) {
        Optional<List<String>> snapshot = Optional.ofNullable(fieldsOrNull).map(List::copyOf);
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new CancellationException("SearchFilterManager is shut down"));
            }
            pendingMessageFields.put(index, snapshot);
            return CompletableFuture.runAsync(() -> writeMessageFields(index, snapshot), filterWriter);
        }
    }

    private void writeMessageFields(String index, Optional<List<String>> snapshot) {
        try {
            databaseManager.storeMessageFieldsForIndex(index, snapshot.orElse(null));
            log.info("storeMessageFields: Stored {} message fields for index='{}'",
                    snapshot.map(List::size).orElse(0), index);
        } catch (StorageException e) {
            log.error("storeMessageFields: Failed for index '{}'", index, e);
            throw new CompletionException(e);
        } finally {
            pendingMessageFields.remove(index, snapshot);
        }
    }

    /**
     * Shutdown the manager and cleanup resources. Running refreshes are cancelled and
     * can no longer write or notify; queued filter writes are flushed first, so the
     * database can be closed once this returns.
     */
    public void shutdown() {
        log.info("Shutting down SearchFilterManager");

        synchronized (lock) {
            closed = true;
            if (indexTask != null) {
                indexTask.cancel(searchManager.getSplunkClient());
                indexTask = null;
            }
            if (fieldTask != null) {
                fieldTask.cancel(searchManager.getSplunkClient());
                fieldTask = null;
            }
        }

        awaitShutdown(filterWriter);
        awaitShutdown(executorService);
    }

    private static void awaitShutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            log.info("Waiting for executor service to terminate");
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // A failing listener must not turn a successful refresh into a failure.

    private void notifyIndexesUpdated(SortedSet<String> indexes) {
        var listener = searchManager.getIndexUpdateListener();
        if (listener == null) {
            return;
        }
        try {
            listener.onIndexesUpdated(indexes, false);
        } catch (RuntimeException e) {
            log.warn("notifyIndexesUpdated: Listener failed", e);
        }
    }

    private void notifyIndexRefreshFailed(Throwable error) {
        var listener = searchManager.getIndexUpdateListener();
        if (listener == null) {
            return;
        }
        try {
            listener.onIndexRefreshFailed(error);
        } catch (RuntimeException e) {
            log.warn("notifyIndexRefreshFailed: Listener failed", e);
        }
    }

    private void notifyFieldSummariesUpdated(RefreshTask<SortedSet<FieldSummary>> task,
            SortedSet<FieldSummary> fieldSummaries) {
        var listener = searchManager.getFieldSummaryUpdateListener();
        if (listener == null) {
            return;
        }
        try {
            if (task.explicitScope) {
                listener.onScopeFieldSummariesUpdated(task.scope, fieldSummaries, false);
            } else {
                listener.onFieldSummariesUpdated(task.index, fieldSummaries, false);
            }
        } catch (RuntimeException e) {
            log.warn("notifyFieldSummariesUpdated: Listener failed", e);
        }
    }

    private void notifyFieldSummaryRefreshFailed(RefreshTask<SortedSet<FieldSummary>> task, Throwable error) {
        var listener = searchManager.getFieldSummaryUpdateListener();
        if (listener == null) {
            return;
        }
        try {
            if (task.explicitScope) {
                listener.onScopeFieldSummaryRefreshFailed(task.scope, error);
            } else {
                listener.onFieldSummaryRefreshFailed(task.index, error);
            }
        } catch (RuntimeException e) {
            log.warn("notifyFieldSummaryRefreshFailed: Listener failed", e);
        }
    }
}
