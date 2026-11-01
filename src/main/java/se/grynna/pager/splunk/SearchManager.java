package se.grynna.pager.splunk;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.db.ScopeTerms;
import se.grynna.pager.db.SearchFilterManager;
import se.grynna.pager.db.SearchFilterManager.FieldSummaryUpdateListener;
import se.grynna.pager.db.SearchFilterManager.IndexUpdateListener;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.splunk.cert.CertificateDecisionProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Manages Splunk connections and search operations.
 *
 * <h2>Architecture</h2>
 * SearchManager is created with credentials and immediately initialized.
 * Session recovery is handled by SplunkClient via ReloginProvider.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Create SearchManager with credentials, reloginProvider, config and clock</li>
 *   <li>Use getSplunkClient() for operations</li>
 *   <li>SplunkClient handles session recovery automatically</li>
 * </ol>
 *
 * @see SplunkClient
 * @see ReloginProvider
 */
@Slf4j
public class SearchManager {

    private final SearchBoundaries searchBoundaries;

    LogEventMapper EVENT_MAPPER = new LogEventMapper();

    @Getter
    private final SplunkClient splunkClient;

    @Getter
    private final DatabaseManager databaseManager;

    @Getter
    private final SplunkLogDataProvider dataProvider;

    private final SearchFilterManager searchFilterManager;

    /** Active searcher — at most one search can be running at a time */
    private volatile Searcher activeSearcher;

    /** The baseline query rendered from {@link #scope}; what every log load and search builds on. */
    @Getter
    private volatile String baselineQuery;

    private volatile Scope scope;

    // NOOP callbacks that just log - used after logout to avoid null checks
    private static final ProgressCallback NOOP_PROGRESS = p -> log.trace("Progress callback after shutdown: {}", p);
    private static final FieldSummaryUpdateListener NOOP_FIELD_LISTENER = (idx, fields, cache) -> log
            .trace("Field summary update after shutdown: index={}, fields={}", idx, fields.size());
    private static final IndexUpdateListener NOOP_INDEX_LISTENER = (indexes, cache) -> log
            .trace("Index update after shutdown: {} indexes", indexes.size());

    @Setter
    @Getter
    private ProgressCallback indexProgressCallback = NOOP_PROGRESS;

    @Setter
    @Getter
    private ProgressCallback fieldProgressCallback = NOOP_PROGRESS;

    @Setter
    @Getter
    private FieldSummaryUpdateListener fieldSummaryUpdateListener = NOOP_FIELD_LISTENER;

    @Setter
    @Getter
    private IndexUpdateListener indexUpdateListener = NOOP_INDEX_LISTENER;

    /**
     * Create and initialize SearchManager with Splunk credentials.
     *
     * @param credentials the Splunk credentials
     * @param reloginProvider optional provider for session recovery (can be null)
     * @param logUpdatedCallback callback for log update notifications
     * @param clock clock for time operations
     * @throws StorageException if database initialization fails
     * @throws SearchException if Splunk connection fails
     */
    public SearchManager(SplunkCredentials credentials, ReloginProvider reloginProvider,
            LogUpdatedCallback logUpdatedCallback, Clock clock) throws StorageException, SearchException {
        this(credentials, reloginProvider, logUpdatedCallback, clock, null);
    }

