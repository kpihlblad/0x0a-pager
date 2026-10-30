package se.grynna.pager.ui;

import se.grynna.pager.splunk.AfterBoundaryLine;
import se.grynna.pager.splunk.BeforeBoundaryLine;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.Placeholder;
import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import static se.grynna.pager.splunk.BoundaryType.*;

/**
 * In-memory {@link LogDataProvider} that shows only a search's matching events,
 * rendered through {@link EventRows}. It is backed by an immutable
 * {@link SearchSession.Snapshot}; {@link #refresh(SearchSession.Snapshot)} swaps
 * in a newer snapshot and rebuilds the rows.
 * <p>
 * EDT-confined by design: no threads, no I/O, no Splunk or {@code SearchManager}
 * access. Out-of-range rows are represented as {@link BeforeBoundaryLine} /
 * {@link AfterBoundaryLine}, or an animated {@link Placeholder} while that side
 * of the search is still {@link SearchSession.State#RUNNING}.
 */
public final class MatchesDataProvider extends EventListProvider {

    static final String MATCH_IN_FIELDS = "(match in fields)";
    /** Mirrors {@code Searcher.MAX_HITS}, the cap behind the match-limit edge text. */
    static final int MAX_MATCHES = 10_000;

    private SearchSession.Snapshot snapshot;

    private final Instant earliestPossible;
    private final Instant latestPossible;
    private BoundaryExpansionListener expansionListener;
    /** The text of a placeholder view that shows no search at all; null for a real search. */
    private final Supplier<String> placeholderText;

    public MatchesDataProvider(SearchSession.Snapshot snapshot) {
        this(snapshot, SearchBoundaries.MIN_MINIMUM_BOUNDARY, Instant.MAX);
    }

    public MatchesDataProvider(SearchSession.Snapshot snapshot, Instant earliestPossible, Instant latestPossible) {
        this(snapshot, earliestPossible, latestPossible, false);
    }

    /**
     * @param firstLineOnly show each event as its first line with a dim {@code ⏎+N}
     *                      suffix, instead of all of its lines
     */
    public MatchesDataProvider(SearchSession.Snapshot snapshot, Instant earliestPossible, Instant latestPossible,
            boolean firstLineOnly) {
        this(snapshot, earliestPossible, latestPossible, firstLineOnly, null);
    }

