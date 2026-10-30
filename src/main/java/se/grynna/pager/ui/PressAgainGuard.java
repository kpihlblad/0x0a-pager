package se.grynna.pager.ui;

/**
 * "Press again to confirm" timing for slow-to-redo actions. The first press arms the
 * guard and returns false; a second press within the window fires it (returns true)
 * and disarms. A press after the window expired is a first press again. The clock is
 * the caller's, so the timing is deterministic under test.
 */
final class PressAgainGuard {

    static final long DEFAULT_WINDOW_MILLIS = 2_000;

    private final long windowMillis;
    private boolean armed;
    private long armedAt;

    PressAgainGuard() {
        this(DEFAULT_WINDOW_MILLIS);
    }

    PressAgainGuard(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * @param nowMillis the press time, from the same clock as earlier presses
     * @return true when this press confirms an earlier one within the window
     */
    boolean press(long nowMillis) {
        if (armed && nowMillis - armedAt < windowMillis) {
            reset();
            return true;
        }
        armed = true;
        armedAt = nowMillis;
        return false;
    }

    /** Disarm: the next press is a first press again. */
    void reset() {
        armed = false;
    }
}