    /**
     * Create and initialize SearchManager with Splunk credentials.
     *
     * @param credentials the Splunk credentials
     * @param reloginProvider optional provider for session recovery (can be null)
     * @param logUpdatedCallback callback for log update notifications
     * @param clock clock for time operations
     * @param certificateDecisionProvider optional provider used to ask the user about
     *                                     untrusted (e.g. self-signed) server certificates; can be null
     * @throws StorageException if database initialization fails
     * @throws SearchException if Splunk connection fails
     */
    public SearchManager(SplunkCredentials credentials, ReloginProvider reloginProvider,
            LogUpdatedCallback logUpdatedCallback, Clock clock, CertificateDecisionProvider certificateDecisionProvider)
            throws StorageException, SearchException {
        // Initialize Splunk client
        splunkClient = new SplunkClient(credentials.host(), credentials.port(), credentials.username(),
                credentials.password(), clock, certificateDecisionProvider, null);
        splunkClient.setReloginProvider(reloginProvider);

        // Initialize database
        databaseManager = new DatabaseManager(credentials.host(), credentials.username(), credentials.password());

        databaseManager.clearSegmentCache();

        // Initialize search filter manager
        searchFilterManager = new SearchFilterManager(databaseManager, this, clock);

        // Initialize data provider
        searchBoundaries = new SearchBoundaries(clock);

        dataProvider = new SplunkLogDataProvider(EVENT_MAPPER, databaseManager, logUpdatedCallback,
                this::getSplunkClient, this::getBaselineQuery, clock, searchBoundaries);

        log.info("SearchManager initialized for user '{}' on host '{}' at {}", credentials.username(),
                credentials.host(), clock.instant());
    }

    /**
     * Build the full Splunk query by filtering the baseline pipeline's events on the search term.
     */
    private String buildSearchQuery(String searchTerm) {
        return SplQuery.withTextFilter(baselineQuery, searchTerm);
    }

    /**
     * Sets the scope of the log: index, field filters and the narrowing/excluded text
     * terms. It is rendered to the baseline query that log loads and searches build on
     * ({@link SplQuery#scope(Scope)}).
     * <p>
     * A scope that renders to a different baseline is a provider generation change: the
     * active search is cancelled, in-flight loads are superseded and the loaded
     * segments are dropped, so the caller must {@code jump} the data provider to a
     * position afterwards. Setting an equivalent scope changes nothing but the stored
     * value.
     * <p>
     * When the scope's index differs from the previous scope's index (or there is no
     * previous scope), the index's saved message fields are put on the event mapper
     * before the log reloads, as part of the same generation change. Within one index the
     * mapper keeps whatever {@link #setMessageFields} last set.
     * <p>
     * Reads the saved message fields from the database when the index changes; that is a
     * local MVStore read, the same as {@link #getScopeTerms}.
     */
    public synchronized void setScope(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        String rendered = SplQuery.scope(scope);
        boolean indexChanged = this.scope == null || !this.scope.indexSelection().sameAs(scope.indexSelection());
        boolean fieldsChanged = indexChanged && applyMessageFields(savedMessageFields(scope.index()));
        boolean changed = fieldsChanged || !rendered.equals(baselineQuery);
        this.scope = scope;
        this.baselineQuery = rendered;
        log.info("Setting scope: {}", rendered);
        if (changed) {
            cancelSearch();
            dataProvider.discardLoadedLog();
        }
    }

    /**
     * The message fields an index has saved, or null for the defaults. A failure to read
     * them is logged and the defaults are used.
     */
    private List<String> savedMessageFields(String index) {
        if (index == null || index.isBlank()) {
            return null;
        }
        try {
            return searchFilterManager.getMessageFields(index).orElse(null);
        } catch (StorageException e) {
            log.error("savedMessageFields: Failed for index '{}', using the default fields", index, e);
            return null;
        }
    }

    /** Put the fields on the mapper; returns true when they differ from the mapper's current fields. */
    private boolean applyMessageFields(List<String> fieldsOrNull) {
        List<String> effective = fieldsOrNull == null
                ? LogEventMapper.DEFAULT_MESSAGE_FIELDS
                : List.copyOf(fieldsOrNull);
        if (effective.equals(EVENT_MAPPER.getMessageFields())) {
            return false;
        }
        EVENT_MAPPER.setMessageFields(effective);
        return true;
    }

