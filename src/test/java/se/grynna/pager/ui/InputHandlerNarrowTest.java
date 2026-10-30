package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.Placeholder;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The narrow keys, the icon click (activity popup) and the row menu triggers, wired to the view port. */
class InputHandlerNarrowTest {

    private static final LogViewer.CharacterSize CHAR_SIZE = new LogViewer.CharacterSize(10, 20, 15, 5);

    private enum View {
        LOG, MATCHES, BOOKMARKS
    }

    @BeforeEach
    void desktopKeyHints() {
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
    }

    @Test
    void theNarrowKeysReachTheViewPortInEveryView() {
        for (View view : View.values()) {
            Harness harness = harness(view, true);

            fire(harness, typed('a'));
            fire(harness, typed('A'));

            verify(harness.viewPort()).narrowToCursorActivity();
            verify(harness.viewPort()).narrowToCursorRequest();
        }
    }

    @Test
    void theNarrowKeysDoNothingWhileATextFieldHasFocus() {
        Harness harness = harness(View.LOG, false, true);

        fire(harness, typed('a'));
        fire(harness, typed('A'));

        verify(harness.viewPort(), never()).narrowToCursorActivity();
        verify(harness.viewPort(), never()).narrowToCursorRequest();
    }

    @Test
    void theNarrowKeysDoNothingWhileAYesNoPromptShows() {
        Harness harness = harness(View.LOG, true);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        fire(harness, typed('a'));

        verify(harness.viewPort(), never()).narrowToCursorActivity();
    }

    @Test
    void escGoesStraightToLeavingTheView() {
        Harness harness = harness(View.MATCHES, true);
        when(harness.viewPort().backToLog()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));

