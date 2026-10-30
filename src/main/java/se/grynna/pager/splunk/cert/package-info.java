/**
 * Interactive TLS certificate trust for the Splunk connection.
 *
 * <p>The Splunk Java SDK validates certificates strictly by default, which makes a
 * self-signed certificate - the norm for a local Splunk test image - fail the TLS
 * handshake. The SDK exposes a static {@code HttpService.setSSLSocketFactory(...)}
 * hook (used for every {@code HttpsURLConnection} it opens), which lets this package
 * substitute an {@link se.grynna.pager.splunk.cert.InteractiveTrustManager}:
 *
 * <ol>
 *   <li>Normally trusted certificates are accepted silently.</li>
 *   <li>Self-signed / otherwise untrusted certificates trigger
 *       {@link se.grynna.pager.splunk.cert.CertificateDecisionProvider}, which the UI
 *       answers with an interactive dialog.</li>
 *   <li>Trusted certificates are pinned per {@code host:port}
 *       ({@link se.grynna.pager.splunk.cert.CertificatePinStore}), so the prompt is not
 *       repeated - and a <em>changed</em> certificate is flagged instead of silently
 *       accepted (guarding against interception).</li>
 * </ol>
 *
 * <p><b>Hostname verification</b> is still performed by the SDK. It deliberately exempts
 * {@code localhost}, {@code 127.0.0.1} and {@code ::1}, which covers a locally running
 * Splunk. A certificate for any other host must still match that host's name.
 */
package se.grynna.pager.splunk.cert;
