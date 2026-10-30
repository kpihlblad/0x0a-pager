package se.grynna.pager.splunk.cert;

import java.util.concurrent.CompletableFuture;

/**
 * Asks the user whether an untrusted server certificate may be trusted.
 *
 * <p>Mirrors {@link se.grynna.pager.splunk.ReloginProvider}: the TLS stack blocks the
 * background thread that is performing the handshake, while the implementation is
 * expected to show a dialog on the Swing Event Dispatch Thread and complete the
 * returned future when the user answers.
 *
 * <h2>Typical implementation</h2>
 * <pre>{@code
 * private CompletableFuture<TrustDecision> handleCertificateTrust(CertificateChallenge challenge) {
 *     CompletableFuture<TrustDecision> future = new CompletableFuture<>();
 *     SwingUtilities.invokeLater(() -> {
 *         CertificateTrustDialog dialog = new CertificateTrustDialog(this, challenge);
 *         dialog.setOnDecision(future::complete);
 *         dialog.setVisible(true);
 *     });
 *     return future;
 * }
 * }</pre>
 */
@FunctionalInterface
public interface CertificateDecisionProvider {

    /**
     * Request a trust decision from the user.
     *
     * @param challenge the certificate chain the server presented, why it is being
     *                  questioned, and any previously trusted fingerprint
     * @return a future completing with the user's {@link TrustDecision}; completing
     *         exceptionally (or cancelling) is treated as a rejection
     */
    CompletableFuture<TrustDecision> requestTrust(CertificateChallenge challenge);
}
