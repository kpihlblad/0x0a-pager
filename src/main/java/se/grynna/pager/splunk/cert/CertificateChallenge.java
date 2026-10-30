package se.grynna.pager.splunk.cert;

/**
 * Describes an untrusted certificate the user is being asked about, including
 * <em>why</em> it is being questioned. This lets the dialog phrase the situation
 * accurately instead of showing a generic "untrusted certificate" warning.
 *
 * @param certificate                  the chain the server presented
 * @param reason                       why the chain could not be trusted automatically
 * @param previouslyTrustedFingerprint the fingerprint the user trusted earlier for this
 *                                     endpoint, when {@link Reason#CHANGED}; otherwise {@code null}
 */
public record CertificateChallenge(ServerCertificate certificate, Reason reason, String previouslyTrustedFingerprint) {

    public enum Reason {
        /** No certificate was ever trusted for this endpoint and the system trust store rejects it. */
        UNTRUSTED,
        /** A previously trusted certificate has been replaced by a different one. */
        CHANGED,
        /** The fingerprint matches the trusted certificate, but it is no longer within its validity period. */
        EXPIRED
    }

    /** True when the presented certificate differs from the one previously trusted. */
    public boolean isChanged() {
        return reason == Reason.CHANGED;
    }
}
