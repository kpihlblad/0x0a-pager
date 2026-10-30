package se.grynna.pager.splunk;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Slf4j
public class SearchBoundaries {
    /**
     * Absolute earliest boundary — a search cannot start before this.
     * <p>
     * Splunk rejects the epoch instant {@code 1970-01-01T00:00:00Z}, and
     * {@code earliest_time=0} means "time is not used" rather than "all time".
     * One year past the epoch is the earliest instant it will accept.
     */
    public static final Instant MIN_MINIMUM_BOUNDARY = Instant.parse("1971-01-01T00:00:00Z");
    /**
     * The year of {@link #MIN_MINIMUM_BOUNDARY} in UTC ("1971") — the designator the UI shows wherever it
     * names the earliest possible time instead of a concrete timestamp. Literally always the minimum time.
     */
    public static final String MIN_BOUNDARY_YEAR = String.valueOf(MIN_MINIMUM_BOUNDARY.atZone(ZoneOffset.UTC)
            .getYear());
    /**
     * Earliest time boundary for log searches - data before this time will not be loaded
     */
    @NonNull
    private Instant minBoundary;

    private final Object lock = new Object();
    private final Clock clock;

    /**
     * Latest time boundary for log searches - data after this time will not be loaded
     */
    @NonNull
    private Instant maxBoundary;

    public SearchBoundaries(Clock clock) {
        this.clock = clock;
        reset(MIN_MINIMUM_BOUNDARY, Instant.MAX);
    }

    public void reset(Instant minBoundary, Instant maxBoundary) {
        this.minBoundary = minBoundary;
        this.maxBoundary = maxBoundary;
    }

    private Instant calculateNlt() {
        return clock.instant().minus(LoadingStrategy.NO_LATER_THAN_OFFSET).truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * Get the dynamic "now" boundary - data can't be newer than this.
     * This is typically "now minus 5 minutes" to account for indexing delays.
     */
    public Instant getNowBoundary() {
        return calculateNlt();
    }

    public void setMinBoundary(Instant t) {
        if (t.isBefore(MIN_MINIMUM_BOUNDARY)) {
            log.warn("MIN boundary {} is before {}, clamping", t, MIN_MINIMUM_BOUNDARY);
            t = MIN_MINIMUM_BOUNDARY;
        }
        synchronized (lock) {
            if (t.equals(minBoundary)) {
                return;
            }
            log.info("Setting MIN boundary to {} (was {})", t, minBoundary);
            minBoundary = t;
        }
    }

    public void setMaxBoundary(Instant t) {
        synchronized (lock) {
            if (t.equals(maxBoundary)) {
                return;
            }
            log.info("Setting MAX boundary to {} (was {})", t, maxBoundary);
            maxBoundary = t;
        }
    }

    /**
     * Get the current minimum boundary.
     */
    public Instant getMinBoundary() {
        synchronized (lock) {
            return minBoundary;
        }
    }

    /**
     * Get the current maximum boundary.
     */
    public Instant getMaxBoundary() {
        synchronized (lock) {
            return maxBoundary;
        }
    }

    Instant getEffectiveMaxBoundary() {
        Instant nowBoundary = calculateNlt();
        return maxBoundary.isBefore(nowBoundary) ? maxBoundary : nowBoundary;
    }
}
