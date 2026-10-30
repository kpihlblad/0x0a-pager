package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exercises the actual provider boundary callback and the same decision helper as LogViewer. */
class MatchesEdgeTest {
    private static final Instant T = Instant.parse("2017-06-01T12:00:00Z");
    private static final Instant LIMIT = T.plusSeconds(86400 * 10);

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    private SearchSession session() {
        var session = new SearchSession("x", T);
        var line = new LoadedLogLine(LoadedLogLine.MsgType.RAW, null, 0, "1:1", "b", T, "x", T, T, "x", false,
                Level.INFO);
        SearchSessionTestSupport.addMatch(session, SearchHit.from(line), line);
        SearchSessionTestSupport.coverWindow(session, T.minusSeconds(60), T.plusSeconds(60));
        return session;
    }

    private static SearchRangeOffer.Decision offer(SearchSession.Snapshot snapshot, SearchNavigation.Edge edge) {
        return SearchRangeOffer.matchesEdge(edge, snapshot, SearchBoundaries.MIN_MINIMUM_BOUNDARY, LIMIT, 1,
                SearchNavigation.Edge.NONE);
    }

    @Test
    void scrollingPastFinishedAndStoppedMatchesUsesExtendButTruncatedUsesRecentre() throws Exception {
        var session = session();
        SearchSessionTestSupport.complete(session);
        var provider = new MatchesDataProvider(session.snapshot());
        var decision = new AtomicReference<SearchRangeOffer.Decision>();
        provider.setBoundaryExpansionListener(request -> decision.set(offer(provider.snapshot(),
                request.type() == BoundaryType.AFTER ? SearchNavigation.Edge.LATER : SearchNavigation.Edge.EARLIER)));
        var cursor = new CursorManager(provider, mock(LogViewPort.class));
        cursor.scrollContent(100, 0);
        assertThat(decision.get().kind()).isEqualTo(SearchRangeOffer.Kind.EXTEND);
        assertThat(decision.get().direction()).isEqualTo(BoundaryType.AFTER);

        SearchSessionTestSupport.extendWith(session, SearchHit.at(T.plusSeconds(1)));
        SearchSessionTestSupport.cancel(session);
        provider.refresh(session.snapshot());
        provider.unfreezeBeforeBoundary();
        assertThat(decision.get().kind()).isEqualTo(SearchRangeOffer.Kind.EXTEND);
        assertThat(decision.get().direction()).isEqualTo(BoundaryType.BEFORE);

        SearchSessionTestSupport.markTruncated(session, SearchSession.Side.EARLIER);
        SearchSessionTestSupport.complete(session);
        provider.refresh(session.snapshot());
        provider.unfreezeBeforeBoundary();
        assertThat(decision.get().kind()).isEqualTo(SearchRangeOffer.Kind.RECENTRE);
        assertThat(decision.get().suggested()).isEqualTo(session.windowStart());
    }

    @Test
    void runningSideIsQuietButFinishedPeerCanOfferExtension() {
        var session = session();
        assertThat(offer(session.snapshot(), SearchNavigation.Edge.EARLIER).ask()).isFalse();
        var s = session.snapshot();
        var earlierDone = new SearchSession.Snapshot(s.version() + 1, s.hits(), s.lines(), s.state(), s.windowStart(),
                s.windowEnd(), false, false, false, true, false, "x", T, null);
        assertThat(offer(earlierDone, SearchNavigation.Edge.EARLIER).kind()).isEqualTo(SearchRangeOffer.Kind.EXTEND);
        assertThat(offer(earlierDone, SearchNavigation.Edge.LATER).ask()).isFalse();
    }

    @Test
    void gotoOutsideCoverageAlwaysOffersRecentreAndUsesHalfOpenEndWithoutRounding() {
        var s = session().snapshot();
        assertThat(SearchRangeOffer.matchesGoto(s, s.windowStart()).ask()).isFalse();
        assertThat(SearchRangeOffer.matchesGoto(s, s.windowEnd().minusNanos(1)).ask()).isFalse();
        var atEnd = SearchRangeOffer.matchesGoto(s, s.windowEnd());
        assertThat(atEnd.kind()).isEqualTo(SearchRangeOffer.Kind.RECENTRE);
        assertThat(atEnd.direction()).isEqualTo(BoundaryType.AFTER);
        var before = SearchRangeOffer.matchesGoto(s, s.windowStart().minusNanos(1));
        assertThat(before.kind()).isEqualTo(SearchRangeOffer.Kind.RECENTRE);
        assertThat(before.direction()).isEqualTo(BoundaryType.BEFORE);
        assertThat(before.suggested()).isEqualTo(s.windowStart().minusNanos(1));
    }
}
