package se.grynna.pager.splunk.cert;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SNIHostName;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Immutable snapshot of a TLS certificate chain presented by a Splunk server,
 * together with everything needed to explain it to the user in a trust dialog.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link Classification} - best-effort guess whether this is a harmless self-signed
 *       dev/test certificate or something potentially more suspicious.</li>
 *   <li>SHA-256 fingerprints of the leaf certificate (DER) and of its public key (SPKI).
 *       The DER fingerprint is used for pinning.</li>
 *   <li>Human readable details: subject, issuer, validity, signature algorithm.</li>
 *   <li>Hostname coverage - does the certificate claim this host under TLS identity
 *       rules (typed SANs, no DNS resolution, single-label wildcards)?</li>
 * </ul>
 */
public record ServerCertificate(String host, int port, List<X509Certificate> chain) {

    /** {@link X509Certificate#getSubjectAlternativeNames()} type for a dNSName. */
    private static final int SAN_DNS = 2;
    /** {@link X509Certificate#getSubjectAlternativeNames()} type for an iPAddress. */
    private static final int SAN_IP = 7;

    /**
     * Best-effort classification of why the chain was not trusted by the system store.
     * Note: an attacker's intercepting certificate is <em>also</em> usually self-signed,
     * so this is never used as proof of safety - it only affects how the situation is
     * explained to the user. The real protection is explicit user trust plus pinning
     * (a previously trusted certificate that changes is flagged as suspicious).
     */
    public enum Classification {
        /** A single self-signed certificate, or a certificate issued by a locally generated CA -
         *  the typical shape of a dev/test Splunk image. */
        SELF_SIGNED,
        /** Signed by an unknown CA (chain could not be validated against a system trust store). */
        UNKNOWN_CA
    }

    public static ServerCertificate of(String host, int port, X509Certificate[] chain) {
        return new ServerCertificate(host, port, List.of(chain.clone()));
    }

    public static ServerCertificate of(String host, int port, List<X509Certificate> chain) {
        return new ServerCertificate(host, port, chain);
    }

    /** The certificate actually presented by the server (first in the chain). */
    public X509Certificate leaf() {
        if (chain.isEmpty()) {
            throw new IllegalStateException("Server did not present any certificates");
        }
        return chain.get(0);
    }

