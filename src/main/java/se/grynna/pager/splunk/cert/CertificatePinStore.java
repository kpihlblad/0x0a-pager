package se.grynna.pager.splunk.cert;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.Config;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Remembers which certificate the user has explicitly trusted for a given
 * {@code host:port}, so that:
 * <ul>
 *   <li>the trust prompt only appears the first time (and not on every connection),</li>
 *   <li>if the certificate ever <em>changes</em> - e.g. a server reinstall, or an
 *       interception attempt - the change is detected and flagged instead of being
 *       silently accepted.</li>
 * </ul>
 *
 * <p>Fingerprints are SHA-256 digests of the leaf certificate's DER encoding, stored
 * per host and port in {@link Preferences}. Preference keys are limited to
 * {@link Preferences#MAX_KEY_LENGTH} characters (80). A key built from the raw host
 * exceeds that for a valid DNS name — 58 host characters at port 8089 is enough —
 * and {@code put} then throws out of the TLS callback, so login fails. The key is
 * therefore a fixed-size hash of the normalized endpoint, not the endpoint itself.
 * This class is deliberately free of Swing dependencies so it can be exercised in tests.
 */
@Slf4j
public class CertificatePinStore {

    /** Short prefix plus a 64-char SHA-256 hex digest. Always within {@link Preferences#MAX_KEY_LENGTH}. */
    private static final String KEY_PREFIX = "pin.";

    private final Preferences prefs;

    /** Uses the application's standard preference node. */
    public CertificatePinStore() {
        this(Preferences.userNodeForPackage(Config.class));
    }

    /** Test/DI constructor. */
    public CertificatePinStore(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs");
    }

    /**
     * Looks up the fingerprint previously trusted for this endpoint.
     *
     * @return the fingerprint previously trusted for this endpoint, if any
     */
    public Optional<String> findPin(String host, int port) {
        return Optional.ofNullable(prefs.get(key(host, port), null));
    }

    /**
     * Records (or replaces) the trusted fingerprint for this endpoint.
     */
    public void storePin(String host, int port, String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        prefs.put(key(host, port), fingerprint);
        flushQuietly(host, port);
    }

    /**
     * Forgets any trusted certificate for this endpoint.
     */
    public void removePin(String host, int port) {
        prefs.remove(key(host, port));
        flushQuietly(host, port);
    }

    private void flushQuietly(String host, int port) {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            log.warn("Unable to persist trusted certificate for {}:{}: {}", host, port, e.getMessage());
        }
    }

    /**
     * Fixed-size key for the normalized endpoint. Equivalent addresses
     * ({@code LocalHost} / {@code localhost.}, {@code ::1} / {@code 0:0:0:0:0:0:0:1})
     * share a pin; the raw host never appears in the key.
     */
    private static String key(String host, int port) {
        return KEY_PREFIX + sha256Hex(normalize(host) + ":" + port);
    }

    private static String normalize(String host) {
        Objects.requireNonNull(host, "host");
        String trimmed = host.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        try {
            // ofLiteral parses IP text only — it does not resolve names.
            return InetAddress.ofLiteral(trimmed).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException notAnIp) {
            String dns = trimmed.toLowerCase(Locale.ROOT);
            if (dns.endsWith(".")) {
                dns = dns.substring(0, dns.length() - 1);
            }
            if (dns.isEmpty()) {
                throw new IllegalArgumentException("host cannot be blank");
            }
            return dns;
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
