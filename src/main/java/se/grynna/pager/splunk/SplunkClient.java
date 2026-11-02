package se.grynna.pager.splunk;

import com.splunk.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import se.grynna.pager.exceptions.ExceptionMessageFormatter;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.splunk.cert.CertificateDecisionProvider;
import se.grynna.pager.splunk.cert.CertificatePinStore;
import se.grynna.pager.splunk.cert.InteractiveSslContextFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.System.currentTimeMillis;
import static java.nio.file.Files.newOutputStream;

/**
 * Splunk API client with transparent session recovery.
 *
 * <h2>Session Recovery</h2>
 * When a Splunk call fails due to session expiration or network error,
 * use {@link #withRecovery(String, SplunkOperation)} to wrap the call.
 * It will:
 * <ol>
 *   <li>Execute the operation</li>
 *   <li>On client error: call ReloginProvider.requestPassword()</li>
 *   <li>On successful relogin: retry the operation once</li>
 * </ol>
 *
 * @see ReloginProvider
 */
@Slf4j
public class SplunkClient {

    private static final String dumpToFile = configDump();

    private static String configDump() {
        var env = System.getenv("SPLUNK_DUMP");
        if (StringUtils.isNotBlank(env)) {
            log.info("Dumping Splunk results to file {}", env);
            return env;
        }
        return null;
    }

    private final Clock clock;
    // Replaced on relogin; read without a lock by every request thread
    private volatile Service service;

    /** Read timeout of every request; all long operations poll, so no request should take longer. */
    static final int READ_TIMEOUT_MS = 60_000;
    static final int CONNECT_TIMEOUT_MS = 20_000;

    /** Interval between background session keepalive requests. */
    static final Duration KEEPALIVE_INTERVAL = Duration.ofMinutes(5);
    /** Longest stretch of application inactivity during which keepalive continues to refresh the session. */
    static final Duration MAX_KEEPALIVE_INACTIVITY = Duration.ofHours(4);
    /** Authenticated in-memory splunkd endpoint that touches the session without starting a search job. */
    static final String KEEPALIVE_PATH = "authentication/current-context";

    private volatile Instant lastActivity;
    private final Object keepAliveLock = new Object();
    private ScheduledExecutorService keepAliveExecutor;

    /**
     * Delays between the cancel passes of one ticket while a cancel is unconfirmed (failed, or
     * Splunk does not know a SID whose create outcome is unknown); the last one repeats.
     */
    private static final long[] CANCEL_RETRY_MS = {1_000, 3_000, 10_000};

    /**
     * How long a retry waits for the cancel of the attempt it replaces, so a retry never adds a
     * search while the previous one may still run.
     */
    long earlierAttemptWaitMillis = READ_TIMEOUT_MS + 15_000;

