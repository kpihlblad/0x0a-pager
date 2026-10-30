package se.grynna.pager.splunk;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Active search session that collects search hits streamed from Splunk.
 * Hits keep both Splunk query time and display time plus event identity fields
 * used to disambiguate equal-time events. Each hit may also keep the mapped
 * {@link LoadedLogLine} it came from.
 *
 * State machine: RUNNING → COMPLETED | CANCELLED | ERROR
 *
 * Thread-safe: hits are accessed from the background searcher thread
 * and the EDT concurrently. Mutable state is guarded by {@code synchronized (this)};
 * listeners are never called while that lock is held. {@link #snapshot()} is the
 * immutable view for readers that must not copy the hit list on every repaint.
 */
@Slf4j
public class SearchSession {

    public enum State {
        RUNNING, COMPLETED, CANCELLED, ERROR
    }

    /**
     * Which side of the covered window lost matches because the hit cap was reached.
     */
    public enum Side {
        EARLIER, LATER
    }

    /**
     * Immutable view of the session. {@code lines.get(i)} is the mapped event for
     * {@code hits.get(i)} and may be null when the hit was recorded with
     * {@link #addHit(long, SearchHit)} only. Cached by the session until
     * {@link #version()} changes.
     */
    public record Snapshot(long version, List<SearchHit> hits, List<LoadedLogLine> lines, State state,
            Instant windowStart, Instant windowEnd, boolean truncatedEarlier, boolean truncatedLater,
            boolean tooManyMatchesWithinSecond, boolean earlierDone, boolean laterDone,
            String query, Instant origin, String errorMessage) {

        public Snapshot(long version, List<SearchHit> hits, List<LoadedLogLine> lines, State state,
                Instant windowStart, Instant windowEnd, boolean truncatedEarlier, boolean truncatedLater,
                boolean tooManyMatchesWithinSecond) {
            this(version, hits, lines, state, windowStart, windowEnd, truncatedEarlier, truncatedLater,
                    tooManyMatchesWithinSecond, false, false, "", null, null);
        }

        public Snapshot {
            // List.copyOf rejects nulls; addHit-only entries store a null line.
            hits = Collections.unmodifiableList(new ArrayList<>(hits));
            lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }

        public int size() {
            return hits.size();
        }

        /**
         * Index of {@code hit} under {@code EventOrder.searchHitComparator()}, or -1
         * if no stored hit compares equal. Equality is exact identity (time plus
         * {@code _bkt}/{@code _cd}), not merely the same timestamp.
         */
        public int indexOf(SearchHit hit) {
            if (hit == null) {
                return -1;
            }
            int idx = Collections.binarySearch(hits, hit, EventOrder.searchHitComparator());
            return idx >= 0 ? idx : -1;
        }

        /** Index of the first hit {@code >= probe}, or {@link #size()} if none. */
        public int ceilingIndex(SearchHit probe) {
            int idx = Collections.binarySearch(hits, probe, EventOrder.searchHitComparator());
            if (idx >= 0) {
                return idx;
            }
            return -idx - 1;
        }
    }

    /** Minimum interval between incremental hit notifications, in milliseconds */
    private static final long UPDATE_THROTTLE_MS = 100;

    private final String query;
    private final Instant searchOrigin;
    private final TreeMap<SearchHit, LoadedLogLine> matches = new TreeMap<>(EventOrder.searchHitComparator());
    private final Set<SearchHit.Identity> hitIdentities = new HashSet<>();
    private final List<Consumer<SearchSession>> updateListeners = new CopyOnWriteArrayList<>();
    private volatile State state = State.RUNNING;
    private volatile String errorMessage;
    private volatile long lastNotifyMillis;
    /**
     * Identifies the Splunk query currently feeding this session. Each
     * {@link Searcher} gets its own run; results and terminal transitions from
     * a superseded run (a stopped query whose stream is still draining, or the
     * run before an extension) are ignored so they cannot resurrect, finish, or
     * fail the current one.
     */
    private long currentRun;
    /** Set when the session was cancelled and replaced; it accepts no more runs or hits. */
    private volatile boolean discarded;
    /** Union of time windows this session has already queried. */
    private volatile Instant windowStart;

    private volatile Instant windowEnd;

    /** Bumped under the lock on every successful add and every state change. */
    private long version;

    private boolean truncatedEarlier;
    private boolean truncatedLater;
    private boolean tooManyMatchesWithinSecond;
    private boolean earlierDone;
    private boolean laterDone;

    /** Rebuilt only when {@link #version} changes. Accessed under the lock. */
    private Snapshot cachedSnapshot;

    public SearchSession(String query, Instant searchOrigin) {
        this.query = query;
        this.searchOrigin = searchOrigin;
    }

    /**
     * Test helper: record a hit with no mapped line.
     * Listeners are notified at most once per {@link #UPDATE_THROTTLE_MS}.
     */
    void addHit(Instant timestamp) {
        addHit(SearchHit.at(timestamp));
    }

    void addHit(SearchHit hit) {
        long run;
        synchronized (this) {
            run = currentRun;
        }
        addHit(run, hit);
    }

    /** Add a hit produced by {@code run}, storing a null line. Ignored unless that run is still running. */
    void addHit(long run, SearchHit hit) {
        addMatch(run, hit, null);
    }

    /**
     * Add a match produced by {@code run}. Same run, identity and dedupe rules as
     * {@link #addHit(long, SearchHit)}; {@code line} may be null. Listeners are
     * notified at most once per {@link #UPDATE_THROTTLE_MS} so a fast-streaming
     * search does not flood the EDT with repaint requests.
     */
    void addMatch(long run, SearchHit hit, LoadedLogLine line) {
        synchronized (this) {
            if (!isActiveRun(run)) {
                return;
            }
            var identity = hit.identity();
            if (identity != null && !hitIdentities.add(identity)) {
                return;
            }
            if (matches.containsKey(hit)) {
                return;
            }
            matches.put(hit, line);
            version++;
        }
        // Notify outside the lock — listeners must never block the searcher thread.
        long now = System.currentTimeMillis();
        if (now - lastNotifyMillis >= UPDATE_THROTTLE_MS) {
            lastNotifyMillis = now;
            notifyListeners();
        }
    }

    /** Next hit after the given reference hit (for forward navigation). */
    public synchronized Optional<SearchHit> nextHit(SearchHit after) {
        return Optional.ofNullable(matches.higherKey(after));
    }

    /** Previous hit before the given reference hit (for backward navigation). */
    public synchronized Optional<SearchHit> previousHit(SearchHit before) {
        return Optional.ofNullable(matches.lowerKey(before));
    }

    /** Next hit after the given timestamp (for forward navigation). */
    public synchronized Optional<Instant> nextHit(Instant after) {
        return nextHit(SearchHit.at(after)).map(SearchHit::splunkTime);
    }

    /** Previous hit before the given timestamp (for backward navigation). */
    public synchronized Optional<Instant> previousHit(Instant before) {
        return previousHit(SearchHit.at(before)).map(SearchHit::splunkTime);
    }

    public synchronized SearchHit firstHit() {
        return matches.firstKey();
    }

    public synchronized SearchHit lastHit() {
        return matches.lastKey();
    }

    /** Closest hit to the given timestamp in either direction */
    public synchronized Optional<Instant> closestHit(Instant around) {
        SearchHit probeMin = new SearchHit(around, Instant.MIN, null, null);
        SearchHit probeMax = new SearchHit(around, Instant.MAX, "\uFFFF", "\uFFFF");

        var exact = matches.ceilingKey(probeMin);
        if (exact != null && exact.splunkTime().equals(around)) {
            return Optional.of(around);
        }

        var higher = matches.higherKey(probeMax);
        var lower = matches.lowerKey(probeMin);

        if (higher == null) return Optional.ofNullable(lower).map(SearchHit::splunkTime);
        if (lower == null) return Optional.of(higher.splunkTime());

        var distHigher = java.time.Duration.between(around, higher.splunkTime()).abs();
        var distLower = java.time.Duration.between(around, lower.splunkTime()).abs();
        return Optional.of(distHigher.compareTo(distLower) <= 0 ? higher.splunkTime() : lower.splunkTime());
    }

    /** All search hits sorted by event order. */
    public synchronized List<SearchHit> getHits() {
        return List.copyOf(matches.keySet());
    }

    public synchronized int hitCount() {
        return matches.size();
    }

    /**
     * Monotonic counter bumped on every successful add and every state change
     * (a {@link #beginRun()} that actually changes state, completion, failure,
     * cancellation, discard, a widening {@link #coverWindow}, and truncation).
     */
    public synchronized long version() {
        return version;
    }

    /**
     * Cached immutable view. The same instance is returned until {@link #version()}
     * changes.
     */
    public synchronized Snapshot snapshot() {
        if (cachedSnapshot != null && cachedSnapshot.version() == version) {
            return cachedSnapshot;
        }
        List<SearchHit> hitList = new ArrayList<>(matches.size());
        List<LoadedLogLine> lineList = new ArrayList<>(matches.size());
        for (var entry : matches.entrySet()) {
            hitList.add(entry.getKey());
            lineList.add(entry.getValue());
        }
        cachedSnapshot = new Snapshot(version, hitList, lineList, state, windowStart, windowEnd, truncatedEarlier,
                truncatedLater, tooManyMatchesWithinSecond, earlierDone, laterDone, query, searchOrigin, errorMessage);
        return cachedSnapshot;
    }

    /**
     * Binary-search index of {@code hit} in event order, or -1 if it is not a stored match.
     * Delegates to the cached {@link #snapshot()}.
     */
    public int indexOf(SearchHit hit) {
        return snapshot().indexOf(hit);
    }

    /** True if {@code side} stopped early because the hit cap was reached. */
    public synchronized boolean isTruncated(Side side) {
        return switch (side) {
            case EARLIER -> truncatedEarlier;
            case LATER -> truncatedLater;
        };
    }

    /**
     * Record that {@code run} hit the match cap on {@code side}. Ignored for a stale
     * or finished run. Notifies listeners when the flag actually changes.
     */
    void markTruncated(long run, Side side) {
        boolean changed = false;
        synchronized (this) {
            if (!isActiveRun(run)) {
                return;
            }
            switch (side) {
                case EARLIER -> {
                    if (!truncatedEarlier) {
                        truncatedEarlier = true;
                        changed = true;
                    }
                }
                case LATER -> {
                    if (!truncatedLater) {
                        truncatedLater = true;
                        changed = true;
                    }
                }
            }
            if (changed) {
                version++;
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    /** Dense first-chunk give-up; status text can explain why narrowing is needed. */
    void markTooManyMatchesWithinSecond(long run) {
        synchronized (this) {
            if (!isActiveRun(run) || tooManyMatchesWithinSecond) return;
            tooManyMatchesWithinSecond = true;
            version++;
        }
        notifyListeners();
    }

    /**
     * Atomically publish a whole chunk and its exact coverage. The budget includes
     * previous runs (extensions) and both concurrent sides. Returns remaining budget,
     * -1 for a stale run, or -2 for overflow; rejected chunks change nothing.
     */
    int commitChunk(long run, List<LoadedLogLine> lines, Instant start, Instant end, int cap) {
        int remaining;
        synchronized (this) {
            if (!isActiveRun(run)) return -1;
            if (lines.size() > cap - matches.size()) return -2;
            for (LoadedLogLine line : lines) {
                SearchHit hit = SearchHit.from(line);
                var identity = hit.identity();
                if ((identity == null || hitIdentities.add(identity)) && !matches.containsKey(hit)) {
                    matches.put(hit, line);
                    version++;
                }
            }
            coverWindow(start, end);
            remaining = cap - matches.size();
        }
        // One notification per chunk, including empty chunks (coverage is progress).
        notifyListeners();
        return remaining;
    }

    /** Guard incremental coverage against stopped/superseded runs. */
    void coverWindow(long run, Instant start, Instant end) {
        synchronized (this) {
            if (!isActiveRun(run)) return;
            coverWindow(start, end);
        }
    }

    /** Timestamp of the earliest hit, or empty if no hits */
    public synchronized Optional<Instant> firstHitTimestamp() {
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.firstKey().displayTime());
    }

    /** Timestamp of the latest hit, or empty if no hits */
    public synchronized Optional<Instant> lastHitTimestamp() {
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.lastKey().displayTime());
    }

    public State getState() {
        return state;
    }

    public String getQuery() {
        return query;
    }

    public Instant getSearchOrigin() {
        return searchOrigin;
    }

    /** Earliest instant already included in this text search, or null if none yet. */
    public Instant windowStart() {
        return windowStart;
    }

    /** Latest instant already included in this text search, or null if none yet. */
    public Instant windowEnd() {
        return windowEnd;
    }

    /**
     * Remember that [start, end] has been queried. Later calls widen the union.
     * A call that does not grow the union is a no-op and does not bump {@link #version()}.
     * Does not notify listeners; a chunked searcher calls this as each chunk completes.
     */
    void coverWindow(Instant start, Instant end) {
        if (start == null || end == null) {
            return;
        }
        Instant from = start.isBefore(end) ? start : end;
        Instant to = start.isBefore(end) ? end : start;
        synchronized (this) {
            Instant newStart = windowStart == null || from.isBefore(windowStart) ? from : windowStart;
            Instant newEnd = windowEnd == null || to.isAfter(windowEnd) ? to : windowEnd;
            if (Objects.equals(newStart, windowStart) && Objects.equals(newEnd, windowEnd)) {
                return;
            }
            windowStart = newStart;
            windowEnd = newEnd;
            version++;
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Start a new run (a new Splunk query feeding this session) and return its
     * token. Any previous run is superseded. The session goes back to RUNNING
     * unless it has been {@link #discard() discarded}, in which case the
     * returned run is never active. {@link #version()} bumps only when the state
     * actually changes.
     */
    long beginRun() {
        boolean changed;
        long run;
        synchronized (this) {
            run = ++currentRun;
            if (discarded) {
                return run;
            }
            changed = state != State.RUNNING;
            state = State.RUNNING;
            errorMessage = null;
            if (changed) {
                version++;
            }
        }
        if (changed) {
            notifyListeners();
        }
        return run;
    }

    /** Initialize this run's active sides, including one-sided extensions. */
    void configureSides(long run, Instant start, Instant end, Instant origin) {
        synchronized (this) {
            if (!isActiveRun(run)) return;
            earlierDone = !origin.isAfter(start);
            laterDone = !origin.isBefore(end);
            version++;
        }
        notifyListeners();
    }

    /** One worker finished; the other may still be searching. Stale runs cannot alter it. */
    void finishSide(long run, Side side) {
        synchronized (this) {
            if (!isActiveRun(run)) return;
            if (side == Side.EARLIER) earlierDone = true;
            else
                laterDone = true;
            version++;
        }
        notifyListeners();
    }

    /** True while {@code run} is the current run and still running. */
    synchronized boolean isActiveRun(long run) {
        return !discarded && run == currentRun && state == State.RUNNING;
    }

    /** Mark {@code run} completed. Returns false (and does nothing) for a stale or finished run. */
    boolean complete(long run) {
        return complete(run, null, null);
    }

    /**
     * Mark {@code run} completed and, if given, record [{@code coverFrom},
     * {@code coverTo}] as queried before listeners hear about the completion.
     */
    boolean complete(long run, Instant coverFrom, Instant coverTo) {
        synchronized (this) {
            if (!isActiveRun(run)) {
                return false;
            }
            coverWindow(coverFrom, coverTo);
            state = State.COMPLETED;
            errorMessage = null;
            version++;
        }
        notifyListeners();
        return true;
    }

    /** Mark {@code run} failed. Returns false (and does nothing) for a stale or finished run. */
    boolean fail(long run, String message) {
        if (!finish(run, State.ERROR, message)) {
            return false;
        }
        log.error("Search session error: {}", message);
        return true;
    }

    /** Stop {@code run}, keeping the hits found so far. Returns false for a stale or finished run. */
    boolean cancel(long run) {
        if (!finish(run, State.CANCELLED, null)) {
            return false;
        }
        log.info("Search session cancelled for query: {}", query);
        return true;
    }

    private boolean finish(long run, State terminal, String message) {
        synchronized (this) {
            if (!isActiveRun(run)) {
                return false;
            }
            state = terminal;
            errorMessage = message;
            version++;
        }
        notifyListeners();
        return true;
    }

    /** Test convenience: apply {@code state} to the current run. */
    void setState(State state) {
        long run;
        synchronized (this) {
            run = currentRun;
        }
        switch (state) {
            case RUNNING -> beginRun();
            case COMPLETED -> complete(run);
            case CANCELLED -> cancel(run);
            case ERROR -> fail(run, errorMessage);
        }
    }

    /** Test convenience: fail the current run. */
    void setError(String message) {
        long run;
        synchronized (this) {
            run = currentRun;
        }
        fail(run, message);
    }

    /** Stop the current run, keeping the hits found so far. */
    public void cancel() {
        long run;
        synchronized (this) {
            run = currentRun;
        }
        cancel(run);
    }

    /**
     * Cancel and retire this session because it was dropped or replaced. It
     * accepts no further runs or hits, and its listeners are detached after
     * being told about the cancellation.
     */
    public void discard() {
        boolean changed;
        synchronized (this) {
            if (discarded) {
                return;
            }
            changed = state == State.RUNNING;
            if (changed) {
                state = State.CANCELLED;
            }
            discarded = true;
            version++;
        }
        log.info("Search session discarded for query: {}", query);
        if (changed) {
            notifyListeners();
        }
        updateListeners.clear();
    }

    public boolean isDiscarded() {
        return discarded;
    }

    public void addUpdateListener(Consumer<SearchSession> listener) {
        updateListeners.add(listener);
    }

    public void removeUpdateListener(Consumer<SearchSession> listener) {
        updateListeners.remove(listener);
    }

    private void notifyListeners() {
        for (var listener : updateListeners) {
            try {
                listener.accept(this);
            } catch (Exception e) {
                log.warn("Search session listener error: {}", e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "SearchSession{query='%s', state=%s, hits=%d}".formatted(query, state, matches.size());
    }
}
