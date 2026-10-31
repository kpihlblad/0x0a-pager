package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Placeholder;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.Searcher;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.clip.InMemoryClipboard;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Narrowing to an activity and the row menu through the viewer, no window shown. Ten loaded rows (row 1 continues
 * event 0), one event per row otherwise:
 * <pre>
 * row  machine/process (term)  thread  request
 *  0   m1/p1 (p1)              t1      r1
 *  1   m1/p1 (p1)              t1      r1     (continuation)
 *  2   m1/p1 (p1)              t2      r1
 *  3   m1/p1 (p1)              t1      r2
 *  4   m1/p1 (p1)              t3      -
 *  5   m1/p1 (p1)              t1      r2
 *  6   m2/p2 (p2)              t1      r1
 *  7   m1/p1 (none)            t1      r3
 *  8   (no activity)
 *  9   m9/p9 (none)            -       -
 * </pre>
 */
class LogViewerNarrowTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int ROWS = 10;
    private static final Activity[] ACTIVITY = {a("m1", "p1", "t1", "r1", "p1"), a("m1", "p1", "t1", "r1", "p1"),
            a("m1", "p1", "t2", "r1", "p1"), a("m1", "p1", "t1", "r2", "p1"), a("m1", "p1", "t3", null, "p1"),
            a("m1", "p1", "t1", "r2", "p1"), a("m2", "p2", "t1", "r1", "p2"), a("m1", "p1", "t1", "r3", null),
            Activity.NONE, a("m9", "p9", null, null, null)};
    private static final boolean[] CONTINUATION = {false, true, false, false, false, false, false, false, false,
            false};
    private static final int[] EVENT_OF_ROW = {0, 0, 1, 2, 3, 4, 5, 6, 7, 8};

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
        when(logProvider.getStatistics())
                .thenReturn(new LogStatistics(LogStatistics.Status.LOADED, ROWS, T0, T0.plusSeconds(600)));
        when(logProvider.getLine(anyInt(), anyBoolean()))
                .thenAnswer(inv -> loaded((int) inv.getArgument(0))
                        ? line(inv.getArgument(0))
                        : Placeholder.from(inv.getArgument(0), ""));
        when(logProvider.peekLine(anyInt()))
                .thenAnswer(inv -> loaded((int) inv.getArgument(0))
                        ? Optional.of(line(inv.getArgument(0)))
                        : Optional.empty());
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
            viewer.cursorManager().setPageStart(0);
            viewer.cursorManager().setClipboard(clipboard);
            viewer.updateVisibleLines();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    // === a and A ===

    @Test
    void aFoldsTheProcessTermAndTheThreadIntoOneScopeChange() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);

            viewer.narrowToCursorActivity();

            assertThat(scopeSet().narrow()).containsExactly("p1", "t1");
            verify(searchManager).setScope(any());
        });
    }

    @Test
    void aLandsOnTheRowsEventHighlighted() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(5);

            viewer.narrowToCursorActivity();

            verify(logProvider).jump(SearchHit.from(line(5)));
            assertThat(viewer.cursorManager().captureViewState().navHighlightLine()).isEqualTo(5);
        });
    }

    @Test
    void aOnAContinuationRowLandsOnTheEvent() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(1);

            viewer.narrowToCursorActivity();

            assertThat(scopeSet().narrow()).containsExactly("p1", "t1");
            assertThat(viewer.cursorManager().captureViewState().navHighlightLine()).isEqualTo(0);
        });
    }

    @Test
    void termsAlreadyInTheScopeAreSkipped() throws Exception {
        scope.set(Scope.of("web", Map.of()).withNarrow("p1"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(0);

            viewer.narrowToCursorActivity();

            assertThat(scopeSet().narrow()).containsExactly("p1", "t1");
        });
    }

    @Test
    void aNoticeSaysWhenTheScopeHasAllTheTerms() throws Exception {
        scope.set(Scope.of("web", Map.of()).withNarrow("p1").withNarrow("t1"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(0);

            viewer.narrowToCursorActivity();

            assertThat(statusBar.noticeText()).isEqualTo("Already narrowed to this row's thread");
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void aTermAlreadyExcludedIsMovedToTheNarrowTerms() throws Exception {
        scope.set(Scope.of("web", Map.of()).withExclude("t1"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(0);

            viewer.narrowToCursorActivity();

            assertThat(scopeSet().narrow()).containsExactly("p1", "t1");
            assertThat(scopeSet().exclude()).isEmpty();
        });
    }

    @Test
    void aWithoutAProcessTermNarrowsToTheThreadAlone() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(7);

            viewer.narrowToCursorActivity();

            assertThat(scopeSet().narrow()).containsExactly("t1");
        });
    }

    @Test
    void aOnAProcessWithoutAThreadOrATermSaysItCannotNarrow() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(9);

            viewer.narrowToCursorActivity();

            assertThat(statusBar.noticeText()).isEqualTo("Can't narrow to this row's process");
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void aOnARowWithoutActivityShowsANotice() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(8);

            viewer.narrowToCursorActivity();

            assertThat(statusBar.noticeText()).isEqualTo("No activity found on this row");
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void aWithoutACursorAsksForOne() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().hideCursor();

            viewer.narrowToCursorActivity();
            assertThat(statusBar.noticeText()).isEqualTo("Place the cursor on a line to narrow to its activity");

            viewer.narrowToCursorRequest();
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void capitalANarrowsToTheRequestAcrossProcesses() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);

            viewer.narrowToCursorRequest();

            assertThat(scopeSet().narrow()).containsExactly("r1");
            verify(logProvider).jump(SearchHit.from(line(6)));
        });
    }

    @Test
    void capitalAOnARowWithoutARequestShowsANotice() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(4);

            viewer.narrowToCursorRequest();

            assertThat(statusBar.noticeText()).isEqualTo("This row has no request id");
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void narrowToActivityTakesAnExplicitLevelAndRow() throws Exception {
        onEdt(() -> {
            viewer.narrowToActivity(Activity.Level.PROCESS, 6);
            assertThat(scopeSet().narrow()).containsExactly("p2");
        });
        onEdt(() -> {
            viewer.narrowToActivity(Activity.Level.PROCESS, 9);
            assertThat(statusBar.noticeText()).isEqualTo("Can't narrow to this row's process");
            viewer.narrowToActivity(Activity.Level.THREAD, 99);
            assertThat(statusBar.noticeText()).as("not a loaded row").isEqualTo("Can't narrow to this row's thread");
        });
    }

    // === the views and bookmarks ===

    @Test
    void aBookmarkOutsideTheNewScopeAsksFirstAndYesNarrows() throws Exception {
        onEdt(() -> {
            viewer.bookmarks().toggle(List.of(eventLine(6)));
            viewer.cursorManager().ensureCursorAt(0);

            viewer.narrowToCursorActivity();

            verify(searchManager, never()).setScope(any());
            assertThat(viewer.isYesNoPromptShowing()).isTrue();

            viewer.actionAcceptBoundaryExpansion(true);
            assertThat(scopeSet().narrow()).containsExactly("p1", "t1");
        });
    }

    @Test
    void answeringNoLeavesTheScopeAlone() throws Exception {
        onEdt(() -> {
            viewer.bookmarks().toggle(List.of(eventLine(6)));
            viewer.cursorManager().ensureCursorAt(0);
            viewer.narrowToCursorActivity();

            viewer.actionAcceptBoundaryExpansion(false);

            verify(searchManager, never()).setScope(any());
            assertThat(statusBar.noticeText()).isEqualTo("Scope unchanged");
        });
    }

    @Test
    void fromTheMatchesViewTheNarrowLeavesItForTheLog() throws Exception {
        onEdt(() -> {
            startSearch(0, 2, 3, 6);
            viewer.toggleMatches();
            assertThat(viewer.isMatchesView()).isTrue();
            viewer.cursorManager().ensureCursorAt(2); // the match of row 3

            viewer.narrowToCursorActivity();

            assertThat(scopeSet().narrow()).containsExactly("p1", "t1");
            assertThat(viewer.isMatchesView()).isFalse();
            verify(logProvider).jump(SearchHit.from(line(3)));
        });
    }

    @Test
    void fromTheBookmarksViewTheNarrowLeavesItForTheLog() throws Exception {
        onEdt(() -> {
            viewer.bookmarks().toggle(List.of(eventLine(0), eventLine(3)));
            viewer.showBookmarks();
            assertThat(viewer.isBookmarksView()).isTrue();
            viewer.cursorManager().ensureCursorAt(1); // the bookmark of row 3

            viewer.narrowToCursorRequest();
            assertThat(viewer.isYesNoPromptShowing()).as("the bookmarks fall outside the new scope").isTrue();
            viewer.actionAcceptBoundaryExpansion(true);

            assertThat(scopeSet().narrow()).containsExactly("r2");
            assertThat(viewer.isBookmarksView()).isFalse();
        });
    }

    // === icon click, keys, menus ===

    @Test
    void aClickOnTheIconOpensTheActivityPopupInsteadOfNarrowingDirectly() throws Exception {
        onEdt(() -> {
            viewer.charSize = new LogViewer.CharacterSize(8, 16, 12, 4);
            JComponent panel = viewPanel();

            click(panel, 2, 3 * 16 + 4);
            viewer.showActivityMenu(2, 3 * 16 + 4); // headless: built, not shown, no exception

            verify(searchManager, never()).setScope(any());
            assertThat(viewer.cursorManager().cursorLine()).as("the press put the cursor on the row").hasValue(3);
        });
    }

    @Test
    void theActivityPopupListsNarrowAndExcludePerLevelAndItsItemsCallTheHandlers() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);

            JPopupMenu menu = viewer.activityMenu();

            assertThat(find(menu, JButton.class).stream().map(JButton::getToolTipText).toList()).containsExactly(
                    "Narrow to thread t1 (a)", "Exclude thread t1", "Narrow to process p1", "Exclude process p1",
                    "Narrow to request r2 (A)", "Exclude request r2");

            button(menu, "Narrow to process p1").doClick();

            assertThat(scopeSet().narrow()).containsExactly("p1");
        });
    }

    @Test
    void theActivityPopupExcludeItemFoldsTheLevelsTermsAsExcludeTerms() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);

            button(viewer.activityMenu(), "Exclude thread t1").doClick();

            assertThat(scopeSet().exclude()).containsExactly("p1", "t1");
        });
    }

    @Test
    void theActivityPopupHasOneDisabledItemWithoutActivityAndIsNullWithoutACursor() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(8);
            JPopupMenu menu = viewer.activityMenu();

            assertThat(texts(menu)).containsExactly("No activity found on this row");
            assertThat(find(menu, JMenuItem.class).getFirst().isEnabled()).isFalse();

            viewer.cursorManager().hideCursor();
            assertThat(viewer.activityMenu()).isNull();
        });
    }

    @Test
    void excludeFromActivityTakesAnExplicitLevelAndRow() throws Exception {
        onEdt(() -> {
            viewer.excludeFromActivity(Activity.Level.REQUEST, 3);
            assertThat(scopeSet().exclude()).containsExactly("r2");
        });
        onEdt(() -> {
            viewer.excludeFromActivity(Activity.Level.THREAD, 9);
            assertThat(statusBar.noticeText()).isEqualTo("Can't exclude from this row's thread");
            viewer.excludeFromActivity(Activity.Level.THREAD, 99);
            assertThat(statusBar.noticeText()).as("not a loaded row").isEqualTo("Can't exclude from this row's thread");
        });
    }

    @Test
    void excludeFromActivitySaysWhenTheScopeHasAllTheTerms() throws Exception {
        scope.set(Scope.of("web", Map.of()).withExclude("r2"));
        onEdt(() -> {
            viewer.excludeFromActivity(Activity.Level.REQUEST, 3);

            assertThat(statusBar.noticeText()).isEqualTo("Already excluded this row's request");
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void aClickOnTheTextOnlyMovesTheCursor() throws Exception {
        onEdt(() -> {
            viewer.charSize = new LogViewer.CharacterSize(8, 16, 12, 4);

            click(viewPanel(), LineRenderer.iconSpaceWidth(16) + 30, 3 * 16 + 4);

            assertThat(viewer.cursorManager().cursorLine()).hasValue(3);
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void theKeysNarrow() throws Exception {
        onEdt(() -> viewer.cursorManager().ensureCursorAt(0));

        fire(KeyStroke.getKeyStroke(Character.valueOf('A'), 0));

        onEdt(() -> assertThat(scopeSet().narrow()).containsExactly("r1"));
    }

    @Test
    void theHamburgerMenuNarrowsToTheActivity() throws Exception {
        onEdt(() -> {
            JButton hamburger = find(statusBar, JButton.class).stream().filter(b -> "☰".equals(b.getText()))
                    .findFirst().orElseThrow();
            JPopupMenu popup = hamburger.getComponentPopupMenu();
            for (var listener : popup.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(popup));
            }
            JMenuItem item = menuItem(popup, "Narrow to activity (a)");
            assertThat(item.isEnabled()).isTrue();
            viewer.cursorManager().ensureCursorAt(3);

            item.doClick();

            assertThat(scopeSet().narrow()).containsExactly("p1", "t1");
        });
    }

    @Test
    void theRowMenuListsTheLevelsOfTheCursorRowAndItsKeys() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);

            JPopupMenu menu = viewer.rowContextMenu();

            assertThat(find(menu, JButton.class).stream().map(JButton::getToolTipText).toList()).containsExactly(
                    "Narrow to thread t1 (a)", "Exclude thread t1", "Narrow to process p1", "Exclude process p1",
                    "Narrow to request r2 (A)", "Exclude request r2");
            assertThat(texts(menu)).containsExactly("Bookmark (Ctrl-B)", "Copy (Ctrl-C)", "Copy as… (Ctrl-Shift-C)",
                    "Details (Enter)");
        });
    }

    @Test
    void theRowMenuNarrowsToTheSelectedTextAlongsideTheActivityTerms() throws Exception {
        onEdt(() -> {
            selectMessage(3);
            JPopupMenu menu = viewer.rowContextMenu();

            assertThat(find(menu, JButton.class).stream().map(JButton::getToolTipText))
                    .contains("Narrow to selection \"message\"", "Exclude selection \"message\"");
            assertThat(texts(menu)).contains("Search for selection");

            button(menu, "Narrow to selection \"message\"").doClick();

            assertThat(scopeSet().narrow()).containsExactly("message");
            verify(logProvider).jump(SearchHit.from(line(3)));
        });
    }

    @Test
    void theRowMenuSearchesForTheSelectedText() throws Exception {
        onEdt(() -> {
            SearchSession searching = new SearchSession("message", line(3).getSplunkTime());
            Searcher searcher = mock(Searcher.class);
            when(searcher.getSession()).thenReturn(searching);
            when(searchManager.search(any(String.class), any(Instant.class))).thenAnswer(invocation -> {
                session.set(searching);
                return searcher;
            });
            selectMessage(3);

            menuItem(viewer.rowContextMenu(), "Search for selection").doClick();

            verify(searchManager).search("message", line(3).getSplunkTime());
            verify(searcher).start();
        });
    }

    @Test
    void theSelectionActionBarExcludesTheSelectedText() throws Exception {
        onEdt(() -> {
            selectMessage(3);
            JButton exclude = find(statusBar, JButton.class).stream().filter(b -> b.getText().equals("− Exclude"))
                    .findFirst().orElseThrow();
            assertThat(exclude.isVisible()).isTrue();

            exclude.doClick();

            assertThat(scopeSet().exclude()).containsExactly("message");
            assertThat(exclude.isVisible()).as("the scope change clears the selection").isFalse();
        });
    }

    @Test
    void theRowMenuHasNoLevelsForARowWithoutActivityAndNoMenuWithoutACursor() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(8);
            assertThat(texts(viewer.rowContextMenu())).first().isEqualTo("Narrow to activity (no activity found)");
            assertThat(find(viewer.rowContextMenu(), JMenuItem.class).getFirst().isEnabled()).isFalse();

            viewer.cursorManager().hideCursor();
            assertThat(viewer.rowContextMenu()).isNull();
        });
    }

    @Test
    void theRowMenuItemsCallTheHandlers() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);

            button(viewer.rowContextMenu(), "Narrow to request r2 (A)").doClick();
            assertThat(scopeSet().narrow()).containsExactly("r2");

            viewer.cursorManager().ensureCursorAt(3);
            menuItem(viewer.rowContextMenu(), "Bookmark (Ctrl-B)").doClick();
            assertThat(viewer.bookmarks().contains(SearchHit.from(line(3)))).isTrue();

            viewer.cursorManager().ensureCursorAt(3);
            assertThat(texts(viewer.rowContextMenu())).contains("Remove bookmark (Ctrl-B)");
            menuItem(viewer.rowContextMenu(), "Remove bookmark (Ctrl-B)").doClick();
            assertThat(viewer.bookmarks().size()).isZero();
        });
    }

    @Test
    void theRowMenuExcludeItemFoldsTheLevelTermsAsExcludeTerms() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);

            button(viewer.rowContextMenu(), "Exclude request r2").doClick();

            assertThat(scopeSet().exclude()).containsExactly("r2");
        });
    }

    @Test
    void copyFromTheRowMenuTakesTheCursorLineWithoutASelection() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            viewer.cursorManager().clearSelection();

            menuItem(viewer.rowContextMenu(), "Copy (Ctrl-C)").doClick();

            assertThat(clipboard.contents()).isNotNull();
            var flavor = java.util.Arrays.stream(clipboard.contents().getTransferDataFlavors())
                    .filter(f -> f.getRepresentationClass() == String.class).findFirst().orElseThrow();
            assertThat(clipboard.contents().getTransferData(flavor).toString()).contains("message 3");
        });
    }

    @Test
    void theRowMenuOffersNarrowAndExcludeOnlyWhileASearchHasMatches() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            assertThat(texts(viewer.rowContextMenu())).noneMatch(t -> t.startsWith("Narrow to matches"));

            startSearch(0, 3);

            assertThat(texts(viewer.rowContextMenu())).contains("Narrow to matches (+)", "Exclude matches (-)");
        });
    }

    @Test
    void theRowMenuKeyWithoutACursorSaysSo() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().hideCursor();

            viewer.showRowContextMenuAtCursor();

            assertThat(statusBar.noticeText()).isEqualTo("Place the cursor on a line to open its menu");
        });
    }

    // === tooltip ===

    @Test
    void theIconTooltipShowsTheRowsActivityOnlyOverTheIcon() throws Exception {
        onEdt(() -> {
            viewer.charSize = new LogViewer.CharacterSize(8, 16, 12, 4);
            int iconWidth = LineRenderer.iconSpaceWidth(16);

            String tooltip = viewer.activityTooltipAt(2, 3 * 16 + 4);
            assertThat(tooltip).contains("machine").contains("m1").contains("thread").contains("t1")
                    .contains("request").contains("r2");

            assertThat(viewer.activityTooltipAt(iconWidth, 3 * 16 + 4)).as("right of the icon").isNull();
            assertThat(viewer.activityTooltipAt(2, 8 * 16 + 4)).isEqualTo(ActivityTooltip.text(Activity.NONE));
            assertThat(viewer.activityTooltipAt(2, 20 * 16 + 4)).as("a placeholder row").isNull();
            assertThat(viewer.activityTooltipAt(2, 5000)).as("below the rows").isNull();
            assertThat(viewer.activityTooltipAt(-1, 4)).isNull();
        });
    }

    @Test
    void theTimeColumnTooltipShowsTheFullTimestampOnlyOverTheTimeColumn() throws Exception {
        onEdt(() -> {
            viewer.charSize = new LogViewer.CharacterSize(8, 16, 12, 4);
            int textStartX = LineRenderer.iconSpaceWidth(16);
            int columnEndX = textStartX + DecoratedLogLine.getPreambleLength() * 8;

            String tooltip = viewer.timeColumnTooltipAt(textStartX + 2, 3 * 16 + 4);
            assertThat(tooltip).isEqualTo(TimeTooltip.text(line(3).getDisplayTime()));
            assertThat(tooltip).contains(DateTimeFormatterManager.formatFullTimestamp(line(3).getDisplayTime(),
                    DateTimeFormatterManager.zoneId()));

            assertThat(viewer.timeColumnTooltipAt(columnEndX, 3 * 16 + 4)).as("right of the time column").isNull();
            assertThat(viewer.timeColumnTooltipAt(textStartX + 2, 1 * 16 + 4)).as("a continuation row").isNull();
            assertThat(viewer.timeColumnTooltipAt(textStartX + 2, 20 * 16 + 4)).as("a placeholder row").isNull();
            assertThat(viewer.timeColumnTooltipAt(textStartX + 2, 5000)).as("below the rows").isNull();
            assertThat(viewer.timeColumnTooltipAt(2, 3 * 16 + 4)).as("over the icon").isNull();
            assertThat(viewer.timeColumnTooltipAt(-1, 3 * 16 + 4)).isNull();
        });
    }

    @Test
    void theViewPanelTooltipIsTheActivityOnTheIconAndTheTimeOverTheTimeColumn() throws Exception {
        onEdt(() -> {
            viewer.charSize = new LogViewer.CharacterSize(8, 16, 12, 4);
            int textStartX = LineRenderer.iconSpaceWidth(16);
            JComponent panel = viewPanel();

            assertThat(panel.getToolTipText(new MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0L, 0, 2, 3 * 16 + 4, 0,
                    false))).isEqualTo(ActivityTooltip.text(line(3).resolvedActivity()));
            assertThat(panel.getToolTipText(new MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0L, 0, textStartX + 2,
                    3 * 16 + 4, 0, false))).isEqualTo(TimeTooltip.text(line(3).getDisplayTime()));
        });
    }

    // === helpers ===

    /** The scope the viewer asked the manager to set (the only call expected). */
    private Scope scopeSet() {
        ArgumentCaptor<Scope> captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        return captor.getValue();
    }

    private static List<String> texts(JPopupMenu menu) {
        return java.util.Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance)
                .filter(c -> !(c instanceof ActivityRows.Row)).map(c -> ((JMenuItem) c).getText()).toList();
    }

    private static JMenuItem menuItem(JPopupMenu popup, String text) {
        return java.util.Arrays.stream(popup.getComponents()).filter(JMenuItem.class::isInstance)
                .map(JMenuItem.class::cast).filter(item -> text.equals(item.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no menu item " + text + " in " + texts(popup)));
    }

    private static JButton button(Container root, String tooltip) {
        return find(root, JButton.class).stream().filter(b -> tooltip.equals(b.getToolTipText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + tooltip));
    }

    private void selectMessage(int row) throws Exception {
        int start = DecoratedLogLine.getPreambleLength();
        viewer.cursorManager().setCursorFromClick(row, start, false);
        viewer.cursorManager().setCursorFromClick(row, start + "message".length(), true);
        assertThat(viewer.cursorManager().singleRowSelectionText()).contains("message");
    }

    /** A finished search whose matches are the events of {@code matchRows}. */
    private void startSearch(int... matchRows) {
        SearchSession active = new SearchSession("message", T0);
        for (int row : matchRows) {
            SearchSessionTestSupport.addMatch(active, SearchHit.from(eventLine(row)), eventLine(row));
        }
        SearchSessionTestSupport.complete(active);
        session.set(active);
        statusBar.setSearchActive(true, "message");
    }

    private JComponent viewPanel() {
        return find(viewer, JComponent.class).stream().filter(c -> c.getMouseListeners().length > 0
                && c.getClass().getSimpleName().equals("ViewPanel")).findFirst().orElseThrow();
    }

    private static void click(JComponent panel, int x, int y) {
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            MouseEvent event = new MouseEvent(panel, id, 0L, id == MouseEvent.MOUSE_RELEASED
                    ? 0
                    : MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1);
            for (var listener : panel.getMouseListeners()) {
                switch (id) {
                    case MouseEvent.MOUSE_PRESSED -> listener.mousePressed(event);
                    case MouseEvent.MOUSE_RELEASED -> listener.mouseReleased(event);
                    default -> listener.mouseClicked(event);
                }
            }
        }
    }

    private static Activity a(String machine, String process, String thread, String request, String processTerm) {
        return new Activity(machine, process, thread, request, null, processTerm);
    }

    private static boolean loaded(int row) {
        return row >= 0 && row < ROWS;
    }

    private static int firstRowOf(Instant time) {
        for (int row = 0; row < ROWS; row++) {
            if (T0.plusSeconds(10L * EVENT_OF_ROW[row]).equals(time)) {
                return row;
            }
        }
        throw new IllegalArgumentException("no event at " + time);
    }

    private static LoadedLogLine line(int row) {
        Instant time = T0.plusSeconds(10L * EVENT_OF_ROW[row]);
        LoadedLogLine line = new LoadedLogLine(MsgType.JSON, "seg", row, "cb" + EVENT_OF_ROW[row], "bkt", time,
                "raw", time, time, "message " + row, CONTINUATION[row], Level.INFO);
        line.setActivity(ACTIVITY[row]);
        return line;
    }

    /** The event-level line of the event that row {@code row} belongs to, as the Matches and Bookmarks views hold it. */
    private static LoadedLogLine eventLine(int row) {
        int first = row;
        while (CONTINUATION[first]) {
            first--;
        }
        return line(first);
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
