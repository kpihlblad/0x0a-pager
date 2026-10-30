package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchManager;
import se.grynna.pager.splunk.SplunkLogDataProvider;
import se.grynna.pager.ui.login.UserLoginPanel;
import se.grynna.pager.ui.time.TimePanel;

import javax.swing.*;
import java.awt.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The modal-login state follows the login overlay's lifecycle, so a cancelled
 * session recovery does not leave the search prompt and the time panel suppressed.
 */
class LoginOverlayLifecycleTest {

    private LogViewer viewer;
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SplunkLogDataProvider provider = mock(SplunkLogDataProvider.class);
        SearchManager searchManager = mock(SearchManager.class);
        when(searchManager.getDataProvider()).thenReturn(provider);

        onEdt(() -> {
            viewer = new LogViewer(Clock.systemUTC());
            viewer.setSize(1024, 768);
            viewer.doLayout();
            // Browsing: a real (non-Mood) provider is active
            viewer.loginComplete(searchManager);
            statusBar = find(viewer, StatusBar.class).getFirst();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        drainEdt();
    }

    @Test
    void cancelledRecoveryWhileBrowsingLeavesSearchPromptAndTimePanelUsable() throws Exception {
        CompletableFuture<String> password = viewer.handleRelogin("Session expired");
        drainEdt();
        onEdt(() -> assertThat(statusBar.isModalLogin()).isTrue());

        onEdt(() -> cancelButton().doClick());
        drainEdt();

        assertThatThrownBy(() -> password.get(5, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);

        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).isFalse();
            assertThat(find(viewer, UserLoginPanel.class)).isEmpty();

            viewer.setSearchInputActive(true);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_INPUT);
            viewer.setSearchInputActive(false);

            viewer.openTimePanel(TimePanel.Focus.AT);
            assertThat(viewer.isTimePanelOpen()).isTrue();
        });
    }

    @Test
    void unreachablePromptOffersRetryWithoutAPasswordAndCancelReleasesIt() throws Exception {
        CompletableFuture<Void> retry = viewer.handleUnreachable("No route to host");
        drainEdt();
        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).isTrue();
            UserLoginPanel panel = find(viewer, UserLoginPanel.class).getFirst();
            assertThat(find(panel, JButton.class)).extracting(JButton::getText).contains("retry", "cancel");
            assertThat(find(panel, JPasswordField.class)).singleElement()
                    .satisfies(field -> assertThat(field.isEditable()).isFalse());
        });

        onEdt(() -> cancelButton().doClick());
        drainEdt();

        assertThatThrownBy(() -> retry.get(5, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        onEdt(() -> assertThat(find(viewer, UserLoginPanel.class)).isEmpty());
    }

    @Test
    void abandonedOverlayClearsModalStateWhenPasswordRequestIsWithdrawn() throws Exception {
        CompletableFuture<String> password = viewer.handleRelogin("Session expired");
        drainEdt();
        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).isTrue();
            assertThat(find(viewer, UserLoginPanel.class)).hasSize(1);
        });

        // e.g. the recovery leader was interrupted by a superseding load
        password.completeExceptionally(new CancellationException("Session recovery interrupted"));
        drainEdt();

        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).isFalse();
            assertThat(find(viewer, UserLoginPanel.class)).isEmpty();
            viewer.openTimePanel(TimePanel.Focus.AT);
            assertThat(viewer.isTimePanelOpen()).isTrue();
        });
    }

    @Test
    void abandonedOverlayCleanupDoesNotDismissNewerOverlay() throws Exception {
        CompletableFuture<String> first = viewer.handleRelogin("Session expired");
        drainEdt();
        CompletableFuture<String> second = viewer.handleRelogin("Session expired again");
        drainEdt();
        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).isTrue();
            assertThat(find(viewer, UserLoginPanel.class)).hasSize(1);
        });

        first.completeExceptionally(new CancellationException("withdrawn"));
        drainEdt();

        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).as("newer overlay still modal").isTrue();
            assertThat(find(viewer, UserLoginPanel.class)).as("newer overlay still shown").hasSize(1);
        });
        assertThat(second).isNotDone();

        second.completeExceptionally(new CancellationException("withdrawn"));
        drainEdt();
        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).isFalse();
            assertThat(find(viewer, UserLoginPanel.class)).isEmpty();
        });
    }

    @Test
    void withdrawnRequestNeverShowsOverlay() throws Exception {
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(edtBlocked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            // The overlay task is queued behind the blocked EDT; withdraw the request first
            CompletableFuture<String> password = viewer.handleRelogin("Session expired");
            password.completeExceptionally(new CancellationException("withdrawn"));
        } finally {
            release.countDown();
        }
        drainEdt();

        onEdt(() -> {
            assertThat(statusBar.isModalLogin()).isFalse();
            assertThat(find(viewer, UserLoginPanel.class)).isEmpty();
        });
    }

    private JButton cancelButton() {
        return find(viewer, UserLoginPanel.class).stream().flatMap(p -> find(p, JButton.class).stream())
                .filter(b -> "cancel".equals(b.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("login overlay is not shown"));
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
        // Two passes: callbacks queued by the first pass run in the second
        onEdt(() -> {});
        onEdt(() -> {});
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }
}
