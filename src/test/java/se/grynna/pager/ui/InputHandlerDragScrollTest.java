package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import se.grynna.pager.splunk.AfterBoundaryLine;
import se.grynna.pager.splunk.BeforeBoundaryLine;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InputHandlerDragScrollTest {

    private static final LogViewer.CharacterSize CHAR_SIZE = new LogViewer.CharacterSize(10, 20, 15, 5);
    private static final int LINES_PER_PAGE = 10;
    private static final int CHARS_PER_LINE = 40;
    private static final int VISIBLE_TEXT_COLS = (CHARS_PER_LINE * CHAR_SIZE.width()
            - LineRenderer.iconSpaceWidth(CHAR_SIZE.height())) / CHAR_SIZE.width();
    private static final String LONG_MSG = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @BeforeEach
    void desktopKeyHints() {
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
    }

    @Test
    void draggingBelowViewportScrollsRepeatedlyAndClampsEndpointToBottomEdge() throws Exception {
        Harness harness = harness(null, null);
        harness.cursorManager().setPageStart(20);
        int col = messageColumn() + 2;

        press(harness, xForCol(col), yForRow(3));
        // Drag 30 rows below the 10-row viewport: endpoint must clamp to row 9, not jump by 30 rows
        drag(harness, xForCol(col), yForRow(40));

        assertThat(harness.handler().isDragScrolling()).isTrue();
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(21);
        assertThat(harness.cursorManager().getMark().orElseThrow().line).isEqualTo(23);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(30);

        harness.handler().dragScrollTick();
        harness.handler().dragScrollTick();

        assertThat(harness.cursorManager().getPageStart()).isEqualTo(23);
        assertThat(harness.cursorManager().getMark().orElseThrow().line).isEqualTo(23);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(32);

        release(harness, xForCol(col), yForRow(40));
        assertThat(harness.handler().isDragScrolling()).isFalse();
    }

    @Test
    void draggingAboveViewportScrollsRepeatedlyAndClampsEndpointToTopEdge() throws Exception {
        Harness harness = harness(null, null);
        harness.cursorManager().setPageStart(20);
        int col = messageColumn() + 2;

        press(harness, xForCol(col), yForRow(5));
        // Even a few pixels above the top edge (y = -5) is outside and clamps to row 0
        drag(harness, xForCol(col), -5);

        assertThat(harness.handler().isDragScrolling()).isTrue();
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(19);
        assertThat(harness.cursorManager().getMark().orElseThrow().line).isEqualTo(25);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(19);

        // A large negative y still steps one line at a time without jumping
        drag(harness, xForCol(col), -600);
        harness.handler().dragScrollTick();

        assertThat(harness.cursorManager().getPageStart()).isEqualTo(17);
        assertThat(harness.cursorManager().getMark().orElseThrow().line).isEqualTo(25);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(17);

        release(harness, xForCol(col), -600);
        assertThat(harness.handler().isDragScrolling()).isFalse();
    }

    @Test
    void draggingRightAndLeftScrollsHorizontallyAndOffersSelectionToSearchPrompt() throws Exception {
        Harness harness = harness(null, null);
        when(harness.viewPort().isSearchInputActive()).thenReturn(true);
        int startCol = messageColumn();

        press(harness, xForCol(startCol), yForRow(2));
        // Drag far past the right edge of the 40-column viewport
        drag(harness, xForCol(CHARS_PER_LINE + 25), yForRow(2));

        assertThat(harness.handler().isDragScrolling()).isTrue();
        int expectedFirstEndCol = VISIBLE_TEXT_COLS;
        assertThat(harness.cursorManager().getMark().orElseThrow().col()).isEqualTo(startCol);
        assertThat(harness.cursorManager().getCursor().orElseThrow().col()).isEqualTo(expectedFirstEndCol);

        harness.handler().dragScrollTick();
        harness.handler().dragScrollTick();

        int expectedEndCol = VISIBLE_TEXT_COLS + 2;
        assertThat(harness.cursorManager().getCursor().orElseThrow().col()).isEqualTo(expectedEndCol);
        String expectedSelected = LONG_MSG.substring(0, expectedEndCol - startCol);
        verify(harness.viewPort()).offerSelectionToSearchPrompt(expectedSelected);

        // Now drag past the left edge (x < 0) to scroll back left
        drag(harness, -50, yForRow(2));
        assertThat(harness.handler().isDragScrolling()).isTrue();
        assertThat(harness.cursorManager().getCursor().orElseThrow().col()).isEqualTo(2);

        harness.handler().dragScrollTick();
        assertThat(harness.cursorManager().getCursor().orElseThrow().col()).isEqualTo(1);

        release(harness, -50, yForRow(2));
        assertThat(harness.handler().isDragScrolling()).isFalse();
    }

    @Test
    void diagonalDragScrollsBothAxesTogether() throws Exception {
        Harness harness = harness(null, null);
        harness.cursorManager().setPageStart(10);
        int startCol = messageColumn();

        press(harness, xForCol(startCol), yForRow(4));
        drag(harness, xForCol(CHARS_PER_LINE + 10), yForRow(LINES_PER_PAGE + 10));

        assertThat(harness.handler().isDragScrolling()).isTrue();
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(11);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(11 + LINES_PER_PAGE - 1);
        assertThat(harness.cursorManager().getCursor().orElseThrow().col()).isEqualTo(VISIBLE_TEXT_COLS);

        harness.handler().dragScrollTick();
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(12);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(12 + LINES_PER_PAGE - 1);
        assertThat(harness.cursorManager().getCursor().orElseThrow().col()).isEqualTo(VISIBLE_TEXT_COLS + 1);

        release(harness, xForCol(CHARS_PER_LINE + 10), yForRow(LINES_PER_PAGE + 10));
    }

    @Test
    void movingBackInsideStopsAutoScrollAndResumesNormalDrag() throws Exception {
        Harness harness = harness(null, null);
        harness.cursorManager().setPageStart(10);
        int col = messageColumn() + 1;

        press(harness, xForCol(col), yForRow(2));
        drag(harness, xForCol(col), yForRow(LINES_PER_PAGE + 5));
        harness.handler().dragScrollTick();

        assertThat(harness.handler().isDragScrolling()).isTrue();
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(12);

        // Move back inside to visible row 4
        drag(harness, xForCol(col + 3), yForRow(4));

        assertThat(harness.handler().isDragScrolling()).isFalse();
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(12);
        assertThat(harness.cursorManager().getMark().orElseThrow().line).isEqualTo(12);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(16);
        assertThat(harness.cursorManager().getCursor().orElseThrow().col()).isEqualTo(col + 3);

        // Ticking after returning inside is a no-op
        harness.handler().dragScrollTick();
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(12);
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(16);

        release(harness, xForCol(col + 3), yForRow(4));
    }

    @Test
    void mouseReleaseStopsAutoScrollBeforeSelectionDone() throws Exception {
        Harness harness = harness(null, null);
        int col = messageColumn();

        press(harness, xForCol(col), yForRow(2));
        drag(harness, xForCol(col), yForRow(LINES_PER_PAGE + 3));
        assertThat(harness.handler().isDragScrolling()).isTrue();

        InOrder order = inOrder(harness.cursorManager());
        release(harness, xForCol(col), yForRow(LINES_PER_PAGE + 3));

        assertThat(harness.handler().isDragScrolling()).isFalse();
        order.verify(harness.cursorManager()).selectionDone();

        // Subsequent ticks or releases do not scroll again
        int pageStartAtRelease = harness.cursorManager().getPageStart();
        harness.handler().dragScrollTick();
        release(harness, xForCol(col), yForRow(LINES_PER_PAGE + 3));
        assertThat(harness.cursorManager().getPageStart()).isEqualTo(pageStartAtRelease);
    }

    @Test
    void ordinaryClicksAndInsideDragsNeverAutoScroll() throws Exception {
        Harness harness = harness(null, null);
        harness.cursorManager().setPageStart(10);
        int col = messageColumn();

        // Click and drag wholly inside
        press(harness, xForCol(col), yForRow(1));
        drag(harness, xForCol(col + 4), yForRow(6));
        release(harness, xForCol(col + 4), yForRow(6));

        assertThat(harness.handler().isDragScrolling()).isFalse();
        verify(harness.cursorManager(), never()).scrollContent(anyInt(), anyInt());

        // Ordinary press/release outside without drag does not start auto-scroll
        press(harness, xForCol(col), -40);
        release(harness, xForCol(col), -40);

        assertThat(harness.handler().isDragScrolling()).isFalse();
        verify(harness.cursorManager(), never()).scrollContent(anyInt(), anyInt());
    }

    @Test
    void timePanelCloseAndRightButtonPressDoNotDragScroll() throws Exception {
        Harness harness = harness(null, null);
        when(harness.viewPort().closeTimePanel()).thenReturn(true);

        press(harness, xForCol(5), yForRow(2));
        drag(harness, xForCol(5), yForRow(LINES_PER_PAGE + 10));
        release(harness, xForCol(5), yForRow(LINES_PER_PAGE + 10));

        assertThat(harness.handler().isDragScrolling()).isFalse();
        verify(harness.cursorManager(), never()).scrollContent(anyInt(), anyInt());

        when(harness.viewPort().closeTimePanel()).thenReturn(false);
        MouseEvent rightPress = new MouseEvent(harness.panel(), MouseEvent.MOUSE_PRESSED, 0L,
                InputEvent.BUTTON3_DOWN_MASK, xForCol(5), yForRow(2), 1, true, MouseEvent.BUTTON3);
        MouseEvent rightDrag = new MouseEvent(harness.panel(), MouseEvent.MOUSE_DRAGGED, 0L,
                InputEvent.BUTTON3_DOWN_MASK, xForCol(5), yForRow(LINES_PER_PAGE + 10), 1, false,
                MouseEvent.NOBUTTON);
        harness.handler().clickHandler.mousePressed(rightPress);
        harness.handler().clickHandler.mouseDragged(rightDrag);

        assertThat(harness.handler().isDragScrolling()).isFalse();
        verify(harness.cursorManager(), never()).scrollContent(anyInt(), anyInt());
    }

    @Test
    void dragScrollRespectsProviderBoundaries() throws Exception {
        // Valid loaded lines are 0..14 (before boundary at 0, after boundary at 15)
        Harness harness = harness(0, 15);
        harness.cursorManager().setPageStart(4);
        int col = messageColumn() + 2;

        press(harness, xForCol(col), yForRow(5));
        drag(harness, xForCol(col), yForRow(LINES_PER_PAGE + 10));
        for (int i = 0; i < 15; i++) {
            harness.handler().dragScrollTick();
        }

        // Cursor line clamps to the last valid line (14) and after boundary is unfrozen
        assertThat(harness.cursorManager().getCursor().orElseThrow().line).isEqualTo(14);
        verify(harness.provider(), atLeastOnce()).unfreezeAfterBoundary();

        release(harness, xForCol(col), yForRow(LINES_PER_PAGE + 10));
    }

    private static int messageColumn() {
        DateTimeFormatterManagerTestUtil.initDTM();
        return DecoratedLogLine.getPreambleLength();
    }

    private static int xForCol(int col) {
        return LineRenderer.iconSpaceWidth(CHAR_SIZE.height()) + col * CHAR_SIZE.width() + CHAR_SIZE.width() / 2;
    }

    private static int yForRow(int row) {
        return row * CHAR_SIZE.height() + CHAR_SIZE.height() / 2;
    }

    private static void press(Harness harness, int x, int y) {
        harness.handler().clickHandler.mousePressed(mouse(harness, MouseEvent.MOUSE_PRESSED, x, y));
    }

    private static void drag(Harness harness, int x, int y) {
        harness.handler().clickHandler.mouseDragged(mouse(harness, MouseEvent.MOUSE_DRAGGED, x, y));
    }

    private static void release(Harness harness, int x, int y) {
        harness.handler().clickHandler.mouseReleased(mouse(harness, MouseEvent.MOUSE_RELEASED, x, y));
    }

    private static MouseEvent mouse(Harness harness, int id, int x, int y) {
        return new MouseEvent(harness.panel(), id, 0L, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
                MouseEvent.BUTTON1);
    }

    private record Harness(
            JPanel panel,
            LogViewPort viewPort,
            LogDataProvider provider,
            CursorManager cursorManager,
            InputHandler handler) {}

    private static Harness harness(Integer beforeLine, Integer afterLine) throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        JPanel panel = new JPanel();
        panel.setSize(CHARS_PER_LINE * CHAR_SIZE.width(), LINES_PER_PAGE * CHAR_SIZE.height());
        JPanel container = new JPanel();
        LogViewPort viewPort = mock(LogViewPort.class);
        when(viewPort.getActiveSearchTerm()).thenReturn(Optional.empty());
        when(viewPort.getCharacterSize()).thenReturn(CHAR_SIZE);

        LogDataProvider provider = mock(LogDataProvider.class);
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        Boundary before = beforeLine != null
                ? new Boundary(BoundaryType.BEFORE, t, beforeLine)
                : new Boundary(BoundaryType.BEFORE, t);
        Boundary after = afterLine != null
                ? new Boundary(BoundaryType.AFTER, t.plusSeconds(60), afterLine)
                : new Boundary(BoundaryType.AFTER, t.plusSeconds(60));
        when(provider.getBeforeBoundary()).thenReturn(before);
        when(provider.getAfterBoundary()).thenReturn(after);
        when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, t.plusSeconds(3600)));
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> {
            int line = inv.getArgument(0);
            if (beforeLine != null && line < beforeLine) {
                return new BeforeBoundaryLine(line, before);
            }
            if (afterLine != null && line >= afterLine) {
                return new AfterBoundaryLine(line, after);
            }
            return new LoadedLogLine(LoadedLogLine.MsgType.JSON, "seg", line, "cb", "bkt", t, "raw", t, t, LONG_MSG,
                    false, Level.INFO);
        });

        CursorManager cursorManager = spy(new CursorManager(provider, viewPort));
        cursorManager.updateSize(LINES_PER_PAGE, CHARS_PER_LINE);
        InputHandler handler = new InputHandler(cursorManager, viewPort, () -> false);
        handler.setup(panel, container);
        return new Harness(panel, viewPort, provider, cursorManager, handler);
    }
}
