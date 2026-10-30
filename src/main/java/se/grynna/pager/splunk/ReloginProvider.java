package se.grynna.pager.splunk;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Provides re-login credentials when a Splunk session is lost.
 * Used ONLY for session recovery - NOT for initial login.
 *
 * <h2>Usage</h2>
 * SplunkClient calls this when it detects a session error (401/403), and {@link #requestRetry} when Splunk cannot be reached.
 * The provider should show the login dialog in "relogin mode" where only password is editable.
 *
 * <h2>Typical Implementation</h2>
 * <pre>
 * private CompletableFuture&lt;String&gt; handleRelogin(String errorMessage) {
 *     CompletableFuture&lt;String&gt; future = new CompletableFuture&lt;&gt;();
 *     SwingUtilities.invokeLater(() -> {
 *         // Show login dialog with locked host/user fields
 *         // On success: future.complete(password)
 *         // On cancel: future.completeExceptionally(new CancellationException())
 *     });
 *     return future;
 * }
 * </pre>
 */
@FunctionalInterface
public interface ReloginProvider {
    /**
     * Request re-login password from user after session loss.
     * Only password is needed - host/port/username are already known.
     *
     * @param errorMessage explains why re-login is needed (e.g., "Session expired")
     * @return CompletableFuture that completes with password, or exceptionally if cancelled
     */
    CompletableFuture<String> requestPassword(String errorMessage);

    /**
     * Splunk could not be reached (no route, refused, timeout): ask the user to retry when the
     * network is back. The session is kept, no password is asked for.
     *
     * @param errorMessage what failed
     * @return completes normally to retry, exceptionally if the user gave up. By default the
     *         failed work is not retried.
     */
    default CompletableFuture<Void> requestRetry(String errorMessage) {
        return CompletableFuture.failedFuture(new CancellationException("Splunk unreachable: " + errorMessage));
    }
}
