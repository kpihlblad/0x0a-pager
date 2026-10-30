package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flip placement arithmetic is pure so it can be tested without Swing; the
 * Swing wiring (LogViewer.showMatchInLog / enterMatches) is exercised manually.
 */
class FlipPlacementTest {

    private static final Instant T = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void logRowAddsTheInEventOffset() {
        assertThat(FlipPlacement.logRow(100, 0)).isEqualTo(100);
        assertThat(FlipPlacement.logRow(100, 2)).isEqualTo(102);
        assertThat(FlipPlacement.logRow(0, 0)).isZero();
    }

    @Test
    void matchesRowReturnsTheAnchorsOwnRowWhenItIsAMatch() {
        SearchSession session = new SearchSession("q", T);
        LoadedLogLine a = event(T, "bkt", "1:1", "a\nb");
        SearchSessionTestSupport.addMatch(session, SearchHit.from(a), a);

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        assertThat(FlipPlacement.matchesRow(provider, SearchHit.from(a))).isZero();
    }

    @Test
    void matchesRowFallsBackToTheNearestByTimeWhenTheAnchorIsNotAMatch() {
        SearchSession session = new SearchSession("q", T);
        LoadedLogLine a = event(T, "bkt", "1:1", "a");
        LoadedLogLine b = event(T.plusSeconds(30), "bkt", "1:2", "b");
        SearchSessionTestSupport.addMatch(session, SearchHit.from(a), a);
        SearchSessionTestSupport.addMatch(session, SearchHit.from(b), b);

        MatchesDataProvider provider = new MatchesDataProvider(session.snapshot());

        // The anchor is an event 5s after `a`, which is not itself a match.
        SearchHit anchor = SearchHit.at(T.plusSeconds(5));
        assertThat(FlipPlacement.matchesRow(provider, anchor)).isZero();
    }

    private static LoadedLogLine event(Instant time, String bkt, String cd, String message) {
        LoadedLogLine line = new LoadedLogLine(MsgType.JSON, null, 0, cd, bkt, time, "raw", time, time, message, false,
                Level.INFO);
        line.setEvent(new TreeMap<>(Map.of("_cd", cd, "_bkt", bkt)));
        return line;
    }
}
