package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplunkLogDataProvider;

import javax.swing.*;
import java.awt.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The bookmark toggle through the log view: rows 0-2 are one event (row 0 first, rows 1-2 continuations),
 * row 3 and row 4 are events of their own. No window is shown.
 */
class LogViewerBookmarksTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int[] EVENT_OF_ROW = {0, 0, 0, 1, 2};
    private static final boolean[] CONTINUATION = {false, true, true, false, false};

    private LogViewer viewer;
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider provider = mock(SplunkLogDataProvider.class);
        when(provider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(provider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, T0.plusSeconds(600)));
        when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T0.plusSeconds(600)));
        when(provider.getStatistics()).thenReturn(new LogStatistics(LogStatistics.Status.LOADED, 5, T0,
                T0.plusSeconds(600)));
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> line(inv.getArgument(0)));
        when(provider.peekLine(anyInt())).thenAnswer(inv -> Optional.of(line(inv.getArgument(0))));

        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(provider);
        when(searchManager.scope()).thenReturn(Scope.of("web", Map.of()));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
            viewer.cursorManager().updateSize(50, 200);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
    }

    @Test
    void aSelectionBookmarksEveryEventItTouchesOnceAndASecondToggleRemovesThem() throws Exception {
        onEdt(() -> {
            // Mark on row 1 (a continuation of event 0), cursor on row 3 (event 1).
            viewer.cursorManager().ensureCursorAt(1);
            viewer.cursorManager().moveCursor(2, 5, true);

            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().size()).isEqualTo(2);
            assertThat(viewer.bookmarks().contains(hit(0))).isTrue();
            assertThat(viewer.bookmarks().contains(hit(1))).isTrue();
            assertThat(viewer.bookmarks().contains(hit(2))).isFalse();
            assertThat(statusBar.noticeText()).isEqualTo("2 bookmarked · F2 shows bookmarks");

            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().size()).isZero();
            assertThat(statusBar.noticeText()).isEqualTo("2 bookmarks removed");
        });
    }

    @Test
    void theCursorsEventIsBookmarkedFromAContinuationRowAndTheHintComesOnce() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(2);
            viewer.cursorManager().clearSelection();

            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().contains(hit(0))).isTrue();
            assertThat(statusBar.noticeText()).isEqualTo("Bookmarked · F2 shows bookmarks");

            viewer.cursorManager().ensureCursorAt(2);
            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().size()).isZero();
            assertThat(statusBar.noticeText()).isEqualTo("Bookmark removed");

            viewer.toggleBookmark();
            assertThat(statusBar.noticeText()).isEqualTo("Bookmarked");
        });
    }

    @Test
    void addingABookmarkMovesTheCursorToTheNextEventsFirstRow() throws Exception {
        onEdt(() -> {
            // Row 1 is a continuation of event 0 (rows 0–2); the next event starts on row 3.
            viewer.cursorManager().ensureCursorAt(1);
            viewer.cursorManager().clearSelection();

            viewer.toggleBookmark();
            assertThat(viewer.cursorManager().cursorLine()).contains(3);

            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().contains(hit(1))).isTrue();
            assertThat(viewer.cursorManager().cursorLine()).contains(4);
        });
    }

    @Test
    void removingABookmarkKeepsTheCursorWhereItIs() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            viewer.cursorManager().clearSelection();
            viewer.toggleBookmark();
            viewer.cursorManager().ensureCursorAt(3);

            viewer.toggleBookmark();

            assertThat(viewer.bookmarks().size()).isZero();
            assertThat(viewer.cursorManager().cursorLine()).contains(3);
        });
    }

    @Test
    void bookmarkingASelectionLeavesTheCursorAndSelectionAlone() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(1);
            viewer.cursorManager().moveCursor(2, 0, true);

            viewer.toggleBookmark();

            assertThat(viewer.cursorManager().cursorLine()).contains(3);
            assertThat(viewer.cursorManager().selectedRows()).isNotEmpty();
        });
    }

    @Test
    void pageModeHasNothingToBookmark() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().hideCursor();

            viewer.toggleBookmark();

            assertThat(viewer.bookmarks().size()).isZero();
            assertThat(statusBar.noticeText()).isEqualTo("Place the cursor on a line to bookmark it");
        });
    }

    @Test
    void logoutClearsTheBookmarks() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(4);
            viewer.cursorManager().clearSelection();
            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().size()).isEqualTo(1);
        });

        onEdt(() -> {
            var handleLogout = LogViewer.class.getDeclaredMethod("handleLogout");
            handleLogout.setAccessible(true);
            handleLogout.invoke(viewer);
            assertThat(viewer.bookmarks().size()).isZero();
        });
    }

    @Test
    void visibleRowsOfABookmarkedEventAreAllMarkedAndNoOthers() {
        List<DecoratedLogLine> rows = new ArrayList<>();
        for (int row = 0; row < EVENT_OF_ROW.length; row++) {
            rows.add(new DecoratedLogLine(line(row)));
        }
        Bookmarks bookmarks = new Bookmarks();
        bookmarks.toggle(List.of(line(0)));

        LogViewer.decorateBookmarks(rows, bookmarks);

        assertThat(rows).extracting(DecoratedLogLine::isBookmarked).containsExactly(true, true, true, false, false);
    }

    private static SearchHit hit(int event) {
        return SearchHit.from(line(firstRowOf(event)));
    }

    private static int firstRowOf(int event) {
        for (int row = 0; row < EVENT_OF_ROW.length; row++) {
            if (EVENT_OF_ROW[row] == event) {
                return row;
            }
        }
        throw new IllegalArgumentException("no event " + event);
    }

    /** The loaded line of a row; rows past the table are events of their own. */
    private static LoadedLogLine line(int row) {
        int event = row < EVENT_OF_ROW.length ? EVENT_OF_ROW[row] : row;
        boolean continuation = row < CONTINUATION.length && CONTINUATION[row];
        Instant time = T0.plusSeconds(10L * event);
        return new LoadedLogLine(MsgType.JSON, "seg", row, "cb" + event, "bkt", time, "raw", time, time,
                "message " + row, continuation, Level.INFO);
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        return flatten(root).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<Component> flatten(Container root) {
        List<Component> all = new ArrayList<>();
        for (Component child : root.getComponents()) {
            all.add(child);
            if (child instanceof Container container) {
                all.addAll(flatten(container));
            }
        }
        return all;
    }

    private static void onEdt(ThrowingRunnable r) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            if (failure[0] instanceof Exception e) throw e;
            if (failure[0] instanceof Error e) throw e;
            throw new RuntimeException(failure[0]);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
