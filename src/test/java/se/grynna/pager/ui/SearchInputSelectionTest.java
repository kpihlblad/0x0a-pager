package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The search input follows a one-line log selection until the user types in it; a prompt opened
 * with a term keeps what was typed when it is opened again.
 */
class SearchInputSelectionTest {

    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        onEdt(() -> statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer));
    }

    @Test
    void aSelectionReplacesAnUntouchedPromptAndIsSelected() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.prefillSearchInput("timeout");

            statusBar.offerSelectionToSearchInput("QuorumCnxManager");

            assertThat(statusBar.inputField().getText()).isEqualTo("QuorumCnxManager");
            assertAllSelected("QuorumCnxManager");
        });
    }

    @Test
    void successiveSelectionsKeepUpdatingUntilTheUserTypes() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.offerSelectionToSearchInput("first");
            statusBar.offerSelectionToSearchInput("second");

            assertThat(statusBar.inputField().getText()).isEqualTo("second");
        });
    }

    @Test
    void aSelectionDoesNotOverwriteWhatTheUserTyped() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.prefillSearchInput("timeout");
            statusBar.inputField().replaceSelection("tim");

            statusBar.offerSelectionToSearchInput("QuorumCnxManager");

            assertThat(statusBar.inputField().getText()).isEqualTo("tim");
        });
    }

    @Test
    void reopeningThePromptWithAPrefillKeepsWhatWasTyped() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.prefillSearchInput("timeout");
            statusBar.inputField().replaceSelection("tim");

            statusBar.prefillSearchInput("boom");

            assertThat(statusBar.inputField().getText()).isEqualTo("tim");
            assertAllSelected("tim");
        });
    }

    @Test
    void aFreshOpenStartsUntouchedAgain() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.inputField().replaceSelection("typed");
            statusBar.setSearchInputActive(false);

            statusBar.setSearchInputActive(true);
            statusBar.offerSelectionToSearchInput("fresh");

            assertThat(statusBar.inputField().getText()).isEqualTo("fresh");
        });
    }

    @Test
    void aSelectionDoesNotOpenOrChangeAClosedPrompt() throws Exception {
        onEdt(() -> {
            statusBar.offerSelectionToSearchInput("ignored");

            assertThat(statusBar.isSearchInputActive()).isFalse();
            assertThat(statusBar.inputField().getText()).isEmpty();
        });
    }

    @Test
    void aBlankSelectionLeavesThePromptAlone() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.prefillSearchInput("timeout");

            statusBar.offerSelectionToSearchInput("   ");
            statusBar.offerSelectionToSearchInput(null);

            assertThat(statusBar.inputField().getText()).isEqualTo("timeout");
        });
    }

    private void assertAllSelected(String text) {
        assertThat(statusBar.inputField().getSelectionStart()).isZero();
        assertThat(statusBar.inputField().getSelectionEnd()).isEqualTo(text.length());
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
