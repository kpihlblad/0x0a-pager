package se.grynna.pager.ui.time;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.Fontalizer;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MonthBoxTest {

    @Test
    void gridIsIsoMondayFirstClickPicksADayAndWheelUpStepsForward() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MonthBox box = new MonthBox(Fontalizer.getFont(), ZoneOffset.UTC,
                    Clock.fixed(Instant.parse("2017-05-03T08:00:00Z"), ZoneOffset.UTC));
            box.setSize(box.getPreferredSize());
            box.setMonth(YearMonth.of(2017, 5));
            AtomicReference<LocalDate> picked = new AtomicReference<>();
            box.setOnDayPicked(picked::set);

            int x = box.getWidth() / 2;
            int y = box.getHeight() - 12;
            LocalDate lastCell = box.dayAt(box.getWidth() - 10, y);
            box.dispatchEvent(new MouseEvent(box, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, x, y, 1,
                    false, MouseEvent.BUTTON1));

            // May 2017 starts on a Monday, so the sixth row ends on Sunday June 11.
            assertThat(lastCell).isEqualTo(LocalDate.of(2017, 6, 11));
            assertThat(picked).hasValue(LocalDate.of(2017, 6, 8));
            assertThat(box.isFocusable()).isFalse();

            box.dispatchEvent(new MouseWheelEvent(box, MouseEvent.MOUSE_WHEEL, 0, 0, x, y, x, y, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1, -1.0));
            assertThat(box.getMonth()).isEqualTo(YearMonth.of(2017, 6));
        });
    }
}
