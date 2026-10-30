package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.splunk.SearchSessionTestSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.JSON;

/**
 * Successive F3 / Shift-F3 presses visit distinct hits and stop at the ends,
 * including when each target is centered below the top of a multi-row viewport.
 * <p>
 * The press loop mirrors {@code LogViewer.navigateSearch}: read the highlight
 * and the first visible event, advance, then {@code gotoLine}, which centers
 * the hit and refreshes the viewport the next press will see.
 */
class SearchNavigationTest {

    private static final Instant BASE = Instant.parse("2017-06-01T00:00:00Z");
    /** Large enough that golden-ratio centering leaves several rows above the target. */
    private static final int LINES_PER_PAGE = 20;

    static {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @Test
    void edgeNoteSaysTheMatchLimitStoppedATruncatedSide() throws Exception {
        Fixture fx = fixture(100, 200);
        SearchSessionTestSupport.markTruncated(fx.session, SearchSession.Side.LATER);
        SearchSessionTestSupport.complete(fx.session);
        press(fx, true);
        press(fx, true);

        assertThat(advance(fx, true).edge()).isEqualTo(SearchNavigation.Edge.LATER);
        assertThat(fx.navigation.edgeNote(fx.session)).isEqualTo(SearchNavigation.truncatedNote(true))
                .isEqualTo("match limit reached — later matches not searched");
    }

    @Test
    void successiveF3PressesVisitDistinctHitsAndStopAtTheLastWhenCentered() throws Exception {
        Fixture fx = fixture(100, 200, 300, 400, 500);
        SearchSessionTestSupport.complete(fx.session);

        List<String> forward = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            forward.add(press(fx, true));
        }
        assertThat(forward).containsExactly("1:100", "1:200", "1:300", "1:400", "1:500");

        int pageStart = fx.cursorManager.getPageStart();
        SearchNavigation.Step end = advance(fx, true);
        assertThat(end.hit()).isNull();
        assertThat(end.edge()).isEqualTo(SearchNavigation.Edge.LATER);
        assertThat(fx.navigation.edgeNote(fx.session)).isEqualTo(SearchNavigation.NO_MORE_LATER_MATCHES);
        assertThat(fx.cursorManager.getPageStart()).isEqualTo(pageStart);
        assertThat(fx.cursorManager.hasNavigationHighlight()).isTrue();

        // Pressing F3 again still does not wrap back to the first match.
        SearchNavigation.Step again = advance(fx, true);
        assertThat(again.hit()).isNull();
        assertThat(again.edge()).isEqualTo(SearchNavigation.Edge.LATER);
        assertThat(fx.cursorManager.getPageStart()).isEqualTo(pageStart);

        assertThat(press(fx, false)).isEqualTo("1:400");
        assertThat(fx.navigation.stuckEdge()).isEqualTo(SearchNavigation.Edge.NONE);
    }

    @Test
    void successiveShiftF3PressesVisitDistinctHitsAndStopAtTheFirstWhenCentered() throws Exception {
        Fixture fx = fixture(100, 200, 300, 400, 500);
        SearchSessionTestSupport.complete(fx.session);
        // Viewport top sits just before the last hit, so the first F3 selects it and centers it.
        fx.cursorManager.setPageStart(450);

        assertThat(press(fx, true)).isEqualTo("1:500");

        List<String> backward = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            backward.add(press(fx, false));
        }
        assertThat(backward).containsExactly("1:400", "1:300", "1:200", "1:100");

        int pageStart = fx.cursorManager.getPageStart();
        SearchNavigation.Step end = advance(fx, false);
        assertThat(end.hit()).isNull();
        assertThat(end.edge()).isEqualTo(SearchNavigation.Edge.EARLIER);
        assertThat(fx.navigation.edgeNote(fx.session)).isEqualTo(SearchNavigation.NO_MORE_EARLIER_MATCHES);
        assertThat(fx.cursorManager.getPageStart()).isEqualTo(pageStart);

        SearchNavigation.Step again = advance(fx, false);
        assertThat(again.hit()).isNull();
        assertThat(fx.cursorManager.getPageStart()).isEqualTo(pageStart);