        verify(harness.viewPort()).backToLog();
    }

    @Test
    void shiftF10AndTheContextMenuKeyOpenTheRowMenuAtTheCursorInEveryView() {
        for (View view : View.values()) {
            Harness harness = harness(view, true);

            fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK));
            fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0));

            verify(harness.viewPort(), times(2)).showRowContextMenuAtCursor();
        }
    }

    @Test
    void theRowMenuKeysDoNothingWhileATextFieldHasFocus() {
        Harness harness = harness(View.LOG, true, true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK));

        verify(harness.viewPort(), never()).showRowContextMenuAtCursor();
    }

    // === right-click ===

    @Test
    void aRightClickOutsideTheSelectionMovesTheCursorAndOpensTheMenuAtThePointer() throws Exception {
        for (boolean onRelease : new boolean[]{false, true}) {
            Harness harness = harness(View.LOG, true);
            int y = 3 * CHAR_SIZE.height() + 4;

            popup(harness, 90, y, onRelease);

            assertThat(harness.cursorManager().cursorLine()).as("onRelease=%s", onRelease).hasValue(3);
            verify(harness.viewPort()).showRowContextMenu(90, y);
        }
    }

    @Test
    void aRightClickOpensTheMenuOnceEvenWhenPressAndReleaseAreBothTriggers() throws Exception {
        Harness harness = harness(View.LOG, true);

        popup(harness, 90, 24, false);
        harness.handler().clickHandler.mouseReleased(mouse(harness, MouseEvent.MOUSE_RELEASED, 90, 24, 1,
                MouseEvent.BUTTON3));

        verify(harness.viewPort(), times(1)).showRowContextMenu(90, 24);
    }

    @Test
    void aRightClickInsideTheSelectionKeepsTheCursorAndTheSelection() throws Exception {
        Harness harness = harness(View.LOG, true);
        harness.cursorManager().ensureCursorAt(0);
        harness.cursorManager().moveCursor(2, 3, true);
        assertThat(harness.cursorManager().selectedRows()).contains(1);

        popup(harness, 90, CHAR_SIZE.height() + 4, false);

        assertThat(harness.cursorManager().cursorLine()).hasValue(2);
        assertThat(harness.cursorManager().selectedRows()).isNotEmpty();
        verify(harness.cursorManager(), never()).setCursorFromClick(anyInt(), anyInt(), anyBoolean());
        verify(harness.viewPort()).showRowContextMenu(90, CHAR_SIZE.height() + 4);
    }

    @Test
    void aRightClickOnAnUnloadedRowOpensNoMenu() throws Exception {
        Harness harness = harness(View.LOG, false);

        popup(harness, 90, 24, false);

        verify(harness.viewPort(), never()).showRowContextMenu(anyInt(), anyInt());
    }

    @Test
    void aLeftClickNeverOpensTheMenu() throws Exception {
        Harness harness = harness(View.LOG, true);

        click(harness, 90, 24, 1, MouseEvent.BUTTON1);

        verify(harness.viewPort(), never()).showRowContextMenu(anyInt(), anyInt());
    }

    @Test
    void aClickOnTheIconOfALoadedRowOpensTheActivityPopupAtThePointer() throws Exception {
        for (View view : View.values()) {
            Harness harness = harness(view, true);

            click(harness, 3, 25, 1, MouseEvent.BUTTON1);

            verify(harness.viewPort()).showActivityMenu(3, 25);
            verify(harness.viewPort(), never()).narrowToCursorActivity();
        }
    }

    @Test
    void aClickOnTheMessageOrThePreambleTextDoesNotOpenTheActivityPopup() throws Exception {
        Harness harness = harness(View.LOG, true);
        int iconWidth = LineRenderer.iconSpaceWidth(CHAR_SIZE.height());

        click(harness, iconWidth, 25, 1, MouseEvent.BUTTON1);
        click(harness, iconWidth + 40, 25, 1, MouseEvent.BUTTON1);

        verify(harness.viewPort(), never()).showActivityMenu(anyInt(), anyInt());
    }

    @Test
    void aPressWithoutAClickIsADragAndDoesNotOpenTheActivityPopup() throws Exception {
        Harness harness = harness(View.LOG, true);

        harness.handler().clickHandler.mousePressed(mouse(harness, MouseEvent.MOUSE_PRESSED, 3, 25, 1,
                MouseEvent.BUTTON1));
        harness.handler().clickHandler.mouseDragged(mouse(harness, MouseEvent.MOUSE_DRAGGED, 90, 65, 1,
                MouseEvent.BUTTON1));
        harness.handler().clickHandler.mouseReleased(mouse(harness, MouseEvent.MOUSE_RELEASED, 90, 65, 1,
                MouseEvent.BUTTON1));

        verify(harness.viewPort(), never()).showActivityMenu(anyInt(), anyInt());
    }

    @Test
    void otherButtonsAndDoubleClicksOnTheIconDoNotOpenTheActivityPopup() throws Exception {
        Harness harness = harness(View.LOG, true);

        click(harness, 3, 25, 1, MouseEvent.BUTTON3);
        click(harness, 3, 25, 2, MouseEvent.BUTTON1);

        verify(harness.viewPort(), never()).showActivityMenu(anyInt(), anyInt());
    }

    @Test
    void aClickOnAnUnloadedRowDoesNotOpenTheActivityPopup() throws Exception {
        Harness harness = harness(View.LOG, false);

        click(harness, 3, 25, 1, MouseEvent.BUTTON1);

        verify(harness.viewPort(), never()).showActivityMenu(anyInt(), anyInt());
    }

    /** A right-click: the popup trigger arrives on press (Linux) or on release (Windows). */
    private static void popup(Harness harness, int x, int y, boolean onRelease) {
        MouseEvent event = new MouseEvent(harness.panel(), onRelease
                ? MouseEvent.MOUSE_RELEASED
                : MouseEvent.MOUSE_PRESSED, 0L, 0, x, y, 1, true, MouseEvent.BUTTON3);
        if (onRelease) {
            harness.handler().clickHandler.mouseReleased(event);
        } else {
            harness.handler().clickHandler.mousePressed(event);
        }
    }

    /** Press then click at (x, y), as a real click arrives. */
    private static void click(Harness harness, int x, int y, int clickCount, int button) {
        harness.handler().clickHandler.mousePressed(mouse(harness, MouseEvent.MOUSE_PRESSED, x, y, clickCount, button));
        harness.handler().clickHandler.mouseReleased(
                mouse(harness, MouseEvent.MOUSE_RELEASED, x, y, clickCount, button));
        harness.handler().clickHandler.mouseClicked(mouse(harness, MouseEvent.MOUSE_CLICKED, x, y, clickCount, button));
    }

    private static MouseEvent mouse(Harness harness, int id, int x, int y, int clickCount, int button) {
        return new MouseEvent(harness.panel(), id, 0L, 0, x, y, clickCount, false, button);
    }

    private static KeyStroke typed(char keyChar) {
        return KeyStroke.getKeyStroke(Character.valueOf(keyChar), 0);
    }

    private static void fire(Harness harness, KeyStroke stroke) {
        InputMap im = harness.panel().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        Object actionKey = im.get(stroke);
        org.assertj.core.api.Assertions.assertThat(actionKey).as("binding for %s", KeyMap.format(stroke)).isNotNull();
        harness.panel().getActionMap().get(actionKey)
                .actionPerformed(new ActionEvent(harness.panel(), ActionEvent.ACTION_PERFORMED, "test"));
    }

    private record Harness(JPanel panel, LogViewPort viewPort, CursorManager cursorManager, InputHandler handler) {}

    private static Harness harness(View view, boolean loaded) {
        return harness(view, loaded, false);
    }

    private static Harness harness(View view, boolean loaded, boolean textFieldFocused) {
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
        try {
            when(provider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> {
                int line = inv.getArgument(0);
                return loaded
                        ? new LoadedLogLine(LoadedLogLine.MsgType.JSON, "seg", line, "cb", "bkt", t, "raw", t, t,
                                "msg", false, Level.INFO)
                        : Placeholder.from(line, "");
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        CursorManager cursorManager = spy(new CursorManager(provider, viewPort));
        cursorManager.updateSize(50, 200);
        doReturn(false).when(cursorManager).clearSelection();
        InputHandler handler = new InputHandler(cursorManager, viewPort, () -> textFieldFocused);
        handler.setup(panel, container);
        return new Harness(panel, viewPort, cursorManager, handler);
    }
}
