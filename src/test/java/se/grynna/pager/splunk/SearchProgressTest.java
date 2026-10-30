package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchProgressTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void waitingForSplunkRetainsTheLoadWindowAndDirection(int direction) {
        Instant start = Instant.parse("2017-03-30T10:00:00.123456789Z");
        Instant end = start.plusSeconds(60);
        LoadDecision decision = LoadDecision.load(null, direction, start, end, "test load");

        SearchProgress progress = SearchProgress.waitingForSplunk(decision);

        assertThat(progress.statusText()).isEqualTo("Waiting for Splunk");
        assertThat(progress.isWaitingForSplunk()).isTrue();
        assertThat(progress.indeterminate()).isTrue();
        assertThat(progress.isDone()).isFalse();
        assertThat(progress.isReady()).isFalse();
        assertThat(progress.isFailed()).isFalse();
        assertThat(progress.isFinalized()).isFalse();
        assertThat(progress.direction()).isEqualTo(direction);
        assertThat(progress.start()).isEqualTo(start);
        assertThat(progress.end()).isEqualTo(end);
        assertThat(progress.doneProgress()).isZero();
        assertThat(progress.scanCount()).isZero();
        assertThat(progress.eventCount()).isZero();
        assertThat(progress.resultCount()).isZero();
        assertThat(progress.resultPreviewCount()).isZero();
        assertThat(progress.runDuration()).isZero();
    }

    @Test
    void normalAndFailedProgressAreNotWaitingForSplunk() {
        SearchProgress running = new SearchProgress("sid", null, null, true, false, false, false, 0, 0, 0, 0,
                0.0f, 0.0f, "Running", false, 1);

        assertThat(running.isWaitingForSplunk()).isFalse();
        assertThat(SearchProgress.FAIL.isWaitingForSplunk()).isFalse();
    }
}
