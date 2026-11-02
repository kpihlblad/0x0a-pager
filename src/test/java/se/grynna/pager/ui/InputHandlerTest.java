package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.ui.clip.InMemoryClipboard;
import se.grynna.pager.ui.time.TimePanel;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InputHandlerTest {

    @BeforeEach
    void desktopKeyHints() {
        // A LogViewer built by another test reads the user's preference into the shared map
        KeyMap.setPreferredFlavour(KeyFlavour.DESKTOP);
    }

    @Test
    void ctrlCIsWiredToCopyAction() {
        JPanel panel = new JPanel();
        JPanel container = new JPanel();

        var cursorManager = new CursorManager(mock(LogDataProvider.class), mock(LogViewPort.class));
        var inputHandler = new InputHandler(cursorManager, mock(LogViewPort.class));
        inputHandler.setup(panel, container);

        Object actionKey = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK));

        assertThat(actionKey).isEqualTo("copy");
        assertThat(panel.getActionMap().get("copy")).isNotNull();
    }

    @Test
    void mouseButton4And5AreTreatedAsHorizontalPan() throws Exception {
        JPanel panel = new JPanel();
        JPanel container = new JPanel();

        var logProvider = mock(LogDataProvider.class);
        var viewPort = mock(LogViewPort.class);

        // Spy so we can verify scrollContent invocation
        var cursorManager = spy(new CursorManager(logProvider, viewPort));
        cursorManager.updateSize(50, 200);

        var inputHandler = new InputHandler(cursorManager, viewPort);
        inputHandler.setup(panel, container);

        // Extra buttons cannot be built with the MouseEvent constructor headless (the toolkit
        // refuses), so the events are stubbed and handed straight to the registered listeners.
        // button 4
        pressButton(panel, 4);

        // button 5
        pressButton(panel, 5);

        // We don't care about direction here, only that horizontal movement is attempted.
        verify(cursorManager, atLeastOnce()).scrollContent(eq(0), anyInt());
    }

    private static void pressButton(JPanel panel, int button) {
        MouseEvent event = mock(MouseEvent.class);
        when(event.getID()).thenReturn(MouseEvent.MOUSE_PRESSED);
        when(event.getSource()).thenReturn(panel);
        when(event.getButton()).thenReturn(button);
        for (MouseListener listener : panel.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    // === Key-map driven bindings ===

    @Test
    void everyHandledActionIsRegisteredAndUnboundActionsAreNot() {
        Harness harness = harness(false);
        KeyMap keyMap = KeyMap.defaults();

        for (UiAction action : harness.handler().boundActions()) {
            for (KeyBinding binding : keyMap.all(action)) {
                Object actionKey = harness.panel().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(binding.stroke());
                assertThat(actionKey).as("%s is registered for %s", action, KeyMap.format(binding.stroke()))
                        .isNotNull();
                assertThat(harness.panel().getActionMap().get(actionKey)).as("action for %s", actionKey).isNotNull();
            }
        }

        // Deliberately unbound: no handler yet, so the key must not swallow input.
        for (UiAction action : new UiAction[]{UiAction.SEARCH_SELECTION_FORWARD,
                UiAction.SEARCH_SELECTION_BACKWARD}) {
            for (KeyBinding binding : keyMap.all(action)) {
                assertThat(harness.panel().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(binding.stroke()))
                        .as("%s stays unbound", action).isNull();
            }
        }
    }

    @Test
    void typedCharactersAreRegisteredAsKeyTypedStrokes() {
        Harness harness = harness(false);
        InputMap im = harness.panel().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        // '/' is a KEY_TYPED stroke, the form a component's InputMap looks up for typing.
        Object slashKey = im.get(KeyStroke.getKeyStroke(Character.valueOf('/'), 0));
        assertThat(slashKey).isNotNull();
        assertThat(harness.panel().getActionMap().get(slashKey)).isNotNull();

        // The same keystroke in KEY_PRESSED form is not what was registered.
        assertThat(im.get(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, 0))).isNull();
    }

    @Test
    void typedSlashOpensTheSearchPrompt() throws Exception {
        Harness harness = harness(false);

        fire(harness, typed('/'));
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt(null);
    }

    @Test
    void ctrlFOpensThePromptPreFilledWithTheActiveTerm() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt("timeout");
    }

    @Test
    void theFirstPromptAfterLoginDoesNotOfferTextCopiedBeforeIt() throws Exception {
        Harness harness = harness(false);
        InMemoryClipboard clipboard = new InMemoryClipboard();
        clipboard.setText("copied before 0x0A Pager started");
        harness.handler().setClipboard(clipboard);
        harness.handler().seedClipboard();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        flushEdt();
        verify(harness.viewPort()).openSearchPrompt(null);

        clipboard.setText("copied afterwards");
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        flushEdt();
        verify(harness.viewPort()).openSearchPrompt("copied afterwards");
    }

    @Test
    void enterInTheLogViewNeverOpensThePromptOrSearchesTheClipboard() throws Exception {
        Harness harness = harness(false);
        InMemoryClipboard clipboard = new InMemoryClipboard();
        clipboard.setText("clipboard text");
        harness.handler().setClipboard(clipboard);

        fire(harness.panel(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        fire(harness.panel(), KeyStroke.getKeyStroke(Character.valueOf('\n'), 0));
        fire(harness.panel(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true));
        flushEdt();

        verify(harness.viewPort(), never()).openSearchPrompt(any());
        verify(harness.viewPort(), never()).executeSearch(any());
        verify(harness.viewPort(), never()).executeSearch(any(), anyBoolean());
        verify(harness.viewPort(), never()).setSearchInputActive(anyBoolean());
    }

    @Test
    void questionMarkOpensTheSamePromptForNow() throws Exception {
        Harness harness = harness(false);

        fire(harness, typed('?'));
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt(null);
    }

    @Test
    void nextMatchPagerKeyDoesNothingWithoutASearch() throws Exception {
        Harness harness = harness(false);

        fire(harness, typed('n'));
        flushEdt();

        verify(harness.viewPort(), never()).searchNext();
        verify(harness.viewPort(), never()).openSearchPrompt(any());
    }

    @Test
    void nextMatchPagerKeyNavigatesWhenASearchIsActive() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, typed('n'));
        flushEdt();

        verify(harness.viewPort()).searchNext();
    }

    @Test
    void f3StillOpensThePromptWhenNoSearchIsActive() throws Exception {
        Harness harness = harness(false);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0));
        flushEdt();

        verify(harness.viewPort()).searchNext();
    }

    @Test
    void desktopNextAndPreviousReopenThePromptWhenTheSearchEndedWithoutMatches() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));
        when(harness.viewPort().searchEndedWithoutMatches()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        flushEdt();

        verify(harness.viewPort(), times(4)).openSearchPrompt("timeout");
        verify(harness.viewPort(), never()).searchNext();
        verify(harness.viewPort(), never()).searchPrevious();
    }

    @Test
    void pagerNextAndPreviousStayInertWhenTheSearchEndedWithoutMatches() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));
        when(harness.viewPort().searchEndedWithoutMatches()).thenReturn(true);

        fire(harness, typed('n'));
        fire(harness, typed('N'));
        flushEdt();

        verify(harness.viewPort(), never()).openSearchPrompt(any());
        verify(harness.viewPort()).searchNext();
        verify(harness.viewPort()).searchPrevious();
    }

    @Test
    void capitalNAnswersNoWhileAPromptShows() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        dispatchSequence(harness.panel(), KeyEvent.VK_N, 'N', true);
        flushEdt();

        verify(harness.viewPort()).actionAcceptBoundaryExpansion(false);
        verify(harness.viewPort(), never()).searchPrevious();
    }

    @Test
    void capitalNOutsideAPromptGoesToThePreviousMatch() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, typed('N'));
        flushEdt();

        verify(harness.viewPort()).searchPrevious();
        verify(harness.viewPort(), never()).actionAcceptBoundaryExpansion(false);
    }

    @Test
    void spaceAnswersYesWhileAPromptShows() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        flushEdt();

        verify(harness.viewPort()).actionAcceptBoundaryExpansion(true);
        verify(harness.cursorManager(), never()).moveCursor(anyInt(), anyInt(), eq(false));
    }

    @Test
    void spacePagesDownOtherwise() throws Exception {
        Harness harness = harness(false);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        flushEdt();

        verify(harness.cursorManager()).moveCursor(harness.cursorManager().getLinesPerPage(), 0, false);
        verify(harness.viewPort(), never()).actionAcceptBoundaryExpansion(true);
    }

    @Test
    void enterAnswersYesWhileAPromptShows() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        flushEdt();

        verify(harness.viewPort()).actionAcceptBoundaryExpansion(true);
        verify(harness.viewPort(), never()).openLogLineDetails(any());
    }

    @Test
    void yAcceptsARecentreOfferInTheMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);
        when(harness.viewPort().isMatchesView()).thenReturn(true);

        fire(harness, typed('Y'));
        flushEdt();

        verify(harness.viewPort()).actionAcceptBoundaryExpansion(true);
        verify(harness.viewPort(), never()).showMatchInLog();
    }

    @Test
    void spaceAndEnterAcceptARecentreOfferInTheMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);
        when(harness.viewPort().isMatchesView()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        flushEdt();

        verify(harness.viewPort(), times(2)).actionAcceptBoundaryExpansion(true);
        verify(harness.viewPort(), never()).showMatchInLog();
        verify(harness.cursorManager(), never()).moveCursor(anyInt(), anyInt(), eq(false));
    }

    @Test
    void nDeclinesARecentreOfferInTheMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);
        when(harness.viewPort().isMatchesView()).thenReturn(true);

        fire(harness, typed('N'));
        flushEdt();

        verify(harness.viewPort()).actionAcceptBoundaryExpansion(false);
        verify(harness.viewPort(), never()).searchPrevious();
    }

    @Test
    void escDeclinesARecentreOfferInTheMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);
        when(harness.viewPort().isMatchesView()).thenReturn(true);
        when(harness.viewPort().actionAcceptBoundaryExpansion(false)).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort()).actionAcceptBoundaryExpansion(false);
        verify(harness.viewPort(), never()).backToLog();
        verify(harness.viewPort(), never()).cancelSearch();
    }

    @Test
    void guardedKeysDoNotFireWhileATextFieldHasFocus() throws Exception {
        Harness harness = harness(true);

        fire(harness, typed('/'));
        fire(harness, typed('n'));
        fire(harness, typed('N'));
        fire(harness, typed('&'));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        flushEdt();

        verify(harness.viewPort(), never()).openSearchPrompt(any());
        verify(harness.viewPort(), never()).searchNext();
        verify(harness.viewPort(), never()).searchPrevious();
        verify(harness.viewPort(), never()).toggleMatches();
        verify(harness.viewPort(), never()).actionAcceptBoundaryExpansion(true);
    }

    @Test
    void unguardedKeysStillFireWhileATextFieldHasFocus() throws Exception {
        Harness harness = harness(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort()).searchNext();
        verify(harness.viewPort()).actionAcceptBoundaryExpansion(false);
    }

    @Test
    void escapeKeepsTheCancelChain() throws Exception {
        Harness harness = harness(false);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));

        // Both strokes reach the same chain: time panel, prompt, search mode, selection, search.
        verify(harness.viewPort(), org.mockito.Mockito.times(2)).closeTimePanel();
        verify(harness.viewPort(), org.mockito.Mockito.times(2)).actionAcceptBoundaryExpansion(false);
        verify(harness.viewPort(), org.mockito.Mockito.times(2)).cancelSearchMode();
        verify(harness.viewPort(), org.mockito.Mockito.times(2)).cancelSearch();
        verify(harness.cursorManager(), org.mockito.Mockito.times(2)).clearSelection();
    }

    @Test
    void gAndROpenTheTimePanelWhichThenKeepsTheLogKeys() throws Exception {
        Harness harness = harness(false);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_G, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_R, 0));
        flushEdt();
        verify(harness.viewPort()).openTimePanel(TimePanel.Focus.AT);
        verify(harness.viewPort()).openTimePanel(TimePanel.Focus.FROM);

        when(harness.viewPort().isTimePanelOpen()).thenReturn(true);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_G, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0));
        flushEdt();
        verify(harness.viewPort()).openTimePanel(TimePanel.Focus.AT);
        verify(harness.viewPort(), never()).searchNext();
    }

    @Test
    void f4AndAmpersandToggleTheMatchesView() throws Exception {
        Harness harness = harness(false);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0));
        fire(harness, typed('&'));

        verify(harness.viewPort(), org.mockito.Mockito.times(2)).toggleMatches();
    }

    @Test
    void plusNarrowsAndMinusExcludesInTheLogView() throws Exception {
        Harness harness = harness(false);

        fire(harness, typed('+'));
        fire(harness, typed('-'));
        flushEdt();

        verify(harness.viewPort()).narrowScopeToSearch();
        verify(harness.viewPort()).excludeSearchFromScope();
    }

    @Test
    void plusNarrowsAndMinusExcludesInTheMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isMatchesView()).thenReturn(true);

        fire(harness, typed('+'));
        fire(harness, typed('-'));
        flushEdt();

        verify(harness.viewPort()).narrowScopeToSearch();
        verify(harness.viewPort()).excludeSearchFromScope();
    }

    @Test
    void plusAndMinusAreGuardedWhileATextFieldHasFocus() throws Exception {
        Harness harness = harness(true);

        fire(harness, typed('+'));
        fire(harness, typed('-'));
        flushEdt();

        verify(harness.viewPort(), never()).narrowScopeToSearch();
        verify(harness.viewPort(), never()).excludeSearchFromScope();
    }

    @Test
    void plusAndMinusDoNothingWhileAPromptShows() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        fire(harness, typed('+'));
        fire(harness, typed('-'));
        flushEdt();

        verify(harness.viewPort(), never()).narrowScopeToSearch();
        verify(harness.viewPort(), never()).excludeSearchFromScope();
    }

    @Test
    void hTogglesTheKeyboardHelp() throws Exception {
        Harness harness = harness(false);

        dispatchSequence(harness.panel(), KeyEvent.VK_H, 'h', false);
        flushEdt();

        verify(harness.viewPort()).showKeyboardHelp();
    }

    @Test
    void hIsGuardedWhileATextFieldHasFocus() throws Exception {
        Harness harness = harness(true);

        fire(harness, typed('h'));
        flushEdt();

        verify(harness.viewPort(), never()).showKeyboardHelp();
    }

    @Test
    void escapeClosesTheKeyboardHelpBeforeAnythingElse() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().closeKeyboardHelp()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort()).closeKeyboardHelp();
        // The chain stops once the help overlay consumed the escape.
        verify(harness.viewPort(), never()).actionAcceptBoundaryExpansion(false);
        verify(harness.viewPort(), never()).cancelSearch();
    }

    @Test
    void typedNWhileAPromptShowsDeclinesExactlyOnceAndDoesNotSearch() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        dispatchSequence(harness.panel(), KeyEvent.VK_N, 'n', false);
        flushEdt();

        verify(harness.viewPort(), org.mockito.Mockito.times(1)).actionAcceptBoundaryExpansion(false);
        verify(harness.viewPort(), never()).searchNext();
        verify(harness.viewPort(), never()).searchPrevious();
    }

    @Test
    void typedShiftNWhileAPromptShowsDeclinesExactlyOnce() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        dispatchSequence(harness.panel(), KeyEvent.VK_N, 'N', true);
        flushEdt();

        verify(harness.viewPort(), org.mockito.Mockito.times(1)).actionAcceptBoundaryExpansion(false);
        verify(harness.viewPort(), never()).searchPrevious();
    }

    @Test
    void typedYAndShiftYAcceptOnce() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        dispatchSequence(harness.panel(), KeyEvent.VK_Y, 'y', false);
        flushEdt();
        dispatchSequence(harness.panel(), KeyEvent.VK_Y, 'Y', true);
        flushEdt();

        verify(harness.viewPort(), org.mockito.Mockito.times(2)).actionAcceptBoundaryExpansion(true);
    }

    @Test
    void typedNWithAnActiveSearchCallsNextMatchExactlyOnce() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        dispatchSequence(harness.panel(), KeyEvent.VK_N, 'n', false);
        flushEdt();

        verify(harness.viewPort(), org.mockito.Mockito.times(1)).searchNext();
        verify(harness.viewPort(), never()).actionAcceptBoundaryExpansion(false);
    }

    // === Matches view context ===

    @Test
    void enterInMatchesViewShowsTheMatchInLog() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isMatchesView()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        flushEdt();

        verify(harness.viewPort()).showMatchInLog();
        verify(harness.viewPort(), never()).openLogLineDetails(any());
    }

    @Test
    void shiftEnterInMatchesViewOpensDetails() throws Exception {
        Harness harness = detailsHarness();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK));
        flushEdt();

        verify(harness.viewPort()).openLogLineDetails(any());
        verify(harness.viewPort(), never()).showMatchInLog();
    }

    @Test
    void typedIInMatchesViewOpensDetails() throws Exception {
        Harness harness = detailsHarness();

        fire(harness, typed('i'));
        flushEdt();

        verify(harness.viewPort()).openLogLineDetails(any());
        verify(harness.viewPort(), never()).showMatchInLog();
    }

    @Test
    void f4AndAmpersandShowTheMatchInLogInMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isMatchesView()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0));
        fire(harness, typed('&'));

        verify(harness.viewPort(), org.mockito.Mockito.times(2)).showMatchInLog();
    }

    @Test
    void escapeInMatchesViewLeavesMatchesBeforeCursorAndSearch() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isMatchesView()).thenReturn(true);
        when(harness.viewPort().backToLog()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort()).backToLog();
        // The chain stops once backToLog consumed the escape.
        verify(harness.cursorManager(), never()).clearSelection();
        verify(harness.viewPort(), never()).cancelSearch();
    }

    @Test
    void promptWinsOverMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isMatchesView()).thenReturn(true);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        flushEdt();

        verify(harness.viewPort()).actionAcceptBoundaryExpansion(true);
        verify(harness.viewPort(), never()).showMatchInLog();
    }

    // === Double-tap Tab selects the region under the cursor ===

    @Test
    void aDoubleTapSelectsTheRegionUnderTheCursor() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = cursorHarness(false, false, now);
        harness.cursorManager().ensureCursorAt(0);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        now.set(100);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        flushEdt();

        verify(harness.cursorManager(), times(1)).selectRegionAtCursor();
    }

    @Test
    void aSingleTapDoesNothing() throws Exception {
        Harness harness = cursorHarness(false, false, new AtomicLong(0));
        harness.cursorManager().ensureCursorAt(0);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        flushEdt();

        verify(harness.cursorManager(), never()).selectRegionAtCursor();
    }

    @Test
    void tapsSeparatedByTheDoubleClickIntervalAreTwoSingleTaps() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = cursorHarness(false, false, now);
        harness.cursorManager().ensureCursorAt(0);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        now.set(5_000);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        flushEdt();

        verify(harness.cursorManager(), never()).selectRegionAtCursor();
    }

    @Test
    void anotherKeyBetweenTheTapsDisarmsTheDoubleTap() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = cursorHarness(false, false, now);
        harness.cursorManager().ensureCursorAt(0);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        now.set(100);
        fire(harness, typed('a'));
        now.set(200);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0));
        flushEdt();

        verify(harness.cursorManager(), never()).selectRegionAtCursor();
    }

    // === Esc twice ends an active search ===

    @Test
    void firstEscWithAnActiveSearchOnlyClearsTheTransientState() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), never()).cancelSearch();
        verify(harness.viewPort()).clearSearchTransient("Esc");
    }

    @Test
    void secondEscWithinTheWindowEndsTheSearch() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        now.set(1_000);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), times(1)).clearSearchTransient("Esc");
        verify(harness.viewPort(), times(1)).cancelSearch();
    }

    @Test
    void secondEscAfterTheWindowOnlyClearsAgain() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        now.set(2_000);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), never()).cancelSearch();
        verify(harness.viewPort(), times(2)).clearSearchTransient("Esc");
    }

    @Test
    void anotherKeyInBetweenDisarmsTheGuard() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        now.set(100);
        fire(harness, typed('n'));
        now.set(200);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort()).searchNext();
        verify(harness.viewPort(), never()).cancelSearch();
        verify(harness.viewPort(), times(2)).clearSearchTransient("Esc");
    }

    @Test
    void escInTheMatchesViewStillLeavesInOnePress() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);
        when(harness.viewPort().isMatchesView()).thenReturn(true);
        when(harness.viewPort().backToLog()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort()).backToLog();
        verify(harness.viewPort(), never()).clearSearchTransient(any());
        verify(harness.viewPort(), never()).cancelSearch();
    }

    @Test
    void escAfterLeavingTheMatchesViewIsAFirstLogViewEsc() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);
        when(harness.viewPort().isMatchesView()).thenReturn(true, false);
        when(harness.viewPort().backToLog()).thenReturn(true, false);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), never()).cancelSearch();
        verify(harness.viewPort(), times(1)).clearSearchTransient("Esc");
    }

    @Test
    void escClearsTheSelectionBeforeTheSearchGuard() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);
        doReturn(true, false).when(harness.cursorManager()).clearSelection();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), never()).clearSearchTransient(any());
        verify(harness.viewPort(), never()).cancelSearch();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), times(1)).clearSearchTransient("Esc");
        verify(harness.viewPort(), never()).cancelSearch();
    }

    @Test
    void escConsumedByAnEarlierStepDisarmsTheGuard() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);
        doReturn(false, true, false).when(harness.cursorManager()).clearSelection();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        now.set(100);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        now.set(200);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), never()).cancelSearch();
        verify(harness.viewPort(), times(2)).clearSearchTransient("Esc");
    }

    // === Copy as ===

    private static final KeyStroke CTRL_SHIFT_C = KeyStroke.getKeyStroke(KeyEvent.VK_C,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);

    @Test
    void ctrlShiftCOpensTheCopyAsMenuInTheLogMatchesAndBookmarksViews() throws Exception {
        Harness harness = harness(false);

        fire(harness, CTRL_SHIFT_C);
        when(harness.viewPort().isMatchesView()).thenReturn(true);
        fire(harness, CTRL_SHIFT_C);
        when(harness.viewPort().isMatchesView()).thenReturn(false);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);
        fire(harness, CTRL_SHIFT_C);

        verify(harness.viewPort(), times(3)).showCopyAsMenu();
    }

    @Test
    void ctrlShiftCIsGuardedWhileTyping() throws Exception {
        Harness harness = harness(true);

        fire(harness, CTRL_SHIFT_C);

        verify(harness.viewPort(), never()).showCopyAsMenu();
    }

    // === Bookmarks view ===

    private static final KeyStroke CTRL_SHIFT_B = KeyStroke.getKeyStroke(KeyEvent.VK_B,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    private static final KeyStroke CTRL_B = KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke ENTER = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
    private static final KeyStroke F2 = KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0);
    private static final KeyStroke F4 = KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0);
    private static final KeyStroke ESC = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

    @Test
    void capitalMAndCtrlShiftBOpenTheBookmarksViewFromTheLogAndMatchesViews() throws Exception {
        Harness harness = harness(false);

        fire(harness, typed('M'));
        fire(harness, CTRL_SHIFT_B);
        when(harness.viewPort().isMatchesView()).thenReturn(true);
        fire(harness, typed('M'));
        fire(harness, CTRL_SHIFT_B);

        verify(harness.viewPort(), times(4)).toggleBookmarks();
    }

    @Test
    void f2TogglesTheBookmarksViewInEveryView() throws Exception {
        Harness harness = harness(false);

        fire(harness, F2);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);
        fire(harness, F2);

        verify(harness.viewPort(), times(2)).toggleBookmarks();
    }

    @Test
    void theBookmarksViewKeysAreGuardedForTyping() throws Exception {
        Harness harness = harness(true);

        fire(harness, typed('M'));
        fire(harness, CTRL_SHIFT_B);

        verify(harness.viewPort(), never()).showBookmarks();
    }

    @Test
    void inTheBookmarksViewMAndCtrlBRemoveAndMDoesNotReopen() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);

        fire(harness, typed('m'));
        fire(harness, CTRL_B);
        fire(harness, typed('M'));

        verify(harness.viewPort(), times(2)).toggleBookmark();
        verify(harness.viewPort(), never()).showBookmarks();
    }

    @Test
    void enterShowsTheEventInTheLogAndF4AndAmpersandGoToTheMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);

        fire(harness, ENTER);
        verify(harness.viewPort()).showMatchInLog();
        verify(harness.viewPort(), never()).openLogLineDetails(any());

        fire(harness, F4);
        fire(harness, typed('&'));
        verify(harness.viewPort(), times(2)).toggleMatches();
        verify(harness.viewPort(), times(1)).showMatchInLog();
    }

    @Test
    void escapeInTheBookmarksViewGoesBackBeforeTheSelectionAndSearch() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);
        when(harness.viewPort().backToLog()).thenReturn(true);
        doReturn(true).when(harness.cursorManager()).clearSelection();

        fire(harness, ESC);
        flushEdt();

        verify(harness.viewPort()).backToLog();
        verify(harness.cursorManager(), never()).clearSelection();
        verify(harness.viewPort(), never()).clearSearchTransient(any());
        verify(harness.viewPort(), never()).cancelSearch();
    }

    @Test
    void escapeStepsRunInOrderWithTheBookmarksViewBeforeTheSelection() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);
        when(harness.viewPort().backToLog()).thenReturn(false);

        fire(harness, ESC);

        var order = inOrder(harness.viewPort(), harness.cursorManager());
        order.verify(harness.viewPort()).closeTimePanel();
        order.verify(harness.viewPort()).closeKeyboardHelp();
        order.verify(harness.viewPort()).actionAcceptBoundaryExpansion(false);
        order.verify(harness.viewPort()).cancelSearchMode();
        order.verify(harness.viewPort()).backToLog();
        order.verify(harness.cursorManager()).clearSelection();
    }

    @Test
    void ctrlCWithoutASelectionCopiesAllBookmarksInTheBookmarksView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);
        when(harness.viewPort().copyAllBookmarks()).thenReturn(true);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));

        verify(harness.viewPort()).copyAllBookmarks();
        verify(harness.cursorManager(), never()).copyToClipboard();
    }

    @Test
    void ctrlCWithASelectionCopiesTheSelectionInTheBookmarksView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isBookmarksView()).thenReturn(true);
        doReturn(java.util.List.of(1, 2)).when(harness.cursorManager()).selectedRows();
        doNothing().when(harness.cursorManager()).copyToClipboard();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));

        verify(harness.viewPort(), never()).copyAllBookmarks();
        verify(harness.cursorManager()).copyToClipboard();
    }

    @Test
    void ctrlCOutsideTheBookmarksViewIsTheOrdinaryCopy() throws Exception {
        Harness harness = harness(false);
        doNothing().when(harness.cursorManager()).copyToClipboard();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));

        verify(harness.viewPort(), never()).copyAllBookmarks();
        verify(harness.cursorManager()).copyToClipboard();
    }

    // === Cursor mode (F7 / v) ===

    @Test
    void f7SwitchesCursorModeOnAndOff() throws Exception {
        Harness harness = cursorHarness(false);
        assertThat(harness.cursorManager().isCursorMode()).isFalse();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        assertThat(harness.cursorManager().isCursorMode()).isTrue();
        assertThat(harness.cursorManager().getCursor()).isPresent();
        assertThat(harness.cursorManager().getCursor().get().line).as("top visible row").isEqualTo(1);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        assertThat(harness.cursorManager().isCursorMode()).isFalse();
        assertThat(harness.cursorManager().getCursor()).isEmpty();
        assertThat(harness.cursorManager().getMark()).isEmpty();
    }

    @Test
    void typedVIsThePagerAliasOfTheCursorToggle() throws Exception {
        Harness harness = cursorHarness(false);

        fire(harness, typed('v'));
        assertThat(harness.cursorManager().isCursorMode()).isTrue();

        fire(harness, typed('v'));
        assertThat(harness.cursorManager().isCursorMode()).isFalse();
    }

    @Test
    void theCursorComesBackWhereItWasAndOffClearsTheSelection() throws Exception {
        Harness harness = cursorHarness(false);
        KeyStroke f7 = KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0);

        fire(harness, f7);
        harness.cursorManager().moveCursor(5, 0, true);
        assertThat(harness.cursorManager().getMark()).isPresent();
        fire(harness, f7);
        assertThat(harness.cursorManager().getMark()).isEmpty();

        fire(harness, f7);
        assertThat(harness.cursorManager().getCursor().get().line).isEqualTo(6);
        assertThat(harness.cursorManager().getMark()).isEmpty();
    }

    @Test
    void theCursorToggleIsGuardedForTypingButF7IsNot() throws Exception {
        Harness harness = cursorHarness(false, true);

        fire(harness, typed('v'));
        assertThat(harness.cursorManager().isCursorMode()).isFalse();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        assertThat(harness.cursorManager().isCursorMode()).isTrue();
    }

    @Test
    void f7AndVToggleTheCursorInTheMatchesViewToo() throws Exception {
        Harness harness = cursorHarness(true);
        harness.cursorManager().ensureCursorAt(0);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        assertThat(harness.cursorManager().isCursorMode()).isFalse();
        assertThat(harness.cursorManager().getCursor()).isEmpty();

        fire(harness, typed('v'));
        assertThat(harness.cursorManager().isCursorMode()).isTrue();
        assertThat(harness.cursorManager().getCursor().get().line).as("back on the row it was on").isEqualTo(0);
    }

    @Test
    void scrollLockKeyTogglesCursorModeWhenTheLockStateIsUnreadable() throws Exception {
        Harness harness = cursorHarness(false, false, new AtomicLong(0), Optional::empty);
        KeyStroke scrollLock = KeyStroke.getKeyStroke(KeyEvent.VK_SCROLL_LOCK, 0);

        fire(harness, scrollLock);
        assertThat(harness.cursorManager().isCursorMode()).isTrue();

        fire(harness, scrollLock);
        assertThat(harness.cursorManager().isCursorMode()).isFalse();
    }

    @Test
    void scrollLockKeyFollowsTheReadableLockState() throws Exception {
        var locked = new AtomicReference<>(Optional.of(true));
        Harness harness = cursorHarness(false, false, new AtomicLong(0), locked::get);
        KeyStroke scrollLock = KeyStroke.getKeyStroke(KeyEvent.VK_SCROLL_LOCK, 0);

        fire(harness, scrollLock);
        assertThat(harness.cursorManager().isCursorMode()).as("lock on is page mode").isFalse();

        locked.set(Optional.of(false));
        fire(harness, scrollLock);
        assertThat(harness.cursorManager().isCursorMode()).as("lock off is cursor mode").isTrue();
    }

    @Test
    void scrollLockKeyIsGuardedWhileTyping() throws Exception {
        Harness harness = cursorHarness(false, true, new AtomicLong(0), Optional::empty);

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_SCROLL_LOCK, 0));

        assertThat(harness.cursorManager().isCursorMode()).isFalse();
    }

    @Test
    void escInCursorModeKeepsTheCursor() throws Exception {
        Harness harness = cursorHarness(false);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));

        assertThat(harness.cursorManager().isCursorMode()).isTrue();
        assertThat(harness.cursorManager().getCursor()).isPresent();
    }

    @Test
    void escClearsTheSelectionButKeepsTheCursor() throws Exception {
        Harness harness = cursorHarness(false);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        harness.cursorManager().moveCursor(3, 0, true);
        assertThat(harness.cursorManager().getMark()).isPresent();
        int line = harness.cursorManager().getCursor().get().line;

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));

        assertThat(harness.cursorManager().getMark()).isEmpty();
        assertThat(harness.cursorManager().isCursorMode()).isTrue();
        assertThat(harness.cursorManager().getCursor().get().line).isEqualTo(line);
        verify(harness.viewPort(), never()).cancelSearch();
    }

    @Test
    void escClearsAMarkSetWithCtrlSpace() throws Exception {
        Harness harness = cursorHarness(false);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        assertThat(harness.cursorManager().getMark()).isPresent();

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));

        assertThat(harness.cursorManager().getMark()).isEmpty();
        assertThat(harness.cursorManager().isCursorMode()).isTrue();
    }

    @Test
    void escWalksTheCancelChainInOrder() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = searchHarness(now);
        InOrder order = inOrder(harness.viewPort(), harness.cursorManager());

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        order.verify(harness.viewPort()).closeTimePanel();
        order.verify(harness.viewPort()).closeKeyboardHelp();
        order.verify(harness.viewPort()).actionAcceptBoundaryExpansion(false);
        order.verify(harness.viewPort()).cancelSearchMode();
        order.verify(harness.viewPort()).backToLog();
        order.verify(harness.cursorManager()).clearSelection();
        order.verify(harness.viewPort()).clearSearchTransient("Esc");
    }

    @Test
    void escTwiceStillEndsTheSearchWhileInCursorMode() throws Exception {
        var now = new AtomicLong(0);
        Harness harness = cursorHarness(false, false, now);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        now.set(500);
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        flushEdt();

        verify(harness.viewPort(), times(1)).clearSearchTransient("Esc");
        verify(harness.viewPort(), times(1)).cancelSearch();
        assertThat(harness.cursorManager().isCursorMode()).isTrue();
    }

    // === Bookmarks (m / Ctrl-B) ===

    @Test
    void mAndCtrlBToggleTheBookmarkInBothViews() throws Exception {
        for (boolean matchesView : new boolean[]{false, true}) {
            Harness harness = cursorHarness(matchesView);

            fire(harness, typed('m'));
            fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
            flushEdt();

            verify(harness.viewPort(), times(2)).toggleBookmark();
        }
    }

    @Test
    void bookmarkKeysAreGuardedWhileTyping() throws Exception {
        Harness harness = cursorHarness(false, true);

        fire(harness, typed('m'));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
        flushEdt();

        verify(harness.viewPort(), never()).toggleBookmark();
    }

    @Test
    void bookmarkKeysDoNothingWhileAYesNoPromptShows() throws Exception {
        Harness harness = cursorHarness(false);
        when(harness.viewPort().isYesNoPromptShowing()).thenReturn(true);

        fire(harness, typed('m'));
        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
        flushEdt();

        verify(harness.viewPort(), never()).toggleBookmark();
    }

    // === Search keys: open the prompt, or move between matches, never both ===

    @Test
    void ctrlFAndSlashOpenThePromptWithTheActiveTermAndNeverMoveToAMatch() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        fire(harness, typed('/'));
        flushEdt();

        verify(harness.viewPort(), times(2)).openSearchPrompt("timeout");
        verify(harness.viewPort(), never()).searchNext();
        verify(harness.viewPort(), never()).searchPrevious();
    }

    @Test
    void ctrlFAlsoOpensThePromptInTheMatchesView() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().isMatchesView()).thenReturn(true);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        fire(harness, typed('/'));
        flushEdt();

        verify(harness.viewPort(), times(2)).openSearchPrompt("timeout");
        verify(harness.viewPort(), never()).searchNext();
    }

    @Test
    void ctrlFStillFiresWhileThePromptHasFocusSoItCanReselectTheTerm() throws Exception {
        Harness harness = harness(true);
        when(harness.viewPort().isSearchInputActive()).thenReturn(true);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt("timeout");
    }

    @Test
    void ctrlFDoesNotFireWhileAnotherTextFieldHasFocus() throws Exception {
        Harness harness = harness(true); // e.g. a field in the index panel or login overlay
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        flushEdt();

        verify(harness.viewPort(), never()).openSearchPrompt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nextAndPreviousKeysNeverOpenThePromptWhileASearchIsActive() throws Exception {
        for (boolean matchesView : new boolean[]{false, true}) {
            Harness harness = harness(false);
            when(harness.viewPort().isMatchesView()).thenReturn(matchesView);
            when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

            fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0));
            fire(harness, typed('n'));
            fire(harness, KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK));
            fire(harness, typed('N'));
            flushEdt();

            verify(harness.viewPort(), times(2)).searchNext();
            verify(harness.viewPort(), times(2)).searchPrevious();
            verify(harness.viewPort(), never()).openSearchPrompt(any());
        }
    }

    @Test
    void theSearchButtonOpensThePromptInsteadOfMovingToAMatch() throws Exception {
        Harness harness = harness(false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        Action button = harness.panel().getActionMap().get(Actions.SEARCH);
        button.actionPerformed(new ActionEvent(harness.panel(), ActionEvent.ACTION_PERFORMED, "button"));
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt("timeout");
        verify(harness.viewPort(), never()).searchNext();
    }

    @Test
    void theScopeAndGotoButtonsWorkWhileAnEditableFieldHasFocus() throws Exception {
        Harness harness = harness(true);

        Action login = harness.panel().getActionMap().get(Actions.LOGIN);
        login.actionPerformed(new ActionEvent(harness.panel(), ActionEvent.ACTION_PERFORMED, "button"));
        Action go = harness.panel().getActionMap().get(Actions.GOTO);
        go.actionPerformed(new ActionEvent(harness.panel(), ActionEvent.ACTION_PERFORMED, "button"));
        flushEdt();

        verify(harness.viewPort()).actionLogin();
        verify(harness.viewPort()).openTimePanel(TimePanel.Focus.AT);
    }

    // === Copy and the search prompt's selection ===

    private static final KeyStroke CTRL_C = KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke CTRL_F = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke SHIFT_RIGHT = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK);

    @Test
    void logCopyIsNotDispatchedWhileTheSearchInputHasFocus() throws Exception {
        Harness harness = harness(true);

        fire(harness, CTRL_C);
        flushEdt();

        verify(harness.cursorManager(), never()).copyToClipboard();
    }

    @Test
    void logCopyIsDispatchedWhileNoTextFieldHasFocus() throws Exception {
        Harness harness = harness(false);

        fire(harness, CTRL_C);
        flushEdt();

        verify(harness.cursorManager()).copyToClipboard();
    }

    @Test
    void ctrlFPrefillsThePromptWithAOneLineSelection() throws Exception {
        Harness harness = cursorHarness(false, false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));
        selectMessage(harness, 0, 3);

        fire(harness, CTRL_F);
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt("msg");
    }

    @Test
    void ctrlFFallsBackToTheActiveTermWithoutASelection() throws Exception {
        Harness harness = cursorHarness(false, false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));

        fire(harness, CTRL_F);
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt("timeout");
    }

    @Test
    void ctrlFFallsBackToTheActiveTermForAMultiLineSelection() throws Exception {
        Harness harness = cursorHarness(false, false);
        when(harness.viewPort().getActiveSearchTerm()).thenReturn(Optional.of("timeout"));
        harness.cursorManager().setCursorFromClick(0, messageColumn(), false);
        harness.cursorManager().setCursorFromClick(1, messageColumn() + 3, true);

        fire(harness, CTRL_F);
        flushEdt();

        verify(harness.viewPort()).openSearchPrompt("timeout");
    }

    @Test
    void aSelectionMadeWhilePromptIsOpenIsOfferedToIt() throws Exception {
        Harness harness = cursorHarness(false, false);
        when(harness.viewPort().isSearchInputActive()).thenReturn(true);
        selectMessage(harness, 0, 0);

        fire(harness, SHIFT_RIGHT);
        fire(harness, SHIFT_RIGHT);
        fire(harness, SHIFT_RIGHT);
        flushEdt();

        verify(harness.viewPort()).offerSelectionToSearchPrompt("msg");
    }

    @Test
    void aSelectionIsNotOfferedWhenThePromptIsClosed() throws Exception {
        Harness harness = cursorHarness(false, false);
        selectMessage(harness, 0, 0);

        fire(harness, SHIFT_RIGHT);
        flushEdt();

        verify(harness.viewPort(), never()).offerSelectionToSearchPrompt(any());
    }

    /** Puts the cursor on the first message column of line 0 and extends the selection {@code by} columns. */
    private static void selectMessage(Harness harness, int line, int by) throws Exception {
        harness.cursorManager().setCursorFromClick(line, messageColumn(), false);
        if (by > 0) {
            harness.cursorManager().setCursorFromClick(line, messageColumn() + by, true);
        }
    }

    private static int messageColumn() {
        DateTimeFormatterManagerTestUtil.initDTM();
        return DecoratedLogLine.getPreambleLength();
    }

    // === Harness ===

    private record Harness(JPanel panel, LogViewPort viewPort, CursorManager cursorManager, InputHandler handler) {}

    private static Harness harness(boolean textFieldFocused) {
        JPanel panel = new JPanel();
        JPanel container = new JPanel();
        LogViewPort viewPort = mock(LogViewPort.class);
        when(viewPort.getActiveSearchTerm()).thenReturn(Optional.empty());
        CursorManager cursorManager = spy(new CursorManager(mock(LogDataProvider.class), viewPort));
        cursorManager.updateSize(50, 200);
        // The cancel chain's selection step is exercised by CursorManagerTest.
        doReturn(false).when(cursorManager).clearSelection();
        InputHandler handler = new InputHandler(cursorManager, viewPort, () -> textFieldFocused);
        handler.setup(panel, container);
        return new Harness(panel, viewPort, cursorManager, handler);
    }

    /** A log-view harness with an active search and a controllable clock (millis in {@code now}). */
    private static Harness searchHarness(AtomicLong now) {
        JPanel panel = new JPanel();
        JPanel container = new JPanel();
        LogViewPort viewPort = mock(LogViewPort.class);
        when(viewPort.getActiveSearchTerm()).thenReturn(Optional.of("timeout"));
        CursorManager cursorManager = spy(new CursorManager(mock(LogDataProvider.class), viewPort));
        cursorManager.updateSize(50, 200);
        doReturn(false).when(cursorManager).clearSelection();
        InputHandler handler = new InputHandler(cursorManager, viewPort, () -> false, now::get);
        handler.setup(panel, container);
        return new Harness(panel, viewPort, cursorManager, handler);
    }

    /** A Matches-view harness whose provider serves a loaded line, so details opens. */
    private static Harness detailsHarness() throws Exception {
        Harness harness = cursorHarness(true);
        harness.cursorManager().ensureCursorAt(0);
        return harness;
    }

    private static Harness cursorHarness(boolean matchesView) throws Exception {
        return cursorHarness(matchesView, false);
    }

    private static Harness cursorHarness(boolean matchesView, boolean textFieldFocused) throws Exception {
        return cursorHarness(matchesView, textFieldFocused, new AtomicLong(0));
    }

    /**
     * A harness around a real cursor manager over a provider that serves loaded lines, so the cursor
     * can really move; the view context and the clock are controllable.
     */
    private static Harness cursorHarness(boolean matchesView, boolean textFieldFocused, AtomicLong now)
            throws Exception {
        return cursorHarness(matchesView, textFieldFocused, now, Optional::empty);
    }

    /** Like {@link #cursorHarness(boolean, boolean, AtomicLong)} with a Scroll Lock state the platform reports. */
    private static Harness cursorHarness(boolean matchesView, boolean textFieldFocused, AtomicLong now,
            Supplier<Optional<Boolean>> scrollLockState) throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        JPanel panel = new JPanel();
        JPanel container = new JPanel();
        LogViewPort viewPort = mock(LogViewPort.class);
        when(viewPort.getActiveSearchTerm()).thenReturn(Optional.empty());
        when(viewPort.isMatchesView()).thenReturn(matchesView);
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
        InputHandler handler = new InputHandler(cursorManager, viewPort, () -> textFieldFocused, now::get,
                scrollLockState);
        handler.setup(panel, container);
        return new Harness(panel, viewPort, cursorManager, handler);
    }

    private static KeyStroke typed(char keyChar) {
        return KeyStroke.getKeyStroke(Character.valueOf(keyChar), 0);
    }

    /** Fires the keystroke through the panel's input map, as Swing would on a key event. */
    private static void fire(Harness harness, KeyStroke stroke) {
        InputMap im = harness.panel().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        Object actionKey = im.get(stroke);
        assertThat(actionKey).as("binding for %s", KeyMap.format(stroke)).isNotNull();
        Action action = harness.panel().getActionMap().get(actionKey);
        assertThat(action).as("action %s", actionKey).isNotNull();
        action.actionPerformed(new ActionEvent(harness.panel(), ActionEvent.ACTION_PERFORMED, "test"));
    }

    /** Dispatches KEY_PRESSED then KEY_TYPED in the order a real key produces them. */
    private static void dispatchSequence(JPanel panel, int keyCode, char keyChar, boolean shift) {
        int modifiers = shift ? InputEvent.SHIFT_DOWN_MASK : 0;
        KeyStroke pressed = KeyStroke.getKeyStroke(keyCode, modifiers);
        KeyStroke typed = KeyStroke.getKeyStroke(Character.valueOf(keyChar), 0);
        fire(panel, pressed);
        fire(panel, typed);
    }

    private static void fire(JPanel panel, KeyStroke stroke) {
        InputMap im = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        Object actionKey = im.get(stroke);
        if (actionKey == null) {
            return;
        }
        Action action = panel.getActionMap().get(actionKey);
        if (action == null) {
            return;
        }
        action.actionPerformed(new ActionEvent(panel, ActionEvent.ACTION_PERFORMED, "test"));
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }
}
