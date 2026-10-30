package se.grynna.pager.splunk.cert;

import com.splunk.HttpService;
import com.splunk.ResponseMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.cert.ServerCertificate.Classification;

import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end TLS tests for the interactive trust path: a real {@link MockWebServer}
 * presenting the same kind of self-signed certificate a local Splunk test image would,
 * exercised through the actual Splunk SDK {@link HttpService} that the application uses.
 */
class InteractiveTlsIntegrationTest {

    private static final String CERT = "self-signed-localhost-a.pem";
    private static final String KEY = "localhost-key-a.pem";

    private MockWebServer server;
    private SSLSocketFactory originalFactory;
    private int port;
    private String fingerprint;

    @BeforeEach
    void setUp() throws Exception {
        originalFactory = HttpService.getSSLSocketFactory();
        server = new MockWebServer();
        server.useHttps(TestCertificates.serverContext(CERT, KEY).getSocketFactory(), false);
        server.start();
        port = server.getPort();
        fingerprint = ServerCertificate.of("localhost", port, TestCertificates.certs(CERT)).sha256Fingerprint();
    }

    @AfterEach
    void tearDown() throws Exception {
        HttpService.setSSLSocketFactory(originalFactory);
        server.shutdown();
    }

    @Test
    void selfSignedCertificateIsPromptedAndRemembered() {
        CertificatePinStore pinStore = pinStoreWithoutPin();
        AtomicInteger prompts = new AtomicInteger();
        List<CertificateChallenge> seen = new ArrayList<>();
        install(challenge -> {
            prompts.incrementAndGet();
            seen.add(challenge);
            return CompletableFuture.completedFuture(TrustDecision.TRUST);
        }, pinStore);
        enqueueOk();

        get("/");

        assertThat(prompts).hasValue(1);
        assertThat(seen).singleElement().satisfies(challenge -> {
            assertThat(challenge.reason()).isEqualTo(CertificateChallenge.Reason.UNTRUSTED);
            assertThat(challenge.certificate().classification()).isEqualTo(Classification.SELF_SIGNED);
        });
        verify(pinStore).storePin(eq("localhost"), eq(port), eq(fingerprint));
    }

    @Test
    void pinnedCertificateConnectsWithoutPrompting() {
        CertificatePinStore pinStore = mock(CertificatePinStore.class);
        when(pinStore.findPin("localhost", port)).thenReturn(Optional.of(fingerprint));
        install(challenge -> {
            throw new AssertionError("The user must not be prompted for a pinned certificate");
        }, pinStore);
        enqueueOk();

        get("/");
    }

    @Test
    void rejectedCertificateFailsTheHandshake() {
        CertificatePinStore pinStore = pinStoreWithoutPin();
        install(challenge -> CompletableFuture.completedFuture(TrustDecision.REJECT), pinStore);
        enqueueOk();

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> get("/"));

        assertThat(failure).isNotNull();
        assertThat(rootCause(failure)).isInstanceOf(CertificateException.class);
        verify(pinStore, never()).storePin(any(), anyInt(), any());
    }

    private CertificatePinStore pinStoreWithoutPin() {
        CertificatePinStore pinStore = mock(CertificatePinStore.class);
        when(pinStore.findPin("localhost", port)).thenReturn(Optional.empty());
        return pinStore;
    }

    private void install(CertificateDecisionProvider provider, CertificatePinStore pinStore) {
        HttpService.setSSLSocketFactory(InteractiveSslContextFactory.create("localhost", port, provider, pinStore));
    }

    private void enqueueOk() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    }

    private void get(String path) {
        HttpService service = new HttpService("localhost", port, "https");
        ResponseMessage response = service.get(path);
        try (InputStream content = response.getContent()) {
            if (content != null) {
                content.readAllBytes();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Identity comparison is intentional: the guard detects a self-referential
    // cause (an exception cycle), which only the exact same instance can be.
    @SuppressWarnings("ReferenceEquality")
    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
