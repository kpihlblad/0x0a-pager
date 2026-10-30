package se.grynna.pager.splunk;

import com.splunk.Event;
import com.splunk.TestEvents;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Instant.EPOCH;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for SplunkLogDataProvider with large dataset.
 * Tests the full workflow: generate random logs, jump to middle, scroll up/down to boundaries.
 * <p>
 * The class normally runs in under a minute. It has hung intermittently, so each test fails after a few minutes
 * instead of blocking the build; a separate thread, because the stuck thread may not be interruptible.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SplunkLogDataProviderIntegrationTest {

    @TempDir
    Path tempDir;

    private SplunkLogDataProvider dataProvider;
    private SplunkClient splunkClient;
    private DatabaseManager databaseManager;
    private FixedClock testClock;

    // Test data

    // TreeMap to store logs by timestamp (nanosecond precision as long key)
    private TreeMap<Long, Event> allLogs;
    private AtomicInteger eventCounter;

    // Track query calls for validation
    private ArrayList<QueryCall> queryCalls;

    // Completions reported by the provider. A wait returns only for a completion after its snapshot,
    // never for a load that had already finished.
    private final AtomicInteger loadCompletions = new AtomicInteger();

    @BeforeAll
    static void beforeAll() {
        Config config = mock(Config.class);
        when(config.getLocale()).thenReturn(Locale.ENGLISH);
        when(config.getZoneId()).thenReturn(ZoneId.of("UTC"));
        DateTimeFormatterManager.initialize(config);
    }

    @BeforeEach
    void setUp() {
        // Initialize query tracking
        queryCalls = new ArrayList<>();
    }

    /**
     * Initialize the test with a specific number of logs.
     */
    void setUpWithLogCount(int logCount, LocalDate testDate, Instant testBegin, Instant testEnd)
            throws SearchException, StorageException, TemporarySearchException {

        // Generate test data
        generateTestLogs(testBegin, testEnd, logCount);

        // Setup mocks
        splunkClient = Mockito.mock(SplunkClient.class);
        databaseManager = new DatabaseManager("test", "test", "test", tempDir.toFile());

        when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            var decision = invocation.getArgument(0, LoadDecision.class);
            Instant start = decision.start();
            Instant end = decision.end();
            int queryNo = queryCalls.size();

            System.out.println(">>> Mocked query:    " + queryCalls.size());
            System.out.println(">>> Reason:          " + decision.reason());
            System.out.println(">>> State:           " + decision.state());
            System.out.println(">>> Start:           " + start);
            System.out.println(">>> End:             " + end);
            System.out.println(">>> Boundary Hit:    " + decision.boundaryHit());
            System.out.println(">>> Duration:        " + Duration.between(start, end));
            System.out.println(">>> Direction:       " + (decision.direction() > 0 ? "DOWN" : "UP"));
            System.out.println(">>> Max Events:      "
                    + (decision.maxEvents().isPresent() ? decision.maxEvents().getAsInt() : "unlimited"));
            System.out.println(">>> Before Boundary: " + dataProvider.getBeforeBoundary());
            System.out.println(">>> After Boundary:  " + dataProvider.getAfterBoundary());
            System.out.println(">>> Now Boundary:    " + dataProvider.getNowBoundary());
            System.out.println();

            // assert shouldLoad is true
            if (!decision.shouldLoad()) {
                throw new IllegalArgumentException(
                        "Mocked queryEventsWithMonitoring called with shouldLoad=false decision");
            }

            // Track this query call
            QueryCall call = new QueryCall(decision);

            // this might throw SearchResultTooBigException
            // in that case let it throw and do not track the query
            var result = mockQueryResults(decision, queryNo);

            // Validate no overlapping querieswaitUntil
            for (QueryCall oldCall : queryCalls) {
                if (call.overlaps(oldCall)) {
                    throw new IllegalStateException(String.format("Overlapping queries detected:\n  Old %s)\n  New %s",
                            oldCall.decision, call.decision));
                }
            }

            // check that the direction matches compared to last query
            if (!queryCalls.isEmpty()) {
                var last = queryCalls.getLast();
                assertThat(call.expandedInDirection(last)).as(
                        "Query direction should match expansion direction compared to last query.\nLast: %s\nNew : %s",
                        last, call).isTrue();
                System.out.println(">>> Query direction " + (call.decision.direction() > 0 ? "DOWN" : "UP")
                        + " matches expansion compared to last query");
                if (call.decision().direction() > 0) {
                    System.out.println(">>> Last end: " + last.decision.end());
                    System.out.println(">>> New  end: " + call.decision.end());
                } else {
                    System.out.println(">>>   Last start: " + last.decision.start());
                    System.out.println(">>>   New start:  " + call.decision.start());
                }
            }

            queryCalls.add(call);
            return result;
        });

        LogUpdatedCallback logUpdated = () -> {
            int n = loadCompletions.incrementAndGet();
            System.out.println("*** Loading complete, completion " + n);
        };

        // Create data provider
        LogEventMapper mapper = new LogEventMapper();

        dataProvider = new SplunkLogDataProvider(mapper, databaseManager, logUpdated, () -> splunkClient, () -> "query",
                testClock, new SearchBoundaries(testClock));

        System.out.println("* DAY START : " + testBegin);
        System.out.println("* NOW       : " + testClock.instant());
        System.out.println("* DAY END   : " + testEnd);
    }

    /**
     * Generate random log messages distributed across the day.
     * Uses TreeMap with nanosecond timestamp as key to ensure ordering and uniqueness.
     */
    private void generateTestLogs(Instant start, Instant end, int count) {
        allLogs = new TreeMap<>();
        eventCounter = new AtomicInteger(0);

        if (count == 0) {
            System.out.println("Generating 0 test logs (empty dataset)");
            return;
        }

        Random random = new Random(42); // Fixed seed for reproducibility

        long durationNanos = Duration.between(start, end).toNanos();
        long startNanos = start.getEpochSecond() * 1_000_000_000L + start.getNano();

        System.out.println("*** Startnanos = " + startNanos + " = " + EPOCH.plusNanos(startNanos));
        System.out.println("*** DurationNanos = " + durationNanos + " = " + Duration.ofNanos(durationNanos));

        System.out.println("*** Generating " + count + " test logs between " + start + " and " + end);

        int attempts = 0;
        int maxAttempts = count * 10; // Safety limit

        while (allLogs.size() < count) {
            attempts++;

            if (attempts > maxAttempts) {
                throw new RuntimeException(
                        "Failed to generate enough unique timestamps after " + maxAttempts + " attempts");
            }

            // Generate random nanosecond timestamp within the day
            long n = startNanos + random.nextLong(durationNanos);

            // Skip if duplicate (TreeMap will tell us)
            if (allLogs.containsKey(n)) {
                continue;
            }

            // Create event at this timestamp
            Instant timestamp = EPOCH.plusNanos(n);

            Event event = createEvent(timestamp, eventCounter.getAndIncrement());
            allLogs.put(n, event);
        }

        System.out.println("*** Generated " + allLogs.size() + " unique log entries in " + attempts + " attempts");
        System.out.println("*** First log: " + allLogs.firstEntry().getValue().get("_time"));
        System.out.println("*** First ins: " + EPOCH.plusNanos(allLogs.firstKey()));
        System.out.println("*** Last log:  " + allLogs.lastEntry().getValue().get("_time"));
        System.out.println("*** Last ins:  " + EPOCH.plusNanos(allLogs.lastKey()));
    }

    /**
     * Create a Splunk Event with the given timestamp.
     */
    private Event createEvent(Instant timestamp, int eventId) {
        String raw = String.format("""
                {
                    "@timestamp": "%s",
                    "message": "Test log message #%d",
                    "level": "INFO",
                    "eventId": %d
                }
                """, timestamp, eventId, eventId);

        return TestEvents.event(Map.of("_time", timestamp.toString(), "_raw", raw, "_cd", "cd:" + eventId, "_bkt",
                "bkt:" + (eventId % 5), "_indextime", String.valueOf(timestamp.getEpochSecond())));
    }

    /**
     * Mock Splunk query results by filtering logs in the requested time range [start, end).
     * Tracks queries that happen after a SearchResultToBigException for verification.
     */
    // Epoch-second x 1e9 + nano-of-second below reconstructs total nanos with
    // sub-millisecond precision preserved; the getNano() use is intentional.
    @SuppressWarnings("JavaInstantGetSecondsGetNano")
    private java.util.stream.Stream<com.splunk.Event> mockQueryResults(LoadDecision decision, int queryCount)
            throws SearchResultToBigException {

        long startNanos = decision.start().getEpochSecond() * 1_000_000_000L + decision.start().getNano();
        long endNanos = decision.end().getEpochSecond() * 1_000_000_000L + decision.end().getNano();

        // Get logs in range [start, end) - half-open interval
        List<Event> results = allLogs.subMap(startNanos, true, endNanos, false).values().stream().toList();

        Instant firstLogTime = results.isEmpty() ? null : Instant.parse(results.getFirst().get("_time"));
        Instant lastLogTime = results.isEmpty() ? null : Instant.parse(results.getLast().get("_time"));
        System.out.println(">>> First log time in result: " + firstLogTime);
        System.out.println(">>> Last  log time in result: " + lastLogTime);

        if (results.size() > 2800) {
            System.out.println(">>> Query " + queryCount + ", result too big: " + results.size());
            // start/end in SearchResultToBigException represent the first/last log instant of the loaded segment
            throw new SearchResultToBigException("Mocked: Result too big: " + results.size(), results.size(),
                    firstLogTime, lastLogTime);
        }

        System.out.printf(">>> Query %d, returned %d logs%n", queryCount, results.size());

        // check for maxEvents limit
        if (decision.maxEvents().isPresent()) {
            int maxEvents = decision.maxEvents().getAsInt();
            if (results.size() >= maxEvents) {

                // last "maxEvents" logs
                int lastIndex = results.size();
                int firstIndex = lastIndex - maxEvents;

                var tail = results.subList(firstIndex, lastIndex);

                firstLogTime = Instant.parse(tail.getFirst().get("_time"));
                lastLogTime = Instant.parse(tail.getLast().get("_time"));
                throw new SearchResultToBigException("Mocked: Result too big (maxEvents): " + results.size(),
                        results.size(), firstLogTime, lastLogTime);
            }
        }

        return results.stream();
    }

    /**
     * Wait for a line to be loaded or hit a boundary.
     */
    private LogLine waitForLineToLoad(int lineNum) throws TemporarySearchException, SearchException, StorageException {
        await().atMost(Duration.ofSeconds(30)).pollDelay(0, MILLISECONDS).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    LogLine line = dataProvider.getLine(lineNum, true);
                    // System.out.println("<<< " + lineNum + ": Got " + line.getClass().getSimpleName() + " waiting for
                    // " + Arrays.toString(new Class<?>[]{LoadedLogLine.class, AfterBoundaryLine.class,
                    // BeforeBoundaryLine.class, NowBoundaryLine.class}));
                    assertThat(line).as("Line %d should be loaded or boundary", lineNum).isInstanceOfAny(
                            LoadedLogLine.class, AfterBoundaryLine.class, BeforeBoundaryLine.class,
                            NowBoundaryLine.class);
                });

        return dataProvider.getLine(lineNum, true);
    }

    /**
     * Validate a loaded log line's timestamp and track it.
     */
    private void validateAndTrackLogLine(Instant dayStart, Instant dayEnd, LoadedLogLine logLine, int lineNum,
            Map<Integer, Instant> loadedLines, Set<Instant> seenTimestamps, LoadedLogLine lastLine, int delta) {
        Instant timestamp = logLine.getTime();

        assertThat(timestamp).as("Line %d timestamp should be within day boundaries", lineNum)
                .isAfterOrEqualTo(dayStart).isBefore(dayEnd);

        assertThat(seenTimestamps).as("Line %d timestamp %s should not be duplicate", lineNum, timestamp)
                .doesNotContain(timestamp);

        seenTimestamps.add(timestamp);
        var prev = loadedLines.put(lineNum, timestamp);

        assertThat(prev).as("Line %d should not have been loaded before", lineNum).isNull();

        if (lastLine != null && delta > 0) {
            Instant lastTimestamp = lastLine.getTime();
            assertThat(timestamp).as("Line %d timestamp %s should be after last line timestamp %s", lineNum, timestamp,
                    lastTimestamp).isAfter(lastTimestamp);
        } else if (lastLine != null && delta < 0) {
            Instant lastTimestamp = lastLine.getTime();
            assertThat(timestamp).as("Line %d timestamp %s should be before last line timestamp %s", lineNum, timestamp,
                    lastTimestamp).isBefore(lastTimestamp);
        }
    }

    /**
     * Scroll up from line 0 until hitting the before boundary.
     * Returns the number of scrolls and updates the tracking collections.
     */
    private int getUntilResult(Map<Integer, Instant> loadedLines, Set<Instant> seenTimestamps, int start, int delta,
            Instant testBegin, Instant testEnd) throws TemporarySearchException, SearchException, StorageException {
        System.out.printf("\n>>> get until result %s delta %d ---%n", start, delta);
        int scrolls = 0;

        // print boundaries
        System.out.println(">>> Before boundary: " + dataProvider.getBeforeBoundary());
        System.out.println(">>> After boundary:  " + dataProvider.getAfterBoundary());
        System.out.println(">>> Now boundary:    " + dataProvider.getNowBoundary());

        LoadedLogLine lastLine = null;
        for (int lineToTest = start;; lineToTest += delta) {
            LogLine line = waitForLineToLoad(lineToTest);
            scrolls++;

            switch (line) {
                case AfterBoundaryLine l -> {
                    System.out.println(">>>  Line " + l + ": HIT BOUNDARY - AfterBoundary");
                    return scrolls;
                }
                case BeforeBoundaryLine l -> {
                    System.out.println("***  Line " + l + ": HIT BOUNDARY - BeforeBoundary");
                    return scrolls;
                }
                case NowBoundaryLine l -> {
                    System.out.println("***  Line " + l + ": HIT BOUNDARY - NowBoundary");
                    return scrolls;
                }
                case LoadedLogLine l -> {
                    validateAndTrackLogLine(testBegin, testEnd, l, lineToTest, loadedLines, seenTimestamps, lastLine,
                            delta);
                    lastLine = l;
                }
                default -> fail("Unexpected line type when scrolling: " + line.getClass().getSimpleName());
            }
        }
    }

    /**
     * Provider for parameterized tests with different log counts.
     */
    private static java.util.stream.Stream<Integer> logCountProvider() {
        return java.util.stream.Stream.of(0, 1, 10, 100, 1000, 10_000);
    }

    @Test
    void testNoEventsNoRefresh()
            throws SearchException, IOException, StorageException, TemporarySearchException, InterruptedException {

        LocalDate testDate = LocalDate.of(2025, 11, 11);
        Instant testBegin = testDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant testEnd = testDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Initialize test clock slightly before the test day
        testClock = new FixedClock(testEnd.minus(1, HOURS), ZoneOffset.UTC);

        setUpWithLogCount(0, testDate, testBegin, testEnd);

        System.out.println("\n*** JumpToLatest()");

        int seen = loadCompletions.get();
        int noonLine = dataProvider.jumpToLatest();

        System.out.println("*** Jumped to Latest: Line: " + noonLine);

        LogLine line;

        do {
            waitForLoadAfter(seen);
            // Snapshot before getLine: it may start the next load, which can finish before the
            // next wait is armed.
            seen = loadCompletions.get();
            System.out.println("*** Get " + noonLine);
            line = dataProvider.getLine(noonLine, true);

        } while (!(line instanceof LoadedLogLine || line instanceof BeforeBoundaryLine));
    }

    /**
     * Wait until a load reported after {@code seen} has finished. Completions at or before
     * {@code seen} do not count, so a leftover callback cannot satisfy this wait.
     */
    private void waitForLoadAfter(int seen) {
        System.out.println("*** Waiting for load to complete (after " + seen + ")...");
        await().atMost(Duration.ofSeconds(30)).pollDelay(0, MILLISECONDS).pollInterval(Duration.ofMillis(10))
                .until(() -> loadCompletions.get() > seen);
    }

    @ParameterizedTest
    @MethodSource("logCountProvider")
    @SneakyThrows
    void testFullScrollScenario(int logCount) {

        final var testDate = LocalDate.of(2025, 11, 11);
        final var testBegin = testDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        final var testEnd = testDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Initialize test clock slightly before the test day
        testClock = new FixedClock(testEnd.plus(1, HOURS), ZoneOffset.UTC);

        setUpWithLogCount(logCount, testDate, testBegin, testEnd);

        final Instant start;
        if (allLogs.isEmpty()) {
            // no logs, set start to start of test
            start = testBegin;
        } else {
            // test first position is just after the first log
            start = Instant.parse(allLogs.firstEntry().getValue().get("_time")).plus(10, MINUTES);
        }
        System.out.println("\n*** Starting Integration Test with " + logCount + " logs ===");
        System.out.println("Total logs generated: " + allLogs.size());

        // Jump to noon to initialize
        System.out.println("\n>>> Jump to start to initialize: " + start);
        int noonLine = dataProvider.jump(start);
        System.out.println(">>> Jumped to noon, got line: " + noonLine);

        // assert that the before and after boundaries are midnight and next midnight
        assertThat(dataProvider.getBeforeBoundary().instant()).as("Before boundary should be at start of day")
                .isEqualTo(testBegin);
        assertThat(dataProvider.getAfterBoundary().instant()).as("After boundary should be at end of day")
                .isEqualTo(testEnd);

        // Track all loaded lines and their timestamps
        Map<Integer, Instant> loadedLines = new TreeMap<>();
        Set<Instant> seenTimestamps = new HashSet<>();

        // Step 1: Scroll UP to start boundary
        System.out.println("\n>>> Scrolling UP to start boundary: " + dataProvider.getBeforeBoundary());
        int scrollsUp = getUntilResult(loadedLines, seenTimestamps, 0, -1, testBegin, testEnd);
        System.out.println(
                "\n*** Finished scrolling UP: " + scrollsUp + " scrolls, loaded " + loadedLines.size() + " lines");

        System.out.println();
        // Step 2: Scroll DOWN to end boundary
        System.out.println("\n*** Scrolling DOWN to end boundary ---");
        int scrollsDown = getUntilResult(loadedLines, seenTimestamps, 1, 1, testBegin, testEnd);
        System.out.println("\n*** Finished scrolling DOWN: " + scrollsDown + " scrolls, total loaded "
                + loadedLines.size() + " lines");

        assertThat(loadedLines.size()).as("Should have loaded all lines").isEqualTo(logCount); //

        // Validate query efficiency
        validateQueryEfficiency(logCount);

        System.out.println("\n=== Integration Test Complete ===");
        System.out.println("Total unique lines loaded: " + loadedLines.size());
        System.out.println("Total unique timestamps: " + seenTimestamps.size());
        System.out.println("Scrolls UP: " + scrollsUp);
        System.out.println("Scrolls DOWN: " + scrollsDown);
        System.out.println("Total queries: " + queryCalls.size());
    }

    /**
     * Generate 30 days of dense logs, jump to 3 month before.
     * Automatically approve boundaries
     */
    @Test
    public void testLargeDatasetJumpToPast()
            throws SearchException, IOException, StorageException, TemporarySearchException, InterruptedException {

        LocalDate testDate = LocalDate.of(2025, 11, 11);
        Instant testBegin = testDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant testEnd = testDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Initialize test clock slightly after the test period
        testClock = new FixedClock(testEnd.plus(1, HOURS), ZoneOffset.UTC);

        int logCount = 100_000;
        setUpWithLogCount(logCount, testDate, testBegin, testEnd);

        // Get the first log time - this is where the too-big exception should point to
        Instant firstLogTime = EPOCH.plusNanos(allLogs.firstKey());
        System.out.println("*** First log time: " + firstLogTime);

        AtomicReference<Instant> acceptedAfterBoundary = new AtomicReference<>();

        // auto-approve AFTER-boundaries
        dataProvider.setBoundaryExpansionListener(request -> {
            if (request.type() == BoundaryType.BEFORE) {
                request.accept(false);
                return;
            }

            var old = acceptedAfterBoundary.getAndSet(request.suggestedBoundary());
            if (old != null) {
                // make sure the suggestion is moving forward
                assertThat(request.suggestedBoundary()).as(
                        "Suggested AFTER boundary " + request.suggestedBoundary() + "shoud be after previous " + old)
                        .isAfter(old);
                System.out.println(
                        ">>> replaced accepted AFTER boundary " + old + " with " + request.suggestedBoundary());
            }
            System.out.println(">>> accepting AFTER boundary " + request.suggestedBoundary());
            request.accept(true);
        });

        System.out.println("\n*** JumpToPast()");

        int seen = loadCompletions.get();
        Instant jumpToTime = testDate.minusMonths(3).atStartOfDay(ZoneOffset.UTC).toInstant();
        int lineNum = dataProvider.jump(jumpToTime);

        System.out.println("*** Jumped to Past: Line: " + lineNum);

        waitForLoadAfter(seen);

        System.out.println("*** Get " + lineNum);
        loadPageFully(lineNum, lineNum + logCount);
    }

    /**
     * Load a full page of logs starting from lineNum to lineNum + i
     *
     * @param lineNum  the first row number to load
     * @param pagesize the number of consecutive rows to load
     * @return the loaded log lines covering the requested page
     */
    private Set<LoadedLogLine> loadPageFully(int lineNum, int pagesize)
            throws TemporarySearchException, SearchException, StorageException, InterruptedException {

        LinkedHashSet<LoadedLogLine> result = new LinkedHashSet<>(pagesize);

        int hit = 0;
        for (int totalruns = 0; totalruns < 76; totalruns++) {
            // Before the scan: getLine may start a load, and that load may finish before the wait.
            int seen = loadCompletions.get();
            hit = 0;
            for (int i = lineNum; i < lineNum + pagesize; i++) {
                LogLine line = dataProvider.getLine(i, i == lineNum || i == (lineNum + pagesize - 1));

                if (line instanceof LoadedLogLine l) {
                    result.add(l);
                    hit++;
                }
            }
            if (hit == pagesize) {
                return result;
            }
            waitForLoadAfter(seen);
        }
        assertThat(hit).as("Should have loaded full page").isEqualTo(pagesize);
        assertThat(result.size()).as("Should have unique loaded lines").isEqualTo(pagesize);
        return result;
    }

    /**
     * Validate that queries are efficient and non-overlapping.
     */
    private void validateQueryEfficiency(int logCount) {
        int totalQueries = queryCalls.size();

        System.out.println("\n*** Query Efficiency Validation ---");
        System.out.println("Total queries made: " + totalQueries);
        System.out.println("Log count: " + logCount);

        // Define expected query limits based on log count
        // lets do
        // logCount 0 starts with 3
        // expect 2000 lines per request
        int requests = (logCount + 1999) / 2000;
        int maxExpectedQueries = 5 + requests;

        assertThat(totalQueries).as("Number of queries should be efficient for %d logs", logCount)
                .isLessThanOrEqualTo(maxExpectedQueries);

        // Validate no duplicate start/end timestamps (no exact same query)
        Set<QueryCall> uniqueQueries = new HashSet<>(queryCalls);
        assertThat(uniqueQueries.size()).as("All queries should be unique (no duplicate start/end pairs)")
                .isEqualTo(totalQueries);

        System.out.println("Query efficiency validation passed!");
        System.out.println("  - All " + totalQueries + " queries are unique");
        System.out.println("  - No overlapping queries detected");
        System.out.println("  - Query count within expected limit: " + totalQueries + " <= " + maxExpectedQueries);
    }

    /**
     * Fixed clock for testing.
     */
    private static class FixedClock extends Clock {
        private final Instant fixedInstant;
        private final ZoneId zone;

        FixedClock(Instant fixedInstant, ZoneId zone) {
            this.fixedInstant = fixedInstant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new FixedClock(fixedInstant, zone);
        }

        @Override
        public Instant instant() {
            return fixedInstant;
        }
    }

    /**
     * Represents a query call with start and end timestamps.
     * Used to track and validate query efficiency.
     */
    private record QueryCall(LoadDecision decision) {
        /**
         * Check if this query overlaps with another query.
         * Queries use half-open intervals [start, end), so they overlap if:
         * - This query starts before the other ends AND
         * - This query ends after the other starts
         */
        boolean overlaps(QueryCall other) {
            // [start1, end1) overlaps with [start2, end2) if:
            // start1 < end2 AND end1 > start2
            return this.decision.start().isBefore(other.decision.end())
                    && this.decision.end().isAfter(other.decision.start());
        }

        boolean expandedInDirection(QueryCall old) {
            if (decision.direction() > 0) {
                // forward
                return this.decision.end().isAfter(old.decision.end())
                        && this.decision.start().isAfter(old.decision.start());
            } else if (decision.direction() < 0) {
                // backward
                return this.decision.start().isBefore(old.decision.start())
                        && this.decision.end().isBefore(old.decision.end());
            } else {
                return false;
            }
        }
    }
}
