package se.grynna.pager.splunk.cert;

import com.splunk.HttpService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the SDK's real HttpURLConnection path,
 * including 0x0A Pager's TLS socket factory, without a Splunk server or UI. A request
 * observed by MockWebServer proves that the TLS handshake has already completed.
 * No platform-thread control: its read would wait for the socket timeout.
 */
@Slf4j
class SplunkSdkInterruptProbeTest {

    @ParameterizedTest(name = "{0}, blocked response body = {1}")
    @CsvSource({"http, false", "https, false", "http, true", "https, true"})
    void virtualThreadInterruptAbortsSdkSocketReadAndPreservesFlag(String scheme, boolean bodyRead) throws Exception {
        SSLSocketFactory originalFactory = HttpService.getSSLSocketFactory();
        try (MockWebServer server = new MockWebServer()) {
            if (scheme.equals("https")) {
                server.useHttps(TestCertificates.serverContext("self-signed-localhost-a.pem", "localhost-key-a.pem")
                        .getSocketFactory(), false);
            }
            server.start();
            if (scheme.equals("https")) {
                HttpService.setSSLSocketFactory(InteractiveSslContextFactory.create("localhost", server.getPort(),
                        challenge -> CompletableFuture.completedFuture(TrustDecision.TRUST_ONCE),
                        new CertificatePinStore()));
            }
            // For the body probe, send headers and one byte, then leave the connection
            // open with a second promised byte missing. No sleeping server task to leak.
            server.enqueue(bodyRead
                    ? new MockResponse().setBody("x").setHeader("Content-Length", 2)
                    : new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

            HttpService service = new HttpService("localhost", server.getPort(), scheme);
            service.setConnectTimeout(3_000);
            service.setReadTimeout(30_000); // much longer than the interrupt deadline
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptedOnExit = new AtomicBoolean();
            CountDownLatch headersRead = new CountDownLatch(1);
            CountDownLatch exited = new CountDownLatch(1);
            Thread worker = Thread.ofVirtual().name("sdk-interrupt-probe").unstarted(() -> {
                try {
                    var response = service.get("/interrupt-probe");
                    headersRead.countDown();
                    try (var content = response.getContent()) {
                        content.readAllBytes();
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    interruptedOnExit.set(Thread.currentThread().isInterrupted());
                    exited.countDown();
                }
            });
            worker.start();
            try {
                var request = server.takeRequest(3, SECONDS);
                assertThat(request).as("the server received the request before interruption").isNotNull();
                assertThat(request.getPath()).isEqualTo("/interrupt-probe");
                if (scheme.equals("https")) {
                    assertThat(request.getHandshake()).as("TLS completed before interrupting the read").isNotNull();
                }
                if (bodyRead) {
                    assertThat(headersRead.await(3, SECONDS)).as("SDK returned headers before reading the body")
                            .isTrue();
                }
                // Synchronize on the actual blocked socket read, not a guessed sleep.
                await().pollInterval(Duration.ofMillis(10)).atMost(3, SECONDS)
                        .until(() -> Arrays.stream(worker.getStackTrace())
                                .anyMatch(frame -> frame.getClassName().equals("sun.nio.ch.NioSocketImpl")
                                        && frame.getMethodName().equals("park")));

                long start = System.nanoTime();
                worker.interrupt();
                assertThat(exited.await(2, SECONDS)).as("interrupt ends the SDK read well before its 30 s timeout")
                        .isTrue();
                long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
                Throwable cause = failure.get();
                assertThat(cause).as("the read failed rather than completing normally").isNotNull();
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                assertThat(cause).isInstanceOf(IOException.class).isNotInstanceOf(SocketTimeoutException.class);
                assertThat(interruptedOnExit).as("SDK/TLS preserves the virtual thread's interrupt flag").isTrue();
                log.info("P1 scheme={} bodyRead={} interrupt-to-exit={}ms rootCause={}: {} interrupted={}", scheme,
                        bodyRead, elapsedMs, cause.getClass().getName(), cause.getMessage(), interruptedOnExit.get());
            } finally {
                worker.interrupt();
                worker.join(SECONDS.toMillis(3));
            }
        } finally {
            HttpService.setSSLSocketFactory(originalFactory);
        }
    }
}
