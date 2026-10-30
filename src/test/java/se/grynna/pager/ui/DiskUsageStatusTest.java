package se.grynna.pager.ui;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A slow disk-usage calculation must not run on the EDT; only the label update may.
 */
class DiskUsageStatusTest {

    private final ExecutorService background = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        background.shutdownNow();
    }

    @Test
    void slowSizeProviderKeepsTheEdtUsableAndUpdatesTheLabelOnTheEdt() throws Exception {
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicBoolean providerRanOnEdt = new AtomicBoolean();
        AtomicBoolean labelUpdatedOnEdt = new AtomicBoolean();
        AtomicReference<String> label = new AtomicReference<>();
        CountDownLatch labelUpdated = new CountDownLatch(1);

        DiskUsageStatus status = new DiskUsageStatus(() -> {
            providerRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            providerStarted.countDown();
            try {
                releaseProvider.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "42 MB";
        }, text -> {
            labelUpdatedOnEdt.set(SwingUtilities.isEventDispatchThread());
            label.set(text);
            labelUpdated.countDown();
        }, SwingUtilities::invokeLater);

        var running = background.submit(status);
        assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // While the size calculation is stuck, the EDT still processes events promptly.
        CompletableFuture<Void> edtResponse = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> edtResponse.complete(null));
        edtResponse.get(2, TimeUnit.SECONDS);
        assertThat(label.get()).as("no label update before the size is known").isNull();

        releaseProvider.countDown();
        assertThat(labelUpdated.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(label.get()).isEqualTo("42 MB");
        assertThat(providerRanOnEdt).as("size calculated off the EDT").isFalse();
        assertThat(labelUpdatedOnEdt).as("label updated on the EDT").isTrue();
        running.get(5, TimeUnit.SECONDS); // the status run completed normally
    }

    @Test
    void failingSizeProviderShowsADashOnTheEdt() throws Exception {
        AtomicReference<String> label = new AtomicReference<>();
        AtomicBoolean onEdt = new AtomicBoolean();
        CountDownLatch labelUpdated = new CountDownLatch(1);

        var running = background.submit(new DiskUsageStatus(() -> {
            throw new IllegalStateException("storage gone");
        }, text -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            label.set(text);
            labelUpdated.countDown();
        }, SwingUtilities::invokeLater));

        assertThat(labelUpdated.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(label.get()).isEqualTo("-");
        assertThat(onEdt).isTrue();
        running.get(5, TimeUnit.SECONDS); // the failed size lookup is contained in the run
    }

    @Test
    void throwingRunDoesNotCancelLaterRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            DiskUsageStatus status = new DiskUsageStatus(() -> {
                runs.incrementAndGet();
                return "1 GB";
            }, text -> {}, task -> {
                if (runs.get() == 1) {
                    throw new RejectedExecutionException("edt is gone");
                }
                task.run();
            });
            var scheduled = scheduler.scheduleAtFixedRate(status, 0, 10, TimeUnit.MILLISECONDS);

            // The first run throws; the schedule must keep running afterwards.
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> runs.get() >= 3);
            scheduled.cancel(false);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
