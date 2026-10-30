package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchHit;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MatchesAnchorTest {

    private static final Instant T = Instant.parse("2020-01-01T00:00:00Z");

    private static SearchHit hit(long seconds) {
        return SearchHit.at(T.plusSeconds(seconds));
    }

    @Test
    void prefersTheHighlightedMatchWhileItsNavigationHighlightIsActive() {
        SearchHit current = hit(10);
        MatchesAnchor.Choice choice = MatchesAnchor.choose(true, current, 5, hit(20), 3, hit(30), 100);

        assertThat(choice.hit()).isSameAs(current);
        assertThat(choice.screenRow()).isEqualTo(5 - 100);
    }

    @Test
    void aHighlightedButMissingMatchFallsThroughToTheCursorEvent() {
        SearchHit cursor = hit(20);
        MatchesAnchor.Choice choice = MatchesAnchor.choose(true, null, 5, cursor, 3, hit(30), 100);

        assertThat(choice.hit()).isSameAs(cursor);
        assertThat(choice.screenRow()).isEqualTo(3 - 100);
    }

    @Test
    void cursorEventWinsWhenThereIsNoNavigationHighlight() {
        SearchHit cursor = hit(20);
        MatchesAnchor.Choice choice = MatchesAnchor.choose(false, hit(10), 5, cursor, 3, hit(30), 100);

        assertThat(choice.hit()).isSameAs(cursor);
        assertThat(choice.screenRow()).isEqualTo(3 - 100);
    }

    @Test
    void firstVisibleEventWinsWithoutHighlightOrCursor() {
        SearchHit first = hit(30);
        MatchesAnchor.Choice choice = MatchesAnchor.choose(false, null, null, null, null, first, 100);

        assertThat(choice.hit()).isSameAs(first);
        assertThat(choice.screenRow()).isZero();
    }

    @Test
    void aCursorWithoutAnEventFallsThroughToTheFirstVisible() {
        SearchHit first = hit(30);
        MatchesAnchor.Choice choice = MatchesAnchor.choose(false, null, null, null, 3, first, 100);

        assertThat(choice.hit()).isSameAs(first);
    }

    @Test
    void nothingSelectableYieldsNone() {
        MatchesAnchor.Choice choice = MatchesAnchor.choose(false, null, null, null, null, null, 100);

        assertThat(choice.hit()).isNull();
    }
}
