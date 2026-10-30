package se.grynna.pager.splunk;

import com.splunk.Event;
import com.splunk.TestEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.exceptions.SearchException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class SplunkClientTest {

    /**
     * SplunkClient that skips the network connect; reconnects are counted instead.
     */
    static class TestSplunkClient extends SplunkClient {
        final AtomicInteger reconnectCount = new AtomicInteger();

        TestSplunkClient() {
            super(Clock.systemUTC());
        }

        @Override
        void reconnect(String newPassword) {
            reconnectCount.incrementAndGet();
        }
    }

    /**
     * Coordinates concurrent relogin callers: counts password prompts and hands out
     * test-controlled password futures, one per prompt.
     */
    static class TestReloginProvider implements ReloginProvider {
        final AtomicInteger promptCount = new AtomicInteger();
        final BlockingQueue<CompletableFuture<String>> passwordRequests = new LinkedBlockingQueue<>();

        @Override
        public CompletableFuture<String> requestPassword(String errorMessage) {
            promptCount.incrementAndGet();
            CompletableFuture<String> future = new CompletableFuture<>();
            passwordRequests.add(future);
            return future;
        }

        CompletableFuture<String> nextRequest() throws InterruptedException {
            CompletableFuture<String> future = passwordRequests.poll(10, SECONDS);
            assertThat(future).as("a password prompt was requested").isNotNull();
            return future;
        }
    }

    /**
     * Runs {@code relogin} on its own thread; the outcome ("ok" or the thrown
     * exception) is recorded in {@code outcomes}.
     */
    private static Thread reloginOnThread(SplunkClient client, String name, BlockingQueue<Object> outcomes) {
        Thread thread = new Thread(() -> {
            try {
                client.relogin("session expired");
                outcomes.add("ok");
            } catch (Throwable t) {
                outcomes.add(t);
            }
        }, name);
        thread.start();
        return thread;
    }

    /**
     * Waits until the thread is parked. A relogin follower has no blocking point other
     * than waiting on the shared recovery, so parked means it has joined the recovery.
     */
    private static void awaitParked(Thread thread) {
        await().atMost(10, SECONDS).until(() -> {
            Thread.State state = thread.getState();
            return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        });
    }

    @Test
    @DisplayName("Concurrent session failures share one password prompt; all waiters proceed")
    void concurrentRelogin_sharesOnePrompt_allWaitersProceed() throws Exception {
        TestSplunkClient client = new TestSplunkClient();
        TestReloginProvider provider = new TestReloginProvider();
        client.setReloginProvider(provider);

        BlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();

        Thread leader = reloginOnThread(client, "relogin-leader", outcomes);
        CompletableFuture<String> password = provider.nextRequest();

        Thread follower = reloginOnThread(client, "relogin-follower", outcomes);
        awaitParked(follower); // joined the in-flight recovery, waiting for its outcome

        password.complete("secret");

        leader.join(SECONDS.toMillis(10));
        follower.join(SECONDS.toMillis(10));
        assertThat(outcomes).as("both waiters proceed after one successful recovery").hasSize(2).containsOnly("ok");
        assertThat(provider.promptCount).as("only one password prompt for concurrent failures").hasValue(1);
        assertThat(client.reconnectCount).as("exactly one reconnect").hasValue(1);

        // A failure after the completed recovery starts a fresh cycle with a new prompt.
        Thread second = reloginOnThread(client, "relogin-second", outcomes);
        CompletableFuture<String> secondPassword = provider.nextRequest();
        secondPassword.complete("secret");
        second.join(SECONDS.toMillis(10));
        assertThat(provider.promptCount).as("a new failure after recovery prompts again").hasValue(2);
    }

    @Test
    @DisplayName("Cancelling the password prompt releases every waiter")
    void reloginCancellation_releasesAllWaiters() throws Exception {
        TestSplunkClient client = new TestSplunkClient();
        TestReloginProvider provider = new TestReloginProvider();
        client.setReloginProvider(provider);

        BlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();

        Thread leader = reloginOnThread(client, "relogin-leader", outcomes);
        CompletableFuture<String> password = provider.nextRequest();

        Thread follower = reloginOnThread(client, "relogin-follower", outcomes);
        awaitParked(follower);

        password.completeExceptionally(new CancellationException("cancelled by user"));

        leader.join(SECONDS.toMillis(10));
        follower.join(SECONDS.toMillis(10));
        assertThat(outcomes).as("both waiters are released with a SearchException").hasSize(2)
                .allSatisfy(o -> assertThat(o).isInstanceOf(SearchException.class));
        assertThat(provider.promptCount).hasValue(1);
        assertThat(client.reconnectCount).as("cancelled recovery never reconnects").hasValue(0);
    }

    @Test
    @DisplayName("Shutdown during recovery releases every waiter")
    void shutdownDuringRelogin_releasesAllWaiters() throws Exception {
        TestSplunkClient client = new TestSplunkClient();
        TestReloginProvider provider = new TestReloginProvider();
        client.setReloginProvider(provider);

        BlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();

        Thread leader = reloginOnThread(client, "relogin-leader", outcomes);
        CompletableFuture<String> password = provider.nextRequest(); // prompt is up; the password future is never answered

        Thread follower = reloginOnThread(client, "relogin-follower", outcomes);
        awaitParked(follower);

        client.markShuttingDown();

        leader.join(SECONDS.toMillis(10));
        follower.join(SECONDS.toMillis(10));
        assertThat(outcomes).as("shutdown releases both waiters with a SearchException").hasSize(2)
                .allSatisfy(o -> assertThat(o).isInstanceOf(SearchException.class));
        assertThat(password).as("shutdown releases the unanswered password prompt").isCompletedExceptionally();

        // No new prompt is shown after shutdown.
        assertThatThrownBy(() -> client.relogin("session expired")).isInstanceOf(SearchException.class);
        assertThat(provider.promptCount).hasValue(1);
    }

    @Test
    @DisplayName("Interrupted recovery leader withdraws its prompt and releases every follower")
    void interruptedLeader_cancelsItsPrompt_releasesFollowers() throws Exception {
        TestSplunkClient client = new TestSplunkClient();
        TestReloginProvider provider = new TestReloginProvider();
        client.setReloginProvider(provider);

        BlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();
        AtomicBoolean leaderStillInterrupted = new AtomicBoolean();
        Thread leader = new Thread(() -> {
            try {
                client.relogin("session expired");
                outcomes.add("ok");
            } catch (Throwable t) {
                leaderStillInterrupted.set(Thread.currentThread().isInterrupted());
                outcomes.add(t);
            }
        }, "relogin-leader");
        leader.start();
        CompletableFuture<String> password = provider.nextRequest();

        Thread follower1 = reloginOnThread(client, "relogin-follower-1", outcomes);
        Thread follower2 = reloginOnThread(client, "relogin-follower-2", outcomes);
        awaitParked(follower1);
        awaitParked(follower2);

        leader.interrupt();

        leader.join(SECONDS.toMillis(10));
        follower1.join(SECONDS.toMillis(10));
        follower2.join(SECONDS.toMillis(10));
        assertThat(outcomes).as("leader and all followers are released with a SearchException").hasSize(3)
                .allSatisfy(o -> assertThat(o).isInstanceOf(SearchException.class));
        assertThat(password).as("the abandoned prompt is withdrawn").isCompletedExceptionally();
        assertThat(leaderStillInterrupted).as("the interrupt is not swallowed").isTrue();
        assertThat(client.reconnectCount).hasValue(0);

        // The failed recovery is cleared: a later failure starts a fresh cycle.
        Thread next = reloginOnThread(client, "relogin-next", outcomes);
        provider.nextRequest().complete("secret");
        next.join(SECONDS.toMillis(10));
        assertThat(provider.promptCount).hasValue(2);
        assertThat(client.reconnectCount).hasValue(1);
    }

    @Test
    @DisplayName("An interrupted follower leaves the shared leader recovery and prompt alone")
    void interruptedFollower_doesNotCancelSharedRecovery() throws Exception {
        TestSplunkClient client = new TestSplunkClient();
        TestReloginProvider provider = new TestReloginProvider();
        client.setReloginProvider(provider);

        BlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();
        Thread leader = reloginOnThread(client, "relogin-leader", outcomes);
        CompletableFuture<String> password = provider.nextRequest();

        BlockingQueue<Object> followerOutcomes = new LinkedBlockingQueue<>();
        Thread follower = reloginOnThread(client, "relogin-follower", followerOutcomes);
        awaitParked(follower);

        follower.interrupt();
        follower.join(SECONDS.toMillis(10));
        assertThat(followerOutcomes).hasSize(1).allSatisfy(o -> assertThat(o).isInstanceOf(SearchException.class));
        assertThat(password).as("follower must not cancel the leader's prompt").isNotDone();

        password.complete("secret");
        leader.join(SECONDS.toMillis(10));
        assertThat(outcomes).containsExactly("ok");
        assertThat(provider.promptCount).hasValue(1);
        assertThat(client.reconnectCount).hasValue(1);
    }

    @Test
    @DisplayName("An interrupted thread never prompts for a password")
    void interruptedThread_doesNotPrompt() throws Exception {
        TestSplunkClient client = new TestSplunkClient();
        TestReloginProvider provider = new TestReloginProvider();
        client.setReloginProvider(provider);

        BlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();
        Thread thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                client.relogin("session expired");
                outcomes.add("ok");
            } catch (Throwable t) {
                outcomes.add(t);
            }
        }, "relogin-interrupted");
        thread.start();
        thread.join(SECONDS.toMillis(10));

        assertThat(outcomes).hasSize(1).allSatisfy(o -> assertThat(o).isInstanceOf(SearchException.class));
        assertThat(provider.promptCount).as("cancelled work does not prompt").hasValue(0);
        assertThat(client.reconnectCount).hasValue(0);
    }

    @Test
    @DisplayName("A superseded (interrupted) failing operation neither prompts nor retries")
    void interruptedOperation_isRejectedBeforeRecoveryOrRetry() throws Exception {
        TestSplunkClient client = new TestSplunkClient();
        TestReloginProvider provider = new TestReloginProvider();
        client.setReloginProvider(provider);

        AtomicInteger attempts = new AtomicInteger();
        BlockingQueue<Object> outcomes = new LinkedBlockingQueue<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                client.loadIndexes(progress -> {
                    attempts.incrementAndGet();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("session expired");
                });
                outcomes.add("ok");
            } catch (Throwable t) {
                stillInterrupted.set(Thread.currentThread().isInterrupted());
                outcomes.add(t);
            }
        }, "interrupted-operation");
        thread.start();
        thread.join(SECONDS.toMillis(10));

        assertThat(outcomes).hasSize(1).allSatisfy(o -> assertThat(o).isInstanceOf(SearchException.class));
        assertThat(attempts).as("no retry after interruption").hasValue(1);
        assertThat(provider.promptCount).as("no prompt after interruption").hasValue(0);
        assertThat(stillInterrupted).as("the interrupt is preserved").isTrue();
    }

    /**
     * Create an Event with the given timestamp.
     */
    private static Event createEventWithTime(String timestamp) {
        return TestEvents.event(Map.of("_time", timestamp));
    }

    @Test
    void calculateTooBigVelocity_emptyStream_returnsNull() {
        var result = SplunkClient.calculateTooBigVelocity(Stream.empty());
        assertNull(result);
    }

    @Test
    void calculateTooBigVelocity_singleEvent_returnsCountOneSameFirstAndLast() {
        var event = createEventWithTime("2025-01-13T10:00:00Z");

        var result = SplunkClient.calculateTooBigVelocity(Stream.of(event));

        assertNotNull(result);
        assertEquals(1, result.count()); // count is total number of events
        assertEquals(Instant.parse("2025-01-13T10:00:00Z"), result.first());
        assertEquals(Instant.parse("2025-01-13T10:00:00Z"), result.last());
    }

    @Test
    void calculateTooBigVelocity_multipleEventsInOrder_returnsCorrectFirstAndLast() {
        var events = List.of(createEventWithTime("2025-01-13T10:00:00Z"), createEventWithTime("2025-01-13T10:01:00Z"),
                createEventWithTime("2025-01-13T10:02:00Z"));

        var result = SplunkClient.calculateTooBigVelocity(events.stream());

        assertNotNull(result);
        assertEquals(3, result.count()); // total 3 events
        assertEquals(Instant.parse("2025-01-13T10:00:00Z"), result.first());
        assertEquals(Instant.parse("2025-01-13T10:02:00Z"), result.last());
    }

    @Test
    void calculateTooBigVelocity_multipleEventsOutOfOrder_findsMinAndMax() {
        // Events not in chronological order - method should find true min/max
        var events = List.of(createEventWithTime("2025-01-13T10:05:00Z"), // middle
                createEventWithTime("2025-01-13T10:00:00Z"), // earliest
                createEventWithTime("2025-01-13T10:10:00Z"), // latest
                createEventWithTime("2025-01-13T10:03:00Z") // middle
        );

        var result = SplunkClient.calculateTooBigVelocity(events.stream());

        assertNotNull(result);
        assertEquals(4, result.count()); // total 4 events
        assertEquals(Instant.parse("2025-01-13T10:00:00Z"), result.first());
        assertEquals(Instant.parse("2025-01-13T10:10:00Z"), result.last());
    }

    @Test
    void calculateTooBigVelocity_allSameTimestamp_sameFirstAndLast() {
        var events = List.of(createEventWithTime("2025-01-13T10:00:00Z"), createEventWithTime("2025-01-13T10:00:00Z"),
                createEventWithTime("2025-01-13T10:00:00Z"));

        var result = SplunkClient.calculateTooBigVelocity(events.stream());

        assertNotNull(result);
        assertEquals(3, result.count()); // total 3 events
        assertEquals(Instant.parse("2025-01-13T10:00:00Z"), result.first());
        assertEquals(Instant.parse("2025-01-13T10:00:00Z"), result.last());
    }

    @Test
    @DisplayName("calculateTooBigVelocity counts every event")
    void calculateTooBigVelocity_countsAllEvents() {
        var events = List.of(createEventWithTime("2025-05-10T12:00:00Z"), createEventWithTime("2025-05-10T12:00:01Z"));

        var result = SplunkClient.calculateTooBigVelocity(events.stream());

        assertNotNull(result);
        assertEquals(2, result.count());
    }

    @Test
    @DisplayName("earliest_time is never the epoch: Splunk rejects it and treats 0 as unused")
    void earliestTimeIsLiftedToOneYearAfterTheEpoch() {
        assertThat(SplunkClient.earliestTimeArg(Instant.EPOCH))
                .isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY.toString());
        assertThat(SplunkClient.earliestTimeArg(Instant.EPOCH.minusSeconds(1)))
                .isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY.toString());
        assertThat(SplunkClient.earliestTimeArg(SearchBoundaries.MIN_MINIMUM_BOUNDARY))
                .isEqualTo("1971-01-01T00:00:00Z");
        Instant later = Instant.parse("2017-12-26T22:28:30Z");
        assertThat(SplunkClient.earliestTimeArg(later)).isEqualTo(later.toString());
    }
}
