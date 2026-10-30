package se.grynna.pager.splunk;

import com.splunk.Event;
import com.splunk.Service;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.exceptions.SearchResultToBigException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Splunk job lifecycle of a load against a fake Splunk REST API (plain HTTP): client-chosen
 * SIDs, cancel by SID from another thread, cleanup of failed attempts and relogin retries.
 */
class SplunkClientJobLifecycleTest {

    private static final Instant T = Instant.parse("2017-03-30T10:00:00Z");
    private static final LoadDecision DECISION = LoadDecision.load(null, -1, T, T.plusSeconds(60), "test");
    private static final Pattern JOB_PATH = Pattern.compile("/services/search/(?:v2/)?jobs/([^/?]+)(/[a-z]+)?.*");

    private MockWebServer server;
    private FakeSplunk splunk;
    private TestClient client;

    /** Client without a real connect; reconnects (relogin) keep the fake service. */
    static final class TestClient extends SplunkClient {
        final AtomicInteger reconnects = new AtomicInteger();

        TestClient() {
            super(Clock.systemUTC());
        }

        @Override
        void reconnect(String newPassword) {
            reconnects.incrementAndGet();
        }
    }

    /** What a job status GET answers: by SID and by how many GETs that SID has had. */
    @FunctionalInterface
    interface StatusAnswer {
        MockResponse answer(String sid, int get);
    }

    /** How the fake answers a create request. */
    enum CreatePolicy {
        CREATE, CREATE_BROKEN_BODY, NO_RESPONSE, DISCONNECT, REJECT_503
    }

    /** Minimal Splunk: create, status, events and cancel of search jobs. */
    static final class FakeSplunk extends Dispatcher {
        final List<String> createRequests = new CopyOnWriteArrayList<>();
        final List<String> createdIds = new CopyOnWriteArrayList<>();
        final List<String> cancelRequests = new CopyOnWriteArrayList<>();
        final List<String> confirmedCancels = new CopyOnWriteArrayList<>();
        /** Creates and confirmed cancels in the order Splunk saw them. */
        final List<String> timeline = new CopyOnWriteArrayList<>();
        final Set<String> existing = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> statusGets = new ConcurrentHashMap<>();
        final CountDownLatch createReceived = new CountDownLatch(1);
        volatile CountDownLatch holdCreate; // when set, the create waits for it
        volatile CreatePolicy createPolicy = CreatePolicy.CREATE;
        /** Status to answer a cancel of the SID with instead of the normal outcome (0: normal). */
        volatile java.util.function.ToIntFunction<String> cancelOverride = sid -> 0;
        volatile StatusAnswer status = (sid, get) -> jobEntry(sid, "DONE", 0);

        @Override
        public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            String path = request.getPath();
            String method = request.getMethod();
            if ("POST".equals(method) && path.matches("/services/search/(v2/)?jobs/?")) {
                String id = form(request.getBody().readUtf8()).get("id");
                createRequests.add(id);
                createReceived.countDown();
                switch (createPolicy) {
                    case NO_RESPONSE -> {
                        return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
                    }
                    case DISCONNECT -> {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    case REJECT_503 -> {
                        return new MockResponse().setResponseCode(503).setBody(
                                "<response><messages><msg type=\"FATAL\">Search not executed: quota</msg></messages></response>");
                    }
                    case CREATE_BROKEN_BODY -> {
                        createdIds.add(id);
                        existing.add(id);
                        timeline.add("create " + id);
                        return new MockResponse().setResponseCode(201)
                                .setBody("<?xml version=\"1.0\"?><response><sid>" + id + "</sid></response>"
                                        + " ".repeat(4096))
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
                    }
                    case CREATE -> {
                        CountDownLatch hold = holdCreate;
                        if (hold != null) {
                            hold.await(10, SECONDS);
                        }
                        createdIds.add(id);
                        existing.add(id);
                        timeline.add("create " + id);
                        return new MockResponse().setResponseCode(201)
                                .setBody("<?xml version=\"1.0\"?><response><sid>" + id + "</sid></response>");
                    }
                }
            }
            Matcher m = JOB_PATH.matcher(path);
            if (!m.matches()) {
                return new MockResponse().setResponseCode(404);
            }
            String sid = m.group(1);
            String action = m.group(2);
            if ("/control".equals(action)) {
                cancelRequests.add(sid);
                int override = cancelOverride.applyAsInt(sid);
                if (override != 0) {
                    return new MockResponse().setResponseCode(override).setBody("<response/>");
                }
                if (!existing.remove(sid)) {
                    return new MockResponse().setResponseCode(404).setBody("<response/>");
                }
                confirmedCancels.add(sid);
                timeline.add("cancelled " + sid);
                return new MockResponse().setResponseCode(200).setBody("<response/>");
            }
            if ("/events".equals(action)) {
                return new MockResponse().setResponseCode(200)
                        .setBody("{\"preview\":false,\"init_offset\":0,\"messages\":[],\"results\":[]}");
            }
            int get = statusGets.computeIfAbsent(sid, s -> new AtomicInteger()).incrementAndGet();
            return status.answer(sid, get);
        }

