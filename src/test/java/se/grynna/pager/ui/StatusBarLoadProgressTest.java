package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.LoadDecision;
import se.grynna.pager.splunk.SearchProgress;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusBarLoadProgressTest {

    private static final Instant T = Instant.parse("2017-03-30T10:00:00Z");
    private static final SearchProgress WAITING = SearchProgress.waitingForSplunk(
            LoadDecision.load(null, -1, T, T.plusSeconds(60), "test load"));
    private StatusBar statusBar;

    @BeforeEach
    void setUp() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        LogViewer viewer = mock(LogViewer.class);
        when(viewer.getActionMap()).thenReturn(new ActionMap());
        when(viewer.getState()).thenReturn(InputState.SCROLL);
        onEdt(() -> {
            statusBar = new StatusBar(new Font(Font.MONOSPACED, Font.PLAIN, 12), viewer);
            statusBar.updateState();
        });
    }

    @Test
    void waitingIsVisibleBesideAnIndeterminateLoadSpinner() throws Exception {
        onEdt(() -> {
            statusBar.setBrowsingRange(T, T.plusSeconds(3600));
            String range = statusBar.searchCenterText();

            statusBar.updateSearchProgress(WAITING);

            assertThat(statusBar.loadProgressLabel().isVisible()).isTrue();
            assertThat(statusBar.loadProgressLabel().getText()).isEqualTo("Waiting for Splunk");
            assertThat(statusBar.loadSpinner().isVisible()).isTrue();
            assertThat(statusBar.loadSpinner().isIndeterminate()).isTrue();
            assertThat(statusBar.loadSpinner().getIcon()).isNotNull();
            assertThat(statusBar.searchCenterText()).isEqualTo(range);
            assertThat(statusBar.inputState()).isEqualTo(InputState.SCROLL);
            statusBar.updateSearchProgress(null);
        });
    }

    @ParameterizedTest
    @EnumSource(NextProgress.class)
    void subsequentProgressClearsTheWaitingMessage(NextProgress next) throws Exception {
        onEdt(() -> {
            statusBar.updateSearchProgress(WAITING);

            statusBar.updateSearchProgress(next.progress());

            assertThat(statusBar.loadProgressLabel().isVisible()).isFalse();
            assertThat(statusBar.loadProgressLabel().getText()).isEmpty();
            assertThat(statusBar.loadSpinner().isVisible()).isEqualTo(next == NextProgress.RUNNING);
            assertThat(statusBar.loadSpinner().isIndeterminate()).isFalse();
            if (next == NextProgress.RUNNING) {
                assertThat(statusBar.loadSpinner().getValue()).isEqualTo(25);
            } else {
                assertThat(statusBar.loadSpinner().getIcon()).isNull();
            }
            statusBar.updateSearchProgress(null);
        });
    }

    @ParameterizedTest
    @EnumSource(value = InputState.class, names = {"SEARCH_INPUT"})
    void waitingAndClearingNeverReplaceTypedInput(InputState prompt) throws Exception {
        onEdt(() -> {
            statusBar.setSearchActive(true, "timeout");
            statusBar.setSearchInputActive(true);
            statusBar.inputField().setText("unfinished input");
            String promptText = statusBar.searchPromptText();

            for (SearchProgress progress : new SearchProgress[]{WAITING, NextProgress.RUNNING.progress(),
                    WAITING, NextProgress.DONE.progress(), WAITING, null}) {
                statusBar.updateSearchProgress(progress);
                statusBar.updateSearchHitCount(3, false, T, T.plusSeconds(60));

                assertThat(statusBar.inputState()).isEqualTo(prompt);
                assertThat(statusBar.searchPromptText()).isEqualTo(promptText);
                assertThat(statusBar.inputField().getText()).isEqualTo("unfinished input");
                assertThat(statusBar.inputField().isEditable()).isTrue();
            }
            statusBar.setTextSearchPhase(SearchStatusText.Phase.COMPLETE);
        });
    }

    @ParameterizedTest
    @EnumSource(value = InputState.class, names = {"MODAL_LOGIN", "MODAL_INDEX", "SCOPE_CONFIRM",
            "BOUNDARY_EXPANSION", "SEARCH_EXTEND"})
    void waitingAndClearingPreserveHigherPriorityPrompts(InputState prompt) throws Exception {
        onEdt(() -> {
            BoundaryRequest request = new BoundaryRequest(BoundaryType.BEFORE, T.minusSeconds(3600),
                    (type, accepted, boundary) -> {});
            switch (prompt) {
                case MODAL_LOGIN -> statusBar.setModalLogin(true);
                case MODAL_INDEX -> statusBar.setModalIndex(true);
                case SCOPE_CONFIRM -> statusBar.showScopeConfirmPrompt("Change scope?");
                case BOUNDARY_EXPANSION -> statusBar.showBoundaryExpansionPrompt(request);
                case SEARCH_EXTEND -> statusBar.showSearchExtendPrompt(request, "earlier");
                default -> throw new AssertionError(prompt);
            }
            String promptText = statusBar.searchPromptText();
            String buttonText = statusBar.boundaryPromptText();

            statusBar.updateSearchProgress(WAITING);
            assertThat(statusBar.loadProgressLabel().isVisible()).isTrue();
            assertThat(statusBar.inputState()).isEqualTo(prompt);
            assertThat(statusBar.searchPromptText()).isEqualTo(promptText);
            assertThat(statusBar.boundaryPromptText()).isEqualTo(buttonText);
            statusBar.updateSearchProgress(null);

            assertThat(statusBar.inputState()).isEqualTo(prompt);
            assertThat(statusBar.searchPromptText()).isEqualTo(promptText);
            assertThat(statusBar.boundaryPromptText()).isEqualTo(buttonText);
        });
    }

    @Test
    void loadProgressDoesNotReplaceOrClearATransientNotice() throws Exception {
        onEdt(() -> {
            statusBar.showNotice("Preferences saved", StatusBar.Severity.INFO);
            statusBar.updateSearchProgress(WAITING);
            statusBar.updateSearchProgress(null);

            assertThat(statusBar.noticeText()).isEqualTo("Preferences saved");
            assertThat(statusBar.isNoticeTimerRunning()).isTrue();
            statusBar.clearNotice();
        });
    }

    @Test
    void progressUpdatesRequireTheEdt() {
        assertThatThrownBy(() -> statusBar.updateSearchProgress(WAITING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Event Dispatch Thread");
    }

    private static void onEdt(Runnable task) throws Exception {
        SwingUtilities.invokeAndWait(task);
    }

    private enum NextProgress {
        RUNNING, DONE, FAILED, CLEARED;

        SearchProgress progress() {
            return switch (this) {
                case RUNNING, DONE -> new SearchProgress("sid", T, T.plusSeconds(60), false, true,
                        this == DONE, false, 0, 0, 0, 0, 0.25f, 0.0f, "Running", false, 1);
                case FAILED -> SearchProgress.FAIL;
                case CLEARED -> null;
            };
        }
    }
}
