package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.ui.clip.ClipboardAccess;
import se.grynna.pager.ui.details.DetailsPanel;
import se.grynna.pager.ui.time.TimePanel;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static se.grynna.pager.ui.Actions.*;

@Slf4j
public class InputHandler {
    // This class binds explicitly to CTRL, not to the platform menu shortcut mask.
    // Keep bindings local to the mapping section to avoid platform-dependent surprises.

    final LogViewPort viewPort;
    final CursorManager cursorManager;
    final MouseAdapter clickHandler;
    private final int wheelAccelerator = 5;

    /** The single source of keystrokes; {@link #setupKeyMappings} installs from it. */
    private final KeyMap keyMap = KeyMap.defaults();

    /** One handler per implemented action; actions without one stay unbound. */
    private final Map<UiAction, KeyHandler> handlers = new EnumMap<>(UiAction.class);

    /**
     * Test seam for the focus guard: {@link #isEditableFieldFocused()} needs a
     * real focused window, which unit tests cannot rely on.
     */
    private final BooleanSupplier editableFieldFocused;

    /** Esc twice ends an active search: the first press only clears its transient state. */
    private final PressAgainGuard endSearchGuard = new PressAgainGuard();

    /** Millisecond clock for {@link #endSearchGuard}; a seam for tests. */
    private final LongSupplier clock;

    /** Keeps cursor mode in step with the Scroll Lock key and, where readable, its state. */
    private final ScrollLockSync scrollLock;

    /** What the search prompt opens with when it is not given a selection. */
    private SearchPrefill searchPrefill = new SearchPrefill(ClipboardAccess.system());

    /** The window whose activation re-syncs the Scroll Lock state; set once the viewer is in a window. */
    private Window scrollLockWindow;

    /** The last press in the log only closed the time panel; its drag, release and click are ignored. */
    private boolean pressClosedTimePanel;

    /**
     * The {@link KeyBinding.Context#ANY} actions that still work while the details are open: they drive the details
     * panel (cursor keys, paging, Ctrl-C) or leave it (Esc, help, login). The rest, selection, search and goto among
     * them, do nothing there.
     */
    private static final Set<UiAction> ANY_IN_DETAILS = EnumSet.of(UiAction.REPAINT, UiAction.CURSOR_UP,
            UiAction.CURSOR_DOWN, UiAction.CURSOR_UP_FAST, UiAction.CURSOR_DOWN_FAST, UiAction.PAGE_UP,
            UiAction.PAGE_DOWN, UiAction.COPY, UiAction.CANCEL, UiAction.HELP, UiAction.LOGIN);

    /** Actions that move focus; they run deferred so the key event completes first. */
    private static final Set<UiAction> DEFERRED = EnumSet.of(UiAction.LOGIN, UiAction.GOTO, UiAction.SET_RANGE,
            UiAction.OPEN_SEARCH, UiAction.OPEN_SEARCH_BACKWARD, UiAction.NEXT_MATCH, UiAction.PREVIOUS_MATCH);

    public InputHandler(CursorManager cursorManager, LogViewPort viewPort) {
        this(cursorManager, viewPort, InputHandler::isEditableFieldFocused, System::currentTimeMillis);
    }

    InputHandler(CursorManager cursorManager, LogViewPort viewPort, BooleanSupplier editableFieldFocused) {
        this(cursorManager, viewPort, editableFieldFocused, System::currentTimeMillis);
    }

    InputHandler(CursorManager cursorManager, LogViewPort viewPort, BooleanSupplier editableFieldFocused,
            LongSupplier clock) {
        this(cursorManager, viewPort, editableFieldFocused, clock, ScrollLockSync::platformLockState);
    }

