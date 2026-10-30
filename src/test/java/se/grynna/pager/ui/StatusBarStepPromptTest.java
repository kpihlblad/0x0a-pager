package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Y/N button reads the step first ({@code 1h earlier (10:00:00)?}, {@code back to 1971?},
 * {@code up to now?}) and its tooltip carries the absolute suggested time; a recentre offers the absolute endpoint.
 */
class StatusBarStepPromptTest {

    private static final Instant NOW = Instant.parse("2017-05-01T12:00:00Z");
    private static final Instant T11 = Instant.parse("2017-05-01T11:00:00Z");
    private static final Instant T1130 = Instant.parse("2017-05-01T11:30:00Z");
    private static final Instant T1145 = Instant.parse("2017-05-01T11:45:00Z");
    private static final Instant T10 = Instant.parse("2017-05-01T10:00:00Z");
    private static final Instant APR28 = Instant.parse("2017-04-28T09:00:00Z");
    private static final Instant MIN = SearchBoundaries.MIN_MINIMUM_BOUNDARY;

    private final AtomicLong clock = new AtomicLong(1_000);
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        SwingUtilities.invokeAndWait(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 14), viewer, clock::get, () -> NOW);
            statusBar.setFirstVisibleTimestamp(T1130);
        });
    }

    @Test
    void searchExtendEarlierReadsTheDeltaFirstFromTheWindowStart() throws Exception {
        onEdt(() -> {
            showSearchWithWindow(T11, T1145);
            statusBar.showSearchExtendPrompt(request(BoundaryType.BEFORE, T10), "earlier");
            assertThat(statusBar.boundaryPromptText()).isEqualTo("1h earlier (10:00:00)?");
            assertThat(statusBar.boundaryPromptTooltip()).isEqualTo(DateTimeFormatterManager.formatTimestamp(T10));
        });
    }

    @Test
    void searchExtendLaterFromTheWindowEndReadsUpToNow() throws Exception {
        onEdt(() -> {
            showSearchWithWindow(T11, T1145);
            statusBar.showSearchExtendPrompt(request(BoundaryType.AFTER, NOW), "later");
            assertThat(statusBar.boundaryPromptText()).isEqualTo("up to now?");
        });
    }

    @Test
    void searchExtendWithoutAWindowFallsBackToTheSuggestedEndpoint() throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "error");
            statusBar.showSearchExtendPrompt(request(BoundaryType.BEFORE, T10), "earlier", "error");
            assertThat(statusBar.boundaryPromptText()).isEqualTo(time(T10) + "?");
        });
    }

    @Test
    void boundaryExpansionToTheMinimumReadsBackTo1971() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.showBoundaryExpansionPrompt(request(BoundaryType.BEFORE, MIN));
            assertThat(statusBar.boundaryPromptText()).isEqualTo("back to 1971?");
            assertThat(statusBar.boundaryPromptTooltip()).isEqualTo(DateTimeFormatterManager.formatTimestamp(MIN));
        });
    }

    @Test
    void boundaryExpansionLaterReadsTheDeltaFromTheBrowsingEnd() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T11, T1145);
            statusBar.showBoundaryExpansionPrompt(request(BoundaryType.AFTER, NOW));
            assertThat(statusBar.boundaryPromptText()).isEqualTo("up to now?");
        });
    }

    @Test
    void boundaryExpansionWithoutABrowsingRangeFallsBackToTheSuggestedEndpoint() throws Exception {
        onEdt(() -> {
            statusBar.showBoundaryExpansionPrompt(request(BoundaryType.BEFORE, APR28));
            assertThat(statusBar.boundaryPromptText()).isEqualTo(dateMinute(APR28) + "?");
        });
    }

    @Test
    void recentreOffersTheAbsoluteEndpointWithoutADelta() throws Exception {
        onEdt(() -> {
            showSearchWithWindow(T11, T1145);
            statusBar.showSearchRecentrePrompt(request(BoundaryType.BEFORE, APR28));
            assertThat(statusBar.boundaryPromptText()).isEqualTo(dateMinute(APR28) + "?");
            assertThat(statusBar.boundaryPromptTooltip()).isEqualTo(DateTimeFormatterManager.formatTimestamp(APR28));
        });
    }

    private void showSearchWithWindow(Instant start, Instant end) {
        statusBar.setSearchActive(true, "error");
        statusBar.updateSearchHitCount(5, true, start, end);
    }

    private static BoundaryRequest request(BoundaryType type, Instant suggested) {
        return new BoundaryRequest(type, suggested, (t, accepted, boundary) -> {});
    }

    private static String time(Instant t) {
        return DateTimeFormatterManagerTestUtil.localTime(t);
    }

    private static String dateMinute(Instant t) {
        return DateTimeFormatterManagerTestUtil.localDateMinute(t);
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
            if (failure[0] instanceof Exception e) {
                throw e;
            }
            throw new AssertionError(failure[0]);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
