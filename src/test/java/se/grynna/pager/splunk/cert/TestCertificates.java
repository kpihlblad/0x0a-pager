package se.grynna.pager.splunk.cert;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Loads the throwaway X.509 fixtures from {@code src/test/resources/certs}.
 *
 * <p>These certificates/keys are generated solely for tests (a localhost CA-less
 * self-signed cert, a second one, a chain to an untrusted CA, and hostname-coverage
 * fixtures). They protect
 * nothing and must never be used outside the test suite.
 */
final class TestCertificates {

    private TestCertificates() {}

    static List<X509Certificate> certs(String resource) {
        try (InputStream in = open(resource)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return factory.generateCertificates(in).stream().map(c -> (X509Certificate) c).toList();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load test certificate " + resource, e);
        }
    }

    static X509Certificate[] certArray(String resource) {
        List<X509Certificate> certs = certs(resource);
        return certs.toArray(X509Certificate[]::new);
    }

    static PrivateKey privateKey(String resource) {
        try (InputStream in = open(resource)) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "").replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load test key " + resource, e);
        }
    }

    /** Builds a server-side SSLContext presenting the given certificate + key. */
    static SSLContext serverContext(String certResource, String keyResource) {
        try {
            List<X509Certificate> chain = certs(certResource);
            PrivateKey key = privateKey(keyResource);

            char[] password = "password".toCharArray();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("server", key, password, chain.toArray(X509Certificate[]::new));

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build test server SSLContext", e);
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = TestCertificates.class.getResourceAsStream("/certs/" + resource);
        if (in == null) {
            throw new IOException("Test certificate resource not found: /certs/" + resource);
        }
        return in;
    }
}
