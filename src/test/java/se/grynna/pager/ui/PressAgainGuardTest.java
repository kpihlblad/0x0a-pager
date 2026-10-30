package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PressAgainGuardTest {

    @Test
    void firstPressOnlyArms() {
        var guard = new PressAgainGuard(2_000);

        assertThat(guard.press(10_000)).isFalse();
    }

    @Test
    void secondPressWithinWindowFiresAndDisarms() {
        var guard = new PressAgainGuard(2_000);

        guard.press(10_000);

        assertThat(guard.press(11_999)).isTrue();
        assertThat(guard.press(12_000)).as("firing disarms: the next press is a first press").isFalse();
    }

    @Test
    void pressAfterTheWindowIsAFirstPressAgain() {
        var guard = new PressAgainGuard(2_000);

        guard.press(10_000);

        assertThat(guard.press(12_000)).as("exactly at the window end is already too late").isFalse();
        assertThat(guard.press(12_500)).as("the late press armed a new window").isTrue();
    }

    @Test
    void resetDisarms() {
        var guard = new PressAgainGuard(2_000);

        guard.press(10_000);
        guard.reset();

        assertThat(guard.press(10_500)).isFalse();
    }

    @Test
    void defaultWindowIsTwoSeconds() {
        var guard = new PressAgainGuard();

        guard.press(0);

        assertThat(guard.press(PressAgainGuard.DEFAULT_WINDOW_MILLIS - 1)).isTrue();
    }
}
