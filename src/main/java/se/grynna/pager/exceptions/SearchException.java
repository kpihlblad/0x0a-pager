package se.grynna.pager.exceptions;

public class SearchException extends Exception {

    public SearchException(String message, Throwable cause) {
        super(message, cause);
    }

    public SearchException(SearchException cause) {
        super(cause.getMessage(), cause);
    }
}
