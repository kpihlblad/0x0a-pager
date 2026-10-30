package se.grynna.pager.ui;

import javax.swing.Timer;
import java.util.function.LongSupplier;

/**
 * The attention flash: a quick pop that fades out, shared by every flash in the app. The intensity rises from 0
 * to 1 over {@link #POP_MILLIS} (ease-out), then falls to 0 over the rest of {@link #DURATION_MILLIS} (ease-in
 * quadratic). {@link #intensity(long)} is the pure curve; the instance is its driver: {@link #start()} restarts
 * it, and a Swing timer repaints while it runs and stops itself when it is over. EDT only.
 */
public final class AttentionFlash {

    public static final int POP_MILLIS = 70;
    public static final int DURATION_MILLIS = 500;

    private static final int FRAME_MILLIS = 16;

    private final Runnable repaint;
    private final LongSupplier clockMillis;
    private final Timer timer;
    private boolean started;
    private long startedAt;

    public AttentionFlash(Runnable repaint) {
        this(repaint, () -> System.nanoTime() / 1_000_000);
    }

    AttentionFlash(Runnable repaint, LongSupplier clockMillis) {
        this.repaint = repaint;
        this.clockMillis = clockMillis;
        this.timer = new Timer(FRAME_MILLIS, e -> tick());
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

    /** Start the flash from 0; a flash already running pops again. */
    public void start() {
        started = true;
        startedAt = clockMillis.getAsLong();
        timer.start();
    }

    /** The current intensity; 0 when never started, stopped or finished. */
    public float intensity() {
        return started ? intensity(clockMillis.getAsLong() - startedAt) : 0f;
    }

    /** True from {@link #start()} until the duration has passed or {@link #stop()} is called. */
    public boolean isRunning() {
        return started && clockMillis.getAsLong() - startedAt < DURATION_MILLIS;
    }

    /** Cancel the flash without a repaint; the caller repaints if it needs the overlay gone at once. */
    public void stop() {
        started = false;
        timer.stop();
    }

    private void tick() {
        repaint.run();
        if (!isRunning()) {
            timer.stop();
        }
    }
}
