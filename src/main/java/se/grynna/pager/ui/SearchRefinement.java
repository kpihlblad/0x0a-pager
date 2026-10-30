package se.grynna.pager.ui;

import se.grynna.pager.splunk.EventOrder;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSession.Snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Instant refinement, pure: when a new search term refines the active one, the matches
 * the old search already holds are tested locally against the new term and shown at once as
 * <em>provisional</em> matches, while Splunk's answer replaces them piece by piece.
 * <p>
 * <b>Caveat.</b> Splunk term search is tokenised ({@code search time} does not match
 * {@code timeout}); the local test is a case-insensitive substring test, the same as the yellow
 * highlight. The provisional set can therefore be a <em>superset</em> of the confirmed one, but it
 * can never miss a confirmed match that was in the old snapshot. Matches the old snapshot did not
 * hold (it was truncated at the cap, or still streaming) appear only once Splunk delivers them.
 * <p>
 * <b>Merge rule.</b> The view shows the confirmed hits (all) plus the provisional hits whose Splunk
 * time lies outside the new session's covered window {@code [windowStart, windowEnd)}: inside it
 * Splunk has answered. Once the session is no longer running (completed, cancelled, failed) the
 * remaining provisional hits are dropped. The merged snapshot keeps the confirmed snapshot's
 * <b>version</b>: the provisional list is fixed when the search starts, so the remainder is a pure
 * function of the confirmed snapshot, and every change to either shows up as a confirmed version
 * bump (including the end of the session, which drops the remainder). Providers and the
 * {@code version <=} guards therefore keep working unchanged.
 */
final class SearchRefinement {

    private SearchRefinement() {}

    /** Hits and mapped lines (same order, same length) the old search held and the new term still matches. */
    record Provisional(List<SearchHit> hits, List<LoadedLogLine> lines) {
        static final Provisional EMPTY = new Provisional(List.of(), List.of());

        Provisional {
            hits = List.copyOf(hits);
            lines = java.util.Collections.unmodifiableList(new ArrayList<>(lines));
        }

        int size() {
            return hits.size();
        }
    }

    /** The snapshot to show and how many of its hits are provisional. */
    record Merged(Snapshot snapshot, int provisionalCount) {}

    /** A plain term: no wildcard, no quote and no boolean operator token. It reaches Splunk as one quoted literal. */
    static boolean isPlain(String term) {
        if (term == null || term.isBlank() || term.indexOf('*') >= 0 || term.indexOf('"') >= 0) {
            return false;
        }
        for (String token : term.strip().split("\\s+")) {
            String upper = token.toUpperCase(Locale.ROOT);
            if (upper.equals("OR") || upper.equals("AND") || upper.equals("NOT")) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code newTerm} refines {@code oldTerm}: both plain, the new one contains the old one and differs. */
    static boolean isRefinement(String oldTerm, String newTerm) {
        if (!isPlain(oldTerm) || !isPlain(newTerm)) {
            return false;
        }
        String oldLower = oldTerm.strip().toLowerCase(Locale.ROOT);
        String newLower = newTerm.strip().toLowerCase(Locale.ROOT);
        return !newLower.equals(oldLower) && newLower.contains(oldLower);
    }

    /** The local test: case-insensitive substring of {@code _raw}, or of the message when {@code _raw} is blank. */
    static boolean matches(LoadedLogLine line, String newTerm) {
        if (line == null || newTerm == null || newTerm.isBlank()) {
            return false;
        }
        String text = line.getRaw() != null && !line.getRaw().isBlank() ? line.getRaw() : line.getMessage();
        return text != null && text.toLowerCase(Locale.ROOT).contains(newTerm.strip().toLowerCase(Locale.ROOT));
    }

    /** The old snapshot's matches that {@code newTerm} still matches locally. Hits without a line cannot be tested. */
    static Provisional provisional(Snapshot old, String newTerm) {
        if (old == null || old.size() == 0) {
            return Provisional.EMPTY;
        }
        List<SearchHit> hits = new ArrayList<>();
        List<LoadedLogLine> lines = new ArrayList<>();
        for (int i = 0; i < old.size(); i++) {
            LoadedLogLine line = old.lines().get(i);
            if (matches(line, newTerm)) {
                hits.add(old.hits().get(i));
                lines.add(line);
            }
        }
        return hits.isEmpty() ? Provisional.EMPTY : new Provisional(hits, lines);
    }

    /** {@code confirmed} plus the provisional hits that are outside its covered window; see the class comment. */
    static Merged merge(Snapshot confirmed, Provisional provisional) {
        if (provisional == null || provisional.size() == 0 || confirmed.state() != SearchSession.State.RUNNING) {
            return new Merged(confirmed, 0);
        }
        Set<SearchHit.Identity> known = new HashSet<>();
        for (SearchHit hit : confirmed.hits()) {
            if (hit.identity() != null) known.add(hit.identity());
        }
        List<SearchHit> extraHits = new ArrayList<>();
        List<LoadedLogLine> extraLines = new ArrayList<>();
        for (int i = 0; i < provisional.size(); i++) {
            SearchHit hit = provisional.hits().get(i);
            if (covered(confirmed, hit.splunkTime())
                    || (hit.identity() != null && known.contains(hit.identity()))
                    || confirmed.indexOf(hit) >= 0) {
                continue;
            }
            extraHits.add(hit);
            extraLines.add(provisional.lines().get(i));
        }
        if (extraHits.isEmpty()) {
            return new Merged(confirmed, 0);
        }

        Comparator<SearchHit> order = EventOrder.searchHitComparator();
        List<SearchHit> hits = new ArrayList<>(confirmed.size() + extraHits.size());
        List<LoadedLogLine> lines = new ArrayList<>(hits.size());
        int c = 0;
        int p = 0;
        while (c < confirmed.size() || p < extraHits.size()) {
            boolean takeConfirmed = p >= extraHits.size()
                    || (c < confirmed.size() && order.compare(confirmed.hits().get(c), extraHits.get(p)) <= 0);
            if (takeConfirmed) {
                hits.add(confirmed.hits().get(c));
                lines.add(confirmed.lines().get(c));
                c++;
            } else {
                hits.add(extraHits.get(p));
                lines.add(extraLines.get(p));
                p++;
            }
        }
        Snapshot merged = new Snapshot(confirmed.version(), hits, lines, confirmed.state(), confirmed.windowStart(),
                confirmed.windowEnd(), confirmed.truncatedEarlier(), confirmed.truncatedLater(),
                confirmed.tooManyMatchesWithinSecond(), confirmed.earlierDone(), confirmed.laterDone(),
                confirmed.query(), confirmed.origin(), confirmed.errorMessage());
        return new Merged(merged, extraHits.size());
    }

    private static boolean covered(Snapshot confirmed, Instant time) {
        Instant start = confirmed.windowStart();
        Instant end = confirmed.windowEnd();
        return start != null && end != null && !time.isBefore(start) && time.isBefore(end);
    }
}
