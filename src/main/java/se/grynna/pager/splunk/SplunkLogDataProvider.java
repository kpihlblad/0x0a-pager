package se.grynna.pager.splunk;

import com.splunk.Event;
import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.ui.LogDataProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static se.grynna.pager.LogStatistics.Status.LOADED;
import static se.grynna.pager.LogStatistics.Status.UNINITIALISED;
import static se.grynna.pager.splunk.BoundaryType.*;

/**
 * SplunkLogDataProvider fetches log events from the server and stores them in a local cache.
 * The class implements the LogDataProvider interface.
 */
@Slf4j
public class SplunkLogDataProvider implements LogDataProvider {

    // Leave enough rows for several pages of scrolling while the next query runs.
    static final int PRELOAD_EARLIER_DISTANCE_LINES = 3500;
    static final int PRELOAD_LATER_DISTANCE_LINES = 1800;

    // allow only ONE of this class to run at a time
    private static final AtomicInteger counter = new AtomicInteger();

    static {
        if (counter.compareAndExchange(0, 1) > 0) {
            throw new IllegalStateException("Only one instance of SplunkLogDataProvider is allowed");
        }
    }

    // Instance variables
    private final Object lock = new Object();
    private final Supplier<SplunkClient> clientSupplier;
    private final DatabaseManager databaseManager;
    private LogUpdatedCallback logUpdatedCallback;
    private final LogEventMapper logEventMapper;
    private final LoadingStrategy loadingStrategy;
    private final Clock clock;
    /**
     * At most this many load requests are open against Splunk: the current one and one superseded
     * request whose cancel has not settled. A further load waits in the pending slot.
     */
    static final int MAX_OPEN = 2;

    /** Upper bound for shutdown: SID cancels and worker exit together. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

    // One daemon virtual thread per load: a superseded request whose socket read ignores
    // interruption keeps only its own thread, so a replacement load starts immediately and
    // the abandoned thread cannot keep the JVM alive (an interrupt does abort a blocked socket
    // read on a virtual thread). At most one job may publish and at most MAX_OPEN are open;
    // superseded requests can finish independently but must discard their results.
    private final ExecutorService executor = Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("pager-load-", 0).factory());
    private final Supplier<String> baselineQuerySupplier;
    private final SearchBoundaries searchBoundaries;

    // ------------------------------------------------------------------
    // Loading state (all guarded by lock)
    //
    // Browsing is always tied to currentSegment. Background fetches run as
    // LoadJobs; at most one job — the "current" one — may publish its result.
    // A jump replaces currentSegment and supersedes the in-flight job: the old
    // job may keep running (a Splunk query cannot always be aborted) but its
    // result is discarded instead of being applied to the new segment, and its
    // Splunk jobs are cancelled by SID.
    //
    // currentLoad is PENDING (waiting for a free slot, never sent: the pending
    // slot, latest wins) or RUNNING. inFlight holds every dispatched job whose
    // Splunk search may still run; a job is dispatched only while
    // inFlight.size() < MAX_OPEN. A job settles (leaves inFlight) only when its
    // ticket is quiet: cancelled (supersede, or its worker returned) and every
    // SID proven not searching (done, gone, never sent, rejected). A returned
    // worker, a failed or unconfirmed cancel or an ambiguous create does not
    // settle it: the client keeps cancelling by SID until the ticket is quiet.
    // ------------------------------------------------------------------
    private LogSegment currentSegment = null;
    private LoadJob currentLoad = null;
    private Throwable loaderException = null;
    private final Set<LoadJob> inFlight = new HashSet<>();
    private boolean shutdown = false;

    // Load status for the progress callback (waiting for a slot / started / dropped), set under
    // lock and handed to the callback after the lock is released (publishStatus). Every status
    // and every job progress gets a sequence number when it is decided under lock; deliveries
    // older than one already delivered are dropped, so a late "waiting" can never overwrite a
    // newer job progress. No lock is held while the callback runs (see deliver).
    private final Object deliveryLock = new Object();
    private final ArrayDeque<Delivery> deliveries = new ArrayDeque<>(); // guarded by deliveryLock
    private boolean delivering; // guarded by deliveryLock
    private long deliveredSeq; // guarded by deliveryLock
    private volatile SearchProgress unpublishedStatus; // written under lock; read unlocked as a fast path
    private long unpublishedStatusSeq; // guarded by lock
    private long statusSeq; // guarded by lock
    private LoadJob waitingReportedFor; // guarded by lock

    private record Delivery(long seq, SearchProgress progress, ProgressCallback callback) {}

    // Cached boundary instances with line numbers (updated when boundaries align with loaded data)
    private Boundary beforeBoundary; // Earliest search boundary with optional line number
    private Boundary afterBoundary; // Latest search boundary with optional line number
    private Boundary nowBoundary; // Current "now" boundary (now - 5min) with optional line number
    private ProgressCallback loadProgressCallback;

    public SplunkLogDataProvider(LogEventMapper logEventMapper, DatabaseManager databaseManager,
            LogUpdatedCallback logUpdatedCallback, Supplier<SplunkClient> splunkClientSupplier,
            Supplier<String> baselineQuerySupplier, Clock clock, SearchBoundaries searchBoundaries) {
        super();
        this.databaseManager = databaseManager;
        this.logEventMapper = logEventMapper;
        this.logUpdatedCallback = logUpdatedCallback;
        this.clientSupplier = splunkClientSupplier;
        this.baselineQuerySupplier = baselineQuerySupplier;
        this.searchBoundaries = searchBoundaries;
        this.clock = clock;

        this.loadingStrategy = new LoadingStrategy(clock, searchBoundaries);

        // Initialize boundaries as null - will be set when search boundaries are configured
        this.beforeBoundary = null;
        this.afterBoundary = null;
        this.nowBoundary = getNowBoundary();

        this.loadProgressCallback = progress -> {
            String statusMsg = String.format("Load: %.0f%% | Scanned: %,d | Matched: %,d | Preview: %,d",
                    progress.doneProgress() * 100, progress.scanCount(), progress.eventCount(),
                    progress.resultPreviewCount());

            log.debug("Load progress: {}", statusMsg);
        };
    }

    void setLogUpdatedCallback(LogUpdatedCallback logUpdatedCallback) {
        this.logUpdatedCallback = logUpdatedCallback;
    }

    @Override
    public int getMaxOobPerSide() {
        return 1;
    }

    @Override
    public int jumpToLatest() throws TemporarySearchException, SearchException, StorageException {
        return jump(searchBoundaries.getNowBoundary());
    }

    /**
     * Jump to the log entry closest to the given timestamp, and assign that to line 0
     *
     * @param target the timestamp to jump to.
     * @return the line number of the closest log entry to the timestamp.
     * @throws TemporarySearchException when a temporary search error occurs
     * @throws SearchException          when a search error occurs
     * @throws StorageException         when a storage error occurs
     */
    @Override
    public int jump(Instant target) throws TemporarySearchException, SearchException, StorageException {
        log.debug("jump: {}", target);

        try {
            synchronized (lock) {
                if (currentSegment != null && currentSegment.contains(target)) {
                    log.debug("jump: {} is in current segment {}", target, currentSegment);
                    // Binary search for line number
                    return binarySearchIteratively(target, currentSegment.getStartLine(),
                            currentSegment.getEndLineExl() - 1);
                }
                // Outside the loaded interval (or nothing loaded yet): reset around the
                // target and load a new segment. Any in-flight load is superseded.
                getFirstLine(target);
                return 0;
            }
        } finally {
            publishStatus();
        }
    }

