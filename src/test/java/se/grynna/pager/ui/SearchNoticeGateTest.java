package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * One end-of-search notice per search session: a finished session is announced
 * on every queued listener call, but only the first may toast.
 */
class SearchNoticeGateTest {

    @Test
    void completedSessionNotifiesOnceEvenWhenPublishedSeveralTimes() {
        var gate = new LogViewer.SearchNoticeGate();
        SearchSession session = mock(SearchSession.class);

        // Side completion, final completion and two queued EDT updates all see COMPLETED.
        boolean[] notices = new boolean[4];
        for (int i = 0; i < notices.length; i++) {
            notices[i] = gate.first(session);
        }

        assertThat(notices).containsExactly(true, false, false, false);
    }

    @Test
    void newSessionGetsItsOwnNotice() {
        var gate = new LogViewer.SearchNoticeGate();
        SearchSession first = mock(SearchSession.class);
        SearchSession second = mock(SearchSession.class);

        assertThat(gate.first(first)).isTrue();
        assertThat(gate.first(first)).isFalse();
        assertThat(gate.first(second)).isTrue();
        assertThat(gate.first(second)).isFalse();
    }

    @Test
    void nullSessionNeverNotifies() {
        assertThat(new LogViewer.SearchNoticeGate().first(null)).isFalse();
    }
}
