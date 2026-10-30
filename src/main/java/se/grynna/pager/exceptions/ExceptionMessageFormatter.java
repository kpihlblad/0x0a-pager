package se.grynna.pager.exceptions;

import com.fasterxml.jackson.core.JsonParseException;
import com.splunk.HttpException;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ConcurrentModificationException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * ExceptionMessageFormatter provides exception classification and formatting.
 *
 * <h2>Architecture: Single Switch Statement</h2>
 * Uses ONE central switch statement in classifyException() that returns both:
 * <ul>
 *   <li>ErrorCategory (CLIENT_ERROR, TECHNICAL_ERROR, USER_ERROR)</li>
 *   <li>Formatted message (user-friendly error text)</li>
 * </ul>
 * Both public methods (isClientError, formatException) call the same switch internally.
 *
 * <h2>Exception Classification</h2>
 * <ul>
 *   <li><b>CLIENT_ERROR</b> - Auth, session errors → requires re-login
 *     <ul>
 *       <li>Splunk HttpException 401/403</li>
 *       <li>Message patterns: "401", "unauthorized", "login failed"</li>
 *     </ul>
 *   </li>
 *   <li><b>NETWORK_ERROR</b> - Splunk unreachable → wait and retry on the same session
 *     <ul>
 *       <li>UnknownHostException, SocketException (ConnectException, NoRouteToHostException, ...),
 *           SocketTimeoutException anywhere in the cause chain</li>
 *       <li>Message patterns: "connection refused", "no route to host", "read timed out", ...</li>
 *     </ul>
 *   </li>
 *   <li><b>TECHNICAL_ERROR</b> - System errors, bugs → log and report
 *     <ul>
 *       <li>NullPointerException, SQLException, IOException, etc.</li>
 *     </ul>
 *   </li>
 *   <li><b>USER_ERROR</b> - Invalid input → show to user
 *     <ul>
 *       <li>IllegalArgumentException, NumberFormatException, JsonParseException</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Splunk HttpException Handling</h2>
 * Splunk's HttpException is detected by class name (not type) because:
 * <ul>
 *   <li>Splunk SDK might not be on classpath in all contexts</li>
 *   <li>Exception often wrapped in SearchException</li>
 *   <li>Checks entire cause chain to find wrapped HttpException</li>
 * </ul>
 *
 * <h2>Design Pattern: Single Source of Truth</h2>
 * <pre>
 * classifyException(Throwable)
 *   └─ ONE switch statement
 *      └─ returns ExceptionInfo(category, message)
 *
 * isClientError(Throwable)
 *   └─ traverses cause chain
 *   └─ calls classifyException() for each exception
 *   └─ returns true if any is CLIENT_ERROR
 *
 * formatException(Throwable)
 *   └─ finds root cause
 *   └─ calls classifyException()
 *   └─ returns formatted message
 * </pre>
 *
 * @see ErrorCategory
 */
@Slf4j
public class ExceptionMessageFormatter {

    /**
     * Exception categories for error handling strategies.
     */
    public enum ErrorCategory {
        CLIENT_ERROR, // Authentication, session errors - requires re-login
        NETWORK_ERROR, // Splunk unreachable (no route, refused, timeout, reset) - wait and retry, no re-login
        TECHNICAL_ERROR, // System errors, bugs, configuration issues
        USER_ERROR // Invalid input, invalid operations
    }

    /**
     * Internal record to hold exception classification and formatted message.
     */
    public record ExceptionInfo(ErrorCategory category, String message) {

        public boolean isClientError() {
            return category == ErrorCategory.CLIENT_ERROR;
        }

        public boolean isNetworkError() {
            return category == ErrorCategory.NETWORK_ERROR;
        }
    }

    public static String formatException(Throwable throwable) {
        if (throwable == null) {
            return "An unexpected error occurred.";
        }

        ExceptionInfo info = classifyException(throwable);

        return info.message;
    }

