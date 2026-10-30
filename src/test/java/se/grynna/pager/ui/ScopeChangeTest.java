package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeChangeTest {
    private static final Instant ORIGIN = Instant.parse("2024-03-30T10:00:00Z");

    private static SearchHit hit(int seconds) {
        Instant time = ORIGIN.plusSeconds(seconds);
        return new SearchHit(time, time, "bkt", "1:" + seconds);
    }

    private static SearchSession.Snapshot snapshot(SearchHit... hits) {
        return SearchSessionTestSupport.sessionWith("boom", ORIGIN, hits).snapshot();
    }

    private static SearchHit choose(SearchSession.Snapshot snapshot, SearchHit... preferred) {
        return ScopeChange.chooseNarrowHit(snapshot, Arrays.asList(preferred), ORIGIN);
    }

    @Test
    void firstPreferredMatchWins() {
        SearchSession.Snapshot snapshot = snapshot(hit(-50), hit(10), hit(50));
        assertThat(choose(snapshot, hit(50), hit(10))).isEqualTo(hit(50));
    }

    @Test
    void nullsAndEventsThatAreNotMatchesAreSkipped() {
        SearchSession.Snapshot snapshot = snapshot(hit(-50), hit(10), hit(50));
        assertThat(choose(snapshot, null, hit(20), hit(10))).isEqualTo(hit(10));
    }

    @Test
    void sameTimeButOtherIdentityIsNotTheMatch() {
        SearchSession.Snapshot snapshot = snapshot(hit(10));
        SearchHit sameTimeOtherEvent = new SearchHit(hit(10).splunkTime(), hit(10).displayTime(), "bkt", "other");
        assertThat(choose(snapshot, sameTimeOtherEvent)).isEqualTo(hit(10));
        assertThat(choose(snapshot(hit(10), hit(30)), sameTimeOtherEvent)).isEqualTo(hit(10));
    }

    @Test
    void withoutAUsablePreferenceTheMatchNearestAtOrAfterTheOriginIsChosen() {
        assertThat(choose(snapshot(hit(-50), hit(10), hit(50)))).isEqualTo(hit(10));
        assertThat(choose(snapshot(hit(-50), hit(-10)), new SearchHit[]{null})).isEqualTo(hit(-10));
    }

    @Test
    void noMatchesGivesNull() {
        assertThat(choose(snapshot())).isNull();
        assertThat(ScopeChange.chooseNarrowHit(snapshot(), List.of(hit(1)), null)).isNull();
    }

    @Test
    void missingOriginFallsBackToTheFirstMatch() {
        assertThat(ScopeChange.chooseNarrowHit(snapshot(hit(5), hit(9)), List.of(), null)).isEqualTo(hit(5));
    }

    @Test
    void targetIsClampedToTheLatestInstant() {
        assertThat(ScopeChange.clampToLatest(ORIGIN.plusSeconds(5), ORIGIN)).isEqualTo(ORIGIN);
        assertThat(ScopeChange.clampToLatest(ORIGIN.minusSeconds(5), ORIGIN)).isEqualTo(ORIGIN.minusSeconds(5));
    }
}
