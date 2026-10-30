package se.grynna.pager.splunk;

import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static se.grynna.pager.splunk.SplunkLogDataProviderTest.createEvent;

/**
 * Runs of one {@link SearchSession}: a stopped or discarded query whose Splunk
 * stream is still draining must not add hits to, complete, or fail whatever
 * the session is doing now.
 */
class SearcherTest {

    private static final Instant T = Instant.parse("2017-03-30T10:00:00Z");
    private final LogEventMapper mapper = new LogEventMapper();

    /** A Splunk stream that blocks (ignoring interrupts) until {@code gate} opens. */
    private static final class GatedStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);

        Stream<Event> of(Event... events) {
            return Stream.of(events).peek(e -> {
                entered.countDown();
                awaitUninterruptibly(gate);
            }).onClose(closed::countDown);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitLatch(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(3, SECONDS)).as("latch").isTrue();
    }

    @Test
    void snapshotsPublishOneSideFinishedWhileTheOtherIsStillRunning() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        GatedStream later = new GatedStream();
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            return d.direction() < 0
                    ? Stream.empty()
                    : later.of(createEvent(T.plusSeconds(1), "later", null, "x"));
        });
        SearchSession session = new SearchSession("x", T);
        Searcher searcher = new Searcher(client, mapper, session, "q", T.minusSeconds(60), T.plusSeconds(60));
        try {
            searcher.start();
            awaitLatch(later.entered);
            await().atMost(3, SECONDS).until(() -> session.snapshot().earlierDone());
            assertThat(session.snapshot().laterDone()).isFalse();
            assertThat(session.getState()).isEqualTo(SearchSession.State.RUNNING);
            later.gate.countDown();
            await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
            assertThat(session.snapshot().earlierDone()).isTrue();
            assertThat(session.snapshot().laterDone()).isTrue();
        } finally {
            searcher.cancel();
            later.gate.countDown();
        }
    }

    @Test
    void sideFlagsResetForExtensionsAndIgnoreSupersededWorkers() {
        SearchSession session = new SearchSession("x", T);
        long first = session.beginRun();
        session.configureSides(first, T.minusSeconds(60), T.plusSeconds(60), T);
        session.finishSide(first, SearchSession.Side.LATER);
        assertThat(session.snapshot().laterDone()).isTrue();
        session.cancel(first);
        long extension = session.beginRun();
        session.configureSides(extension, T.plusSeconds(60), T.plusSeconds(120), T.plusSeconds(60));
        assertThat(session.snapshot().earlierDone()).isTrue();
        assertThat(session.snapshot().laterDone()).isFalse();
        var beforeStale = session.snapshot();
        session.finishSide(first, SearchSession.Side.LATER);
        assertThat(session.snapshot()).isSameAs(beforeStale);
        session.cancel(extension);
    }

    @Test
    void stoppedQueryDrainingLateCannotAddHitsToOrFinishTheExtension() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        GatedStream first = new GatedStream();
        GatedStream extension = new GatedStream();
        Event stale = createEvent(T.minusSeconds(30), "cd_stale", null, "stale");
        Event fresh = createEvent(T.plusSeconds(30), "cd_fresh", null, "fresh");
        when(client.queryEventsWithMonitoring(any(), any(), eq("q1"), any())).thenReturn(first.of(stale));
        when(client.queryEventsWithMonitoring(any(), any(), eq("q2"), any())).thenReturn(extension.of(fresh));

        SearchSession session = new SearchSession("boom", T);
        Searcher original = new Searcher(client, mapper, session, "q1", T.minusSeconds(60), T);
        original.start();
        awaitLatch(first.entered);

        original.cancel();
        assertThat(session.getState()).isEqualTo(SearchSession.State.CANCELLED);

        Instant extEnd = T.plusSeconds(120);
        Searcher widened = new Searcher(client, mapper, session, "q2", T, extEnd).coverWindowOnComplete();
        assertThat(session.getState()).isEqualTo(SearchSession.State.RUNNING);
        widened.start();
        awaitLatch(extension.entered);

        // The stopped query's stream finally drains while the extension is running.
        first.gate.countDown();
        awaitLatch(first.closed);
        await().during(200, MILLISECONDS).atMost(2, SECONDS)
                .until(() -> session.getState() == SearchSession.State.RUNNING);
        assertThat(session.hitCount()).as("stale run must not add hits").isZero();
        assertThat(session.windowEnd()).as("stale run must not cover the extension window").isNull();

        extension.gate.countDown();
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        assertThat(session.getHits()).extracting(SearchHit::cd).containsExactly("cd_fresh");
        assertThat(session.windowStart()).isEqualTo(T);
        assertThat(session.windowEnd()).isEqualTo(extEnd);
    }

    @Test
    void discardedSessionIgnoresItsDrainingQueryAndStopsNotifying() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        GatedStream stream = new GatedStream();
        Event late = createEvent(T, "cd_late", null, "late");
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> stream.of(late));

        SearchSession session = new SearchSession("boom", T);
        AtomicInteger notifications = new AtomicInteger();
        session.addUpdateListener(s -> notifications.incrementAndGet());
        Searcher searcher = new Searcher(client, mapper, session, "q", T.minusSeconds(60), T.plusSeconds(60));
        searcher.start();
        awaitLatch(stream.entered);

        session.discard();
        int afterDiscard = notifications.get();
        stream.gate.countDown();
        awaitLatch(stream.closed);

        await().during(200, MILLISECONDS).atMost(Duration.ofSeconds(2))
                .until(() -> session.getState() == SearchSession.State.CANCELLED);
        assertThat(session.hitCount()).isZero();
        assertThat(notifications.get()).as("no updates after discard").isEqualTo(afterDiscard);

        // A discarded session cannot be revived by an extension either.
        new Searcher(client, mapper, session, "q", T, T.plusSeconds(60)).start();
        assertThat(session.getState()).isEqualTo(SearchSession.State.CANCELLED);
    }

    @Test
    void overflowingFirstChunkIsDiscardedAndHalvedDownToOneSecond() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        Event event = TestEvents.event(Map.of());
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any()))
                .thenAnswer(invocation -> Stream.generate(() -> event).limit(4));

        List<LoadedLogLine> mapped = new ArrayList<>();
        LogEventMapper cappingMapper = mock(LogEventMapper.class);
        when(cappingMapper.mapEventSingleLine(any())).thenAnswer(invocation -> {
            int i = mapped.size();
            Instant time = T.plusMillis(i);
            LoadedLogLine line = new LoadedLogLine(LoadedLogLine.MsgType.RAW, "seg", 0, "cd_" + i, "bkt", time, "raw",
                    time, time, "m" + i, false, Level.INFO);
            mapped.add(line);
            return line;
        });

        SearchSession session = new SearchSession("boom", T);
        CopyOnWriteArrayList<SearchSession.State> truncatedWhile = new CopyOnWriteArrayList<>();
        session.addUpdateListener(s -> {
            if (s.isTruncated(SearchSession.Side.EARLIER)) {
                truncatedWhile.add(s.getState());
            }
        });

        new Searcher(client, cappingMapper, session, "q", T.minusSeconds(60), T, T, 3).start();

        await().atMost(30, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED
                && truncatedWhile.contains(SearchSession.State.COMPLETED));

        assertThat(session.hitCount()).isZero();
        assertThat(session.isTruncated(SearchSession.Side.EARLIER)).isTrue();
        assertThat(session.isTruncated(SearchSession.Side.LATER)).isFalse();
        assertThat(session.snapshot().truncatedEarlier()).isTrue();
        assertThat(session.snapshot().lines()).isEmpty();
        assertThat(session.snapshot().tooManyMatchesWithinSecond()).isTrue();
        assertThat(session.windowStart()).isNull();
        assertThat(session.windowEnd()).isNull();
        assertThat(mapped).hasSize(7 * 3); // 60, 30, 15, 7.5, 3.75, 1.875, 1 seconds
        assertThat(truncatedWhile).startsWith(SearchSession.State.RUNNING);
        assertThat(truncatedWhile).endsWith(SearchSession.State.COMPLETED);
    }

    @Test
    void bothSidesRunConcurrentlyAndStopKeepsOnlyCompletedChunks() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        CountDownLatch firstStarted = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(2);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger earlier = new AtomicInteger();
        AtomicInteger later = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(4);
        List<JobTicket> firstTickets = new CopyOnWriteArrayList<>();
        List<JobTicket> secondTickets = new CopyOnWriteArrayList<>();
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            int count = (d.direction() < 0 ? earlier : later).incrementAndGet();
            (count == 1 ? firstTickets : secondTickets).add(invocation.getArgument(3));
            (count == 1 ? firstStarted : secondStarted).countDown();
            if (count > 1) awaitUninterruptibly(releaseSecond); // still searching on Splunk
            return Stream.of(createEvent(d.start().plusNanos(1), "cd_" + d.start(), null, "match"))
                    .peek(e -> awaitUninterruptibly(releaseFirst)).onClose(closed::countDown);
        });
        SearchSession session = new SearchSession("boom", T);
        Searcher searcher = new Searcher(client, mapper, session, "q", T.minusSeconds(1000), T.plusSeconds(1000), T,
                10);
        try {
            searcher.start();
            awaitLatch(firstStarted); // Neither first chunk can finish before this gate opens.
            assertThat(session.hitCount()).isZero();
            assertThat(session.windowStart()).isNull();
            releaseFirst.countDown();
            awaitLatch(secondStarted);
            assertThat(session.hitCount()).isEqualTo(2);
            assertThat(session.windowStart()).isEqualTo(T.minusSeconds(60));
            assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(60));
            searcher.cancel();
            // The searching chunks' Splunk jobs are cancelled by SID; the completed ones are not
            assertThat(secondTickets).hasSize(2)
                    .allSatisfy(ticket -> verify(client, atLeastOnce()).cancelJobs(ticket));
            firstTickets.forEach(ticket -> verify(client, never()).cancelJobs(ticket));
            var stopped = session.snapshot();
            releaseSecond.countDown();
            awaitLatch(closed);
            assertThat(session.snapshot()).isSameAs(stopped);
            assertThat(session.getState()).isEqualTo(SearchSession.State.CANCELLED);
        } finally {
            searcher.cancel();
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
    }

    @Test
    void nonFirstOverflowHalvesRetriesTheChunkAndOtherSideUsesLeftoverBudget() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        SearchSession session = new SearchSession("boom", T);
        CountDownLatch earlierTruncated = new CountDownLatch(1);
        session.addUpdateListener(s -> {
            if (s.isTruncated(SearchSession.Side.EARLIER)) earlierTruncated.countDown();
        });
        AtomicInteger earlierCalls = new AtomicInteger();
        AtomicInteger laterCalls = new AtomicInteger();
        List<SearchSession.Snapshot> published = new CopyOnWriteArrayList<>();
        session.addUpdateListener(s -> published.add(s.snapshot()));
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            int count;
            if (d.direction() < 0) {
                count = switch (earlierCalls.incrementAndGet()) {
                    case 1 -> 1;
                    case 2 -> 6;
                    default -> 2;
                };
            } else {
                awaitLatch(earlierTruncated);
                laterCalls.incrementAndGet();
                count = 2;
            }
            assertThat(d.maxEvents()).isPresent();
            return java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> createEvent(d.start().plusNanos(i), "cd_" + d.start() + "_" + i, null, "m"));
        });
        new Searcher(client, mapper, session, "q", T.minusSeconds(1000), T.plusSeconds(1000), T, 5).start();
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        assertThat(session.hitCount()).isEqualTo(5);
        // [T-60, T) completed, then the second chunk overflowed and its 30 s retry fit:
        // the covered window ends at the retry's far edge, not at the overflowed chunk's start.
        assertThat(session.windowStart()).isEqualTo(T.minusSeconds(90));
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(60));
        assertThat(session.isTruncated(SearchSession.Side.EARLIER)).isTrue();
        assertThat(session.snapshot().tooManyMatchesWithinSecond()).isFalse();
        assertThat(earlierCalls).hasValue(3);
        assertThat(laterCalls).hasValue(1);
        assertThat(published).allSatisfy(s -> {
            assertThat(s.size()).isLessThanOrEqualTo(5);
            assertThat(s.hits()).allSatisfy(hit -> {
                assertThat(hit.splunkTime()).isAfterOrEqualTo(s.windowStart());
                assertThat(hit.splunkTime()).isBefore(s.windowEnd());
            });
        });
    }

    @Test
    void nonFirstOverflowCommitsTheHalvedRetryAndTruncatesAtItsFarEdge() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        List<LoadDecision> decisions = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            decisions.add(d);
            int count = switch (calls.incrementAndGet()) {
                case 1 -> 1;
                case 2 -> 50;
                default -> 2;
            };
            return java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> createEvent(d.start().plusNanos(i), "cd_" + d.start() + "_" + i, null, "m"));
        });
        SearchSession session = new SearchSession("boom", T);
        new Searcher(client, mapper, session, "q", T, T.plusSeconds(1000), T, 10).start();
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        // [T, T+60) fits, [T+60, T+120) overflows, its 30 s retry [T+60, T+90) fits.
        assertThat(decisions).extracting(LoadDecision::start).containsExactly(T, T.plusSeconds(60),
                T.plusSeconds(60));
        assertThat(decisions).extracting(LoadDecision::end).containsExactly(T.plusSeconds(60),
                T.plusSeconds(120), T.plusSeconds(90));
        assertThat(session.hitCount()).isEqualTo(3);
        assertThat(session.windowStart()).isEqualTo(T);
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(90));
        assertThat(session.isTruncated(SearchSession.Side.LATER)).isTrue();
        assertThat(session.isTruncated(SearchSession.Side.EARLIER)).isFalse();
        assertThat(session.snapshot().tooManyMatchesWithinSecond()).isFalse();
        assertThat(session.getHits()).extracting(SearchHit::cd).containsExactly("cd_" + T + "_0",
                "cd_" + T.plusSeconds(60) + "_0", "cd_" + T.plusSeconds(60) + "_1");
    }

    @Test
    void concurrentChunksCannotBothSpendTheSameRemainingBudget() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            bothStarted.countDown();
            return java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> createEvent(d.start().plusNanos(i), "cd_" + d.start() + "_" + i, null, "m"))
                    .peek(e -> awaitUninterruptibly(release));
        });
        SearchSession session = new SearchSession("boom", T);
        List<Integer> counts = new CopyOnWriteArrayList<>();
        session.addUpdateListener(s -> counts.add(s.hitCount()));
        Searcher searcher = new Searcher(client, mapper, session, "q", T.minusSeconds(60), T.plusSeconds(60), T, 3);
        try {
            searcher.start();
            awaitLatch(bothStarted);
            release.countDown();
            await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
            assertThat(session.hitCount()).isEqualTo(2);
            assertThat(counts).allMatch(n -> n <= 3);
            // Exactly one side spends the budget and reaches its window edge; the other
            // halves its overflowing chunk and ends truncated without overspending.
            assertThat(session.snapshot().truncatedEarlier() ^ session.snapshot().truncatedLater()).isTrue();
        } finally {
            release.countDown();
            searcher.cancel();
        }
    }

    @Test
    void clientTooBigExceptionTriggersHalvingAndEndsTheSideTruncated() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new se.grynna.pager.exceptions.SearchResultToBigException("too big", 4, null, null);
            }
            return Stream.empty();
        });
        SearchSession session = new SearchSession("boom", T);
        new Searcher(client, mapper, session, "q", T, T.plusSeconds(60), T, 3).start();
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        assertThat(calls).hasValue(2);
        assertThat(session.windowStart()).isEqualTo(T);
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(30));
        assertThat(session.isTruncated(SearchSession.Side.LATER)).isTrue();
    }

    @Test
    void extensionCountsHitsFromPreviousRunAgainstTheSameCap() throws Exception {
        SplunkClient client = mock(SplunkClient.class);
        SearchSession session = new SearchSession("boom", T);
        session.addHit(new SearchHit(T, T, "bkt", "old"));
        session.coverWindow(T, T.plusSeconds(60));
        when(client.queryEventsWithMonitoring(any(), any(), eq("q"), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            assertThat(d.maxEvents()).hasValue(2);
            return Stream.of(createEvent(d.start(), "new", null, "m"));
        });
        new Searcher(client, mapper, session, "q", T.plusSeconds(60), T.plusSeconds(120), T.plusSeconds(60), 2)
                .start();
        await().atMost(3, SECONDS).until(() -> session.getState() == SearchSession.State.COMPLETED);
        assertThat(session.hitCount()).isEqualTo(2);
        assertThat(session.windowStart()).isEqualTo(T);
        assertThat(session.windowEnd()).isEqualTo(T.plusSeconds(120));
    }
}