    InputHandler(CursorManager cursorManager, LogViewPort viewPort, BooleanSupplier editableFieldFocused,
            LongSupplier clock, Supplier<Optional<Boolean>> scrollLockState) {
        this.viewPort = viewPort;
        this.cursorManager = cursorManager;
        this.editableFieldFocused = editableFieldFocused;
        this.clock = clock;
        this.scrollLock = new ScrollLockSync(scrollLockState, cursorManager::isCursorMode, cursorOn -> {
            if (cursorOn) {
                cursorManager.showCursor();
            } else {
                cursorManager.hideCursor();
            }
        });
        registerHandlers();
        this.clickHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                endSearchGuard.reset();
                pressClosedTimePanel = viewPort.closeTimePanel();
                if (pressClosedTimePanel) {
                    return;
                }
                if (e.isPopupTrigger()) {
                    openRowMenu(e);
                    return;
                }
                // Skip multi-click presses — the second press of a double-click (clickCount=2)
                // would overwrite the saved previous selection before mouseClicked can use it
                if (e.getClickCount() > 1) return;
                mouse(e, false);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressClosedTimePanel) {
                    return;
                }
                mouse(e, true);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (pressClosedTimePanel) {
                    return;
                }
                if (e.isPopupTrigger()) {
                    openRowMenu(e);
                }
                // Signal end of drag — selection-echo highlights can now appear
                cursorManager.selectionDone();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (pressClosedTimePanel) {
                    return;
                }
                if (e.getClickCount() > 1) {

                    var charSize = viewPort.getCharacterSize();
                    if (charSize == null) {
                        return;
                    }

                    int line = e.getY() / charSize.height();
                    int col = Math.max(0, e.getX() - LineRenderer.iconSpaceWidth(charSize.height())) / charSize.width();

                    // Matches and Bookmarks views: a double-click on the preamble shows the event in the log;
                    // on the message text it keeps the word selection below.
                    if ((viewPort.isMatchesView() || viewPort.isBookmarksView())
                            && cursorManager.isPreambleClick(col)) {
                        viewPort.showMatchInLog();
                        return;
                    }

                    try {
                        cursorManager.handleDoubleClick(line, col, e.getClickCount());
                        offerSelectionToPrompt();
                    } catch (TemporarySearchException | SearchException | StorageException ex) {
                        log.error(ex.getMessage(), ex);
                    }
                } else if (e.getButton() == MouseEvent.BUTTON1 && !e.isPopupTrigger() && !e.isConsumed()) {
                    activityMenuClickedIcon(e);
                }
            }

            /**
             * Right-click (a popup trigger on press or release, whichever the platform uses) on a loaded row opens
             * the row menu at the pointer. A click outside the selection first moves the cursor to the row, as a
             * left click does; inside the selection the cursor and selection stay.
             */
            private void openRowMenu(MouseEvent e) {
                var charSize = viewPort.getCharacterSize();
                if (charSize == null || e.isConsumed()) {
                    return;
                }
                int visibleLine = e.getY() / charSize.height();
                int col = Math.max(0, e.getX() - LineRenderer.iconSpaceWidth(charSize.height())) / charSize.width();
                int row = cursorManager.getPageStart() + visibleLine;
                try {
                    if (cursorManager.getLogLineAt(row) == null) {
                        return;
                    }
                    if (!cursorManager.selectedRows().contains(row)) {
                        cursorManager.setCursorFromClick(visibleLine, col, false);
                    }
                    e.consume();
                    viewPort.showRowContextMenu(e.getX(), e.getY());
                } catch (TemporarySearchException | SearchException | StorageException ex) {
                    log.warn("openRowMenu: {}", ex.getMessage());
                }
            }

            /**
             * A click (press and release in place, so never a drag) on a loaded row's icon opens the activity popup
             * (narrow/exclude per level) at the pointer. The press has already put the cursor on the row.
             */
            private void activityMenuClickedIcon(MouseEvent e) {
                var charSize = viewPort.getCharacterSize();
                if (charSize == null || e.getX() >= LineRenderer.iconSpaceWidth(charSize.height())) {
                    return;
                }
                try {
                    var cursorRow = cursorManager.cursorLine();
                    if (cursorRow.isPresent() && cursorManager.getLogLineAt(cursorRow.get()) != null) {
                        viewPort.showActivityMenu(e.getX(), e.getY());
                    }
                } catch (TemporarySearchException | SearchException | StorageException ex) {
                    log.warn("activityMenuClickedIcon: {}", ex.getMessage());
                }
            }

            private void mouse(MouseEvent e, boolean dragged) {

                if (e.isConsumed()) {
                    return;
                }

                // Button handling notes:
                // - Touchpads can generate MOUSE_DRAGGED events with NOBUTTON.
                // - Some mice have extra buttons that can be mapped by the OS/driver.
                // We only ignore popup triggers here.
                if (e.isPopupTrigger()) {
                    return;
                }

                switch (e.getButton()) {
                    case MouseEvent.NOBUTTON, MouseEvent.BUTTON1 -> {}

                    // Many mice map horizontal scrolling / navigation to button 4/5 (back/forward).
                    // Treat those as horizontal pan.
                    case 4, 5 -> {
                        try {
                            int direction = (e.getButton() == 4) ? 1 : -1;
                            cursorManager.scrollContent(0, direction * wheelAccelerator);
                            e.consume();
                        } catch (Exception ex) {
                            log.error("Horizontal button scroll error", ex);
                        }
                        return;
                    }

                    default -> {
                        log.debug("WHATTA?=!?!? {}", e.getButton());
                        return;
                    }
                }

                var charSize = viewPort.getCharacterSize();
                if (charSize == null) {
                    return;
                }

                int line = e.getY() / charSize.height();
                int col = Math.max(0, e.getX() - LineRenderer.iconSpaceWidth(charSize.height())) / charSize.width();
                try {
                    log.trace("Mouse event: {} {},{}, drag={} ", dragged ? "drag" : "click", line, col, dragged);
                    cursorManager.setCursorFromClick(line, col, dragged);
                    offerSelectionToPrompt();
                } catch (Exception ex) {
                    log.error("Click error", ex);
                }
            }
        };
    }

    /**
     * Sets up input handling for the viewer panel.
     * Key bindings are added to the viewer's InputMap, and shared actions (SEARCH, GOTO, etc.)
     * are also added to the container's ActionMap so buttons in StatusBar can access them.
     *
     * @param viewer    The panel to add mouse and keyboard listeners to
     * @param container The parent container (LogViewer) whose ActionMap will hold shared actions for button access
     */
    public void setup(JPanel viewer, JComponent container) {
        setupMouse(viewer);
        setupKeyMappings(viewer, container);
        followScrollLockWindow(viewer);

        // Ensure the view panel can receive keyboard focus.
        // Key bindings use WHEN_IN_FOCUSED_WINDOW so they work regardless,
        // but having focus on viewPanel keeps the event model tidy.
        viewer.setFocusable(true);

        // Detect shift-release to finalize selection-echo highlights.
        // KeyBindings (InputMap/ActionMap) don't fire on key-release, and KeyListener
        // only works on the focused component — so we use a global KeyEventDispatcher
        // that intercepts all key events in the application.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_RELEASED && e.getKeyCode() == KeyEvent.VK_SHIFT) {
                cursorManager.selectionDone();
            }
            return false; // never consume — let normal dispatch continue
        });
    }

    private void setupMouse(JPanel panel) {
        panel.addMouseWheelListener(e -> {
            try {
                // Trackpads often emit horizontal scroll as a wheel event where
                // SHIFT swaps axis (or platforms report horizontal-only rotation).
                // We treat the wheel rotation as vertical by default, and as horizontal when shift is held.
                //
                // The viewport uses columns for horizontal movement.
                double preciseRotation = e.getPreciseWheelRotation();

                // Some devices report very small fractional deltas.
                // Accumulate by rounding away from 0 so short swipes still move.
                int delta = (int) Math.copySign(Math.ceil(Math.abs(preciseRotation)), preciseRotation);
                if (delta == 0) {
                    return;
                }

                boolean horizontal = e.isShiftDown();

                // macOS trackpads can generate horizontal-only scrolling without SHIFT.
                // There's no portable way to read horizontal wheel delta from AWT, but in practice
                // the common user gesture is shift+scroll for horizontal pan, so support that robustly.
                int deltaLines = horizontal ? 0 : delta * wheelAccelerator;
                int deltaCols = horizontal ? delta * wheelAccelerator : 0;

                log.trace("Mouse wheel event rotation={} precise={} horizontal={} -> lines={} cols={} ",
                        e.getWheelRotation(), preciseRotation, horizontal, deltaLines, deltaCols);

                cursorManager.scrollContent(deltaLines, deltaCols);

            } catch (Exception ex) {
                log.error("Scroll error", ex);
            }
        });

        panel.addMouseListener(clickHandler);
        panel.addMouseMotionListener(clickHandler);
    }

    /**
     * Checks whether an editable text field currently has focus.
     * Used to guard global key bindings so they don't steal keystrokes
     * from search input, login fields, etc. Their own Copy/Paste keys
     * (the field's InputMap) are consulted first, so the log's Copy never sees them.
     */
    private static boolean isEditableFieldFocused() {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focused instanceof JTextComponent text && text.isEditable();
    }

    /**
     * Installs the bindings from {@link KeyMap#defaults()}.
     * <p>
     * One Swing action per keystroke: a keystroke bound to exactly one action
     * (the common case) runs that action; a keystroke bound in several contexts
     * (Space, Enter, N, ...) resolves the action for {@link #currentContext()}
     * when it fires. The {@link KeyGuard} of the resolved binding decides whether
     * a focused text field blocks it, and each action's deferred/focus policy is
     * preserved.
     */
    private void setupKeyMappings(JPanel viewer, JComponent container) {
        // All bindings use WHEN_IN_FOCUSED_WINDOW so they fire regardless of which
        // component has focus; the guard keeps bare keys out of text fields.
        InputMap im = viewer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = viewer.getActionMap();
        // Container's ActionMap holds shared actions accessible by StatusBar buttons
        ActionMap containerAm = container.getActionMap();

        for (Map.Entry<KeyStroke, List<Registered>> entry : bindableBindings().entrySet()) {
            String name = actionKey(entry.getValue());
            im.put(entry.getKey(), name);
            am.put(name, swingAction(entry.getValue()));
        }

        // Legacy action names the status bar looks up at click time. The buttons
        // must keep working, so they point at the canonical keystroke's action.
        alias(am, am, SEARCH, UiAction.OPEN_SEARCH);
        alias(am, containerAm, SEARCH, UiAction.OPEN_SEARCH);
        alias(am, am, GOTO, UiAction.GOTO);
        alias(am, containerAm, GOTO, UiAction.GOTO);

        // Preferences - accessible from hamburger menu, no keystroke of its own
        am.put(PREFERENCES, action(viewPort::showPreferences));
    }

    /**
     * Bindings for implemented actions, grouped by keystroke. Actions
     * without a handler are deliberately left unbound so a key never silently
     * swallows input (search-for-selection, bookmarks, Match details and the
     * Matches view actions arrive with their phases).
     */
    private Map<KeyStroke, List<Registered>> bindableBindings() {
        Map<KeyStroke, List<Registered>> byStroke = new LinkedHashMap<>();
        for (UiAction action : UiAction.values()) {
            if (!handlers.containsKey(action)) {
                continue;
            }
            for (KeyBinding binding : keyMap.all(action)) {
                byStroke.computeIfAbsent(binding.stroke(), stroke -> new ArrayList<>())
                        .add(new Registered(action, binding));
            }
        }
        return byStroke;
    }

    /**
     * Stable Swing action name: the {@link UiAction} name for the canonical
     * keystroke, with the key appended for aliases, and {@code CONTEXT.} for
     * keystrokes that several actions share across contexts.
     */
    private String actionKey(List<Registered> registered) {
        if (registered.size() > 1) {
            return "CONTEXT." + KeyMap.format(registered.get(0).binding().stroke());
        }
        Registered only = registered.get(0);
        String name = only.action() == UiAction.COPY ? "copy" : only.action().name();
        return only.binding().equals(keyMap.canonical(only.action()))
                ? name
                : name + "." + KeyMap.format(only.binding().stroke());
    }

    private AbstractAction swingAction(List<Registered> registered) {
        ThrowingRunnable task = () -> dispatch(registered);
        if (registered.stream().allMatch(r -> r.binding().guard() == KeyGuard.GUARDED)) {
            return guardedAction(task);
        }
        // An unguarded keystroke is never the same keystroke as a guarded one in
        // default map; if one ever mixes, dispatch() checks the resolved binding's
        // guard itself, so this wrapper only decides the common case.
        return action(task);
    }

    /** Resolves the action for the current context and runs it. */
    private void dispatch(List<Registered> registered) {
        Registered target = resolveContext(registered);
        if (target == null || target.action() != UiAction.CANCEL) {
            endSearchGuard.reset();
        }
        if (target == null) {
            return;
        }
        // The time panel keeps the keyboard; only the cancel chain (which closes it first) gets through.
        if (viewPort.isTimePanelOpen() && target.action() != UiAction.CANCEL) {
            return;
        }
        if (target.binding().context() == KeyBinding.Context.ANY && !ANY_IN_DETAILS.contains(target.action())
                && currentContext() == KeyBinding.Context.DETAILS_VIEW) {
            return;
        }
        if (target.binding().guard() == KeyGuard.GUARDED && editableFieldFocused.getAsBoolean()) {
            return;
        }
        if (target.binding().guard() == KeyGuard.SEARCH_INPUT_OK && editableFieldFocused.getAsBoolean()
                && !viewPort.isSearchInputActive()) {
            return;
        }
        KeyHandler handler = handlers.get(target.action());
        if (handler == null) {
            return;
        }
        if (DEFERRED.contains(target.action())) {
            // Deferred (not guarded-deferred): the guard was checked just above,
            // while the triggering event was still being processed.
            SwingUtilities.invokeLater(() -> run(handler, target.binding()));
        } else {
            run(handler, target.binding());
        }
    }

    private void run(KeyHandler handler, KeyBinding binding) {
        try {
            handler.run(binding);
        } catch (Exception ex) {
            log.error("Input error", ex);
        }
    }

    /** An exact-context binding wins over an {@link KeyBinding.Context#ANY} one. */
    private Registered resolveContext(List<Registered> registered) {
        KeyBinding.Context context = currentContext();
        Registered any = null;
        for (Registered candidate : registered) {
            if (candidate.binding().context() == context) {
                return candidate;
            }
            if (candidate.binding().context() == KeyBinding.Context.ANY) {
                any = candidate;
            }
        }
        return any;
    }

    /**
     * Where we are, for bindings that differ per context. Prompts win over everything, open details win over the
     * view they cover (its keys do not fire), and the Bookmarks and Matches views win over the log view (Enter, F4,
     * Shift-Enter and {@code i} mean different things there).
     */
    private KeyBinding.Context currentContext() {
        if (viewPort.isYesNoPromptShowing()) {
            return KeyBinding.Context.PROMPT;
        }
        if (viewPort.isDetailsOpen()) {
            return KeyBinding.Context.DETAILS_VIEW;
        }
        if (viewPort.isBookmarksView()) {
            return KeyBinding.Context.BOOKMARKS_VIEW;
        }
        if (viewPort.isMatchesView()) {
            return KeyBinding.Context.MATCHES_VIEW;
        }
        return KeyBinding.Context.LOG_VIEW;
    }

    private void alias(ActionMap source, ActionMap target, Object name, UiAction action) {
        Action actionForKey = source.get(action.name());
        if (actionForKey != null) {
            target.put(name, actionForKey);
        }
    }

    private AbstractAction action(ThrowingRunnable task) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    task.run();
                } catch (Exception ex) {
                    log.error("Input error", ex);
                }
            }
        };
    }

    /**
     * Like action(), but skips execution when an editable text field has focus.
     * Prevents global key bindings from stealing keystrokes during text input.
     */
    private AbstractAction guardedAction(ThrowingRunnable task) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (editableFieldFocused.getAsBoolean()) return;
                try {
                    task.run();
                } catch (Exception ex) {
                    log.error("Input error", ex);
                }
            }
        };
    }

    /**
     * Registers the implemented behaviour. Everything else in
     * {@link UiAction} stays unbound until it has a handler.
     */
    private void registerHandlers() {
        handlers.put(UiAction.REPAINT, ignored -> viewPort.updateVisibleLines());

        handlers.put(UiAction.CURSOR_UP, ignored -> {
            if (!inDetails(panel -> panel.moveSelection(-1))) {
                cursorManager.moveCursor(-1, 0, false);
            }
        });
        handlers.put(UiAction.CURSOR_DOWN, ignored -> {
            if (!inDetails(panel -> panel.moveSelection(1))) {
                cursorManager.moveCursor(1, 0, false);
            }
        });
        handlers.put(UiAction.CURSOR_LEFT, ignored -> cursorManager.moveCursor(0, -1, false));
        handlers.put(UiAction.CURSOR_RIGHT, ignored -> cursorManager.moveCursor(0, 1, false));

        handlers.put(UiAction.SELECT_UP, ignored -> extendSelection(-1, 0));
        handlers.put(UiAction.SELECT_DOWN, ignored -> extendSelection(1, 0));
        handlers.put(UiAction.SELECT_LEFT, ignored -> extendSelection(0, -1));
        handlers.put(UiAction.SELECT_RIGHT, ignored -> extendSelection(0, 1));

        handlers.put(UiAction.CURSOR_UP_FAST, ignored -> {
            if (!inDetails(panel -> panel.moveSelection(-8))) {
                cursorManager.moveCursor(-8, 0, false);
            }
        });
        handlers.put(UiAction.CURSOR_DOWN_FAST, ignored -> {
            if (!inDetails(panel -> panel.moveSelection(8))) {
                cursorManager.moveCursor(8, 0, false);
            }
        });
        handlers.put(UiAction.CURSOR_LEFT_FAST, ignored -> cursorManager.moveCursor(0, -8, false));
        handlers.put(UiAction.CURSOR_RIGHT_FAST, ignored -> cursorManager.moveCursor(0, 8, false));

        handlers.put(UiAction.SELECT_UP_FAST, ignored -> extendSelection(-8, 0));
        handlers.put(UiAction.SELECT_DOWN_FAST, ignored -> extendSelection(8, 0));
        handlers.put(UiAction.SELECT_LEFT_FAST, ignored -> extendSelection(0, -8));
        handlers.put(UiAction.SELECT_RIGHT_FAST, ignored -> extendSelection(0, 8));

        handlers.put(UiAction.PAGE_UP, ignored -> {
            if (!inDetails(DetailsPanel::pageUp)) {
                cursorManager.moveCursor(-cursorManager.getLinesPerPage(), 0, false);
            }
        });
        handlers.put(UiAction.PAGE_DOWN, ignored -> {
            if (!inDetails(DetailsPanel::pageDown)) {
                cursorManager.moveCursor(cursorManager.getLinesPerPage(), 0, false);
            }
        });
        handlers.put(UiAction.SELECT_PAGE_UP, ignored -> extendSelection(-cursorManager.getLinesPerPage(), 0));
        handlers.put(UiAction.SELECT_PAGE_DOWN, ignored -> extendSelection(cursorManager.getLinesPerPage(), 0));

        handlers.put(UiAction.SET_MARK, ignored -> cursorManager.setMarkAtCursor());
        // In the Bookmarks view Copy without a selection takes every bookmarked event.
        handlers.put(UiAction.COPY, ignored -> {
            if (viewPort.isDetailsOpen()) {
                viewPort.detailsCopyValue();
                return;
            }
            if (!viewPort.isBookmarksView() || !cursorManager.selectedRows().isEmpty()
                    || !viewPort.copyAllBookmarks()) {
                cursorManager.copyToClipboard();
            }
        });

        handlers.put(UiAction.OPEN_DETAILS, ignored -> openDetailsForCursor());

        // Ctrl-Shift-C opens the format menu of Copy as…, in every view.
        handlers.put(UiAction.COPY_AS, ignored -> viewPort.showCopyAsMenu());

        handlers.put(UiAction.LOGIN, ignored -> viewPort.actionLogin());
        handlers.put(UiAction.GOTO, ignored -> viewPort.openTimePanel(TimePanel.Focus.AT));
        handlers.put(UiAction.SET_RANGE, ignored -> viewPort.openTimePanel(TimePanel.Focus.FROM));

        // Cancel chain: time panel, help overlay, boundary/extend prompt, search input, the details,
        // leave the event-list view (Bookmarks back to where it was opened from, Matches to the log), clear the selection/mark (the cursor stays: F7 hides it),
        // then the search (Log view: a second Esc within the guard window; the first one
        // only clears the search's transient state).
        handlers.put(UiAction.CANCEL, ignored -> {
            if (cancelStepsBeforeSearch()) {
                endSearchGuard.reset();
                return;
            }
            if (!viewPort.isMatchesView() && !viewPort.isBookmarksView() && viewPort.getActiveSearchTerm().isPresent()
                    && !endSearchGuard.press(clock.getAsLong())) {
                viewPort.clearSearchTransient(KeyMap.preferred().label(UiAction.CANCEL));
                return;
            }
            endSearchGuard.reset();
            viewPort.cancelSearch();
        });

        // F7 / `v` switch cursor mode on and off in both views. Scroll Lock follows its lock state.
        handlers.put(UiAction.TOGGLE_CURSOR, binding -> {
            if (binding.stroke().getKeyCode() == KeyEvent.VK_SCROLL_LOCK) {
                scrollLock.keyPressed();
            } else {
                cursorManager.toggleCursor();
            }
        });

        // Ctrl-F, `/` always open the prompt (never "next"); an active search's term is offered for
        // replacing: pre-filled with all of it selected.
        handlers.put(UiAction.OPEN_SEARCH, ignored -> viewPort.openSearchPrompt(openPrefill()));
        // `?` opens the same prompt for now; the backwards submit flavour lands with the prompt rework.
        handlers.put(UiAction.OPEN_SEARCH_BACKWARD, ignored -> viewPort.openSearchPrompt(openPrefill()));

        // Pager aliases (`n`, `N`) never open the prompt; the unguarded desktop keys
        // (F3, Ctrl-S, Shift-F3, Ctrl-R) open it when idle, and pre-filled when the search
        // ended without matches (nothing to step through: type a new term).
        handlers.put(UiAction.NEXT_MATCH, binding -> {
            if (binding.guard() == KeyGuard.UNGUARDED && viewPort.searchEndedWithoutMatches()) {
                viewPort.openSearchPrompt(openPrefill());
            } else if (binding.guard() == KeyGuard.UNGUARDED || searchTerm().isPresent()) {
                viewPort.searchNext();
            }
        });
        handlers.put(UiAction.PREVIOUS_MATCH, binding -> {
            if (binding.guard() == KeyGuard.UNGUARDED && viewPort.searchEndedWithoutMatches()) {
                viewPort.openSearchPrompt(openPrefill());
            } else if (binding.guard() == KeyGuard.UNGUARDED || searchTerm().isPresent()) {
                viewPort.searchPrevious();
            }
        });

        // F4 and `&` toggle the Matches view (log view) / show the match in the log
        // (matches view) — the context resolves the same keys to the right action.
        handlers.put(UiAction.TOGGLE_MATCHES, ignored -> viewPort.toggleMatches());

        // Enter/F4/`&` in the Matches view show the match in the log.
        handlers.put(UiAction.SHOW_IN_LOG, ignored -> viewPort.showMatchInLog());

        // Shift-Enter/`i` in the Matches view open details for the selected match.
        handlers.put(UiAction.MATCH_DETAILS, ignored -> openDetailsForCursor());

        // `m` and Ctrl-B toggle the bookmark of the cursor's event or of every event the selection touches.
        handlers.put(UiAction.TOGGLE_BOOKMARK, ignored -> viewPort.toggleBookmark());

        // `M` and Ctrl-Shift-B open the Bookmarks view from the log or Matches view; F2 also closes it.
        handlers.put(UiAction.BOOKMARKS_VIEW, ignored -> viewPort.toggleBookmarks());

        // `h` toggles the keyboard help overlay; guarded so it never fires while typing.
        handlers.put(UiAction.HELP, ignored -> viewPort.showKeyboardHelp());

        // `+` and `-` fold the active search into the scope; the viewer's guards toast when there is no search.
        handlers.put(UiAction.NARROW, ignored -> viewPort.narrowScopeToSearch());
        handlers.put(UiAction.EXCLUDE, ignored -> viewPort.excludeSearchFromScope());

        // `a` and `A` narrow the scope to the cursor row's activity; Shift-F10 and the menu key open the row menu
        // (the field menu while the details are open).
        handlers.put(UiAction.NARROW_TO_ACTIVITY, ignored -> viewPort.narrowToCursorActivity());
        handlers.put(UiAction.NARROW_TO_REQUEST, ignored -> viewPort.narrowToCursorRequest());
        handlers.put(UiAction.CONTEXT_MENU, ignored -> {
            if (viewPort.isDetailsOpen()) {
                viewPort.showDetailsFieldMenu();
            } else {
                viewPort.showRowContextMenuAtCursor();
            }
        });

        // The event details: the keys that only exist while they are open.
        handlers.put(UiAction.DETAILS_PREVIOUS, ignored -> viewPort.detailsStep(false));
        handlers.put(UiAction.DETAILS_NEXT, ignored -> viewPort.detailsStep(true));
        handlers.put(UiAction.DETAILS_FIRST, ignored -> inDetails(DetailsPanel::home));
        handlers.put(UiAction.DETAILS_LAST, ignored -> inDetails(DetailsPanel::end));
        handlers.put(UiAction.DETAILS_NARROW_FIELD, ignored -> viewPort.detailsNarrowField());
        handlers.put(UiAction.DETAILS_EXCLUDE_FIELD, ignored -> viewPort.detailsExcludeField());
        handlers.put(UiAction.DETAILS_MESSAGE_FIELD, ignored -> viewPort.detailsToggleMessageField());

        handlers.put(UiAction.PROMPT_YES, ignored -> viewPort.actionAcceptBoundaryExpansion(true));
        handlers.put(UiAction.PROMPT_NO, ignored -> viewPort.actionAcceptBoundaryExpansion(false));
    }

    /** The cancel chain's steps that end before the search itself; true when one consumed the key. */
    private boolean cancelStepsBeforeSearch() {
        return viewPort.closeTimePanel() || viewPort.closeKeyboardHelp()
                || viewPort.actionAcceptBoundaryExpansion(false) || viewPort.cancelSearchMode()
                || viewPort.closeDetails()
                || viewPort.backToLog()
                || cursorManager.clearSelection();
    }

    /** The active search term, used to pre-fill the prompt and to gate the pager aliases. */
    private Optional<String> searchTerm() {
        return viewPort.getActiveSearchTerm();
    }

    /**
     * What the prompt opens with (null for a plain prompt): see {@link SearchPrefill} for the order
     * of a one-line selection, a new one-line clipboard text and the active search term.
     */
    private String openPrefill() throws TemporarySearchException, SearchException, StorageException {
        return searchPrefill.open(cursorManager.singleRowSelectionText(), searchTerm(),
                viewPort.lastSubmittedSearchTerm()).orElse(null);
    }

    /** Text on the clipboard at login was not copied for a search: the prompt will not offer it as new. */
    void seedClipboard() {
        searchPrefill.seed();
    }

    /** Where the search prompt's clipboard pre-fill reads from; the system clipboard unless a test replaces it. */
    void setClipboard(ClipboardAccess clipboard) {
        this.searchPrefill = new SearchPrefill(clipboard);
    }

    /** Re-syncs cursor mode with the Scroll Lock state whenever the viewer's window is activated. */
    private void followScrollLockWindow(JComponent viewer) {
        viewer.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) {
                listenToWindow(viewer);
            }
        });
        listenToWindow(viewer);
    }

    private void listenToWindow(JComponent viewer) {
        Window window = SwingUtilities.getWindowAncestor(viewer);
        if (window == null || window == scrollLockWindow) {
            return;
        }
        scrollLockWindow = window;
        WindowAdapter activation = new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                scrollLock.windowActivated();
            }

            @Override
            public void windowGainedFocus(WindowEvent e) {
                scrollLock.windowActivated();
            }
        };
        window.addWindowListener(activation);
        window.addWindowFocusListener(activation);
    }

    /** Moves the selection end; an open prompt follows a one-line selection. */
    private void extendSelection(int deltaLines, int deltaCols)
            throws TemporarySearchException, SearchException, StorageException {
        cursorManager.moveCursor(deltaLines, deltaCols, true);
        offerSelectionToPrompt();
    }

    /** While the search input is open, offers the log's one-line selection to it (focus stays put). */
    private void offerSelectionToPrompt() throws TemporarySearchException, SearchException, StorageException {
        if (!viewPort.isSearchInputActive()) {
            return;
        }
        cursorManager.singleRowSelectionText().flatMap(PromptText::fromSelection)
                .ifPresent(viewPort::offerSelectionToSearchPrompt);
    }

    /** Open details for the cursor's line, if the cursor sits on a loaded line; with the details open, close them. */
    private void openDetailsForCursor() {
        if (viewPort.closeDetails()) {
            return;
        }
        cursorManager.cursorLogLine().ifPresent(viewPort::openLogLineDetails);
    }

    /** Runs {@code action} on the details panel when the details are open; false when they are not. */
    private boolean inDetails(java.util.function.Consumer<DetailsPanel> action) {
        DetailsPanel panel = viewPort.detailsPanel();
        if (panel == null) {
            return false;
        }
        action.accept(panel);
        return true;
    }

    /** Actions with a handler; the rest are deliberately unbound. */
    public Set<UiAction> boundActions() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    /**
     * One keystroke's worth of {@link KeyMap} declarations, kept together so a
     * shared keystroke can resolve to the action of the current context.
     */
    private record Registered(UiAction action, KeyBinding binding) {}

    @FunctionalInterface
    interface KeyHandler {
        void run(KeyBinding binding) throws TemporarySearchException, SearchException, StorageException;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws TemporarySearchException, SearchException, StorageException;
    }
}
