package se.grynna.pager.splunk;

import com.splunk.Event;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.exceptions.SearchResultToBigException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Executes one origin-centred session run, with one sequential worker per side. */
@Slf4j
public class Searcher {
    static final int MAX_HITS = 10_000;

    private final SplunkClient splunkClient;
    private final LogEventMapper eventMapper;
    @Getter
    private final SearchSession session;
    private final String splunkQuery;
    private final SearchPlan plan;
    private final int cap;
    private final long run;
    private final Object planLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger workers = new AtomicInteger(2);
    private final ExecutorService executor = Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("searcher-", 0).factory());
    private final List<Future<?>> tasks = new ArrayList<>();
    /** The Splunk jobs of the chunks in flight, cancelled by SID on abandon. */
    private final Set<JobTicket> tickets = ConcurrentHashMap.newKeySet();
    private volatile boolean abandoned;

    /** Use the session's browsing origin, clamped by the plan to this run's window. */
    public Searcher(SplunkClient client, LogEventMapper mapper, SearchSession session, String query,
            Instant earliest, Instant latest) {
        this(client, mapper, session, query, earliest, latest, session.getSearchOrigin());
    }

    public Searcher(SplunkClient client, LogEventMapper mapper, SearchSession session, String query,
            Instant earliest, Instant latest, Instant origin) {
        this(client, mapper, session, query, earliest, latest, origin, MAX_HITS);
    }

    Searcher(SplunkClient client, LogEventMapper mapper, SearchSession session, String query,
            Instant earliest, Instant latest, Instant origin, int cap) {
        splunkClient = client;
        eventMapper = mapper;
        this.session = session;
        splunkQuery = query;
        this.cap = cap;
        plan = new SearchPlan(origin, earliest, latest, cap);
        run = session.beginRun();
        session.configureSides(run, earliest, latest,
                origin.isBefore(earliest) ? earliest : origin.isAfter(latest) ? latest : origin);
    }

    /** Starts at most once; submitting and cancelling are serialized, never blocking on I/O. */
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) return;
        if (!session.isActiveRun(run)) {
            executor.shutdown();
            return;
        }
        try {
            for (SearchSession.Side side : SearchSession.Side.values()) {
                tasks.add(executor.submit(() -> executeSide(side)));
            }
        } catch (RejectedExecutionException ignored) {
            // A cancelled searcher cannot be restarted.
        }
        executor.shutdown();
    }

    private void executeSide(SearchSession.Side side) {
        try {
            while (session.isActiveRun(run) && !Thread.currentThread().isInterrupted()) {
                Optional<SearchPlan.Chunk> next;
                synchronized (planLock) {
                    next = plan.next(side);
                }
                if (next.isEmpty()) break;
                SearchPlan.Chunk chunk = next.orElseThrow();
                int budget = Math.max(0, cap - session.hitCount());
                if (budget == 0) {
                    session.markTruncated(run, side);
                    break;
                }
                List<LoadedLogLine> buffered = new ArrayList<>();
                boolean overflow = false;
                LoadDecision decision = LoadDecision.loadWithMaxEvents(null,
                        side == SearchSession.Side.EARLIER ? -1 : 1, chunk.start(), chunk.end(),
                        "search: " + session.getQuery(), budget + 1);
                JobTicket ticket = new JobTicket();
                tickets.add(ticket);
                // After add: an abandon either sees the ticket or is seen here
                if (abandoned) splunkClient.cancelJobs(ticket);
                try (Stream<Event> stream = splunkClient.queryEventsWithMonitoring(decision, progress -> {},
                        splunkQuery, ticket)) {
                    Iterator<Event> events = stream.iterator();
                    while (session.isActiveRun(run) && !Thread.currentThread().isInterrupted() && events.hasNext()) {
                        Event event = events.next();
                        if (!session.isActiveRun(run) || Thread.currentThread().isInterrupted()) return;
                        if (buffered.size() == budget) {
                            overflow = true;
                            break;
                        }
                        buffered.add(eventMapper.mapEventSingleLine(event));
                    }
                } catch (SearchResultToBigException e) {
                    overflow = true;
                } finally {
                    tickets.remove(ticket);
                }
                if (!session.isActiveRun(run) || Thread.currentThread().isInterrupted()) return;
                int remaining = overflow
                        ? -2
                        : session.commitChunk(run, buffered, chunk.start(), chunk.end(), cap);
                if (remaining == -1) return;
                synchronized (planLock) {
                    if (remaining == -2) {
                        plan.overflowed(chunk);
                    } else {
                        plan.completed(chunk, buffered.size(), remaining);
                    }
                    SearchPlan.SideState state = plan.state(side);
                    if (state.truncated()) session.markTruncated(run, side);
                    if (state.gaveUpBelowOneSecond()) session.markTooManyMatchesWithinSecond(run);
                }
            }
        } catch (Exception e) {
            if (session.fail(run, e.getMessage())) {
                log.error("Search failed for '{}'", session.getQuery(), e);
                abandon();
            }
        } finally {
            session.finishSide(run, side);
            if (workers.decrementAndGet() == 0) session.complete(run);
        }
    }

    /** No-op: every completed chunk covers its own window, including extensions. */
    Searcher coverWindowOnComplete() {
        return this;
    }

    /** Stop keeps only fully completed chunks; in-flight buffers are never published. */
    public void cancel() {
        session.cancel(run);
        abandon();
    }

    /** Superseding callers install the new run token before interrupting the old workers. */
    synchronized void abandon() {
        abandoned = true;
        tickets.forEach(splunkClient::cancelJobs);
        for (Future<?> task : tasks)
            task.cancel(true);
        executor.shutdownNow();
    }
}