    /**
     * Set the message fields: the ordered JSON field paths whose values make up a JSON
     * event's message. {@code null} restores the default fields; an empty list means no
     * field contributes, so JSON events show their raw row.
     * <p>
     * A change of the effective fields is a provider generation change like
     * {@link #setScope}: the active search is cancelled and the loaded log discarded, so
     * the caller must {@code jump} the data provider afterwards. Setting the fields that are
     * already in effect changes nothing. Does not store them; see {@link #storeMessageFields}.
     *
     * @return true when the fields changed
     */
    public synchronized boolean setMessageFields(List<String> fieldsOrNull) {
        boolean changed = applyMessageFields(fieldsOrNull);
        if (changed) {
            log.info("Setting message fields: {}", EVENT_MAPPER.getMessageFields());
            cancelSearch();
            dataProvider.discardLoadedLog();
        }
        return changed;
    }

    /** The message fields in effect for JSON events. */
    public List<String> messageFields() {
        return EVENT_MAPPER.getMessageFields();
    }

    /**
     * Get the saved message fields of an index; empty when the index uses the defaults.
     */
    public Optional<List<String>> getMessageFields(String index) throws StorageException {
        return searchFilterManager.getMessageFields(index);
    }

    /**
     * Store the message fields of an index in the background; null restores the defaults.
     *
     * @return completes when written, or exceptionally if the write failed
     */
    public CompletableFuture<Void> storeMessageFields(String index, List<String> fieldsOrNull) {
        return searchFilterManager.storeMessageFields(index, fieldsOrNull);
    }

    /** The current scope, or null before one is set. */
    public Scope scope() {
        return scope;
    }

    /**
     * Get the saved narrowing and excluded terms of an index.
     */
    public ScopeTerms getScopeTerms(String index) throws StorageException {
        return searchFilterManager.getScopeTerms(index);
    }

    /**
     * Store the narrowing and excluded terms of an index in the background.
     *
     * @return completes when written, or exceptionally if the write failed
     */
    public CompletableFuture<Void> storeScopeTerms(String index, ScopeTerms terms) {
        return searchFilterManager.storeScopeTerms(index, terms);
    }

    /**
     * Get the Splunk host.
     */
    public String getHost() {
        return splunkClient.getHost();
    }

    /**
     * Get the Splunk port.
     */
    public int getPort() {
        return splunkClient.getPort();
    }

    /**
     * Get the Splunk user.
     */
    public String getUser() {
        return splunkClient.getUser();
    }

    public void logout() {
        log.info("SearchManager logout initiated");

        // 1. Cancel any active search
        cancelSearch();

        // 2. Mark SplunkClient as shutting down FIRST - this makes all ongoing
        //    operations (waitUntil loops) exit immediately
        splunkClient.markShuttingDown();

        // 3. Replace callbacks with NOOP implementations to prevent UI updates during cleanup
        indexProgressCallback = NOOP_PROGRESS;
        fieldProgressCallback = NOOP_PROGRESS;
        fieldSummaryUpdateListener = NOOP_FIELD_LISTENER;
        indexUpdateListener = NOOP_INDEX_LISTENER;

        // 4. Shutdown the data provider (cancels background loading tasks)
        try {
            dataProvider.shutdown();
        } catch (Exception e) {
            log.warn("Error shutting down SplunkLogDataProvider: {}", e.getMessage());
        }

        // 5. Shutdown the search filter manager (cancels index/field refresh tasks)
        try {
            searchFilterManager.shutdown();
        } catch (Exception e) {
            log.warn("Error shutting down SearchFilterManager: {}", e.getMessage());
        }

        // 6. Logout from Splunk
        try {
            splunkClient.logout();
        } catch (Exception e) {
            log.warn("Error during Splunk logout: {}", e.getMessage());
        }

        // 7. Close the database
        try {
            databaseManager.close();
        } catch (Exception e) {
            log.warn("Error closing database: {}", e.getMessage());
        }

        log.info("SearchManager logout complete");
    }

    public String getLastIndex() {
        return databaseManager.getLastIndex();
    }

    public void setLastIndex(String index) {
        databaseManager.setLastIndex(index);
    }

    /**
     * Get the cached indexes; refreshes them in the background when the cache is
     * empty or out of date. Use {@link #refreshIndexes()} to force a refresh.
     */
    public SortedSet<String> loadIndexes() {
        return searchFilterManager.loadIndexes();
    }