    /**
     * Runs creates and SID cancels off the requesting thread. Creates are never interrupted (their
     * outcome decides whether a job exists); cancels must not wait for a stuck or interrupted worker,
     * and the EDT never waits for Splunk. Stopped by {@link #logout()}.
     */
    private final ExecutorService jobControl = Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("pager-jobctl-", 0).factory());
    private final Set<JobTicket> cancelLoops = ConcurrentHashMap.newKeySet();
    private volatile boolean jobControlStopping = false;

    /** Shortest time between two {@link #setBusyListener busy} notices of the same kind. */
    static final Duration BUSY_NOTICE_INTERVAL = Duration.ofSeconds(30);
    private volatile BusyListener busyListener = (detail, gaveUp) -> {};
    private final Object busyLock = new Object();
    // Guarded by busyLock; index 0: retrying, 1: gave up
    private final long[] lastBusyNoticeNanos = new long[2];
    private final boolean[] busyNoticed = new boolean[2];
    /** Waits before retrying a create that Splunk refused as busy; then the work fails. */
    List<Duration> busyRetryDelays = List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));

    @Getter
    private final String host;

    @Getter
    private final int port;

    @Getter
    private final String user;

    /**
     * Asks the user whether untrusted (e.g. self-signed) certificates may be trusted.
     * When {@code null}, the SDK default (strict) validation is used.
     */
    private final CertificateDecisionProvider certificateDecisionProvider;

    /** Remembers certificates the user trusted, per host:port. */
    private final CertificatePinStore certificatePinStore;

    /**
     * NOOP provider used during shutdown - just logs and throws.
     */
    private static final ReloginProvider NOOP_RELOGIN_PROVIDER = errorMessage -> {
        log.debug("Relogin requested during shutdown, ignoring: {}", errorMessage);
        throw new CancellationException("Session recovery not available (shutdown in progress)");
    };

    /**
     * Provider for session recovery. Initialized with NOOP to avoid null checks.
     */
    private ReloginProvider reloginProvider = NOOP_RELOGIN_PROVIDER;

    /**
     * Flag indicating shutdown is in progress - skip recovery attempts.
     */
    private volatile boolean shuttingDown = false;

    /**
     * Monotonic generation for the active Splunk session/service.
     * Incremented every time a relogin installs a new service.
     */
    private final AtomicLong serviceGeneration = new AtomicLong(0);

    /**
     * Guards {@link #inFlightRecovery} and {@link #inFlightPasswordRequest}.
     */
    private final Object reloginLock = new Object();

    /**
     * The one in-flight session-recovery operation, shared by every thread that hits a
     * recoverable client error while it runs. Completed with {@code null} on
     * success and exceptionally on cancellation/failure/shutdown, releasing all waiters.
     * Guarded by {@link #reloginLock}.
     */
    private CompletableFuture<Void> inFlightRecovery;

    /**
     * The password request currently awaiting user input (the future returned by the
     * {@link ReloginProvider}). Tracked so {@link #markShuttingDown()} can release a
     * recovery leader blocked waiting for it. Guarded by {@link #reloginLock}.
     */
    private CompletableFuture<?> inFlightPasswordRequest;

    /**
     * Small LRU set of recently cancelled job SIDs to avoid cancelling the same job twice.
     */
    private final java.util.Set<String> cancelledJobs = java.util.Collections
            .newSetFromMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > 10; // Keep last 10 cancelled job SIDs
                }
            });

    public SplunkClient(String host, int port, String user, String pass, Clock clock) throws SearchException {
        this(host, port, user, pass, clock, null, new CertificatePinStore());
    }

    /**
     * Creates a Splunk client.
     *
     * @param certificateDecisionProvider optional provider used to ask the user about
     *                                    untrusted certificates (e.g. a self-signed cert
     *                                    from a local test image). When {@code null}, the
     *                                    SDK's strict default validation applies.
     * @param certificatePinStore         store for certificates the user has trusted;
     *                                    when {@code null} a default store is used
     */
    public SplunkClient(String host, int port, String user, String pass, Clock clock,
            CertificateDecisionProvider certificateDecisionProvider, CertificatePinStore certificatePinStore)
            throws SearchException {
        this.host = host;
        this.port = port;
        this.user = user;
        this.clock = clock;
        this.lastActivity = clock.instant();
        this.certificateDecisionProvider = certificateDecisionProvider;
        this.certificatePinStore = certificatePinStore != null ? certificatePinStore : new CertificatePinStore();

        log.debug("login {}@{}:{}", user, host, port);

        // validate
        if (StringUtils.isBlank(user) || StringUtils.isBlank(pass)) {
            throw new IllegalArgumentException("userName or password is blank");
        }

        this.service = createService(host, port, user, pass);
        serviceGeneration.incrementAndGet();
        startKeepAlive(KEEPALIVE_INTERVAL);
        log.debug("login successful: {}", service.getInfo().getServerName());
    }

    /**
     * Test-only constructor: creates a client without connecting. {@link #service} stays
     * {@code null}; tests override {@link #reconnect(String)} to avoid the network.
     */
    SplunkClient(Clock clock) {
        this.host = "test";
        this.port = 8089;
        this.user = "test";
        this.clock = clock;
        this.lastActivity = clock.instant();
        this.certificateDecisionProvider = null;
        this.certificatePinStore = new CertificatePinStore();
    }

    private static void failIfDispatchThread() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Cannot perform network operation on Swing Event Dispatch Thread");
        }
    }

    /**
     * Creates and connects a Splunk Service with the given credentials.
     */
    private Service createService(String host, int port, String user, String pass) throws SearchException {
        ServiceArgs serviceArgs = serviceArgs(host, port, user, pass);

        failIfDispatchThread();

        // If a decision provider is configured, replace the SDK's TLS trust manager with one
        // that queries the user about self-signed / otherwise untrusted certificates, while
        // still accepting normally trusted certificates silently. The SDK stores the socket
        // factory in a static field, so install it right before each connect.
        if (certificateDecisionProvider != null) {
            HttpService.setSSLSocketFactory(
                    InteractiveSslContextFactory.create(host, port, certificateDecisionProvider, certificatePinStore));
            log.debug("Installed interactive certificate trust for {}:{}", host, port);
        }

        try {
            return Service.connect(serviceArgs);
        } catch (Throwable e) {
            var msg = "Failed to connect to %s:%d with user %s%n%s".formatted(host, port, user,
                    ExceptionMessageFormatter.formatException(e));
            log.error(msg);
            throw new SearchException(msg, e);
        }
    }

    /** Connection arguments; the SDK has one connect and one read timeout per service. */
    static ServiceArgs serviceArgs(String host, int port, String user, String pass) {
        ServiceArgs serviceArgs = new ServiceArgs();
        serviceArgs.setPort(port);
        serviceArgs.setHost(host);
        serviceArgs.setScheme("https");
        serviceArgs.setUsername(user);
        serviceArgs.setPassword(pass);
        serviceArgs.put("connectTimeout", CONNECT_TIMEOUT_MS);
        serviceArgs.put("readTimeout", READ_TIMEOUT_MS);
        return serviceArgs;
    }

    /** Test seam: use {@code testService} (e.g. plain HTTP against a fake server) for every request. */
    void setServiceForTest(Service testService) {
        this.service = testService;
    }

    /**
     * Attempts to recover from a client error by requesting a new password and reconnecting.
     * If reconnection fails (e.g., wrong password), retries by showing the dialog again with the new error.
     *
     * <p><b>Coalescing:</b> the first thread to hit a recoverable error becomes the
     * recovery leader and prompts for a password; concurrent failures join the in-flight
     * recovery instead of prompting again. Every waiter is released with the same outcome -
     * success, cancellation, or shutdown - so no operation is ever left blocked on an
     * orphaned password request.
     *
     * @param errorMessage describes what went wrong
     * @throws SearchException if recovery is cancelled by user
     */
    void relogin(String errorMessage) throws SearchException {
        recover(errorMessage, false);
    }

    /**
     * Like {@link #relogin}, or with {@code unreachable} for a network failure: ask the user to
     * retry when Splunk is reachable again, keeping the session (no password, no reconnect).
     * Both kinds share one in-flight recovery: a failure of either kind joins whichever is shown.
     */
    void recover(String errorMessage, boolean unreachable) throws SearchException {
        log.info("Attempting {} after error: {}", unreachable ? "network recovery" : "session recovery",
                errorMessage);
        failIfDispatchThread();

        CompletableFuture<Void> recovery;
        boolean leader;
        synchronized (reloginLock) {
            if (inFlightRecovery != null && !inFlightRecovery.isDone()) {
                recovery = inFlightRecovery;
                leader = false;
            } else {
                recovery = new CompletableFuture<>();
                inFlightRecovery = recovery;
                leader = true;
            }
        }

        if (leader) {
            leadRecovery(errorMessage, recovery, unreachable);
        } else {
            log.debug("Joining in-flight session recovery instead of prompting again");
            awaitRecovery(recovery);
        }
    }

    /**
     * Leader side of session recovery: prompts for a password and reconnects, retrying
     * with a fresh prompt while reconnection fails. The outcome is published to the
     * shared {@code recovery} future, releasing every joined waiter.
     */
    // Identity comparison is intentional: only the exact in-flight recovery
    // future this leader published may be cleared; a newer recovery (or none)
    // must be left alone.
    @SuppressWarnings("ReferenceEquality")
    private void leadRecovery(String errorMessage, CompletableFuture<Void> recovery, boolean unreachable)
            throws SearchException {
        String currentErrorMessage = errorMessage;

        try {
            // Keep retrying until successful or user cancels
            while (true) {
                try {
                    // A superseded (interrupted) operation must not prompt or reconnect
                    throwIfInterrupted();

                    if (unreachable) {
                        // The session is kept: the caller retries its work once the user says so
                        awaitUserRequest(reloginProvider.requestRetry(currentErrorMessage));
                        throwIfInterrupted();
                        if (shuttingDown) {
                            throw new CancellationException("Shutdown in progress");
                        }
                        recovery.complete(null);
                        log.info("Retrying after Splunk was unreachable");
                        return;
                    }

                    // Request password via CompletableFuture - blocks until user responds
                    String newPassword = requestPassword(currentErrorMessage);

                    throwIfInterrupted();

                    // Reconnect with new password (password not stored, used only for this connection)
                    reconnect(newPassword);

                    if (shuttingDown) {
                        throw new CancellationException("Shutdown in progress");
                    }

                    if (!recovery.complete(null)) {
                        throw new CancellationException("Session recovery already completed");
                    }

                    log.info("Session recovery successful");
                    return; // Success - exit loop

                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new SearchException("Relogin interrupted", ex);
                } catch (CancellationException ex) {
                    throw new SearchException("Relogin cancelled", ex);
                } catch (ExecutionException ex) {
                    throw new SearchException("Relogin failed: " + ex.getCause().getMessage(), ex.getCause());
                } catch (SearchException ex) {
                    // Reconnection failed (e.g., wrong password) - retry with new error message
                    log.warn("Session recovery failed, retrying: {}", ex.getMessage());
                    currentErrorMessage = ExceptionMessageFormatter.formatException(ex);
                }
            }
        } catch (SearchException | RuntimeException | Error e) {
            recovery.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (reloginLock) {
                if (inFlightRecovery == recovery) {
                    inFlightRecovery = null;
                }
            }
        }
    }

    /**
     * Follower side of session recovery: waits for the leader's shared recovery to
     * finish and surfaces the same outcome. Never completes the shared future - the
     * leader and other waiters may still need it if this thread is interrupted.
     */
    private void awaitRecovery(CompletableFuture<Void> recovery) throws SearchException {
        try {
            recovery.get();
            log.info("In-flight session recovery succeeded - proceeding");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchException("Interrupted while waiting for in-flight session recovery", e);
        } catch (CancellationException e) {
            throw new SearchException("Session recovery cancelled", e);
        } catch (ExecutionException e) {
            // The leader's recovery failed (cancelled, shutdown, ...) - surface it as-is
            if (e.getCause() instanceof SearchException se) {
                throw se;
            }
            throw new SearchException("Session recovery failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * Asks the relogin provider for a new password and blocks until the user responds.
     * The pending request is tracked so {@link #markShuttingDown()} can release a
     * leader blocked here.
     */
    // Identity comparison is intentional: only the exact in-flight password
    // request future created here may be cleared; a newer request (or none)
    // must be left alone.
    @SuppressWarnings("ReferenceEquality")
    private String requestPassword(String errorMessage) throws InterruptedException, ExecutionException {
        return awaitUserRequest(reloginProvider.requestPassword(errorMessage));
    }

    /** Waits for the user's answer to a recovery prompt; see {@link #requestPassword}. */
    // Identity comparison is intentional, as in requestPassword
    @SuppressWarnings("ReferenceEquality")
    private <T> T awaitUserRequest(CompletableFuture<T> request) throws InterruptedException, ExecutionException {
        boolean shutdownDetected;
        synchronized (reloginLock) {
            shutdownDetected = shuttingDown;
            inFlightPasswordRequest = request;
        }

        if (shutdownDetected) {
            request.completeExceptionally(new CancellationException("Session recovery aborted - shutdown in progress"));
        }

        try {
            return request.get();
        } catch (InterruptedException e) {
            // The leader was superseded: withdraw its prompt so the UI overlay is not left
            // waiting for an answer nobody awaits. The interrupt is re-asserted by the caller.
            request.completeExceptionally(new CancellationException("Session recovery interrupted"));
            throw e;
        } finally {
            synchronized (reloginLock) {
                if (inFlightPasswordRequest == request) {
                    inFlightPasswordRequest = null;
                }
            }
        }
    }

    /**
     * Fails with {@link InterruptedException} if the current thread has been interrupted,
     * without clearing the interrupt status.
     */
    private static void throwIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Operation superseded");
        }
    }

    /**
     * Rejects an interrupted (superseded) operation before it can recover or retry. The
     * interrupt status is left set for the caller.
     */
    private void rejectIfInterrupted(String operation, Exception cause) throws SearchException {
        if (Thread.currentThread().isInterrupted()) {
            log.info("Skipping error recovery for interrupted {}", operation);
            throw new SearchException("Error during " + operation + " (interrupted)", cause);
        }
    }

    /**
     * Re-establishes the Splunk service with a new password.
     * Package-private seam so tests can avoid the network.
     */
    void reconnect(String newPassword) throws SearchException {
        Service reconnectedService = createService(host, port, user, newPassword);

        synchronized (reloginLock) {
            if (shuttingDown) {
                try {
                    reconnectedService.logout();
                } catch (Exception e) {
                    log.debug("Error while discarding post-shutdown relogin service: {}", e.getMessage());
                }
                throw new CancellationException("Session recovery aborted - shutdown in progress");
            }

            this.service = reconnectedService;
            serviceGeneration.incrementAndGet();
            recordActivity();
        }
    }

    /**
     * Records user or application activity so background keepalive continues for up to
     * {@link #MAX_KEEPALIVE_INACTIVITY} from this instant. Safe to call from any thread,
     * including the EDT.
     */
    public void recordActivity() {
        this.lastActivity = clock.instant();
    }

    Instant lastActivity() {
        return lastActivity;
    }

    /**
     * Starts periodic session keepalive at {@code interval}. Stopped by {@link #markShuttingDown()}.
     */
    void startKeepAlive(Duration interval) {
        long millis = Math.max(1L, interval.toMillis());
        synchronized (keepAliveLock) {
            if (shuttingDown) {
                return;
            }
            if (keepAliveExecutor != null) {
                keepAliveExecutor.shutdownNow();
            }
            keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("pager-keepalive-", 0).factory());
            keepAliveExecutor.scheduleWithFixedDelay(this::keepAliveTick, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    private void stopKeepAlive() {
        ScheduledExecutorService executor;
        synchronized (keepAliveLock) {
            executor = keepAliveExecutor;
            keepAliveExecutor = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * One quiet keepalive check: touches {@link #KEEPALIVE_PATH} on the active session while not
     * shutting down, not waiting on a recovery prompt, and within {@link #MAX_KEEPALIVE_INACTIVITY}
     * of {@link #lastActivity}. Never prompts or throws.
     *
     * @return {@code true} if the keepalive request was sent and succeeded
     */
    boolean keepAliveTick() {
        Service current = service;
        if (shuttingDown || current == null || isRecoveryInFlight()) {
            return false;
        }
        Instant now = clock.instant();
        Duration idle = Duration.between(lastActivity, now);
        if (idle.compareTo(MAX_KEEPALIVE_INACTIVITY) > 0) {
            log.debug("Skipping Splunk keepalive after {} of inactivity", idle);
            return false;
        }
        try {
            ResponseMessage response = current.get(KEEPALIVE_PATH);
            closeKeepAliveResponse(response);
            log.debug("Splunk keepalive succeeded");
            return true;
        } catch (Throwable e) {
            log.debug("Splunk keepalive failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isRecoveryInFlight() {
        synchronized (reloginLock) {
            return inFlightRecovery != null && !inFlightRecovery.isDone();
        }
    }

    private static void closeKeepAliveResponse(ResponseMessage response) {
        if (response == null) {
            return;
        }
        InputStream content = response.getContent();
        if (content == null) {
            return;
        }
        try (content) {
            content.readNBytes(CREATE_RESPONSE_LIMIT);
        } catch (IOException e) {
            log.debug("Could not drain keepalive response: {}", e.toString());
        }
    }

    /**
     * Internal helper that wraps an operation with automatic session recovery.
     * Used by public methods to transparently handle session expiration.
     */
    private <T> T withRecovery(String operation, SplunkOperation<T> action)
            throws SearchException, TemporarySearchException, SearchResultToBigException, SearchCancelledException {
        failIfDispatchThread();
        recordActivity();
        long operationGeneration = serviceGeneration.get();

        try {
            return action.execute();
        } catch (SearchResultToBigException | SearchCancelledException e) {
            throw e;
        } catch (Exception e) {
            return handleErrorAndRetry(operation, action, e, operationGeneration);
        }
    }

    /**
     * Handles an error by attempting relogin if it's a client error, then retrying.
     */
    private <T> T handleErrorAndRetry(String operation, SplunkOperation<T> action, Exception e,
            long operationGeneration)
            throws SearchException, TemporarySearchException, SearchResultToBigException, SearchCancelledException {

        // A superseded operation must not prompt, relogin or retry
        rejectIfInterrupted(operation, e);

        // Skip recovery during shutdown - just rethrow
        if (shuttingDown) {
            log.info("Skipping error recovery during shutdown for {}", operation);
            rethrowTyped(e, "Error during " + operation + " (shutdown in progress)");
        }

        var exceptionInfo = ExceptionMessageFormatter.classifyException(e);

        if (exceptionInfo.isNetworkError()) {
            return retryWhenReachable(operation, action, e, exceptionInfo.message());
        }

        // Not a recoverable client error - rethrow immediately
        if (!exceptionInfo.isClientError()) {
            rethrowTyped(e, "Error during " + operation);
        }

        // Another operation may already have recovered and replaced the session while this
        // operation was in flight with the stale one. Retry once on the new session before
        // prompting again.
        if (serviceGeneration.get() != operationGeneration) {
            log.info("Detected newer session while handling {} - retrying once before relogin", operation);
            rejectIfInterrupted(operation, e);
            try {
                return action.execute();
            } catch (SearchResultToBigException | SearchCancelledException retrySpecial) {
                throw retrySpecial;
            } catch (Exception retryException) {
                var retryInfo = ExceptionMessageFormatter.classifyException(retryException);
                if (retryInfo.isNetworkError()) {
                    return retryWhenReachable(operation, action, retryException, retryInfo.message());
                }
                if (!retryInfo.isClientError()) {
                    rethrowTyped(retryException, "Error during " + operation + " (after concurrent session refresh)");
                }
                // Still a client/session error - continue with explicit relogin.
                exceptionInfo = retryInfo;
            }
        }

        rejectIfInterrupted(operation, e);
        log.warn("Client error during {}: {}", operation, exceptionInfo.message());

        // Attempt relogin (throws if fails/cancelled)
        relogin(exceptionInfo.message());

        if (shuttingDown) {
            throw new SearchException("Error during " + operation + " (shutdown in progress)",
                    new CancellationException("Shutdown in progress"));
        }
        rejectIfInterrupted(operation, e);

        // Retry once after successful relogin
        try {
            log.info("Retrying {} after relogin", operation);
            return action.execute();
        } catch (SearchResultToBigException | SearchCancelledException retrySpecial) {
            throw retrySpecial;
        } catch (Exception retryException) {
            if (ExceptionMessageFormatter.isNetworkError(retryException)) {
                return retryWhenReachable(operation, action, retryException,
                        ExceptionMessageFormatter.formatException(retryException));
            }
            log.error("Operation failed after relogin: {}", retryException.getMessage());
            rethrowTyped(retryException, "Error during " + operation + " (after retry)");
            throw new SearchException("Unexpected error", retryException); // unreachable
        }
    }

    /**
     * Splunk could not be reached: pause until the user asks to retry, then run the
     * operation again on the same session, as long as it keeps failing to reach Splunk. A
     * session error on a retry goes to the relogin handling; any other failure is thrown.
     */
    private <T> T retryWhenReachable(String operation, SplunkOperation<T> action, Exception failure, String message)
            throws SearchException, TemporarySearchException, SearchResultToBigException, SearchCancelledException {
        Exception e = failure;
        String currentMessage = message;
        while (true) {
            rejectIfInterrupted(operation, e);
            log.warn("Splunk unreachable during {}: {}", operation, currentMessage);
            recover(currentMessage, true);
            if (shuttingDown) {
                throw new SearchException("Error during " + operation + " (shutdown in progress)",
                        new CancellationException("Shutdown in progress"));
            }
            rejectIfInterrupted(operation, e);
            try {
                log.info("Retrying {} after Splunk was unreachable", operation);
                return action.execute();
            } catch (SearchResultToBigException | SearchCancelledException special) {
                throw special;
            } catch (Exception retryException) {
                if (isSessionError(retryException)) {
                    return handleErrorAndRetry(operation, action, retryException, serviceGeneration.get());
                }
                if (!ExceptionMessageFormatter.isNetworkError(retryException)) {
                    rethrowTyped(retryException, "Error during " + operation + " (after network retry)");
                }
                e = retryException;
                currentMessage = ExceptionMessageFormatter.formatException(retryException);
            }
        }
    }

    /**
     * Rethrows exception preserving type, or wraps in SearchException.
     */
    private void rethrowTyped(Exception e, String message)
            throws SearchException, TemporarySearchException, SearchResultToBigException {
        if (e instanceof SearchResultToBigException se) throw se;
        if (e instanceof SearchException se) throw se;
        if (e instanceof TemporarySearchException te) throw te;
        if (e instanceof SearchResultToBigException re) throw re;
        throw new SearchException(message, e);
    }

    /**
     * Internal functional interface for operations that may require session recovery.
     */
    @FunctionalInterface
    private interface SplunkOperation<T> {
        T execute()
                throws SearchException, TemporarySearchException, SearchResultToBigException, SearchCancelledException;
    }

    /** Splunk refused a create with HTTP 503 (busy): no job exists, the attempt may be retried. */
    private static final class BusyRefusal extends RuntimeException {
        BusyRefusal(HttpException cause) {
            super(cause.getMessage(), cause, false, false);
        }

        HttpException http() {
            return (HttpException) getCause();
        }
    }

    /**
     * Run {@code attempt}, retrying it after each {@link #busyRetryDelays} wait while Splunk refuses
     * its create as busy. Each retry is a new attempt with a new SID. Interrupting the wait
     * cancels; after the last retry the 503 is thrown as is.
     */
    private <T> T retryWhileBusy(SplunkOperation<T> attempt)
            throws SearchException, TemporarySearchException, SearchResultToBigException, SearchCancelledException {
        for (int retry = 0;; retry++) {
            try {
                return attempt.execute();
            } catch (BusyRefusal busy) {
                if (retry >= busyRetryDelays.size() || shuttingDown) {
                    log.warn("Splunk still busy after {} retries; giving up", retry);
                    if (!shuttingDown) {
                        noticeBusy(busy.http(), true);
                    }
                    throw busy.http();
                }
                if (retry == 0) {
                    noticeBusy(busy.http(), false);
                }
                Duration delay = busyRetryDelays.get(retry);
                log.info("Splunk busy; retrying in {} s ({}/{})", delay.toSeconds(), retry + 1,
                        busyRetryDelays.size());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SearchCancelledException("Superseded while waiting to retry a busy Splunk", e);
                }
            }
        }
    }

    // probably better for searching?
    // processId ?
    // serviceRequestID

    // kubernetes.labels.controller-revision-line
    // kubernetes.labels.controller-uid
    // kubernetes.labels.pod-template-line

    String latestTimestampBy = """
            | stats latest(_time) as latest_timestamp by %s
            | eval latest_timestamp=strftime(latest_timestamp, "%Y-%m-%d %H:%M:%S")
            | table kubernetes.pod_name, latest_timestamp""";

    String mostActivePodsByLogVolume = """
            index=<your_k8s_index> sourcetype=<your_sourcetype>
            | stats count by kubernetes.pod_name""";

    //    | sort -count
    //    | head 10
    //    | table kubernetes.pod_name count
    //    | rename count as "Log Events"0

    String timeSinceLastLog = """
            | stats latest(_time) as latest_timestamp by kubernetes.pod_name
            | eval latest_timestamp=strftime(latest_timestamp, "%Y-%m-%d %H:%M:%S")
            | eval minutes_since_last_log=round((now() - strptime(latest_timestamp, "%Y-%m-%d %H:%M:%S"))/60, 2)
            """;
    // | where minutes_since_last_log > 60

    // uniqueish events
    String eventsWithLowClusterCount = """
            | where cluster_count < 3
            | table _time kubernetes.pod_name kubernetes.container_name _raw cluster_count
            | eval _time=strftime(_time, "%Y-%m-%d %H:%M:%S")""";

    /**
     * Sets the relogin provider for session recovery.
     *
     * @param provider the provider, or null to disable session recovery
     */
    public void setReloginProvider(ReloginProvider provider) {
        this.reloginProvider = provider != null ? provider : NOOP_RELOGIN_PROVIDER;
    }

    /** Told when Splunk refuses to create a search with HTTP 503 (its concurrent-search quota is reached). */
    @FunctionalInterface
    public interface BusyListener {
        /**
         * @param detail Splunk's reason
         * @param gaveUp {@code false}: the first refusal of a job, it is being retried;
         *               {@code true}: the last retry was refused too, the work failed
         */
        void busy(String detail, boolean gaveUp);
    }

    /**
     * Told Splunk's reason when it refuses to create a search with HTTP 503, at most once per
     * {@link #BUSY_NOTICE_INTERVAL} for each kind (retrying, gave up). Called on a worker thread;
     * must not block.
     */
    public void setBusyListener(BusyListener listener) {
        this.busyListener = Objects.requireNonNull(listener, "listener");
    }

    private void noticeBusy(HttpException e, boolean gaveUp) {
        int kind = gaveUp ? 1 : 0;
        long now = System.nanoTime();
        synchronized (busyLock) {
            if (busyNoticed[kind] && now - lastBusyNoticeNanos[kind] < BUSY_NOTICE_INTERVAL.toNanos()) {
                return;
            }
            busyNoticed[kind] = true;
            lastBusyNoticeNanos[kind] = now;
        }
        String detail = StringUtils.defaultIfBlank(e.getDetail(), e.getMessage());
        try {
            busyListener.busy(detail, gaveUp);
        } catch (RuntimeException listenerFailure) {
            log.warn("Busy listener failed", listenerFailure);
        }
    }

    /**
     * Marks the client as shutting down. All ongoing operations will abort quickly.
     * Call this first before shutting down other components.
     *
     * <p>Releases session-recovery waiters: the pending password request frees a
     * leader blocked waiting for user input, and the shared recovery future frees every
     * joined follower.
     */
    public void markShuttingDown() {
        log.info("Marking SplunkClient as shutting down");
        shuttingDown = true;
        reloginProvider = NOOP_RELOGIN_PROVIDER;
        stopKeepAlive();

        CompletableFuture<Void> recovery;
        CompletableFuture<?> passwordRequest;
        synchronized (reloginLock) {
            recovery = inFlightRecovery;
            passwordRequest = inFlightPasswordRequest;
        }
        // Complete the password request first so the leader can publish its own outcome;
        // completing the shared recovery directly is the fallback for a leader blocked
        // elsewhere (e.g. inside a reconnect attempt).
        if (passwordRequest != null) {
            passwordRequest.completeExceptionally(
                    new CancellationException("Session recovery aborted - shutdown in progress"));
        }
        if (recovery != null) {
            recovery.completeExceptionally(
                    new CancellationException("Session recovery aborted - shutdown in progress"));
        }
    }

    /**
     * Logout from Splunk and disable session recovery.
     * After calling this, any ongoing operations will fail without attempting relogin.
     */
    void logout() {
        log.info("Logging out from Splunk {}:{}", host, port);

        // Mark as shutting down - this prevents relogin attempts
        markShuttingDown();
        stopJobControl();

        try {
            service.logout();
        } catch (Exception e) {
            log.debug("Error during Splunk logout: {}", e.getMessage());
        }
    }

    /** Upper bound for letting a cancel request in flight finish before logout. */
    private static final Duration JOB_CONTROL_STOP_WAIT = Duration.ofSeconds(1);

    /**
     * Stop the cancel loops and creates, bounded: a pass in flight may finish its request, waiting
     * loops end at once, and whatever is still running after the bound is interrupted. Cancels not
     * confirmed by then are given up (the session ends).
     */
    private void stopJobControl() {
        jobControlStopping = true;
        cancelLoops.forEach(JobTicket::wake);
        jobControl.shutdown();
        try {
            if (!jobControl.awaitTermination(JOB_CONTROL_STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("Interrupting Splunk job control still running at logout");
                jobControl.shutdownNow();
            }
        } catch (InterruptedException e) {
            jobControl.shutdownNow();
            Thread.currentThread().interrupt();
        }
        cancelLoops.forEach(ticket -> log.info("Logged out with unconfirmed Splunk job cancels: {}", ticket));
    }

    /**
     * Load available indexes from Splunk with automatic session recovery.
     */
    public SortedSet<String> loadIndexes(ProgressCallback progressCallback)
            throws TemporarySearchException, SearchResultToBigException, SearchException, SearchCancelledException {
        return loadIndexes(progressCallback, new JobTicket());
    }

    /** Like {@link #loadIndexes(ProgressCallback)}, cancellable by SID through {@link #cancelJobs}. */
    public SortedSet<String> loadIndexes(ProgressCallback progressCallback, JobTicket ticket)
            throws TemporarySearchException, SearchResultToBigException, SearchException, SearchCancelledException {
        return withRecovery("loadIndexes", () -> {
            awaitEarlierAttempts(ticket);
            String q = "| eventcount summarize=false index=* | dedup index | fields index";

            progressCallback.onProgress(new SearchProgress(null, null, null, false, false, false, false, 0, 0, 0, 0,
                    0.0f, 0.0f, "Loading indexes...", false, 0));

            Instant end = clock.instant();

            JobArgs jobArgs = new JobArgs();
            Instant start = end.minus(7, ChronoUnit.DAYS);
            LoadDecision decision = LoadDecision.load(null, -1, start, end, "load indexes");
            Job job = runQuery(jobArgs, decision, q, ticket, progressCallback);

            var results = resultsReader(job, 0, 0);

            // Read results
            TreeSet<String> indexes = new TreeSet<>();

            for (Event event : results) {
                log.debug("index {}", event);
                String index = event.get("index");
                if (index != null) {
                    indexes.add(index);
                }
            }

            log.info("Found indexes: {}", indexes);
            return indexes;
        });
    }

    /**
     * Load field summaries for an index with automatic session recovery. Samples the newest
     * events, starting with a short window that ends at the index's newest event and widening it
     * while the sample is small ({@link FieldSampling}), so indexes without recent data still get
     * fields and busy ones stay fast. An index without events has no fields.
     */
    public SortedSet<FieldSummary> loadFieldSummary(String index, ProgressCallback progressCallback)
            throws TemporarySearchException, SearchException, SearchCancelledException {
        return loadFieldSummary(index, 0, progressCallback);
    }

    /**
     * Like {@link #loadFieldSummary(String, ProgressCallback)} with a deeper sample: see
     * {@link FieldSampling} for what {@code depth} (0 to {@link FieldSampling#MAX_DEPTH}) means.
     */
    public SortedSet<FieldSummary> loadFieldSummary(String index, int depth, ProgressCallback progressCallback)
            throws TemporarySearchException, SearchException, SearchCancelledException {
        return loadFieldSummary(index, depth, progressCallback, new JobTicket());
    }

    /** Like {@link #loadFieldSummary(String, int, ProgressCallback)}, cancellable through {@link #cancelJobs}. */
    public SortedSet<FieldSummary> loadFieldSummary(String index, int depth, ProgressCallback progressCallback,
            JobTicket ticket) throws TemporarySearchException, SearchException, SearchCancelledException {
        return loadFieldSummaryForScope(Scope.ofValues(index, Map.of()), depth, progressCallback, ticket);
    }

    /**
     * Load field summaries for {@code scope} with automatic session recovery. Samples the newest matching events
     * within windows anchored at the index selection's newest event ({@link FieldSampling}), cancellable through
     * {@link #cancelJobs}.
     */
    public SortedSet<FieldSummary> loadFieldSummaryForScope(Scope scope, int depth, ProgressCallback progressCallback,
            JobTicket ticket) throws TemporarySearchException, SearchException, SearchCancelledException {
        String index = scope == null ? null : scope.index();
        return withRecovery("loadFieldSummary", () -> {
            awaitEarlierAttempts(ticket);
            SortedSet<FieldSummary> fieldSummaries = new TreeSet<>();
            Optional<Instant> anchor = latestEventTime(index, progressCallback, ticket);
            if (anchor.isEmpty()) {
                log.info("Field summary for index {}: no events", index);
                return fieldSummaries;
            }
            Instant end = FieldSampling.windowEnd(anchor.get());
            String query = FieldSampling.query(scope, depth);

            for (int step = 0; step < FieldSampling.STEPS; step++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new SearchCancelledException("Field summary for " + index + " cancelled",
                            new InterruptedException());
                }
                Instant start = FieldSampling.windowStart(step, anchor.get());
                log.info("Loading field summary for index {} over [{}, {})", index, start, end);

                LoadDecision decision = LoadDecision.load(null, -1, start, end,
                        "load field summary for index " + index + " (step " + (step + 1) + ")");
                Job job = runQuery(new JobArgs(), decision, query, ticket, progressCallback);

                fieldSummaries = new TreeSet<>();
                long sampled = readFieldSummaries(job, fieldSummaries);
                log.info("Field summary for index {} over [{}, {}): {} fields from {} events", index, start, end,
                        fieldSummaries.size(), sampled);
                if (FieldSampling.isDone(step, sampled, depth)) {
                    break;
                }
            }
            return fieldSummaries;
        });
    }

    /** The time of the newest event in {@code index} up to now, or empty when it has none. */
    Optional<Instant> latestEventTime(String index, ProgressCallback progressCallback, JobTicket ticket)
            throws TemporarySearchException, SearchResultToBigException, SearchException, SearchCancelledException {
        LoadDecision decision = LoadDecision.load(null, -1, SearchBoundaries.MIN_MINIMUM_BOUNDARY, clock.instant(),
                "find the newest event of index " + index);
        Optional<Instant> latest;
        try {
            latest = runLatestEventQuery(FieldSampling.latestEventQuery(index), decision, progressCallback, ticket);
        } catch (HttpException | SearchException | TemporarySearchException e) {
            // Splunk may refuse the tstats query (on Enterprise e.g. "WHERE clause is not an exact
            // query", when a role's search filter is ANDed into it): the job is created but fails at
            // once (waitUntil reports it), or job creation answers 400. Any such failure falls back
            // to a plain search; a session or network error goes on to the recovery handling, and a
            // shutdown is not retried.
            if (isSessionError(e) || isBusy(e) || ExceptionMessageFormatter.isNetworkError(e) || shuttingDown) {
                throw e;
            }
            log.info("tstats failed for index {} ({}); reading the newest event instead", index, rootMessage(e));
            latest = runLatestEventQuery(FieldSampling.latestEventFallbackQuery(index), decision, progressCallback,
                    ticket);
        }
        log.info("Newest event of index {}: {}", index, latest.orElse(null));
        return latest;
    }

    Optional<Instant> runLatestEventQuery(String query, LoadDecision decision, ProgressCallback progressCallback,
            JobTicket ticket)
            throws TemporarySearchException, SearchResultToBigException, SearchException, SearchCancelledException {
        Job job = runQuery(new JobArgs(), decision, query, ticket, progressCallback);
        for (Event event : resultsReader(job, 0, 0)) {
            return parseEpochSeconds(event.get(FieldSampling.LATEST_FIELD));
        }
        return Optional.empty();
    }

    private static boolean isBusy(Throwable e) {
        return e instanceof HttpException http && http.getStatus() == 503;
    }

    /** Whether {@code e} or one of its causes is a Splunk 401/403: the session is gone. */
    static boolean isSessionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpException http && (http.getStatus() == 401 || http.getStatus() == 403)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    /** Splunk epoch seconds, possibly fractional; empty when absent or unparseable. */
    static Optional<Instant> parseEpochSeconds(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal seconds = new BigDecimal(value.strip());
            return Optional.of(Instant.ofEpochSecond(seconds.longValue(),
                    seconds.remainder(BigDecimal.ONE).movePointRight(9).intValue()));
        } catch (NumberFormatException | ArithmeticException | java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Adds the fields of a done field summary job to {@code into}.
     *
     * @return the number of events sampled: the highest per-field count, since some field
     *         (such as {@code host}) is present on every event
     */
    private static long readFieldSummaries(Job job, SortedSet<FieldSummary> into) throws SearchException {
        var results = resultsReader(job, 0, 0);

        long sampled = 0;
        for (Event event : results) {
            log.trace("field summary {}", event);
            into.add(new FieldSummary(event));
            sampled = Math.max(sampled, parseCount(event.get("count")));
        }
        return sampled;
    }

    private static long parseCount(String count) {
        try {
            return count == null ? 0 : Long.parseLong(count.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Queries events between start and end with progress monitoring.
     * <p>
     * <b>INTERVAL SEMANTICS:</b> Uses half-open interval [start, end) where:
     * <ul>
     *   <li>Start time is INCLUSIVE: events at exactly 'start' are included</li>
     *   <li>End time is EXCLUSIVE: events at exactly 'end' are NOT included</li>
     * </ul>
     * This matches Splunk's time-based search behavior where earliest is inclusive
     * and latest is exclusive.
     * <p>
     * Adjacent queries can be made without gaps or overlaps:
     * <pre>
     * Query 1: [14:00:00, 14:05:00)  // events from 14:00:00 to 14:04:59.999...
     * Query 2: [14:05:00, 14:10:00)  // events from 14:05:00 to 14:09:59.999...
     * Event at exactly 14:05:00 appears only in Query 2
     * </pre>
     *
     * @param decision         the load decision with start and end times
     * @param progressCallback callback for progress updates during search
     * @param baselineQuery    the baseline query string
     * @return reader for the query results
     * @throws SearchException            if search fails
     * @throws TemporarySearchException   if search is interrupted
     * @throws SearchResultToBigException if result count exceeds acceptable limit
     */
    public Stream<Event> queryEventsWithMonitoring(LoadDecision decision, ProgressCallback progressCallback,
            String baselineQuery)
            throws SearchException, TemporarySearchException, SearchResultToBigException, SearchCancelledException {
        return queryEventsWithMonitoring(decision, progressCallback, baselineQuery, new JobTicket());
    }

    /**
     * Like {@link #queryEventsWithMonitoring(LoadDecision, ProgressCallback, String)}, with the
     * jobs recorded on {@code ticket} so another thread can cancel them by SID
     * ({@link #cancelJobs(JobTicket)}).
     * <p>
     * Every attempt (a relogin retry is a new attempt) creates its job with a new client-chosen SID.
     * A cancelled ticket starts no job; a job whose create returns after the cancel is cancelled.
     * Any failure before the event stream is returned cancels that attempt's job in the background.
     * A retry starts its search only once the earlier attempts' jobs are confirmed not searching
     * (it waits, interruptibly, for their cancels), so a retry never adds a concurrent search.
     */
    public Stream<Event> queryEventsWithMonitoring(LoadDecision decision, ProgressCallback progressCallback,
            String baselineQuery, JobTicket ticket)
            throws SearchException, TemporarySearchException, SearchResultToBigException, SearchCancelledException {
        Objects.requireNonNull(ticket, "ticket");
        return withRecovery("queryEvents", () -> retryWhileBusy(() -> {
            awaitEarlierAttempts(ticket);
            String sid = ticket.nextSid();
            if (!ticket.register(sid)) {
                throw new SearchCancelledException("Search cancelled before it started", null);
            }
            boolean handedOver = false;
            try {
                Job job = startSearch(decision, progressCallback, baselineQuery, ticket, sid);
                Stream<Event> events = decision.withinOneSecond() ? pagedEventsReader(job) : eventsReader(job);
                handedOver = true;
                return events;
            } finally {
                if (!handedOver) {
                    ticket.attemptFailed(sid);
                    ensureCancelling(ticket);
                }
            }
        }));
    }

    /**
     * Before a retry: wait until the jobs of the earlier attempts can no longer be searching. Their
     * cancels run on the current session, which a relogin may have replaced, so start a pass now.
     */
    private void awaitEarlierAttempts(JobTicket ticket) throws TemporarySearchException {
        List<String> earlier = ticket.searching();
        if (earlier.isEmpty()) {
            return;
        }
        log.info("Waiting for the cancel of earlier attempts before retrying: {}", ticket);
        ensureCancelling(ticket);
        try {
            if (!ticket.awaitNotSearching(earlier, earlierAttemptWaitMillis)) {
                throw new TemporarySearchException(
                        "Not retrying: the previous search could not be confirmed cancelled on Splunk");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchCancelledException("Superseded while waiting for an earlier attempt's cancel", e);
        }
    }

    private static Stream<Event> eventsReader(Job job) throws SearchException {
        return eventsReader(job, 0, 0);
    }

    /**
     * Read all of the job's events in pages of {@link #MAX_RESULT_ROWS}, since a single
     * events request is capped there. Pages are opened one at a time as the stream is consumed.
     */
    private static Stream<Event> pagedEventsReader(Job job) {
        int eventCount = job.getEventCount();
        if (eventCount <= MAX_RESULT_ROWS) {
            return eventsReaderUnchecked(job, 0, 0);
        }
        int pages = (eventCount + MAX_RESULT_ROWS - 1) / MAX_RESULT_ROWS;
        log.info("Reading {} events in {} pages: {}", eventCount, pages, job);
        return Stream.iterate(0, offset -> offset < eventCount, offset -> offset + MAX_RESULT_ROWS)
                .flatMap(offset -> eventsReaderUnchecked(job, offset, MAX_RESULT_ROWS));
    }

    private static Stream<Event> eventsReaderUnchecked(Job job, int offset, int count) {
        try {
            return eventsReader(job, offset, count);
        } catch (SearchException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static Stream<Event> eventsReader(Job job, int offset, int count) throws SearchException {
        BufferedInputStream eventsStream = null;
        try {

            var eventsArgs = new JobEventsArgs();
            eventsArgs.setCount(count);
            eventsArgs.setOutputMode(JobEventsArgs.OutputMode.JSON);
            eventsArgs.setOffset(offset);

            // Read results

            eventsStream = new BufferedInputStream(job.getEvents(eventsArgs));
            eventsStream.mark(64 * 1024); // Save the first bytes for extraction of error messages

            ResultsReaderJson reader = new ResultsReaderJson(eventsStream);

            // Convert reader iterator to iterable, then to stream with auto-close on stream termination
            var iterator = reader.iterator();
            Iterable<Event> iterable = () -> iterator;

            return java.util.stream.StreamSupport.stream(iterable.spliterator(), false).onClose(() -> {
                try {
                    reader.close();
                } catch (Exception ex) {
                    log.warn("Failed to close ResultsReaderJson", ex);
                }
            });

        } catch (Exception e) {
            // dump stream
            String streamMsg = "no data";
            try {
                if (eventsStream != null) {
                    eventsStream.reset();
                    // string from stream
                    streamMsg = new String(eventsStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("Error reading events: {} : {}", job, streamMsg, e);
                throw new SearchException("Error reading log", e);
            } catch (IOException ex) {
                throw new SearchException("Error reading log", ex);
            }
        }
    }

    private static ResultsReaderJson resultsReader(Job job, int count, int offset) throws SearchException {
        BufferedInputStream resultsStream = null;
        try {

            var resultsArgs = new JobResultsArgs();
            resultsArgs.setCount(count);
            resultsArgs.setOutputMode(JobResultsArgs.OutputMode.JSON);
            resultsArgs.setOffset(offset);

            // Read results

            resultsStream = new BufferedInputStream(job.getResults(resultsArgs));
            if (dumpToFile != null) {
                try (var fos = newOutputStream(Path.of(dumpToFile + "." + currentTimeMillis() + ".json"))) {
                    resultsStream.mark(16 * 1024 * 1024);
                    resultsStream.transferTo(fos);
                    resultsStream.reset();
                } catch (Exception e) {
                    log.error("Error dumping to file {}: {}", dumpToFile, e.getMessage());
                }
            }
            resultsStream.mark(16 * 1024); // Save the first 16kB for extraction of error messages

            ResultsReaderJson reader = new ResultsReaderJson(resultsStream);
            return reader;

        } catch (Exception e) {
            // dump stream
            String streamMsg = "no data";
            try {
                if (resultsStream != null) {
                    resultsStream.reset();
                    // string from stream
                    streamMsg = new String(resultsStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                log.error("Error reading events: {} : {}", job, streamMsg, e);
                throw new SearchException("Error reading log", e);
            } catch (IOException ex) {
                throw new SearchException("Error reading log", ex);
            }
        }
    }

    private void waitUntil(Job job, ProgressCallback progressCallback, LoadDecision decision)
            throws TemporarySearchException, SearchResultToBigException, SearchCancelledException {

        int sleep = 500;

        while (!job.isReady()) {
            var progress = buildSearchProgress(job, decision);
            log.debug("Waiting for ready: {}, {}", progress, decision);
            publishProgress(progressCallback, progress);
            checkShuttingDown(decision, job);
            sleep(job, sleep);
        }

        while (!job.isDone()) {
            // Build progress snapshot for logging and callbacks
            var progress = buildSearchProgress(job, decision);
            log.debug("Waiting for done: {}, {}", progress, decision);
            publishProgress(progressCallback, progress);

            checkShuttingDown(decision, job);

            if (progress.isFailed() || progress.isFinalized()) {
                log.error("Search failed or was finalized. Job state: {}", progress);
                job.finish();
                publishProgress(progressCallback, progress);
                throw new TemporarySearchException(
                        "Search failed or was finalized" + (progress.isFailed() ? failureDetail(job) : ""));
            }

            if (job.getEventIsTruncated()) {
                log.error("Events truncated (in wait): {}", job);
                publishProgress(progressCallback, SearchProgress.FAIL);
                throw searchResultTooBigException(job, progress);
            }

            // Check maxEvents limit if set - for early abort during binary search forward
            if (decision.maxEvents().isPresent()) {
                int maxEvents = decision.maxEvents().getAsInt();
                if (progress.resultCount() >= maxEvents || progress.eventCount() >= maxEvents) {
                    log.info("Result count {} or event count {} reached maxEvents limit {}, aborting early",
                            progress.resultCount(), progress.eventCount(), maxEvents);
                    job.finish();
                    publishProgress(progressCallback, progress);
                    throw searchResultTooBigException(job, progress);
                }
            }

            sleep(job, sleep);

            sleep = Integer.min(4500, (int) (sleep * 2));
        }

        // Final progress update
        SearchProgress finalProgress = buildSearchProgress(job, decision);
        publishProgress(progressCallback, finalProgress);

        // A job Splunk fails at once (e.g. a WHERE clause tstats cannot run) is done before the
        // loop above sees it; report it here instead of letting the results read fail later.
        if (finalProgress.isFailed()) {
            log.error("Search failed: {} for {}", finalProgress, decision);
            throw new TemporarySearchException("Search failed" + failureDetail(job));
        }

        // Log final statistics using progress object
        log.info("Search complete: {} for {}", finalProgress, decision);
    }

    /**
     * Splunk's own reason for a failed job, from its {@code messages} (fatal and error entries),
     * as {@code ": <text>"}; empty when there is none or it cannot be read.
     */
    static String failureDetail(Job job) {
        try {
            if (job.get("messages") instanceof Map<?, ?> messages) {
                String text = Stream.of("fatal", "error").map(messages::get).filter(Objects::nonNull)
                        .flatMap(value -> value instanceof Collection<?> list ? list.stream() : Stream.of(value))
                        .map(String::valueOf).filter(s -> !s.isBlank()).collect(Collectors.joining("; "));
                return text.isEmpty() ? "" : ": " + text;
            }
        } catch (RuntimeException e) {
            log.debug("Could not read the messages of job {}: {}", job.getSid(), e.getMessage());
        }
        return "";
    }

    private void checkShuttingDown(LoadDecision decision, Job job) throws TemporarySearchException {
        if (shuttingDown) {
            log.info("Shutting down - aborting {}", decision);
            cancelJobQuietly(job);
            throw new TemporarySearchException("Shutdown in progress");
        }
    }

    private void sleep(Job job, int sleep) throws SearchCancelledException {
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            cancelJobQuietly(job);
            throw new SearchCancelledException("Interrupted while waiting for search to start", e);
        }
    }

    private static SearchResultToBigException searchResultTooBigException(Job job, SearchProgress progress) {
        try {

            var firstLast = calculateTooBigVelocity(eventsReader(job));

            return new SearchResultToBigException("Result count too high (" + firstLast + ")", firstLast.count(),
                    firstLast.first, firstLast.last);

        } catch (Exception e) {
            log.error("Error reading results to determine size: {}", e.getMessage());
            throw new SearchResultToBigException("Result count too high, and error reading results",
                    progress.resultCount(), null, null);
        }
    }

    record TooBigVelocity(int count, Instant first, Instant last) {}

    // Package-private for testing
    static TooBigVelocity calculateTooBigVelocity(Stream<Event> stream) {

        try (stream) {

            Iterator<Event> it = stream.iterator();
            if (!it.hasNext()) {
                return null;
            }
            Event first = it.next();

            Instant firstTimestamp = Instant.parse(first.get("_time"));
            Instant lastTimestamp = firstTimestamp;

            int i = 1;
            while (it.hasNext()) {

                i++;
                var next = it.next();
                Instant t = Instant.parse(next.get("_time"));
                if (t.isAfter(lastTimestamp)) {
                    lastTimestamp = t;
                }
                if (t.isBefore(firstTimestamp)) {
                    firstTimestamp = t;
                }
            }
            return new TooBigVelocity(i, firstTimestamp, lastTimestamp);
        }
    }

    private static void publishProgress(ProgressCallback progressCallback, SearchProgress progress) {
        // Progress callback
        if (progressCallback != null) {
            progressCallback.onProgress(progress);
        }
    }

    /**
     * Cancels a Splunk job quietly - best effort, no exceptions thrown.
     * Used during shutdown to release server resources.
     * Tracks cancelled jobs in LRU to avoid cancelling the same job twice.
     */
    private void cancelJobQuietly(Job job) {
        if (job == null) return;
        String sid = job.getSid();
        if (sid == null) return;

        synchronized (cancelledJobs) {
            if (cancelledJobs.contains(sid)) {
                log.debug("Job {} already cancelled, skipping", sid);
                return;
            }
            cancelledJobs.add(sid);
        }

        try {
            log.info("Cancelling Splunk job: {}", sid);
            job.cancel();
        } catch (Exception e) {
            log.debug("Could not cancel job {}: {}", sid, e.getMessage());
        }
    }

    /**
     * Cancel a request on Splunk: no further attempt of {@code ticket} starts a job, and every job
     * of it that may still be searching is cancelled by SID through the current session. Returns at
     * once; the cancels run on a separate thread, one cancel loop per ticket, retrying unconfirmed
     * cancels after 1, 3 and then every 10 s until {@link JobTicket#whenQuiet()} or logout. Quiet:
     * never prompts, never relogs in, also works while shutting down (until {@link #logout()}).
     */
    public void cancelJobs(JobTicket ticket) {
        ticket.cancelRequested();
        ensureCancelling(ticket);
    }

    /** Start the ticket's cancel loop unless it runs already (a running loop is woken instead). */
    private void ensureCancelling(JobTicket ticket) {
        if (jobControlStopping || !ticket.startCancelLoop()) {
            return;
        }
        cancelLoops.add(ticket);
        try {
            jobControl.execute(() -> runCancelLoop(ticket));
        } catch (RejectedExecutionException e) {
            cancelLoops.remove(ticket);
            ticket.endCancelLoop();
            log.info("Not cancelling after logout: {}", ticket);
        }
    }

    private void runCancelLoop(JobTicket ticket) {
        try {
            int retries = 0;
            while (!jobControlStopping) {
                ticket.takeCancelWork();
                cancelPass(ticket);
                // Remove first: once the loop has ended, a new loop may register the ticket again
                cancelLoops.remove(ticket);
                if (ticket.endCancelLoopIfIdle()) {
                    return;
                }
                cancelLoops.add(ticket);
                // New work (a job just created, a relogin) is handled at once; only a wait that
                // ran out moves on to the next, longer delay
                if (!ticket.awaitCancelWork(CANCEL_RETRY_MS[Math.min(retries, CANCEL_RETRY_MS.length - 1)])) {
                    retries++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Cancel loop failed for {}", ticket, e);
        }
        // Stopped by logout (or failed): give up, nothing confirms the remaining cancels
        cancelLoops.remove(ticket);
        ticket.endCancelLoop();
        log.info("Stopped cancelling: {}", ticket);
    }

    /**
     * One cancel request per target SID through the current session. A 404 proves a created job
     * gone. For a create without an answer it proves nothing (Splunk may still execute the request
     * later), so such a SID stays possibly searching until a cancel succeeds, or until logout: no
     * time limit releases it. Failures are left for the next pass.
     */
    private void cancelPass(JobTicket ticket) {
        Service current = service;
        if (current == null) {
            return;
        }
        for (String sid : ticket.cancelTargets()) {
            JobTicket.SidState before = ticket.state(sid);
            try {
                // Not Job.cancel(): it hides the 404 that tells "unknown to Splunk" from "cancelled"
                current.getJob(sid).control("cancel");
                ticket.gone(sid);
                log.info("Cancelled Splunk job {}", sid);
            } catch (HttpException e) {
                if (e.getStatus() != 404) {
                    log.debug("Cancel of job {} failed: {}", sid, e.getMessage());
                } else if (before != JobTicket.SidState.AMBIGUOUS) {
                    ticket.gone(sid);
                } else {
                    log.debug("Job {} unknown to Splunk; its create may still arrive", sid);
                }
            } catch (RuntimeException e) {
                log.debug("Cancel of job {} failed: {}", sid, e.toString());
            }
            ticket.cancelTried(sid);
        }
    }

    /**
     * Builds a SearchProgress object from the current job state.
     * Safely handles jobs that aren't ready yet by only querying available fields.
     *
     * @param job The Splunk job to extract progress from
     * @return SearchProgress object with all safely accessible fields populated
     */
    private SearchProgress buildSearchProgress(Job job, LoadDecision decision) {

        // isReady() is safe to call - it just checks state without throwing
        boolean isReady = job.isReady();

        // SID is always available
        String jobId = job.getSid();

        // isDone() is safe - it calls isReady() internally and returns false if not ready
        boolean isDone = job.isDone();

        // If the job isn't ready, most other methods will throw exceptions
        // So we need to check and provide safe defaults
        if (!isReady) {
            return new SearchProgress(jobId, decision.start(), decision.end(), true, false, false, false, 0, 0, 0, 0,
                    0.0f, 0.0f, "NOT_READY", false, decision.direction());
        }

        float progress = job.getDoneProgress();
        boolean indeterminate = !isDone && (progress == 1f || progress == 0f);

        // Job is ready - safe to query all fields (they call checkReady() internally)
        return new SearchProgress(jobId, decision.start(), decision.end(), indeterminate, true, isDone, job.isFailed(),
                job.getScanCount(), job.getEventCount(), job.getResultCount(), job.getResultPreviewCount(),
                job.getDoneProgress(), job.getRunDuration(), job.getDispatchState(), job.isFinalized(),
                decision.direction());
    }

    /**
     * Default Splunk REST API limit for events endpoint (limits.conf [restapi] maxresultrows).
     * The events endpoint silently caps responses at this many rows while the job
     * reports eventIsTruncated=false, so we must check eventCount ourselves.
     */
    static final int MAX_RESULT_ROWS = 50_000;

    private Job startSearch(LoadDecision decision, ProgressCallback progressCallback, String baselineQuery,
            JobTicket ticket, String sid)
            throws TemporarySearchException, SearchException, SearchResultToBigException, SearchCancelledException {
        if (decision.withinOneSecond() && decision.maxEvents().isPresent()) {
            // A window within one second cannot be narrowed: read it completely, not capped.
            decision = decision.withoutMaxEvents();
        }
        JobArgs jobArgs = new JobArgs();
        jobArgs.setId(sid);
        String query = prepareQuery(jobArgs, decision, baselineQuery);
        Job job = createJob(query, jobArgs, ticket, sid);
        waitUntil(job, progressCallback, decision);
        ticket.done(sid);

        var isTruncated = job.getEventIsTruncated();
        if (isTruncated) {
            log.error("Events truncated: {}", job);
            throw searchResultTooBigException(job, buildSearchProgress(job, decision));
        }

        // The events endpoint caps at MAX_RESULT_ROWS (Splunk default 50k) but the job
        // reports eventIsTruncated=false. Detect silent truncation by comparing the
        // total event count against the API limit. A window within one second cannot be
        // narrowed, so it is read in pages instead.
        int eventCount = job.getEventCount();
        if (eventCount > MAX_RESULT_ROWS && !decision.withinOneSecond()) {
            log.error("Event count {} exceeds REST API limit {} (silent truncation would occur): {}", eventCount,
                    MAX_RESULT_ROWS, job);
            throw new SearchResultToBigException(
                    "Event count " + eventCount + " exceeds REST API limit " + MAX_RESULT_ROWS, eventCount, null, null);
        }

        var dispatchState = job.getDispatchState();
        if (!"DONE".equals(dispatchState)) {
            throw new TemporarySearchException("Search dispatch state indicates incomplete results: " + dispatchState);
        }

        return job;
    }

    /**
     * Run one job of {@code ticket} (with a new SID) to completion. A job that fails or is
     * superseded before it is done is cancelled by SID in the background.
     */
    private Job runQuery(JobArgs jobArgs, LoadDecision decision, String query, JobTicket ticket,
            ProgressCallback progressCallback)
            throws TemporarySearchException, SearchException, SearchResultToBigException, SearchCancelledException {
        return retryWhileBusy(() -> {
            String sid = ticket.nextSid();
            if (!ticket.register(sid)) {
                throw new SearchCancelledException("Search cancelled before it started", null);
            }
            boolean done = false;
            try {
                jobArgs.setId(sid);
                Job job = createJob(prepareQuery(jobArgs, decision, query), jobArgs, ticket, sid);
                waitUntil(job, progressCallback, decision);
                ticket.done(sid);
                done = true;
                return job;
            } finally {
                if (!done) {
                    ticket.attemptFailed(sid);
                    ensureCancelling(ticket);
                }
            }
        });
    }

    /**
     * Create the job of {@code sid} and return it once Splunk has it. The create request runs on its
     * own thread and is never interrupted, so its outcome is always recorded on the ticket: created,
     * rejected (Splunk answered with an error, no job), not sent, or ambiguous (no answer: the job
     * may exist and is cancelled). A superseded caller stops waiting at once; a job created after
     * its ticket was cancelled is cancelled. Unlike {@code JobCollection.create}, the POST is
     * separate from the first status read, so a failing read cannot hide that the job exists.
     */
    private Job createJob(String query, JobArgs jobArgs, JobTicket ticket, String sid)
            throws SearchCancelledException {
        Service current = service;
        if (Thread.currentThread().isInterrupted() || current == null) {
            ticket.notSent(sid);
            throw new SearchCancelledException("Search superseded before it was created", null);
        }
        CompletableFuture<Boolean> create;
        try {
            create = CompletableFuture.supplyAsync(() -> postCreate(current, query, jobArgs, ticket, sid), jobControl);
        } catch (RejectedExecutionException e) {
            ticket.notSent(sid);
            throw new SearchCancelledException("Splunk client is logged out", null);
        }
        boolean usable;
        try {
            usable = create.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchCancelledException("Superseded while its search was created", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpException http && http.getStatus() == 503) {
                throw new BusyRefusal(http);
            }
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
        if (!usable) {
            throw new SearchCancelledException("Search cancelled while it was created", null);
        }
        // The job exists: if this read fails, the attempt's cleanup cancels it
        Job job = current.getJob(sid);
        job.refresh();
        return job;
    }

    /** POST the create and record its outcome. Runs on {@link #jobControl}. */
    private boolean postCreate(Service current, String query, JobArgs jobArgs, JobTicket ticket, String sid) {
        String path;
        try {
            path = current.enableV2SearchApi() ? "search/v2/jobs" : "search/jobs";
        } catch (RuntimeException e) {
            ticket.notSent(sid);
            throw e;
        }
        ResponseMessage response;
        try {
            // HttpService.send returns only after the status line arrived and throws HttpException
            // (having read the error body) for any status >= 400
            response = current.post(path, Args.create(jobArgs).add("search", query));
        } catch (HttpException e) {
            if (e.getStatus() / 100 == 4 || e.getStatus() == 503) {
                ticket.rejected(sid);
                if (e.getStatus() == 503) {
                    log.warn("Splunk refused search {} (HTTP 503): {}", sid,
                            StringUtils.defaultIfBlank(e.getDetail(), e.getMessage()));
                }
            } else {
                ticket.createFailed(sid);
                ensureCancelling(ticket);
            }
            throw e;
        } catch (RuntimeException e) {
            if (neverSent(e)) {
                ticket.notSent(sid);
            } else {
                ticket.createFailed(sid);
                ensureCancelling(ticket);
            }
            throw e;
        }
        // Splunk accepted the create of our SID: record the job before touching the body, so a
        // failing body read cannot hide it
        boolean usable = ticket.created(sid);
        if (!usable) {
            ensureCancelling(ticket);
        }
        readCreateResponse(response, sid);
        return usable;
    }

    /** Longest create response read; Splunk answers with a few bytes ({@code <response><sid>..}). */
    private static final int CREATE_RESPONSE_LIMIT = 64 * 1024;

    /**
     * Read (bounded) and close the create response so its connection is released, and check that
     * Splunk kept the SID the client chose. A failing read only loses the check: the job is recorded.
     */
    private static void readCreateResponse(ResponseMessage response, String sid) {
        InputStream content = response.getContent();
        if (content == null) {
            return;
        }
        try (content) {
            String body = new String(content.readNBytes(CREATE_RESPONSE_LIMIT), StandardCharsets.UTF_8);
            if (!body.contains("<sid>" + sid + "</sid>")) {
                log.error("Splunk did not confirm the requested SID {} (HTTP {}): {}", sid, response.getStatus(),
                        StringUtils.abbreviate(body, 500));
            }
        } catch (IOException e) {
            log.debug("Could not read the create response of job {}: {}", sid, e.toString());
        }
    }

    /** The request failed before it could reach Splunk (connect or TLS handshake failed). */
    static boolean neverSent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.NoRouteToHostException || t instanceof javax.net.ssl.SSLHandshakeException
                    || (t instanceof java.net.SocketTimeoutException && t.getMessage() != null
                            && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connect"))) {
                return true;
            }
        }
        return false;
    }

    private String prepareQuery(JobArgs jobArgs, LoadDecision decision, String query) {

        // Append |head N to query when maxEvents is set for early truncation detection
        String effectiveQuery = query;
        if (decision.maxEvents().isPresent()) {
            int maxEvents = decision.maxEvents().getAsInt();
            // effectiveQuery = query + " | head " + maxEvents;
            jobArgs.setMaximumCount(maxEvents);

            log.debug("Setting maximum count {}", maxEvents);
        }

        log.debug("Starting query: {}", effectiveQuery);
        // Run a simple search by first creating the search job

        jobArgs.setEarliestTime(earliestTimeArg(decision.start()));
        jobArgs.setLatestTime(decision.end().toString());
        //  jobArgs.setStatusBuckets(300);

        return effectiveQuery;
    }

    /**
     * The {@code earliest_time} to send for a search starting at {@code start}.
     * <p>
     * Splunk rejects {@code 1970-01-01T00:00:00Z} and treats {@code earliest_time=0} as "time is
     * not used", so anything before {@link SearchBoundaries#MIN_MINIMUM_BOUNDARY} (epoch plus one
     * year) is lifted to that instant. Later starts are sent as ISO-8601, which Splunk accepts.
     */
    static String earliestTimeArg(Instant start) {
        Instant earliest = start.isBefore(SearchBoundaries.MIN_MINIMUM_BOUNDARY)
                ? SearchBoundaries.MIN_MINIMUM_BOUNDARY
                : start;
        return earliest.toString();
    }
}
