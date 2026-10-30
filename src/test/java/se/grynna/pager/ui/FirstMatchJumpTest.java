package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a fresh search may jump to its first match at/after the origin: only
 * once the searched window covers [origin, candidate], or when it has finished.
 */
class FirstMatchJumpTest {

    private static final Instant ORIGIN = Instant.parse("2024-03-30T10:00:00Z");

    private static Instant at(int seconds) {
        return ORIGIN.plusSeconds(seconds);
    }

    private static SearchHit hit(int seconds, String cd) {
        Instant time = at(seconds);
        return new SearchHit(time, time, "bkt", cd);
    }

    private static SearchHit hit(int seconds) {
        return hit(seconds, "1:" + seconds);
    }

    private static Optional<SearchHit> decide(SearchSession session) {
        return FirstMatchJump.decide(session.snapshot(), ORIGIN);
    }

    @Test
    void runningAndWindowNotCoveredWaits() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(10));

        assertThat(decide(session)).isEmpty();
    }

    @Test
    void runningAndWindowCoversOriginToCandidateJumpsEarly() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(10), hit(20));
        SearchSessionTestSupport.coverWindow(session, at(-60), at(30));

        assertThat(decide(session)).contains(hit(10));
    }

    @Test
    void runningAndWindowStartsAfterOriginWaits() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(10));
        SearchSessionTestSupport.coverWindow(session, at(5), at(30));

        assertThat(decide(session)).isEmpty();
    }

    @Test
    void runningAndWindowEndsBeforeCandidateWaits() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(30));
        SearchSessionTestSupport.coverWindow(session, at(-60), at(20));

        assertThat(decide(session)).isEmpty();
    }

    @Test
    void completedJumpsToFirstHitAtOrAfterOrigin() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(-10), hit(10), hit(20));
        SearchSessionTestSupport.complete(session);

        assertThat(decide(session)).contains(hit(10));
    }

    @Test
    void completedWithOnlyEarlierHitsLandsOnTheLastOneBeforeOrigin() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(-30), hit(-10));
        SearchSessionTestSupport.complete(session);

        assertThat(decide(session)).contains(hit(-10));
    }

    @Test
    void completedWithoutHitsStaysPut() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN);
        SearchSessionTestSupport.complete(session);

        assertThat(decide(session)).isEmpty();
    }

    @Test
    void hitExactlyAtOriginIsChosenEvenWhenItDisplaysAnEarlierSourceTime() {
        // Source timestamps can precede Splunk time; the probe must still sort first.
        SearchHit atOrigin = new SearchHit(ORIGIN, ORIGIN.minusSeconds(30), "bkt", "1:0");
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, atOrigin, hit(10));
        SearchSessionTestSupport.coverWindow(session, ORIGIN, at(10));

        assertThat(decide(session)).contains(atOrigin);
    }

    @Test
    void earliestHitAtTheSameInstantWinsByComparatorOrder() {
        SearchHit second = hit(0, "1:2");
        SearchHit first = hit(0, "1:1");
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, second, first);
        SearchSessionTestSupport.complete(session);

        assertThat(decide(session)).contains(first);
    }

    @Test
    void cancelledBehavesLikeCompleted() {
        SearchSession after = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(10));
        SearchSessionTestSupport.cancel(after);
        assertThat(decide(after)).contains(hit(10));

        SearchSession before = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(-10), hit(-20));
        SearchSessionTestSupport.cancel(before);
        assertThat(decide(before)).contains(hit(-10));
    }

    @Test
    void erroredSearchNeverMovesTheView() {
        SearchSession session = SearchSessionTestSupport.sessionWith("boom", ORIGIN, hit(10));
        SearchSessionTestSupport.coverWindow(session, at(-60), at(30));
        SearchSessionTestSupport.fail(session, "boom");

        assertThat(decide(session)).isEmpty();
    }
}
