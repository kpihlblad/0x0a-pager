package se.grynna.pager.exceptions;

/**
 * Thrown when the database files are locked by another running 0x0A Pager instance
 * (or by a previous session that has not finished releasing them).
 *
 * This condition must never be "recovered" by deleting the files — the owning
 * instance is still writing to them.
 */
public class DatabaseLockedException extends StorageException {

    public DatabaseLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
