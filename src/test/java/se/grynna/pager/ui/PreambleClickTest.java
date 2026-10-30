package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreambleClickTest {

    @Test
    void columnsBeforeTheMessageTextAreThePreamble() {
        assertThat(CursorManager.isPreambleColumn(0, 24)).isTrue();
        assertThat(CursorManager.isPreambleColumn(23, 24)).isTrue();
        assertThat(CursorManager.isPreambleColumn(24, 24)).isFalse();
        assertThat(CursorManager.isPreambleColumn(80, 24)).isFalse();
    }

    @Test
    void negativeColumnsAreNeverThePreamble() {
        assertThat(CursorManager.isPreambleColumn(-1, 24)).isFalse();
    }

    @Test
    void noPreambleMeansNoColumnIsPreamble() {
        assertThat(CursorManager.isPreambleColumn(0, 0)).isFalse();
    }
}
