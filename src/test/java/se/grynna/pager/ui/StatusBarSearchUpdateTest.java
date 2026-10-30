package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Streaming search updates must render through the normal prompt
 * precedence and never clobber a goto, new-search, or modal prompt.
 */
class StatusBarSearchUpdateTest {

    private static final Instant T = Instant.parse("2017-03-30T10:00:00Z");
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        onEdt(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer);
            statusBar.setSearchActive(true, "timeout");
        });
    }

    private static String time(Instant t) {
        return DateTimeFormatterManagerTestUtil.localTime(t);
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private void deliverHits(int count, boolean completed) throws Exception {
        onEdt(() -> {
            statusBar.setTextSearchPhase(completed ? SearchStatusText.Phase.COMPLETE : SearchStatusText.Phase.RUNNING);
            statusBar.setSearchPosition(null, count, null);
            statusBar.updateSearchHitCount(count, completed, T, T.plusSeconds(count));
        });
    }

    @Test
    void newSearchInputSurvivesStreamingHitsOfTheCurrentSearch() throws Exception {
        onEdt(() -> {
            statusBar.setSearchInputActive(true);
            statusBar.inputField().setText("refused");
        });

        deliverHits(5, false);

        onEdt(() -> {
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_INPUT);
            assertThat(statusBar.inputField().getText()).isEqualTo("refused");
            assertThat(statusBar.inputField().isEditable()).isTrue();
        });
    }

    @Test
    void modalPromptIsNotReplacedByStreamingHits() throws Exception {
        onEdt(() -> statusBar.setModalLogin(true));

        deliverHits(2, true);

        onEdt(() -> assertThat(statusBar.inputState()).isEqualTo(InputState.MODAL_LOGIN));
    }

    @Test
    void summaryIsShownOnceThePromptCloses() throws Exception {
        onEdt(() -> statusBar.setSearchInputActive(true));
        deliverHits(4, true);

        onEdt(() -> {
            statusBar.setSearchInputActive(false);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_ACTIVE);
        });
    }

    @Test
    void matchesModePrecedesSearchActiveInThePromptPrecedence() throws Exception {
        onEdt(() -> {
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_ACTIVE);

            statusBar.setMatchesMode(true);
            assertThat(statusBar.inputState()).isEqualTo(InputState.MATCHES);

            statusBar.setMatchesMode(false);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SEARCH_ACTIVE);
        });
    }

    @Test
    void matchesStreamingPromptSaysSearchingAndTheCenterShowsTheCoveredWindow() throws Exception {
        Instant from = T.minusSeconds(3600);
        Instant to = T.plusSeconds(7200);
        onEdt(() -> {
            statusBar.setMatchesMode(true);
            statusBar.setSearchTruncation(true, false, false);
            statusBar.updateSearchHitCount(10_000, false, from, to);
            assertThat(statusBar.inputState()).isEqualTo(InputState.MATCHES);
            assertThat(statusBar.searchPromptText()).contains("\"timeout\" — searching…").doesNotContain("10,000");
            assertThat(statusBar.searchCenterText())
                    .isEqualTo(time(T.minusSeconds(3600)) + " \u2192 " + time(T.plusSeconds(7200)))
                    .doesNotContain("10,000");
            assertThat(statusBar.viewTabs().labelOf(ViewTabs.Tab.MATCHES)).isEqualTo("MATCHES 10,000+");
            statusBar.updateSearchHitCount(10_000, true, from, to);
            assertThat(statusBar.searchPromptText()).doesNotContain("10,000").contains("\"timeout\"");
            assertThat(statusBar.searchCenterText())
                    .isEqualTo(time(T.minusSeconds(3600)) + " \u2192 " + time(T.plusSeconds(7200)));
        });
    }

    @Test
    void coveredRangeGrowsEvenBeforeAnyMatchesArriveAndSurvivesStop() throws Exception {
        onEdt(() -> {
            statusBar.setMatchesMode(true);
            statusBar.updateSearchHitCount(0, false, T, T.plusSeconds(60));
            assertThat(statusBar.searchPromptText()).contains("searching…");
            assertThat(statusBar.searchCenterText())
                    .isEqualTo(time(T) + " \u2192 " + time(T.plusSeconds(60)));
            statusBar.updateSearchHitCount(0, false, T.minusSeconds(60), T.plusSeconds(120));
            assertThat(statusBar.searchCenterText())
                    .isEqualTo(time(T.minusSeconds(60)) + " \u2192 " + time(T.plusSeconds(120)));
            statusBar.setTextSearchPhase(SearchStatusText.Phase.STOPPED);
            assertThat(statusBar.searchPromptText()).contains("stopped");
            assertThat(statusBar.searchCenterText())
                    .isEqualTo(time(T.minusSeconds(60)) + " \u2192 " + time(T.plusSeconds(120)));
        });
    }
}
