package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the BinarySearchClosest algorithm using a simple in-memory data structure.
 * No mocking needed - tests the algorithm directly with real data.
 */
class BinarySearchClosestTest {

    /**
     * Test exact match - search timestamp exactly matches a log entry timestamp
     */
    @Test
    void search_exactMatch() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 0, 10, 20, 30, 40);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for exact match at index 2 (20 seconds)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(20));
        int result = search.search(searchKey, 0, 4);

        assertEquals(2, result, "Should find exact match at index 2");
    }

    /**
     * Test when searched timestamp is between two entries - closer to lower entry
     */
    @Test
    void search_betweenTwoEntries_closerToLower() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 0, 10, 20, 30, 40);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for 22 seconds (2 sec after index 2, 8 sec before index 3)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(22));
        int result = search.search(searchKey, 0, 4);

        assertEquals(2, result, "Should return closest match (index 2) when between entries");
    }

    /**
     * Test when searched timestamp is between two entries - closer to upper entry
     */
    @Test
    void search_betweenTwoEntries_closerToUpper() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 0, 10, 20, 30, 40);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for 28 seconds (8 sec after index 2, 2 sec before index 3)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(28));
        int result = search.search(searchKey, 0, 4);

        assertEquals(3, result, "Should return closest match (index 3) when between entries");
    }

    /**
     * Test when searched timestamp is exactly halfway between two entries
     */
    @Test
    void search_exactlyBetweenTwoEntries() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 0, 10, 20, 30, 40);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for 25 seconds (exactly between index 2 and 3)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(25));
        int result = search.search(searchKey, 0, 4);

        assertTrue(result == 2 || result == 3,
                "Should return either index 2 or 3 when exactly halfway between entries");
    }

    /**
     * Test search for timestamp before all entries
     */
    @Test
    void search_beforeAllEntries() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 10, 20, 30);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for 5 seconds (before all entries)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(5));
        int result = search.search(searchKey, 0, 2);

        assertEquals(0, result, "Should return first entry when searched time is before all entries");
    }

    /**
     * Test search for timestamp after all entries
     */
    @Test
    void search_afterAllEntries() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 10, 20, 30);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for 50 seconds (after all entries)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(50));
        int result = search.search(searchKey, 0, 2);

        assertEquals(2, result, "Should return last entry when searched time is after all entries");
    }

    /**
     * Test with single entry
     */
    @Test
    void search_singleEntry() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 20);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for different timestamp
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(25));
        int result = search.search(searchKey, 0, 0);

        assertEquals(0, result, "Should return the only entry when there's a single entry");
    }

    /**
     * Test with two entries - find closest to first
     */
    @Test
    void search_twoEntries_closerToFirst() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 10, 30);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for 12 seconds (closer to first entry)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(12));
        int result = search.search(searchKey, 0, 1);

        assertEquals(0, result, "Should return first entry when closer to it");
    }

    /**
     * Test with two entries - find closest to second
     */
    @Test
    void search_twoEntries_closerToSecond() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = createLogLines(baseTime, 10, 30);

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for 28 seconds (closer to second entry)
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(28));
        int result = search.search(searchKey, 0, 1);

        assertEquals(1, result, "Should return second entry when closer to it");
    }

    /**
     * Test with larger dataset to ensure algorithm scales
     */
    @Test
    void search_largeDataset() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");

        // Create 100 entries at 1-second intervals
        List<LoadedLogLine> logLines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            logLines.add(createLogLine(i, baseTime.plusSeconds(i)));
        }

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        // Search for exact match at index 50
        LoadedLogLine searchKey = createSearchKey(baseTime.plusSeconds(50));
        int result = search.search(searchKey, 0, 99);
        assertEquals(50, result, "Should find exact match in large dataset");

        // Search for value between entries (closer to 75)
        searchKey = createSearchKey(baseTime.plusSeconds(74).plusMillis(800));
        result = search.search(searchKey, 0, 99);
        assertEquals(75, result, "Should find closest match in large dataset");
    }

    @Test
    void search_preservesNanosecondPrecisionForExactAndNearestMatches() throws Exception {
        Instant baseTime = Instant.parse("2026-01-02T10:00:00Z");
        List<LoadedLogLine> logLines = List.of(createLogLine(0, baseTime.plusNanos(100)),
                createLogLine(1, baseTime.plusNanos(200)), createLogLine(2, baseTime.plusNanos(300)));

        BinarySearchClosest<LoadedLogLine> search = createBinarySearch(logLines);

        assertEquals(2, search.search(createSearchKey(baseTime.plusNanos(300)), 0, 2),
                "an exact nanosecond match must win");
        assertEquals(2, search.search(createSearchKey(baseTime.plusNanos(260)), 0, 2),
                "260ns is nearer 300ns than 200ns");
        assertEquals(1, search.search(createSearchKey(baseTime.plusNanos(240)), 0, 2),
                "240ns is nearer 200ns than 300ns");
    }

    // Helper methods

    /**
     * Create a list of LoadedLogLine objects with specified second offsets from base time
     */
    private List<LoadedLogLine> createLogLines(Instant baseTime, int... secondOffsets) {
        List<LoadedLogLine> lines = new ArrayList<>();
        for (int i = 0; i < secondOffsets.length; i++) {
            lines.add(createLogLine(i, baseTime.plusSeconds(secondOffsets[i])));
        }
        return lines;
    }

    /**
     * Create a single LoadedLogLine with the given line number and timestamp
     */
    private LoadedLogLine createLogLine(int lineNumber, Instant timestamp) {
        LoadedLogLine line = new LoadedLogLine();
        line.setLineNumber(lineNumber);
        line.setTime(timestamp);
        return line;
    }

    /**
     * Create a search key (LoadedLogLine with only timestamp set)
     */
    private LoadedLogLine createSearchKey(Instant timestamp) {
        LoadedLogLine key = new LoadedLogLine();
        key.setTime(timestamp);
        return key;
    }

    /**
     * Create a BinarySearchClosest instance configured for LoadedLogLine timestamp search
     */
    private BinarySearchClosest<LoadedLogLine> createBinarySearch(List<LoadedLogLine> logLines) {
        // Element provider using the list
        BinarySearchClosest.ElementProvider<LoadedLogLine> provider = logLines::get;

        // Comparator for comparing log lines by timestamp
        Comparator<LoadedLogLine> comparator = Comparator.comparing(LoadedLogLine::getTime);

        // Distance calculator for finding closest match
        BinarySearchClosest.DistanceCalculator<LoadedLogLine> distanceCalculator = (key, element) -> Duration
                .between(key.getTime(), element.getTime()).abs();

        return new BinarySearchClosest<>(provider, comparator, distanceCalculator);
    }
}