    @Override
    public int jump(Instant target, Instant from, Instant to) {
        synchronized (lock) {
            Instant start = from.isBefore(SearchBoundaries.MIN_MINIMUM_BOUNDARY)
                    ? SearchBoundaries.MIN_MINIMUM_BOUNDARY
                    : from;
            Instant end = to == null ? Instant.MAX : to;
            Instant now = searchBoundaries.getNowBoundary();
            Instant effectiveEnd = end.isBefore(now) ? end : now;
            if (!start.isBefore(effectiveEnd)) {
                throw new IllegalArgumentException("Range start must be before its effective end");
            }
            // An explicit range always supersedes the segment, including an in-segment jump.
            getFirstLine(start, null, start, end);
        }
        publishStatus();
        return 0;
    }

    @Override
    public int jump(SearchHit target) throws TemporarySearchException, SearchException, StorageException {
        log.debug("jump(hit): {}", target);

        try {
            synchronized (lock) {
                if (currentSegment != null && currentSegment.contains(target.splunkTime())) {
                    log.debug("jump(hit): {} is in current segment {}", target, currentSegment);
                    return binarySearchIteratively(target, currentSegment.getStartLine(),
                            currentSegment.getEndLineExl() - 1);
                }
                getFirstLine(target.splunkTime(), target);
                return 0;
            }
        } finally {
            publishStatus();
        }
    }

    private LoadedLogLine awaitLoadedLogLine(int index)
            throws TemporarySearchException, SearchException, StorageException {
        log.debug("binarySearch: fetching line {}", index);
        var ll = getLine(index, false);

        while (!(ll instanceof LoadedLogLine)) {
            log.debug("binarySearch: waiting for LoadedLogLine at line {}", index);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TemporarySearchException("Interrupted while waiting for log line", e);
            }
            ll = getLine(index, false);
        }