    /**
     * Load field summaries for an index. Returns cached data immediately
     * and triggers background refresh if data is stale or force is true.
     */
    public SortedSet<FieldSummary> loadFieldSummaries(String index, boolean force) {
        return searchFilterManager.loadFieldSummaries(index, force);
    }

    /**
     * Sample the fields of an index deeper than before (more events, further back) and refresh
     * them. Returns cached data immediately; fresh data arrives through the listener.
     */
    public SortedSet<FieldSummary> deepenFieldSummaries(String index) {
        return searchFilterManager.deepenFieldSummaries(index);
    }

    /** The number of newest events the fields of an index are sampled from. */
    public int fieldSampleSize(String index) {
        return searchFilterManager.fieldSampleSize(index);
    }

    /**
     * Get saved field filter values for an index.
     */
    public Map<String, List<String>> getFieldFilters(String index) throws StorageException {
        return searchFilterManager.getFieldFilters(index);
    }

    /**
     * Store field filter values for an index in the background.
     *
     * @return completes when written, or exceptionally if the write failed
     */
    public CompletableFuture<Void> storeFieldFilters(String index, Map<String, List<String>> filters) {
        return searchFilterManager.storeFieldFilters(index, filters);
    }

    /**
     * Trigger explicit refresh of indexes from Splunk. The outcome is delivered to the
     * {@link IndexUpdateListener}, including failures.
     */
    public void refreshIndexes() {
        // Fire-and-forget by contract: the outcome (including failures) reaches the
        // IndexUpdateListener; observe the future so an exceptional completion is not
        // dropped. Cancellation means the refresh was superseded or the manager shut down.
        searchFilterManager.refreshIndexes().exceptionally(e -> {
            if (!(e instanceof CancellationException)) {
                log.warn("refreshIndexes: Background index refresh failed", e);
            }
            return null;
        });
    }

    /**
     * Get the field highlight patterns (comma-separated glob patterns).
     * Fields matching these patterns are visually highlighted in the filter selector.
     */
    public String getFieldHighlightPatterns() {
        return databaseManager.getFieldHighlightPatterns();
    }

    /**
     * Set the field highlight patterns (comma-separated glob patterns).
     * Example: "*_pod_name,*_container_name,service*"
     */
    public void setFieldHighlightPatterns(String patterns) {
        databaseManager.setFieldHighlightPatterns(patterns);
    }

    /**
     * Set the callback used for reporting progress during log load operations.
     */
    public void setLoadProgressCallback(ProgressCallback spc) {
        dataProvider.setLoadProgressCallback(spc);
    }

    /**
     * Start a new search session. Creates a Searcher that streams matching events
     * directly from Splunk into a SearchSession. The baseline query is combined with
     * the search term so Splunk filters server-side.
     *
     * Any previously active search is cancelled first.
     *
     * @param searchTerm  text to search for within the current query scope
     * @param origin      timestamp the user is currently viewing (for navigation context)
     * @return the Searcher — call start() to begin streaming results
     */
    public synchronized Searcher search(String searchTerm, Instant origin) {
        return startSearch(searchTerm, origin, searchBoundaries.getMinBoundary(),
                searchBoundaries.getEffectiveMaxBoundary());
    }

    /**
     * Re-centre at an explicitly accepted target, even outside the log's current
     * range. Include a minute on each side (within absolute limits), without
     * changing the saved log viewport or its browsing boundaries.
     */
    public synchronized Searcher searchAround(String searchTerm, Instant origin) {
        Instant min = SearchBoundaries.MIN_MINIMUM_BOUNDARY;
        Instant max = latestSearchableInstant();
        Instant target = origin.isBefore(min) ? min : origin.isAfter(max) ? max : origin;
        Instant from = target.minusSeconds(60);
        Instant to = target.plusSeconds(60);
        if (from.isBefore(min)) from = min;
        if (to.isAfter(max)) to = max;
        Instant earliest = searchBoundaries.getMinBoundary();
        Instant latest = searchBoundaries.getEffectiveMaxBoundary();
        return startSearch(searchTerm, target, from.isBefore(earliest) ? from : earliest,
                to.isAfter(latest) ? to : latest);
    }