    /**
     * Core classification method - single switch returns both category and message.
     * Checks: HttpException by class name, message patterns, exception types.
     */
    public static ExceptionInfo classifyException(Throwable input) {

        // The HTTP status decides, also when the HttpException is wrapped in a SearchException:
        // only 401/403 means the session is gone. Any other status (400 for a search Splunk
        // rejects, ...) is the request's fault, and prompting for a password would not help.
        HttpException http = findHttpException(input);
        if (http != null) {
            boolean sessionError = http.getStatus() == 401 || http.getStatus() == 403;
            return new ExceptionInfo(sessionError ? ErrorCategory.CLIENT_ERROR : ErrorCategory.TECHNICAL_ERROR,
                    http.getMessage());
        }

        // A transport failure anywhere in the chain (the SDK wraps it in a RuntimeException, 0x0A Pager
        // in a SearchException): Splunk is unreachable, a new password would not help
        Throwable transport = findTransportFailure(input);
        if (transport != null) {
            return new ExceptionInfo(ErrorCategory.NETWORK_ERROR, transportMessage(transport));
        }

        Throwable cause = getRootCause(input);

        String message = cause.getMessage();

        // Check for Splunk HttpException by class name (might not be on classpath)
        //        if (className.contains("HttpException")) {
        //            String formattedMessage = "Connection error: Unable to communicate with server";
        //            if (message != null) {
        //                // Extract meaningful part from "HTTP 401 -- Login failed" style messages
        //                if (message.contains("--")) {
        //                    String[] parts = message.split("--", 2);
        //                    if (parts.length > 1) {
        //                        formattedMessage = "Authentication error: " + parts[1].trim();
        //                    }
        //                } else if (message.contains("401") || message.toLowerCase().contains("unauthorized")) {
        //                    formattedMessage = "Authentication failed: Invalid credentials or session expired";
        //                } else if (message.contains("403")) {
        //                    formattedMessage = "Access denied: Insufficient permissions";
        //                }
        //
        //            }
        //            return new ExceptionInfo(ErrorCategory.CLIENT_ERROR, formattedMessage);
        //        }

        // Check message patterns for authentication/session/network errors
        if (message != null) {
            String lowerMessage = message.toLowerCase(Locale.ROOT);
            if (lowerMessage.contains("401") || lowerMessage.contains("403") || lowerMessage.contains("unauthorized")
                    || lowerMessage.contains("authentication failed") || lowerMessage.contains("login failed")
                    || lowerMessage.contains("session expired")) {
                return new ExceptionInfo(ErrorCategory.CLIENT_ERROR, message);
            }
        }

        // Single switch statement for all exception types with both category and message
        return switch (cause) {
            case TimeoutException e -> new ExceptionInfo(ErrorCategory.CLIENT_ERROR, "Operation timed out.");

            // User/input errors
            case NumberFormatException ignored ->
                new ExceptionInfo(ErrorCategory.USER_ERROR, "Invalid number format: " + message);
            case IllegalArgumentException ignored ->
                new ExceptionInfo(ErrorCategory.USER_ERROR, "Invalid input: " + message);
            case JsonParseException ignored -> new ExceptionInfo(ErrorCategory.USER_ERROR, "Parsing error: " + message);
            case MalformedURLException ignored ->
                new ExceptionInfo(ErrorCategory.USER_ERROR, "Invalid URL: " + message);
            // Certificate problems (e.g. the user declined an untrusted certificate) are not
            // recoverable by re-logging in - surface them instead of retrying.
            case java.security.cert.CertificateException ignored ->
                new ExceptionInfo(ErrorCategory.USER_ERROR, message);

            // Technical/system errors
            case NullPointerException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Technical Error: NullPointerException");
            case IllegalStateException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Operation not allowed in current state: " + message);
            case ClassCastException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Invalid type cast: " + message);
            case ArithmeticException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Arithmetic error: " + message);
            case IndexOutOfBoundsException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Index out of bounds: " + message);
            case ConcurrentModificationException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Concurrent modification detected.");
            case UnsupportedOperationException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Operation not supported.");
            case NoSuchMethodException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Method not found: " + message);
            case ClassNotFoundException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Required class not found: " + message);
            case SecurityException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Permission denied: " + message);
            case SQLException ignored -> new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Database error: " + message);
            case FileNotFoundException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "File not found: " + message);
            case IOException ignored -> new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "IO error: " + message);
            case InterruptedException ignored ->
                new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, "Operation interrupted.");

            // Custom exceptions - CLIENT_ERROR for search-related issues
            case SearchException ignored -> new ExceptionInfo(ErrorCategory.CLIENT_ERROR, message);
            case TemporarySearchException ignored -> new ExceptionInfo(ErrorCategory.CLIENT_ERROR, message);
            case StorageException ignored -> new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, message);

            default -> {
                // Fallback to a generic message if no specific formatting applies
                if (isBlank(message) || message.equals("null")) {
                    message = cause.getClass().getSimpleName();
                }
                yield new ExceptionInfo(ErrorCategory.TECHNICAL_ERROR, message);
            }
        };
    }

    private static final String[] TRANSPORT_MESSAGES = {"connection refused", "connection reset",
            "connect timed out", "read timed out", "no route to host", "network is unreachable",
            "host is unreachable"};

    /** Whether {@code throwable} or one of its causes says Splunk could not be reached. */
    public static boolean isNetworkError(Throwable throwable) {
        return findHttpException(throwable) == null && findTransportFailure(throwable) != null;
    }

    // Identity comparison is intentional: the guard detects a self-referential
    // cause (an exception cycle), which only the exact same instance can be.
    @SuppressWarnings("ReferenceEquality")
    private static Throwable findTransportFailure(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < 32; depth++) {
            if (cause instanceof java.net.SocketException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof UnknownHostException || hasTransportMessage(cause)) {
                return cause;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static boolean hasTransportMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String pattern : TRANSPORT_MESSAGES) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String transportMessage(Throwable transport) {
        String message = transport.getMessage();
        String prefix = switch (transport) {
            case UnknownHostException ignored -> "Unknown host";
            case java.net.NoRouteToHostException ignored -> "No route to host";
            case java.net.ConnectException ignored -> "Connection refused";
            case java.net.SocketTimeoutException ignored -> "Connection timed out";
            case java.net.SocketException ignored -> "Network error";
            default -> null;
        };
        if (prefix == null || isBlank(message)) {
            return prefix != null ? prefix : message;
        }
        return prefix + ": " + message;
    }

    // Identity comparison is intentional: the guard detects a self-referential
    // cause (an exception cycle), which only the exact same instance can be.
    @SuppressWarnings("ReferenceEquality")
    private static HttpException findHttpException(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < 32; depth++) {
            if (cause instanceof HttpException http) {
                return http;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return null;
    }

    // Identity comparison is intentional: the guard detects a self-referential
    // cause (an exception cycle), which only the exact same instance can be.
    @SuppressWarnings("ReferenceEquality")
    private static Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable;

        while (cause.getCause() != null && cause.getCause() != cause) {
            log.debug("getRootCause:", cause);
            switch (cause) {
                case SearchException e -> {
                    return e;
                }
                case TemporarySearchException e -> {
                    return e;
                }
                case StorageException e -> {
                    return e;
                }
                default -> {}
            }
            cause = cause.getCause();
        }
        return cause;
    }

    // Example usage
    public static void main(String[] args) {
        // Test with nested exceptions
        try {
            throwNestedException();
        } catch (Exception e) {
            log.info(formatException(e));
        }

        // Test with direct exceptions
        try {
            throw new UnknownHostException("remote.server.xyz");
        } catch (Exception e) {
            log.info(formatException(e));
        }
    }

    private static void throwNestedException() {
        try {
            throw new IOException("Disk error");
        } catch (IOException e) {
            throw new RuntimeException("Processing failed", e);
        }
    }
}
