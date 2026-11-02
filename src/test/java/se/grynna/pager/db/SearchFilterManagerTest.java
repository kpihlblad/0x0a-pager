package se.grynna.pager.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.db.SearchFilterManager.FieldSummaryUpdateListener;
import se.grynna.pager.db.SearchFilterManager.IndexUpdateListener;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.splunk.*;
import se.grynna.pager.testutils.MutableClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SearchFilterManagerTest {

    private DatabaseManager databaseManager;
    private SearchManager searchManager;
    private SplunkClient splunkClient;
    private SearchFilterManager manager;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        databaseManager = mock(DatabaseManager.class);
        searchManager = mock(SearchManager.class);
        splunkClient = mock(SplunkClient.class);
        fixedClock = Clock.fixed(Instant.now(), Clock.systemUTC().getZone());

        // Setup default mock behaviors
        when(databaseManager.loadIndexes()).thenReturn(new TreeSet<>());
        when(databaseManager.loadFieldSummaries(any())).thenReturn(new TreeSet<>());

        // Return the mocked SplunkClient from SearchManager
        when(searchManager.getSplunkClient()).thenReturn(splunkClient);

        manager = new SearchFilterManager(databaseManager, searchManager, fixedClock);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void testLoadIndexes_FreshFetch() throws Exception {

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        AtomicBoolean fromCache = new AtomicBoolean(true);
        CountDownLatch latch = new CountDownLatch(1);

        // Arrange
        TreeSet<String> expectedIndexes = new TreeSet<>(List.of("index1", "index2", "index3"));

        // Mock the SplunkClient's loadIndexes method
        when(splunkClient.loadIndexes(any(), any())).thenReturn(expectedIndexes);
        when(searchManager.getIndexUpdateListener()).thenReturn((indexes, cache) -> {
            listenerCalled.set(true);
            fromCache.set(cache);
            latch.countDown();
        });

        // Act - Use refreshIndexes() to trigger actual fetch
        CompletableFuture<SortedSet<String>> result = manager.refreshIndexes();
        SortedSet<String> indexes = result.get(5, TimeUnit.SECONDS);

        // Wait for listener
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "Listener should be called within timeout");

        // Assert
        assertEquals(expectedIndexes, indexes);
        assertTrue(listenerCalled.get());
        assertFalse(fromCache.get(), "Should not be from cache on fresh fetch");
        verify(splunkClient, times(1)).loadIndexes(any(), any());
        verify(databaseManager, atLeastOnce()).storeIndexes(any());
    }

    @Test
    void testLoadIndexes_CachedData() throws Exception {
        // Arrange
        TreeSet<String> expectedIndexes = new TreeSet<>(Arrays.asList("index1", "index2"));
        when(splunkClient.loadIndexes(any(), any())).thenReturn(expectedIndexes);
        when(databaseManager.loadIndexes()).thenReturn(new TreeSet<>(), expectedIndexes, expectedIndexes);

        // First fetch to populate cache
        manager.refreshIndexes().get(5, TimeUnit.SECONDS);

        AtomicInteger listenerCallCount = new AtomicInteger(0);
        when(searchManager.getIndexUpdateListener())
                .thenReturn((indexes, cache) -> listenerCallCount.incrementAndGet());

        // Act - Second fetch should use cache
        SortedSet<String> indexes = manager.loadIndexes();

        Thread.sleep(100); // Give listeners time to fire

        // Assert
        assertEquals(expectedIndexes, indexes);
        verify(splunkClient, times(1)).loadIndexes(any(), any()); // Should only call once
    }

    @Test
    void testLoadIndexes_FallbackToDatabase() throws Exception {
        // Arrange
        TreeSet<String> dbIndexes = new TreeSet<>(Arrays.asList("db_index1", "db_index2"));
        when(splunkClient.loadIndexes(any(), any()))
                .thenThrow(new SearchException("Connection failed", new RuntimeException()));
        when(databaseManager.loadIndexes()).thenReturn(dbIndexes);

        // Act - refreshIndexes will fail, but loadIndexes returns DB data
        CompletableFuture<SortedSet<String>> result = manager.refreshIndexes();

        try {
            result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected to fail
        }

        SortedSet<String> indexes = manager.loadIndexes();

        // Assert
        assertEquals(dbIndexes, indexes);
        verify(databaseManager, atLeast(2)).loadIndexes(); // Called in init and in loadIndexes
    }

    @Test
    void testLoadFieldSummaries_FreshFetch() throws Exception {
        // Arrange
        String index = "test_index";
        SortedSet<FieldSummary> expectedSummaries = new TreeSet<>();
        FieldSummary field1 = mock(FieldSummary.class);
        when(field1.getField()).thenReturn("field1");
        when(field1.compareTo(any())).thenCallRealMethod();
        expectedSummaries.add(field1);

        when(splunkClient.loadFieldSummary(eq(index), anyInt(), any(), any())).thenReturn(expectedSummaries);
        when(databaseManager.loadFieldSummaries(index)).thenReturn(new TreeSet<>(), expectedSummaries);

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        when(searchManager.getFieldSummaryUpdateListener()).thenReturn((idx, summaries, cache) -> {
            listenerCalled.set(true);
            latch.countDown();
        });

        // Act
        SortedSet<FieldSummary> summaries = manager.loadFieldSummaries(index, true);

        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "Listener should be called within timeout");

        // Assert
        assertNotNull(summaries);
        assertTrue(listenerCalled.get());
        verify(splunkClient, times(1)).loadFieldSummary(eq(index), anyInt(), any(), any());
    }

    @Test
    void testLoadFieldSummaries_CachedData() throws Exception {
        // Arrange
        String index = "test_index";
        SortedSet<FieldSummary> expectedSummaries = new TreeSet<>();
        SortedSet<FieldSummary> someSummaries = new TreeSet<>();

        expectedSummaries.add(mock(FieldSummary.class));
        someSummaries.add(mock(FieldSummary.class));

        when(splunkClient.loadFieldSummary(eq(index), anyInt(), any(), any())).thenReturn(expectedSummaries);
        when(databaseManager.loadFieldSummaries(eq(index))).thenReturn(someSummaries, expectedSummaries,
                expectedSummaries);

        // First fetch to populate cache
        System.out.println(">>> First fetch to populate cache");
        manager.loadFieldSummaries(index, true);
        Thread.sleep(200);

        AtomicInteger listenerCallCount = new AtomicInteger(0);
        when(searchManager.getFieldSummaryUpdateListener())
                .thenReturn((idx, summaries, cache) -> listenerCallCount.incrementAndGet());

        // Act - Second fetch should use cache
        System.out.println(">>> Second fetch to use cache");
        manager.loadFieldSummaries(index, false);

        Thread.sleep(100); // Give listeners time to fire

        // Assert
        verify(splunkClient, times(1)).loadFieldSummary(eq(index), anyInt(), any(), any()); // Should only call once
    }

    @Test
    void testLoadIndexes_ImmediateReturnFromCache() throws Exception {
        // Arrange
        TreeSet<String> expectedIndexes = new TreeSet<>(Arrays.asList("index1", "index2"));
        when(splunkClient.loadIndexes(any(), any())).thenReturn(expectedIndexes);
        when(databaseManager.loadIndexes()).thenReturn(new TreeSet<>(), expectedIndexes, expectedIndexes);

        // First fetch to populate cache
        manager.refreshIndexes().get(5, TimeUnit.SECONDS);

        // Act - Second fetch should return immediately from cache
        long startTime = System.currentTimeMillis();

        SortedSet<String> indexes = manager.loadIndexes();
        long duration = System.currentTimeMillis() - startTime;

        // Assert
        assertEquals(expectedIndexes, indexes);
        assertTrue(duration < 100, "Should return almost instantly from cache, took: " + duration + "ms");
        verify(splunkClient, times(1)).loadIndexes(any(), any());
    }

    @Test
    void testFieldFilters() throws Exception {
        // Arrange
        String index = "test_index";
        Map<String, List<String>> filters = new HashMap<>();
        filters.put("field1", List.of("value1"));
        filters.put("field2", List.of("value2"));

        when(databaseManager.loadFieldFiltersForIndex(eq(index))).thenReturn(filters);

        // Act
        manager.storeFieldFilters(index, filters).get(5, TimeUnit.SECONDS);
        Map<String, List<String>> result = manager.getFieldFilters(index);

        // Assert
        assertEquals(filters, result);
        verify(databaseManager).storeFieldFiltersForIndex(eq(index), eq(filters));
        verify(databaseManager).loadFieldFiltersForIndex(eq(index));
    }

    @Test
    void testMultipleListeners() throws Exception {
        // Arrange
        TreeSet<String> expectedIndexes = new TreeSet<>(Collections.singletonList("index1"));
        when(splunkClient.loadIndexes(any(), any())).thenReturn(expectedIndexes);
        when(databaseManager.loadIndexes()).thenReturn(new TreeSet<>(), expectedIndexes);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);

        // Setup listener from searchManager
        when(searchManager.getIndexUpdateListener()).thenReturn((indexes, cache) -> {
            callCount.incrementAndGet();
            latch.countDown();
        });

        // Act
        manager.refreshIndexes().get(5, TimeUnit.SECONDS);
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "Listener should complete within timeout");

        // Assert
        assertTrue(callCount.get() >= 1, "Listener should be called at least once");
    }

    @Test
    void testListenerCalledOnRefresh() throws Exception {
        // Arrange
        TreeSet<String> expectedIndexes = new TreeSet<>(Collections.singletonList("index1"));
        when(splunkClient.loadIndexes(any(), any())).thenReturn(expectedIndexes);
        when(databaseManager.loadIndexes()).thenReturn(new TreeSet<>(), expectedIndexes, expectedIndexes);

        AtomicInteger callCount = new AtomicInteger(0);
        when(searchManager.getIndexUpdateListener()).thenReturn((indexes, cache) -> callCount.incrementAndGet());

        // Act - First refresh
        manager.refreshIndexes().get(5, TimeUnit.SECONDS);
        Thread.sleep(100);

        int firstCount = callCount.get();
        assertTrue(firstCount >= 1, "Listener should be called at least once");

        // Act - Load from cache (no refresh)
        manager.loadIndexes();
        Thread.sleep(100);

        // Assert - should not call listener again since data is from cache
        assertEquals(firstCount, callCount.get(), "Listener should not be called when using cached data");
    }

    @Test
    void testFieldMerging() throws TemporarySearchException, SearchException {
        // Arrange
        String index = "test_index";

        var fieldSummaryUpdateListener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(fieldSummaryUpdateListener);

        FieldSummary field1 = mock(FieldSummary.class);
        FieldSummary field2 = mock(FieldSummary.class);
        when(field1.getField()).thenReturn("field1");
        when(field2.getField()).thenReturn("field2");
        when(field1.compareTo(any())).thenCallRealMethod();
        when(field2.compareTo(any())).thenCallRealMethod();

        SortedSet<FieldSummary> firstFetch = new TreeSet<>(Arrays.asList(field1, field2));
        when(splunkClient.loadFieldSummary(eq(index), anyInt(), any(), any())).thenReturn(firstFetch);
        when(databaseManager.loadFieldSummaries(index)).thenReturn(new TreeSet<>(), firstFetch);

        // Act
        manager.loadFieldSummaries(index, true);

        // validate that the listener was called with correct parameters
        verify(fieldSummaryUpdateListener, timeout(3000).times(1)).onFieldSummariesUpdated(eq(index), eq(firstFetch),
                eq(false));
    }

    @Test
    void testConcurrentAccess() throws Exception {
        // Arrange
        TreeSet<String> expectedIndexes = new TreeSet<>(Arrays.asList("index1", "index2"));
        when(splunkClient.loadIndexes(any(), any())).thenReturn(expectedIndexes);
        when(databaseManager.loadIndexes()).thenReturn(expectedIndexes);

        // Act - Multiple concurrent requests - all should get the same database result
        manager.loadIndexes();
        manager.loadIndexes();
        manager.loadIndexes();

        // Should call database multiple times but Splunk only once or zero times if cache is valid
        verify(splunkClient, atMost(1)).loadIndexes(any(), any());
    }

    @Test
    void testForceRefresh() throws Exception {
        // Arrange
        TreeSet<String> expectedIndexes = new TreeSet<>(Arrays.asList("index1", "index2"));
        when(splunkClient.loadIndexes(any(), any())).thenReturn(expectedIndexes);
        when(databaseManager.loadIndexes()).thenReturn(new TreeSet<>(), expectedIndexes, expectedIndexes);

        // First fetch
        manager.refreshIndexes().get(5, TimeUnit.SECONDS);

        // A plain load uses the nonempty, fresh cache
        manager.loadIndexes();
        Thread.sleep(100);
        verify(splunkClient, times(1)).loadIndexes(any(), any());

        // Act - An explicit refresh fetches anyway
        manager.refreshIndexes().get(5, TimeUnit.SECONDS);

        // Assert
        verify(splunkClient, times(2)).loadIndexes(any(), any());
    }

    @Test
    void testEmptyIndexReturnsEmptySet() throws TemporarySearchException, SearchException {
        // Act
        SortedSet<FieldSummary> summaries = manager.loadFieldSummaries("", false);

        // Assert
        assertTrue(summaries.isEmpty());
        verify(splunkClient, never()).loadFieldSummary(anyString(), anyInt(), any(), any());
    }

    @Test
    void testNullIndexReturnsEmptySet() throws TemporarySearchException, SearchException {
        // Act
        SortedSet<FieldSummary> summaries = manager.loadFieldSummaries(null, false);

        // Assert
        assertTrue(summaries.isEmpty());
        verify(splunkClient, never()).loadFieldSummary(anyString(), anyInt(), any(), any());
    }

    // --- Refresh ownership and obsolete completions ---

    private static SortedSet<FieldSummary> summaries(String... fields) {
        SortedSet<FieldSummary> set = new TreeSet<>();
        for (String field : fields) {
            FieldSummary summary = mock(FieldSummary.class);
            when(summary.getField()).thenReturn(field);
            when(summary.compareTo(any())).thenCallRealMethod();
            set.add(summary);
        }
        return set;
    }

    /** Blocks until released, ignoring interrupts, like a query that finishes despite cancellation. */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                if (latch.await(5, TimeUnit.SECONDS)) {
                    break;
                }
                throw new AssertionError("latch was never released");
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void reversedCompletionOrder_onlyTheLatestFieldRefreshPublishes() throws Exception {
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        SortedSet<FieldSummary> older = summaries("old");
        SortedSet<FieldSummary> newer = summaries("new");
        CountDownLatch releaseOlder = new CountDownLatch(1);
        CountDownLatch olderStarted = new CountDownLatch(1);
        AtomicBoolean olderReturned = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        when(splunkClient.loadFieldSummary(eq("A"), anyInt(), any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                olderStarted.countDown();
                awaitUninterruptibly(releaseOlder);
                olderReturned.set(true);
                return older;
            }
            return newer;
        });

        manager.loadFieldSummaries("A", true);
        assertTrue(olderStarted.await(2, TimeUnit.SECONDS));
        manager.loadFieldSummaries("A", true);

        verify(databaseManager, timeout(2000)).storeFieldSummaries(eq("A"), same(newer));
        verify(listener, timeout(2000)).onFieldSummariesUpdated(eq("A"), any(), eq(false));

        releaseOlder.countDown();
        await(olderReturned);
        Thread.sleep(100);

        verify(databaseManager, never()).storeFieldSummaries(any(), same(older));
        verify(listener, times(1)).onFieldSummariesUpdated(any(), any(), anyBoolean());
        verify(listener, never()).onFieldSummaryRefreshFailed(any(), any());
    }

    @Test
    void refreshForAnotherIndexSupersedesTheRunningOne() throws Exception {
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch aStarted = new CountDownLatch(1);
        AtomicBoolean aReturned = new AtomicBoolean();
        SortedSet<FieldSummary> aFields = summaries("a");
        when(splunkClient.loadFieldSummary(eq("A"), anyInt(), any(), any())).thenAnswer(inv -> {
            aStarted.countDown();
            awaitUninterruptibly(releaseA);
            aReturned.set(true);
            return aFields;
        });
        SortedSet<FieldSummary> stub1 = summaries("b");
        when(splunkClient.loadFieldSummary(eq("B"), anyInt(), any(), any())).thenReturn(stub1);

        manager.loadFieldSummaries("A", false);
        assertTrue(aStarted.await(2, TimeUnit.SECONDS));
        manager.loadFieldSummaries("B", false);
        verify(listener, timeout(2000)).onFieldSummariesUpdated(eq("B"), any(), eq(false));

        releaseA.countDown();
        await(aReturned);
        Thread.sleep(100);

        verify(databaseManager, never()).storeFieldSummaries(eq("A"), any());
        verify(listener, never()).onFieldSummariesUpdated(eq("A"), any(), anyBoolean());
    }

    @Test
    void supersededRefreshIsInterruptedAndItsJobsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<JobTicket> ticket = new AtomicReference<>();
        when(splunkClient.loadFieldSummary(eq("A"), anyInt(), any(), any())).thenAnswer(inv -> {
            ticket.set(inv.getArgument(3));
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new SearchCancelledException("interrupted", e);
            }
            return summaries();
        });
        SortedSet<FieldSummary> stub2 = summaries();
        when(splunkClient.loadFieldSummary(eq("B"), anyInt(), any(), any())).thenReturn(stub2);

        manager.loadFieldSummaries("A", false);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        manager.loadFieldSummaries("B", false);

        assertTrue(interrupted.await(2, TimeUnit.SECONDS), "superseded fetch should be interrupted");
        verify(splunkClient).cancelJobs(ticket.get());
    }

    @Test
    void unforcedLoadJoinsARunningRefreshForTheSameIndex() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(splunkClient.loadFieldSummary(eq("A"), anyInt(), any(), any())).thenAnswer(inv -> {
            awaitUninterruptibly(release);
            return summaries("a");
        });

        manager.loadFieldSummaries("A", false);
        manager.loadFieldSummaries("A", false);
        release.countDown();

        verify(splunkClient, timeout(2000).times(1)).loadFieldSummary(eq("A"), anyInt(), any(), any());
        Thread.sleep(100);
        verify(splunkClient, times(1)).loadFieldSummary(eq("A"), anyInt(), any(), any());
    }

    @Test
    void noRefreshWritesOrNotifiesAfterShutdown() throws Exception {
        var indexListener = mock(IndexUpdateListener.class);
        when(searchManager.getIndexUpdateListener()).thenReturn(indexListener);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        when(splunkClient.loadIndexes(any(), any())).thenAnswer(inv -> {
            awaitUninterruptibly(release);
            returned.set(true);
            return new TreeSet<>(List.of("late"));
        });

        CompletableFuture<SortedSet<String>> refresh = manager.refreshIndexes();
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(manager::shutdown);
        await(refresh::isCancelled);
        release.countDown();
        shutdown.get(10, TimeUnit.SECONDS);
        await(returned);

        verify(databaseManager, never()).storeIndexes(any());
        verify(indexListener, never()).onIndexesUpdated(any(), anyBoolean());
        verify(indexListener, never()).onIndexRefreshFailed(any());
        manager = null;
    }

    // --- Failures reach the listener ---

    @Test
    void failedIndexRefreshIsReported() throws Exception {
        var listener = mock(IndexUpdateListener.class);
        when(searchManager.getIndexUpdateListener()).thenReturn(listener);
        SearchException failure = new SearchException("permission denied", new RuntimeException());
        when(splunkClient.loadIndexes(any(), any())).thenThrow(failure);

        CompletableFuture<SortedSet<String>> refresh = manager.refreshIndexes();

        verify(listener, timeout(2000)).onIndexRefreshFailed(same(failure));
        verify(listener, never()).onIndexesUpdated(any(), anyBoolean());
        // The discarded refresh future still surfaces the failure to its observer.
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> refresh.get(5, TimeUnit.SECONDS));
        assertSame(failure, thrown.getCause());
    }

    @Test
    void failedFieldRefreshIsReportedAndRetryWorks() throws Exception {
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        SearchCancelledException failure = new SearchCancelledException("authentication cancelled", null);
        SortedSet<FieldSummary> stub3 = summaries("a");
        when(splunkClient.loadFieldSummary(eq("A"), anyInt(), any(), any())).thenThrow(failure).thenReturn(stub3);

        manager.loadFieldSummaries("A", false);
        verify(listener, timeout(2000)).onFieldSummaryRefreshFailed(eq("A"), same(failure));

        manager.loadFieldSummaries("A", true);
        verify(listener, timeout(2000)).onFieldSummariesUpdated(eq("A"), any(), eq(false));
    }

    @Test
    void supersededFailureIsNotReported() throws Exception {
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        when(splunkClient.loadFieldSummary(eq("A"), anyInt(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            awaitUninterruptibly(release);
            failed.set(true);
            throw new SearchException("late failure", new RuntimeException());
        });
        SortedSet<FieldSummary> stub4 = summaries("b");
        when(splunkClient.loadFieldSummary(eq("B"), anyInt(), any(), any())).thenReturn(stub4);

        manager.loadFieldSummaries("A", false);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        manager.loadFieldSummaries("B", false);
        verify(listener, timeout(2000)).onFieldSummariesUpdated(eq("B"), any(), eq(false));
        release.countDown();
        await(failed);
        Thread.sleep(100);

        verify(listener, never()).onFieldSummaryRefreshFailed(any(), any());
    }

    // --- Forced refresh and cache expiry ---

    @Test
    void indexCacheRefreshesWhenExpiredButNotWhileFresh() throws Exception {
        manager.shutdown();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        manager = new SearchFilterManager(databaseManager, searchManager, clock);
        TreeSet<String> cached = new TreeSet<>(List.of("index1"));
        when(databaseManager.loadIndexes()).thenReturn(cached);
        when(splunkClient.loadIndexes(any(), any())).thenReturn(cached);

        // Nothing refreshed in this session yet: persisted data is stale
        manager.loadIndexes();
        verify(splunkClient, timeout(2000).times(1)).loadIndexes(any(), any());
        awaitNoRefreshRunning();

        clock.fastForward(SearchFilterManager.CACHE_TTL.minusSeconds(1));
        manager.loadIndexes();
        Thread.sleep(100);
        verify(splunkClient, times(1)).loadIndexes(any(), any());

        clock.fastForward(Duration.ofSeconds(2));
        manager.loadIndexes();
        verify(splunkClient, timeout(2000).times(2)).loadIndexes(any(), any());
    }

    @Test
    void fieldCacheFreshnessIsTrackedPerIndex() throws Exception {
        manager.shutdown();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        manager = new SearchFilterManager(databaseManager, searchManager, clock);
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        SortedSet<FieldSummary> stub5 = summaries("f");
        when(databaseManager.loadFieldSummaries(any())).thenReturn(stub5);
        SortedSet<FieldSummary> stub6 = summaries("f");
        when(splunkClient.loadFieldSummary(any(), anyInt(), any(), any())).thenReturn(stub6);

        manager.loadFieldSummaries("A", false);
        verify(listener, timeout(2000)).onFieldSummariesUpdated(eq("A"), any(), eq(false));

        clock.fastForward(Duration.ofMinutes(3));
        manager.loadFieldSummaries("A", false); // fresh: no fetch
        manager.loadFieldSummaries("B", false); // never refreshed: fetch
        verify(listener, timeout(2000)).onFieldSummariesUpdated(eq("B"), any(), eq(false));
        verify(splunkClient, times(1)).loadFieldSummary(eq("A"), anyInt(), any(), any());

        clock.fastForward(Duration.ofMinutes(3)); // A expired, B still fresh
        manager.loadFieldSummaries("B", false);
        Thread.sleep(100);
        verify(splunkClient, times(1)).loadFieldSummary(eq("B"), anyInt(), any(), any());
        manager.loadFieldSummaries("A", false);
        verify(splunkClient, timeout(2000).times(2)).loadFieldSummary(eq("A"), anyInt(), any(), any());
    }

    @Test
    void forcedFieldRefreshFetchesAFreshNonemptyCache() throws Exception {
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        SortedSet<FieldSummary> stub7 = summaries("f");
        when(databaseManager.loadFieldSummaries(any())).thenReturn(stub7);
        SortedSet<FieldSummary> stub8 = summaries("f");
        when(splunkClient.loadFieldSummary(any(), anyInt(), any(), any())).thenReturn(stub8);

        manager.loadFieldSummaries("A", false);
        verify(listener, timeout(2000)).onFieldSummariesUpdated(eq("A"), any(), eq(false));
        manager.loadFieldSummaries("A", true);

        verify(splunkClient, timeout(2000).times(2)).loadFieldSummary(eq("A"), anyInt(), any(), any());
    }

    @Test
    void deepeningSamplesDeeperEachTimeUpToTheMaximumPerIndex() throws Exception {
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        SortedSet<FieldSummary> cached = summaries("f");
        SortedSet<FieldSummary> fetched = summaries("f");
        when(databaseManager.loadFieldSummaries(any())).thenReturn(cached);
        when(splunkClient.loadFieldSummary(any(), anyInt(), any(), any())).thenReturn(fetched);

        manager.loadFieldSummaries("A", false);
        verify(splunkClient, timeout(2000)).loadFieldSummary(eq("A"), eq(0), any(), any());
        assertEquals(10_000, manager.fieldSampleSize("A"));

        for (int depth = 1; depth <= FieldSampling.MAX_DEPTH + 1; depth++) {
            manager.deepenFieldSummaries("A");
            int expected = Math.min(depth, FieldSampling.MAX_DEPTH);
            verify(splunkClient, timeout(2000).atLeastOnce()).loadFieldSummary(eq("A"), eq(expected), any(), any());
            verify(listener, timeout(2000).times(depth + 1)).onFieldSummariesUpdated(eq("A"), any(), eq(false));
        }
        assertEquals(1_000_000, manager.fieldSampleSize("A"));

        // The depth stays for later refreshes of A and does not leak to other indexes
        manager.loadFieldSummaries("A", true);
        verify(splunkClient, timeout(2000).times(3)).loadFieldSummary(eq("A"), eq(FieldSampling.MAX_DEPTH), any(),
                any());
        manager.loadFieldSummaries("B", false);
        verify(splunkClient, timeout(2000)).loadFieldSummary(eq("B"), eq(0), any(), any());
        assertEquals(10_000, manager.fieldSampleSize("B"));
    }

    @Test
    void scopeQueryCacheReusesFreshResultAndInvalidatesOnDeepen() throws Exception {
        var listener = mock(FieldSummaryUpdateListener.class);
        when(searchManager.getFieldSummaryUpdateListener()).thenReturn(listener);
        Scope filtered = Scope.of("A", Map.of("app", "orders")).withNarrow("timeout");
        SortedSet<FieldSummary> first = summaries("pod", "level");
        SortedSet<FieldSummary> deeper = summaries("pod", "level", "rare");
        when(splunkClient.loadFieldSummaryForScope(eq(filtered), eq(0), any(), any())).thenReturn(first);
        when(splunkClient.loadFieldSummaryForScope(eq(filtered), eq(1), any(), any())).thenReturn(deeper);

        assertTrue(manager.loadFieldSummariesForScope(filtered, false).isEmpty());
        verify(listener, timeout(2000)).onScopeFieldSummariesUpdated(eq(filtered), eq(first), eq(false));
        verify(splunkClient, times(1)).loadFieldSummaryForScope(eq(filtered), eq(0), any(), any());

        // Cache hit for identical query does not re-run calculation
        SortedSet<FieldSummary> cached = manager.loadFieldSummariesForScope(filtered, false);
        assertEquals(first, cached);
        Thread.sleep(100);
        verify(splunkClient, times(1)).loadFieldSummaryForScope(eq(filtered), eq(0), any(), any());

        // Deepening invalidates the cached query entry for the index and re-samples at depth 1
        assertTrue(manager.deepenFieldSummariesForScope(filtered).isEmpty());
        verify(splunkClient, timeout(2000).times(1)).loadFieldSummaryForScope(eq(filtered), eq(1), any(), any());
        verify(listener, timeout(2000).times(2)).onScopeFieldSummariesUpdated(eq(filtered), any(), eq(false));
        assertEquals(deeper, manager.loadFieldSummariesForScope(filtered, false));
    }

    private void awaitNoRefreshRunning() throws Exception {
        // The refresh finishes (and records its time) right after it stores the result
        verify(databaseManager, timeout(2000).atLeastOnce()).storeIndexes(any());
        Thread.sleep(50);
    }

    // --- Filter snapshots are ordered and visible before they are written ---

    @Test
    void pendingFilterSnapshotIsVisibleAndWritesStayInOrder() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            awaitUninterruptibly(release);
            return null;
        }).when(databaseManager).storeFieldFiltersForIndex(eq("A"), eq(Map.of("app", List.of("1"))));

        var first = manager.storeFieldFilters("A", Map.of("app", List.of("1")));
        var second = manager.storeFieldFilters("A", Map.of("app", List.of("2")));

        assertEquals(Map.of("app", List.of("2")), manager.getFieldFilters("A"));
        verify(databaseManager, never()).loadFieldFiltersForIndex(any());

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        var inOrder = inOrder(databaseManager);
        inOrder.verify(databaseManager).storeFieldFiltersForIndex("A", Map.of("app", List.of("1")));
        inOrder.verify(databaseManager).storeFieldFiltersForIndex("A", Map.of("app", List.of("2")));

        when(databaseManager.loadFieldFiltersForIndex("A")).thenReturn(Map.of("app", List.of("2")));
        assertEquals(Map.of("app", List.of("2")), manager.getFieldFilters("A"));
        verify(databaseManager).loadFieldFiltersForIndex("A");
    }

    @Test
    void pendingScopeTermsAreVisibleAndWritesStayInOrder() throws Exception {
        ScopeTerms one = new ScopeTerms(List.of("a"), List.of());
        ScopeTerms two = new ScopeTerms(List.of("a"), List.of("b"));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            awaitUninterruptibly(release);
            return null;
        }).when(databaseManager).storeScopeTermsForIndex(eq("A"), eq(one));

        var first = manager.storeScopeTerms("A", one);
        var second = manager.storeScopeTerms("A", two);

        assertEquals(two, manager.getScopeTerms("A"));
        verify(databaseManager, never()).loadScopeTermsForIndex(any());

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);

        var inOrder = inOrder(databaseManager);
        inOrder.verify(databaseManager).storeScopeTermsForIndex("A", one);
        inOrder.verify(databaseManager).storeScopeTermsForIndex("A", two);

        when(databaseManager.loadScopeTermsForIndex("A")).thenReturn(two);
        assertEquals(two, manager.getScopeTerms("A"));
        verify(databaseManager).loadScopeTermsForIndex("A");
    }

    @Test
    void shutdownFlushesQueuedFilterWrites() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            awaitUninterruptibly(release);
            return null;
        }).when(databaseManager).storeFieldFiltersForIndex(eq("A"), any());

        var firstWrite = manager.storeFieldFilters("A", Map.of("app", List.of("1")));
        var secondWrite = manager.storeFieldFilters("B", Map.of("app", List.of("2")));
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(manager::shutdown);
        Thread.sleep(100);
        release.countDown();
        shutdown.get(10, TimeUnit.SECONDS);
        firstWrite.get(5, TimeUnit.SECONDS);
        secondWrite.get(5, TimeUnit.SECONDS);
        manager = null;

        verify(databaseManager).storeFieldFiltersForIndex("A", Map.of("app", List.of("1")));
        verify(databaseManager).storeFieldFiltersForIndex("B", Map.of("app", List.of("2")));
    }

    private static void await(AtomicBoolean flag) {
        await(flag::get);
    }

    @Test
    void messageFieldsPendingSnapshotIsVisibleBeforeTheWriteAndTheDefaultIsStoredAsNull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(databaseManager).storeMessageFieldsForIndex(eq("web"), any());
        when(databaseManager.loadMessageFieldsForIndex("web")).thenReturn(Optional.empty());

        CompletableFuture<Void> write = manager.storeMessageFields("web", List.of("title"));

        assertEquals(Optional.of(List.of("title")), manager.getMessageFields("web"));
        verify(databaseManager, never()).loadMessageFieldsForIndex(any());

        release.countDown();
        write.get(5, TimeUnit.SECONDS);
        verify(databaseManager).storeMessageFieldsForIndex("web", List.of("title"));
        assertEquals(Optional.empty(), manager.getMessageFields("web"));
        verify(databaseManager).loadMessageFieldsForIndex("web");

        manager.storeMessageFields("web", null).get(5, TimeUnit.SECONDS);
        verify(databaseManager).storeMessageFieldsForIndex("web", null);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(condition::getAsBoolean);
    }
}
