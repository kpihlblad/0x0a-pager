package se.grynna.pager.splunk.cert;

/**
 * The user's answer to the "do you trust this certificate?" prompt.
 */
public enum TrustDecision {

    /** Accept the certificate now and remember the fingerprint for future connections. */
    TRUST,

    /** Accept the certificate for this connection only; ask again next time. */
    TRUST_ONCE,

    /** Refuse to connect. */
    REJECT
}