    private Searcher startSearch(String searchTerm, Instant origin, Instant earliest, Instant latest) {
        // Cancel any running search first
        cancelSearch();

        String fullQuery = buildSearchQuery(searchTerm);
        Instant clampedOrigin = origin.isBefore(earliest) ? earliest : origin.isAfter(latest) ? latest : origin;
        SearchSession session = new SearchSession(searchTerm, clampedOrigin);

        Searcher searcher = new Searcher(splunkClient, EVENT_MAPPER, session, fullQuery, earliest, latest,
                clampedOrigin);

        activeSearcher = searcher;
        return searcher;
    }

    /** Without a browsing anchor, search backwards from the effective window end. */
    public Searcher search(String searchTerm) {
        return search(searchTerm, searchBoundaries.getEffectiveMaxBoundary());
    }

    /**
     * Cancel any currently active search and drop it. Search mode should exit.
     */
    public synchronized void cancelSearch() {
        Searcher current = activeSearcher;
        activeSearcher = null;
        if (current != null) {
            // Retire the session first so no late result from any of its runs
            // (including a draining stopped query) can reach it or its listeners.
            current.getSession().discard();
            current.abandon();
        }
    }

    /**
     * Stop the in-flight text search but keep the hits found so far.
     * The session stays reachable so F3 can still walk those matches.
     */
    public void stopSearch() {
        Searcher current = activeSearcher;
        if (current != null) {
            current.cancel();
        }
    }

    /**
     * Query an additional time slice and add its matches to the active session.
     * Used when the user widens the search range. Does not discard hits already found.
     * The extension becomes the session's current run, so a previous (stopped)
     * query that is still draining can no longer add hits or finish the session.
     *
     * @param expected the session the extension was requested for; if another
     *                 search replaced or cancelled it meanwhile, nothing happens
     */
    // Identity comparison is intentional: `expected` is the session token this
    // extension was requested for; if the active search has since been replaced
    // or cancelled, the stale request must be ignored.
    @SuppressWarnings("ReferenceEquality")
    public void extendSearch(SearchSession expected, Instant earliest, Instant latest) {
        if (earliest == null || latest == null || !earliest.isBefore(latest)) {
            return;
        }
        Searcher extension;
        synchronized (this) {
            Searcher current = activeSearcher;
            if (current == null || current.getSession() != expected || expected.isDiscarded()) {
                return;
            }
            SearchSession session = current.getSession();
            // Extensions grow a contiguous covered edge, never span an uncovered gap.
            Instant origin = latest;
            if (session.windowEnd() != null && !earliest.isBefore(session.windowEnd())) {
                if (earliest.isAfter(session.windowEnd()) || session.isTruncated(SearchSession.Side.LATER)) return;
                origin = earliest;
            } else if (session.windowStart() != null && !latest.isAfter(session.windowStart())) {
                if (latest.isBefore(session.windowStart()) || session.isTruncated(SearchSession.Side.EARLIER)) return;
            } else if (session.windowStart() != null) {
                return;
            }
            // Supersede the old run before interrupting it, so its interrupted
            // stream cannot report a failure against the session.
            extension = new Searcher(splunkClient, EVENT_MAPPER, session, buildSearchQuery(session.getQuery()),
                    earliest, latest, origin);
            current.abandon();
            activeSearcher = extension;
        }
        extension.start();
    }

    /** Latest instant a text search is allowed to reach (now, minus indexing lag). */
    public Instant latestSearchableInstant() {
        return searchBoundaries.getNowBoundary();
    }

    /**
     * Get the active search session, if any.
     */
    public SearchSession getActiveSearchSession() {
        Searcher current = activeSearcher;
        return current != null ? current.getSession() : null;
    }
}
