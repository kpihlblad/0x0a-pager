package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.AfterBoundaryLine;
import se.grynna.pager.splunk.BeforeBoundaryLine;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.LogEventMapper;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.Placeholder;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.clip.InMemoryClipboard;
import se.grynna.pager.ui.details.DetailsPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * The inline event details through the viewer, no window shown. The log has twelve rows of nine events: event 0 is
 * rows 0-2, event 2 is rows 4-5, every other event is one row. Event 8 (row 11) is a raw event, the rest are JSON
 * with the fields {@code level}, {@code msg} and {@code n} (the event number). Rows below 0 and from 12 are
 * boundaries.
 */
class LogViewerDetailsTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int[] EVENT_OF_ROW = {0, 0, 0, 1, 2, 2, 3, 4, 5, 6, 7, 8};
    private static final boolean[] CONTINUATION = {false, true, true, false, false, true, false, false, false, false,
            false, false};
    private static final int RAW_EVENT = 8;
    private static final KeyStroke ESC = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    private static final KeyStroke ENTER = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
    private static final KeyStroke UP = KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0);
    private static final KeyStroke DOWN = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0);
    private static final KeyStroke F3 = KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0);
    private static final KeyStroke SHIFT_F3 = KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK);
    private static final KeyStroke ALT_UP = KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK);
    private static final KeyStroke CTRL_C = KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK);

    private LogViewer viewer;
    private StatusBar statusBar;
    private SplunkLogDataProvider logProvider;
    private SearchManager searchManager;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();
    private final AtomicReference<Scope> scope = new AtomicReference<>(Scope.of("web", Map.of()));
    private final AtomicReference<List<String>> messageFields = new AtomicReference<>(
            LogEventMapper.DEFAULT_MESSAGE_FIELDS);
    private final Set<Integer> loadingRows = new HashSet<>();
    private final InMemoryClipboard clipboard = new InMemoryClipboard();

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        logProvider = mock(SplunkLogDataProvider.class);
        Boundary before = new Boundary(BoundaryType.BEFORE, T0);
        Boundary after = new Boundary(BoundaryType.AFTER, T0.plusSeconds(600));
        when(logProvider.getBeforeBoundary()).thenReturn(before);
        when(logProvider.getAfterBoundary()).thenReturn(after);
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T0.plusSeconds(600)));
        when(logProvider.getStatistics()).thenReturn(new LogStatistics(LogStatistics.Status.LOADED,
                EVENT_OF_ROW.length, T0, T0.plusSeconds(600)));
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> {
            int row = inv.getArgument(0);
            if (row < 0) {
                return new BeforeBoundaryLine(row, before, "Start");
            }
            if (row >= EVENT_OF_ROW.length) {
                return new AfterBoundaryLine(row, after, "End");
            }
            return loadingRows.contains(row) ? new Placeholder(row, "Loading") : line(row);
        });
        when(logProvider.peekLine(anyInt())).thenAnswer(inv -> {
            int row = inv.getArgument(0);
            boolean loaded = row >= 0 && row < EVENT_OF_ROW.length && !loadingRows.contains(row);
            return loaded ? Optional.<LogLine>of(line(row)) : Optional.<LogLine>empty();
        });
        when(logProvider.jump(any(SearchHit.class)))
                .thenAnswer(inv -> firstRowOf(((SearchHit) inv.getArgument(0)).splunkTime()));

        searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(logProvider);
        when(searchManager.scope()).thenAnswer(inv -> scope.get());
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(T0.plusSeconds(3600));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(searchManager.storeMessageFields(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(searchManager.messageFields()).thenAnswer(inv -> messageFields.get());
        when(searchManager.setMessageFields(any())).thenAnswer(inv -> {
            List<String> fields = inv.getArgument(0);
            messageFields.set(fields == null ? LogEventMapper.DEFAULT_MESSAGE_FIELDS : fields);
            return true;
        });

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

    // === Open and close ===

    @Test
    void openingFromTheLogOverlaysThePanelWithTheCursorEventAndLeavesTheViewAsItWas() throws Exception {
        int windows = Window.getWindows().length;
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(5); // the continuation row of event 2
            CursorManager.ViewState before = viewer.cursorManager().captureViewState();

            viewer.openLogLineDetails(line(5));

            assertThat(viewer.isDetailsOpen()).isTrue();
            DetailsPanel panel = viewer.detailsPanel();
            assertThat(panel.getParent()).isInstanceOf(JLayeredPane.class);
            assertThat(panel.getBounds()).isEqualTo(new Rectangle(0, 0, viewer.getWidth(), panel.getHeight()));
            assertThat(panel.details().field("n"))
                    .hasValueSatisfying(field -> assertThat(field.value()).isEqualTo("2"));
            assertThat(panel.positionLabel()).isEqualTo("row 4");
            assertThat(panel.details().message()).contains("message 4").contains("message 5");
            assertThat(viewer.cursorManager().captureViewState()).as("the view underneath").isEqualTo(before);
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.DETAILS);
            assertThat(statusBar.inputState()).isEqualTo(InputState.DETAILS);
            assertThat(statusBar.searchPromptText())
                    .startsWith("Details · ↑↓ field · Alt-Up/Alt-Down prev/next · + narrow")
                    .contains("f message").endsWith("Esc back");
            assertThat(visibleButton("◀ Prev")).isTrue();
            assertThat(visibleButton("Next ▶")).isTrue();
        });
        assertThat(Window.getWindows()).as("no dialog or window").hasSize(windows);
    }

    @Test
    void escClosesTheDetailsAndTheViewIsExactlyAsItWas() throws Exception {
        CursorManager.ViewState[] before = new CursorManager.ViewState[1];
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            before[0] = viewer.cursorManager().captureViewState();
            viewer.openLogLineDetails(line(6));
        });

        fire(ESC);

        onEdt(() -> {
            assertThat(viewer.isDetailsOpen()).isFalse();
            assertThat(viewer.detailsPanel()).isNull();
            assertThat(find(viewer, DetailsPanel.class)).isEmpty();
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(before[0]);
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.LOG);
            assertThat(statusBar.inputState()).isNotEqualTo(InputState.DETAILS);
            assertThat(visibleButton("◀ Prev")).isFalse();
        });
    }

    @Test
    void enterOpensTheDetailsAndEnterAgainClosesThem() throws Exception {
        onEdt(() -> viewer.cursorManager().ensureCursorAt(3));

        fire(ENTER);
        onEdt(() -> assertThat(viewer.isDetailsOpen()).isTrue());

        fire(ENTER);
        onEdt(() -> assertThat(viewer.isDetailsOpen()).isFalse());
    }

    @Test
    void theDetailsTabClosesThemAndTheTabsStaysUsable() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            viewer.openLogLineDetails(line(3));
            viewer.openCursorDetails();
            assertThat(viewer.isDetailsOpen()).isFalse();

            viewer.openCursorDetails();
            assertThat(viewer.isDetailsOpen()).isTrue();
        });
    }

    @Test
    void withoutACursorLineANoticeSaysSo() throws Exception {
        onEdt(() -> {
            viewer.openCursorDetails();

            assertThat(viewer.isDetailsOpen()).isFalse();
            assertThat(statusBar.noticeText()).isEqualTo("Place the cursor on a line to see its details");
        });
    }

    @Test
    void openingAgainJustRefreshes() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            viewer.openLogLineDetails(line(3));
            DetailsPanel panel = viewer.detailsPanel();
            panel.select(1);

            viewer.openLogLineDetails(line(3));

            assertThat(viewer.detailsPanel()).isSameAs(panel);
            assertThat(panel.selectedIndex()).isEqualTo(1);
            assertThat(find(viewer, DetailsPanel.class)).hasSize(1);
        });
    }

    @Test
    void openFromTheMatchesAndBookmarksViewsLabelsTheirPositionAndEscClosesOnlyTheDetails() throws Exception {
        onEdt(() -> {
            startSearch(1, 3, 5, 7);
            bookmark(3, 7);
            firstVisible(3);
            viewer.toggleMatches();
            assertThat(viewer.isMatchesView()).isTrue();
            viewer.cursorManager().ensureCursorAt(matchRow(1)); // event 3
            CursorManager.ViewState before = viewer.cursorManager().captureViewState();

            viewer.openLogLineDetails(viewer.cursorManager().cursorLogLine().orElseThrow());

            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("match 2 of 4");
            assertThat(viewer.detailsPanel().details().field("n").orElseThrow().value()).isEqualTo("3");
            assertThat(viewer.cursorManager().captureViewState()).isEqualTo(before);
            assertThat(viewer.isMatchesView()).isTrue();
        });

        fire(ESC);

        onEdt(() -> {
            assertThat(viewer.isDetailsOpen()).isFalse();
            assertThat(viewer.isMatchesView()).as("Esc closed only the details").isTrue();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.MATCHES);

            viewer.showBookmarks();
            viewer.cursorManager().ensureCursorAt(0);
            viewer.openLogLineDetails(viewer.cursorManager().cursorLogLine().orElseThrow());

            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("bookmark 1 of 2");
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.DETAILS);
            viewer.closeDetails();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.BOOKMARKS);
            assertThat(viewer.isBookmarksView()).isTrue();
        });
    }

    @Test
    void escAnswersAnOpenQuestionBeforeItClosesTheDetails() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(3);
            viewer.openLogLineDetails(line(3));
            viewer.requestBoundaryExpansion(
                    new BoundaryRequest(BoundaryType.BEFORE, T0, (time, accepted, boundary) -> {}));
        });

        fire(ESC);
        onEdt(() -> assertThat(viewer.isDetailsOpen()).as("the question went first").isTrue());

        fire(ESC);
        onEdt(() -> assertThat(viewer.isDetailsOpen()).isFalse());
    }

    // === Keys ===

    @Test
    void upAndDownMoveTheFieldSelectionNotTheLogCursor() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            assertThat(viewer.detailsPanel().selectedIndex()).isZero();
        });

        fire(DOWN);
        fire(DOWN);
        fire(UP);

        onEdt(() -> {
            assertThat(viewer.detailsPanel().selectedIndex()).isEqualTo(1);
            assertThat(viewer.cursorManager().cursorLine()).hasValue(6);
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK));
        onEdt(() -> {
            assertThat(viewer.detailsPanel().selectedIndex()).as("8 fields, clamped to the last").isEqualTo(
                    viewer.detailsPanel().details().fields().size() - 1);
            assertThat(viewer.cursorManager().cursorLine()).hasValue(6);
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0));
        onEdt(() -> assertThat(viewer.detailsPanel().selectedIndex()).isZero());
        fire(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0));
        onEdt(() -> assertThat(viewer.detailsPanel().selectedIndex())
                .isEqualTo(viewer.detailsPanel().details().fields().size() - 1));
    }

    @Test
    void theViewKeysDoNothingWhileTheDetailsAreOpen() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            bookmark(3);
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
        });

        fire(KeyStroke.getKeyStroke(Character.valueOf('a'), 0));
        fire(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0));
        fire(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        fire(KeyStroke.getKeyStroke(Character.valueOf('/'), 0));
        fire(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
        fire(KeyStroke.getKeyStroke(Character.valueOf('M'), 0));

        onEdt(() -> {
            assertThat(viewer.isDetailsOpen()).isTrue();
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.isBookmarksView()).isFalse();
            assertThat(statusBar.isSearchInputActive()).isFalse();
            assertThat(viewer.cursorManager().cursorLine()).hasValue(6);
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void mAndCtrlBToggleTheBookmarkOfTheShownEventWithoutMovingTheCursor() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(5);
            viewer.openLogLineDetails(line(5));
        });

        fire(KeyStroke.getKeyStroke(Character.valueOf('m'), 0));

        onEdt(() -> {
            assertThat(viewer.bookmarks().events()).hasSize(1);
            assertThat(SearchHit.from(viewer.bookmarks().events().getFirst())).isEqualTo(hit(2));
            assertThat(statusBar.noticeText()).startsWith("Bookmarked");
            assertThat(viewer.cursorManager().cursorLine()).hasValue(5);
            assertThat(viewer.isDetailsOpen()).isTrue();
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));

        onEdt(() -> {
            assertThat(viewer.bookmarks().events()).isEmpty();
            assertThat(statusBar.noticeText()).isEqualTo("Bookmark removed");
        });
    }

    @Test
    void removingTheShownBookmarkInTheBookmarksViewMovesTheDetailsToTheNextOne() throws Exception {
        onEdt(() -> {
            bookmark(0, 3, 7);
            viewer.showBookmarks();
            viewer.cursorManager().ensureCursorAt(viewer.cursorManager().getPageStart());
            viewer.openLogLineDetails(viewer.cursorManager().cursorLogLine().orElseThrow());
            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("bookmark 1 of 3");
        });

        fire(KeyStroke.getKeyStroke(Character.valueOf('m'), 0));

        onEdt(() -> {
            assertThat(viewer.bookmarks().events()).hasSize(2);
            assertThat(viewer.isDetailsOpen()).isTrue();
            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("bookmark 1 of 2");
            assertThat(viewer.detailsPanel().details().field("n").orElseThrow().value()).isEqualTo("3");
        });
    }

    @Test
    void ctrlCCopiesTheSelectedFieldsValue() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("n");
        });

        fire(CTRL_C);

        onEdt(() -> {
            assertThat(clipboard.text()).hasValue("3");
            assertThat(statusBar.noticeText()).isEqualTo("Copied n");
        });
    }

    // === Previous / next ===

    @Test
    void nextAndPreviousStepTheLogCursorFromEventToEventSkippingContinuationRows() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(5);
            viewer.openLogLineDetails(line(5));
            select("msg");
        });

        fire(F3);
        onEdt(() -> {
            assertThat(viewer.cursorManager().cursorLine()).as("event 2 ends on a continuation row").hasValue(6);
            assertThat(eventNumber()).isEqualTo("3");
            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("row 6");
            assertThat(viewer.detailsPanel().selectedField().orElseThrow().name()).as("same field").isEqualTo("msg");
        });

        fire(SHIFT_F3);
        fire(ALT_UP);
        fire(ALT_UP);
        onEdt(() -> {
            assertThat(viewer.cursorManager().cursorLine()).as("event 2, 1, then event 0 from its last row")
                    .hasValue(0);
            assertThat(eventNumber()).isEqualTo("0");
            assertThat(viewer.detailsPanel().details().message()).contains("message 0").contains("message 2");
            assertThat(viewer.isDetailsOpen()).isTrue();
        });
    }

    @Test
    void thePagerKeysAndTheAltArrowStepToo() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
        });

        fire(KeyStroke.getKeyStroke(Character.valueOf('n'), 0));
        onEdt(() -> assertThat(eventNumber()).isEqualTo("4"));
        fire(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
        onEdt(() -> assertThat(eventNumber()).isEqualTo("5"));
        fire(KeyStroke.getKeyStroke(Character.valueOf('N'), 0));
        onEdt(() -> assertThat(eventNumber()).isEqualTo("4"));
        fire(ALT_UP);
        onEdt(() -> assertThat(eventNumber()).isEqualTo("3"));
    }

    @Test
    void atTheEdgesOfTheLogANoticeSaysSoAndTheDetailsStay() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(0);
            viewer.openLogLineDetails(line(0));
            viewer.detailsStep(false);

            assertThat(statusBar.noticeText()).isEqualTo("No more logs");
            assertThat(viewer.cursorManager().cursorLine()).hasValue(0);
            assertThat(eventNumber()).isEqualTo("0");

            viewer.cursorManager().ensureCursorAt(11);
            viewer.openLogLineDetails(line(11));
            statusBar.showNotice("", StatusBar.Severity.INFO);
            viewer.detailsStep(true);

            assertThat(statusBar.noticeText()).isEqualTo("No more logs");
            assertThat(viewer.cursorManager().cursorLine()).hasValue(11);
            assertThat(viewer.isDetailsOpen()).isTrue();
        });
    }

    @Test
    void aStepIntoRowsThatAreStillLoadingWaitsAndCompletesWhenTheyArrive() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(4);
            viewer.openLogLineDetails(line(4));
            loadingRows.add(6);

            viewer.detailsStep(true);

            assertThat(statusBar.noticeText()).isEqualTo("Loading…");
            assertThat(eventNumber()).as("still the old event").isEqualTo("2");
            assertThat(viewer.cursorManager().cursorLine()).hasValue(4);

            viewer.updateVisibleLines();
            assertThat(eventNumber()).as("still loading").isEqualTo("2");

            loadingRows.clear();
            viewer.updateVisibleLines();

            assertThat(eventNumber()).isEqualTo("3");
            assertThat(viewer.cursorManager().cursorLine()).hasValue(6);
        });
    }

    @Test
    void aNewStepOrCloseDropsTheWaitingOne() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(4);
            viewer.openLogLineDetails(line(4));
            loadingRows.add(6);
            viewer.detailsStep(true);

            viewer.closeDetails();
            loadingRows.clear();
            viewer.updateVisibleLines();

            assertThat(viewer.isDetailsOpen()).isFalse();
            assertThat(viewer.cursorManager().cursorLine()).hasValue(4);
        });
    }

    @Test
    void inTheMatchesViewNextAndPreviousStepByMatch() throws Exception {
        onEdt(() -> {
            startSearch(1, 3, 5, 7);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.cursorManager().ensureCursorAt(matchRow(0));
            viewer.openLogLineDetails(viewer.cursorManager().cursorLogLine().orElseThrow());
            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("match 1 of 4");

            viewer.detailsStep(false);
            assertThat(statusBar.noticeText()).isEqualTo("No more earlier matches");

            viewer.detailsStep(true);
            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("match 2 of 4");
            assertThat(eventNumber()).isEqualTo("3");
            assertThat(viewer.cursorManager().cursorLine()).hasValue(matchRow(1));

            viewer.detailsStep(true);
            viewer.detailsStep(true);
            assertThat(eventNumber()).isEqualTo("7");
            viewer.detailsStep(true);
            assertThat(statusBar.noticeText()).isEqualTo("No more later matches");
            assertThat(eventNumber()).isEqualTo("7");

            viewer.detailsStep(false);
            assertThat(eventNumber()).isEqualTo("5");
        });
    }

    @Test
    void inTheBookmarksViewNextAndPreviousStepByBookmark() throws Exception {
        onEdt(() -> {
            bookmark(0, 3, 7);
            viewer.showBookmarks();
            viewer.cursorManager().ensureCursorAt(viewer.cursorManager().getPageStart());
            viewer.openLogLineDetails(viewer.cursorManager().cursorLogLine().orElseThrow());

            viewer.detailsStep(false);
            assertThat(statusBar.noticeText()).isEqualTo("First bookmark");

            viewer.detailsStep(true);
            assertThat(viewer.detailsPanel().positionLabel()).isEqualTo("bookmark 2 of 3");
            assertThat(eventNumber()).isEqualTo("3");
            viewer.detailsStep(true);
            viewer.detailsStep(true);
            assertThat(statusBar.noticeText()).isEqualTo("Last bookmark");
            assertThat(eventNumber()).isEqualTo("7");
            assertThat(viewer.isBookmarksView()).isTrue();
        });
    }

    // === Field actions ===

    @Test
    void plusNarrowsTheScopeToTheFieldsValueClosesTheDetailsAndReloadsAtTheEvent() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(5);
            viewer.openLogLineDetails(line(5));
            select("level");
        });

        fire(KeyStroke.getKeyStroke(Character.valueOf('+'), 0));

        onEdt(() -> {
            Scope set = scopeSet();
            assertThat(set.narrow()).containsExactly(FieldTerm.of("level", "INFO").orElseThrow().encode());
            assertThat(set.exclude()).isEmpty();
            assertThat(viewer.isDetailsOpen()).isFalse();
            verify(logProvider).jump(hit(2));
        });
    }

    @Test
    void minusExcludesTheFieldsValueAtTheTopRowsTime() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("n");
        });

        fire(KeyStroke.getKeyStroke(Character.valueOf('-'), 0));

        onEdt(() -> {
            Scope set = scopeSet();
            assertThat(set.exclude()).containsExactly(FieldTerm.of("n", "3").orElseThrow().encode());
            assertThat(set.narrow()).isEmpty();
            assertThat(viewer.isDetailsOpen()).isFalse();
        });
    }

    @Test
    void minusFromTheMatchesViewLeavesItForTheLog() throws Exception {
        onEdt(() -> {
            startSearch(1, 3);
            firstVisible(3);
            viewer.toggleMatches();
            viewer.cursorManager().ensureCursorAt(matchRow(1));
            viewer.openLogLineDetails(viewer.cursorManager().cursorLogLine().orElseThrow());
            select("n");

            viewer.detailsExcludeField();

            assertThat(scopeSet().exclude()).containsExactly(FieldTerm.of("n", "3").orElseThrow().encode());
            assertThat(viewer.isMatchesView()).isFalse();
            assertThat(viewer.isDetailsOpen()).isFalse();
        });
    }

    @Test
    void aFieldThatCannotBeFilteredOnGivesANoticeAndChangesNothing() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("_raw");

            viewer.detailsNarrowField();
            assertThat(statusBar.noticeText()).isEqualTo("Can't narrow on _raw: internal field");

            viewer.detailsExcludeField();
            assertThat(statusBar.noticeText()).isEqualTo("Can't exclude _raw: internal field");

            verify(searchManager, never()).setScope(any());
            assertThat(viewer.isDetailsOpen()).isTrue();
        });
    }

    @Test
    void aTermTheScopeAlreadyHasSaysSo() throws Exception {
        scope.set(Scope.of("web", Map.of()).withNarrowField("level", "INFO"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("level");
            assertThat(viewer.detailsPanel().selectedField().orElseThrow().mark())
                    .isEqualTo(se.grynna.pager.ui.details.EventDetails.ScopeMark.NARROW);

            viewer.detailsNarrowField();

            assertThat(statusBar.noticeText()).isEqualTo("Already narrowed to level=\"INFO\"");
            verify(searchManager, never()).setScope(any());
            assertThat(viewer.isDetailsOpen()).isTrue();
        });
    }

    @Test
    void narrowingWithoutALoginSaysSo() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("n");
            scope.set(null);

            viewer.detailsNarrowField();

            assertThat(statusBar.noticeText()).startsWith("Not connected");
            verify(searchManager, never()).setScope(any());
        });
    }

    // === Message fields ===

    @Test
    void fAddsTheSelectedFieldToTheMessageFieldsAppliesThemAndReopensOnTheSameEvent() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(5);
            viewer.openLogLineDetails(line(5));
            select("level");
            assertThat(viewer.detailsPanel().selectedField().orElseThrow().inMessage()).isFalse();
        });

        fire(KeyStroke.getKeyStroke(Character.valueOf('f'), 0));

        List<String> expected = new ArrayList<>(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
        expected.add("level");
        onEdt(() -> {
            verify(searchManager).storeMessageFields("web", expected);
            verify(searchManager).setMessageFields(expected);
            assertThat(statusBar.noticeText()).isEqualTo("Message fields: + level");
            verify(logProvider).jump(hit(2));
            assertThat(viewer.isDetailsOpen()).as("reopened on the same event").isTrue();
            assertThat(SearchHit.from(viewer.detailsEvent())).isEqualTo(hit(2));
            DetailsPanel panel = viewer.detailsPanel();
            assertThat(panel.selectedField().orElseThrow().name()).isEqualTo("level");
            assertThat(panel.selectedField().orElseThrow().inMessage()).as("the ✉ mark moved").isTrue();
            assertThat(viewer.cursorManager().cursorLine()).hasValue(4);
        });
    }

    @Test
    void fRemovesAFieldThatIsInTheMessage() throws Exception {
        messageFields.set(List.of("msg", "level"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("level");
            assertThat(viewer.detailsPanel().selectedField().orElseThrow().inMessage()).isTrue();

            viewer.detailsToggleMessageField();

            verify(searchManager).storeMessageFields("web", List.of("msg"));
            assertThat(messageFields.get()).containsExactly("msg");
            assertThat(statusBar.noticeText()).isEqualTo("Message fields: \u2212 level");
            assertThat(viewer.isDetailsOpen()).isTrue();
            assertThat(viewer.detailsPanel().selectedField().orElseThrow().inMessage()).isFalse();
        });
    }

    @Test
    void togglingBackToTheDefaultListStoresNull() throws Exception {
        List<String> custom = append(LogEventMapper.DEFAULT_MESSAGE_FIELDS, "level");
        messageFields.set(custom);
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("level");

            viewer.detailsToggleMessageField();

            verify(searchManager).storeMessageFields("web", null);
            assertThat(messageFields.get()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
        });
    }

    @Test
    void onlyJsonFieldsCanBeInTheMessage() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("_raw");

            viewer.detailsToggleMessageField();

            assertThat(statusBar.noticeText()).isEqualTo("Only JSON fields can be in the message");

            viewer.closeDetails();
            viewer.cursorManager().ensureCursorAt(11); // the raw event
            viewer.openLogLineDetails(line(11));
            assertThat(viewer.detailsPanel().details().msgType()).isEqualTo(MsgType.RAW);
            statusBar.showNotice("", StatusBar.Severity.INFO);

            viewer.detailsToggleMessageField();

            assertThat(statusBar.noticeText()).isEqualTo("Only JSON fields can be in the message");
            verify(searchManager, never()).setMessageFields(any());
            verify(searchManager, never()).storeMessageFields(any(), any());
            assertThat(viewer.isDetailsOpen()).isTrue();
        });
    }

    @Test
    void aFailedStoreSurfacesAsAToastButTheListStillApplies() throws Exception {
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("disk full"));
        when(searchManager.storeMessageFields(any(), any())).thenReturn(failed);
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("level");

            viewer.detailsToggleMessageField();
        });
        onEdt(() -> verify(searchManager).setMessageFields(any()));
    }

    // === Field menu ===

    @Test
    void theFieldMenuOffersTheFieldActionsWithTheirKeys() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("level");

            JPopupMenu menu = viewer.detailsFieldMenu();

            assertThat(texts(menu)).containsExactly("Narrow to level=\"INFO\" (+)", "Exclude level=\"INFO\" (-)",
                    "Add to message (f)", "Copy value (Ctrl-C)", "Copy level=\"INFO\"");
            assertThat(menuItem(menu, "Narrow to level=\"INFO\" (+)").isEnabled()).isTrue();
        });
    }

    @Test
    void theFieldMenuDisablesWhatDoesNotApplyAndOffersTheResetForACustomList() throws Exception {
        messageFields.set(append(LogEventMapper.DEFAULT_MESSAGE_FIELDS, "level"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("_raw");

            JPopupMenu menu = viewer.detailsFieldMenu();

            assertThat(texts(menu)).contains("Reset message fields");
            assertThat(menuItem(menu, texts(menu).getFirst()).isEnabled()).isFalse();
            assertThat(menuItem(menu, "Add to message (f)").isEnabled()).isFalse();

            select("level");
            assertThat(texts(viewer.detailsFieldMenu())).contains("Remove from message (f)");
        });
    }

    @Test
    void theFieldMenuItemsCallTheFieldActions() throws Exception {
        messageFields.set(append(LogEventMapper.DEFAULT_MESSAGE_FIELDS, "level"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
            select("n");

            menuItem(viewer.detailsFieldMenu(), "Copy n=\"3\"").doClick();
            assertThat(clipboard.text()).hasValue("n=\"3\"");

            menuItem(viewer.detailsFieldMenu(), "Copy value (Ctrl-C)").doClick();
            assertThat(clipboard.text()).hasValue("3");

            menuItem(viewer.detailsFieldMenu(), "Reset message fields").doClick();
            verify(searchManager).storeMessageFields("web", null);
            assertThat(messageFields.get()).isEqualTo(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
            assertThat(statusBar.noticeText()).isEqualTo("Message fields: reset");
            assertThat(viewer.isDetailsOpen()).as("reopened").isTrue();
        });
    }

    @Test
    void theContextMenuKeyAndAMenuRequestNeedAFieldButNoDisplay() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));
        });

        fire(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK));

        onEdt(() -> assertThat(viewer.isDetailsOpen()).isTrue());
    }

    // === Things that take the view away ===

    @Test
    void logoutClosesTheDetails() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));

            viewer.requestLogout();

            assertThat(viewer.isDetailsOpen()).isFalse();
            assertThat(find(viewer, DetailsPanel.class)).isEmpty();
            assertThat(statusBar.viewTabs().active()).isEqualTo(ViewTabs.Tab.LOG);
        });
    }

    @Test
    void aScopeChangeClosesTheDetails() throws Exception {
        scope.set(Scope.of("web", Map.of()).withNarrow("foo"));
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));

            viewer.removeScopeTerm("foo");

            verify(searchManager).setScope(any());
            assertThat(viewer.isDetailsOpen()).isFalse();
        });
    }

    @Test
    void anotherLoginClosesTheDetails() throws Exception {
        onEdt(() -> {
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));

            viewer.loginComplete(searchManager);

            assertThat(viewer.isDetailsOpen()).isFalse();
        });
    }

    @Test
    void enteringAnEventViewClosesTheDetails() throws Exception {
        onEdt(() -> {
            bookmark(3);
            viewer.cursorManager().ensureCursorAt(6);
            viewer.openLogLineDetails(line(6));

            viewer.showBookmarks();

            assertThat(viewer.isDetailsOpen()).isFalse();
            assertThat(viewer.isBookmarksView()).isTrue();
        });
    }

    // === helpers ===

    private String eventNumber() {
        return viewer.detailsPanel().details().field("n").orElseThrow().value();
    }

    private void select(String name) {
        viewer.detailsPanel().select(viewer.detailsPanel().details().indexOf(name));
    }

    private boolean visibleButton(String text) {
        return find(statusBar, JButton.class).stream().filter(button -> text.equals(button.getText()))
                .anyMatch(Component::isVisible);
    }

    private int matchRow(int matchIndex) {
        MatchesDataProvider provider = (MatchesDataProvider) viewer.cursorManager().captureViewState().provider();
        return provider.rows().rowOf(matchIndex, 0);
    }

    private Scope scopeSet() {
        ArgumentCaptor<Scope> captor = ArgumentCaptor.forClass(Scope.class);
        verify(searchManager).setScope(captor.capture());
        return captor.getValue();
    }

    private static List<String> withoutDefault(String name) {
        List<String> fields = new ArrayList<>(LogEventMapper.DEFAULT_MESSAGE_FIELDS);
        fields.remove(name);
        return List.copyOf(fields);
    }

    private static List<String> append(List<String> fields, String name) {
        List<String> result = new ArrayList<>(fields);
        result.add(name);
        return List.copyOf(result);
    }

    private static List<String> texts(JPopupMenu menu) {
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance)
                .map(c -> ((JMenuItem) c).getText()).toList();
    }

    private static JMenuItem menuItem(JPopupMenu popup, String text) {
        return Arrays.stream(popup.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                .filter(item -> text.equals(item.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no menu item " + text + " in " + texts(popup)));
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

    private static String raw(int event) {
        return event == RAW_EVENT
                ? "plain text event"
                : "{\"level\":\"INFO\",\"msg\":\"message " + event + "\",\"n\":\"" + event + "\"}";
    }

    private static MsgType typeOf(int event) {
        return event == RAW_EVENT ? MsgType.RAW : MsgType.JSON;
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
        return new LoadedLogLine(typeOf(event), "seg", first, "cb" + event, "bkt", time, raw(event), time, time,
                String.join("\n", messages), false, Level.INFO);
    }

    private static LoadedLogLine line(int row) {
        int event = EVENT_OF_ROW[row];
        boolean continuation = CONTINUATION[row];
        Instant time = T0.plusSeconds(10L * event);
        return new LoadedLogLine(typeOf(event), "seg", row, "cb" + event, "bkt", time, raw(event), time, time,
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
