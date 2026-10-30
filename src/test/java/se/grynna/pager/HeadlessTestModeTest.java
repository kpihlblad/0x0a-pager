package se.grynna.pager;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the surefire setup: unit tests run headless, so no test can open a window. */
class HeadlessTestModeTest {

    @Test
    void testsRunHeadless() {
        assertThat(GraphicsEnvironment.isHeadless()).isTrue();
    }
}
