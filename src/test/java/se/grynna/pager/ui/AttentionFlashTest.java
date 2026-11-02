package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AttentionFlashTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicInteger repaints = new AtomicInteger();
    private final AttentionFlash flash = new AttentionFlash(repaints::incrementAndGet, clock::get);

    @AfterEach
    void stopTimer() throws Exception {
        SwingUtilities.invokeAndWait(flash::stop);
    }

    @Test
    void curveIsZeroBeforeStart() {
        assertThat(AttentionFlash.intensity(-1)).isZero();
    }

    @Test
    void curveStartsAtZeroAndPopsToOne() {
        assertThat(AttentionFlash.intensity(0)).isZero();
        assertThat(AttentionFlash.intensity(AttentionFlash.POP_MILLIS / 2)).isBetween(0.5f, 0.8f);
        assertThat(AttentionFlash.intensity(AttentionFlash.POP_MILLIS)).isEqualTo(1f);
    }

    @Test
    void curveFadesOutAndEndsAtDuration() {
        float midFade = AttentionFlash.intensity((AttentionFlash.POP_MILLIS + AttentionFlash.DURATION_MILLIS) / 2);
        assertThat(midFade).isBetween(0.1f, 0.4f);
        float justBefore = AttentionFlash.intensity(AttentionFlash.DURATION_MILLIS - 1);
        assertThat(justBefore).isGreaterThan(0f).isLessThan(0.01f);
        assertThat(AttentionFlash.intensity(AttentionFlash.DURATION_MILLIS)).isZero();
        assertThat(AttentionFlash.intensity(10_000)).isZero();
    }

    @Test
    void popRisesAndFadeFalls() {
        for (int t = 0; t < AttentionFlash.POP_MILLIS; t++) {
            assertThat(AttentionFlash.intensity(t + 1)).as("pop at %d ms", t + 1)
                    .isGreaterThan(AttentionFlash.intensity(t));
        }
        for (int t = AttentionFlash.POP_MILLIS; t < AttentionFlash.DURATION_MILLIS; t++) {
            assertThat(AttentionFlash.intensity(t + 1)).as("fade at %d ms", t + 1)
                    .isLessThan(AttentionFlash.intensity(t));
        }
    }

    @Test
    void curveStaysWithinZeroAndOne() {
        for (long t = -10; t <= 1_000; t++) {
            assertThat(AttentionFlash.intensity(t)).isBetween(0f, 1f);
        }
    }

    @Test
    void notRunningBeforeStart() {
        assertThat(flash.isRunning()).isFalse();
        assertThat(flash.intensity()).isZero();
    }

    @Test
    void runningAfterStartFollowsTheClock() throws Exception {
        SwingUtilities.invokeAndWait(flash::start);

        assertThat(flash.isRunning()).isTrue();
        assertThat(flash.intensity()).isZero();

        clock.addAndGet(AttentionFlash.POP_MILLIS);
        assertThat(flash.intensity()).isEqualTo(1f);

        clock.addAndGet(AttentionFlash.DURATION_MILLIS - AttentionFlash.POP_MILLIS - 1);
        assertThat(flash.isRunning()).isTrue();
        assertThat(flash.intensity()).isGreaterThan(0f);
    }

    @Test
    void notRunningAtDuration() throws Exception {
        SwingUtilities.invokeAndWait(flash::start);

        clock.addAndGet(AttentionFlash.DURATION_MILLIS);

        assertThat(flash.isRunning()).isFalse();
        assertThat(flash.intensity()).isZero();
    }

    @Test
    void restartRePopsFromZero() throws Exception {
        SwingUtilities.invokeAndWait(flash::start);
        clock.addAndGet(300);
        assertThat(flash.isRunning()).isTrue();
        assertThat(flash.intensity()).isGreaterThan(0f).isLessThan(1f);

        SwingUtilities.invokeAndWait(flash::start);

        assertThat(flash.isRunning()).isTrue();
        assertThat(flash.intensity()).isZero();
        clock.addAndGet(AttentionFlash.POP_MILLIS);
        assertThat(flash.intensity()).isEqualTo(1f);
    }

    @Test
    void stopCancelsTheFlash() throws Exception {
        SwingUtilities.invokeAndWait(flash::start);
        clock.addAndGet(AttentionFlash.POP_MILLIS);

        SwingUtilities.invokeAndWait(flash::stop);

        assertThat(flash.isRunning()).isFalse();
        assertThat(flash.intensity()).isZero();
    }

    @Test
    void doubleCurvePopsTwiceWithAGapBetween() {
        int second = AttentionFlash.DURATION_MILLIS + AttentionFlash.DOUBLE_GAP_MILLIS;
        assertThat(AttentionFlash.intensity(AttentionFlash.POP_MILLIS, true, 0)).isEqualTo(1f);
        assertThat(AttentionFlash.intensity(AttentionFlash.DURATION_MILLIS + 1, true, 0)).isZero();
        assertThat(AttentionFlash.intensity(second + AttentionFlash.POP_MILLIS, true, 0)).isEqualTo(1f);
        assertThat(AttentionFlash.intensity(AttentionFlash.DOUBLE_DURATION_MILLIS, true, 0)).isZero();
    }

    @Test
    void reminderPopsOnlyAtItsInterval() {
        int interval = AttentionFlash.REPEAT_INTERVAL_MILLIS;
        assertThat(AttentionFlash.intensity(interval - 1, true, interval)).isZero();
        assertThat(AttentionFlash.intensity(interval + AttentionFlash.POP_MILLIS, true, interval)).isEqualTo(1f);
        assertThat(AttentionFlash.intensity(interval + AttentionFlash.DURATION_MILLIS, true, interval)).isZero();
        assertThat(AttentionFlash.intensity(2L * interval + AttentionFlash.POP_MILLIS, true, interval)).isEqualTo(1f);
    }

    @Test
    void doubleFlashRunsTwiceThenWaitsForItsReminder() throws Exception {
        SwingUtilities.invokeAndWait(() -> flash.startDouble(AttentionFlash.REPEAT_INTERVAL_MILLIS));

        assertThat(flash.isRepeating()).isTrue();
        clock.addAndGet(AttentionFlash.DURATION_MILLIS + AttentionFlash.DOUBLE_GAP_MILLIS);
        assertThat(flash.isRunning()).as("second pulse still to come").isTrue();

        clock.addAndGet(AttentionFlash.DOUBLE_DURATION_MILLIS - AttentionFlash.DURATION_MILLIS
                - AttentionFlash.DOUBLE_GAP_MILLIS);
        assertThat(flash.isRunning()).as("quiet between pulses").isFalse();
        assertThat(flash.intensity()).isZero();

        clock.addAndGet(AttentionFlash.REPEAT_INTERVAL_MILLIS - AttentionFlash.DOUBLE_DURATION_MILLIS
                + AttentionFlash.POP_MILLIS);
        assertThat(flash.isRunning()).isTrue();
        assertThat(flash.intensity()).isEqualTo(1f);
    }

    @Test
    void stopDisarmsTheReminders() throws Exception {
        SwingUtilities.invokeAndWait(() -> flash.startDouble(AttentionFlash.REPEAT_INTERVAL_MILLIS));

        SwingUtilities.invokeAndWait(flash::stop);

        assertThat(flash.isRepeating()).isFalse();
        assertThat(flash.isRunning()).isFalse();
    }
}
