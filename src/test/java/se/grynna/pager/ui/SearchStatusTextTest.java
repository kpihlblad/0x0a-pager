package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchStatusTextTest {

    @Test
    void truncatedCountUsesPlusAndEdgeNote() {
        assertThat(SearchStatusText.summary(null, 10000, SearchStatusText.Phase.COMPLETE, null, true, false))
                .as("the count belongs to the Matches tab").isEmpty();
        assertThat(SearchStatusText.summary(5000, 10000, SearchStatusText.Phase.COMPLETE, null, true, false))
                .isEqualTo("5000 of 10,000+");
        assertThat(SearchStatusText.summary(null, 10000, SearchStatusText.Phase.COMPLETE,
                "match limit reached — earlier matches not searched", true, false))
                .contains("match limit reached");
    }

    @Test
    void truncatedCountsGainAPlusInEveryPhase() {
        assertThat(SearchStatusText.summary(7, 10000, SearchStatusText.Phase.COMPLETE, null, true, false))
                .isEqualTo("7 of 10,000+");
        assertThat(SearchStatusText.summary(7, 9000, SearchStatusText.Phase.RUNNING, null, true, false))
                .isEqualTo("7 of 9,000+");
        assertThat(SearchStatusText.summary(7, 9000, SearchStatusText.Phase.STOPPED, null, true, false))
                .isEqualTo("7 of 9,000+ · stopped");
        assertThat(SearchStatusText.summary(null, 9000, SearchStatusText.Phase.RUNNING, null, true, false))
                .isEqualTo("searching…");
        assertThat(SearchStatusText.summary(null, 9000, SearchStatusText.Phase.STOPPED, null, true, false))
                .isEqualTo("stopped");
        assertThat(SearchStatusText.summary(null, 12, SearchStatusText.Phase.COMPLETE, null, false, false)).isEmpty();
    }

    @Test
    void tooManyMatchesWithinSecondText() {
        assertThat(SearchStatusText.summary(null, 10000, SearchStatusText.Phase.COMPLETE, null, false, true))
                .isEqualTo("more than 10,000 matches within a second; narrow the search");
        assertThat(SearchStatusText.summary(5000, 10000, SearchStatusText.Phase.RUNNING, null, false, true))
                .isEqualTo("5000 of 10,000+");
    }

    @Test
    void provisionalCountsGainATildeAndComposeWithThePlus() {
        assertThat(SearchStatusText.summary(3, 10000, SearchStatusText.Phase.COMPLETE, null, true, false, true))
                .isEqualTo("3 of ~10,000+");
        assertThat(SearchStatusText.summary(3, 212, SearchStatusText.Phase.RUNNING, null, false, false, true))
                .isEqualTo("3 of ~212");
        assertThat(SearchStatusText.count(1234, true, true)).isEqualTo("~1,234+");
        assertThat(SearchStatusText.count(1234, true)).isEqualTo("1,234+");
        assertThat(SearchStatusText.summary(null, 212, SearchStatusText.Phase.RUNNING, null, false, false))
                .isEqualTo("searching…");
    }

    @Test
    void noPositionSaysOnlyWhatTheCountDoesNotCover() {
        assertThat(SearchStatusText.summary(null, 0, SearchStatusText.Phase.RUNNING, null)).isEqualTo("searching…");
        assertThat(SearchStatusText.summary(null, 0, SearchStatusText.Phase.COMPLETE, null)).isEqualTo("no matches");
        assertThat(SearchStatusText.summary(null, 4, SearchStatusText.Phase.COMPLETE, null)).isEmpty();
        assertThat(SearchStatusText.summary(null, 4, SearchStatusText.Phase.STOPPED, null)).isEqualTo("stopped");
        assertThat(SearchStatusText.summary(null, 4, SearchStatusText.Phase.FAILED, null)).isEqualTo("search failed");
        assertThat(SearchStatusText.summary(null, 4, SearchStatusText.Phase.COMPLETE, "no more later matches"))
                .isEqualTo("no more later matches");
    }
}