        long cancelsOf(String sid) {
            return cancelRequests.stream().filter(sid::equals).count();
        }
    }

    static MockResponse jobEntry(String sid, String dispatchState, int eventCount) {
        boolean done = "DONE".equals(dispatchState);
        String keys = Map.of("dispatchState", dispatchState, "isDone", done ? "1" : "0", "isFailed", "0",
                "isFinalized", "0", "doneProgress", done ? "1.0" : "0.5", "scanCount", "0", "eventCount",
                Integer.toString(eventCount), "resultCount", "0", "resultPreviewCount", "0", "runDuration", "0.1")
                .entrySet().stream().map(e -> "<s:key name=\"" + e.getKey() + "\">" + e.getValue() + "</s:key>")
                .collect(Collectors.joining()) + "<s:key name=\"eventIsTruncated\">0</s:key><s:key name=\"sid\">"
                + sid + "</s:key>";
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<entry xmlns=\"http://www.w3.org/2005/Atom\" xmlns:s=\"http://dev.splunk.com/ns/rest\">"
                + "<title>search</title><id>http://localhost/services/search/jobs/" + sid + "</id>"
                + "<updated>2017-03-30T10:00:00.000+00:00</updated>"
                + "<link href=\"/services/search/jobs/" + sid + "\" rel=\"alternate\"/>"
                + "<published>2017-03-30T10:00:00.000+00:00</published>"
                + "<content type=\"text/xml\"><s:dict>" + keys + "</s:dict></content></entry>";
        return new MockResponse().setResponseCode(200).setBody(body);
    }

    static MockResponse unauthorized() {
        return new MockResponse().setResponseCode(401).setBody(
                "<response><messages><msg type=\"WARN\">call not properly authenticated</msg></messages></response>");
    }

