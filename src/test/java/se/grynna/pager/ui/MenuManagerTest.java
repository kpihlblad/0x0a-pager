package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.LogStatistics;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.login.UserLoginPanel;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The hamburger menu's Select Index item opens the login/index flow.
 */
class MenuManagerTest {

    private static final Instant ORIGIN = Instant.parse("2017-03-30T10:00:00Z");
    private static final Instant LATEST = ORIGIN.plusSeconds(3600);

    private LogViewer viewer;
    private StatusBar statusBar;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();

    @AfterEach
    void restorePreferredFlavour() {
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
    }

    @Test
    void selectIndexMenuItemOpensTheLoginOverlay() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            // The viewer reads the user's key hints preference; the tests state their own
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            assertThat(loginOverlayShown()).isFalse();
        });

        onEdt(() -> selectIndexItem().doClick());
        drainEdt();

        onEdt(() -> assertThat(loginOverlayShown()).isTrue());
    }

    @Test
    void keyboardHelpMenuItemIsLabelledFromTheKeyMap() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            // The viewer reads the user's key hints preference; the tests state their own
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
        });

        onEdt(() -> assertThat(helpItem().getText())
                .isEqualTo("Keyboard help (" + KeyMap.defaults().label(UiAction.HELP) + ")"));
    }

    @Test
    void matchesEntryIsLabelledFromTheKeyMapAndNeedsASearch() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            // The viewer reads the user's key hints preference; the tests state their own
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
        });

        onEdt(() -> {
            JPopupMenu popup = hamburgerPopup();
            for (PopupMenuListener listener : popup.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(new PopupMenuEvent(popup));
            }
            JMenuItem matches = matchesItem();
            assertThat(matches.getText()).isEqualTo("Show matches (F4)");
            assertThat(matches.getText()).isEqualTo(
                    "Show matches (" + KeyMap.defaults().label(UiAction.TOGGLE_MATCHES) + ")");
            assertThat(matches.isEnabled()).isFalse();
        });
    }

    @Test
    void narrowToActivityEntryIsLabelledFromTheKeyMap() {
        assertThat(MenuManager.narrowActivityText(KeyMap.defaults())).isEqualTo("Narrow to activity (a)");
    }

    @Test
    void matchesEntryTextFollowsTheView() {
        KeyMap keyMap = KeyMap.defaults();

        assertThat(MenuManager.matchesEntry(true, false, keyMap))
                .isEqualTo(new MenuManager.MatchesItem("Show matches (F4)", true));
        assertThat(MenuManager.matchesEntry(true, true, keyMap))
                .isEqualTo(new MenuManager.MatchesItem("Show in log (Enter)", true));
        assertThat(MenuManager.matchesEntry(false, false, keyMap).enabled()).isFalse();
        assertThat(MenuManager.matchesEntry(false, true, keyMap).enabled()).isFalse();
    }

    @Test
    void narrowAndExcludeEntriesAreLabelledFromTheKeyMapAndNeedASearch() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            // The viewer reads the user's key hints preference; the tests state their own
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
        });

        onEdt(() -> {
            JPopupMenu popup = hamburgerPopup();
            for (PopupMenuListener listener : popup.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(new PopupMenuEvent(popup));
            }
            assertThat(menuItem("Narrow scope to matches (+)").isEnabled()).isFalse();
            assertThat(menuItem("Exclude matches from scope (-)").isEnabled()).isFalse();
        });
    }

    @Test
    void scopeEntryLabelsAreTakenFromTheKeyMap() {
        KeyMap keyMap = KeyMap.defaults();

        assertThat(MenuManager.narrowText(keyMap)).isEqualTo(
                "Narrow scope to matches (" + keyMap.label(UiAction.NARROW) + ")");
        assertThat(MenuManager.excludeText(keyMap)).isEqualTo(
                "Exclude matches from scope (" + keyMap.label(UiAction.EXCLUDE) + ")");
    }

    @Test
    void entryLabelsFollowTheKeyHintsFlavour() {
        KeyMap pager = KeyMap.defaults().withFlavour(KeyFlavour.PAGER);
        KeyMap emacs = KeyMap.defaults().withFlavour(KeyFlavour.EMACS);

        assertThat(MenuManager.matchesEntry(true, false, pager).text()).isEqualTo("Show matches (&)");
        assertThat(MenuManager.matchesEntry(true, true, pager).text()).isEqualTo("Show in log (&)");
        assertThat(MenuManager.matchesEntry(true, false, emacs).text()).isEqualTo("Show matches (F4)");
        assertThat(MenuManager.cursorText(KeyMap.defaults())).isEqualTo("Cursor mode (F7)");
        assertThat(MenuManager.cursorText(pager)).isEqualTo("Cursor mode (v)");
        assertThat(MenuManager.cursorText(emacs)).isEqualTo("Cursor mode (F7)");
        assertThat(MenuManager.helpText(pager)).isEqualTo("Keyboard help (h)");
        assertThat(MenuManager.loginText(pager)).isEqualTo("Scope: index and filters (L)");
        assertThat(MenuManager.bookmarkText(KeyMap.defaults())).isEqualTo("Toggle bookmark (Ctrl-B)");
        assertThat(MenuManager.bookmarkText(pager)).isEqualTo("Toggle bookmark (m)");
        assertThat(MenuManager.bookmarksViewText(KeyMap.defaults())).isEqualTo("Show bookmarks (F2)");
        assertThat(MenuManager.bookmarksViewText(pager)).isEqualTo("Show bookmarks (M)");
    }

    @Test
    void menuLabelsAreRebuiltFromThePreferenceEachTimeTheMenuOpens() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
        });

        onEdt(() -> {
            openPopup();
            assertThat(menuItem("Cursor mode (F7)")).isInstanceOf(JCheckBoxMenuItem.class);
            assertThat(menuItem("Cursor mode (F7)").isSelected()).isFalse();
            assertThat(menuItem("Narrow scope to matches (+)")).isNotNull();

            KeyMap.setPreferredFlavour(KeyFlavour.PAGER);
            openPopup();
            assertThat(menuItem("Cursor mode (v)")).isNotNull();
            assertThat(hasItem("Cursor mode (F7)")).isFalse();
        });
    }

    @Test
    void theCursorModeItemTogglesAndShowsTheCursorState() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
        });

        onEdt(() -> {
            assertThat(viewer.isCursorMode()).isFalse();
            menuItem("Cursor mode (F7)").doClick();
            assertThat(viewer.isCursorMode()).isTrue();

            openPopup();
            assertThat(menuItem("Cursor mode (F7)").isSelected()).isTrue();

            menuItem("Cursor mode (F7)").doClick();
            assertThat(viewer.isCursorMode()).isFalse();
        });
    }

    @Test
    void copyAsAndExportEntriesAreLabelledFromTheKeyMap() {
        assertThat(MenuManager.copyAsText(KeyMap.defaults())).isEqualTo(
                "Copy as… (" + KeyMap.defaults().label(UiAction.COPY_AS) + ")");
        assertThat(MenuManager.copyAsText(KeyMap.defaults())).isEqualTo("Copy as… (Ctrl-Shift-C)");
        assertThat(MenuManager.copyAsText(KeyMap.defaults().withFlavour(KeyFlavour.PAGER)))
                .isEqualTo("Copy as… (Ctrl-Shift-C)");
        assertThat(MenuManager.exportText()).isEqualTo("Export…");
    }

    @Test
    void theMenuHasCopyAsAndExportEntries() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
        });

        onEdt(() -> {
            openPopup();
            assertThat(menuItem("Copy as… (Ctrl-Shift-C)")).isNotNull();
            assertThat(menuItem("Export…")).isNotNull();
        });
    }

    @Test
    void copyBookmarksIsEnabledOnlyWhileBookmarksExistAndCopiesThemAsText() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        var clipboard = new se.grynna.pager.ui.clip.InMemoryClipboard();
        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            viewer.cursorManager().setClipboard(clipboard);
        });

        onEdt(() -> {
            openPopup();
            assertThat(menuItem("Copy bookmarks").isEnabled()).isFalse();
        });

        onEdt(() -> {
            java.time.Instant time = java.time.Instant.parse("2017-03-30T10:00:00Z");
            viewer.bookmarks().toggle(List.of(new se.grynna.pager.splunk.LoadedLogLine(
                    se.grynna.pager.splunk.LoadedLogLine.MsgType.RAW, "seg", 0, "cd", "bkt", time, "raw", time, time,
                    "bookmarked message", false, se.grynna.pager.splunk.Level.INFO)));
            openPopup();
            JMenuItem item = menuItem("Copy bookmarks");
            assertThat(item.isEnabled()).isTrue();

            item.doClick();

            assertThat(clipboard.text().orElseThrow()).contains("bookmarked message");
            assertThat(viewer.bookmarks().unsavedCount()).isZero();
        });
    }

    @Test
    void searchEntriesAreDisabledWithoutASearchAndTheLogActionsAreEnabled() throws Exception {
        openViewerWithSearch();

        onEdt(() -> {
            openPopup();
            assertThat(matchesItem().isEnabled()).isFalse();
            assertThat(menuItem("Narrow scope to matches (+)").isEnabled()).isFalse();
            assertThat(menuItem("Exclude matches from scope (-)").isEnabled()).isFalse();
            assertThat(menuItem("Copy as… (Ctrl-Shift-C)").isEnabled()).isTrue();
            assertThat(menuItem("Export…").isEnabled()).isTrue();
        });
    }

    @Test
    void searchEntriesAreEnabledWhileTheSearchIsRunningAndTheMenuReflectsItWhenItOpens() throws Exception {
        openViewerWithSearch();
        onEdt(() -> {
            openPopup();
            assertThat(matchesItem().isEnabled()).isFalse();
        });

        onEdt(() -> startLiveSearch("boom", false));

        onEdt(() -> {
            openPopup();
            assertThat(matchesItem().getText()).isEqualTo("Show matches (F4)");
            assertThat(matchesItem().isEnabled()).isTrue();
            assertThat(menuItem("Narrow scope to matches (+)").isEnabled()).isTrue();
            assertThat(menuItem("Exclude matches from scope (-)").isEnabled()).isTrue();
        });
    }

    @Test
    void searchEntriesStayEnabledWhenTheRunningSearchCompletes() throws Exception {
        openViewerWithSearch();
        onEdt(() -> startLiveSearch("boom", false));

        onEdt(() -> {
            SearchSessionTestSupport.complete(session.get());
            statusBar.updateSearchHitCount(2, true, ORIGIN, LATEST);
            openPopup();
            assertThat(matchesItem().isEnabled()).isTrue();
            assertThat(menuItem("Narrow scope to matches (+)").isEnabled()).isTrue();
            assertThat(menuItem("Exclude matches from scope (-)").isEnabled()).isTrue();
        });
    }

    @Test
    void searchEntriesStayEnabledWhenTheSearchIsStoppedAndItsMatchesRemain() throws Exception {
        openViewerWithSearch();
        onEdt(() -> startLiveSearch("boom", false));

        onEdt(() -> {
            SearchSessionTestSupport.cancel(session.get());
            statusBar.setTextSearchPhase(SearchStatusText.Phase.STOPPED);
            openPopup();
            assertThat(matchesItem().isEnabled()).isTrue();
            assertThat(menuItem("Narrow scope to matches (+)").isEnabled()).isTrue();
            assertThat(menuItem("Exclude matches from scope (-)").isEnabled()).isTrue();
        });
    }

    @Test
    void searchEntriesAreDisabledAgainOnceTheSearchIsExited() throws Exception {
        openViewerWithSearch();
        onEdt(() -> startLiveSearch("boom", false));

        onEdt(() -> {
            session.set(null);
            statusBar.setSearchActive(false, null);
            openPopup();
            assertThat(matchesItem().isEnabled()).isFalse();
            assertThat(menuItem("Narrow scope to matches (+)").isEnabled()).isFalse();
            assertThat(menuItem("Exclude matches from scope (-)").isEnabled()).isFalse();
        });
    }

    private void openViewerWithSearch() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        onEdt(() -> {
            SplunkLogDataProvider provider = mock(SplunkLogDataProvider.class);
            when(provider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, ORIGIN));
            when(provider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, LATEST));
            when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, LATEST));
            when(provider.getStatistics()).thenReturn(
                    new LogStatistics(LogStatistics.Status.LOADED, 3, ORIGIN, LATEST));
            SearchManager searchManager = mock(SearchManager.class);
            when(searchManager.getDataProvider()).thenReturn(provider);
            when(searchManager.scope()).thenReturn(Scope.of("web", Map.of()));
            when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
            when(searchManager.latestSearchableInstant()).thenReturn(LATEST);
            when(searchManager.storeScopeTerms(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
            installSearchManager(viewer, searchManager);
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
        });
    }

    /** A search whose Splunk query is still running (or already complete), with two matches found so far. */
    private void startLiveSearch(String term, boolean completed) {
        SearchSession active = SearchSessionTestSupport.sessionWith(term, ORIGIN,
                new SearchHit(ORIGIN.plusSeconds(10), ORIGIN.plusSeconds(10), "bkt", "1:10"),
                new SearchHit(ORIGIN.plusSeconds(50), ORIGIN.plusSeconds(50), "bkt", "1:50"));
        if (completed) {
            SearchSessionTestSupport.complete(active);
        }
        session.set(active);
        statusBar.setSearchActive(true, term);
        statusBar.updateSearchHitCount(2, completed, ORIGIN, LATEST);
    }

    /** The login flow owns this field; the test installs the mock the same way LogViewerScopeTest does. */
    private static void installSearchManager(LogViewer viewer, SearchManager searchManager) {
        try {
            java.lang.reflect.Field field = LogViewer.class.getDeclaredField("searchManager");
            field.setAccessible(true);
            field.set(viewer, searchManager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void openPopup() {
        JPopupMenu popup = hamburgerPopup();
        for (PopupMenuListener listener : popup.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(new PopupMenuEvent(popup));
        }
    }

    private boolean hasItem(String text) {
        return Arrays.stream(menuItems()).anyMatch(item -> text.equals(item.getText()));
    }

    private JMenuItem menuItem(String text) {
        return Arrays.stream(menuItems()).filter(item -> text.equals(item.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("menu item not found: " + text));
    }

    private JPopupMenu hamburgerPopup() {
        JButton hamburger = find(viewer, JButton.class).stream().filter(b -> "☰".equals(b.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("hamburger button not found"));
        return (JPopupMenu) hamburger.getComponentPopupMenu();
    }

    private JMenuItem matchesItem() {
        return Arrays.stream(menuItems()).filter(item -> item.getText().startsWith("Show "))
                .findFirst().orElseThrow(() -> new AssertionError("Matches menu item not found"));
    }

    private JMenuItem selectIndexItem() {
        return Arrays.stream(menuItems()).filter(item -> item.getText().startsWith("Scope: index and filters"))
                .findFirst().orElseThrow(() -> new AssertionError("Scope menu item not found"));
    }

    private JMenuItem helpItem() {
        return Arrays.stream(menuItems()).filter(item -> item.getText().startsWith("Keyboard help")).findFirst()
                .orElseThrow(() -> new AssertionError("Keyboard help menu item not found"));
    }

    private JMenuItem[] menuItems() {
        JButton hamburger = find(viewer, JButton.class).stream().filter(b -> "☰".equals(b.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("hamburger button not found"));
        return Arrays.stream(hamburger.getComponentPopupMenu().getComponents()).filter(JMenuItem.class::isInstance)
                .map(JMenuItem.class::cast).toArray(JMenuItem[]::new);
    }

    private boolean loginOverlayShown() {
        return !find(viewer, UserLoginPanel.class).isEmpty();
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                found.add(type.cast(c));
            }
            if (c instanceof Container container) {
                found.addAll(find(container, type));
            }
        }
        return found;
    }

    private static void drainEdt() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }
}
