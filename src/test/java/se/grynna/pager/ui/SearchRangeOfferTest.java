package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.splunk.SearchSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRangeOfferTest {

    private static final Instant DAY = Instant.parse("2017-06-01T00:00:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2017-06-02T00:00:00Z");
    private static final Instant LATER = Instant.parse("2017-06-10T00:00:00Z");

    @Test
    void finishedSearchAsksToExtendLaterByADay() {
        SearchRangeOffer.Decision decision = SearchRangeOffer.decide(SearchNavigation.Edge.LATER,
                SearchSession.State.COMPLETED, DAY, NEXT_DAY, SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1,
                SearchNavigation.Edge.NONE);

        assertThat(decision.ask()).isTrue();
        assertThat(decision.direction()).isEqualTo(BoundaryType.AFTER);
        assertThat(decision.suggested()).isEqualTo(Instant.parse("2017-06-03T00:00:00Z"));
    }

    @Test
    void finishedSearchAsksToExtendEarlier() {
        SearchRangeOffer.Decision decision = SearchRangeOffer.decide(SearchNavigation.Edge.EARLIER,
                SearchSession.State.COMPLETED, NEXT_DAY, LATER, SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1,
                SearchNavigation.Edge.NONE);

        assertThat(decision.ask()).isTrue();
        assertThat(decision.direction()).isEqualTo(BoundaryType.BEFORE);
        assertThat(decision.suggested()).isEqualTo(DAY);
    }

    @Test
    void runningOrStoppedOrDeclinedOrAlreadyAtTheLimitDoesNotAsk() {
        assertThat(offer(SearchSession.State.RUNNING, SearchNavigation.Edge.NONE).ask()).isFalse();
        assertThat(offer(SearchSession.State.CANCELLED, SearchNavigation.Edge.NONE).ask()).isFalse();
        assertThat(offer(SearchSession.State.COMPLETED, SearchNavigation.Edge.LATER).ask()).isFalse();
        assertThat(SearchRangeOffer.decide(SearchNavigation.Edge.LATER, SearchSession.State.COMPLETED, DAY, LATER,
                SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1, SearchNavigation.Edge.NONE).ask()).isFalse();
    }

    @Test
    void truncatedEdgeOffersRecentreForCompletedAndCancelled() {
        SearchRangeOffer.Decision laterCompleted = SearchRangeOffer.decide(SearchNavigation.Edge.LATER,
                SearchSession.State.COMPLETED, DAY, NEXT_DAY, SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1,
                SearchNavigation.Edge.NONE, false, true);
        assertThat(laterCompleted.ask()).isTrue();
        assertThat(laterCompleted.kind()).isEqualTo(SearchRangeOffer.Kind.RECENTRE);
        assertThat(laterCompleted.suggested()).isEqualTo(NEXT_DAY);

        SearchRangeOffer.Decision earlierCompleted = SearchRangeOffer.decide(SearchNavigation.Edge.EARLIER,
                SearchSession.State.COMPLETED, NEXT_DAY, LATER, SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1,
                SearchNavigation.Edge.NONE, true, false);
        assertThat(earlierCompleted.ask()).isTrue();
        assertThat(earlierCompleted.kind()).isEqualTo(SearchRangeOffer.Kind.RECENTRE);
        assertThat(earlierCompleted.suggested()).isEqualTo(NEXT_DAY);

        SearchRangeOffer.Decision laterCancelled = SearchRangeOffer.decide(SearchNavigation.Edge.LATER,
                SearchSession.State.CANCELLED, DAY, NEXT_DAY, SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1,
                SearchNavigation.Edge.NONE, false, true);
        assertThat(laterCancelled.ask()).isTrue();
        assertThat(laterCancelled.kind()).isEqualTo(SearchRangeOffer.Kind.RECENTRE);
    }

    @Test
    void nonTruncatedUnchangedAndDeclinedSuppressesRecentre() {
        SearchRangeOffer.Decision extend = SearchRangeOffer.decide(SearchNavigation.Edge.LATER,
                SearchSession.State.COMPLETED, DAY, NEXT_DAY, SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1,
                SearchNavigation.Edge.NONE, false, false);
        assertThat(extend.ask()).isTrue();
        assertThat(extend.kind()).isEqualTo(SearchRangeOffer.Kind.EXTEND);

        assertThat(SearchRangeOffer.decide(SearchNavigation.Edge.LATER, SearchSession.State.COMPLETED, DAY, NEXT_DAY,
                SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1, SearchNavigation.Edge.LATER, false, true).ask())
                .isFalse();
    }

    @Test
    void searchStatusDistinguishesRunningStoppedAndComplete() {
        assertThat(SearchStatusText.summary(null, 0, SearchStatusText.Phase.RUNNING, null)).isEqualTo("searching…");
        assertThat(SearchStatusText.summary(null, 4, SearchStatusText.Phase.RUNNING, null)).isEqualTo("searching…");
        assertThat(SearchStatusText.summary(null, 4, SearchStatusText.Phase.COMPLETE, null)).isEmpty();
        assertThat(SearchStatusText.summary(null, 4, SearchStatusText.Phase.STOPPED, null)).isEqualTo("stopped");
        assertThat(SearchStatusText.summary(12, 12, SearchStatusText.Phase.COMPLETE, "search stopped"))
                .isEqualTo("12 of 12 · search stopped");
    }

    private static SearchRangeOffer.Decision offer(SearchSession.State state, SearchNavigation.Edge declined) {
        return SearchRangeOffer.decide(SearchNavigation.Edge.LATER, state, DAY, NEXT_DAY,
                SearchBoundaries.MIN_MINIMUM_BOUNDARY, LATER, 1, declined);
    }
}
