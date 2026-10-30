package se.grynna.pager.splunk.cert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.cert.ServerCertificate.Classification;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InteractiveTrustManagerTest {

    private static final String HOST = "localhost";
    private static final int PORT = 8089;

    private X509TrustManager systemTrustManager;
    private CertificatePinStore pinStore;
    private final List<CertificateChallenge> challenges = new ArrayList<>();

    private X509Certificate[] chainA;
    private X509Certificate[] chainB;
    private String fingerprintA;

    @BeforeEach
    void setUp() {
        systemTrustManager = mock(X509TrustManager.class);
        pinStore = mock(CertificatePinStore.class);
        when(pinStore.findPin(HOST, PORT)).thenReturn(Optional.empty());

        chainA = TestCertificates.certArray("self-signed-localhost-a.pem");
        chainB = TestCertificates.certArray("self-signed-localhost-b.pem");
        fingerprintA = ServerCertificate.of(HOST, PORT, chainA).sha256Fingerprint();
    }

    private InteractiveTrustManager manager(TrustDecision decision) {
        return new InteractiveTrustManager(HOST, PORT, challenge -> {
            challenges.add(challenge);
            return CompletableFuture.completedFuture(decision);
        }, pinStore, systemTrustManager);
    }

    private void systemRejects() throws Exception {
        doThrow(new CertificateException("PKIX path building failed")).when(systemTrustManager)
                .checkServerTrusted(any(), any());
    }

    @Test
    void trustedBySystemStoreIsAcceptedSilently() throws Exception {
        var manager = manager(TrustDecision.REJECT); // provider must not be consulted

        manager.checkServerTrusted(chainA, "RSA");

        assertThat(challenges).isEmpty();
        verifyNoInteractions(pinStore);
    }

    @Test
    void untrustedSelfSignedCertificateIsQueriedAndPinnedWhenTrusted() throws Exception {
        systemRejects();
        var manager = manager(TrustDecision.TRUST);

        manager.checkServerTrusted(chainA, "RSA");

        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).reason()).isEqualTo(CertificateChallenge.Reason.UNTRUSTED);
        assertThat(challenges.get(0).certificate().classification()).isEqualTo(Classification.SELF_SIGNED);
        assertThat(challenges.get(0).previouslyTrustedFingerprint()).isNull();
        verify(pinStore).storePin(HOST, PORT, fingerprintA);
    }

    @Test
    void rejectingTheCertificateThrows() throws Exception {
        systemRejects();
        var manager = manager(TrustDecision.REJECT);

        assertThatThrownBy(() -> manager.checkServerTrusted(chainA, "RSA")).isInstanceOf(CertificateException.class)
                .hasMessageContaining("not trusted");
        verify(pinStore, never()).storePin(any(), anyInt(), any());
    }

    @Test
    void pinnedMatchingFingerprintIsAcceptedSilently() throws Exception {
        systemRejects();
        when(pinStore.findPin(HOST, PORT)).thenReturn(Optional.of(fingerprintA));
        var manager = manager(TrustDecision.REJECT); // must not be consulted

        manager.checkServerTrusted(chainA, "RSA");

        assertThat(challenges).isEmpty();
    }

    @Test
    void changedCertificateIsFlaggedAndQueryable() throws Exception {
        systemRejects();
        String previouslyTrusted = ServerCertificate.of(HOST, PORT, chainB).sha256Fingerprint();
        when(pinStore.findPin(HOST, PORT)).thenReturn(Optional.of(previouslyTrusted));
        var manager = manager(TrustDecision.TRUST);

        manager.checkServerTrusted(chainA, "RSA");

        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).reason()).isEqualTo(CertificateChallenge.Reason.CHANGED);
        assertThat(challenges.get(0).isChanged()).isTrue();
        assertThat(challenges.get(0).previouslyTrustedFingerprint()).isEqualTo(previouslyTrusted);
        verify(pinStore).storePin(HOST, PORT, fingerprintA);
    }

    @Test
    void trustOnceAcceptsWithoutPinning() throws Exception {
        systemRejects();
        var manager = manager(TrustDecision.TRUST_ONCE);

        manager.checkServerTrusted(chainA, "RSA");

        assertThat(challenges).hasSize(1);
        verify(pinStore, never()).storePin(any(), anyInt(), any());
    }

    @Test
    void withoutProviderUntrustedCertificateIsRejected() throws Exception {
        systemRejects();
        var manager = new InteractiveTrustManager(HOST, PORT, null, pinStore, systemTrustManager);

        assertThatThrownBy(() -> manager.checkServerTrusted(chainA, "RSA")).isInstanceOf(CertificateException.class);
    }

    @Test
    void exceptionalDecisionIsTreatedAsRejection() throws Exception {
        systemRejects();
        var manager = new InteractiveTrustManager(HOST, PORT,
                challenge -> CompletableFuture.failedFuture(new IllegalStateException("dialog closed")), pinStore,
                systemTrustManager);

        assertThatThrownBy(() -> manager.checkServerTrusted(chainA, "RSA")).isInstanceOf(CertificateException.class);
        verify(pinStore, never()).storePin(any(), anyInt(), any());
    }

    @Test
    void expiredCertificateMatchingThePinIsQueriedAgain() throws Exception {
        systemRejects();
        when(pinStore.findPin(HOST, PORT)).thenReturn(Optional.of(fingerprintA));

        byte[] der = chainA[0].getEncoded();
        X509Certificate expired = mock(X509Certificate.class);
        when(expired.getEncoded()).thenReturn(der);
        when(expired.getSubjectX500Principal()).thenReturn(chainA[0].getSubjectX500Principal());
        when(expired.getIssuerX500Principal()).thenReturn(chainA[0].getSubjectX500Principal());
        doThrow(new java.security.cert.CertificateExpiredException()).when(expired).checkValidity();

        var manager = manager(TrustDecision.REJECT);

        assertThatThrownBy(() -> manager.checkServerTrusted(new X509Certificate[]{expired}, "RSA"))
                .isInstanceOf(CertificateException.class);
        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).reason()).isEqualTo(CertificateChallenge.Reason.EXPIRED);
    }
}
