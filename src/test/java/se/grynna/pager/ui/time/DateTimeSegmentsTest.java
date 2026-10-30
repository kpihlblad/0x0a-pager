package se.grynna.pager.ui.time;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.time.DateTimeSegments.Segment;
import se.grynna.pager.ui.time.DateTimeSegments.TypingState;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DateTimeSegmentsTest {

    private static LocalDateTime t(String iso) {
        return LocalDateTime.parse(iso);
    }

    private static TypingState type(TypingState s, String keys) {
        for (char c : keys.toCharArray()) {
            s = DateTimeSegments.isSeparator(c) ? s.separator() : s.digit(c);
        }
        return s;
    }

    @Test
    void stepsCarryLikeAClock() {
        assertThat(DateTimeSegments.step(t("2017-05-01T12:59:59"), Segment.SECOND, 1))
                .isEqualTo(t("2017-05-01T13:00:00"));
        assertThat(DateTimeSegments.step(t("2017-05-01T00:00:30"), Segment.MINUTE, -1))
                .isEqualTo(t("2017-04-30T23:59:30"));
        assertThat(DateTimeSegments.step(t("2016-12-31T23:00:00"), Segment.DAY, 1)).isEqualTo(t("2017-01-01T23:00"));
        assertThat(DateTimeSegments.step(t("2017-12-31T23:59:59"), Segment.SECOND, 1)).isEqualTo(t("2018-01-01T00:00"));
    }

    @Test
    void monthAndYearStepsClampTheDay() {
        assertThat(DateTimeSegments.step(t("2017-01-31T10:00:00"), Segment.MONTH, 1)).isEqualTo(t("2017-02-28T10:00"));
        assertThat(DateTimeSegments.step(t("2016-01-31T10:00:00"), Segment.MONTH, 1)).isEqualTo(t("2016-02-29T10:00"));
        assertThat(DateTimeSegments.step(t("2016-02-29T10:00:00"), Segment.YEAR, 1)).isEqualTo(t("2017-02-28T10:00"));
        assertThat(DateTimeSegments.stepLarger(t("2017-05-01T10:00:00"), Segment.HOUR, 1))
                .isEqualTo(t("2017-05-02T10:00"));
    }

    @Test
    void typingAllDigitsFromTheYearFillsEverySegment() {
        TypingState s = type(TypingState.of(t("2000-01-01T00:00:00"), Segment.YEAR), "20170501120314");

        assertThat(s.value()).isEqualTo(t("2017-05-01T12:03:14"));
        assertThat(s.isTyping()).isFalse();
    }

    @Test
    void separatorsCompleteAPartialSegmentButNotAfterAutoAdvance() {
        TypingState s = type(TypingState.of(t("2000-01-01T00:00:00"), Segment.YEAR), "2017-5-1 9:3:7").commit();

        assertThat(s.value()).isEqualTo(t("2017-05-01T09:03:07"));
        assertThat(type(TypingState.of(t("2000-01-01T00:00:00"), Segment.YEAR), "2017-05-01 12:03:14").value())
                .isEqualTo(t("2017-05-01T12:03:14"));
    }

    @Test
    void outOfRangeSegmentClampsOnCompletion() {
        TypingState month = type(TypingState.of(t("2017-03-31T00:00:00"), Segment.MONTH), "13");
        TypingState day = type(TypingState.of(t("2017-02-01T00:00:00"), Segment.DAY), "31");

        assertThat(month.value()).isEqualTo(t("2017-12-31T00:00"));
        assertThat(month.segment()).isEqualTo(Segment.DAY);
        assertThat(day.value()).isEqualTo(t("2017-02-28T00:00"));
    }

    @Test
    void backspaceDropsATypedDigitThenMovesFocusLeft() {
        TypingState typing = type(TypingState.of(t("2017-05-01T12:03:14"), Segment.MONTH), "1");

        assertThat(typing.backspace().buffer()).isEmpty();
        assertThat(typing.backspace().segment()).isEqualTo(Segment.MONTH);

        TypingState idle = TypingState.of(t("2017-05-01T12:03:14"), Segment.MINUTE);
        assertThat(idle.backspace().segment()).isEqualTo(Segment.HOUR);
        assertThat(idle.backspace().value()).isEqualTo(t("2017-05-01T12:03:14"));
        assertThat(TypingState.of(t("2017-05-01T12:03:14"), Segment.YEAR).backspace().segment())
                .isEqualTo(Segment.YEAR);
    }

    @Test
    void columnsMapToSegments() {
        assertThat(DateTimeSegments.format(t("2017-05-01T12:03:14"))).isEqualTo("2017-05-01 12:03:14");
        assertThat(DateTimeSegments.segmentAt(0)).isEqualTo(Segment.YEAR);
        assertThat(DateTimeSegments.segmentAt(5)).isEqualTo(Segment.MONTH);
        assertThat(DateTimeSegments.segmentAt(12)).isEqualTo(Segment.HOUR);
        assertThat(DateTimeSegments.segmentAt(40)).isEqualTo(Segment.SECOND);
    }
}