    /**
     * Classification logic:
     * <ul>
     *   <li>{@link Classification#SELF_SIGNED} only when the leaf itself is self-issued
     *       (subject == issuer) - the shape of a single self-signed certificate such as a
     *       Splunk test image's server cert.</li>
     *   <li>{@link Classification#UNKNOWN_CA} otherwise, i.e. the certificate claims to be
     *       issued by some authority the system does not trust - which is also the shape of
     *       an intercepting certificate, hence the deliberately cautious wording.</li>
     * </ul>
     */
    public Classification classification() {
        if (chain.isEmpty()) {
            return Classification.UNKNOWN_CA;
        }
        X509Certificate leaf = leaf();
        boolean leafSelfIssued = leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal());
        return leafSelfIssued ? Classification.SELF_SIGNED : Classification.UNKNOWN_CA;
    }

    /** SHA-256 fingerprint of the leaf certificate's DER encoding - used for pinning. */
    public String sha256Fingerprint() {
        try {
            return sha256(leaf().getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Unable to encode leaf certificate", e);
        }
    }

    /** SHA-256 fingerprint of the leaf certificate's public key (SPKI). */
    public String sha256SpkiFingerprint() {
        return sha256(leaf().getPublicKey().getEncoded());
    }

    private static String sha256(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public Instant notBefore() {
        return leaf().getNotBefore().toInstant();
    }

    public Instant notAfter() {
        return leaf().getNotAfter().toInstant();
    }

    /** True if the certificate is valid at this moment (not yet expired, not before its start). */
    public boolean isValidNow() {
        try {
            leaf().checkValidity();
            return true;
        } catch (CertificateException e) {
            return false;
        }
    }

    /**
     * Does this certificate actually cover the host we are connecting to?
     *
     * <p>Follows TLS identity rules (RFC 6125, as applied by the JDK hostname
     * checker), and does not resolve names — this is called while building the
     * trust dialog on the EDT:
     * <ul>
     *   <li>An IP address is compared only to iPAddress SANs, never to a DNS name
     *       or the CN, and never by looking the name up.</li>
     *   <li>A DNS name is compared only to dNSName SANs. If any DNS SAN is present
     *       it takes precedence over the CN, even when none of the SANs match.</li>
     *   <li>The CN is consulted only when the certificate has no DNS SAN.</li>
     *   <li>A wildcard matches a single left-most label ({@code *.example.com}
     *       matches {@code www.example.com}, not {@code a.b.example.com}).</li>
     * </ul>
     */
    public boolean coversHost() {
        if (host == null || host.isBlank()) {
            return false;
        }
        String target = host.trim();
        if (isIpLiteral(target)) {
            return ipAddressCovers(target);
        }
        return dnsNameCovers(target);
    }

    private static boolean isIpLiteral(String host) {
        try {
            InetAddress.ofLiteral(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** iPAddress SANs only. No CN fallback and no name resolution. */
    private boolean ipAddressCovers(String target) {
        InetAddress expected = InetAddress.ofLiteral(target);
        for (List<?> entry : subjectAlternativeNameEntries()) {
            if (entry.size() < 2 || sanType(entry) != SAN_IP) {
                continue;
            }
            if (sameIp(expected, entry.get(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * dNSName SANs, or the most specific CN when the certificate has none.
     * A DNS SAN that does not match is a mismatch — the CN is not a second chance.
     */
    private boolean dnsNameCovers(String target) {
        if (!isAcceptableDnsName(target)) {
            return false;
        }
        boolean sawDnsSan = false;
        for (List<?> entry : subjectAlternativeNameEntries()) {
            if (entry.size() < 2 || sanType(entry) != SAN_DNS || !(entry.get(1) instanceof String dnsName)) {
                continue;
            }
            sawDnsSan = true;
            if (dnsNameMatches(target, dnsName)) {
                return true;
            }
        }
        if (sawDnsSan) {
            return false;
        }
        String cn = mostSpecificCn(leaf());
        return cn != null && dnsNameMatches(target, cn);
    }

    private static boolean sameIp(InetAddress expected, Object value) {
        try {
            if (value instanceof String literal) {
                return expected.equals(InetAddress.ofLiteral(literal));
            }
            if (value instanceof byte[] bytes) {
                return expected.equals(InetAddress.getByAddress(bytes));
            }
        } catch (IllegalArgumentException | UnknownHostException e) {
            return false;
        }
        return false;
    }

    /**
     * TLS rejects a name it cannot send as an SNI host name (empty, trailing dot,
     * non-LDH characters). Coverage must not say yes in those cases either.
     */
    private static boolean isAcceptableDnsName(String host) {
        try {
            new SNIHostName(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean dnsNameMatches(String host, String template) {
        try {
            host = IDN.toUnicode(IDN.toASCII(host));
            template = IDN.toUnicode(IDN.toASCII(template));
        } catch (RuntimeException e) {
            return false;
        }
        if (hasIllegalWildcard(template)) {
            return false;
        }
        // The JDK checker validates the template as an SNI name with '*' rewritten.
        // A template it would reject must not be reported as covering the host.
        try {
            new SNIHostName(template.replace('*', 'z'));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (isWildcardOverPublicSuffix(template)) {
            return false;
        }
        return matchLeftmostWildcard(host, template);
    }

    /** Uses the JDK public-suffix list rule applied by its TLS hostname checker. */
    private static boolean isWildcardOverPublicSuffix(String template) {
        int star = template.lastIndexOf('*');
        if (star < 0) {
            return false;
        }
        int dotAfterWildcard = template.indexOf('.', star);
        if (dotAfterWildcard < 0 || dotAfterWildcard == template.length() - 1) {
            return false;
        }
        String suffix = template.substring(dotAfterWildcard + 1);
        return JdkPublicSuffixes.isIcannPublicSuffix(suffix);
    }

    /** A lone {@code *} or a {@code *} with no dot after it is not a DNS wildcard. */
    private static boolean hasIllegalWildcard(String template) {
        if (template.equals("*") || template.equals("*.")) {
            return true;
        }
        int star = template.lastIndexOf('*');
        if (star < 0) {
            return false;
        }
        return template.substring(star).indexOf('.') < 0;
    }

    /**
     * Wildcard matching is confined to the left-most label, which must then be
     * followed by the rest of the name exactly. {@code *.example.com} therefore
     * matches one label, not {@code a.b.example.com}, and {@code www.*.example.com}
     * matches nothing — the {@code *} is not in the left-most label.
     */
    private static boolean matchLeftmostWildcard(String host, String template) {
        host = host.toLowerCase(Locale.ROOT);
        template = template.toLowerCase(Locale.ROOT);
        int templateDot = template.indexOf('.');
        int hostDot = host.indexOf('.');
        if (templateDot < 0) {
            templateDot = template.length();
        }
        if (hostDot < 0) {
            hostDot = host.length();
        }
        return matchWildcards(host.substring(0, hostDot), template.substring(0, templateDot))
                && template.substring(templateDot).equals(host.substring(hostDot));
    }

    /** {@code *} matches any (including empty) sequence within a single label. */
    private static boolean matchWildcards(String name, String template) {
        int star = template.indexOf('*');
        if (star < 0) {
            return name.equals(template);
        }
        boolean first = true;
        while (star >= 0) {
            String prefix = template.substring(0, star);
            template = template.substring(star + 1);
            int index = name.indexOf(prefix);
            if (index < 0 || (first && index != 0)) {
                return false;
            }
            first = false;
            name = name.substring(index + prefix.length());
            star = template.indexOf('*');
        }
        return name.endsWith(template);
    }

    /**
     * The most specific CN (the last one in {@link LdapName#getRdns()}, which
     * lists least-significant first). A CN that is not NFKC-normalized is ignored,
     * matching the JDK checker.
     */
    private static String mostSpecificCn(X509Certificate cert) {
        try {
            String cn = null;
            for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType()) && rdn.getValue() instanceof String value) {
                    cn = value;
                }
            }
            if (cn == null || !Normalizer.isNormalized(cn, Normalizer.Form.NFKC)) {
                return null;
            }
            return cn;
        } catch (InvalidNameException e) {
            return null;
        }
    }

    /** Human readable values of the subject alternative names, for display. */
    public List<String> subjectAlternativeNames() {
        List<String> result = new ArrayList<>();
        for (List<?> entry : subjectAlternativeNameEntries()) {
            if (entry.size() > 1 && entry.get(1) != null) {
                result.add(entry.get(1).toString());
            }
        }
        return result;
    }

    private static int sanType(List<?> entry) {
        if (entry.isEmpty() || !(entry.get(0) instanceof Integer type)) {
            return -1;
        }
        return type;
    }

    private List<List<?>> subjectAlternativeNameEntries() {
        try {
            Collection<List<?>> san = leaf().getSubjectAlternativeNames();
            if (san == null) {
                return List.of();
            }
            return new ArrayList<>(san);
        } catch (CertificateParsingException ignored) {
            return List.of();
        }
    }

    @Override
    public String toString() {
        return "ServerCertificate[%s:%d, subject=%s, classification=%s, sha256=%s]".formatted(host, port,
                leaf().getSubjectX500Principal(), classification(), sha256Fingerprint());
    }
}
