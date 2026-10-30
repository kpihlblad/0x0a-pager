package se.grynna.pager.splunk;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;

/**
 * Result of evaluating whether we should load more data.
 *
 * @param shouldLoad  whether to load more data
 * @param start       the start time of the range to load (inclusive)
 * @param end         the end time of the range to load (exclusive)
 * @param reason      human-readable explanation
 * @param boundaryHit which boundary was hit (if any)
 * @param maxEvents   optional limit on events - when set, adds |head N to query and
 *                    triggers SearchResultTooBigException if exceeded during progress checks
 */
public record LoadDecision(DirectionStateBase state, boolean shouldLoad, int direction, Instant start, Instant end,
        String reason, BoundaryType boundaryHit, OptionalInt maxEvents) {

    public LoadDecision {
        if (reason == null) {
            throw new NullPointerException("reason must be non-null");
        }
    }

    public static LoadDecision noLoad(BoundaryType boundaryHit, String reason) {
        return new LoadDecision(null, false, 0, null, null, reason, boundaryHit, OptionalInt.empty());
    }

    public static LoadDecision load(DirectionStateBase state, int direction, Instant start, Instant end,
            String reason) {
        if (start == null) {
            throw new NullPointerException("start must be non-null for load decision" + state);
        }
        if (end == null) {
            throw new NullPointerException("end must be non-null for load decision: " + state);
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException(
                    "start and end cannot be equal for load decision.\nStart: " + start + "\nState: " + state);
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end for load decision.\nStart: " + start
                    + "\nEnd  : " + end + "\nState: " + state);
        }

        return new LoadDecision(state, true, direction, start, end, reason, null, OptionalInt.empty());
    }

    /**
     * Create a load decision with a maximum event limit.
     * Used during binary search to quickly detect truncation.
     */
    public static LoadDecision loadWithMaxEvents(DirectionStateBase state, int direction, Instant start, Instant end,
            String reason, int maxEvents) {
        if (start == null) {
            throw new NullPointerException("start must be non-null for load decision");
        }
        if (end == null) {
            throw new NullPointerException("end must be non-null for load decision");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "start must be before end for load decision.\nStart: " + start + "\nEnd  : " + end);
        }
        return new LoadDecision(state, true, direction, start, end, reason, null, OptionalInt.of(maxEvents));
    }

    public static LoadDecision boundary(DirectionStateBase state, BoundaryType boundaryType, String reason) {
        return new LoadDecision(state, false, 0, null, null, reason, boundaryType, OptionalInt.empty());
    }

    /** True if {@code t} lies in the decision's half-open window {@code [start, end)}. */
    public boolean contains(Instant t) {
        return !t.isBefore(start) && t.isBefore(end);
    }

    /**
     * True if the window lies within one whole second. Splunk floors a sub-second
     * {@code earliest_time} to the second, so such a window cannot be narrowed further;
     * {@link SplunkClient} reads it completely, paging past the REST row limit.
     */
    public boolean withinOneSecond() {
        return shouldLoad && !end.isAfter(start.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
    }

    public LoadDecision withoutMaxEvents() {
        return new LoadDecision(state, shouldLoad, direction, start, end, reason, boundaryHit, OptionalInt.empty());
    }

    // duration
    public Duration getDuration() {
        return Duration.between(start, end);
    }

    @Override
    public String toString() {
        return "LoadDecision{" + " dir=" + direction + ",shouldLoad=" + shouldLoad + ", " + start + " - " + end
                + ", duration=" + (shouldLoad ? getDuration() : null) + ", reason='" + reason + '\'' + ", boundaryHit="
                + boundaryHit + (maxEvents.isPresent() ? ", maxEvents=" + maxEvents.getAsInt() : "") + ", state="
                + state + '}';
    }
}
