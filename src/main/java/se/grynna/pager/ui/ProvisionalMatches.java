package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchSession;

/**
 * The provisional matches of one refining search, bound to the session that is answering them
 * (see {@link SearchRefinement}). EDT only. A session other than the bound one always sees
 * its confirmed snapshot, so a stale holder can never leak into a newer search.
 */
@SuppressWarnings("ReferenceEquality")
final class ProvisionalMatches {

    private SearchSession session;
    private SearchRefinement.Provisional provisional = SearchRefinement.Provisional.EMPTY;
    /** The last merge, reused while the session hands out the same cached snapshot (same version). */
    private SearchSession.Snapshot mergedFrom;
    private SearchRefinement.Merged mergedResult;

    void bind(SearchSession session, SearchRefinement.Provisional provisional) {
        this.session = session;
        this.provisional = provisional;
        this.mergedFrom = null;
        this.mergedResult = null;
    }

    void clear() {
        session = null;
        provisional = SearchRefinement.Provisional.EMPTY;
        mergedFrom = null;
        mergedResult = null;
    }

    /** Whether any provisional matches are held (for any session). */
    boolean isActive() {
        return session != null && provisional.size() > 0;
    }

    boolean isBoundTo(SearchSession candidate) {
        return session != null && session == candidate && provisional.size() > 0;
    }

    /** The snapshot to show for {@code candidate}: merged while this holder is bound to it, else its confirmed snapshot. */
    SearchRefinement.Merged merged(SearchSession candidate) {
        if (!isBoundTo(candidate)) {
            return new SearchRefinement.Merged(candidate.snapshot(), 0);
        }
        SearchSession.Snapshot confirmed = candidate.snapshot();
        if (confirmed != mergedFrom) {
            mergedResult = SearchRefinement.merge(confirmed, provisional);
            mergedFrom = confirmed;
        }
        return mergedResult;
    }
}
