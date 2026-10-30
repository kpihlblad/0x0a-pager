package se.grynna.pager.splunk.cert;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.swing.SwingUtilities;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * An {@link X509TrustManager} that trusts valid certificates silently and asks the
 * user about the rest.
 *
 * <h2>Behaviour</h2>
 * <ol>
 *   <li>If the system trust store accepts the chain (a normal, publicly trusted
 *       certificate), it is accepted silently - no prompt.</li>
 *   <li>Otherwise the presented chain is classified and the user is asked via the
 *       {@link CertificateDecisionProvider}. This is the path taken by self-signed
 *       certificates, e.g. a local Splunk test image.</li>
 *   <li>If the user chose to trust it, the fingerprint is pinned (see
 *       {@link CertificatePinStore}) so the prompt is not shown again.</li>
 *   <li>If a pinned fingerprint <em>changes</em>, the user is not quietly re-trusted:
 *       the change is surfaced as {@link CertificateChallenge.Reason#CHANGED} so it can
 *       be judged as a possible interception rather than accepted silently.</li>
 * </ol>
 *
 * <p>The provider is invoked from the thread performing the TLS handshake (never the
 * EDT) and must itself dispatch to the EDT for any UI.
 */
@Slf4j
public class InteractiveTrustManager implements X509TrustManager {

    private final String host;
    private final int port;
    private final X509TrustManager systemTrustManager;
    private final CertificateDecisionProvider decisionProvider;
    private final CertificatePinStore pinStore;

    /** Production constructor: validates against the platform default trust store. */
    public InteractiveTrustManager(String host, int port, CertificateDecisionProvider decisionProvider,
            CertificatePinStore pinStore) {
        this(host, port, decisionProvider, pinStore, systemTrustManager());
    }

    /** Test/DI constructor. */
    InteractiveTrustManager(String host, int port, CertificateDecisionProvider decisionProvider,
            CertificatePinStore pinStore, X509TrustManager systemTrustManager) {
        this.host = host;
        this.port = port;
        this.decisionProvider = decisionProvider;
        this.pinStore = pinStore;
        this.systemTrustManager = systemTrustManager;
    }

    private static X509TrustManager systemTrustManager() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager tm : factory.getTrustManagers()) {
                if (tm instanceof X509TrustManager x509) {
                    return x509;
                }
            }
            throw new IllegalStateException("No X509TrustManager available from the system trust store");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialize the system trust store", e);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        systemTrustManager.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            systemTrustManager.checkServerTrusted(chain, authType);
            return; // a normal, publicly trusted certificate
        } catch (CertificateException systemRejected) {
            log.debug("Certificate for {}:{} rejected by system trust store: {}", host, port,
                    systemRejected.getMessage());
        }

        ServerCertificate certificate = ServerCertificate.of(host, port, chain);
        String fingerprint = safeFingerprint(certificate);
        Optional<String> trustedFingerprint = pinStore.findPin(host, port);

        if (fingerprint != null && trustedFingerprint.filter(fingerprint::equals).isPresent()) {
            if (certificate.isValidNow()) {
                log.debug("Certificate for {}:{} matches pinned fingerprint, accepting", host, port);
                return;
            }
            askUser(new CertificateChallenge(certificate, CertificateChallenge.Reason.EXPIRED, fingerprint),
                    fingerprint);
            return;
        }

        CertificateChallenge.Reason reason = trustedFingerprint.isPresent()
                ? CertificateChallenge.Reason.CHANGED
                : CertificateChallenge.Reason.UNTRUSTED;
        askUser(new CertificateChallenge(certificate, reason, trustedFingerprint.orElse(null)), fingerprint);
    }

    private void askUser(CertificateChallenge challenge, String fingerprint) throws CertificateException {
        if (challenge.certificate().chain().isEmpty()) {
            throw new CertificateException("Server did not present any certificate for " + host + ":" + port);
        }
        if (decisionProvider == null) {
            throw new CertificateException("The certificate presented by " + host + ":" + port
                    + " is not trusted and no trust prompt is available");
        }
        if (SwingUtilities.isEventDispatchThread()) {
            throw new CertificateException("Cannot prompt for certificate trust on the Swing Event Dispatch Thread");
        }

        TrustDecision decision = await(challenge);

        if (decision == null || decision == TrustDecision.REJECT) {
            log.warn("User rejected the certificate presented by {}:{}", host, port);
            throw new CertificateException(
                    "The certificate presented by " + host + ":" + port + " was not trusted. Connection refused.");
        }

        if (decision == TrustDecision.TRUST && fingerprint != null) {
            pinStore.storePin(host, port, fingerprint);
            log.info("Trusted and pinned certificate for {}:{} ({})", host, port, fingerprint);
        } else {
            log.info("Trusted certificate for {}:{} for this connection only", host, port);
        }
    }

    private TrustDecision await(CertificateChallenge challenge) throws CertificateException {
        CompletableFuture<TrustDecision> future;
        try {
            future = decisionProvider.requestTrust(challenge);
        } catch (RuntimeException e) {
            throw new CertificateException("Failed to prompt for certificate trust: " + e.getMessage(), e);
        }
        if (future == null) {
            throw new CertificateException("Certificate trust prompt returned no decision");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CertificateException("Interrupted while waiting for certificate trust decision", e);
        } catch (CancellationException e) {
            throw new CertificateException("Certificate trust prompt was cancelled", e);
        } catch (ExecutionException e) {
            throw new CertificateException("Certificate trust prompt failed: " + e.getCause().getMessage(),
                    e.getCause());
        }
    }

    private static String safeFingerprint(ServerCertificate certificate) {
        try {
            return certificate.sha256Fingerprint();
        } catch (RuntimeException e) {
            log.warn("Unable to compute certificate fingerprint: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return systemTrustManager.getAcceptedIssuers();
    }
}
