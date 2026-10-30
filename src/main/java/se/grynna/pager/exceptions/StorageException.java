package se.grynna.pager.exceptions;

public class StorageException extends Exception {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(StorageException cause) {
        super(cause.getMessage(), cause);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
