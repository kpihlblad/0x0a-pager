package se.grynna.pager.ui;

import javax.swing.Timer;
import java.util.function.LongSupplier;

/**
 * The attention flash: a quick pop that fades out, shared by every flash in the app. The intensity rises from 0
 * to 1 over {@link #POP_MILLIS} (ease-out), then falls to 0 over the rest of {@link #DURATION_MILLIS} (ease-in
 * quadratic). {@link #intensity(long)} is the pure curve; the instance is its driver: {@link #start()} restarts
 * it, {@link #startDouble(int)} plays two pulses and then repeats a single pulse at a fixed interval. A Swing timer
 * repaints while a pulse is active and stops itself between pulses. EDT only.
 */
public final class AttentionFlash {

    public static final int POP_MILLIS = 70;
    public static final int DURATION_MILLIS = 500;
    public static final int DOUBLE_GAP_MILLIS = 80;
    public static final int DOUBLE_DURATION_MILLIS = 2 * DURATION_MILLIS + DOUBLE_GAP_MILLIS;
    public static final int REPEAT_INTERVAL_MILLIS = 6_000;

    private static final int FRAME_MILLIS = 16;

    private final Runnable repaint;
    private final LongSupplier clockMillis;
    private final Timer timer;
    private final Timer repeatTimer;
    private boolean started;
    private boolean doublePulse;
    private int repeatMillis;
    private long startedAt;

    public AttentionFlash(Runnable repaint) {
        this(repaint, () -> System.nanoTime() / 1_000_000);
    }

    AttentionFlash(Runnable repaint, LongSupplier clockMillis) {
        this.repaint = repaint;
        this.clockMillis = clockMillis;
        this.timer = new Timer(FRAME_MILLIS, e -> tick());
        this.repeatTimer = new Timer(REPEAT_INTERVAL_MILLIS, e -> repeat());
    }

    /** The curve at {@code elapsedMillis} after the start: 0 before and after the flash, always within [0, 1]. */
    public static float intensity(long elapsedMillis) {
        if (elapsedMillis < 0 || elapsedMillis >= DURATION_MILLIS) {
            return 0f;
        }
        if (elapsedMillis < POP_MILLIS) {
            double t = elapsedMillis / (double) POP_MILLIS;
            return (float) (1 - (1 - t) * (1 - t));
        }
        double u = (elapsedMillis - POP_MILLIS) / (double) (DURATION_MILLIS - POP_MILLIS);
        return (float) ((1 - u) * (1 - u));
    }

    /**
     * The curve for a double-flash with optional periodic single-flash reminders: two pulses separated by
     * {@link #DOUBLE_GAP_MILLIS}, and when {@code repeatMillis > 0}, a single pulse every {@code repeatMillis}
     * measured from the start of the first pulse. Always within [0, 1].
     */
    public static float intensity(long elapsedMillis, boolean doublePulse, int repeatMillis) {
        if (elapsedMillis < 0) {
            return 0f;
        }
        if (doublePulse) {
            if (elapsedMillis < DURATION_MILLIS) {
                return intensity(elapsedMillis);
            }
            long second = elapsedMillis - (DURATION_MILLIS + DOUBLE_GAP_MILLIS);
            if (second >= 0 && second < DURATION_MILLIS) {
                return intensity(second);
            }
        } else if (elapsedMillis < DURATION_MILLIS) {
            return intensity(elapsedMillis);
        }
        if (repeatMillis > 0 && elapsedMillis >= repeatMillis) {
            long cycle = elapsedMillis % repeatMillis;
            if (cycle < DURATION_MILLIS) {
                return intensity(cycle);
            }
        }
        return 0f;
    }

    /** Start a single pulse from 0; a flash already running pops again. */
    public void start() {
        started = true;
        doublePulse = false;
        repeatMillis = 0;
        repeatTimer.stop();
        startedAt = clockMillis.getAsLong();
        timer.start();
    }

    /** Start a double pulse with no reminders. */
    public void startDouble() {
        startDouble(0);
    }

    /**
     * Start a double pulse; when {@code repeatIntervalMillis > 0}, a single pulse follows every that many
     * milliseconds from the start until {@link #stop()}. A flash already running restarts.
     */
    public void startDouble(int repeatIntervalMillis) {
        started = true;
        doublePulse = true;
        repeatMillis = Math.max(0, repeatIntervalMillis);
        startedAt = clockMillis.getAsLong();
        timer.start();
        repeatTimer.stop();
        if (repeatMillis > 0) {
            repeatTimer.setInitialDelay(repeatMillis);
            repeatTimer.setDelay(repeatMillis);
            repeatTimer.restart();
        }
    }

    /** The current intensity; 0 when never started, stopped or between pulses. */
    public float intensity() {
        return started ? intensity(clockMillis.getAsLong() - startedAt, doublePulse, repeatMillis) : 0f;
    }

    /** True while a pulse is active: from {@link #start()} until the flash is over or {@link #stop()} is called. */
    public boolean isRunning() {
        return started && activePulse(clockMillis.getAsLong() - startedAt);
    }

    /** True when periodic single-pulse reminders are armed. */
    public boolean isRepeating() {
        return started && repeatMillis > 0;
    }

    /** Cancel the flash without a repaint; the caller repaints if it needs the overlay gone at once. */
    public void stop() {
        started = false;
        doublePulse = false;
        repeatMillis = 0;
        timer.stop();
        repeatTimer.stop();
    }

    /** Whether {@code elapsed} falls in a pulse window or the gap a double flash keeps the timer running through. */
    private boolean activePulse(long elapsed) {
        if (elapsed < 0) {
            return false;
        }
        if (elapsed < (doublePulse ? DOUBLE_DURATION_MILLIS : DURATION_MILLIS)) {
            return true;
        }
        return repeatMillis > 0 && elapsed >= repeatMillis && elapsed % repeatMillis < DURATION_MILLIS;
    }

    private void tick() {
        repaint.run();
        if (!isRunning()) {
            timer.stop();
        }
    }

    /** Repaints without the stop check: the clock can read a millisecond before the repeat boundary. */
    private void repeat() {
        if (started && repeatMillis > 0) {
            timer.start();
            repaint.run();
        }
    }
}
