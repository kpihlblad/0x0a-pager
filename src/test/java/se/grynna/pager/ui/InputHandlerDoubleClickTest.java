package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routes a double-click by view: on the preamble (icon, timestamp, level) the Matches and Bookmarks views show
 * the event in the log, while on the message text, and in the Log view, it selects a word.
 */
class InputHandlerDoubleClickTest {

    private static final LogViewer.CharacterSize CHAR_SIZE = new LogViewer.CharacterSize(10, 20, 15, 5);

    @BeforeEach
    void desktopKeyHints() {
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
    }

    @Test
    void matchesPreambleDoubleClickShowsTheMatchInTheLog() throws Exception {
        Harness harness = harness(View.MATCHES);

        doubleClick(harness, 2);

        verify(harness.viewPort()).showMatchInLog();
        verify(harness.cursorManager(), never()).handleDoubleClick(anyInt(), anyInt(), anyInt());
    }

    @Test
    void matchesMessageDoubleClickSelectsTheWordAndDoesNotShowTheLog() throws Exception {
        Harness harness = harness(View.MATCHES);

        doubleClick(harness, messageColumn());

        verify(harness.viewPort(), never()).showMatchInLog();
        verify(harness.cursorManager()).handleDoubleClick(0, messageColumn(), 2);
        assertThat(harness.cursorManager().singleRowSelectionText()).contains("msg");
    }

    @Test
    void matchesLastPreambleColumnShowsTheLogAndFirstMessageColumnSelectsAWord() throws Exception {
        Harness harness = harness(View.MATCHES);

        doubleClick(harness, messageColumn() - 1);
        verify(harness.viewPort()).showMatchInLog();

        Harness other = harness(View.MATCHES);
        doubleClick(other, messageColumn());
        verify(other.viewPort(), never()).showMatchInLog();
        verify(other.cursorManager()).handleDoubleClick(0, messageColumn(), 2);
    }

    @Test
    void bookmarksPreambleDoubleClickShowsTheMatchInTheLog() throws Exception {
        Harness harness = harness(View.BOOKMARKS);

        doubleClick(harness, 2);

        verify(harness.viewPort()).showMatchInLog();
        verify(harness.cursorManager(), never()).handleDoubleClick(anyInt(), anyInt(), anyInt());
    }

    @Test
    void bookmarksMessageDoubleClickSelectsTheWordAndDoesNotShowTheLog() throws Exception {
        Harness harness = harness(View.BOOKMARKS);

        doubleClick(harness, messageColumn());

        verify(harness.viewPort(), never()).showMatchInLog();
        verify(harness.cursorManager()).handleDoubleClick(0, messageColumn(), 2);
        assertThat(harness.cursorManager().singleRowSelectionText()).contains("msg");
    }

    @Test
    void logViewPreambleDoubleClickSelectsAWordInsteadOfShowingTheLog() throws Exception {
        Harness harness = harness(View.LOG);

        doubleClick(harness, 2);

        verify(harness.viewPort(), never()).showMatchInLog();
        verify(harness.cursorManager()).handleDoubleClick(0, 2, 2);
    }

    @Test
    void logViewMessageDoubleClickSelectsTheWord() throws Exception {
        Harness harness = harness(View.LOG);

        doubleClick(harness, messageColumn());

        verify(harness.viewPort(), never()).showMatchInLog();
        verify(harness.cursorManager()).handleDoubleClick(0, messageColumn(), 2);
        assertThat(harness.cursorManager().singleRowSelectionText()).contains("msg");
    }

    @Test
    void singleClickOnThePreambleDoesNotRoute() throws Exception {
        for (View view : View.values()) {
            Harness harness = harness(view);

            click(harness, 2, 1);

            verify(harness.viewPort(), never()).showMatchInLog();
            verify(harness.cursorManager(), never()).handleDoubleClick(anyInt(), anyInt(), anyInt());
        }
    }

    @Test
    void noDoubleClickRoutesWithoutACharacterSize() throws Exception {
        Harness harness = harness(View.MATCHES);
        when(harness.viewPort().getCharacterSize()).thenReturn(null);

        harness.handler().clickHandler.mouseClicked(event(harness, 2, 2));

        verify(harness.viewPort(), never()).showMatchInLog();
        verify(harness.cursorManager(), never()).handleDoubleClick(anyInt(), anyInt(), anyInt());
    }

    private enum View {
        LOG, MATCHES, BOOKMARKS
    }

    private static int messageColumn() {
        return DecoratedLogLine.getPreambleLength();
    }

    /** Double-clicks on line 0 at the given visible column (the pointer lands in the middle of that cell). */
    private static void doubleClick(Harness harness, int col) {
        click(harness, col, 2);
    }

    private static void click(Harness harness, int col, int clickCount) {
        harness.handler().clickHandler.mouseClicked(event(harness, col, clickCount));
    }

    private static MouseEvent event(Harness harness, int col, int clickCount) {
        int x = LineRenderer.iconSpaceWidth(CHAR_SIZE.height()) + col * CHAR_SIZE.width() + CHAR_SIZE.width() / 2;
        int y = CHAR_SIZE.height() / 2;
        return new MouseEvent(harness.panel(), MouseEvent.MOUSE_CLICKED, 0L, 0, x, y, clickCount, false,
                MouseEvent.BUTTON1);
    }

    private record Harness(JPanel panel, LogViewPort viewPort, CursorManager cursorManager, InputHandler handler) {}

    /** A real cursor manager over a provider serving one JSON line "msg", so a word can really be selected. */
    private static Harness harness(View view) throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        JPanel panel = new JPanel();
        JPanel container = new JPanel();
        LogViewPort viewPort = mock(LogViewPort.class);
        when(viewPort.getActiveSearchTerm()).thenReturn(Optional.empty());
        when(viewPort.isMatchesView()).thenReturn(view == View.MATCHES);
        when(viewPort.isBookmarksView()).thenReturn(view == View.BOOKMARKS);
        when(viewPort.getCharacterSize()).thenReturn(CHAR_SIZE);
        LogDataProvider provider = mock(LogDataProvider.class);
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        when(provider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, t));
        when(provider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, t.plusSeconds(60)));
        when(provider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, t.plusSeconds(60)));
        when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> {
            int line = inv.getArgument(0);
            return new LoadedLogLine(LoadedLogLine.MsgType.JSON, "seg", line, "cb", "bkt", t, "raw", t, t, "msg",
                    false, Level.INFO);
        });
        CursorManager cursorManager = spy(new CursorManager(provider, viewPort));
        cursorManager.updateSize(50, 200);
        doReturn(false).when(cursorManager).clearSelection();
        InputHandler handler = new InputHandler(cursorManager, viewPort, () -> false);
        handler.setup(panel, container);
        return new Harness(panel, viewPort, cursorManager, handler);
    }
}
