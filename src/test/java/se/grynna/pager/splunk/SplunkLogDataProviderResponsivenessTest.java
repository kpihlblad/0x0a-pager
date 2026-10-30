package se.grynna.pager.splunk;

import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SplunkLogDataProviderResponsivenessTest {
    @TempDir
    Path tempDir;

    @Test
    void stalledRequestDoesNotBlockEdtBrowsingOrCancellationAndLateDataIsDiscarded() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        Instant target = Instant.parse("2017-03-30T10:00:00Z");
        SplunkClient client = mock(SplunkClient.class);
        CountDownLatch firstLoaded = new CountDownLatch(1);
        CountDownLatch extensionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtension = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger queries = new AtomicInteger();
        when(client.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            LoadDecision decision = invocation.getArgument(0);
            if (queries.incrementAndGet() == 1) {
                return Stream.of(event(target));
            }
            extensionStarted.countDown();
            // Model a socket read that does not respond to Future.cancel(true).
            boolean released = false;
            while (!released) {
                try {
                    releaseExtension.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // The test releases the request explicitly in finally.
                }
            }
            return Stream.of(event(decision.start().plusNanos(1)));
        });
        DatabaseManager database = new DatabaseManager("test", "test", "test", tempDir.toFile());
        Clock clock = Clock.fixed(target.plusSeconds(3600), ZoneOffset.UTC);
        SplunkLogDataProvider provider = new SplunkLogDataProvider(new LogEventMapper(), database, () -> {
            firstLoaded.countDown();
            completed.countDown();
        }, () -> client, () -> "index=test", clock, new SearchBoundaries(clock));
        try {
            provider.jump(target);
            assertThat(firstLoaded.await(3, SECONDS)).as("initial rows are cached").isTrue();
            provider.getLine(1, false);
            assertThat(extensionStarted.await(3, SECONDS)).as("extension is stalled in the request").isTrue();

            CompletableFuture<Void> edt = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                try {
                    assertThat(provider.getStatistics().loadedLines()).isEqualTo(1);
                    assertThat(provider.getLine(0, false)).isInstanceOf(LoadedLogLine.class);
                    assertThat(provider.peekLine(0)).isPresent();
                    assertThat(provider.getLine(1, false)).isInstanceOf(Placeholder.class);
                    provider.preload(0, 1);
                    assertThat(provider.cancelSearch()).isTrue();
                    edt.complete(null);
                } catch (Throwable failure) {
                    edt.completeExceptionally(failure);
                }
            });
            edt.get(3, SECONDS);
            assertThat(releaseExtension.getCount()).as("EDT completed before the server replied").isEqualTo(1);
            releaseExtension.countDown();
            assertThat(completed.await(3, SECONDS)).as("cancelled request eventually returned").isTrue();
            assertThat(provider.getStatistics().loadedLines()).as("late data did not extend the segment").isEqualTo(1);
        } finally {
            releaseExtension.countDown();
            provider.shutdown();
            database.close();
        }
    }

    @Test
    void replacementLoadStartsWhileSupersededRequestIgnoresInterruptionAndStaleResultIsDiscarded() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        Instant staleTarget = Instant.parse("2017-03-30T10:00:00Z");
        Instant newTarget = Instant.parse("2017-03-28T10:00:00Z");
        SplunkClient client = mock(SplunkClient.class);
        CountDownLatch staleStarted = new CountDownLatch(1);
        CountDownLatch releaseStale = new CountDownLatch(1);
        CountDownLatch staleReturned = new CountDownLatch(1);
        CountDownLatch replacementLoaded = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger progressCallbacks = new AtomicInteger();
        when(client.queryEventsWithMonitoring(any(), any(), any(), any())).thenAnswer(invocation -> {
            LoadDecision decision = invocation.getArgument(0);
            if (decision.contains(staleTarget)) {
                staleStarted.countDown();
                // Model a socket read that does not respond to Future.cancel(true).
                boolean released = false;
                while (!released) {
                    try {
                        releaseStale.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // The test releases the request explicitly in finally.
                    }
                }
                staleReturned.countDown();
                invocation.getArgument(1, ProgressCallback.class).onProgress(SearchProgress.FAIL);
                return Stream.of(event(staleTarget));
            }
            invocation.getArgument(1, ProgressCallback.class).onProgress(SearchProgress.FAIL);
            return Stream.of(event(newTarget));
        });
        DatabaseManager database = new DatabaseManager("test", "test", "test", tempDir.toFile());
        Clock clock = Clock.fixed(staleTarget.plusSeconds(3600), ZoneOffset.UTC);
        SplunkLogDataProvider provider = new SplunkLogDataProvider(new LogEventMapper(), database, () -> {
            completed.countDown();
            if (staleReturned.getCount() == 1) {
                replacementLoaded.countDown();
            }
        }, () -> client, () -> "index=test", clock, new SearchBoundaries(clock));
        provider.setLoadProgressCallback(progress -> {
            // Count only the queries' own progress, not the provider's load status updates
            if (progress == SearchProgress.FAIL) {
                progressCallbacks.incrementAndGet();
            }
        });
        try {
            provider.jump(staleTarget);
            assertThat(staleStarted.await(3, SECONDS)).as("first query is stuck in the request").isTrue();

            provider.jump(newTarget);
            assertThat(replacementLoaded.await(3, SECONDS)).as("replacement loaded before the stale reply").isTrue();
            assertThat(releaseStale.getCount()).as("stale request still blocked").isEqualTo(1);
            assertThat(provider.getStatistics().loadedLines()).isEqualTo(1);
            assertThat(provider.getLine(0, false)).isInstanceOfSatisfying(LoadedLogLine.class,
                    line -> assertThat(line.getSplunkTime()).isEqualTo(newTarget));

            releaseStale.countDown();
            assertThat(staleReturned.await(3, SECONDS)).as("stale request returned").isTrue();
            assertThat(completed.await(3, SECONDS)).as("stale job finished").isTrue();
            assertThat(progressCallbacks.get()).as("only the replacement's progress was delivered").isEqualTo(1);

            assertThat(provider.getStatistics().loadedLines()).as("stale data did not touch the segment")
                    .isEqualTo(1);
            assertThat(provider.getLine(0, false)).isInstanceOfSatisfying(LoadedLogLine.class,
                    line -> assertThat(line.getSplunkTime()).isEqualTo(newTarget));
        } finally {
            releaseStale.countDown();
            provider.shutdown();
            database.close();
        }
    }

    private static Event event(Instant time) {
        return TestEvents.event(Map.of("_time", time.toString(), "_raw", "cached log event", "_bkt", "bucket", "_cd",
                time.toString(), "_indextime", Long.toString(time.getEpochSecond())));
    }
}
