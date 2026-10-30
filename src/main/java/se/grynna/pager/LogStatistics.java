package se.grynna.pager;

import java.time.Instant;

public record LogStatistics(se.grynna.pager.LogStatistics.Status status, int loadedLines, Instant startTime,
        Instant endTime) {

    public enum Status {
        UNINITIALISED, LOADING, LOADED, ERROR
    }
}
