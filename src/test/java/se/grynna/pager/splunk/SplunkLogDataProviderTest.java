package se.grynna.pager.splunk;

import com.splunk.Event;
import com.splunk.Job;
import com.splunk.TestEvents;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.testutils.MutableClock;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Slf4j
class SplunkLogDataProviderTest {

    @Mock
    SplunkClient splunkClient;

    @Mock
    Job job;

    @Spy
    private static DatabaseManager databaseManager = createDb();

    LogEventMapper EVENT_MAPPER = new LogEventMapper();

    Clock testClock;

    SplunkLogDataProvider dataProvider;

    SearchBoundaries searchBoundaries;

    @SneakyThrows
    private static DatabaseManager createDb() {
        // Create a proper temporary directory
        var tempDir = Files.createTempDirectory("splunkTest");

        // Add shutdown hook to ensure cleanup even if test fails
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                deleteDirectoryRecursively(tempDir);
            } catch (Exception e) {
                System.err.println("Error during shutdown cleanup: " + e.getMessage());
            }
        }));

        return new DatabaseManager("test", "test", "test", tempDir.toFile());
    }

    @BeforeAll
    static void initDateTimeFormatterManager() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @BeforeEach
    void setUp() throws IOException {
        testClock = new MutableClock(Instant.parse("2023-05-13T14:00:00Z"), ZoneId.of("Atlantic/Azores"));
        searchBoundaries = new SearchBoundaries(testClock);
        dataProvider = new SplunkLogDataProvider(EVENT_MAPPER, databaseManager, () -> {}, () -> splunkClient,
                () -> "query", testClock, searchBoundaries);
        Instant startOfDay = testClock.instant().truncatedTo(ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        dataProvider.setBeforeBoundary(startOfDay);
        dataProvider.setAfterBoundary(endOfDay);
    }

    @AfterAll
    static void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private static void deleteDirectoryRecursively(Path path) {
        try {
            if (Files.exists(path)) {
                try (Stream<Path> paths = Files.walk(path)) {
                    paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to delete temp directory: " + e.getMessage());
        }
    }

    @Test
    void ThrowsOnScrollToUninitializedSegment() throws TemporarySearchException, SearchException, StorageException {
        Assertions.assertThatException().isThrownBy(() -> dataProvider.getLine(0, false));
    }

    @Test
    void getFirstLineReturnsPlaceholderBeforeDataLoaded() throws Exception {
        // Mock the splunk client to delay response
        Instant start = testClock.instant().minus(10, MINUTES);
        Instant end = start.plusSeconds(30);

        var events = new ArrayList<Event>();
        events.addAll(lines(start, end));

        Semaphore semaphore = new Semaphore(0);

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            // Simulate delay in response
            System.out.println("*** Waiting to acquire semaphore to return results");
            semaphore.acquire();
            System.out.println("*** Acquired semaphore, returning results");
            return events.stream();
        });

        // Jump to a timestamp to trigger loading
        System.out.print("*** Jumping to start time: " + start);
        dataProvider.jump(start);

        // Immediately check that getLine(0) returns a Placeholder while loading
        System.out.println("*** Checking line 0 for Placeholder");
        System.out.flush();
        var line = getDataProviderLine(0);
        System.out.println("*** Retrieved line 0: " + line.getClass().getName());
        System.out.flush();
        assertThat(line).isInstanceOf(Placeholder.class);
        semaphore.release();

        // Wait for data to be loaded
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
    }

    @Test
    void peekLineReturnsOnlyLoadedLinesAndNeverLoads() throws Exception {
        assertThat(dataProvider.peekLine(0)).isEmpty();
        verifyNoInteractions(splunkClient);

        Instant start = testClock.instant().minus(10, MINUTES);
        var events = new ArrayList<Event>();
        events.addAll(lines(start, start.plusSeconds(30)));
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any()))
                .thenAnswer(invocation -> events.stream());

        dataProvider.jump(start);
        await().atMost(3, SECONDS)
                .untilAsserted(
                        () -> assertThat(dataProvider.peekLine(0).orElseThrow()).isInstanceOf(LoadedLogLine.class));

        int invocationsBefore = Mockito.mockingDetails(splunkClient).getInvocations().size();
        assertThat(dataProvider.peekLine(1_000_000)).isEmpty();
        assertThat(dataProvider.peekLine(-1)).isEmpty();
        assertThat(Mockito.mockingDetails(splunkClient).getInvocations()).hasSize(invocationsBefore);
    }

    private LogLine getDataProviderLine(int lineNumber)
            throws TemporarySearchException, SearchException, StorageException {
        return dataProvider.getLine(lineNumber, true);
    }

    @Test
    void preloadEarlierFillsRowsBeforeViewportReachesThem() throws Exception {
        Instant target = testClock.instant().minus(10, MINUTES);
        Event atTarget = createEvent(target, "cd_target", null, "target");
        Event earlier = createEvent(target.minusSeconds(40), "cd_earlier", null, "earlier");
        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            queries.incrementAndGet();
            LoadDecision decision = invocation.getArgument(0);
            return Stream.of(atTarget, earlier).filter(event -> {
                Instant time = Instant.parse(event.get("_time"));
                return !time.isBefore(decision.start()) && time.isBefore(decision.end());
            });
        });

        dataProvider.jump(target);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
        assertThat(queries.get()).isEqualTo(1);

        dataProvider.preload(0, 0);
        await().atMost(3, SECONDS).untilAsserted(() -> {
            assertThat(queries.get()).isEqualTo(2);
            assertThat(getDataProviderLine(-1)).isInstanceOf(LoadedLogLine.class);
        });
        assertThat(((LoadedLogLine) getDataProviderLine(-1)).getTime()).isEqualTo(target.minusSeconds(40));
    }

    @Test
    @DisplayName("Splunk floors a sub-second earliest to the whole second; such events belong to the adjacent window")
    void preloadDropsEventsBelowSubSecondWindowStart() throws Exception {
        Instant target = testClock.instant().minus(10, MINUTES);
        Event atTarget = createEvent(target, "cd_target", null, "target");
        AtomicInteger queries = new AtomicInteger();
        List<Instant> preloadStarts = new ArrayList<>();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            LoadDecision decision = invocation.getArgument(0);
            if (queries.incrementAndGet() == 1) {
                return Stream.of(atTarget).filter(event -> {
                    Instant time = Instant.parse(event.get("_time"));
                    return !time.isBefore(decision.start()) && time.isBefore(decision.end());
                });
            }
            preloadStarts.add(decision.start());
            // Real Splunk ignores the fraction of earliest_time: an event just before
            // decision.start() (here 435 ms) comes back with the window's events.
            return Stream.of(createEvent(decision.start().minusMillis(435), "cd_outside", null, "outside"),
                    createEvent(decision.start(), "cd_edge", null, "edge"),
                    createEvent(decision.start().plusMillis(1), "cd_inside", null, "inside"));
        });

        dataProvider.jump(target);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        dataProvider.preload(0, 0);
        await().atMost(3, SECONDS).untilAsserted(() -> assertThat(queries.get()).isEqualTo(2));
        // No exception may surface once the load has finished, and the window edge
        // must not move the min boundary.
        await().atMost(3, SECONDS).untilAsserted(() -> {
            assertThat(getDataProviderLine(-1)).isInstanceOf(LoadedLogLine.class);
            assertThat(getDataProviderLine(-2)).isInstanceOf(LoadedLogLine.class);
        });
        var messages = new ArrayList<String>();
        for (int i = -2; i <= 0; i++) {
            messages.add(((LoadedLogLine) getDataProviderLine(i)).getCb());
        }
        assertThat(messages).containsExactly("cd_edge", "cd_inside", "cd_target");
        assertThat(dataProvider.getBeforeBoundary().instant()).isEqualTo(
                dataProvider.getBeforeBoundary().instant().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("A load that violates the window leaves the segment and the min boundary untouched")
    void failedLoadDoesNotExtendSegmentOrMoveBoundary() throws Exception {
        Instant target = testClock.instant().minus(10, MINUTES);
        Event atTarget = createEvent(target, "cd_target", null, "target");
        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (queries.incrementAndGet() == 1) {
                return Stream.of(atTarget);
            }
            throw new SearchException("boom", new RuntimeException());
        });

        dataProvider.jump(target);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        dataProvider.preload(0, 0);
        await().atMost(3, SECONDS).untilAsserted(() -> assertThat(queries.get()).isEqualTo(2));
        // The failure is reported once ...
        await().atMost(3, SECONDS).untilAsserted(() -> {
            try {
                getDataProviderLine(0);
            } catch (Exception e) {
                assertThat(e).isInstanceOf(SearchException.class);
                return;
            }
            Assertions.fail("expected the failed load to be reported");
        });
        // ... and afterwards getLine keeps working: the segment simply stops here.
        assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class);
        assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class);
        assertThat(dataProvider.getBeforeBoundary().instant()).isEqualTo(target.minusSeconds(30));
    }

    @Test
    void assertScrollToTimestampInitialised()
            throws TemporarySearchException, SearchException, StorageException, SearchResultToBigException {

        // Mock a small set of loaded log lines
        Instant start = testClock.instant().minus(10, MINUTES);
        Instant end = start.plusSeconds(30);

        var events = new ArrayList<Event>();
        events.addAll(lines(start, end));

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            return events.stream();
        });

        dataProvider.jump(start);

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(1)).isInstanceOf(LoadedLogLine.class));

        // validate that the lines are loaded
        for (int i = 0; i < events.size(); i++) {
            var line = (LoadedLogLine) getDataProviderLine(i);
            var event = events.get(i);
            assertThat(line.getTime()).as("Line %s has wrong timestamp", i)
                    .isEqualTo(Instant.parse(event.get("_time")));

            assertThat(line.getRaw()).as("Line %s has wrong raw", i).isEqualTo(event.get("_raw"));
        }
    }

    @Test
    void searchHitUsesSplunkTimeWhenSourceTimestampDiffers() throws Exception {
        Instant splunkTime = testClock.instant().minus(10, MINUTES);
        Instant sourceTime = splunkTime.minus(7, MINUTES).plusNanos(123_456);
        Event event = createEvent(splunkTime, sourceTime, "cd_skewed", null, "matched");

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            return !splunkTime.isBefore(d.start()) && splunkTime.isBefore(d.end()) ? Stream.of(event) : Stream.empty();
        });

        SearchSession session = new SearchSession("matched", splunkTime);
        Searcher searcher = new Searcher(splunkClient, EVENT_MAPPER, session, "query", splunkTime.minusSeconds(1),
                splunkTime.plusSeconds(1));
        searcher.start();

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(session.getState()).isEqualTo(SearchSession.State.COMPLETED));
        assertThat(session.getHits()).extracting(SearchHit::splunkTime).containsExactly(splunkTime);

        LoadedLogLine mapped = EVENT_MAPPER.mapEventSingleLine(event);
        assertThat(mapped.getDisplayTime()).isEqualTo(sourceTime);
        assertThat(mapped.getSplunkTime()).isEqualTo(splunkTime);
    }

    @Test
    void jumpWithinLoadedSegmentPreservesNanosecondPrecision() throws Exception {
        Instant base = testClock.instant().minus(10, MINUTES);
        List<Event> events = List.of(createEvent(base.plusNanos(100), "cd100", null, "100ns"),
                createEvent(base.plusNanos(200), "cd200", null, "200ns"),
                createEvent(base.plusNanos(300), "cd300", null, "300ns"));

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any()))
                .thenAnswer(invocation -> events.stream());

        dataProvider.jump(base.plusNanos(200));
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        assertThat(dataProvider.jump(base.plusNanos(260))).isEqualTo(1);
        assertThat(((LoadedLogLine) getDataProviderLine(1)).getSplunkTime()).isEqualTo(base.plusNanos(300));
    }

    @Test
    void searchKeepsDistinctHitsForEqualSplunkTime() throws Exception {
        Instant t = testClock.instant().minus(10, MINUTES);
        Event eventA = createEvent(t, t.plusNanos(100), "cdA", null, "A");
        Event eventB = createEvent(t, t.plusNanos(200), "cdB", null, "B");

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            LoadDecision d = invocation.getArgument(0);
            return !t.isBefore(d.start()) && t.isBefore(d.end()) ? Stream.of(eventB, eventA) : Stream.empty();
        });

        SearchSession session = new SearchSession("equal-time", t);
        Searcher searcher = new Searcher(splunkClient, EVENT_MAPPER, session, "query", t.minusSeconds(1),
                t.plusSeconds(1));
        searcher.start();

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(session.getState()).isEqualTo(SearchSession.State.COMPLETED));

        assertThat(session.getHits()).extracting(SearchHit::cd).containsExactly("cdA", "cdB");
    }

    @Test
    void jumpBySearchHitSelectsExactEventWhenSplunkTimesAreEqual() throws Exception {
        Instant t = testClock.instant().minus(10, MINUTES);
        Event eventA = createEvent(t, t.plusNanos(100), "cdA", null, "A");
        Event eventB = createEvent(t, t.plusNanos(200), "cdB", null, "B");

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any()))
                .thenAnswer(invocation -> Stream.of(eventA, eventB));

        SearchHit target = new SearchHit(t, t.plusNanos(200), "bkt_cdB", "cdB");
        dataProvider.jump(target);

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        LoadedLogLine line0 = (LoadedLogLine) getDataProviderLine(0);
        assertThat(line0.getCb()).isEqualTo("cdB");
        assertThat(line0.getSplunkTime()).isEqualTo(t);
        assertThat(line0.getDisplayTime()).isEqualTo(t.plusNanos(200));
    }

    @Test
    void jumpBySearchHitWithinLoadedSegmentUsesEventStartRowForMultilineEvent() throws Exception {
        Instant t = testClock.instant().minus(10, MINUTES);
        Event target = createEvent(t, "cdMulti", "traceLine", "firstLine");
        Event later = createEvent(t.plusSeconds(1), "cdLater", null, "laterLine");

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any()))
                .thenAnswer(invocation -> Stream.of(target, later));

        dataProvider.jump(t);

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(1)).isInstanceOf(LoadedLogLine.class));

        SearchHit hit = new SearchHit(t, t, "bkt_cdMulti", "cdMulti");
        int line = dataProvider.jump(hit);

        assertThat(line).isEqualTo(0);
        assertThat(((LoadedLogLine) getDataProviderLine(line)).isContinuation()).isFalse();
    }

    @Test
    @DisplayName("Initial too-big query is retried and the event closest to the jump target gets row zero")
    void initialTooBigQueryRetriesWithPreservedTarget() throws Exception {
        Instant target = testClock.instant().truncatedTo(MINUTES).minus(10, MINUTES);
        Instant earlier = target.minus(20, ChronoUnit.SECONDS);
        Instant requested = target.minus(5, ChronoUnit.SECONDS);

        Event earlierEvent = createEvent(earlier, "cd_earlier", null, "earlier");
        Event requestedEvent = createEvent(requested, "cd_requested", null, "requested");
        List<Event> available = List.of(earlierEvent, requestedEvent);

        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            var decision = invocation.getArgument(0, LoadDecision.class);
            if (queries.getAndIncrement() == 0) {
                // Simulate Splunk truncating the initial 60-second query.
                throw new SearchResultToBigException("Mocked: result too big", 3000,
                        target.minus(29, ChronoUnit.SECONDS), target.plus(29, ChronoUnit.SECONDS));
            }
            return available.stream().filter(event -> {
                Instant eventTime = Instant.parse(event.get("_time"));
                return !eventTime.isBefore(decision.start()) && eventTime.isBefore(decision.end());
            });
        });

        dataProvider.jump(target);

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        LoadedLogLine line0 = (LoadedLogLine) getDataProviderLine(0);
        assertThat(line0.getTime()).as("The event closest to the jump target must be assigned row zero")
                .isEqualTo(requested);
    }

    @Test
    @DisplayName("Jumping to a hit whose initial window is truncated puts the hit on row 0")
    void truncatedInitialLoadKeepsTargetOnRowZero() throws Exception {
        // The retry after a truncated [T-30s, T+30s) only queries [T-x, T): the
        // end is exclusive, so the hit at T cannot be in it and row 0 becomes the
        // event before T. LogSegment.preserveInitialTarget exists to prevent this.
        Instant target = testClock.instant().truncatedTo(MINUTES).minus(10, MINUTES);
        Event before = createEvent(target.minusSeconds(1), "1:1", null, "before target");
        Event hit = createEvent(target, "1:2", null, "target");

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenThrow(
                new SearchResultToBigException("truncated", 3500, target.minusSeconds(10), target.plusSeconds(10)))
                .thenAnswer(inv -> {
                    LoadDecision d = inv.getArgument(0);
                    return Stream.of(before, hit).filter(e -> {
                        Instant t = Instant.parse(e.get("_time"));
                        return !t.isBefore(d.start()) && t.isBefore(d.end());
                    });
                });

        dataProvider.jump(new SearchHit(target, target, "bkt1", "1:2"));

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        assertThat(((LoadedLogLine) getDataProviderLine(0)).getMessage()).isEqualTo("target");
    }

    @Test
    @DisplayName("A load superseded by jumping elsewhere must not affect the active segment")
    void jumpSupersedesInFlightLoad() throws Exception {
        Instant target1 = testClock.instant().truncatedTo(MINUTES).minus(20, MINUTES);
        Instant target2 = testClock.instant().truncatedTo(MINUTES).minus(15, MINUTES);

        List<Event> events1 = lines(target1.minusSeconds(5), target1.plusSeconds(5));
        List<Event> events2 = lines(target2.minusSeconds(5), target2.plusSeconds(5));

        Semaphore firstQueryStarted = new Semaphore(0);
        Semaphore releaseFirstQuery = new Semaphore(0);
        AtomicInteger queries = new AtomicInteger();

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (queries.getAndIncrement() == 0) {
                firstQueryStarted.release();
                // Block uninterruptibly: the jump must supersede this load, and its
                // late result must be discarded rather than interrupt-killed mid-test.
                releaseFirstQuery.acquireUninterruptibly();
                return events1.stream();
            }
            var decision = invocation.getArgument(0, LoadDecision.class);
            return events2.stream().filter(event -> {
                Instant eventTime = Instant.parse(event.get("_time"));
                return !eventTime.isBefore(decision.start()) && eventTime.isBefore(decision.end());
            });
        });

        dataProvider.jump(target1);
        assertThat(firstQueryStarted.tryAcquire(3, SECONDS)).as("first load is blocked inside its Splunk query")
                .isTrue();

        // Jump elsewhere while the first load is still in flight.
        dataProvider.jump(target2);

        // The stale result arrives only now.
        releaseFirstQuery.release();

        await().atMost(10, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        // Only the second jump's events may populate the segment, with the event at
        // the requested timestamp on row zero.
        LoadedLogLine line0 = (LoadedLogLine) getDataProviderLine(0);
        assertThat(line0.getTime()).isEqualTo(target2);
        for (int i = -20; i <= 20; i++) {
            LogLine line = getDataProviderLine(i);
            assertThat(line).as("line %d", i).isInstanceOf(LoadedLogLine.class);
            assertThat(((LoadedLogLine) line).getTime()).as("line %d must come from the second jump target's window", i)
                    .isBetween(target2.minusSeconds(5), target2.plusSeconds(5));
        }

        // The stale load must never have written to the database.
        verify(databaseManager, atLeastOnce()).insertLogsBatch(eq(new LogSegment(target2).getId()), any());
        verify(databaseManager, never()).insertLogsBatch(eq(new LogSegment(target1).getId()), any());
    }

    @Test
    @DisplayName("A scope change supersedes the in-flight load and the same position reloads under the new query")
    void discardLoadedLogSupersedesLoadAndSamePositionReloads() throws Exception {
        Instant target = testClock.instant().truncatedTo(MINUTES).minus(20, MINUTES);
        String segmentId = new LogSegment(target).getId();

        Semaphore firstQueryStarted = new Semaphore(0);
        Semaphore releaseFirstQuery = new Semaphore(0);
        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            boolean first = queries.getAndIncrement() == 0;
            String message = first ? "old-scope" : "new-scope";
            if (first) {
                firstQueryStarted.release();
                releaseFirstQuery.acquireUninterruptibly();
            }
            var decision = invocation.getArgument(0, LoadDecision.class);
            List<Event> events = new ArrayList<>();
            int idx = 0;
            for (Instant i = target.minusSeconds(5); i.isBefore(target.plusSeconds(5)); i = i.plusMillis(100)) {
                events.add(createEvent(i, "cd" + i, null, message + idx++));
            }
            return events.stream().filter(event -> {
                Instant eventTime = Instant.parse(event.get("_time"));
                return !eventTime.isBefore(decision.start()) && eventTime.isBefore(decision.end());
            });
        });

        dataProvider.jump(target);
        assertThat(firstQueryStarted.tryAcquire(3, SECONDS)).isTrue();

        // Scope change while the first load is in flight
        dataProvider.discardLoadedLog();
        verify(databaseManager, atLeastOnce()).clearSegmentCache();
        Assertions.assertThatThrownBy(() -> dataProvider.getLine(0, false)).isInstanceOf(IllegalStateException.class);

        // The stale result arrives late and must be dropped
        releaseFirstQuery.release();
        Thread.sleep(200);
        verify(databaseManager, never()).insertLogsBatch(eq(segmentId), any());

        // The same position loads again, from a new query, into a clean segment
        dataProvider.jump(target);
        await().atMost(10, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
        assertThat(queries.get()).isGreaterThanOrEqualTo(2);
        assertThat(((LoadedLogLine) getDataProviderLine(0)).getTime()).isEqualTo(target);
        for (int i = -10; i <= 10; i++) {
            assertThat(((LoadedLogLine) getDataProviderLine(i)).getMessage()).as("line %d", i)
                    .startsWith("new-scope");
        }
    }

    @Test
    void explicitRangeReplacesEvenALoadedSegmentAndStartsAtItsFirstEvent() throws Exception {
        Instant from = testClock.instant().minus(20, MINUTES).plusNanos(123);
        Instant to = from.plusSeconds(20);
        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            queries.incrementAndGet();
            LoadDecision decision = invocation.getArgument(0);
            return lines(from.minusSeconds(5), from.plusSeconds(10)).stream()
                    .filter(event -> {
                        Instant time = Instant.parse(event.get("_time").toString());
                        return !time.isBefore(decision.start()) && time.isBefore(decision.end());
                    });
        });
        dataProvider.jump(from);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(dataProvider.peekLine(0)).isPresent());

        dataProvider.jump(from, from, to);

        assertThat(dataProvider.getBeforeBoundary().instant()).isEqualTo(from);
        assertThat(dataProvider.getAfterBoundary().instant()).isEqualTo(to);
        await().atMost(3, SECONDS).untilAsserted(() -> {
            assertThat(queries.get()).isEqualTo(2);
            LoadedLogLine first = (LoadedLogLine) dataProvider.peekLine(0).orElseThrow();
            assertThat(first.getSplunkTime()).isEqualTo(from);
        });
    }

    @Test
    void explicitRangeClampsTheStartAndNullEndKeepsFollowingNow() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        dataProvider.setLogUpdatedCallback(loaded::countDown);
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any()))
                .thenAnswer(invocation -> Stream.empty());
        dataProvider.jump(Instant.EPOCH, Instant.EPOCH, null);

        assertThat(dataProvider.getBeforeBoundary().instant()).isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY);
        assertThat(dataProvider.getAfterBoundary().instant()).isEqualTo(Instant.MAX);
        Instant now = searchBoundaries.getEffectiveMaxBoundary();
        ((MutableClock) testClock).fastForward(java.time.Duration.ofMinutes(10));
        assertThat(searchBoundaries.getEffectiveMaxBoundary()).isEqualTo(now.plusSeconds(600));
        assertThat(loaded.await(3, SECONDS)).isTrue();
    }

    @Test
    void invalidExplicitRangeChangesNothing() {
        Instant before = searchBoundaries.getMinBoundary();
        Instant after = searchBoundaries.getMaxBoundary();
        Instant now = searchBoundaries.getNowBoundary();
        for (Instant end : List.of(now.minusSeconds(1), now, now.plusSeconds(1))) {
            Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> dataProvider.jump(now, now, end));
        }
        Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> dataProvider.jump(now, now, null));
        Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> dataProvider.jump(Instant.EPOCH, Instant.EPOCH,
                        SearchBoundaries.MIN_MINIMUM_BOUNDARY));
        assertThat(searchBoundaries.getMinBoundary()).isEqualTo(before);
        assertThat(searchBoundaries.getMaxBoundary()).isEqualTo(after);
        verifyNoInteractions(splunkClient);
    }

    @Test
    @DisplayName("A cross-segment jump resets the boundaries to time-only until the new segment loads")
    void jumpOutsideSegmentClearsBoundaryLineNumbers() throws Exception {
        Instant target1 = testClock.instant().truncatedTo(MINUTES).minus(20, MINUTES);
        Instant target2 = target1.plus(10, MINUTES);

        List<Event> events1 = lines(target1.minusSeconds(5), target1.plusSeconds(5));
        List<Event> events2 = lines(target2.minusSeconds(5), target2.plusSeconds(5));

        Semaphore holdSecondQuery = new Semaphore(0);
        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (queries.getAndIncrement() == 0) {
                return events1.stream();
            }
            // Hold the second fetch so the in-flight window can be observed.
            holdSecondQuery.acquireUninterruptibly();
            return events2.stream();
        });

        // Segment 1 loaded: boundaries carry its line numbers.
        dataProvider.jump(target1);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
        assertThat(dataProvider.getBeforeBoundary().line()).isPresent();
        assertThat(dataProvider.getAfterBoundary().line()).isPresent();

        // Jump outside the loaded interval. While the replacement load is in flight,
        // the boundaries must not carry the old segment's line numbers
        // (CursorManager clamps scrolling to them).
        dataProvider.jump(target2);
        assertThat(dataProvider.getBeforeBoundary().line()).isEmpty();
        assertThat(dataProvider.getAfterBoundary().line()).isEmpty();
        assertThat(dataProvider.getBeforeBoundary().instant()).isEqualTo(target2.truncatedTo(ChronoUnit.DAYS));
        assertThat(dataProvider.getAfterBoundary().instant())
                .isEqualTo(target2.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS));

        // Once the new segment loads, the boundaries regain line numbers.
        holdSecondQuery.release();
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
        assertThat(dataProvider.getBeforeBoundary().line()).isPresent();
        assertThat(dataProvider.getAfterBoundary().line()).isPresent();
    }

    @Test
    @DisplayName("Cancelling an extension freezes chronologically ordered boundaries at the segment edges")
    void cancelSearchKeepsBoundariesInChronologicalOrder() throws Exception {
        // Distinctive target so the segment id does not collide with other tests.
        Instant target = testClock.instant().truncatedTo(MINUTES).minus(25, MINUTES);

        // The initial query covers [target-30s, target+30s) but only finds these.
        List<Event> initialEvents = lines(target.minusSeconds(5), target.plusSeconds(5));
        // Data the cancelled extension would have found, and data for the resume checks.
        List<Event> laterEvents = lines(target.plusSeconds(60), target.plusSeconds(90));
        List<Event> earlierEvents = lines(target.minusSeconds(90), target.minusSeconds(60));
        List<Event> allEvents = Stream.of(earlierEvents, initialEvents, laterEvents).flatMap(List::stream).toList();

        // runJob notifies after every job: initial load + the superseded extension.
        CountDownLatch jobCompletions = new CountDownLatch(2);
        dataProvider.setLogUpdatedCallback(jobCompletions::countDown);

        Semaphore extensionQueryStarted = new Semaphore(0);
        Semaphore releaseExtensionQuery = new Semaphore(0);
        AtomicInteger queries = new AtomicInteger();

        // Loads (4-arg) and the search below (3-arg) share one answer and one query count
        org.mockito.stubbing.Answer<Stream<Event>> query = invocation -> {
            var decision = invocation.getArgument(0, LoadDecision.class);
            int queryNo = queries.getAndIncrement();
            if (queryNo == 0) {
                return initialEvents.stream();
            }
            if (queryNo == 1) {
                // Block the extension uninterruptibly so the cancel supersedes it; its
                // late result must be discarded rather than resurrect loading.
                extensionQueryStarted.release();
                releaseExtensionQuery.acquireUninterruptibly();
                return laterEvents.stream();
            }
            return allEvents.stream().filter(event -> {
                Instant eventTime = Instant.parse(event.get("_time"));
                return !eventTime.isBefore(decision.start()) && eventTime.isBefore(decision.end());
            });
        };
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(query);

        dataProvider.jump(target);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        SegmentInfo segment = dataProvider.getSegmentInfo();

        // Trigger a DOWN extension load and block it inside its Splunk query.
        getDataProviderLine(segment.endLineExclusive() + 150);
        assertThat(extensionQueryStarted.tryAcquire(3, SECONDS)).as("extension load is blocked inside its Splunk query")
                .isTrue();

        // Cancel the active extension, then let the stale result arrive.
        assertThat(dataProvider.cancelSearch()).isTrue();
        releaseExtensionQuery.release();
        assertThat(jobCompletions.await(3, SECONDS)).as("both the initial and the superseded extension job completed")
                .isTrue();

        // Boundaries must be pinned to the loaded segment edges in chronological
        // order: BEFORE (min) = segment start, AFTER (max) = segment end.
        assertThat(dataProvider.getBeforeBoundary().instant()).isEqualTo(segment.startTime());
        assertThat(dataProvider.getAfterBoundary().instant()).isEqualTo(segment.endTime());
        assertThat(dataProvider.getBeforeBoundary().instant())
                .as("BEFORE (min) boundary must not be after AFTER (max) boundary")
                .isBeforeOrEqualTo(dataProvider.getAfterBoundary().instant());
        assertThat(dataProvider.getBeforeBoundary().line()).hasValue(segment.startLine());
        assertThat(dataProvider.getAfterBoundary().line()).hasValue(segment.endLineExclusive());

        // The cancelled load must have been discarded: no insert, no segment growth,
        // and both directions stay stopped (boundary lines, no new loads).
        verify(databaseManager, times(1)).insertLogsBatch(any(), any());
        assertThat(dataProvider.getSegmentInfo().size()).isEqualTo(segment.size());
        assertThat(getDataProviderLine(segment.endLineExclusive() + 150)).isInstanceOf(AfterBoundaryLine.class);
        assertThat(getDataProviderLine(segment.startLine() - 1)).isInstanceOf(BeforeBoundaryLine.class);

        // A bounded search over the frozen window must be valid and find exactly the
        // loaded events (with inverted boundaries the search range is unusable).
        SearchSession session = new SearchSession("message", target);
        Searcher searcher = new Searcher(splunkClient, EVENT_MAPPER, session, "query",
                dataProvider.getBeforeBoundary().instant(), dataProvider.getAfterBoundary().instant());
        searcher.start();
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(session.getState()).isEqualTo(SearchSession.State.COMPLETED));
        assertThat(session.hitCount()).isEqualTo(initialEvents.size());

        // Resume loading in each direction: unfreeze, accept the expansion prompt,
        // and verify that scrolling past an edge loads again.
        dataProvider.setBoundaryExpansionListener(request -> request.accept(true));

        dataProvider.unfreezeAfterBoundary();
        await().atMost(3, SECONDS).untilAsserted(
                () -> assertThat(getDataProviderLine(segment.endLineExclusive())).isInstanceOf(LoadedLogLine.class));
        assertThat(((LoadedLogLine) getDataProviderLine(segment.endLineExclusive())).getTime())
                .isEqualTo(target.plusSeconds(60));

        dataProvider.unfreezeBeforeBoundary();
        await().atMost(3, SECONDS).untilAsserted(
                () -> assertThat(getDataProviderLine(segment.startLine() - 1)).isInstanceOf(LoadedLogLine.class));
        assertThat(((LoadedLogLine) getDataProviderLine(segment.startLine() - 1)).getTime())
                .isBetween(target.minusSeconds(90), target.minusSeconds(60));
    }

    @Test
    @DisplayName("A failed background load stops at the loaded segment; scrolling past the edge restarts")
    void failedBackgroundLoadStopsAtSegment() throws Exception {
        // Distinctive target so the segment id does not collide with other tests.
        Instant target = testClock.instant().truncatedTo(MINUTES).minus(35, MINUTES);

        List<Event> initialEvents = lines(target.minusSeconds(5), target.plusSeconds(5));
        List<Event> laterEvents = lines(target.plusSeconds(60), target.plusSeconds(90));
        List<Event> allEvents = Stream.of(initialEvents, laterEvents).flatMap(List::stream).toList();

        // runJob notifies after every job: initial load + the failing extension.
        CountDownLatch jobCompletions = new CountDownLatch(2);
        dataProvider.setLogUpdatedCallback(jobCompletions::countDown);

        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            var decision = invocation.getArgument(0, LoadDecision.class);
            int queryNo = queries.getAndIncrement();
            if (queryNo == 0) {
                return initialEvents.stream();
            }
            if (queryNo == 1) {
                throw new SearchException("Mocked: extension failed", null);
            }
            return allEvents.stream().filter(event -> {
                Instant eventTime = Instant.parse(event.get("_time"));
                return !eventTime.isBefore(decision.start()) && eventTime.isBefore(decision.end());
            });
        });

        dataProvider.jump(target);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));
        SegmentInfo segment = dataProvider.getSegmentInfo();

        // Trigger a DOWN extension load that fails, and wait until its job is done
        // (so the provider is no longer loading when the failure is observed).
        int beyond = segment.endLineExclusive() + 150;
        getDataProviderLine(beyond);
        assertThat(jobCompletions.await(3, SECONDS)).as("both the initial and the failing extension job completed")
                .isTrue();

        // The failure is reported exactly once.
        Assertions.assertThatThrownBy(() -> getDataProviderLine(beyond)).isInstanceOf(SearchException.class)
                .hasMessageContaining("extension failed");

        // Stopped at the loaded segment: ordered, line-stamped boundaries and
        // boundary lines on both sides — no silent retry.
        assertThat(dataProvider.getBeforeBoundary().instant()).isEqualTo(segment.startTime());
        assertThat(dataProvider.getAfterBoundary().instant()).isEqualTo(segment.endTime());
        assertThat(dataProvider.getBeforeBoundary().line()).hasValue(segment.startLine());
        assertThat(dataProvider.getAfterBoundary().line()).hasValue(segment.endLineExclusive());
        assertThat(getDataProviderLine(beyond)).isInstanceOf(AfterBoundaryLine.class);
        assertThat(getDataProviderLine(segment.startLine() - 1)).isInstanceOf(BeforeBoundaryLine.class);
        assertThat(queries.get()).as("a failed load must not be re-issued without user action").isEqualTo(2);

        // Scrolling past the edge unfreezes it and loading resumes.
        dataProvider.setBoundaryExpansionListener(request -> request.accept(true));
        dataProvider.unfreezeAfterBoundary();
        await().atMost(3, SECONDS).untilAsserted(
                () -> assertThat(getDataProviderLine(segment.endLineExclusive())).isInstanceOf(LoadedLogLine.class));
        assertThat(((LoadedLogLine) getDataProviderLine(segment.endLineExclusive())).getTime())
                .isEqualTo(target.plusSeconds(60));
    }

    @Test
    @DisplayName("A too-big load of more than one effective second still shrinks the window")
    void splittableTooBigWindowStillShrinks() throws Exception {
        Instant target = testClock.instant().truncatedTo(MINUTES).minus(50, MINUTES);
        Event atTarget = createEvent(target, "cd_target_r33b", null, "target");
        Event earlier = createEvent(target.minusSeconds(40), "cd_earlier_r33b", null, "earlier");

        List<LoadDecision> upDecisions = new ArrayList<>();
        AtomicInteger queries = new AtomicInteger();
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            LoadDecision decision = invocation.getArgument(0);
            int queryNo = queries.getAndIncrement();
            if (queryNo == 0) {
                return Stream.of(atTarget);
            }
            synchronized (upDecisions) {
                upDecisions.add(decision);
            }
            if (queryNo == 1) {
                throw new SearchResultToBigException("Mocked: too big", 60_000, null, null);
            }
            return Stream.of(earlier).filter(event -> decision.contains(Instant.parse(event.get("_time"))));
        });

        dataProvider.jump(target);
        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        await().atMost(10, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(-1)).isInstanceOf(LoadedLogLine.class));

        synchronized (upDecisions) {
            assertThat(upDecisions).hasSize(2);
            assertThat(upDecisions.get(1).getDuration()).isLessThan(upDecisions.get(0).getDuration());
        }
    }

    @Test
    void noDuplicateLinesOnMultipleScrolls() throws Exception {

        Instant now = testClock.instant().truncatedTo(MINUTES).minus(10, MINUTES);

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            var events = new ArrayList<Event>(lines(now, now.plusSeconds(30)));
            return events.stream();
        });

        var i = dataProvider.jump(now);
        System.out.printf("jumped to %d%n", i);

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(14)).isInstanceOf(LoadedLogLine.class));

        // check all lines are loaded and have consequtive timestamps
        for (int j = 0; j < 10; j++) {
            if (getDataProviderLine(j) instanceof LoadedLogLine logLine) {
                assertThat(logLine.getTime()).as("Line %s has wrong timestamp", j).isEqualTo(now.plusMillis(100L * j));
            } else {
                Assertions
                        .fail("Line " + j + " is not a LoadedLogLine: " + getDataProviderLine(j).getClass().getName());
            }
        }

        // Verify that the database is never called to insert the same lines more than once
        verify(databaseManager, times(1)).insertLogsBatch(any(String.class), ArgumentMatchers.any(List.class));

        verify(databaseManager, times(2)).load(any(), eq(0));
        verify(databaseManager, times(1)).load(any(), eq(14));
        verify(databaseManager, times(1)).load(any(), eq(5));
        verify(databaseManager, times(0)).load(any(), eq(13));
    }

    @Test
    void tooFewHits() throws Exception, SearchResultToBigException {

        Instant now = testClock.instant().truncatedTo(MINUTES).minus(10, MINUTES);

        System.out.println("*** Generating available events from " + now + " to " + now.plus(5, MINUTES));

        List<Event> availableResult = new ArrayList<>();
        for (Instant i = now; i.isBefore(now.plus(5, MINUTES)); i = i.plus(1, MINUTES)) {
            availableResult.add(createEvent(i, "cd" + i, null, "message" + i));
        }

        // print available events:  time + message
        availableResult.forEach(
                event -> System.out.println("*** Available event: " + event.get("_time") + " " + event.get("_cd")));

        // Mock queryEventsWithMonitoring to return events within the time range
        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            var decision = invocation.getArgument(0, LoadDecision.class);
            Instant start = decision.start();
            Instant end = decision.end();
            // return the events that are within the start and end time
            List<Event> testData = new ArrayList<>();
            for (Event event : availableResult) {
                Instant eventTime = Instant.parse(event.get("_time"));
                System.out.println("*** Considering event: " + eventTime + " " + event.get("_cd") + " (" + start + ", "
                        + end + ")");
                if (eventTime.compareTo(start) >= 0 && eventTime.isBefore(end)) {
                    System.out.println("*** Return event: " + eventTime + " " + event.get("_cd") + " (" + start + ", "
                            + end + ")");
                    testData.add(event);
                }
            }
            System.out.printf("*** Return %d events between %s and %s - sleep! %n", testData.size(), start, end);
            sleep(500);
            System.out.printf("*** Return %d events between %s and %s - returning%n", testData.size(), start, end);

            return testData.stream();
        });

        System.out.printf("*** jump to %tT%n", Date.from(now));

        var i = this.dataProvider.jump(now);
        System.out.printf("*** jumped to %d%n", i);

        System.out.printf("*** scroll to %d%n", 0);
        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(this.getDataProviderLine(0)).isInstanceOfAny(LoadedLogLine.class));

        await().atMost(30, SECONDS).with().pollInterval(1, SECONDS)
                .logging(s -> System.out.println("*** Checking line 1"))
                .untilAsserted(() -> assertThat(this.getDataProviderLine(1)).isInstanceOfAny(LoadedLogLine.class));

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(this.getDataProviderLine(2)).isInstanceOfAny(LoadedLogLine.class));

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(this.getDataProviderLine(3)).isInstanceOfAny(LoadedLogLine.class));

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(this.getDataProviderLine(4)).isInstanceOfAny(LoadedLogLine.class));

        System.out.printf("*** scroll to %d%n", 10);
        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(this.getDataProviderLine(14)).isInstanceOfAny(NowBoundaryLine.class));

        // this should return NoResult
        // NoResult noResult = (NoResult) dataProvider.getLine(5);

        // check all lines are loaded and have consequtive timestamps
        for (int j = 0; j < 4; j++) {
            System.out.printf("*** Checking line %d%n", j);
            if (this.getDataProviderLine(j) instanceof LoadedLogLine logLine) {
                assertThat(logLine.getTime()).as("Line %s has wrong timestamp", j)
                        .isEqualTo(availableResult.get(j).get("_time"));
            } else {
                Assertions.fail(
                        "Line " + j + " is not a LoadedLogLine: " + this.getDataProviderLine(j).getClass().getName());
            }
        }

        for (int j = 5; j < 10; j++) {
            System.out.printf("*** Checking line %d is After boundary %n", j);
            assertThat(getDataProviderLine(j)).isInstanceOf(NowBoundaryLine.class);
        }

        // Verify that the database is never called to insert the same lines more than once

        verify(databaseManager, times(2)).load(any(), eq(0));

        verify(databaseManager, times(0)).load(any(), eq(14));

        verify(databaseManager, times(0)).load(any(), eq(5));

        verify(databaseManager, times(0)).load(any(), eq(13));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // Create a minimal splunk event
    public static Event createEvent(Instant time, String cd, String stacktrace, String message) {

        return createEvent(time, time, cd, stacktrace, message);
    }

    public static Event createEvent(Instant splunkTime, Instant sourceTime, String cd, String stacktrace,
            String message) {

        // Create a minimal
        // String stacktrace, String message
        //     stacktrace
        // message
        String optinalStacktrace = stacktrace == null ? "" : """
                "stacktrace": "%s",
                """.formatted(stacktrace);

        String raw = """
                {
                    "@timestamp": "%s",%s

                    "message": "%s"
                }
                """.formatted(sourceTime, optinalStacktrace, message);

        return TestEvents.event(Map.of("_time", splunkTime.toString(), "_raw", raw, "_cd", cd, "_bkt", "bkt_" + cd,
                "_indextime", String.valueOf(splunkTime.toEpochMilli())));
    }

    @SneakyThrows
    private List<Event> lines(Instant start, Instant end) {

        System.out.println("LOADLOADLOAD");
        Thread.sleep(10);

        ArrayList<Event> events = new ArrayList<>();
        int idx = 0;
        for (Instant i = start; i.isBefore(end); i = i.plusMillis(100)) {
            events.add(createEvent(i, "cd" + i, null, "message" + idx));
            System.out.println("***  event at " + i + " cd" + i + " message" + idx);
            idx++;
        }

        return events;
    }

    // ==================== Half-Open Interval Verification Tests ====================

    @Test
    @DisplayName("Verify half-open interval: start time is inclusive")
    void verifyStartTimeInclusive() throws Exception, SearchResultToBigException {
        Instant start = testClock.instant().truncatedTo(MINUTES).minus(10, MINUTES);

        List<Instant> eventTimes = new ArrayList<>();
        eventTimes.add(start); // Event at exact start time
        eventTimes.add(start.plusMillis(100)); // Event just after start time

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            var decision = invocation.getArgument(0, LoadDecision.class);
            Instant queryStart = decision.start();
            Instant queryEnd = decision.end();

            List<Event> testData = new ArrayList<>();

            for (Instant eventTime : eventTimes) {

                // Simulate Splunk behavior: include events where time >= queryStart AND time < queryEnd
                if (eventTime.compareTo(queryStart) >= 0 && eventTime.isBefore(queryEnd)) {
                    Event event = createEvent(eventTime, "cd_" + eventTime, null, "Event at " + eventTime);
                    testData.add(event);
                }
            }

            System.out.printf("<<< Returning %d events for query from %s to %s%n", testData.size(), queryStart,
                    queryEnd);

            return testData.stream();
        });

        dataProvider.jump(start);

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        // Verify the event at start time is included
        LoadedLogLine line = (LoadedLogLine) getDataProviderLine(0);
        assertThat(line.getTime()).isEqualTo(start);
    }

    @Test
    @DisplayName("Verify half-open interval: end time is exclusive")
    void verifyEndTimeExclusive() throws Exception {
        Instant start = testClock.instant().truncatedTo(MINUTES).minus(10, MINUTES);
        Instant end = start.plusSeconds(10);

        // Create events: one before end
        Event eventBeforeEnd = createEvent(end.minusMillis(100), "cd_before", null, "Before end");

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            var decision = invocation.getArgument(0, LoadDecision.class);
            Instant queryStart = decision.start();
            Instant queryEnd = decision.end();

            List<Event> testData = new ArrayList<>();

            // Simulate Splunk behavior: include events where time >= queryStart AND time < queryEnd
            Instant eventTime = end.minusMillis(100);
            if (eventTime.compareTo(queryStart) >= 0 && eventTime.isBefore(queryEnd)) {
                testData.add(eventBeforeEnd);
            }

            return testData.stream();
        });

        dataProvider.jump(start);

        await().atMost(3, SECONDS)
                .untilAsserted(() -> assertThat(getDataProviderLine(0)).isInstanceOf(LoadedLogLine.class));

        // Should have the event before end
        LoadedLogLine line = (LoadedLogLine) getDataProviderLine(0);
        assertThat(line.getTime()).isEqualTo(end.minusMillis(100));
        assertThat(line.getTime()).isNotEqualTo(end);
    }
}
