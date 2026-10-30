package se.grynna.pager.exceptions;

public class TemporarySearchException extends Exception {

    public TemporarySearchException(String message) {
        super(message);
    }

    public TemporarySearchException(TemporarySearchException cause) {
        super(cause.getMessage(), cause);
    }

    public TemporarySearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
