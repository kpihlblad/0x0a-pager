package se.grynna.pager.splunk;

import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.splunk.SplunkLogDataProvider.LoadState;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The load gate: at most {@link SplunkLogDataProvider#MAX_OPEN} loads whose Splunk search
 * may still run, one pending slot where the latest jump wins, and superseded jobs cancelled by SID
 * without waiting for their workers. A slot frees only when the load's ticket is quiet: every SID
 * proven done, gone, rejected or never sent. The fake client plays the real one: it registers and
 * creates a SID, blocks for the targets the test holds (ignoring interrupts, or ending at once on
 * an interrupt), and marks the SID done when its search completes. The fake cancel records the
 * ticket; the test confirms it explicitly (marks the SIDs gone).
 */
class SplunkLogDataProviderLoadGateTest {

    private static final Instant BASE = Instant.parse("2017-03-01T10:00:00Z");
    private static final List<Instant> TARGETS = IntStream.range(0, 25).mapToObj(i -> BASE.plus(i, DAYS)).toList();

    @TempDir
    Path tempDir;

    private final SplunkClient client = mock(SplunkClient.class);
    private final Map<Instant, CountDownLatch> holds = new ConcurrentHashMap<>();
    private final List<Instant> queried = new CopyOnWriteArrayList<>();
    private final List<JobTicket> tickets = new CopyOnWriteArrayList<>();
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger maxSearching = new AtomicInteger();
    private final AtomicInteger returned = new AtomicInteger();
    private volatile boolean searchDoneBeforeBlocking;
    /** Held queries end at once on an interrupt, leaving their job created (like a real interrupted poll). */
    private volatile boolean interruptible;
    /** Splunk rejects every create (e.g. 503 quota): no job exists. */
    private volatile boolean rejectCreates;
    /** Cancels are confirmed at once instead of being held for the test. */
    private volatile boolean confirmCancels;

    private final Set<JobTicket> cancelled = new java.util.concurrent.CopyOnWriteArraySet<>();

    private final List<SearchProgress> progress = new CopyOnWriteArrayList<>();
    private DatabaseManager database;
    private SplunkLogDataProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        when(client.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            LoadDecision decision = invocation.getArgument(0);
            ProgressCallback callback = invocation.getArgument(1);
            JobTicket ticket = invocation.getArgument(3);
            Instant target = TARGETS.stream().filter(decision::contains).findFirst().orElseThrow();
            queried.add(target);
            tickets.add(ticket);
            String sid = "sid-" + queried.size();
            if (!ticket.register(sid)) {
                throw new SearchCancelledException("cancelled before create", null);
            }
            if (rejectCreates) {
                ticket.rejected(sid);
                throw new SearchException("HTTP 503 -- Search not executed: quota", null);
            }
            ticket.created(sid);
            maxSearching.accumulateAndGet(searchingNow(), Math::max);
            if (searchDoneBeforeBlocking) {
                ticket.done(sid);
            }
            open.incrementAndGet();
            try {
                CountDownLatch hold = holds.get(target);
                if (hold != null) {
                    if (interruptible) {
                        try {
                            hold.await(10, SECONDS);
                        } catch (InterruptedException e) {
                            throw new SearchCancelledException("interrupted while polling", e);
                        }
                    } else {
                        awaitIgnoringInterrupts(hold);
                    }
                }
            } finally {
                open.decrementAndGet();
            }
            ticket.done(sid);
            callback.onProgress(SearchProgress.FAIL);
            return Stream.of(event(target));
        });
        doAnswer(invocation -> {
            JobTicket ticket = invocation.getArgument(0);
            ticket.cancelRequested();
            cancelled.add(ticket);
            if (confirmCancels) {
                confirm(ticket);
            }
            return null;
        }).when(client).cancelJobs(any());

        database = new DatabaseManager("test", "test", "test", tempDir.toFile());
        Clock clock = Clock.fixed(BASE.plus(60, DAYS), ZoneOffset.UTC);
        provider = new SplunkLogDataProvider(new LogEventMapper(), database, returned::incrementAndGet, () -> client,
                () -> "index=test", clock, new SearchBoundaries(clock));
        provider.setLoadProgressCallback(progress::add);
    }

    @AfterEach
    void tearDown() {
        holds.values().forEach(CountDownLatch::countDown);
        cancelled.forEach(this::confirm);
        provider.shutdown();
        database.close();
    }

    /** SIDs of all loads that may be searching on the fake Splunk right now. */
    private int searchingNow() {
        return tickets.stream().mapToInt(ticket -> ticket.searching().size()).sum();
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        // Models a socket read that does not respond to Future.cancel(true)
        while (true) {
            try {
                if (latch.await(10, SECONDS)) {
                    return;
                }
            } catch (InterruptedException ignored) {
                // released explicitly by the test
            }
        }
    }

    private CountDownLatch hold(int target) {
        return holds.computeIfAbsent(TARGETS.get(target), t -> new CountDownLatch(1));
    }

    private void release(int target) {
        hold(target).countDown();
    }

    private void jump(int target) throws Exception {
        provider.jump(TARGETS.get(target));
    }

    private void awaitQueries(int count) {
        await().atMost(3, SECONDS).until(() -> queried.size() >= count);
        assertThat(queried).hasSize(count);
    }

    /** Splunk confirms the cancel: every SID gone. */
    private void confirm(JobTicket ticket) {
        ticket.searching().forEach(ticket::gone);
    }

    private long deliveredQueryProgress() {
        return progress.stream().filter(p -> p == SearchProgress.FAIL).count();
    }

    private void assertShows(int target) throws Exception {
        assertThat(provider.getStatistics().loadedLines()).isEqualTo(1);
        assertThat(provider.getLine(0, false)).isInstanceOfSatisfying(LoadedLogLine.class,
                line -> assertThat(line.getSplunkTime()).isEqualTo(TARGETS.get(target)));
    }

    /** Two stuck requests (0 abandoned, 1 running) and an unconfirmed cancel: the gate is full. */
    private void fillGate() throws Exception {
        hold(0);
        hold(1);
        jump(0);
        awaitQueries(1);
        jump(1);
        awaitQueries(2);
    }

    @Test
    @DisplayName("A superseded job is cancelled by SID without waiting for its stuck worker")
    void supersededJobIsCancelledBySidWithoutWaitingForStuckWorker() throws Exception {
        hold(0);
        jump(0);
        awaitQueries(1);
        JobTicket first = tickets.getFirst();

        jump(1);

        assertThat(cancelled).containsExactly(first);
        assertThat(first.isCancelled()).isTrue();
        assertThat(hold(0).getCount()).as("the stuck worker has not returned").isEqualTo(1);
        awaitQueries(2);
        await().atMost(3, SECONDS).until(() -> returned.get() == 1);
        assertShows(1);
        assertThat(provider.loadStateForTest()).as("the unconfirmed abandoned job keeps its slot")
                .isEqualTo(new LoadState(LoadState.Current.NONE, 1, 1));
    }

    @Test
    @DisplayName("Never more than two loads open; the latest of many jumps wins the pending slot")
    void neverMoreThanTwoLoadJobsOpenAndLatestWins() throws Exception {
        fillGate();
        hold(2); // never released before the end: it must never be queried anyway

        for (int target = 2; target <= 21; target++) {
            jump(target);
            assertThat(provider.loadStateForTest().inFlight()).isLessThanOrEqualTo(SplunkLogDataProvider.MAX_OPEN);
        }

        assertThat(queried).hasSize(2);
        assertThat(maxSearching).hasValue(2);
        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.PENDING, 2, 2));

        // The oldest worker returns with its search done: proven not searching, its slot frees and
        // the pending (latest) job starts
        release(0);

        awaitQueries(3);
        assertThat(queried.get(2)).as("only the last target is sent").isEqualTo(TARGETS.get(21));
        await().atMost(3, SECONDS).until(() -> provider.getStatistics().loadedLines() == 1);
        assertShows(21);

        release(1);
        await().atMost(3, SECONDS).until(() -> provider.loadStateForTest().inFlight() == 0);
        assertShows(21);
        assertThat(maxSearching).hasValue(2);
        assertThat(deliveredQueryProgress()).as("only the published job reports progress").isEqualTo(1);
    }

    @Test
    @DisplayName("With a free slot the replacement loads while the abandoned request is stuck")
    void replacementStartsWhenAbandonedStuckAndSlotFree() throws Exception {
        confirmCancels = true;
        hold(0);
        jump(0);
        awaitQueries(1);

        jump(1);

        await().atMost(3, SECONDS).until(() -> returned.get() == 1);
        assertShows(1);
        assertThat(hold(0).getCount()).as("stale request still blocked").isEqualTo(1);
        assertThat(provider.loadStateForTest()).as("the confirmed cancel freed the stuck job's slot")
                .isEqualTo(new LoadState(LoadState.Current.NONE, 0, 0));

        release(0);
        await().atMost(3, SECONDS).until(() -> returned.get() == 2);
        assertShows(1);
    }

    @Test
    @DisplayName("A pending load is cancellable and never sent")
    void pendingLoadIsCancellableAndNeverSent() throws Exception {
        fillGate();
        jump(2);
        assertThat(provider.loadStateForTest().current()).isEqualTo(LoadState.Current.PENDING);

        assertThat(provider.cancelSearch()).isTrue();

        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.NONE, 2, 2));
        confirm(tickets.get(0));
        confirm(tickets.get(1));
        release(0);
        release(1);
        await().atMost(3, SECONDS).until(() -> provider.loadStateForTest().inFlight() == 0);
        assertThat(provider.getLine(0, false)).isNotInstanceOf(Placeholder.class);
        assertThat(provider.loadStateForTest().current()).isEqualTo(LoadState.Current.NONE);
        assertThat(queried).hasSize(2);
    }

    @ParameterizedTest(name = "release {0} first")
    @ValueSource(ints = {0, 1})
    @DisplayName("Only the current job publishes across gate transitions")
    void onlyCurrentJobPublishesAcrossGateTransitions(int first) throws Exception {
        fillGate();
        jump(2);

        release(first);
        awaitQueries(3);
        await().atMost(3, SECONDS).until(() -> provider.getStatistics().loadedLines() == 1);
        assertShows(2);

        release(1 - first);
        await().atMost(3, SECONDS).until(() -> returned.get() == 3);
        assertShows(2);
        assertThat(deliveredQueryProgress()).isEqualTo(1);
    }

    @Test
    @DisplayName("Shutdown cancels open jobs, drops the pending one and returns promptly")
    void shutdownCancelsOpenJobsDropsPendingAndReturnsPromptly() throws Exception {
        fillGate();
        jump(2);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            cancelled.forEach(this::confirm);
        });

        long start = System.nanoTime();
        provider.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("waits for the cancels, bounded").isBetween(200L, 3_000L);
        assertThat(cancelled).containsExactly(tickets.get(0), tickets.get(1));
        assertThat(List.of(tickets.get(0), tickets.get(1))).as("cancels confirmed before shutdown returned")
                .allSatisfy(ticket -> assertThat(ticket.whenQuiet()).isCompleted());
        release(0);
        release(1);
        assertThat(queried).as("the pending load is never sent").hasSize(2);
        assertThat(provider.loadStateForTest().current()).isEqualTo(LoadState.Current.NONE);
    }

    @Test
    @DisplayName("A jump after shutdown leaves no phantom job")
    void rejectedSubmitAfterShutdownLeavesNoPhantomJob() throws Exception {
        provider.shutdown();

        jump(0);

        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.NONE, 0, 0));
        assertThat(queried).isEmpty();
    }

    @Test
    @DisplayName("The pending load starts when a returning worker proves its search done, cancel unconfirmed")
    void pendingStartsWhenTheReturningWorkerProvesItsSearchDone() throws Exception {
        fillGate();
        jump(2);

        release(0);

        awaitQueries(3);
        assertThat(queried.get(2)).isEqualTo(TARGETS.get(2));
        assertThat(tickets.get(0).whenQuiet()).as("freed by the done search, not by a cancel").isCompleted();
    }

    @Test
    @DisplayName("Interrupted workers that exit at once keep their slots while their cancels stall; latest wins")
    void interruptedWorkersExitingAtOnceDoNotFreeSlotsWhileCancelsStall() throws Exception {
        interruptible = true;
        fillGate();
        for (int target = 2; target <= 10; target++) {
            jump(target);
        }

        await().atMost(3, SECONDS).until(() -> open.get() == 0 && returned.get() == 2);
        assertThat(queried).as("both workers returned, yet nothing else was sent").hasSize(2);
        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.PENDING, 2, 2));
        assertThat(maxSearching).hasValue(2);
        Thread.sleep(200); // negative check: returned workers must not free a slot later either
        assertThat(queried).hasSize(2);

        confirm(tickets.get(0));

        awaitQueries(3);
        assertThat(queried.get(2)).as("only the latest target is sent").isEqualTo(TARGETS.get(10));
        await().atMost(3, SECONDS).until(() -> provider.getStatistics().loadedLines() == 1);
        assertShows(10);
        assertThat(maxSearching).hasValue(2);
    }

    @Test
    @DisplayName("Loads whose create Splunk rejected leave no job and never pin the gate")
    void rejectedCreatesDoNotPinTheGate() throws Exception {
        rejectCreates = true;
        for (int target = 0; target < 6; target++) {
            jump(target);
            int jumps = target + 1;
            await().atMost(3, SECONDS).until(() -> returned.get() == jumps);
        }

        assertThat(queried).hasSize(6);
        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.NONE, 0, 0));
        verify(client, never()).cancelJobs(any());
    }

    @Test
    @DisplayName("A failed or unconfirmed cancel does not release the slot")
    void cancelFailureDoesNotReleaseTheSlot() throws Exception {
        fillGate();
        jump(2);
        jump(3);

        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.PENDING, 2, 2));
        Thread.sleep(200); // negative check: nothing may start later either
        assertThat(queried).hasSize(2);
        assertThat(provider.loadStateForTest().inFlight()).isEqualTo(2);
    }

    @Test
    @DisplayName("A late cancel result and late progress of an old job leave the current job alone")
    void staleProgressAndCancelResultsAreIgnored() throws Exception {
        hold(0);
        jump(0);
        awaitQueries(1);
        jump(1);
        await().atMost(3, SECONDS).until(() -> returned.get() == 1);
        jump(2);
        await().atMost(3, SECONDS).until(() -> returned.get() == 2);
        assertShows(2);

        confirm(tickets.getFirst());
        release(0);
        await().atMost(3, SECONDS).until(() -> returned.get() == 3);

        assertShows(2);
        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.NONE, 0, 0));
        assertThat(deliveredQueryProgress()).as("progress of jobs 2 and 3 only").isEqualTo(2);
    }

    @Test
    @DisplayName("The pending load reports waiting for Splunk; dispatch and cancel clear it in order")
    void pendingLoadReportsWaitingAndDispatchOrCancelClearsIt() throws Exception {
        fillGate();
        progress.clear();

        jump(2);

        assertThat(progress).last().satisfies(p -> {
            assertThat(p.isWaitingForSplunk()).isTrue();
            assertThat(p.start()).isBefore(TARGETS.get(2).plusNanos(1));
        });

        jump(3);
        assertThat(progress).last().satisfies(p -> assertThat(p.end()).isAfter(TARGETS.get(3)));

        assertThat(provider.cancelSearch()).isTrue();
        assertThat(progress).last().satisfies(p -> {
            assertThat(p.isWaitingForSplunk()).isFalse();
            assertThat(p.isDone()).as("cancel clears the load status").isTrue();
        });

        jump(4);
        assertThat(progress).last().satisfies(p -> assertThat(p.isWaitingForSplunk()).isTrue());
        release(0);
        await().atMost(3, SECONDS).until(() -> provider.getStatistics().loadedLines() == 1);
        assertShows(4);
        await().atMost(3, SECONDS).until(() -> progress.getLast().isDone());
        int lastWaiting = lastIndexOf(SearchProgress::isWaitingForSplunk);
        int starting = lastIndexOf(p -> "Starting".equals(p.statusText()));
        assertThat(starting).as("dispatch replaces the waiting status").isGreaterThan(lastWaiting);
        assertThat(progress.indexOf(SearchProgress.FAIL)).as("the job's own progress follows")
                .isGreaterThan(starting);
    }

    private int lastIndexOf(java.util.function.Predicate<SearchProgress> condition) {
        for (int i = progress.size() - 1; i >= 0; i--) {
            if (condition.test(progress.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("Superseding a job whose search is done frees its slot without a cancel")
    void supersedingAJobWhoseSearchIsDoneFreesItsSlotWithoutCancel() throws Exception {
        searchDoneBeforeBlocking = true;
        hold(0);
        jump(0);
        awaitQueries(1);

        jump(1);

        verify(client, never()).cancelJobs(any());
        await().atMost(3, SECONDS).until(() -> returned.get() == 1);
        assertShows(1);
        assertThat(provider.loadStateForTest()).isEqualTo(new LoadState(LoadState.Current.NONE, 0, 0));
    }

    private static Event event(Instant time) {
        return TestEvents.event(Map.of("_time", time.toString(), "_raw", "cached log event", "_bkt", "bucket", "_cd",
                time.toString(), "_indextime", Long.toString(time.getEpochSecond())));
    }
}
