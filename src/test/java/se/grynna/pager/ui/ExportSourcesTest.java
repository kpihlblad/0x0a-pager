package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.export.ExportEvent;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.LogEventMapper;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportSourcesTest {

    private static final Instant T0 = Instant.parse("2023-01-01T12:00:00Z");

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    /** Event-level line at {@code seconds} after T0. */
    private static LoadedLogLine event(long seconds, String message) {
        Instant time = T0.plusSeconds(seconds);
        LoadedLogLine line = new LoadedLogLine(MsgType.JSON, null, 0, "cd" + seconds, "bkt", time, "raw", time, time,
                message, false, Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cd" + seconds, "_bkt", "bkt")));
        return line;
    }

    private static List<String> messages(List<ExportEvent> events) {
        return events.stream().map(event -> String.join("|", event.messageLines())).toList();
    }

    /** Rows of the events, split the way the log view shows them; the log provider reads them by row. */
    private static LogDataProvider logRows(LoadedLogLine... events) {
        List<LoadedLogLine> rows = Stream.of(events).flatMap(event -> LogEventMapper.splitLines(event).stream())
                .toList();
        LogDataProvider provider = mock(LogDataProvider.class);
        when(provider.peekLine(anyInt())).thenAnswer(inv -> {
            int row = inv.getArgument(0);
            return row >= 0 && row < rows.size() ? Optional.<LogLine>of(rows.get(row)) : Optional.<LogLine>empty();
        });
        return provider;
    }

    @Test
    void selectionTouchingSeveralRowsOfOneEventGivesThatEventOnceAndTheOtherEventsInOrder() {
        // Rows: event 10 is rows 0-2, event 20 is row 3, event 30 is row 4.
        BookmarksDataProvider provider = new BookmarksDataProvider(
                List.of(event(10, "a1\na2\na3"), event(20, "b"), event(30, "c")), false, "");

        List<ExportEvent> selected = ExportSources.selection(provider, List.of(2, 1, 3), Optional.of(4));

        assertThat(messages(selected)).containsExactly("a1|a2|a3", "b");
    }

    @Test
    void selectionOverLogRowsReadsWholeEventsAndIgnoresTheCursorWhenThereIsASelection() {
        LogDataProvider provider = logRows(event(10, "a1\na2\na3"), event(20, "b"));

        List<ExportEvent> selected = ExportSources.selection(provider, List.of(1, 2, 3), Optional.of(0));

        assertThat(messages(selected)).containsExactly("a1|a2|a3", "b");
    }

    @Test
    void withoutASelectionTheCursorRowsEventIsTheSource() {
        BookmarksDataProvider provider = new BookmarksDataProvider(
                List.of(event(10, "a1\na2\na3"), event(20, "b"), event(30, "c")), false, "");

        assertThat(messages(ExportSources.selection(provider, List.of(), Optional.of(1)))).containsExactly("a1|a2|a3");
        assertThat(messages(ExportSources.selection(provider, List.of(), Optional.of(4)))).containsExactly("c");
    }

    @Test
    void withoutASelectionOrACursorThereIsNothingToExport() {
        BookmarksDataProvider provider = new BookmarksDataProvider(List.of(event(10, "a")), false, "");

        assertThat(ExportSources.selection(provider, List.of(), Optional.empty())).isEmpty();
    }

    @Test
    void aCursorRowOutsideTheRowsGivesNothing() {
        BookmarksDataProvider provider = new BookmarksDataProvider(List.of(event(10, "a")), false, "");

        assertThat(ExportSources.selection(provider, List.of(), Optional.of(7))).isEmpty();
    }

    @Test
    void matchesAreEmptyWithoutASearch() {
        assertThat(ExportSources.matches(null)).isEmpty();
    }

    @Test
    void matchesAreTheLoadedEventsOfTheSnapshotInHitOrder() {
        LoadedLogLine first = event(10, "match one");
        LoadedLogLine second = event(20, "match two");
        List<SearchHit> hits = List.of(SearchHit.from(first), SearchHit.from(event(15, "not loaded")),
                SearchHit.from(second));
        var snapshot = new SearchSession.Snapshot(1, hits, java.util.Arrays.asList(first, null, second),
                SearchSession.State.COMPLETED, T0, T0.plusSeconds(60), false, false, false);

        assertThat(messages(ExportSources.matches(snapshot))).containsExactly("match one", "match two");
    }

    @Test
    void bookmarksComeInEventOrderWhateverTheToggleOrder() {
        Bookmarks bookmarks = new Bookmarks();
        bookmarks.toggle(List.of(event(30, "third"), event(10, "first\nmore")));
        bookmarks.toggle(List.of(event(20, "second")));

        List<ExportEvent> exported = ExportSources.bookmarks(bookmarks);

        assertThat(messages(exported)).containsExactly("first|more", "second", "third");
        assertThat(exported.get(0).displayTime()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void bookmarksAreEmptyWhenNoneAreSet() {
        assertThat(ExportSources.bookmarks(new Bookmarks())).isEmpty();
    }

    @Test
    void selectedEventsAreDistinctByIdentity() {
        List<LoadedLogLine> events = new ArrayList<>(ExportSources.selectedEvents(
                new BookmarksDataProvider(List.of(event(10, "a1\na2"), event(20, "b")), false, ""),
                List.of(0, 1, 1, 0)));

        assertThat(events).hasSize(1);
        assertThat(SearchHit.from(events.getFirst())).isEqualTo(SearchHit.from(event(10, "")));
    }
}