        assertThat(press(fx, true)).isEqualTo("1:200");
        assertThat(fx.navigation.stuckEdge()).isEqualTo(SearchNavigation.Edge.NONE);
    }

    @Test
    void runningSearchSaysMatchesMayStillArriveAndANewHitReleasesTheEdge() throws Exception {
        Fixture fx = fixture(100, 200);
        press(fx, true);
        press(fx, true);

        SearchNavigation.Step end = advance(fx, true);
        assertThat(end.edge()).isEqualTo(SearchNavigation.Edge.LATER);
        assertThat(fx.navigation.edgeNote(fx.session)).isEqualTo(SearchNavigation.NO_LATER_MATCHES_YET);

        SearchSessionTestSupport.complete(fx.session);
        assertThat(fx.navigation.edgeNote(fx.session)).isEqualTo(SearchNavigation.NO_MORE_LATER_MATCHES);

        // A match that arrives past the edge is reachable again; F3 must not stay stuck.
        SearchSessionTestSupport.extendWith(fx.session, hitAt(300));
        fx.navigation.releaseIfMovedPast(fx.session);
        assertThat(fx.navigation.stuckEdge()).isEqualTo(SearchNavigation.Edge.NONE);
        assertThat(press(fx, true)).isEqualTo("1:300");
    }

    @Test
    void searchStatusNamesTheMatchAndTheEndWithoutCallingThemLogs() {
        assertThat(SearchStatusText.summary(3, 12, true, null)).isEqualTo("3 of 12");
        assertThat(SearchStatusText.summary(12, 12, true, SearchNavigation.NO_MORE_LATER_MATCHES))
                .isEqualTo("12 of 12 · no more later matches");
        assertThat(SearchStatusText.summary(12, 12, false, SearchNavigation.NO_LATER_MATCHES_YET))
                .isEqualTo("12 of 12 · no later matches yet");
        assertThat(SearchStatusText.summary(1, 12, true, SearchNavigation.NO_MORE_EARLIER_MATCHES))
                .isEqualTo("1 of 12 · no more earlier matches");
        assertThat(SearchStatusText.summary(null, 4, false, null)).isEqualTo("searching…");
        assertThat(SearchStatusText.summary(null, 0, true, SearchNavigation.NO_MORE_LATER_MATCHES))
                .isEqualTo("no more later matches");
        assertThat(SearchStatusText.center(SearchNavigation.NO_MORE_LATER_MATCHES, "searched 10:00:00 → 10:05:00"))
                .isEqualTo("No more later matches  ·  searched 10:00:00 → 10:05:00");
    }

    @Test
    void reverseFromCenteredHitDoesNotSkipHitsBetweenHighlightAndViewportTop() throws Exception {
        // Hits three lines apart. Centering (offset 9) leaves earlier hits between
        // the viewport top and the highlighted row. Anchoring on the viewport top
        // would skip those hits; anchoring on the selected hit must not.
        Fixture fx = fixture(1000, 1003, 1006, 1009);
        fx.cursorManager.setPageStart(1007);

        assertThat(press(fx, true)).isEqualTo("1:1009");
        assertThat(fx.cursorManager.getPageStart()).isLessThan(1009);
        assertThat(fx.cursorManager.getPageStart()).isLessThanOrEqualTo(1000);

        assertThat(press(fx, false)).isEqualTo("1:1006");
        assertThat(fx.navigation.stuckEdge()).isEqualTo(SearchNavigation.Edge.NONE);
    }

    @Test
    void manualScrollResetsAnchorToTheCurrentViewport() throws Exception {
        Fixture fx = fixture(100, 200, 300, 400);
        fx.cursorManager.setPageStart(250);

        assertThat(press(fx, true)).isEqualTo("1:300");
        assertThat(fx.cursorManager.hasNavigationHighlight()).isTrue();

        // Scroll back above the selected hit. The highlight clears, so the next
        // F3 must follow the new viewport top (hit 200), not continue from 300.
        fx.cursorManager.scrollContent(-120, 0);
        fx.navigation.releaseStuck();

        assertThat(fx.cursorManager.hasNavigationHighlight()).isFalse();
        assertThat(fx.cursorManager.getPageStart()).isLessThan(200);

        assertThat(press(fx, true)).isEqualTo("1:200");
    }

    @Test
    void selectSetsTheCurrentHitAndDropsTheStuckEdge() throws Exception {
        Fixture fx = fixture(100, 200);
        press(fx, true);
        press(fx, true);
        assertThat(advance(fx, true).edge()).isEqualTo(SearchNavigation.Edge.LATER);

        fx.navigation.select(hitAt(100));

        assertThat(fx.navigation.current()).isEqualTo(hitAt(100));
        assertThat(fx.navigation.stuckEdge()).isEqualTo(SearchNavigation.Edge.NONE);
        // The highlight from the last goto is still active, so F3 continues from the selection.
        assertThat(advance(fx, true).hit()).isEqualTo(hitAt(200));
    }

    private Fixture fixture(int... hitLines) throws Exception {
        LogDataProvider provider = mock(LogDataProvider.class);
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(invocation -> loadedLine(invocation.getArgument(0)));
        when(provider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, Instant.EPOCH, -1_000_000));
        when(provider.getAfterBoundary())
                .thenReturn(new Boundary(BoundaryType.AFTER, Instant.parse("2099-01-01T00:00:00Z"), 1_000_000));

        CursorManager cursorManager = new CursorManager(provider, mock(LogViewPort.class));
        cursorManager.updateSize(LINES_PER_PAGE, 120);

        SearchHit[] hits = new SearchHit[hitLines.length];
        for (int i = 0; i < hitLines.length; i++) {
            hits[i] = hitAt(hitLines[i]);
        }
        SearchSession session = SearchSessionTestSupport.sessionWith("timeout", BASE, hits);
        return new Fixture(cursorManager, session, new SearchNavigation());
    }

    /**
     * Same sequence as {@code LogViewer.navigateSearch} followed by
     * {@code CursorManager.gotoLine}: the chosen hit is centered, which moves
     * the first visible event to a timestamp before the hit.
     */
    private SearchNavigation.Step advance(Fixture fx, boolean forward) throws Exception {
        return fx.navigation.advance(fx.session, fx.cursorManager.hasNavigationHighlight(),
                firstVisible(fx.cursorManager), forward);
    }

    private String press(Fixture fx, boolean forward) throws Exception {
        SearchNavigation.Step step = advance(fx, forward);
        assertThat(step.hit()).isNotNull();
        assertThat(step.edge()).isEqualTo(SearchNavigation.Edge.NONE);

        int targetLine = lineOf(step.hit());
        fx.cursorManager.gotoLine(targetLine);

        assertThat(fx.cursorManager.hasNavigationHighlight())
                .as("centering a search hit keeps the navigation highlight").isTrue();
        assertThat(fx.cursorManager.getPageStart()).as("multi-row viewport centers the target below the top")
                .isLessThan(targetLine);
        SearchHit top = firstVisible(fx.cursorManager);
        assertThat(top).isNotNull();
        assertThat(top.splunkTime()).as("viewport top is before the centered hit, which is what made F3 reselect it")
                .isBefore(step.hit().splunkTime());

        return step.hit().cd();
    }

    private SearchHit firstVisible(CursorManager cursorManager) throws Exception {
        for (DecoratedLogLine line : cursorManager.getVisibleLines()) {
            if (line.getLogLine() instanceof LoadedLogLine loaded) {
                return SearchHit.from(loaded);
            }
        }
        return null;
    }

    private static LoadedLogLine loadedLine(int lineNo) {
        Instant time = BASE.plusSeconds(lineNo);
        return new LoadedLogLine(JSON, "seg", lineNo, cd(lineNo), "bkt", time, "raw", time, time, "m", false,
                Level.INFO);
    }

    private static SearchHit hitAt(int lineNo) {
        Instant time = BASE.plusSeconds(lineNo);
        return new SearchHit(time, time, "bkt", cd(lineNo));
    }

    private static String cd(int lineNo) {
        return "1:" + lineNo;
    }

    private static int lineOf(SearchHit hit) {
        return Integer.parseInt(hit.cd().substring(2));
    }

    private record Fixture(CursorManager cursorManager, SearchSession session, SearchNavigation navigation) {}
}
