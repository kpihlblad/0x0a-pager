package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;
import se.grynna.pager.splunk.SplunkLogDataProvider;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ctrl-F and {@code /} always open the search prompt pre-filled with the current search term, all
 * of it selected, in the Log view and in the Matches view; no window is shown.
 */
class SearchPromptKeysTest {
    private static final Instant ORIGIN = Instant.parse("2017-03-30T10:00:00Z");
    private static final KeyStroke CTRL_F = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);

    private LogViewer viewer;
    private StatusBar statusBar;
    private final AtomicReference<SearchSession> session = new AtomicReference<>();

    private static SearchHit hit(int seconds) {
        Instant time = ORIGIN.plusSeconds(seconds);
        return new SearchHit(time, time, "bkt", "1:" + seconds);
    }

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider provider = mock(SplunkLogDataProvider.class);
        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(provider);
        when(searchManager.scope()).thenReturn(Scope.of("web", Map.of()));
        when(searchManager.getActiveSearchSession()).thenAnswer(inv -> session.get());
        when(searchManager.latestSearchableInstant()).thenReturn(ORIGIN.plusSeconds(3600));
        when(searchManager.storeScopeTerms(any(), any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

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

            SearchSession active = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(-10), hit(10), hit(50));
            SearchSessionTestSupport.complete(active);
            session.set(active);
            statusBar.setSearchActive(true, "boom");
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    @Test
    void ctrlFInTheLogViewOpensThePromptWithTheTermSelected() throws Exception {
        pressCtrlF();

        onEdt(() -> {
            assertThat(statusBar.isSearchInputActive()).isTrue();
            assertAllSelected(statusBar.inputField(), "boom");
            assertThat(statusBar.inputHintText()).startsWith("Enter search · ").contains("F3/Shift-F3 next/prev");
        });
    }

    @Test
    void typingReplacesTheSelectedTermAndCtrlFAgainKeepsWhatWasTyped() throws Exception {
        pressCtrlF();
        onEdt(() -> {
            statusBar.inputField().replaceSelection("tim");
            assertThat(statusBar.inputField().getText()).isEqualTo("tim");
        });

        pressCtrlF();

        onEdt(() -> {
            assertThat(statusBar.isSearchInputActive()).isTrue();
            assertAllSelected(statusBar.inputField(), "tim");
        });
    }

    @Test
    void ctrlFInTheMatchesViewOpensThePromptAndKeepsTheView() throws Exception {
        onEdt(() -> {
            // The first visible hit anchors the Matches view; there is no painted page to derive it from
            java.lang.reflect.Field anchor = LogViewer.class.getDeclaredField("firstVisibleSearchHit");
            anchor.setAccessible(true);
            anchor.set(viewer, hit(10));
            viewer.toggleMatches();
            assertThat(viewer.isMatchesView()).isTrue();
        });

        pressCtrlF();

        onEdt(() -> {
            assertThat(viewer.isMatchesView()).as("the new search keeps the Matches view").isTrue();
            assertThat(statusBar.isSearchInputActive()).isTrue();
            assertAllSelected(statusBar.inputField(), "boom");
        });
    }

    @Test
    void slashOpensThePromptTheSameWay() throws Exception {
        fire(KeyStroke.getKeyStroke(Character.valueOf('/'), 0));

        onEdt(() -> {
            assertThat(statusBar.isSearchInputActive()).isTrue();
            assertAllSelected(statusBar.inputField(), "boom");
        });
    }

    @Test
    void theSearchButtonOpensThePromptToo() throws Exception {
        onEdt(() -> {
            JButton button = find(statusBar, JButton.class).stream()
                    .filter(b -> b.getIcon() != null && b.getText().isEmpty() && b.getActionListeners().length > 0)
                    .findFirst().orElseThrow();
            Action action = viewer.getActionMap().get(Actions.SEARCH);
            assertThat(action).isNotNull();
            action.actionPerformed(new ActionEvent(button, ActionEvent.ACTION_PERFORMED, "click"));
        });
        flush();

        onEdt(() -> {
            assertThat(statusBar.isSearchInputActive()).isTrue();
            assertAllSelected(statusBar.inputField(), "boom");
        });
    }

    private static void assertAllSelected(JTextField field, String text) {
        assertThat(field.getText()).isEqualTo(text);
        assertThat(field.getSelectionStart()).isEqualTo(0);
        assertThat(field.getSelectionEnd()).isEqualTo(field.getText().length());
    }

    private void pressCtrlF() throws Exception {
        fire(CTRL_F);
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
        flush();
    }

    private static void flush() throws Exception {
        onEdt(() -> {});
        onEdt(() -> {});
    }

    private static List<Component> flatten(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            found.add(c);
            if (c instanceof Container container) {
                found.addAll(flatten(container));
            }
        }
        return found;
    }

    private static <T> List<T> find(Container root, Class<T> type) {
        return flatten(root).stream().filter(type::isInstance).map(type::cast).toList();
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
