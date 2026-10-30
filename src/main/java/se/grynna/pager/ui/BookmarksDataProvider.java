package se.grynna.pager.ui;

import se.grynna.pager.splunk.AfterBoundaryLine;
import se.grynna.pager.splunk.BeforeBoundaryLine;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The bookmarked events as a list: {@link Bookmarks#events()} over {@link EventRows}, no
 * streaming. {@link #refresh(List)} swaps in a newer list; the viewer re-anchors the view
 * with {@link Reanchor} around it.
 * <p>
 * Outside the list the rows are plain dim text: the first bookmark is preceded by
 * {@link #FIRST_BOOKMARK}, the last is followed by {@link #LAST_BOOKMARK}; with no bookmarks
 * the one row 0 holds the empty-state hint.
 */
public final class BookmarksDataProvider extends EventListProvider {

    static final String FIRST_BOOKMARK = "First bookmark";
    static final String LAST_BOOKMARK = "Last bookmark";

    private final Supplier<String> emptyText;

    /**
     * @param events        the bookmarked events in event order
     * @param firstLineOnly show each event as its first line with a dim {@code ⏎+N} suffix
     * @param emptyText     the hint shown while there are no bookmarks
     */
    public BookmarksDataProvider(List<LoadedLogLine> events, boolean firstLineOnly, String emptyText) {
        this(events, firstLineOnly, () -> emptyText);
    }

    /**
     * @param events        the bookmarked events in event order
     * @param firstLineOnly show each event as its first line with a dim {@code ⏎+N} suffix
     * @param emptyText     the hint shown while there are no bookmarks, read each time it is drawn so that
     *                      it follows the key hints preference
     */
    public BookmarksDataProvider(List<LoadedLogLine> events, boolean firstLineOnly, Supplier<String> emptyText) {
        super(firstLineOnly);
        this.emptyText = Objects.requireNonNull(emptyText, "emptyText");
        refresh(events);
    }

    /** Hint for the empty view, naming the key that bookmarks a line. */
    static String emptyHint(KeyMap keyMap) {
        return "No bookmarks yet. Press " + keyMap.label(UiAction.TOGGLE_BOOKMARK) + " on a line to bookmark it.";
    }

    /** Replace the shown events with {@code events} (in event order) and rebuild the rows. */
    public void refresh(List<LoadedLogLine> events) {
        List<SearchHit> hits = events.stream().map(SearchHit::from).toList();
        Instant first = hits.isEmpty() ? Instant.EPOCH : hits.getFirst().splunkTime();
        Instant last = hits.isEmpty() ? Instant.EPOCH : hits.getLast().splunkTime();
        setEvents(events, hits, first, last, true);
    }

    @Override
    public LogLine getLine(int line, boolean edge) {
        int rowCount = rows().rowCount();
        if (rowCount == 0) {
            if (line >= 0) {
                return new AfterBoundaryLine(line, getAfterBoundary(), line == 0 ? emptyText.get() : "");
            }
            return new BeforeBoundaryLine(line, getBeforeBoundary(), "");
        }
        if (line < 0) {
            return new BeforeBoundaryLine(line, getBeforeBoundary(), line == -1 ? FIRST_BOOKMARK : "");
        }
        if (line >= rowCount) {
            return new AfterBoundaryLine(line, getAfterBoundary(), line == rowCount ? LAST_BOOKMARK : "");
        }
        return rows().line(line);
    }

    /**
     * Every event whole, as the log shows it, one string per row: what Copy puts on the clipboard
     * when nothing is selected. Independent of the first-line preference.
     */
    static List<String> wholeEventText(List<LoadedLogLine> events) {
        EventRows whole = EventRows.of(events, false);
        List<String> lines = new java.util.ArrayList<>(whole.rowCount());
        for (int row = 0; row < whole.rowCount(); row++) {
            lines.add(new DecoratedLogLine(whole.line(row)).formattedLine());
        }
        return lines;
    }
}
