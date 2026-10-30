package se.grynna.pager.splunk.cert;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.security.GeneralSecurityException;

/**
 * Builds an {@link SSLSocketFactory} that validates server certificates with an
 * {@link InteractiveTrustManager}: normal certificates pass silently, self-signed or
 * otherwise untrusted ones are queried with the user.
 */
public final class InteractiveSslContextFactory {

    private InteractiveSslContextFactory() {}

    public static SSLSocketFactory create(String host, int port, CertificateDecisionProvider decisionProvider,
            CertificatePinStore pinStore) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new InteractiveTrustManager(host, port, decisionProvider, pinStore)},
                    null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialize TLS trust for " + host + ":" + port, e);
        }
    }
}