    private static Map<String, String> form(String body) {
        return Stream.of(body.split("&")).map(kv -> kv.split("=", 2)).filter(kv -> kv.length == 2)
                .collect(Collectors.toMap(kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8), (a, b) -> a));
    }

    @BeforeEach
    void startFakeSplunk() throws Exception {
        splunk = new FakeSplunk();
        server = new MockWebServer();
        server.setDispatcher(splunk);
        server.start();
        Service service = new Service("localhost", server.getPort(), "http");
        service.setToken("Splunk test");
        service.version = "10.4.3";
        service.instanceType = "enterprise";
        service.setReadTimeout(20_000);
        client = new TestClient();
        client.setServiceForTest(service);
    }

    @AfterEach
    void stopFakeSplunk() throws Exception {
        client.logout();
        CountDownLatch hold = splunk.holdCreate;
        if (hold != null) {
            hold.countDown();
        }
        server.shutdown();
    }

    /** Run the query on a virtual thread, like a provider load. */
    private CompletableFuture<List<Event>> query(JobTicket ticket, Thread[] worker) {
        CompletableFuture<List<Event>> result = new CompletableFuture<>();
        worker[0] = Thread.ofVirtual().name("test-load").start(() -> {
            try (Stream<Event> events = client.queryEventsWithMonitoring(DECISION, progress -> {}, "search *",
                    ticket)) {
                result.complete(events.toList());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    private static Throwable failure(CompletableFuture<?> future) {
        await().atMost(5, SECONDS).until(future::isDone);
        return future.handle((value, failure) -> failure).join();
    }

    /** A ticket whose job exists on the fake Splunk. */
    private JobTicket ticketWithCreatedJob() {
        JobTicket ticket = new JobTicket();
        String sid = ticket.nextSid();
        ticket.register(sid);
        ticket.created(sid);
        splunk.existing.add(sid);
        return ticket;
    }

    @Test
    @DisplayName("The create request carries a client-chosen SID")
    void createPostContainsClientAssignedId() throws Exception {
        JobTicket ticket = new JobTicket();

        assertThat(query(ticket, new Thread[1]).get(5, SECONDS)).isEmpty();

        assertThat(splunk.createdIds).singleElement().satisfies(id -> {
            assertThat(id).matches("pager_[0-9a-f]{32}_1");
            assertThat(ticket.state(id)).isEqualTo(JobTicket.SidState.DONE);
        });
        assertThat(splunk.cancelRequests).as("a handed-over result is not cancelled").isEmpty();
        ticket.cancelRequested();
        assertThat(ticket.whenQuiet()).as("a done search is quiet once the request ends").isCompleted();
    }

    @Test
    @DisplayName("A ticket cancelled before the create never creates a job")
    void cancelledTicketNeverPosts() {
        JobTicket ticket = new JobTicket();
        ticket.cancelRequested();

        assertThat(failure(query(ticket, new Thread[1]))).isInstanceOf(SearchCancelledException.class);
        assertThat(server.getRequestCount()).isZero();
        assertThat(ticket.whenQuiet()).isCompleted();
    }

    @Test
    @DisplayName("An interrupted worker stops waiting at once; its create completes and the job is cancelled")
    void interruptDuringCreateCancelsTheJobOnceItExists() throws Exception {
        splunk.holdCreate = new CountDownLatch(1);
        JobTicket ticket = new JobTicket();
        Thread[] worker = new Thread[1];
        CompletableFuture<List<Event>> result = query(ticket, worker);
        assertThat(splunk.createReceived.await(5, SECONDS)).isTrue();

        worker[0].interrupt();

        assertThat(failure(result)).isNotNull();
        String sid = ticket.searching().getFirst();
        assertThat(ticket.state(sid)).as("the create is still in flight, never interrupted")
                .isEqualTo(JobTicket.SidState.REGISTERED);
        assertThat(splunk.cancelRequests).as("nothing to cancel before Splunk answers").isEmpty();

        splunk.holdCreate.countDown();

        await().atMost(3, SECONDS).until(() -> splunk.confirmedCancels.contains(sid));
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.GONE);
    }

    @Test
    @DisplayName("A load interrupted while polling is cancelled by its SID")
    void interruptDuringPollCancelsBySid() throws Exception {
        CountDownLatch pollHeld = new CountDownLatch(1);
        splunk.status = (sid, get) -> {
            if (get == 1) {
                return jobEntry(sid, "RUNNING", 0);
            }
            pollHeld.countDown();
            return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
        };
        JobTicket ticket = new JobTicket();
        Thread[] worker = new Thread[1];
        CompletableFuture<List<Event>> result = query(ticket, worker);
        assertThat(pollHeld.await(5, SECONDS)).isTrue();

        worker[0].interrupt();

        assertThat(failure(result)).isNotNull();
        String sid = splunk.createdIds.getFirst();
        await().atMost(3, SECONDS).until(() -> splunk.confirmedCancels.contains(sid));
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.GONE);
    }

    @Test
    @DisplayName("A failure after the search is done still cancels the job")
    void exceptionAfterWaitUntilCancelsJob() {
        splunk.status = (sid, get) -> jobEntry(sid, "DONE", SplunkClient.MAX_RESULT_ROWS + 1);
        JobTicket ticket = new JobTicket();

        assertThat(failure(query(ticket, new Thread[1]))).isInstanceOf(SearchResultToBigException.class);

        String sid = splunk.createdIds.getFirst();
        await().atMost(3, SECONDS).until(() -> splunk.confirmedCancels.contains(sid));
    }

    @Test
    @DisplayName("A failing first status read after a successful create still cancels the existing job")
    void refreshFailureAfterCreateMarksTheJobCreatedAndCancelsIt() {
        splunk.status = (sid, get) -> new MockResponse().setResponseCode(500).setBody("<response/>");
        JobTicket ticket = new JobTicket();

        assertThat(failure(query(ticket, new Thread[1]))).isNotNull();

        String sid = splunk.createdIds.getFirst();
        await().atMost(3, SECONDS).until(() -> splunk.confirmedCancels.contains(sid));
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.GONE);
    }

    @Test
    @DisplayName("A metadata search cancelled while polling is cancelled by its SID")
    void cancelledMetadataSearchIsCancelledBySid() throws Exception {
        splunk.status = (sid, get) -> jobEntry(sid, "RUNNING", 0);
        JobTicket ticket = new JobTicket();
        CompletableFuture<Object> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(client.loadIndexes(progress -> {}, ticket));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        await().atMost(5, SECONDS).until(() -> !splunk.createdIds.isEmpty());
        String sid = splunk.createdIds.getFirst();
        assertThat(ticket.allSids()).containsExactly(sid);

        client.cancelJobs(ticket);

        await().atMost(3, SECONDS).until(() -> splunk.confirmedCancels.contains(sid));
        assertThat(ticket.whenQuiet()).isCompleted();
    }

    @Test
    @DisplayName("A 503-refused create is retried 3 times with new SIDs, then fails; no job, notices throttled")
    void rejectedCreateLeavesNothingToCancel() {
        splunk.createPolicy = CreatePolicy.REJECT_503;
        client.busyRetryDelays = List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));
        List<String> busy = new java.util.concurrent.CopyOnWriteArrayList<>();
        client.setBusyListener((detail, gaveUp) -> busy.add((gaveUp ? "gave up: " : "retrying: ") + detail));
        JobTicket ticket = new JobTicket();

        assertThat(failure(query(ticket, new Thread[1]))).isNotNull();
        assertThat(splunk.createRequests).hasSize(4).doesNotHaveDuplicates();
        assertThat(failure(query(new JobTicket(), new Thread[1]))).isNotNull();
        assertThat(busy).as("one of each kind, throttled").hasSize(2).allMatch(s -> s.contains("quota"));
        assertThat(busy.get(0)).startsWith("retrying");
        assertThat(busy.get(1)).startsWith("gave up");

        assertThat(ticket.allSids()).hasSize(4)
                .allSatisfy(sid -> assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.REJECTED));
        ticket.cancelRequested();
        assertThat(ticket.whenQuiet()).isCompleted();
        assertThat(splunk.cancelRequests).isEmpty();
    }

    @Test
    @DisplayName("A busy retry that Splunk accepts returns the events; superseding the backoff sends no create")
    void busyRetrySucceedsOrIsSuperseded() throws Exception {
        splunk.createPolicy = CreatePolicy.REJECT_503;
        client.busyRetryDelays = List.of(Duration.ofSeconds(30));
        Thread[] worker = new Thread[1];
        CompletableFuture<List<Event>> superseded = query(new JobTicket(), worker);
        await().atMost(5, SECONDS).until(() -> splunk.createRequests.size() == 1);
        worker[0].interrupt();
        assertThat(failure(superseded)).isInstanceOf(SearchCancelledException.class);
        assertThat(splunk.createRequests).hasSize(1);

        client.busyRetryDelays = List.of(Duration.ofMillis(200));
        CompletableFuture<List<Event>> retried = query(new JobTicket(), new Thread[1]);
        await().atMost(5, SECONDS).until(() -> splunk.createRequests.size() == 2);
        splunk.createPolicy = CreatePolicy.CREATE;
        assertThat(retried.get(5, SECONDS)).isEmpty();
        assertThat(splunk.createdIds).singleElement().isEqualTo(splunk.createRequests.get(2));
    }

    @Test
    @DisplayName("A create that cannot connect was never sent: quiet at once")
    void refusedConnectionIsNotSent() throws Exception {
        MockWebServer closed = new MockWebServer();
        closed.start();
        int port = closed.getPort();
        closed.shutdown();
        Service unreachable = new Service("localhost", port, "http");
        unreachable.setToken("Splunk test");
        unreachable.version = "10.4.3";
        unreachable.instanceType = "enterprise";
        client.setServiceForTest(unreachable);
        JobTicket ticket = new JobTicket();

        assertThat(failure(query(ticket, new Thread[1]))).isNotNull();

        String sid = ticket.allSids().getFirst();
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.NOT_SENT);
        ticket.cancelRequested();
        assertThat(ticket.whenQuiet()).isCompleted();
    }

    @Test
    @DisplayName("Splunk unreachable pauses on one retry prompt (no password, same session); retry runs the work again")
    void unreachableSplunkPausesForOneRetryPromptWithoutRelogin() throws Exception {
        Service reachable = new Service("localhost", server.getPort(), "http");
        reachable.setToken("Splunk test");
        reachable.version = "10.4.3";
        reachable.instanceType = "enterprise";
        MockWebServer closed = new MockWebServer();
        closed.start();
        int port = closed.getPort();
        closed.shutdown();
        Service unreachable = new Service("localhost", port, "http");
        unreachable.setToken("Splunk test");
        unreachable.version = "10.4.3";
        unreachable.instanceType = "enterprise";
        client.setServiceForTest(unreachable);

        AtomicInteger passwordPrompts = new AtomicInteger();
        List<String> retryPrompts = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> retry = new CompletableFuture<>();
        client.setReloginProvider(new ReloginProvider() {
            @Override
            public CompletableFuture<String> requestPassword(String errorMessage) {
                passwordPrompts.incrementAndGet();
                return CompletableFuture.completedFuture("secret");
            }

            @Override
            public CompletableFuture<Void> requestRetry(String errorMessage) {
                retryPrompts.add(errorMessage);
                return retry;
            }
        });

        CompletableFuture<List<Event>> first = query(new JobTicket(), new Thread[1]);
        CompletableFuture<List<Event>> second = query(new JobTicket(), new Thread[1]);
        await().atMost(5, SECONDS).until(() -> !retryPrompts.isEmpty());
        Thread.sleep(300); // negative check: the second failure joins the shown prompt
        assertThat(retryPrompts).singleElement().asString().containsIgnoringCase("connection refused");
        assertThat(first).isNotDone();

        client.setServiceForTest(reachable); // the network is back
        retry.complete(null);

        assertThat(first.get(5, SECONDS)).isEmpty();
        assertThat(second.get(5, SECONDS)).isEmpty();
        assertThat(passwordPrompts).hasValue(0);
        assertThat(client.reconnects).hasValue(0);
        assertThat(retryPrompts).hasSize(1);
    }

    @Test
    @DisplayName("A create without an answer stays possibly searching however long Splunk answers 404, until a cancel succeeds")
    void ambiguousCreateKeepsCapacityUntilACancelSucceeds() throws Exception {
        splunk.createPolicy = CreatePolicy.DISCONNECT;
        JobTicket ticket = new JobTicket();

        assertThat(failure(query(ticket, new Thread[1]))).isNotNull();
        String sid = ticket.allSids().getFirst();
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.AMBIGUOUS);
        ticket.cancelRequested();

        // Retries after 1 and 3 s: every 404 leaves the request possibly searching, however old
        await().atMost(8, SECONDS).until(() -> splunk.cancelsOf(sid) >= 4);
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.AMBIGUOUS);
        assertThat(ticket.whenQuiet()).as("a 404 never proves an unanswered create was not executed").isNotDone();

        // Splunk executes the delayed create after all; the next cancel succeeds
        splunk.existing.add(sid);
        client.cancelJobs(ticket);

        ticket.whenQuiet().get(3, SECONDS);
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.GONE);
        assertThat(splunk.confirmedCancels).containsExactly(sid);
    }

    @Test
    @DisplayName("An accepted create is recorded before its response body is read; a broken body loses nothing")
    void brokenCreateResponseBodyStillRecordsTheJob() throws Exception {
        splunk.createPolicy = CreatePolicy.CREATE_BROKEN_BODY;
        JobTicket ticket = new JobTicket();

        assertThat(query(ticket, new Thread[1]).get(5, SECONDS)).isEmpty();

        String sid = splunk.createdIds.getFirst();
        assertThat(ticket.state(sid)).isEqualTo(JobTicket.SidState.DONE);
        assertThat(splunk.cancelRequests).isEmpty();
    }

    @Test
    @DisplayName("A failing cancel never prompts and does not confirm")
    void cancelJobsNeverPrompts() {
        AtomicInteger prompts = new AtomicInteger();
        client.setReloginProvider(message -> {
            prompts.incrementAndGet();
            return CompletableFuture.completedFuture("secret");
        });
        splunk.cancelOverride = sid -> 401;
        JobTicket ticket = ticketWithCreatedJob();
        String sid = ticket.searching().getFirst();

        client.cancelJobs(ticket);

        await().atMost(3, SECONDS).until(() -> splunk.cancelsOf(sid) >= 1);
        assertThat(prompts).hasValue(0);
        assertThat(client.reconnects).hasValue(0);
        assertThat(ticket.whenQuiet()).as("an unconfirmed cancel settles nothing").isNotDone();
    }

    @Test
    @DisplayName("Logout stops the cancel retries within its bound")
    void logoutStopsCancelRetries() throws Exception {
        splunk.cancelOverride = sid -> 503;
        JobTicket ticket = ticketWithCreatedJob();
        String sid = ticket.searching().getFirst();
        client.cancelJobs(ticket);
        await().atMost(3, SECONDS).until(() -> splunk.cancelsOf(sid) >= 1);

        long start = System.nanoTime();
        client.logout();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);

        long cancels = splunk.cancelsOf(sid);
        Thread.sleep(1_500); // negative check: the retry due after 1 s must not come
        assertThat(splunk.cancelsOf(sid)).isEqualTo(cancels);
        JobTicket later = ticketWithCreatedJob();
        client.cancelJobs(later);
        Thread.sleep(200);
        assertThat(splunk.cancelsOf(later.searching().getFirst())).as("no new loops after logout").isZero();
    }

    @Test
    @DisplayName("A relogin retry creates a new SID only after the first attempt's cancel is confirmed")
    void retryAfterReloginWaitsForTheFirstAttemptsCancelAndUsesNewSid() throws Exception {
        client.setReloginProvider(message -> CompletableFuture.completedFuture("secret"));
        splunk.status = (sid, get) -> {
            if (sid.endsWith("_1")) {
                return get == 1 ? jobEntry(sid, "RUNNING", 0) : unauthorized();
            }
            return jobEntry(sid, "DONE", 0);
        };
        // The first cancel of attempt 1 fails (old session); the retry must wait for the next one
        AtomicInteger firstCancels = new AtomicInteger();
        splunk.cancelOverride = sid -> sid.endsWith("_1") && firstCancels.incrementAndGet() == 1 ? 401 : 0;
        JobTicket ticket = new JobTicket();

        assertThat(query(ticket, new Thread[1]).get(10, SECONDS)).isEmpty();

        assertThat(client.reconnects).hasValue(1);
        assertThat(splunk.createdIds).hasSize(2);
        String first = splunk.createdIds.get(0);
        String second = splunk.createdIds.get(1);
        assertThat(first).endsWith("_1");
        assertThat(second).endsWith("_2").isNotEqualTo(first);
        assertThat(splunk.timeline).as("never two searches of one request at once")
                .containsExactly("create " + first, "cancelled " + first, "create " + second);
        assertThat(splunk.cancelRequests).doesNotContain(second);
        assertThat(ticket.isCancelled()).as("a retry does not cancel the ticket").isFalse();
    }

    @Test
    @DisplayName("A cancel before the create answers waits for it, then cancels the created job")
    void cancelBeforeCreateAnswersIsSentOnceTheJobExists() throws Exception {
        splunk.holdCreate = new CountDownLatch(1);
        JobTicket ticket = new JobTicket();
        CompletableFuture<List<Event>> result = query(ticket, new Thread[1]);
        assertThat(splunk.createReceived.await(5, SECONDS)).isTrue();
        String sid = ticket.searching().getFirst();

        client.cancelJobs(ticket);
        Thread.sleep(200); // negative check: no cancel can reach a job Splunk has not created
        assertThat(splunk.cancelRequests).isEmpty();
        assertThat(ticket.whenQuiet()).isNotDone();

        splunk.holdCreate.countDown();

        assertThat(failure(result)).isInstanceOf(SearchCancelledException.class);
        ticket.whenQuiet().get(3, SECONDS);
        assertThat(splunk.confirmedCancels).containsExactly(sid);
    }

    @Test
    @DisplayName("One read timeout of 60 s for every request")
    void readTimeoutIs60s() {
        var args = SplunkClient.serviceArgs("host", 8089, "user", "pass");

        assertThat(args.get("readTimeout")).isEqualTo(60_000);
        assertThat(args.get("connectTimeout")).isEqualTo(20_000);
    }

    @Test
    void connectAndHandshakeFailuresAreNeverSent() {
        assertThat(SplunkClient.neverSent(new RuntimeException(new java.net.ConnectException("refused")))).isTrue();
        assertThat(
                SplunkClient.neverSent(new RuntimeException(new java.net.SocketTimeoutException("Connect timed out"))))
                .isTrue();
        assertThat(SplunkClient.neverSent(new RuntimeException(new java.net.SocketTimeoutException("Read timed out"))))
                .isFalse();
        assertThat(SplunkClient.neverSent(new RuntimeException(new java.net.SocketException("Connection reset"))))
                .isFalse();
    }
}
