package se.grynna.pager.ui;

import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.splunk.*;
import se.grynna.pager.testutils.MutableClock;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test simulating the login action flow:
 * 1. Set boundaries
 * 2. Jump to latest
 * 3. CursorManager requests visible lines
 * 4. Viewport refreshes
 * <br>
 * This test reproduces the NullPointerException bug where nowBoundary is null
 * when createBoundaryLine is called.
 */
class LoginSimulationTest {

    @TempDir
    Path tempDir;

    private SplunkLogDataProvider dataProvider;
    private CursorManager cursorManager;
    private MutableClock testClock;

    // Test data
    private static final Instant NOW = Instant.parse("2025-11-17T21:04:00Z");
    private static final Instant DAY_START = Instant.parse("2025-11-17T00:00:00Z");
    private static final Instant DAY_END = Instant.parse("2025-11-18T00:00:00Z");

    private final Semaphore loadingSemaphore = new Semaphore(0);
    private final Map<Instant, Event> testEvents = new TreeMap<>();

    @BeforeEach
    void setUp() throws StorageException {
        DateTimeFormatterManagerTestUtil.initDTM();

        // Initialize test clock
        testClock = MutableClock.fixed(NOW, ZoneOffset.UTC);

        // Generate some test logs
        generateTestLogs();

        // Setup mocks

        SplunkClient splunkClient = Mockito.mock(SplunkClient.class);
        DatabaseManager databaseManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        LogViewPort viewPort = mock(LogViewPort.class);

        // Mock SplunkClient to return test data
        try {

            when(splunkClient.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
                var decision = invocation.getArgument(0, LoadDecision.class);
                Instant start = decision.start();
                Instant end = decision.end();

                // Return events in range [start, end)
                List<Event> eventsInRange = testEvents.entrySet().stream()
                        .filter(entry -> !entry.getKey().isBefore(start) && entry.getKey().isBefore(end))
                        .map(Map.Entry::getValue).toList();

                return eventsInRange.stream();
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup mock", e);
        }

        // Create data provider
        dataProvider = new SplunkLogDataProvider(new LogEventMapper(), databaseManager,
                () -> loadingSemaphore.release(), () -> splunkClient, () -> "query", testClock,
                new SearchBoundaries(testClock));

        // Create cursor manager
        cursorManager = new CursorManager(dataProvider, viewPort);
        cursorManager.updateSize(25, 80); // Standard terminal size
    }

    private void generateTestLogs() {
        // Generate 100 logs in the last hour before NOW
        Instant logTime = NOW.minus(60, MINUTES);
        for (int i = 0; i < 100; i++) {
            logTime = logTime.plus(36, java.time.temporal.ChronoUnit.SECONDS); // ~36 seconds apart
            Event event = createMockEvent(logTime, i);
            testEvents.put(logTime, event);
        }
    }

    /**
     * Create a Splunk Event with the fields LogEventMapper reads.
     */
    private Event createMockEvent(Instant timestamp, int index) {
        String rawJson = String.format("{\"message\":\"Test log message %d\",\"level\":\"INFO\",\"@timestamp\":\"%s\"}",
                index, timestamp);
        return TestEvents.event(Map.of("_time", timestamp.toString(), "_raw", rawJson, "_indextime",
                String.valueOf(timestamp.toEpochMilli()), "_bkt", "bkt1", "_cd", "1:" + index, "level", "INFO"));
    }

    @Test
    @DisplayName("Login simulation: jump to latest and refresh visible lines")
    void loginSimulation_jumpToLatestAndRefresh() throws Exception {
        // Simulate login flow

        // Step 1: Set search boundaries (done by IndexPanel)
        dataProvider.setBeforeBoundary(DAY_START);
        dataProvider.setAfterBoundary(DAY_END);

        // Step 2: Jump to latest (done by loginComplete)
        // This starts loading in background but returns immediately
        int line = dataProvider.jumpToLatest();

        // Step 3: Position cursor (golden ratio from bottom) BEFORE waiting for load
        // This is what the real UI does - it positions the cursor immediately
        int linesPerPage = cursorManager.getLinesPerPage();
        int topLine = line - (linesPerPage * 3) / 5; // This will be negative, like -15
        cursorManager.setPageStart(topLine);

        // Step 4: Request visible lines IMMEDIATELY, while data is still loading
        // In the real app, this happens in LogViewer.loginComplete() -> updateVisibleLines()
        // The segment exists but has size 0, so lines outside [0,0) will hit the NO_LATER_THAN boundary
        // nowBoundary is initialized in jump(), so this does not throw NullPointerException

        List<DecoratedLogLine> visibleLines = cursorManager.getVisibleLines();

        // Verify we got some lines (will be placeholders or boundary lines since data is loading)
        assertThat(visibleLines).as("Visible lines should be returned without NPE").isNotNull()
                .hasSizeLessThanOrEqualTo(linesPerPage);

        // Clean up: wait for load to complete
        loadingSemaphore.tryAcquire(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("getNowBoundary should initialize nowBoundary if null")
    void getNowBoundary_shouldInitializeIfNull() {
        // Set search boundaries
        dataProvider.setBeforeBoundary(DAY_START);
        dataProvider.setAfterBoundary(DAY_END);

        // Get now boundary before any jump
        Boundary nowBoundary = dataProvider.getNowBoundary();

        // Should not be null
        assertThat(nowBoundary).as("nowBoundary should be initialized").isNotNull();
        assertThat(nowBoundary.instant()).as("nowBoundary should be NOW - 5 minutes").isEqualTo(NOW.minus(5, MINUTES));
    }

    @Test
    @DisplayName("Jump to latest should initialize nowBoundary")
    void jumpToLatest_shouldInitializeNowBoundary() throws Exception {
        // Set search boundaries
        dataProvider.setBeforeBoundary(DAY_START);
        dataProvider.setAfterBoundary(DAY_END);

        // Jump to latest
        dataProvider.jumpToLatest();

        // Wait for load
        boolean loaded = loadingSemaphore.tryAcquire(5, TimeUnit.SECONDS);
        assertThat(loaded).isTrue();

        // Now boundary should be initialized
        Boundary nowBoundary = dataProvider.getNowBoundary();
        assertThat(nowBoundary).as("nowBoundary should be initialized after jump").isNotNull();
    }

    @Test
    @DisplayName("getLine beyond segment should return boundary line, not throw NPE")
    void getLine_beyondSegment_shouldReturnBoundaryLine() throws Exception {
        // Set search boundaries
        dataProvider.setBeforeBoundary(DAY_START);
        dataProvider.setAfterBoundary(DAY_END);

        // Jump to latest
        int line = dataProvider.jumpToLatest();

        // Wait for load
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                return loadingSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        });

        // Try to get a line beyond the segment (should hit NO_LATER_THAN boundary)
        // This should NOT throw NullPointerException
        try {
            LogLine logLine = dataProvider.getLine(line + 100, false);
            assertThat(logLine).as("Should return a boundary line").isNotNull();

            // Should be a NowBoundaryLine
            if (logLine instanceof NowBoundaryLine nowBoundaryLine) {
                assertThat(nowBoundaryLine.boundary()).as("NowBoundaryLine should have non-null boundary").isNotNull();
            }
        } catch (NullPointerException e) {
            if (e.getMessage() != null && e.getMessage().contains("boundary must be non-null")) {
                fail("BUG: nowBoundary is null when creating NowBoundaryLine");
            }
            throw e;
        }
    }
}
