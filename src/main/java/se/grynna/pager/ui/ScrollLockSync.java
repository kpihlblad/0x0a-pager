package se.grynna.pager.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps cursor mode in step with the keyboard's Scroll Lock state where the platform reports it.
 * Lock on means page mode (cursor off): the arrows scroll, which is what Scroll Lock always meant.
 * Where the state cannot be read, the Scroll Lock key only toggles cursor mode. The lock state is
 * read, never set.
 */
final class ScrollLockSync {

    private final Supplier<Optional<Boolean>> lockState;
    private final BooleanSupplier cursorMode;
    private final Consumer<Boolean> setCursorMode;

    /**
     * @param lockState     the Scroll Lock state, empty when the platform does not report it
     * @param cursorMode    whether cursor mode is on
     * @param setCursorMode switches cursor mode on (true) or off (false)
     */
    ScrollLockSync(Supplier<Optional<Boolean>> lockState, BooleanSupplier cursorMode,
            Consumer<Boolean> setCursorMode) {
        this.lockState = lockState;
        this.cursorMode = cursorMode;
        this.setCursorMode = setCursorMode;
    }

    /** The Scroll Lock key was pressed: follow the lock state, or toggle when it cannot be read. */
    void keyPressed() {
        Optional<Boolean> locked = lockState.get();
        setCursorMode.accept(locked.map(on -> !on).orElseGet(() -> !cursorMode.getAsBoolean()));
    }

    /** The window was activated or gained focus: follow the lock state when it can be read. */
    void windowActivated() {
        lockState.get().ifPresent(locked -> {
            if (cursorMode.getAsBoolean() == locked) {
                setCursorMode.accept(!locked);
            }
        });
    }

    /** The lock state from the AWT toolkit; empty where the platform does not report it. */
    static Optional<Boolean> platformLockState() {
        try {
            return Optional.of(Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_SCROLL_LOCK));
        } catch (UnsupportedOperationException e) {
            return Optional.empty();
        }
    }
}
