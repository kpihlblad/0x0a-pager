package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.ViewTabs.Tab;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The view tabs in the status row: the active tab, the counts and the click routing, with a mocked viewer. */
class StatusBarViewTabsTest {

    private static final Instant START = Instant.parse("2017-05-01T12:00:00Z");

    private LogViewer viewer;
    private StatusBar statusBar;
    private ViewTabs tabs;

    @BeforeEach
    void setUp() throws Exception {
        viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities.invokeAndWait(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer);
            tabs = statusBar.viewTabs();
            tabs.setSize(tabs.getPreferredSize());
        });
    }

    @Test
    void theLogTabIsActiveByDefaultAndTheTabsSitAtTheLeftOfTheStatusRow() throws Exception {
        onEdt(() -> {
            assertThat(tabs.active()).isEqualTo(Tab.LOG);
            Component firstVisible = java.util.Arrays.stream(tabs.getParent().getComponents())
                    .filter(Component::isVisible)
                    .findFirst().orElseThrow();
            assertThat(firstVisible).isSameAs(tabs);
        });
    }

    @Test
    void theModesSwitchTheActiveTab() throws Exception {
        onEdt(() -> {
            statusBar.setMatchesMode(true);
            assertThat(tabs.active()).isEqualTo(Tab.MATCHES);

            statusBar.setBookmarksMode(true);
            assertThat(tabs.active()).as("bookmarks win over matches").isEqualTo(Tab.BOOKMARKS);

            statusBar.setBookmarksMode(false);
            assertThat(tabs.active()).as("back to the matches underneath").isEqualTo(Tab.MATCHES);

            statusBar.setMatchesMode(false);
            assertThat(tabs.active()).isEqualTo(Tab.LOG);
        });
    }

    @Test
    void theHitCountAppearsOnTheMatchesTabAndClearsWithTheSearch() throws Exception {
        onEdt(() -> {
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES");

            statusBar.setSearchActive(true, "error");
            assertThat(tabs.labelOf(Tab.MATCHES)).as("no count yet").isEqualTo("MATCHES");

            statusBar.updateSearchHitCount(212, false, START, START.plusSeconds(60));
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES 212");

            statusBar.setSearchProvisional(true);
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES ~212");
            statusBar.setSearchProvisional(false);

            statusBar.setSearchTruncation(false, true, false);
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES 212+");

            statusBar.setSearchActive(false, null);
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES");
        });
    }

    @Test
    void clearSearchInfoClearsTheMatchCount() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.updateSearchHitCount(7, true, START, START.plusSeconds(60));
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES 7");

            statusBar.clearSearchInfo();

            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES");
        });
    }

    @Test
    void aZeroCountIsShown() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "nothing");
            statusBar.updateSearchHitCount(0, true, START, START.plusSeconds(60));
            assertThat(tabs.labelOf(Tab.MATCHES)).isEqualTo("MATCHES 0");
        });
    }

    @Test
    void theBookmarkCountShowsInEveryView() throws Exception {
        onEdt(() -> {
            statusBar.setBookmarkCount(3);
            assertThat(tabs.labelOf(Tab.BOOKMARKS)).isEqualTo("BOOKMARKS 3");

            statusBar.setBookmarkCount(0);
            assertThat(tabs.labelOf(Tab.BOOKMARKS)).isEqualTo("BOOKMARKS");
        });
    }

    @Test
    void clickingTheTabsCallsTheViewer() throws Exception {
        onEdt(() -> {
            click(Tab.LOG);
            verify(viewer).showLog();
            click(Tab.MATCHES);
            verify(viewer).toggleMatches();
            click(Tab.BOOKMARKS);
            verify(viewer).showBookmarks();
            click(Tab.DETAILS);
            verify(viewer).openCursorDetails();
        });
    }

    @Test
    void clickingMatchesInTheMatchesViewDoesNothing() throws Exception {
        when(viewer.isMatchesView()).thenReturn(true);
        onEdt(() -> {
            click(Tab.MATCHES);
            verify(viewer, never()).toggleMatches();
        });
    }

    private void click(Tab tab) {
        int x = tabs.tabBounds(tab).x + 3;
        tabs.dispatchEvent(new MouseEvent(tabs, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK, x, 1, 1,
                false, MouseEvent.BUTTON1));
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
            if (failure[0] instanceof Error e) throw e;
            if (failure[0] instanceof Exception e) throw e;
            throw new RuntimeException(failure[0]);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
