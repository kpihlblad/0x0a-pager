package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReanchorTest {
    private static final Instant T = Instant.parse("2017-03-02T14:00:00Z");

    private static LoadedLogLine event(int nanos, String cd, String message) {
        Instant t = T.plusNanos(nanos);
        return new LoadedLogLine(LoadedLogLine.MsgType.RAW, null, 0, cd, "b", t, message, t, t, message, false,
                Level.INFO);
    }

    private static MatchesDataProvider provider(LoadedLogLine... lines) {
        SearchSession session = new SearchSession("x", T);
        for (var line : lines)
            SearchSessionTestSupport.addMatch(session, SearchHit.from(line), line);
        return new MatchesDataProvider(session.snapshot());
    }

    @Test
    void insertionsAboveBelowAndAtEqualTimeKeepMultilineOffsetsAndSeparateAnchors() {
        var a = event(100, "1:2", "a\na2\na3");
        var b = event(300, "3:1", "b\nb2");
        var old = provider(a, b);
        // Top is a's continuation; cursor/mark/highlight are separately anchored.
        var state = new CursorManager.ViewState(old, 1, 4, 0, 2, 0, 0, false, 3);
        var view = Reanchor.capture(old, state);
        var newer = provider(event(0, "0:1", "above\nabove2"), event(100, "1:1", "same-time before a"),
                a, event(200, "2:1", "between\nbetween2"), b, event(400, "4:1", "below"));

        var placed = Reanchor.place(newer, view);
        assertThat(placed.pageStart()).isEqualTo(4);
        assertThat(placed.cursor()).isEqualTo(9);
        assertThat(placed.mark()).isEqualTo(5);
        assertThat(placed.highlight()).isEqualTo(8);
        assertThat(newer.hitAt(placed.pageStart())).isEqualTo(SearchHit.from(a));
        assertThat(newer.offsetWithin(placed.cursor())).isEqualTo(1);
    }

    @Test
    void insertionBelowDoesNotMoveEitherAnchor() {
        var a = event(100, "1:1", "a\na2");
        var old = provider(a);
        var view = Reanchor.capture(old, new CursorManager.ViewState(old, 0, 1, 0, null, null, 0, false, null));
        var placed = Reanchor.place(provider(a, event(200, "2:1", "below")), view);
        assertThat(placed.pageStart()).isZero();
        assertThat(placed.cursor()).isEqualTo(1);
    }

    @Test
    void boundaryAboveViewportKeepsFirstActualRowAtItsScreenRow() {
        var a = event(100, "1:1", "a");
        var old = provider(a);
        var view = Reanchor.capture(old, new CursorManager.ViewState(old, -2, 0, 0, null, null, 0, false, null));
        var placed = Reanchor.place(provider(event(0, "0:1", "above"), a), view);
        assertThat(placed.pageStart()).isEqualTo(-1);
        assertThat(placed.cursor()).isEqualTo(1);
    }

    @Test
    void removedAnchorFallsBackToNearestTimeWithNanosecondTieEarlierAndClampsOffset() {
        var oldHit = SearchHit.from(event(100, "old", "a\nb\nc"));
        var newer = provider(event(0, "1:1", "earlier"), event(200, "2:1", "later\nsecond"));
        assertThat(Reanchor.resolve(newer, new Reanchor.Anchor(oldHit, 2))).isZero();
        var nearerLater = SearchHit.from(event(101, "old", "x"));
        assertThat(Reanchor.resolve(newer, new Reanchor.Anchor(nearerLater, 1))).isEqualTo(2);
    }

    @Test
    void emptyNewSnapshotHasNoResolvableAnchors() {
        var anchor = new Reanchor.Anchor(SearchHit.from(event(100, "old", "x")), 0);
        assertThat(Reanchor.resolve(provider(), anchor)).isNull();
        assertThat(Reanchor.place(provider(), new Reanchor.View(anchor, 0, anchor, null, null, 900)).pageStart())
                .as("empty/error message must stay visible even after replacing a scrolled session").isZero();
    }
}
