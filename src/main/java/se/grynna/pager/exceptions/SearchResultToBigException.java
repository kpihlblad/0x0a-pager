package se.grynna.pager.exceptions;

import java.time.Instant;

public class SearchResultToBigException extends RuntimeException {
    private final int resultCount;
    private final Instant start;
    private final Instant end;

    public SearchResultToBigException(String message, int resultCount, Instant start, Instant end) {
        super(message);
        this.resultCount = resultCount;
        this.start = start;
        this.end = end;
    }

    public int getResultCount() {
        return resultCount;
    }

    public Instant getStart() {
        return start;
    }

    public Instant getEnd() {
        return end;
    }
}
