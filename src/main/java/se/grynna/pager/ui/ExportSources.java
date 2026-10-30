package se.grynna.pager.ui;

import se.grynna.pager.export.ExportEvent;
import se.grynna.pager.splunk.EventOrder;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Where copy-as and export take their events from: the selection (or the cursor's event), the
 * search's matches, or the bookmarks. Every result is distinct per event and in {@link EventOrder}.
 * <p>
 * EDT-confined: it reads view state. Log rows go through {@link EventLines}; the event-list views
 * already hold whole events.
 */
public final class ExportSources {

    /** The choices of the Export dialog. */
    public enum Source {
        SELECTION, MATCHES, BOOKMARKS
    }

    private ExportSources() {}

    /**
     * The events the selection touches, or the cursor row's event when nothing is selected.
     *
     * @param provider     the view being shown
     * @param selectedRows the selected rows, empty without a selection
     * @param cursorRow    the cursor row, empty in page mode
     */
    public static List<ExportEvent> selection(LogDataProvider provider, List<Integer> selectedRows,
            Optional<Integer> cursorRow) {
        return selectionEvents(provider, selectedRows, cursorRow).stream().map(ExportEvent::of).toList();
    }

    /** The event-level lines behind {@link #selection}, for callers that need their identities. */
    static List<LoadedLogLine> selectionEvents(LogDataProvider provider, List<Integer> selectedRows,
            Optional<Integer> cursorRow) {
        List<Integer> rows = selectedRows.isEmpty() ? cursorRow.map(List::of).orElse(List.of()) : selectedRows;
        return selectedEvents(provider, rows);
    }

    /** The distinct event-level lines of {@code rows}, in event order. */
    static List<LoadedLogLine> selectedEvents(LogDataProvider provider, List<Integer> rows) {
        TreeMap<SearchHit, LoadedLogLine> distinct = new TreeMap<>(EventOrder.searchHitComparator());
        for (int row : rows) {
            eventAt(provider, row).ifPresent(event -> distinct.putIfAbsent(SearchHit.from(event), event));
        }
        return List.copyOf(distinct.values());
    }

    private static Optional<LoadedLogLine> eventAt(LogDataProvider provider, int row) {
        if (provider instanceof EventListProvider list) {
            if (row < 0 || row >= list.rows().rowCount()) {
                return Optional.empty();
            }
            return Optional.of(list.rows().event(list.rows().eventAt(row)));
        }
        return EventLines.eventOf(provider::peekLine, row);
    }

    /** Every loaded event of the search snapshot, in hit order; empty for a null snapshot (no search). */
    public static List<ExportEvent> matches(SearchSession.Snapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.lines().stream().filter(Objects::nonNull).map(ExportEvent::of).toList();
    }

    /** The bookmarked events in {@link EventOrder} order. */
    public static List<ExportEvent> bookmarks(Bookmarks bookmarks) {
        return bookmarks.events().stream().map(ExportEvent::of).toList();
    }
}