    private MatchesDataProvider(SearchSession.Snapshot snapshot, Instant earliestPossible, Instant latestPossible,
            boolean firstLineOnly, Supplier<String> placeholderText) {
        super(firstLineOnly);
        this.earliestPossible = earliestPossible;
        this.latestPossible = latestPossible;
        this.placeholderText = placeholderText;
        rebuild(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /**
     * The Matches view before any search: no rows, and {@code text} (read each time it is drawn, so it
     * follows the key hints preference) in place of a "No matches" message.
     */
    public static MatchesDataProvider placeholder(Supplier<String> text) {
        SearchSession.Snapshot none = new SearchSession.Snapshot(0, List.of(), List.of(),
                SearchSession.State.COMPLETED, null, null, false, false, false);
        return new MatchesDataProvider(none, SearchBoundaries.MIN_MINIMUM_BOUNDARY, Instant.MAX, false,
                Objects.requireNonNull(text, "text"));
    }

    /** Hint for the empty Matches view, naming the key that goes back. */
    static String emptyHint(KeyMap keyMap) {
        return "No search yet. Type a search term and press Enter, or " + keyMap.label(UiAction.CANCEL)
                + " to go back.";
    }

    /** Whether this is the search-less {@link #placeholder} view. */
    public boolean isPlaceholder() {
        return placeholderText != null;
    }

    /**
     * Swap in {@code newer} and rebuild the rows(). A snapshot whose version is not
     * strictly greater than the current one is ignored.
     */
    public void refresh(SearchSession.Snapshot newer) {
        if (newer == null || newer.version() <= snapshot.version()) {
            return;
        }
        rebuild(newer);
    }

    private void rebuild(SearchSession.Snapshot snap) {
        List<SearchHit> hits = snap.hits();
        List<LoadedLogLine> lines = snap.lines();
        List<LoadedLogLine> keptLines = new ArrayList<>(hits.size());
        List<SearchHit> keptHits = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            LoadedLogLine line = lines.get(i);
            if (line != null) {
                keptLines.add(line);
                keptHits.add(hits.get(i));
            }
        }

        Instant beforeTime = snap.windowStart() != null
                ? snap.windowStart()
                : keptHits.isEmpty()
                        ? snap.origin() != null ? snap.origin() : Instant.EPOCH
                        : keptHits.getFirst().splunkTime();
        Instant afterTime = snap.windowEnd() != null
                ? snap.windowEnd()
                : keptHits.isEmpty()
                        ? snap.origin() != null ? snap.origin() : Instant.EPOCH
                        : keptHits.getLast().splunkTime();

        this.snapshot = snap;
        setEvents(keptLines, keptHits, beforeTime, afterTime, snap.state() != SearchSession.State.RUNNING);
    }

    /** The snapshot backing this view. */
    public SearchSession.Snapshot snapshot() {
        return snapshot;
    }

    /**
     * {@link #MATCH_IN_FIELDS} when the search term occurs in none of the event's message rows,
     * i.e. the event matched on a field that is not displayed.
     */
    @Override
    protected String extraSuffix(int eventIndex) {
        return messageContainsQuery(rows().event(eventIndex).getMessage()) ? null : MATCH_IN_FIELDS;
    }

    private boolean messageContainsQuery(String message) {
        String query = snapshot.query();
        if (query == null || query.isEmpty()) {
            return true;
        }
        return message != null && message.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    /**
     * Only the row next to an edge carries that edge's text; the other out-of-range rows are
     * blank (placeholders stay animated while their side runs). With no rows yet, row 0 is the
     * one row next to both edges and carries a combined message.
     */
    @Override
    public LogLine getLine(int line, boolean edge) {
        if (rows().rowCount() == 0 && snapshot.state() != SearchSession.State.RUNNING) {
            if (line >= 0) return new AfterBoundaryLine(line, getAfterBoundary(), line == 0 ? emptyMessage() : "");
            return new BeforeBoundaryLine(line, getBeforeBoundary(), "");
        }
        if (rows().rowCount() == 0 && (running(false) || running(true))) {
            return Placeholder.from(line, line == 0 ? searchingMessage() : "");
        }
        if (line < 0) {
            return running(false)
                    ? Placeholder.from(line, line == -1 ? searchingEarlierMessage() : "")
                    : new BeforeBoundaryLine(line, getBeforeBoundary(), line == -1 ? edgeText(false) : "");
        }
        if (line >= rows().rowCount()) {
            boolean adjacent = line == rows().rowCount();
            return running(true)
                    ? Placeholder.from(line, adjacent ? searchingLaterMessage() : "")
                    : new AfterBoundaryLine(line, getAfterBoundary(), adjacent ? edgeText(true) : "");
        }
        return rows().line(line);
    }

    private String searchingEarlierMessage() {
        return "Searching earlier… covered back to " + time(getBeforeBoundary().instant());
    }

    private String searchingLaterMessage() {
        return "Searching later… covered up to " + time(getAfterBoundary().instant());
    }

    private String searchingMessage() {
        boolean earlier = running(false);
        boolean later = running(true);
        if (earlier && later) {
            return "Searching… covered back to " + time(getBeforeBoundary().instant()) + ", up to "
                    + time(getAfterBoundary().instant());
        }
        return earlier ? searchingEarlierMessage() : searchingLaterMessage();
    }

    private String emptyMessage() {
        if (placeholderText != null) return placeholderText.get();
        if (snapshot.state() == SearchSession.State.ERROR) return "Search failed: " + snapshot.errorMessage();
        if (snapshot.truncatedEarlier() || snapshot.truncatedLater()) return edgeText(snapshot.truncatedLater());
        if (snapshot.state() == SearchSession.State.CANCELLED) return edgeText(true);
        return "No matches for \"" + snapshot.query() + "\" between " + time(getBeforeBoundary().instant())
                + " and " + time(getAfterBoundary().instant());
    }

    boolean running(boolean later) {
        boolean truncated = later ? snapshot.truncatedLater() : snapshot.truncatedEarlier();
        boolean done = later ? snapshot.laterDone() : snapshot.earlierDone();
        return snapshot.state() == SearchSession.State.RUNNING && !truncated && !done;
    }

    private static String time(Instant t) {
        return DateTimeFormatterManager.formatTimestamp(t);
    }

    private String edgeText(boolean later) {
        Instant covered = later ? getAfterBoundary().instant() : getBeforeBoundary().instant();
        if (snapshot.state() == SearchSession.State.ERROR) return "Search failed: " + snapshot.errorMessage();
        if (later ? snapshot.truncatedLater() : snapshot.truncatedEarlier()) {
            return "Match limit (" + String.format(Locale.ROOT, "%,d", MAX_MATCHES) + ") reached. Matches "
                    + (later ? "after " : "before ")
                    + time(covered) + " are not shown.";
        }
        if (snapshot.state() == SearchSession.State.CANCELLED) return "Search stopped at " + time(covered);
        if (later ? !covered.isBefore(latestPossible) : !covered.isAfter(earliestPossible)) {
            return later ? "Now" : "Start of searchable data";
        }
        return "Searched " + (later ? "up to " : "back to ") + time(covered);
    }

    @Override
    public void unfreezeBeforeBoundary() {
        requestExpansion(false);
    }

    @Override
    public void unfreezeAfterBoundary() {
        requestExpansion(true);
    }

    private void requestExpansion(boolean later) {
        if (expansionListener != null && !running(later)) {
            expansionListener.requestBoundaryExpansion(new BoundaryRequest(later ? AFTER : BEFORE,
                    later ? getAfterBoundary().instant() : getBeforeBoundary().instant(),
                    (type, accepted, boundary) -> {}));
        }
    }

    @Override
    public void setBoundaryExpansionListener(BoundaryExpansionListener listener) {
        expansionListener = listener;
    }

    @Override
    protected Instant windowStart() {
        return snapshot.windowStart();
    }

    @Override
    protected Instant windowEnd() {
        return snapshot.windowEnd();
    }
}
