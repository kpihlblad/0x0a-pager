package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;

import java.time.Instant;
import java.util.List;

/**
 * Pure decisions for folding a search into the scope.
 * The wiring (leaving the Matches view, the provider reload) lives in {@code LogViewer}.
 */
final class ScopeChange {

    private ScopeChange() {}

    /**
     * The match to land on after narrowing: the first of {@code preferred} that is a
     * match of the search (same event identity), otherwise the match nearest at/after the
     * search origin, or the last one before it. Null when the search has no match.
     *
     * @param preferred candidates in priority order (selected match, cursor event, visible events);
     *                  nulls and events that are not matches are skipped
     */
    static SearchHit chooseNarrowHit(SearchSession.Snapshot snapshot, List<SearchHit> preferred, Instant origin) {
        for (SearchHit candidate : preferred) {
            if (candidate != null && snapshot.indexOf(candidate) >= 0) {
                return candidate;
            }
        }
        List<SearchHit> hits = snapshot.hits();
        if (hits.isEmpty()) {
            return null;
        }
        if (origin == null) {
            return hits.getFirst();
        }
        int index = snapshot.ceilingIndex(new SearchHit(origin, Instant.MIN, null, null));
        return hits.get(Math.min(index, hits.size() - 1));
    }

    /** {@code target} limited to the latest instant the provider can jump to. */
    static Instant clampToLatest(Instant target, Instant latest) {
        return target.isAfter(latest) ? latest : target;
    }
}
