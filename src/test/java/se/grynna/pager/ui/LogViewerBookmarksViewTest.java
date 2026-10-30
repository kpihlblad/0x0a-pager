package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.AfterBoundaryLine;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.Searcher;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.clip.InMemoryClipboard;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Bookmarks view through the viewer, no window shown. The log has twelve rows of nine events:
 * event 0 is rows 0-2, event 2 is rows 4-5, every other event is one row.
 */
class LogViewerBookmarksViewTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int[] EVENT_OF_ROW = {0, 0, 0, 1, 2, 2, 3, 4, 5, 6, 7, 8};
    private static final boolean[] CONTINUATION = {false, true, true, false, false, true, false, false, false, false,
            false, false};

    private LogViewer viewer;
    private StatusBar statusBar;
    private SplunkLogDataProvider logProvider;
    private SearchManager searchManager;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();
    private final AtomicReference<Scope> scope = new AtomicReference<>(Scope.of("web", Map.of()));
    private final InMemoryClipboard clipboard = new InMemoryClipboard();

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        logProvider = mock(SplunkLogDataProvider.class);
        when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, T0.plusSeconds(600)));
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T0.plusSeconds(600)));
        when(logProvider.getStatistics()).thenReturn(new LogStatistics(LogStatistics.Status.LOADED,
                EVENT_OF_ROW.length, T0, T0.plusSeconds(600)));
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> line(inv.getArgument(0)));
        when(logProvider.peekLine(anyInt())).thenAnswer(inv -> Optional.of(line(inv.getArgument(0))));
        when(logProvider.jump(any(SearchHit.class)))
                .thenAnswer(inv -> firstRowOf(((SearchHit) inv.getArgument(0)).splunkTime()));

        searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(logProvider);
        when(searchManager.scope()).thenAnswer(inv -> scope.get());
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(T0.plusSeconds(3600));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            java.lang.reflect.Field field = LogViewer.class.getDeclaredField("searchManager");
            field.setAccessible(true);
            field.set(viewer, searchManager);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
            viewer.cursorManager().updateSize(50, 200);
            viewer.cursorManager().setClipboard(clipboard);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    // === Open and Esc ===

    @Test
    void escGoesBackToTheLogViewExactlyAsItWas() throws Exception {
        onEdt(() -> {
            bookmark(0, 2, 4);
            viewer.cursorManager().setPageStart(2);
            viewer.cursorManager().ensureCursorAt(6);
            viewer.cursorManager().scrollContent(0, 3);
            CursorManager.ViewState before = viewer.cursorManager().captureViewState();
            assertThat(before.pageStart()).isEqualTo(2);
            assertThat(before.horizontalOffset()).isEqualTo(3);

            viewer.showBookmarks();

            assertThat(viewer.isBookmarksView()).isTrue();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState().provider()).isInstanceOf(BookmarksDataProvider.class);
            assertThat(viewer.cursorManager().isCursorMode()).as("cursor mode on entry").isTrue();
            assertThat(statusBar.inputState()).isEqualTo(InputState.BOOKMARKS);
            assertThat(statusBar.searchPromptText()).startsWith("3 bookmarks · ").contains("remove").contains("back");

            assertThat(viewer.backToLog()).isTrue();

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(before);
            assertThat(statusBar.inputState()).isNotEqualTo(InputState.BOOKMARKS);
            assertThat(viewer.backToLog()).as("nothing left to leave").isFalse();
        });
    }

    @Test
    void theCursorStartsOnTheBookmarkNearestTheCursorEvent() throws Exception {
        onEdt(() -> {
            bookmark(0, 3, 7);
            viewer.cursorManager().ensureCursorAt(6); // event 3

            viewer.showBookmarks();

            // Bookmarked events: 0 (rows 0-2), 3 (row 3), 7 (row 4).
            assertThat(viewer.cursorManager().cursorLine()).hasValue(3);
        });
    }

    @Test
    void escFromBookmarksOpenedInMatchesReturnsToMatchesWithItsStateAndThenToTheLog() throws Exception {
        onEdt(() -> {
            startSearch(1, 3, 5, 7);
            bookmark(0, 3);
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();
            firstVisible(3);
            viewer.toggleMatches();
            assertThat(viewer.isMatchesView()).isTrue();
            viewer.cursorManager().ensureCursorAt(2);
            viewer.cursorManager().scrollContent(0, 2);
            CursorManager.ViewState matchesBefore = viewer.cursorManager().captureViewState();

            viewer.showBookmarks();
            assertThat(viewer.isBookmarksView()).isTrue();
            assertThat(viewer.isMatchesView()).isFalse();

            assertThat(viewer.backToLog()).isTrue();
            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(matchesBefore);
            assertThat(statusBar.inputState()).isEqualTo(InputState.MATCHES);

            assertThat(viewer.backToLog()).isTrue();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(logBefore);
        });
    }

    @Test
    void aMatchArrivingWhileTheBookmarksCoverTheMatchesViewIsTakenInOnReturnAndTheViewStaysStill()
            throws Exception {
        onEdt(() -> {
            startSearch(3, 5, 7);
            bookmark(3);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.cursorManager().ensureCursorAt(1); // event 5
            SearchHit cursorEvent = hitOfRow(viewer.cursorManager().cursorLine().orElseThrow());
            assertThat(cursorEvent).isEqualTo(hit(5));

            viewer.showBookmarks();
            SearchSessionTestSupport.addMatch(session.get(), hit(1), eventLine(1));

            assertThat(viewer.backToLog()).isTrue();

            MatchesDataProvider matches = (MatchesDataProvider) viewer.cursorManager().captureViewState().provider();
            assertThat(matches.rows().eventCount()).isEqualTo(4);
            int cursor = viewer.cursorManager().cursorLine().orElseThrow();
            assertThat(matches.hitAt(cursor)).as("same event under the cursor").isEqualTo(cursorEvent);
        });
    }

    @Test
    void addingABookmarkInTheMatchesViewMovesTheCursorToTheNextMatch() throws Exception {
        onEdt(() -> {
            startSearch(0, 3, 7);
            firstVisible(3);
            viewer.toggleMatches();
            MatchesDataProvider matches = (MatchesDataProvider) viewer.cursorManager().captureViewState().provider();
            viewer.cursorManager().ensureCursorAt(matches.rows().rowOf(0, 1)); // a continuation row of event 0
            viewer.cursorManager().clearSelection();

            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().contains(hit(0))).isTrue();
            assertThat(viewer.cursorManager().cursorLine()).hasValue(matches.rows().rowOf(1, 0));

            viewer.cursorManager().ensureCursorAt(matches.rows().rowOf(2, 0));
            viewer.toggleBookmark(); // the last match: nowhere to go
            assertThat(viewer.cursorManager().cursorLine()).hasValue(matches.rows().rowOf(2, 0));
        });
    }

    @Test
    void openingFromAMatchesViewAndPressingF4GoesBackToThatMatchesViewExactly() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.cursorManager().ensureCursorAt(1);
            CursorManager.ViewState matchesBefore = viewer.cursorManager().captureViewState();

            viewer.showBookmarks();
            viewer.toggleMatches();

            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(matchesBefore);
        });
    }

    @Test
    void f4FromBookmarksOpenedInTheLogEntersMatchesAndEscThenLandsOnTheLogAsItWas() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            bookmark(0);
            viewer.cursorManager().ensureCursorAt(3);
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();

            viewer.showBookmarks();
            viewer.toggleMatches();

            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState().provider()).isInstanceOf(MatchesDataProvider.class);

            viewer.backToLog();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(logBefore);
        });
    }

    @Test
    void f4FromTheLogWithNothingToAnchorOnStillOpensTheMatchesView() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);

            viewer.toggleMatches();

            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(viewer.cursorManager().cursorLine()).hasValue(0);
        });
    }

    @Test
    void f4WithoutAnActiveSearchShowsTheEmptyMatchesViewWithTheStandardSearchPrompt() throws Exception {
        onEdt(() -> {
            bookmark(0);
            viewer.showBookmarks();

            viewer.toggleMatches();

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(emptyMatches().isPlaceholder()).isTrue();
            assertThat(viewer.isSearchInputActive()).isTrue();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_INPUT);
        });
    }

    @Test
    void openingTheSearchPromptInTheBookmarksViewOpensItInTheLog() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            bookmark(0);
            viewer.toggleMatches();
            viewer.showBookmarks();

            viewer.openSearchPrompt("");

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState().provider()).isSameAs(logProvider);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_INPUT);
        });
    }

    @Test
    void openingTheBookmarksViewClosesAnOpenSearchPrompt() throws Exception {
        onEdt(() -> {
            bookmark(0);
            viewer.setSearchInputActive(true);

            viewer.showBookmarks();

            assertThat(viewer.isBookmarksView()).isTrue();
            assertThat(viewer.isSearchInputActive()).isFalse();
            assertThat(statusBar.inputState()).isEqualTo(InputState.BOOKMARKS);
        });
    }

    @Test
    void theMatchesTabWithoutAnActiveSearchSwitchesToTheEmptyMatchesViewAndAsksForASearch() throws Exception {
        onEdt(() -> {
            bookmark(0);
            viewer.showBookmarks();

            clickTab(ViewTabs.Tab.MATCHES);

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.MATCHES);
            assertThat(emptyMatches().rows().rowCount()).isZero();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_INPUT);
        });
    }

    @Test
    void theEmptyMatchesViewTellsThereIsNoSearchYet() throws Exception {
        onEdt(() -> {
            viewer.toggleMatches();

            assertThat(((AfterBoundaryLine) emptyMatches().getLine(0, false)).message()).startsWith("No search yet");
        });
    }

    @Test
    void escFromTheEmptyMatchesViewClosesThePromptThenLeavesToTheLogAsItWas() throws Exception {
        onEdt(() -> {
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();
            viewer.toggleMatches();

            viewer.setSearchInputActive(false);
            assertThat(viewer.isMatchesView()).isTrue();

            assertThat(viewer.backToLog()).isTrue();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(logBefore);
        });
    }

    @Test
    void submittingASearchFromTheEmptyMatchesViewShowsTheMatchesOfThatSearch() throws Exception {
        Searcher searcher = mock(Searcher.class);
        SearchSession started = new SearchSession("zzz", T0);
        when(searcher.getSession()).thenReturn(started);
        when(searchManager.search(any(String.class), any(Instant.class))).thenReturn(searcher);
        onEdt(() -> {
            bookmark(0);
            viewer.showBookmarks();
            clickTab(ViewTabs.Tab.MATCHES);
            session.set(started);

            viewer.executeSearch("zzz", false);

            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(((MatchesDataProvider) viewer.cursorManager().captureViewState().provider()).isPlaceholder())
                    .isFalse();
        });
    }

    @Test
    void aPlainSearchPromptDoesNotOpenTheMatchesView() throws Exception {
        Searcher searcher = mock(Searcher.class);
        SearchSession started = new SearchSession("zzz", T0);
        when(searcher.getSession()).thenReturn(started);
        when(searchManager.search(any(String.class), any(Instant.class))).thenReturn(searcher);
        onEdt(() -> {
            session.set(started);
            viewer.setSearchInputActive(true);

            viewer.executeSearch("zzz", false);

            assertThat(viewer.isMatchesView()).isFalse();
        });
    }

    @Test
    void theMatchesTabLeavesTheBookmarksViewWhileASearchIsActive() throws Exception {
        onEdt(() -> {
            startSearch(1);
            viewer.showBookmarks();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.BOOKMARKS);

            clickTab(ViewTabs.Tab.MATCHES);

            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.MATCHES);
        });
    }

    @Test
    void theEmptyHintFollowsTheKeyHintsPreferenceWhileTheViewIsOpen() throws Exception {
        onEdt(() -> {
            viewer.showBookmarks();
            assertThat(((AfterBoundaryLine) provider().getLine(0, false)).message()).contains("Press Ctrl-B");

            KeyMap.setPreferredFlavour(KeyFlavour.PAGER);
            try {
                assertThat(((AfterBoundaryLine) provider().getLine(0, false)).message()).contains("Press m");
            } finally {
                KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            }
        });
    }

    // === Show in log ===

    @Test
    void enterLandsOnTheEventAndItsRowOffsetInTheLogKeepingTheScreenRow() throws Exception {
        onEdt(() -> {
            bookmark(2, 4);
            viewer.showBookmarks();
            // Bookmark rows: event 2 is rows 0-1, event 4 is row 2. Row 1 is line 2 of event 2.
            viewer.cursorManager().ensureCursorAt(1);
            int screenRow = viewer.cursorManager().screenRowOf(1);

            viewer.showMatchInLog();

            assertThat(viewer.isBookmarksView()).isFalse();
            CursorManager.ViewState state = viewer.cursorManager().captureViewState();
            assertThat(state.provider()).isSameAs(logProvider);
            assertThat(state.navHighlightLine()).as("event 2 starts on log row 4, offset 1").isEqualTo(5);
            assertThat(state.pageStart()).isEqualTo(5 - screenRow);
        });
    }

    @Test
    void showingInTheLogFromBookmarksOpenedInMatchesLeavesBothViews() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            bookmark(3, 6);
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();
            firstVisible(3);
            viewer.toggleMatches();
            viewer.showBookmarks();
            viewer.cursorManager().ensureCursorAt(1); // event 6

            viewer.showMatchInLog();

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isFalse();
            CursorManager.ViewState state = viewer.cursorManager().captureViewState();
            assertThat(state.provider()).isSameAs(logProvider);
            assertThat(state.navHighlightLine()).isEqualTo(firstRowOf(T0.plusSeconds(60)));
            assertThat(statusBar.inputState()).isNotIn(InputState.MATCHES, InputState.BOOKMARKS);
            assertThat(logBefore.provider()).isSameAs(logProvider);
        });
    }

    // === Removal ===

    @Test
    void mRemovesTheBookmarkUnderTheCursorAndTheCursorMovesToTheNextEventThenThePreviousWhenLast()
            throws Exception {
        onEdt(() -> {
            bookmark(0, 2, 4);
            viewer.showBookmarks();
            // e0 rows 0-2, e2 rows 3-4, e4 row 5.
            viewer.cursorManager().ensureCursorAt(4); // the second row of event 2
            viewer.toggleBookmark();

            assertThat(viewer.bookmarks().size()).isEqualTo(2);
            assertThat(viewer.bookmarks().contains(hit(2))).isFalse();
            assertThat(statusBar.noticeText()).isEqualTo("Bookmark removed");
            assertThat(provider().rows().rowCount()).isEqualTo(4);
            assertThat(viewer.cursorManager().cursorLine()).as("event 4 moved up under the cursor").hasValue(3);
            assertThat(statusBar.searchPromptText()).startsWith("2 bookmarks");

            viewer.toggleBookmark(); // the last event

            assertThat(viewer.bookmarks().size()).isEqualTo(1);
            assertThat(viewer.cursorManager().cursorLine()).as("back to the previous event").hasValue(0);

            viewer.toggleBookmark();

            assertThat(viewer.bookmarks().size()).isZero();
            assertThat(viewer.isBookmarksView()).as("the empty view stays open").isTrue();
            assertThat(provider().rows().rowCount()).isZero();
            assertThat(provider().getLine(0, false)).isInstanceOfSatisfying(
                    se.grynna.pager.splunk.AfterBoundaryLine.class,
                    after -> assertThat(after.message())
                            .isEqualTo("No bookmarks yet. Press Ctrl-B on a line to bookmark it."));
            assertThat(statusBar.searchPromptText()).startsWith("0 bookmarks");
        });
    }

    @Test
    void aSelectionRemovesEveryEventItTouchesAndTheViewDoesNotJump() throws Exception {
        onEdt(() -> {
            bookmark(0, 2, 4, 6);
            viewer.showBookmarks();
            // e0 rows 0-2, e2 rows 3-4, e4 row 5, e6 row 6.
            viewer.cursorManager().ensureCursorAt(2);
            viewer.cursorManager().clearSelection();
            viewer.cursorManager().moveCursor(2, 1, true); // rows 2..4: events 0 and 2

            viewer.toggleBookmark();

            assertThat(viewer.bookmarks().size()).isEqualTo(2);
            assertThat(viewer.bookmarks().contains(hit(0))).isFalse();
            assertThat(viewer.bookmarks().contains(hit(2))).isFalse();
            assertThat(provider().rows().rowCount()).isEqualTo(2);
            assertThat(viewer.cursorManager().cursorLine()).as("the next remaining event").hasValue(0);
            assertThat(viewer.cursorManager().getMark()).isEmpty();
        });
    }

    @Test
    void theViewIsRefreshedWhenTheBookmarksChangeUnderIt() throws Exception {
        onEdt(() -> {
            bookmark(0, 4);
            viewer.showBookmarks();
            viewer.cursorManager().ensureCursorAt(3); // event 4
            SearchHit cursorEvent = hit(4);

            viewer.bookmarks().toggle(List.of(eventLine(1)));

            assertThat(provider().rows().eventCount()).isEqualTo(3);
            assertThat(provider().hitAt(viewer.cursorManager().cursorLine().orElseThrow())).isEqualTo(cursorEvent);
            assertThat(statusBar.searchPromptText()).startsWith("3 bookmarks");
        });
    }

    @Test
    void theEmptyViewOpensWithOneHintRowAndNoCrash() throws Exception {
        onEdt(() -> {
            viewer.showBookmarks();

            assertThat(viewer.isBookmarksView()).isTrue();
            assertThat(provider().rows().rowCount()).isZero();
            assertThat(statusBar.searchPromptText()).startsWith("0 bookmarks");
            viewer.toggleBookmark();
            assertThat(statusBar.noticeText()).isEqualTo("Place the cursor on a line to bookmark it");
        });
    }

    // === Logout and scope ===

    @Test
    void logoutWhileTheBookmarksViewIsOpenLeavesItAndClearsTheBookmarks() throws Exception {
        onEdt(() -> {
            bookmark(0, 2);
            viewer.showBookmarks();
            assertThat(viewer.isBookmarksView()).isTrue();

            var handleLogout = LogViewer.class.getDeclaredMethod("handleLogout");
            handleLogout.setAccessible(true);
            handleLogout.invoke(viewer);

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.bookmarks().size()).isZero();
            assertThat(statusBar.inputState()).isNotEqualTo(InputState.BOOKMARKS);
            assertThat(viewer.cursorManager().captureViewState().provider())
                    .isNotInstanceOf(BookmarksDataProvider.class);
        });
    }

    @Test
    void aScopeChangeInTheBookmarksViewReturnsToTheLogFirst() throws Exception {
        scope.set(Scope.of("web", Map.of()).withNarrow("x"));
        onEdt(() -> {
            bookmark(0);
            viewer.showBookmarks();

            viewer.removeScopeTerm("x");

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState().provider()).isSameAs(logProvider);
            assertThat(statusBar.inputState()).isNotEqualTo(InputState.BOOKMARKS);
        });
    }

    @Test
    void aScopeChangeInTheBookmarksViewOpenedInMatchesLeavesBothViews() throws Exception {
        scope.set(Scope.of("web", Map.of()).withNarrow("x"));
        onEdt(() -> {
            startSearch(1, 3);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.showBookmarks();

            viewer.removeScopeTerm("x");

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState().provider()).isSameAs(logProvider);
        });
    }

    @Test
    void aNewSearchFromTheBookmarksViewFirstGoesBackToTheViewItWasOpenedFrom() throws Exception {
        Searcher searcher = mock(Searcher.class);
        SearchSession started = new SearchSession("zzz", T0);
        when(searcher.getSession()).thenReturn(started);
        when(searchManager.search(any(String.class), any(Instant.class))).thenReturn(searcher);
        onEdt(() -> {
            bookmark(0);
            viewer.showBookmarks();

            viewer.executeSearch("zzz");

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState().provider()).isSameAs(logProvider);
        });
    }

    // === Copy ===

    @Test
    void ctrlCWithoutASelectionCopiesEveryBookmarkedEventWholeAndInOrder() throws Exception {
        onEdt(() -> {
            bookmark(4, 0);
            viewer.showBookmarks();
            viewer.cursorManager().clearSelection();
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));

        onEdt(() -> {
            List<String> expected = BookmarksDataProvider.wholeEventText(viewer.bookmarks().events());
            assertThat(expected).hasSize(4);
            assertThat(clipboard.text()).isPresent();
            assertThat(clipboard.text().get().lines().toList()).isEqualTo(expected);
            assertThat(expected.get(0)).endsWith("message 0");
            assertThat(expected.get(3)).endsWith("message 7");
            assertThat(statusBar.noticeText()).isEqualTo("Copied 2 bookmarks");
        });
    }

    @Test
    void ctrlCWithASelectionCopiesJustTheSelectionAsShown() throws Exception {
        onEdt(() -> {
            bookmark(0, 4);
            viewer.showBookmarks();
            viewer.cursorManager().ensureCursorAt(0);
            viewer.cursorManager().clearSelection();
            viewer.cursorManager().moveCursor(1, 0, true); // all of row 0
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));

        onEdt(() -> {
            assertThat(clipboard.text()).isPresent();
            assertThat(clipboard.text().get()).contains("message 0").doesNotContain("message 7");
            assertThat(statusBar.noticeText()).isNotEqualTo("Copied 2 bookmarks");
        });
    }

    @Test
    void ctrlCInTheEmptyViewHasNothingToCopy() throws Exception {
        onEdt(() -> viewer.showBookmarks());

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));

        onEdt(() -> {
            assertThat(clipboard.contents()).isNull();
            assertThat(statusBar.noticeText()).isEqualTo("No bookmarks to copy");
        });
    }

    @Test
    void copyAllButtonInTheChromeCopiesEveryBookmarkAndMarksThemSaved() throws Exception {
        onEdt(() -> {
            bookmark(0, 3);
            viewer.showBookmarks();
            JButton copyAll = find(statusBar, JButton.class).stream()
                    .filter(b -> "Copy all".equals(b.getText())).findFirst().orElseThrow();
            assertThat(copyAll.isEnabled()).isTrue();

            copyAll.doClick();

            assertThat(clipboard.text().orElseThrow().lines().toList())
                    .isEqualTo(BookmarksDataProvider.wholeEventText(viewer.bookmarks().events()));
            assertThat(statusBar.noticeText()).isEqualTo("Copied 2 bookmarks");
            assertThat(viewer.bookmarks().unsavedCount()).isZero();
        });
    }

    // === Keys through the real input map ===

    @Test
    void capitalMAndCtrlShiftBOpenTheViewAndEscClosesIt() throws Exception {
        fire(KeyStroke.getKeyStroke(Character.valueOf('M'), 0));
        onEdt(() -> assertThat(viewer.isBookmarksView()).isTrue());

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        onEdt(() -> assertThat(viewer.isBookmarksView()).isFalse());

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        onEdt(() -> assertThat(viewer.isBookmarksView()).isTrue());
    }

    @Test
    void f2TogglesTheBookmarksViewOnAndBackToTheLog() throws Exception {
        onEdt(() -> {
            bookmark(0);
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();

            viewer.toggleBookmarks();
            assertThat(viewer.isBookmarksView()).isTrue();
            assertThat(viewer.cursorManager().captureViewState().provider()).isInstanceOf(BookmarksDataProvider.class);

            viewer.toggleBookmarks();
            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(logBefore);
            assertThat(statusBar.inputState()).isNotEqualTo(InputState.BOOKMARKS);
        });
    }

    @Test
    void f2FromBookmarksOpenedInMatchesReturnsToTheMatchesViewAsItWas() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            bookmark(3);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.cursorManager().ensureCursorAt(2);
            CursorManager.ViewState matchesBefore = viewer.cursorManager().captureViewState();

            viewer.toggleBookmarks();
            assertThat(viewer.isBookmarksView()).isTrue();

            viewer.toggleBookmarks();
            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(matchesBefore);
        });
    }

    @Test
    void f2KeyOpensAndClosesTheBookmarksView() throws Exception {
        fire(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        onEdt(() -> assertThat(viewer.isBookmarksView()).isTrue());

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        onEdt(() -> assertThat(viewer.isBookmarksView()).isFalse());
    }

    @Test
    void enterAndMThroughTheKeyMapShowInLogAndRemove() throws Exception {
        onEdt(() -> bookmark(4, 6));
        fire(KeyStroke.getKeyStroke(Character.valueOf('M'), 0));
        fire(KeyStroke.getKeyStroke(Character.valueOf('m'), 0));
        onEdt(() -> {
            assertThat(viewer.bookmarks().size()).isEqualTo(1);
            assertThat(viewer.isBookmarksView()).isTrue();
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));

        onEdt(() -> {
            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState().navHighlightLine()).isEqualTo(firstRowOf(
                    T0.plusSeconds(60)));
        });
    }

    @Test
    void f7SwitchesCursorModeInTheBookmarksView() throws Exception {
        onEdt(() -> {
            bookmark(0);
            viewer.showBookmarks();
            assertThat(viewer.isCursorMode()).isTrue();
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));

        onEdt(() -> {
            assertThat(viewer.isCursorMode()).isFalse();
            assertThat(viewer.isBookmarksView()).isTrue();
        });
    }

    // === helpers ===

    private void clickTab(ViewTabs.Tab tab) {
        ViewTabs tabs = statusBar.viewTabs();
        tabs.setSize(tabs.getPreferredSize());
        tabs.dispatchEvent(new java.awt.event.MouseEvent(tabs, java.awt.event.MouseEvent.MOUSE_CLICKED, 0,
                InputEvent.BUTTON1_DOWN_MASK, tabs.tabBounds(tab).x + 2, 1, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
    }

    private BookmarksDataProvider provider() {
        return (BookmarksDataProvider) viewer.cursorManager().captureViewState().provider();
    }

    private SearchHit hitOfRow(int row) {
        return ((MatchesDataProvider) viewer.cursorManager().captureViewState().provider()).hitAt(row);
    }

    // === View tabs ===

    @Test
    void showLogFromMatchesLandsInTheLogAsItWas() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            firstVisible(3);
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();
            viewer.toggleMatches();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.MATCHES);

            viewer.showLog();

            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(logBefore);
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.LOG);
        });
    }

    @Test
    void showLogFromBookmarksOpenedInMatchesLeavesBothViews() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            bookmark(3);
            firstVisible(3);
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();
            viewer.toggleMatches();
            viewer.showBookmarks();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.BOOKMARKS);

            viewer.showLog();

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(logBefore);
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.LOG);
        });
    }

    @Test
    void showLogFromBookmarksOpenedInTheLogReturnsToTheLog() throws Exception {
        onEdt(() -> {
            bookmark(0, 2);
            CursorManager.ViewState logBefore = viewer.cursorManager().captureViewState();
            viewer.showBookmarks();

            viewer.showLog();

            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(logBefore);
        });
    }

    @Test
    void showLogInTheLogDoesNothing() throws Exception {
        onEdt(() -> {
            CursorManager.ViewState before = viewer.cursorManager().captureViewState();
            viewer.showLog();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(before);
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.LOG);
        });
    }

    @Test
    void theBookmarkCountReachesTheTabsOutsideTheBookmarksView() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            viewer.cursorManager().clearSelection();

            viewer.toggleBookmark();
            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.BOOKMARKS)).isEqualTo("BOOKMARKS 1");

            bookmark(5, 7);
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.BOOKMARKS)).isEqualTo("BOOKMARKS 3");

            viewer.cursorManager().ensureCursorAt(3);
            viewer.cursorManager().clearSelection();
            viewer.toggleBookmark();
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.BOOKMARKS)).isEqualTo("BOOKMARKS 2");

            viewer.bookmarks().clear();
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.BOOKMARKS)).isEqualTo("BOOKMARKS");
        });
    }

    @Test
    void addingABookmarkFlashesTheBookmarksTabAndRemovingOneDoesNot() throws Exception {
        onEdt(() -> {
            bookmark(1); // row 3
            assertThat(statusBar.viewTabs().isFlashing(ViewTabs.Tab.BOOKMARKS)).as("direct changes are quiet")
                    .isFalse();
            viewer.cursorManager().ensureCursorAt(3);
            viewer.cursorManager().clearSelection();

            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().contains(hit(1))).isFalse();
            assertThat(statusBar.viewTabs().isFlashing(ViewTabs.Tab.BOOKMARKS)).as("removed").isFalse();

            viewer.cursorManager().ensureCursorAt(3);
            viewer.toggleBookmark();
            assertThat(viewer.bookmarks().contains(hit(1))).isTrue();
            assertThat(statusBar.viewTabs().isFlashing(ViewTabs.Tab.BOOKMARKS)).as("added").isTrue();
        });
    }

    @Test
    void bookmarkingInTheMatchesViewFlashesTheBookmarksTab() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.cursorManager().clearSelection();

            viewer.toggleBookmark();

            assertThat(statusBar.viewTabs().isFlashing(ViewTabs.Tab.BOOKMARKS)).isTrue();
        });
    }

    @Test
    void theBookmarkCountReachesTheTabsInTheMatchesView() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.cursorManager().clearSelection();

            viewer.toggleBookmark();

            assertThat(viewer.isMatchesView()).isTrue();
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.BOOKMARKS)).isEqualTo("BOOKMARKS 1");
        });
    }

    @Test
    void theDetailsTabWithoutACursorLineSaysWhatToDo() throws Exception {
        onEdt(() -> {
            assertThat(viewer.cursorManager().cursorLine()).as("page mode: no cursor yet").isEmpty();

            viewer.openCursorDetails();

            assertThat(statusBar.noticeText()).isEqualTo("Place the cursor on a line to see its details");
        });
    }

    @Test
    void clickingTheLogTabLeavesTheMatchesView() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            firstVisible(3);
            viewer.toggleMatches();

            clickTab(ViewTabs.Tab.LOG);

            assertThat(viewer.isMatchesView()).isFalse();
        });
    }

    private MatchesDataProvider emptyMatches() {
        return (MatchesDataProvider) viewer.cursorManager().captureViewState().provider();
    }

    private void bookmark(int... events) {
        for (int event : events) {
            viewer.bookmarks().toggle(List.of(eventLine(event)));
        }
    }

    private void startSearch(int... matchEvents) {
        SearchSession active = new SearchSession("message", T0);
        for (int event : matchEvents) {
            SearchSessionTestSupport.addMatch(active, hit(event), eventLine(event));
        }
        SearchSessionTestSupport.complete(active);
        session.set(active);
        statusBar.setSearchActive(true, "message");
    }

    /** The first visible event anchors the Matches view; there is no painted page to derive it from. */
    private void firstVisible(int event) throws Exception {
        java.lang.reflect.Field anchor = LogViewer.class.getDeclaredField("firstVisibleSearchHit");
        anchor.setAccessible(true);
        anchor.set(viewer, hit(event));
    }

    private static SearchHit hit(int event) {
        return SearchHit.from(eventLine(event));
    }

    private static int firstRowOf(Instant time) {
        for (int row = 0; row < EVENT_OF_ROW.length; row++) {
            if (T0.plusSeconds(10L * EVENT_OF_ROW[row]).equals(time)) {
                return row;
            }
        }
        throw new IllegalArgumentException("no event at " + time);
    }

    /** The event-level line: every row of the event joined, as {@link EventLines#eventOf} builds it. */
    private static LoadedLogLine eventLine(int event) {
        List<String> messages = new ArrayList<>();
        int first = -1;
        for (int row = 0; row < EVENT_OF_ROW.length; row++) {
            if (EVENT_OF_ROW[row] == event) {
                messages.add("message " + row);
                if (first < 0) first = row;
            }
        }
        Instant time = T0.plusSeconds(10L * event);
        return new LoadedLogLine(MsgType.JSON, "seg", first, "cb" + event, "bkt", time, "raw", time, time,
                String.join("\n", messages), false, Level.INFO);
    }

    private static LoadedLogLine line(int row) {
        int event = row >= 0 && row < EVENT_OF_ROW.length ? EVENT_OF_ROW[row] : row;
        boolean continuation = row >= 0 && row < CONTINUATION.length && CONTINUATION[row];
        Instant time = T0.plusSeconds(10L * event);
        return new LoadedLogLine(MsgType.JSON, "seg", row, "cb" + event, "bkt", time, "raw", time, time,
                "message " + row, continuation, Level.INFO);
    }

    private void fire(KeyStroke stroke) throws Exception {
        onEdt(() -> {
            JComponent panel = find(viewer, JComponent.class).stream()
                    .filter(c -> c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke) != null).findFirst()
                    .orElseThrow(() -> new AssertionError("no component binds " + stroke));
            Object key = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
            panel.getActionMap().get(key)
                    .actionPerformed(new ActionEvent(panel, ActionEvent.ACTION_PERFORMED, "test"));
        });
        onEdt(() -> {});
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