        return (LoadedLogLine) ll;
    }

    public int binarySearchIteratively(Instant key, int low, int high)
            throws TemporarySearchException, SearchException, StorageException {
        // Element provider that retrieves LoadedLogLine by index
        BinarySearchClosest.ElementProvider<LoadedLogLine> provider = this::awaitLoadedLogLine;

        // Comparator for comparing log lines by timestamp
        Comparator<LoadedLogLine> comparator = Comparator.comparing(LoadedLogLine::getSplunkTime);

        // Distance calculator for finding closest match
        BinarySearchClosest.DistanceCalculator<LoadedLogLine> distanceCalculator = (searchLine, line) -> Duration
                .between(searchLine.getSplunkTime(), line.getSplunkTime()).abs();

        // Create a temporary LoadedLogLine with the search timestamp
        LoadedLogLine searchKey = new LoadedLogLine();
        searchKey.setTime(key);

        BinarySearchClosest<LoadedLogLine> binarySearch = new BinarySearchClosest<>(provider, comparator,
                distanceCalculator);

        try {
            return binarySearch.search(searchKey, low, high);
        } catch (TemporarySearchException | SearchException | StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Unexpected error during binary search", e);
        }
    }

    public int binarySearchIteratively(SearchHit key, int low, int high)
            throws TemporarySearchException, SearchException, StorageException {
        BinarySearchClosest.ElementProvider<LoadedLogLine> provider = this::awaitLoadedLogLine;

        Comparator<LoadedLogLine> comparator = EventOrder.loadedLogLineComparator();
        BinarySearchClosest.DistanceCalculator<LoadedLogLine> distanceCalculator = (searchLine, line) -> Duration
                .between(searchLine.getSplunkTime(), line.getSplunkTime()).abs();

        LoadedLogLine searchKey = new LoadedLogLine();
        searchKey.setTime(key.splunkTime());
        searchKey.setMsgTime(key.displayTime());
        searchKey.setBkt(key.bkt());
        searchKey.setCb(key.cd());

        BinarySearchClosest<LoadedLogLine> binarySearch = new BinarySearchClosest<>(provider, comparator,
                distanceCalculator);

        try {
            return binarySearch.search(searchKey, low, high);
        } catch (TemporarySearchException | SearchException | StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Unexpected error during binary search", e);
        }
    }

    private void validateSearchBoundary() throws TemporarySearchException {
        if (searchBoundaries.getMinBoundary() == null) {
            throw new TemporarySearchException("beforeBoundary is null");
        }
        if (searchBoundaries.getMaxBoundary() == null) {
            throw new TemporarySearchException("afterBoundary is null");
        }
    }

    @Override
    public LogLine getLine(int lineNumber, boolean edge)
            throws TemporarySearchException, SearchException, StorageException {
        try {
            return lineOrLoad(lineNumber, edge);
        } finally {
            publishStatus();
        }
    }

    private LogLine lineOrLoad(int lineNumber, boolean edge)
            throws TemporarySearchException, SearchException, StorageException {

        log.trace("getLine: {}", lineNumber);

        synchronized (lock) {
            validateSearchBoundary();
            checkLoaderException();

            if (currentSegment == null) {
                log.error("getLine:{} uninitialized", lineNumber);
                throw new IllegalStateException("Must initialize with jump() first");
            }

            // Check if line is in current segment
            if (currentSegment.contains(lineNumber)) {
                log.trace("getLine:{} exists in segment {}", lineNumber, currentSegment);

                return databaseManager.load(currentSegment.getId(), lineNumber);
            }

            loadingStrategy.noteUnloadedRowNeeded();

            if (isLoading()) {
                if (currentSegment.offset(lineNumber) < 0) {
                    if (loadingStrategy.isLoadingUp()) {
                        log.trace(
                                "getLine: {} requested before current segment start {}, but loading up, returning placeholder",
                                lineNumber, currentSegment.getStartLine());
                        return placeHolder(lineNumber);
                    } else {
                        log.trace("getLine: {} requested before current segment start {}, loading in background",
                                lineNumber, currentSegment.getStartLine());
                        return createBoundaryLine(lineNumber, BEFORE);
                    }
                } else {
                    if (loadingStrategy.isLoadingDown()) {
                        log.trace(
                                "getLine: {} requested after current segment end {}, but loading down, returning placeholder",
                                lineNumber, currentSegment.getEndLineExl());
                        return placeHolder(lineNumber);
                    } else {
                        log.trace("getLine: {} requested after current segment end {}, loading in background",
                                lineNumber, currentSegment.getEndLineExl());
                        return createBoundaryLine(lineNumber, AFTER);
                    }
                }
            }

            // Decide what to do based on loading strategy
            LoadDecision decision = loadingStrategy.evaluateLineRequest(lineNumber, currentSegment, edge);

            if (decision.shouldLoad()) {
                log.debug("getLine: {} loading {}", lineNumber, decision);
                scheduleLoad(new LoadJob(currentSegment, decision, lineNumber, "getLine " + lineNumber));
                return placeHolder(lineNumber);
            } else if (decision.boundaryHit() != null) {
                log.debug("getLine:{} boundary reached - {} - {}", lineNumber, decision.boundaryHit(),
                        decision.reason());

                return createBoundaryLine(lineNumber, decision.boundaryHit());
            }

            log.trace("getLine: {} placeholder", lineNumber);

            return Placeholder.from(lineNumber, "no load decision");
        }
    }

    @Override
    public Optional<LogLine> peekLine(int lineNumber) {
        synchronized (lock) {
            if (currentSegment == null || !currentSegment.contains(lineNumber)) {
                return Optional.empty();
            }
            return Optional.ofNullable(databaseManager.load(currentSegment.getId(), lineNumber));
        }
    }

    @Override
    public void preload(int firstVisible, int lastVisible) {
        try {
            preloadNear(firstVisible, lastVisible);
        } finally {
            publishStatus();
        }
    }

    private void preloadNear(int firstVisible, int lastVisible) {
        synchronized (lock) {
            if (currentSegment == null || isLoading() || loaderException != null) {
                return;
            }
            // Only look ahead while the viewport still overlaps loaded data.
            if (lastVisible < currentSegment.getStartLine() || firstVisible >= currentSegment.getEndLineExl()) {
                return;
            }
            int before = Math.max(0, firstVisible - currentSegment.getStartLine());
            int after = Math.max(0, currentSegment.getEndLineExl() - 1 - lastVisible);
            boolean up = before < PRELOAD_EARLIER_DISTANCE_LINES;
            boolean down = after < PRELOAD_LATER_DISTANCE_LINES;
            if (!up && !down) {
                return;
            }
            // Prefer the closer edge; a later repaint can fill the other side.
            boolean earlier = up && (!down || before <= after);
            int target = earlier ? currentSegment.getStartLine() - 1 : currentSegment.getEndLineExl();
            LoadDecision decision = loadingStrategy.evaluateLineRequest(target, currentSegment, false);
            if (decision.shouldLoad()) {
                scheduleLoad(new LoadJob(currentSegment, decision, target, "preload near " + target));
            }
        }
    }

    @Override
    public void unfreezeBeforeBoundary() {
        loadingStrategy.unfreezeBeforeBoundary();
        logUpdatedCallback.call();
    }

    @Override
    public void unfreezeAfterBoundary() {
        loadingStrategy.unfreezeAfterBoundary();
        logUpdatedCallback.call();
    }

    /**
     * Forget the loaded log because the scope (and so the baseline query) changed: the
     * in-flight load is superseded (its late result is discarded), the segments are
     * dropped from the segment cache and no segment is attached. The next
     * {@link #jump(Instant)} or {@link #jump(SearchHit)} loads a fresh segment around its
     * target. Until then the provider cannot serve lines, so callers jump straight away.
     */
    public void discardLoadedLog() {
        synchronized (lock) {
            log.info("Discarding the loaded log for a scope change");
            supersedeCurrentLoad();
            loaderException = null;
            currentSegment = null;
            databaseManager.clearSegmentCache();
        }
        publishStatus();
    }

    @Override
    public boolean cancelSearch() {
        try {
            synchronized (lock) {
                if (!isLoading()) {
                    log.debug("No ongoing loader task to cancel");
                    return false;
                }
                log.info("Cancelling ongoing loader task");
                // A pending load is dropped without ever being sent
                supersedeCurrentLoad();
                stopAtSegment();
                return true;
            }
        } finally {
            publishStatus();
        }
    }

    /**
     * Freeze the search window at the loaded segment and stop both direction
     * state machines; scrolling past an edge unfreezes it again. BEFORE is the
     * earliest (min) boundary and AFTER the latest (max) one, so they must be
     * pinned in chronological order — segment start to BEFORE, segment end to
     * AFTER. Used both for a user cancel and for a failed background load. A
     * superseded job's late result is discarded (see LoadJob), so it cannot
     * overwrite this stopped state. Callers must hold lock.
     */
    private void stopAtSegment() {
        if (currentSegment != null) {
            setBeforeBoundary(currentSegment.getStartTime());
            setAfterBoundary(currentSegment.getEndTime());
            loadingStrategy.cancel();
        }
    }

    private LogLine placeHolder(int lineNumber) {

        return Placeholder.from(lineNumber, "");
    }

    /**
     * Create the appropriate boundary line based on the boundary type.
     * Uses cached boundary instances which include line numbers when available.
     */
    private LogLine createBoundaryLine(int lineNumber, BoundaryType boundaryHit) {
        return switch (boundaryHit) {
            case BEFORE -> new BeforeBoundaryLine(lineNumber, beforeBoundary);
            case AFTER -> new AfterBoundaryLine(lineNumber, afterBoundary);
            case NOW -> {
                var nb = getNowBoundary();
                var nowBoundaryLine = nb.line().orElse(Integer.MAX_VALUE);
                if (lineNumber < nowBoundaryLine) {
                    nb = new Boundary(NOW, nb.instant(), lineNumber);
                    setNowBoundary(nb);
                    log.trace("createBoundaryLine: NOW boundary at line {}", lineNumber);
                } else {
                    log.trace("createBoundaryLine: NOW boundary at line {} but no line number assigned", lineNumber);
                }
                yield new NowBoundaryLine(lineNumber, nb);
            }
        };
    }

    @Override
    public LogStatistics getStatistics() {

        synchronized (lock) {
            if (currentSegment == null) {
                return new LogStatistics(UNINITIALISED, 0, clock.instant(), clock.instant());
            }

            return new LogStatistics(LOADED, currentSegment.size(), currentSegment.getStartTime(),
                    currentSegment.getEndTime());
        }
    }

    @Override
    public SegmentInfo getSegmentInfo() {

        synchronized (lock) {
            if (currentSegment == null) {
                return SegmentInfo.uninitialized();
            }

            Duration timeSpan = Duration.between(currentSegment.getStartTime(), currentSegment.getEndTime());
            double logRate = loadingStrategy.getSegmentRate(currentSegment);

            return new SegmentInfo(currentSegment.getStartTime(), currentSegment.getEndTime(),
                    currentSegment.getStartLine(), currentSegment.getEndLineExl(), currentSegment.size(), logRate,
                    timeSpan, searchBoundaries.getMinBoundary(), searchBoundaries.getMaxBoundary(),
                    searchBoundaries.getNowBoundary());
        }
    }

    @Override
    public Boundary getBeforeBoundary() {
        synchronized (lock) {
            return beforeBoundary;
        }
    }

    @Override
    public void setBeforeBoundary(Instant t) {
        searchBoundaries.setMinBoundary(t);
        synchronized (lock) {
            // Reset to boundary without line number until data is loaded
            beforeBoundary = new Boundary(BEFORE, searchBoundaries.getMinBoundary());
            // Trigger boundary update if segment exists
            if (currentSegment != null) {
                updateBoundaries();
            }
        }
    }

    @Override
    public Boundary getAfterBoundary() {
        synchronized (lock) {
            return afterBoundary;
        }
    }

    @Override
    public void setAfterBoundary(Instant t) {
        synchronized (lock) {
            // Reset to boundary without line number until data is loaded
            searchBoundaries.setMaxBoundary(t);
            afterBoundary = new Boundary(AFTER, t);
            // Trigger boundary update if segment exists
            if (currentSegment != null) {
                updateBoundaries();
            }
        }
    }

    @Override
    public Boundary getNowBoundary() {
        synchronized (lock) {
            // Get current nowBoundary from strategy (may have changed due to clock)
            Instant currentNow = searchBoundaries.getNowBoundary();

            // Check if the time component has changed
            if (nowBoundary == null || !nowBoundary.instant().equals(currentNow)) {
                // Time changed, reset boundary without line number
                nowBoundary = new Boundary(NOW, currentNow);

                // Trigger boundary update if segment exists
                if (currentSegment != null) {
                    updateBoundaries();
                }
            }

            return nowBoundary;
        }
    }

    @Override
    public void setBoundaryExpansionListener(BoundaryExpansionListener listener) {
        loadingStrategy.setBoundaryExpansionListener(listener);
    }

    public void setNowBoundary(Boundary nowBoundary) {
        this.nowBoundary = nowBoundary;
    }

    /**
     * One background fetch tied to the segment it fills and the decision that
     * produced it. At most one job is "current" at a time. When a job is
     * superseded (jump elsewhere, cancelSearch, shutdown) it may still finish its
     * Splunk query, but it must discard the result: a stale job never touches the
     * segment, the loading strategy, the boundaries, or loaderException.
     */
    private final class LoadJob {
        final LogSegment segment;
        final LoadDecision decision;
        final int requestedLine;
        final String description;
        /** The Splunk side of this load: its jobs by SID, for cancelling them. */
        final JobTicket ticket = new JobTicket();
        JobState state = JobState.PENDING; // guarded by lock
        Future<?> future; // guarded by lock

        LoadJob(LogSegment segment, LoadDecision decision, int requestedLine, String description) {
            this.segment = segment;
            this.decision = decision;
            this.requestedLine = requestedLine;
            this.description = description;
        }

        /** True while this job is the one allowed to publish its result. */
        boolean isCurrent() {
            synchronized (lock) {
                return this == currentLoad;
            }
        }

        @Override
        public String toString() {
            return description + " " + decision;
        }
    }

    /**
     * PENDING (waiting for a slot, never sent) → RUNNING (in inFlight) → ABANDONED (superseded,
     * still in inFlight) → SETTLED; a PENDING job that is superseded or refused is DROPPED.
     */
    private enum JobState {
        PENDING, RUNNING, ABANDONED, SETTLED, DROPPED
    }

    /** Snapshot of the load gate, for tests. */
    record LoadState(Current current, int inFlight, int abandoned) {
        enum Current {
            NONE, PENDING, RUNNING
        }
    }

    LoadState loadStateForTest() {
        synchronized (lock) {
            LoadState.Current current = currentLoad == null
                    ? LoadState.Current.NONE
                    : currentLoad.state == JobState.PENDING ? LoadState.Current.PENDING : LoadState.Current.RUNNING;
            int abandoned = (int) inFlight.stream().filter(job -> job.state == JobState.ABANDONED).count();
            return new LoadState(current, inFlight.size(), abandoned);
        }
    }

    /**
     * Make the job the current one and start it when a slot is free; otherwise it waits in the
     * pending slot (it is currentLoad, so the next supersede drops it: latest wins). Callers must
     * hold lock and have superseded any previous job first. After shutdown the job is dropped, so
     * no phantom job is registered.
     */
    private void scheduleLoad(LoadJob job) {
        if (currentLoad != null) {
            throw new IllegalStateException("scheduleLoad while another load is in flight: " + currentLoad);
        }
        loaderException = null;
        if (shutdown) {
            log.debug("Not scheduling {} after shutdown", job.description);
            job.state = JobState.DROPPED;
            return;
        }
        currentLoad = job;
        tryDispatch();
    }

    /** Start the pending current job if fewer than MAX_OPEN requests are open. Callers must hold lock. */
    private void tryDispatch() {
        LoadJob job = currentLoad;
        if (job == null || job.state != JobState.PENDING) {
            return;
        }
        if (shutdown) {
            job.state = JobState.DROPPED;
            currentLoad = null;
            return;
        }
        if (inFlight.size() >= MAX_OPEN) {
            if (waitingReportedFor != job) {
                log.info("Load waits for a free Splunk slot ({} open): {}", inFlight.size(), job.description);
                setStatus(SearchProgress.waitingForSplunk(job.decision));
                waitingReportedFor = job;
            }
            return;
        }
        job.state = JobState.RUNNING;
        inFlight.add(job);
        // The only way out of inFlight: every Splunk job of this load proven not searching
        job.ticket.whenQuiet().thenRun(() -> ticketQuiet(job));
        try {
            job.future = executor.submit(() -> runJob(job));
        } catch (RejectedExecutionException e) {
            log.debug("Load rejected after shutdown: {}", job.description);
            inFlight.remove(job);
            job.state = JobState.DROPPED;
            currentLoad = null;
            return;
        }
        waitingReportedFor = null;
        setStatus(startingStatus(job.decision));
    }

    /**
     * The job no longer counts against MAX_OPEN; start the pending job if there is one.
     * Idempotent. Callers must hold lock.
     */
    private void settle(LoadJob job) {
        if (job.state == JobState.RUNNING || job.state == JobState.ABANDONED) {
            job.state = JobState.SETTLED;
            inFlight.remove(job);
            tryDispatch();
        }
    }

    /**
     * Nothing of the job can be searching on Splunk any more. Runs in whichever thread made the
     * ticket quiet: a cancel or create thread of the client, the worker, or (reentrantly) a provider
     * thread that holds lock.
     */
    private void ticketQuiet(LoadJob job) {
        synchronized (lock) {
            settle(job);
        }
        publishStatus();
    }

    /**
     * Stop further attempts of the job and have the client cancel whatever of it may still be
     * searching. May settle the job at once (reentrantly) when nothing is. Callers must hold lock.
     */
    private void cancelOnSplunk(LoadJob job) {
        if (!job.ticket.cancelRequested().isEmpty()) {
            clientSupplier.get().cancelJobs(job.ticket);
        }
    }

    private void runJob(LoadJob job) {
        try {
            synchronized (lock) {
                if (job.state != JobState.RUNNING) {
                    // Superseded before it started: nothing was sent, nothing to discard
                    return;
                }
            }
            load(job);
        } catch (SearchResultToBigException e) {
            log.info("Load result too big ({}): {}", job.description, e.toString());
        } catch (SearchCancelledException e) {
            log.info("Load cancelled ({}): {}", job.description, e.toString());
        } catch (Throwable e) {
            synchronized (lock) {
                if (job == currentLoad) {
                    log.error("Load failed ({}), stopping at the loaded segment", job.description, e);
                    // Stop deliberately rather than retry: pin the boundaries while
                    // this job is still current, so no getLine can observe the
                    // not-loading window and silently re-issue the failed load.
                    // Scrolling past an edge (unfreeze) or a new jump restarts.
                    stopAtSegment();
                    loaderException = e;
                } else {
                    log.debug("Superseded load failed ({}), discarding error: {}", job.description, e.toString());
                }
            }
        } finally {
            synchronized (lock) {
                if (job == currentLoad) {
                    currentLoad = null;
                    setStatus(idleStatus(job.decision));
                }
                if (job.state == JobState.RUNNING) {
                    job.state = JobState.ABANDONED;
                }
                // The worker is gone, but a returned worker proves nothing about Splunk (an
                // interrupted one returns at once): the job keeps its slot until its ticket is quiet.
                cancelOnSplunk(job);
            }
            publishStatus();
            logUpdatedCallback.call();
        }
    }

    /**
     * Detach the current job (if any) so its late result is discarded. A pending job is dropped
     * without ever being sent. A running job is abandoned: no further attempt may start, its worker
     * is interrupted and its Splunk jobs are cancelled by SID on the client's thread, without
     * waiting for the worker. It keeps its slot until its ticket is quiet. Callers must hold lock;
     * nothing here blocks.
     */
    private void supersedeCurrentLoad() {
        LoadJob job = currentLoad;
        currentLoad = null;
        if (job == null) {
            return;
        }
        log.debug("Superseding {} load: {}", job.state, job.description);
        waitingReportedFor = null;
        setStatus(idleStatus(job.decision));
        if (job.state == JobState.PENDING) {
            job.state = JobState.DROPPED;
            return;
        }
        if (job.state != JobState.RUNNING) {
            return;
        }
        job.state = JobState.ABANDONED;
        // First stop new attempts, then interrupt: an attempt either sees the cancel or its SID is
        // a cancel target. A ticket without searching SIDs settles here (never started, nothing
        // created, or the search is done and only the result transfer remains).
        cancelOnSplunk(job);
        if (job.future != null) {
            job.future.cancel(true);
        }
    }

    private boolean isLoading() {
        return currentLoad != null;
    }

    /**
     * Report a failed background load once. The stopped state was already
     * applied by runJob when the failure happened.
     */
    private void checkLoaderException() throws TemporarySearchException, SearchException, StorageException {
        if (loaderException != null) {
            Throwable t = loaderException;
            loaderException = null;
            switch (t) {
                case TemporarySearchException e -> throw e;
                case SearchException e -> throw e;
                default -> {
                    throw new IllegalStateException("Internal failure", t);
                }
            }
        }
    }

    /**
     * Start browsing at a new target: reset the search window around it, replace the
     * segment, and schedule the initial load. Any in-flight load is superseded — its
     * late result is discarded (see {@link LoadJob}).
     */
    private void getFirstLine(Instant target) {
        getFirstLine(target, null);
    }

    private void getFirstLine(Instant target, SearchHit targetHit) {
        Instant from = target.truncatedTo(ChronoUnit.DAYS);
        getFirstLine(target, targetHit, from, from.plus(1, ChronoUnit.DAYS));
    }

    private void getFirstLine(Instant target, SearchHit targetHit, Instant from, Instant to) {
        log.debug("getFirstLine: {}", target);
        synchronized (lock) {
            if (target.isAfter(searchBoundaries.getNowBoundary())) {
                throw new IllegalArgumentException(
                        "Target time must not be after now boundary: " + searchBoundaries.getNowBoundary());
            }

            supersedeCurrentLoad();

            searchBoundaries.reset(from, to);
            loadingStrategy.reset(target, 0, from, to);

            // Detach the outgoing segment before setting the boundaries: the setters
            // run updateBoundaries() when a segment exists and would stamp the old
            // segment's line numbers onto the fresh boundaries (CursorManager clamps
            // scrolling to those). With no segment attached the setters create
            // time-only boundaries; insertLogs() assigns line numbers once the new
            // segment has loaded.
            currentSegment = null;

            setAfterBoundary(to);
            setBeforeBoundary(from);

            var segment = new LogSegment(target);
            // The first query may be truncated and retried via load(); the retry
            // initializes this still-empty segment around the preserved target.
            segment.preserveInitialTarget(target);
            if (targetHit != null) {
                segment.preserveInitialTarget(targetHit);
            }
            currentSegment = segment;
            log.debug("getFirstLine: created new segment {}", segment);

            var initial = loadingStrategy.calculateInitialSearchRange(target);
            scheduleLoad(new LoadJob(segment, initial, 0, "jump to " + target));
        }
    }

    /**
     * Insert logs into the database and update boundary line numbers.
     * Called after logs are loaded and line numbers are assigned.
     */
    private void insertLogs(String segmentId, LoadedLogLines sortedEvents) throws StorageException {
        databaseManager.insertLogsBatch(segmentId, sortedEvents.logLines());

        // Recalculate boundaries after inserting logs
        updateBoundaries();
    }

    /**
     * Update boundary instances with line numbers if the segment boundaries align with search boundaries.
     * This is called:
     * - After inserting logs (insertLogs)
     * - When boundary times are changed (setBeforeBoundary, setAfterBoundary)
     * - When nowBoundary time changes (getNowBoundary)
     * <p>
     */
    private void updateBoundaries() {
        synchronized (lock) {
            if (currentSegment == null) {
                return;
            }

            LogSegment segment = currentSegment;

            // Get boundary times from strategy
            Instant beforeTime = searchBoundaries.getMinBoundary();
            Instant afterTime = searchBoundaries.getMaxBoundary();

            // Update boundaries with line numbers
            if (beforeTime != null) {
                beforeBoundary = new Boundary(BEFORE, beforeTime, segment.getStartLine());
            }

            // Update afterBoundary: if segment ends at the boundary, include last line
            if (afterTime != null) {
                afterBoundary = new Boundary(AFTER, afterTime, segment.getEndLineExl());
            }

            log.debug("Boundaries: {}, {}, {}", beforeBoundary, afterBoundary, getNowBoundary());
        }
    }

    /**
     * Fetch the time range of the job's decision and publish it into the job's
     * segment — provided the job is still current when the fetch returns. A
     * superseded job discards whatever it fetched.
     * <p>
     * This is the single load path: for a still-empty segment (initial load, or
     * retry after a truncated initial query) {@link LogSegment#add} initializes it
     * around its preserved target; otherwise it extends the segment.
     */
    private void load(LoadJob job) throws SearchException, StorageException, TemporarySearchException {
        LoadDecision decision = job.decision;
        log.debug("load: {}", decision);

        LoadedLogLines sortedEvents;
        try {
            sortedEvents = fetchLogsInRange(job);
        } catch (SearchResultToBigException e) {
            log.info("load: result too big for {}", decision, e);
            // A truncated result shrinks the strategy's search window so the next
            // request retries with a smaller range — unless the job is already stale.
            synchronized (lock) {
                if (job.isCurrent()) {
                    loadingStrategy.recordTooBigResult(e, decision, false);
                }
            }
            throw e;
        }

        synchronized (lock) {
            if (!job.isCurrent()) {
                log.info("Discarding {} events from superseded load ({})", sortedEvents.logLines().size(),
                        job.description);
                return;
            }

            // Verify before anything is stored: a failing load must leave the segment,
            // the strategy and the boundaries exactly as they were.
            verifyWithinWindow(decision, sortedEvents.logLines());

            // Update loading strategy with load result, and store if accepted
            if (loadingStrategy.recordLoadResult(job.requestedLine, sortedEvents, decision)) {
                int startLine = job.segment.add(decision, sortedEvents);
                updateLineNumbers(sortedEvents.logLines(), startLine, job.segment.getId());
                insertLogs(job.segment.getId(), sortedEvents);

                log.debug("load: {} lines {}:{} in {}", sortedEvents.logLines().size(), job.segment.getStartLine(),
                        job.segment.getEndLineExl(), job.segment.getId());
            } else {
                log.debug("load: strategy declined to store {}", decision);
            }
        }
    }

    private static void verifyWithinWindow(LoadDecision decision, List<LoadedLogLine> lines) {
        for (LoadedLogLine line : lines) {
            if (!decision.contains(line.getSplunkTime())) {
                throw new IllegalStateException("Event time " + line.getSplunkTime() + " is outside decision window ["
                        + decision.start() + ", " + decision.end() + ")");
            }
        }
    }

    private void updateLineNumbers(List<LoadedLogLine> sortedEvents, int startLine, String segmentId) {
        // set the line numbers for the events
        if (sortedEvents.isEmpty()) {
            log.debug("updateLineNumbers: no events to update for segment {}", segmentId);
            return;
        }
        log.debug("updateLineNumbers: assigning line numbers starting at {} for {} events in segment {}", startLine,
                sortedEvents.size(), segmentId);
        for (int i = 0; i < sortedEvents.size(); i++) {
            sortedEvents.get(i).setLineNumber(startLine + i);
            sortedEvents.get(i).setSegmentId(segmentId);
        }
    }

    /**
     * Fetches logs in the specified absolute time range [start, end).
     * The time range is provided by LoadDecision which has already applied boundary constraints.
     *
     * @param job the job whose decision contains the time range to fetch; progress is reported only
     *            while the job is current
     * @return Loaded log lines with actual time boundaries
     */
    private LoadedLogLines fetchLogsInRange(LoadJob job)
            throws SearchException, TemporarySearchException, SearchCancelledException {

        LoadDecision decision = job.decision;

        // Validate time range
        if (!decision.start().isBefore(decision.end())) {
            throw new IllegalArgumentException("Start must be before end: " + decision);
        }

        log.info("fetchLogsInRange: loading {}, beforeBoundary={}, afterBoundary={}, nowBoundary={}", decision,
                searchBoundaries.getMinBoundary(), searchBoundaries.getMaxBoundary(),
                searchBoundaries.getNowBoundary());

        var splunkClient = clientSupplier.get();
        // Stream is auto-closed when terminal operation completes
        try (Stream<Event> eventStream = splunkClient.queryEventsWithMonitoring(decision, currentOnly(job),
                baselineQuerySupplier.get(), job.ticket)) {
            var logs = mapLoadedLogLines(stopWhenStale(eventStream, job), decision);
            log.info("Fetched {} events for {}", logs.size(), decision);
            var result = new LoadedLogLines(decision.start(), decision.end(), logs);
            log.debug("Got {}  for {}", result, decision);
            return result;
        }
    }

    /**
     * Wrap the progress callback so a superseded job's late progress is dropped. The
     * external callback runs outside the provider lock.
     */
    private ProgressCallback currentOnly(LoadJob job) {
        return progress -> {
            ProgressCallback callback;
            long seq;
            synchronized (lock) {
                if (job != currentLoad) {
                    return;
                }
                callback = loadProgressCallback;
                seq = ++statusSeq;
            }
            deliver(seq, progress, callback);
        };
    }

    /** Events checked per stale check while a superseded job's results are still being read. */
    private static final int STALE_CHECK_INTERVAL = 256;

    /**
     * Stop reading a superseded job's results instead of downloading them only to discard them.
     * The check is cooperative (every {@link #STALE_CHECK_INTERVAL} events); a read blocked on the
     * socket is ended by the interrupt.
     */
    private Stream<Event> stopWhenStale(Stream<Event> events, LoadJob job) {
        int[] read = {0};
        return events.peek(event -> {
            if (++read[0] % STALE_CHECK_INTERVAL == 0 && !job.isCurrent()) {
                throw new SearchCancelledException("Superseded load stopped reading its results", null);
            }
        });
    }

    /** Record the load status to hand to the progress callback once the lock is released. Under lock. */
    private void setStatus(SearchProgress status) {
        unpublishedStatus = status;
        unpublishedStatusSeq = ++statusSeq;
    }

    /**
     * Hand the latest recorded load status to the progress callback. Must run without the provider
     * lock (the callback is foreign code); a no-op when called with the lock held, the outermost
     * caller publishes.
     */
    private void publishStatus() {
        if (unpublishedStatus == null || Thread.holdsLock(lock)) {
            return;
        }
        SearchProgress status;
        long seq;
        ProgressCallback callback;
        synchronized (lock) {
            status = unpublishedStatus;
            if (status == null) {
                return;
            }
            unpublishedStatus = null;
            seq = unpublishedStatusSeq;
            callback = loadProgressCallback;
        }
        deliver(seq, status, callback);
    }

    /**
     * Deliver in decision order without holding any lock across the callback: the delivery is
     * queued, and the thread that finds no delivery in progress drains the queue, so callbacks never
     * overlap and never block a caller (the EDT included) that only queues. A status or progress
     * decided before one already delivered is dropped. The drainer may run callbacks queued by other
     * threads, so the callback must be thread-agnostic (the UI's hands over with invokeLater).
     */
    private void deliver(long seq, SearchProgress progress, ProgressCallback callback) {
        synchronized (deliveryLock) {
            deliveries.add(new Delivery(seq, progress, callback));
            if (delivering) {
                return;
            }
            delivering = true;
        }
        while (true) {
            Delivery next;
            synchronized (deliveryLock) {
                next = deliveries.poll();
                if (next == null) {
                    delivering = false;
                    return;
                }
                if (next.seq() < deliveredSeq) {
                    continue;
                }
                deliveredSeq = next.seq();
            }
            try {
                next.callback().onProgress(next.progress());
            } catch (RuntimeException e) {
                log.warn("Load progress callback failed", e);
            }
        }
    }

    /** The job was sent to Splunk; its own progress follows. */
    private static SearchProgress startingStatus(LoadDecision decision) {
        return new SearchProgress("N/A", decision.start(), decision.end(), true, false, false, false, 0, 0, 0, 0, 0.0f,
                0.0f, "Starting", false, decision.direction());
    }

    /** No load is running for the view any more (finished, dropped or cancelled). */
    private static SearchProgress idleStatus(LoadDecision decision) {
        return new SearchProgress("N/A", decision.start(), decision.end(), false, false, true, false, 0, 0, 0, 0, 0.0f,
                0.0f, "Idle", false, decision.direction());
    }

    /**
     * Map the fetched events and keep exactly those in the decision's half-open window
     * {@code [start, end)}. Splunk floors a sub-second {@code earliest_time} to the whole
     * second, so it can return events up to a second before {@code start}; those belong to
     * the adjacent (earlier) window, which will load them itself, so they are dropped here
     * instead of being stored in this segment.
     */
    private List<LoadedLogLine> mapLoadedLogLines(Stream<Event> eventStream, LoadDecision decision) {
        var mapped = eventStream.flatMap(logEventMapper::mapEvent).sorted(EventOrder.loadedLogLineComparator())
                .toList();
        var inWindow = mapped.stream().filter(line -> decision.contains(line.getSplunkTime())).toList();
        if (inWindow.size() != mapped.size()) {
            log.debug("Dropped {} of {} events outside [{}, {}) returned by Splunk", mapped.size() - inWindow.size(),
                    mapped.size(), decision.start(), decision.end());
        }
        return inWindow;
    }

    private List<JobTicket> inFlightTickets() {
        synchronized (lock) {
            return inFlight.stream().map(job -> job.ticket).toList();
        }
    }

    /**
     * Set the callback used for reporting progress during log load operations.
     */
    public void setLoadProgressCallback(ProgressCallback loadProgressCallback) {
        this.loadProgressCallback = loadProgressCallback;
    }

    /**
     * Shutdown the data provider and cleanup resources.
     * Cancels any ongoing background loading and shuts down the executor.
     */
    public void shutdown() {
        log.info("Shutting down SplunkLogDataProvider");

        List<CompletableFuture<Void>> quiet;
        synchronized (lock) {
            // No more dispatches: a pending load is dropped, the running one is cancelled by SID
            shutdown = true;
            supersedeCurrentLoad();
            quiet = inFlight.stream().map(job -> job.ticket.whenQuiet()).toList();
        }
        publishStatus();

        // Interrupt running tasks; a request that ignores interruption is abandoned (daemon
        // virtual thread) rather than waited for.
        executor.shutdownNow();
        // Give the SID cancels (they run on the client's own threads) a bounded chance to be
        // confirmed before the caller logs the session out (which stops them).
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
        try {
            CompletableFuture.allOf(quiet.toArray(CompletableFuture[]::new))
                    .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.warn("Shutting down with unconfirmed Splunk job cancels: {}", inFlightTickets());
        } catch (ExecutionException e) {
            log.debug("Splunk job cancel failed during shutdown: {}", e.getMessage());
        } catch (InterruptedException e) {
            log.debug("Interrupted while waiting for Splunk job cancels");
            Thread.currentThread().interrupt();
        }
        try {
            if (!executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                log.warn("Abandoning load tasks that ignore interruption");
            }
        } catch (InterruptedException e) {
            log.debug("Interrupted while waiting for executor shutdown");
            Thread.currentThread().interrupt();
        }

        // Reset singleton counter to allow new instance after logout
        counter.set(0);

        log.info("SplunkLogDataProvider shutdown complete");
    }

    @Override
    public String toString() {
        return "SplunkLogDataProvider{" + ", currentSegment=" + currentSegment + ", minBoundary="
                + searchBoundaries.getMinBoundary() + ", maxBoundary=" + searchBoundaries.getMaxBoundary() + ", clock="
                + clock + '}';
    }
}
