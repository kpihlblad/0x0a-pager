package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Copy all and Export… chrome buttons of the Bookmarks view, with a mocked viewer (no window). */
class StatusBarBookmarksChromeTest {

    private LogViewer viewer;
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities
                .invokeAndWait(() -> statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer));
    }

    @Test
    void theButtonsAreShownOnlyInTheBookmarksView() throws Exception {
        onEdt(() -> {
            assertThat(visible("Copy all")).isFalse();
            assertThat(visible("Export\u2026")).isFalse();

            statusBar.setBookmarkCount(2);
            statusBar.setBookmarksMode(true);
            assertThat(visible("Copy all")).isTrue();
            assertThat(visible("Export\u2026")).isTrue();

            statusBar.setBookmarksMode(false);
            assertThat(visible("Copy all")).isFalse();
            assertThat(visible("Export\u2026")).isFalse();
        });
    }

    @Test
    void theButtonsAreDisabledWithoutBookmarksAndEnabledAfterOne() throws Exception {
        onEdt(() -> {
            statusBar.setBookmarksMode(true);
            assertThat(button("Copy all").isEnabled()).isFalse();
            assertThat(button("Export\u2026").isEnabled()).isFalse();
            assertThat(button("Copy all").isVisible()).isTrue();

            statusBar.setBookmarkCount(1);
            assertThat(button("Copy all").isEnabled()).isTrue();
            assertThat(button("Export\u2026").isEnabled()).isTrue();

            statusBar.setBookmarkCount(0);
            assertThat(button("Copy all").isEnabled()).isFalse();
            assertThat(button("Export\u2026").isEnabled()).isFalse();
        });
    }

    @Test
    void theTooltipsDescribeTheActions() throws Exception {
        onEdt(() -> {
            assertThat(button("Copy all").getToolTipText()).isEqualTo("Copy all bookmarks as shown");
            assertThat(button("Export\u2026").getToolTipText()).isEqualTo("Export the bookmarks to a file");
        });
    }

    @Test
    void theButtonsStayShownWhileAYesNoPromptIsUp() throws Exception {
        onEdt(() -> {
            statusBar.setBookmarkCount(2);
            statusBar.setBookmarksMode(true);
            statusBar.showScopeConfirmPrompt("2 bookmarks are outside the new scope. Continue?");
            assertThat(visible("Copy all")).isTrue();
            assertThat(visible("Export\u2026")).isTrue();
        });
    }

    @Test
    void theButtonsNeverTakeKeyboardFocus() throws Exception {
        onEdt(() -> {
            assertThat(button("Copy all").isFocusable()).isFalse();
            assertThat(button("Export\u2026").isFocusable()).isFalse();
        });
    }

    @Test
    void exportOpensTheExportDialogWithBookmarksPreselected() throws Exception {
        onEdt(() -> {
            statusBar.setBookmarkCount(1);
            statusBar.setBookmarksMode(true);

            button("Export\u2026").doClick();

            verify(viewer).showExportDialog(ExportSources.Source.BOOKMARKS);
        });
    }

    @Test
    void copyAllTakesTheCopyBookmarksPath() throws Exception {
        onEdt(() -> {
            statusBar.setBookmarkCount(1);
            statusBar.setBookmarksMode(true);

            button("Copy all").doClick();

            verify(viewer).copyBookmarks();
        });
    }

    private boolean visible(String text) {
        return button(text).isVisible();
    }

    private JButton button(String text) {
        Optional<JButton> found = flatten(statusBar).stream().filter(JButton.class::isInstance)
                .map(JButton.class::cast).filter(b -> text.equals(b.getText())).findFirst();
        return found.orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static java.util.List<Component> flatten(Container root) {
        java.util.List<Component> all = new java.util.ArrayList<>();
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
