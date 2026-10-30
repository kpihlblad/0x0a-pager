package se.grynna.pager.splunk;

/**
 * Describes why login is needed.
 *
 * <h2>Types</h2>
 * <ul>
 *   <li>INITIAL_LOGIN - First login, all fields editable</li>
 *   <li>RELOGIN - Session recovery, only password editable (host/user locked)</li>
 * </ul>
 */
public record LoginReason(Type type, String message) {
    public enum Type {
        /** Initial login - all fields editable */
        INITIAL_LOGIN,
        /** Session recovery - only password editable */
        RELOGIN,
        /** Splunk unreachable - nothing editable, retry on the same session */
        UNREACHABLE
    }

    /** Standard reason for initial login (no error message) */
    public static final LoginReason INITIAL = new LoginReason(Type.INITIAL_LOGIN, null);

    /** Create an initial login reason with error message (e.g., previous login attempt failed) */
    public static LoginReason initialWithError(String errorMessage) {
        return new LoginReason(Type.INITIAL_LOGIN, errorMessage);
    }

    /** Create a relogin reason with error message */
    public static LoginReason relogin(String errorMessage) {
        return new LoginReason(Type.RELOGIN, errorMessage);
    }

    /** Create a reason for a network failure: retry without a password */
    public static LoginReason unreachable(String errorMessage) {
        return new LoginReason(Type.UNREACHABLE, errorMessage);
    }

    /** Check if this is a relogin (session recovery); also true when Splunk is unreachable */
    public boolean isRelogin() {
        return type == Type.RELOGIN || type == Type.UNREACHABLE;
    }

    /** Check if Splunk is unreachable: only a retry is offered */
    public boolean isUnreachable() {
        return type == Type.UNREACHABLE;
    }

    /** Check if there's an error message to display */
    public boolean hasError() {
        return message != null && !message.isBlank();
    }
}
