package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a freshly submitted search may already jump to its first
 * match at or after the search origin.
 * <p>
 * A running search streams matches in Splunk order (newest first), so the match
 * nearest after the origin can still arrive later. Jumping early would yank the
 * view to a hit that is not the first one. The jump is therefore safe only when
 * the covered window proves that everything from the origin to the candidate was
 * searched — which is the case per chunk once the searcher works outward from
 * the origin, and after a single-window search completes.
 * <p>
 * A finished search that found nothing after the origin still lands on the last
 * match before it, so a search near the end of the data shows its nearest hit
 * instead of staying put. An errored search never moves the view.
 */
final class FirstMatchJump {

    private FirstMatchJump() {}

    static Optional<SearchHit> decide(SearchSession.Snapshot snapshot, Instant origin) {
        if (snapshot == null || origin == null) {
            return Optional.empty();
        }
        SearchSession.State state = snapshot.state();
        if (state == SearchSession.State.ERROR) {
            return Optional.empty();
        }

        // Probe that sorts before every real hit at exactly `origin`: equal-time
        // hits order by display time before bucket/chunk identity, so an
        // Instant.MIN display time wins against them while staying >= origin.
        SearchHit probe = new SearchHit(origin, Instant.MIN, null, null);
        int index = snapshot.ceilingIndex(probe);
        List<SearchHit> hits = snapshot.hits();
        SearchHit candidate = index < hits.size() ? hits.get(index) : null;

        if (candidate != null && covered(snapshot, origin, candidate.splunkTime())) {
            return Optional.of(candidate);
        }
        if (state == SearchSession.State.RUNNING) {
            return Optional.empty();
        }
        // COMPLETED or CANCELLED: the hits we have are all we will get.
        if (candidate != null) {
            return Optional.of(candidate);
        }
        return index > 0 ? Optional.of(hits.get(index - 1)) : Optional.empty();
    }

    /** True when [{@code origin}, {@code candidateTime}] lies inside the searched window. */
    private static boolean covered(SearchSession.Snapshot snapshot, Instant origin, Instant candidateTime) {
        Instant start = snapshot.windowStart();
        Instant end = snapshot.windowEnd();
        return start != null && end != null && !start.isAfter(origin) && !end.isBefore(candidateTime);
    }
}
