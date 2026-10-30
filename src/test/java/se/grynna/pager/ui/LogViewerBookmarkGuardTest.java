package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.export.ExportController;
import se.grynna.pager.export.ExportFormat;
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
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.UnsavedBookmarksGuard.Choice;
import se.grynna.pager.ui.UnsavedBookmarksGuard.Purpose;
import se.grynna.pager.ui.clip.InMemoryClipboard;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bookmarks and scope changes, copy and quit through the viewer, no window and no dialog shown. The log has
 * five single-row events: even events say "boom", odd ones "quiet", all with host=a1 and index=web.
 */
class LogViewerBookmarkGuardTest {

    private static final Instant T0 = Instant.parse("2017-03-30T10:00:00Z");
    private static final int EVENTS = 5;

    private LogViewer viewer;
    private StatusBar statusBar;
    private SearchManager searchManager;
    private final AtomicReference<Scope> scope = new AtomicReference<>(Scope.of("web", Map.of("host", "a1")));
    private final AtomicReference<SearchSession> session = new AtomicReference<>();
    private final InMemoryClipboard clipboard = new InMemoryClipboard();

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider provider = mock(SplunkLogDataProvider.class);
        when(provider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, T0));
        when(provider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, T0.plusSeconds(600)));
        when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, T0.plusSeconds(600)));
        when(provider.getStatistics())
                .thenReturn(new LogStatistics(LogStatistics.Status.LOADED, EVENTS, T0, T0.plusSeconds(600)));
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> line(inv.getArgument(0)));
        when(provider.peekLine(anyInt())).thenAnswer(inv -> Optional.of(line(inv.getArgument(0))));

        searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(provider);
        when(searchManager.scope()).thenAnswer(inv -> scope.get());
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(T0.plusSeconds(3600));
        when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        doAnswer(inv -> {
            scope.set(inv.getArgument(0));
            session.set(null);
            return null;
        }).when(searchManager).setScope(any());

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

    // === Scope changes from the status bar ===

    @Test
    void narrowingWithBookmarksOutsideAsksFirstAndChangesNothingUntilYes() throws Exception {
        onEdt(() -> {
            bookmark(0, 1, 2, 3);
            searchFor("boom", 0, 2, 4);

            viewer.narrowScopeToSearch();

            assertThat(statusBar.hasScopeConfirmPrompt()).isTrue();
            assertThat(statusBar.inputState()).isEqualTo(InputState.SCOPE_CONFIRM);
            assertThat(statusBar.searchPromptText())
                    .isEqualTo("2 bookmarks are outside the new scope and will be removed. Continue?");
            assertThat(statusBar.noticeText()).contains("Copy bookmarks").contains("Export");
            assertThat(viewer.isYesNoPromptShowing()).isTrue();
            verify(searchManager, never()).setScope(any());
            assertThat(viewer.bookmarks().size()).isEqualTo(4);
            assertThat(statusBar.isSearchActive()).as("search untouched while asking").isTrue();
        });
    }

    @Test
    void yesAppliesTheScopeAndDropsExactlyTheOutsideBookmarks() throws Exception {
        onEdt(() -> {
            bookmark(0, 1, 2, 3);
            searchFor("boom", 0, 2, 4);
            viewer.narrowScopeToSearch();

            assertThat(viewer.actionAcceptBoundaryExpansion(true)).isTrue();

            assertThat(scope.get().narrow()).containsExactly("boom");
            assertThat(hitsOf(viewer.bookmarks().events())).containsExactly(hit(0), hit(2));
            assertThat(statusBar.hasScopeConfirmPrompt()).isFalse();
            assertThat(viewer.isYesNoPromptShowing()).isFalse();
            assertThat(statusBar.noticeText()).isEqualTo("2 bookmarks removed");
        });
    }

    @Test
    void noKeepsEveryBookmarkAndTheScope() throws Exception {
        onEdt(() -> {
            bookmark(0, 1, 2, 3);
            searchFor("boom", 0, 2, 4);
            viewer.narrowScopeToSearch();

            assertThat(viewer.actionAcceptBoundaryExpansion(false)).isTrue();

            verify(searchManager, never()).setScope(any());
            assertThat(scope.get().narrow()).isEmpty();
            assertThat(viewer.bookmarks().size()).isEqualTo(4);
            assertThat(statusBar.hasScopeConfirmPrompt()).isFalse();
            assertThat(viewer.isYesNoPromptShowing()).isFalse();
            assertThat(statusBar.isSearchActive()).isTrue();
        });
    }

    @Test
    void narrowingWithNoBookmarkOutsideAppliesAtOnceWithoutAPrompt() throws Exception {
        onEdt(() -> {
            bookmark(0, 2);
            searchFor("boom", 0, 2, 4);

            viewer.narrowScopeToSearch();

            assertThat(statusBar.hasScopeConfirmPrompt()).isFalse();
            assertThat(scope.get().narrow()).containsExactly("boom");
            assertThat(viewer.bookmarks().size()).isEqualTo(2);
        });
    }

    @Test
    void excludingDropsTheBookmarksThatContainTheTermAfterYes() throws Exception {
        onEdt(() -> {
            bookmark(0, 1);
            searchFor("boom", 0, 2, 4);

            viewer.excludeSearchFromScope();

            assertThat(statusBar.searchPromptText())
                    .isEqualTo("1 bookmark is outside the new scope and will be removed. Continue?");
            verify(searchManager, never()).setScope(any());

            viewer.actionAcceptBoundaryExpansion(true);

            assertThat(scope.get().exclude()).containsExactly("boom");
            assertThat(hitsOf(viewer.bookmarks().events())).containsExactly(hit(1));
            assertThat(statusBar.noticeText()).isEqualTo("1 bookmark removed");
        });
    }

    @Test
    void removingATermNeverAsks() throws Exception {
        scope.set(scope.get().withNarrow("boom"));
        onEdt(() -> {
            bookmark(0, 2);

            viewer.removeScopeTerm("boom");

            assertThat(statusBar.hasScopeConfirmPrompt()).isFalse();
            assertThat(viewer.bookmarks().size()).isEqualTo(2);
        });
    }

    @Test
    void aSecondScopeChangeWhileAskingIsRefused() throws Exception {
        onEdt(() -> {
            bookmark(0, 1);
            searchFor("boom", 0, 2, 4);
            viewer.narrowScopeToSearch();

            viewer.excludeSearchFromScope();

            assertThat(statusBar.noticeText()).isEqualTo("Answer the open question first");
            assertThat(statusBar.hasScopeConfirmPrompt()).isTrue();
            verify(searchManager, never()).setScope(any());
        });
    }

    @Test
    void logoutForgetsTheOpenQuestion() throws Exception {
        onEdt(() -> {
            bookmark(1);
            searchFor("boom", 0, 2, 4);
            viewer.narrowScopeToSearch();

            handleLogout();

            assertThat(statusBar.hasScopeConfirmPrompt()).isFalse();
            assertThat(viewer.isYesNoPromptShowing()).isFalse();
            assertThat(viewer.actionAcceptBoundaryExpansion(true)).isFalse();
        });
    }

    // === The index panel's guard ===

    @Test
    void theIndexPanelGuardCountsAndDropsTheBookmarksOutsideTheNewScope() throws Exception {
        onEdt(() -> {
            bookmark(0, 1, 2);
            var guard = viewer.bookmarkScopeGuard();
            Scope sameIndexOtherHost = Scope.of("web", Map.of("host", "a2"));
            Scope otherIndex = Scope.of("db", Map.of("host", "a1"));
            Scope narrowed = scope.get().withNarrow("boom");

            assertThat(guard.outside(scope.get())).isZero();
            assertThat(guard.outside(sameIndexOtherHost)).isEqualTo(3);
            assertThat(guard.outside(otherIndex)).isEqualTo(3);
            assertThat(guard.outside(narrowed)).isEqualTo(1);

            guard.drop(narrowed);
            assertThat(hitsOf(viewer.bookmarks().events())).containsExactly(hit(0), hit(2));
            assertThat(statusBar.noticeText()).isEqualTo("1 bookmark removed");
        });
    }

    // === Copy bookmarks and the unsaved count ===

    @Test
    void copyBookmarksPutsAllBookmarksOnTheClipboardAsTextAndMarksThemSaved() throws Exception {
        onEdt(() -> {
            bookmark(3, 0);
            assertThat(viewer.hasBookmarks()).isTrue();
            assertThat(viewer.bookmarks().unsavedCount()).isEqualTo(2);

            viewer.copyBookmarks();

            List<String> expected = BookmarksDataProvider.wholeEventText(viewer.bookmarks().events());
            assertThat(clipboard.text().orElseThrow().lines().toList()).isEqualTo(expected);
            assertThat(expected.getFirst()).endsWith("boom 0");
            assertThat(expected.getLast()).endsWith("quiet 3");
            assertThat(statusBar.noticeText()).isEqualTo("Copied 2 bookmarks");
            assertThat(viewer.bookmarks().unsavedCount()).isZero();
        });
    }

    @Test
    void copyBookmarksWithoutBookmarksSaysSo() throws Exception {
        onEdt(() -> {
            assertThat(viewer.hasBookmarks()).isFalse();

            viewer.copyBookmarks();

            assertThat(clipboard.contents()).isNull();
            assertThat(statusBar.noticeText()).isEqualTo("No bookmarks to copy");
        });
    }

    @Test
    void ctrlCOfAllBookmarksInTheBookmarksViewMarksThemSaved() throws Exception {
        onEdt(() -> {
            bookmark(0, 1);
            viewer.showBookmarks();
            viewer.cursorManager().clearSelection();

            assertThat(viewer.copyAllBookmarks()).isTrue();

            assertThat(viewer.bookmarks().unsavedCount()).isZero();
            assertThat(statusBar.noticeText()).isEqualTo("Copied 2 bookmarks");
        });
    }

    @Test
    void copyAsOfABookmarkedEventMarksJustThatEventSaved() throws Exception {
        onEdt(() -> {
            bookmark(0, 1);
            viewer.cursorManager().ensureCursorAt(1);
            viewer.cursorManager().clearSelection();

            viewer.copyAs(ExportFormat.TEXT);

            assertThat(viewer.bookmarks().unsavedCount()).isEqualTo(1);
            viewer.cursorManager().ensureCursorAt(0);
            viewer.copyAs(ExportFormat.CSV);
            assertThat(viewer.bookmarks().unsavedCount()).isZero();
        });
    }

    @Test
    void copyAsOfAnEventThatIsNotBookmarkedChangesNothing() throws Exception {
        onEdt(() -> {
            bookmark(0);
            viewer.cursorManager().ensureCursorAt(3);
            viewer.cursorManager().clearSelection();

            viewer.copyAs(ExportFormat.TEXT);

            assertThat(viewer.bookmarks().unsavedCount()).isEqualTo(1);
        });
    }

    @Test
    void aSuccessfulExportMarksTheExportedBookmarksSavedAndContinues() throws Exception {
        AtomicInteger continued = new AtomicInteger();
        onEdt(() -> {
            bookmark(0, 1);
            viewer.setExportController(exportController(Path.of("ignored"), false));

            viewer.exportChosen(new ExportDialog.Choice(ExportSources.Source.BOOKMARKS, ExportFormat.TEXT,
                    Path.of("ignored")), continued::incrementAndGet);

            assertThat(viewer.bookmarks().unsavedCount()).isZero();
            assertThat(continued).hasValue(1);
        });
    }

    @Test
    void aFailedExportLeavesTheBookmarksUnsavedAndDoesNotContinue() throws Exception {
        AtomicInteger continued = new AtomicInteger();
        onEdt(() -> {
            bookmark(0, 1);
            viewer.setExportController(exportController(Path.of("ignored"), true));

            viewer.exportChosen(new ExportDialog.Choice(ExportSources.Source.BOOKMARKS, ExportFormat.TEXT,
                    Path.of("ignored")), continued::incrementAndGet);

            assertThat(viewer.bookmarks().unsavedCount()).isEqualTo(2);
            assertThat(continued).hasValue(0);
        });
    }

    // === Quit and logout ===

    @Test
    void quitWithNothingUnsavedExitsWithoutAsking() throws Exception {
        List<String> asked = new ArrayList<>();
        onEdt(() -> {
            viewer.setUnsavedChooser((purpose, count) -> {
                asked.add(purpose + " " + count);
                return Choice.CANCEL;
            });
            bookmark(0);
            viewer.copyBookmarks();
            AtomicInteger exited = new AtomicInteger();

            viewer.requestQuit(exited::incrementAndGet);

            assertThat(exited).hasValue(1);
            assertThat(asked).isEmpty();
        });
    }

    @Test
    void quitAsksAndEachAnswerDecides() throws Exception {
        List<String> asked = new ArrayList<>();
        AtomicReference<Choice> answer = new AtomicReference<>();
        onEdt(() -> {
            viewer.setUnsavedChooser((purpose, count) -> {
                asked.add(purpose + " " + count);
                return answer.get();
            });
            bookmark(0, 1);
            AtomicInteger exited = new AtomicInteger();

            answer.set(Choice.CANCEL);
            viewer.requestQuit(exited::incrementAndGet);
            assertThat(exited).hasValue(0);

            answer.set(Choice.COPY);
            viewer.requestQuit(exited::incrementAndGet);
            assertThat(exited).hasValue(1);
            assertThat(clipboard.text().orElseThrow()).contains("boom 0").contains("quiet 1");
            assertThat(viewer.bookmarks().unsavedCount()).isZero();
            assertThat(asked).containsExactly("QUIT 2", "QUIT 2");

            bookmark(2);
            answer.set(Choice.PROCEED);
            viewer.requestQuit(exited::incrementAndGet);
            assertThat(exited).hasValue(2);
            assertThat(viewer.bookmarks().unsavedCount()).isEqualTo(1);
        });
    }

    @Test
    void theCloseListenerRunsTheSameGuard() throws Exception {
        onEdt(() -> {
            viewer.setUnsavedChooser((purpose, count) -> Choice.CANCEL);
            bookmark(0);
            AtomicInteger exited = new AtomicInteger();

            AppClose.listener(viewer, exited::incrementAndGet).windowClosing(null);
            assertThat(exited).hasValue(0);

            viewer.copyBookmarks();
            AppClose.listener(viewer, exited::incrementAndGet).windowClosing(null);
            assertThat(exited).hasValue(1);
        });
    }

    @Test
    void logoutWithUnsavedBookmarksAsksAndCancelKeepsTheSession() throws Exception {
        List<Purpose> asked = new ArrayList<>();
        onEdt(() -> {
            viewer.setUnsavedChooser((purpose, count) -> {
                asked.add(purpose);
                return Choice.CANCEL;
            });
            bookmark(0, 1);

            viewer.requestLogout();

            assertThat(asked).containsExactly(Purpose.LOG_OUT);
            assertThat(viewer.bookmarks().size()).isEqualTo(2);
            verify(searchManager, never()).cancelSearch();
        });
    }

    @Test
    void logoutAnywayAndCopyAndLogOutClearTheBookmarks() throws Exception {
        onEdt(() -> {
            viewer.setUnsavedChooser((purpose, count) -> Choice.COPY);
            bookmark(0, 1);

            viewer.requestLogout();

            assertThat(clipboard.text().orElseThrow()).contains("boom 0");
            assertThat(viewer.bookmarks().size()).isZero();
        });
    }

    @Test
    void logoutWithNothingUnsavedDoesNotAsk() throws Exception {
        onEdt(() -> {
            viewer.setUnsavedChooser((purpose, count) -> {
                throw new AssertionError("must not ask");
            });

            viewer.requestLogout();
        });
    }

    // === helpers ===

    private ExportController exportController(Path file, boolean failing) {
        return new ExportController(Runnable::run, Runnable::run, (path, bytes) -> {
            if (failing) {
                throw new IOException("disk full");
            }
        }, text -> {}, text -> {});
    }

    private void handleLogout() throws Exception {
        var method = LogViewer.class.getDeclaredMethod("handleLogout");
        method.setAccessible(true);
        method.invoke(viewer);
    }

    private void bookmark(int... events) {
        for (int event : events) {
            viewer.bookmarks().toggle(List.of(eventLine(event)));
        }
    }

    private void searchFor(String term, int... matchEvents) {
        SearchSession active = new SearchSession(term, T0);
        for (int event : matchEvents) {
            SearchSessionTestSupport.addMatch(active, hit(event), eventLine(event));
        }
        SearchSessionTestSupport.complete(active);
        session.set(active);
        statusBar.setSearchActive(true, term);
    }

    private static List<SearchHit> hitsOf(List<LoadedLogLine> lines) {
        return lines.stream().map(SearchHit::from).toList();
    }

    private static SearchHit hit(int event) {
        return SearchHit.from(eventLine(event));
    }

    private static LoadedLogLine eventLine(int event) {
        return line(event);
    }

    private static LoadedLogLine line(int row) {
        Instant time = T0.plusSeconds(10L * row);
        String text = (row % 2 == 0 ? "boom " : "quiet ") + row;
        var line = new LoadedLogLine(MsgType.RAW, "seg", row, "cb" + row, "bkt", time, text, time, time, text, false,
                Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("index", "web", "host", "a1", "_cd", "cb" + row, "_bkt", "bkt")));
        return line;
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
