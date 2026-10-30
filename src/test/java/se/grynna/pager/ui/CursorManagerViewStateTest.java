package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.splunk.*;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.grynna.pager.splunk.Level.INFO;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.JSON;

// INSTRUCTIONS: prefer AssertJ assertThat over JUnit's assertEquals

class CursorManagerViewStateTest {

    private static final Instant TIMESTAMP = Instant.parse("2024-09-17T08:21:00Z");
    private static final Instant IN_A_LONG_LONG_TIME = TIMESTAMP.plusMillis(10L * 365L * 24L * 60L * 60L * 1000L);
    private static final String MSG_20 = "abcdefghijklmnopqrst";

    private final LogViewPort viewPort = mock(LogViewPort.class);

    /** A provider whose valid rows are {@code [beforeLine, afterLine)}. */
    private static LogDataProvider providerWithRows(int beforeLine, int afterLine)
            throws TemporarySearchException, SearchException, StorageException {
        LogDataProvider provider = mock(LogDataProvider.class);
        when(provider.getBeforeBoundary())
                .thenReturn(new Boundary(BoundaryType.BEFORE, TIMESTAMP.plusMillis(beforeLine), beforeLine));
        when(provider.getAfterBoundary())
                .thenReturn(new Boundary(BoundaryType.AFTER, TIMESTAMP.plusMillis(afterLine), afterLine));
        when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, IN_A_LONG_LONG_TIME));
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(invocation -> {
            int line = invocation.getArgument(0);
            var time = TIMESTAMP.plusMillis(line);
            return new LoadedLogLine(JSON, "seg", line, "cb1", "bkt", time, "{msg=\"" + MSG_20 + "\"}", time, time,
                    MSG_20, false, INFO);
        });
        return provider;
    }

    @Test
    void swapBackRestoresCapturedStateExactly() throws Exception {
        LogDataProvider providerA = providerWithRows(0, 1000);
        LogDataProvider providerB = providerWithRows(0, 50);
        CursorManager cm = new CursorManager(providerA, viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        // Establish a rich state on A: mark (5,10), cursor (7,20), horizontal offset 5.
        cm.setCursorFromClick(5, 10, false);
        cm.setCursorFromClick(7, 20, true);
        cm.scrollContent(0, 5);

        CursorManager.ViewState captured = cm.captureViewState();
        assertThat(captured.provider()).isSameAs(providerA);
        assertThat(captured.pageStart()).isZero();
        assertThat(captured.cursorLine()).isEqualTo(7);
        assertThat(captured.cursorCol()).isEqualTo(20);
        assertThat(captured.markLine()).isEqualTo(5);
        assertThat(captured.markCol()).isEqualTo(10);
        assertThat(captured.horizontalOffset()).isEqualTo(5);
        assertThat(captured.scrollLock()).isFalse();
        assertThat(captured.navHighlightLine()).isNull();

        // Swap to B (reset), then back to A with the captured state.
        cm.swapProvider(providerB, null);
        assertThat(cm.getPageStart()).isZero();
        assertThat(cm.cursorLine()).isEmpty();
        assertThat(cm.getState()).isEqualTo(InputState.SCROLL);

        cm.swapProvider(providerA, captured);

        assertThat(cm.getPageStart()).isZero();
        assertThat(cm.cursorLine()).contains(7);
        assertThat(cm.getCursor().orElseThrow().col()).isEqualTo(20);
        assertThat(cm.getMark().orElseThrow().line).isEqualTo(5);
        assertThat(cm.getMark().orElseThrow().col()).isEqualTo(10);
        assertThat(cm.getState()).isEqualTo(InputState.CURSOR);
        assertThat(cm.captureViewState().horizontalOffset()).isEqualTo(5);
    }

    @Test
    void restoreClampsPageStartAndCursorIntoProviderRows() throws Exception {
        LogDataProvider providerA = providerWithRows(0, 1000);
        LogDataProvider providerB = providerWithRows(0, 10); // rows 0..9
        CursorManager cm = new CursorManager(providerA, viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.setPageStart(400);
        cm.setCursorFromClick(0, 5, false); // cursor (400,5)
        cm.setCursorFromClick(1, 8, true); // mark (400,5), cursor (401,8)

        CursorManager.ViewState captured = cm.captureViewState();
        assertThat(captured.pageStart()).isEqualTo(400);
        assertThat(captured.cursorLine()).isEqualTo(401);

        cm.swapProvider(providerB, captured);

        // pageStart clamped into [0, bottomLine - linesPerPage + 1] = [0, 0].
        assertThat(cm.getPageStart()).isZero();
        // cursor line clamped to bottomLine (9), column preserved after the row clamp.
        assertThat(cm.cursorLine()).contains(9);
        assertThat(cm.getCursor().orElseThrow().col()).isEqualTo(8);
        assertThat(cm.getMark().orElseThrow().line).isEqualTo(9);
        assertThat(cm.getMark().orElseThrow().col()).isEqualTo(5);
    }

    @Test
    void gotoLinePlacesLineAtRequestedScreenRow() throws Exception {
        CursorManager cm = new CursorManager(providerWithRows(0, 1000), viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.gotoLine(500, 7);

        assertThat(cm.screenRowOf(500)).isEqualTo(7);
        assertThat(cm.getPageStart()).isEqualTo(493);
        assertThat(cm.hasNavigationHighlight()).isTrue();
        assertThat(cm.cursorLine()).isEmpty(); // gotoLine clears the cursor, like gotoLine(int)
    }

    @Test
    void gotoLineClampsAtTopEdge() throws Exception {
        CursorManager cm = new CursorManager(providerWithRows(0, 1000), viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.gotoLine(0, 7); // screen row 7 is unreachable above the first row

        assertThat(cm.getPageStart()).isZero();
        assertThat(cm.screenRowOf(0)).isZero();
    }

    @Test
    void gotoLineClampsAtBottomEdge() throws Exception {
        CursorManager cm = new CursorManager(providerWithRows(0, 1000), viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.gotoLine(999, 2); // screen row 2 is unreachable below the last row

        assertThat(cm.getPageStart()).isEqualTo(990);
        assertThat(cm.screenRowOf(999)).isEqualTo(9);
    }

    @Test
    void gotoLineClampsScreenRowIntoPage() throws Exception {
        CursorManager cm = new CursorManager(providerWithRows(0, 1000), viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.gotoLine(500, 15); // below the page -> clamped to the last row (9)
        assertThat(cm.getPageStart()).isEqualTo(491);
        assertThat(cm.screenRowOf(500)).isEqualTo(9);

        cm.gotoLine(500, -3); // above the page -> clamped to the first row (0)
        assertThat(cm.getPageStart()).isEqualTo(500);
        assertThat(cm.screenRowOf(500)).isZero();
    }

    @Test
    void gotoLineGoldenRatioPlacementUnchanged() throws Exception {
        CursorManager cm = new CursorManager(providerWithRows(0, 1000), viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.gotoLine(50);

        // center = (int)(10 * 0.46) = 4 -> pageStart = 50 - 4 = 46
        assertThat(cm.getPageStart()).isEqualTo(46);
        assertThat(cm.hasNavigationHighlight()).isTrue();
        assertThat(cm.cursorLine()).isEmpty();
    }

    @Test
    void swapBackRestoresNavigationHighlight() throws Exception {
        LogDataProvider provider = providerWithRows(0, 1000);
        CursorManager cm = new CursorManager(provider, viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.gotoLine(50); // sets the navigation highlight and clears the cursor
        CursorManager.ViewState captured = cm.captureViewState();
        assertThat(captured.navHighlightLine()).isEqualTo(50);

        cm.swapProvider(provider, captured);

        assertThat(cm.hasNavigationHighlight()).isTrue();
        assertThat(cm.captureViewState().navHighlightLine()).isEqualTo(50);
    }

    @Test
    void ensureCursorAtSeedsACursorAndEntersCursorMode() throws Exception {
        CursorManager cm = new CursorManager(providerWithRows(0, 1000), viewPort);
        cm.updateSize(10, 80);
        DateTimeFormatterManagerTestUtil.initDTM();

        cm.gotoLine(500, 7); // clears the cursor, scroll mode
        assertThat(cm.cursorLine()).isEmpty();

        cm.ensureCursorAt(500);

        assertThat(cm.cursorLine()).contains(500);
        assertThat(cm.getState()).isEqualTo(InputState.CURSOR);
    }
}
