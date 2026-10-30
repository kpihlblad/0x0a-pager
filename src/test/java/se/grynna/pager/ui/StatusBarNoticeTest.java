package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Transient state notices in the status row: one at a time, warn in amber, cleared by
 * the timer or explicitly. Failures are toasts and never reach this path.
 */
class StatusBarNoticeTest {

    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        onEdt(() -> statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer));
    }

    @Test
    void aNoticeIsShownAndStartsTheClearTimer() throws Exception {
        onEdt(() -> {
            statusBar.showNotice("Esc again to end search", StatusBar.Severity.INFO);

            assertThat(statusBar.noticeText()).isEqualTo("Esc again to end search");
            assertThat(statusBar.noticeColor()).isEqualTo(DarculaColors.FOREGROUND);
            assertThat(statusBar.isNoticeTimerRunning()).isTrue();
        });
    }

    @Test
    void aNewNoticeReplacesThePreviousOne() throws Exception {
        onEdt(() -> {
            statusBar.showNotice("Nothing would be left", StatusBar.Severity.WARN);
            statusBar.showNotice("Already narrowed to \"boom\"", StatusBar.Severity.INFO);

            assertThat(statusBar.noticeText()).isEqualTo("Already narrowed to \"boom\"");
            assertThat(statusBar.noticeColor()).isEqualTo(DarculaColors.FOREGROUND);
        });
    }

    @Test
    void warnNoticesAreAmber() throws Exception {
        onEdt(() -> {
            statusBar.showNotice("No matches for \"boom\"", StatusBar.Severity.WARN);

            assertThat(statusBar.noticeColor()).isEqualTo(DarculaColors.ORANGE);
        });
    }

    @Test
    void clearNoticeRemovesTheTextAndStopsTheTimer() throws Exception {
        onEdt(() -> {
            statusBar.showNotice("Preferences saved.", StatusBar.Severity.INFO);

            statusBar.clearNotice();

            assertThat(statusBar.noticeText()).isEmpty();
            assertThat(statusBar.isNoticeTimerRunning()).isFalse();
        });
    }

    @Test
    void aNoticeClearsItselfAfterTheDelay() throws Exception {
        onEdt(() -> statusBar.showNotice("Successfully connected to Splunk", StatusBar.Severity.INFO));

        await().atMost(Duration.ofMillis(StatusBar.NOTICE_MILLIS + 3000L))
                .until(() -> statusBar.noticeText().isEmpty());
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
