package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.preferences.DTUtil;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionSpanTextTest {

    private static String dur(Duration d) {
        return SelectionSpanText.duration(d, '.');
    }

    @Test
    void zeroIsZeroSeconds() {
        assertThat(dur(Duration.ZERO)).isEqualTo("0s");
    }

    @Test
    void subMicrosecondsAreNanoseconds() {
        assertThat(dur(Duration.ofNanos(999))).isEqualTo("999ns");
        assertThat(dur(Duration.ofNanos(340))).isEqualTo("340ns");
    }

    @Test
    void subMillisecondsAreWholeMicroseconds() {
        assertThat(dur(Duration.ofNanos(1_000))).isEqualTo("1\u00B5s");
        assertThat(dur(Duration.ofNanos(12_999))).isEqualTo("12\u00B5s");
    }

    @Test
    void subSecondsAreWholeMilliseconds() {
        assertThat(dur(Duration.ofMillis(340))).isEqualTo("340ms");
        assertThat(dur(Duration.ofNanos(999_999_999))).isEqualTo("999ms");
    }

    @Test
    void underAMinuteShowsTenths() {
        assertThat(dur(Duration.ofMillis(13_400))).isEqualTo("13.4s");
        assertThat(dur(Duration.ofMillis(1_000))).isEqualTo("1.0s");
        assertThat(dur(Duration.ofMillis(59_960))).isEqualTo("59.9s");
    }

    @Test
    void underAnHourShowsMinutesAndPaddedSecondsWithTenths() {
        assertThat(dur(Duration.ofSeconds(133, 400_000_000))).isEqualTo("2m 13.4s");
        assertThat(dur(Duration.ofSeconds(123, 400_000_000))).isEqualTo("2m 03.4s");
    }

    @Test
    void underADayShowsHoursMinutesAndWholeSeconds() {
        assertThat(dur(Duration.ofSeconds(3 * 3_600 + 2 * 60 + 10))).isEqualTo("3h 02m 10s");
    }

    @Test
    void longerShowsDaysHoursAndMinutes() {
        assertThat(dur(Duration.ofDays(2).plusHours(4).plusMinutes(5))).isEqualTo("2d 4h 05m");
    }

    @Test
    void negativeDurationsAreShownAbsolute() {
        assertThat(dur(Duration.ofMillis(-13_400))).isEqualTo("13.4s");
        assertThat(dur(Duration.ofMillis(-133_400))).isEqualTo("2m 13.4s");
    }

    @Test
    void decimalSeparatorIsTheGivenCharacter() {
        assertThat(SelectionSpanText.duration(Duration.ofMillis(13_400), ',')).isEqualTo("13,4s");
        assertThat(SelectionSpanText.duration(Duration.ofSeconds(123, 400_000_000), ',')).isEqualTo("2m 03,4s");
    }

    @Test
    void rangeOnTheSameDayShowsTimesOnly() {
        var first = Instant.parse("2026-10-25T12:00:01.200Z");
        var last = Instant.parse("2026-10-25T12:02:14.600Z");

        assertThat(SelectionSpanText.range(first, last, ZoneOffset.UTC,
                DTUtil.createDateTimeFormatterWithMillis(Locale.UK),
                DTUtil.createTimeFormatterWithMillis(Locale.UK), '.'))
                .isEqualTo("12:00:01.200 \u2192 12:02:14.600 (\u0394 2m 13.4s)");
    }

    @Test
    void rangeAcrossMidnightShowsDates() {
        var first = Instant.parse("2026-10-25T23:59:30.000Z");
        var last = Instant.parse("2026-10-26T00:00:05.500Z");

        assertThat(SelectionSpanText.range(first, last, ZoneOffset.UTC,
                DTUtil.createDateTimeFormatterWithMillis(Locale.UK),
                DTUtil.createTimeFormatterWithMillis(Locale.UK), '.'))
                .isEqualTo("25/10/2026, 23:59:30.000 \u2192 26/10/2026, 00:00:05.500 (\u0394 35.5s)");
    }

    @Test
    void rangeUsesTheLocaleDecimalSeparatorOfTheFormatter() {
        var first = Instant.parse("2026-10-25T12:00:01.200Z");
        var last = Instant.parse("2026-10-25T12:02:14.600Z");
        var swedish = Locale.forLanguageTag("sv-SE");

        assertThat(
                SelectionSpanText.range(first, last, ZoneOffset.UTC, DTUtil.createDateTimeFormatterWithMillis(swedish),
                        DTUtil.createTimeFormatterWithMillis(swedish), ','))
                .isEqualTo("12:00:01,200 \u2192 12:02:14,600 (\u0394 2m 13,4s)");
    }

    @Test
    void rangeKeepsTheOrderGiven() {
        var first = Instant.parse("2026-10-25T12:02:14.600Z");
        var last = Instant.parse("2026-10-25T12:00:01.200Z");
        DateTimeFormatter time = DTUtil.createTimeFormatterWithMillis(Locale.UK);

        assertThat(SelectionSpanText.range(first, last, ZoneOffset.UTC,
                DTUtil.createDateTimeFormatterWithMillis(Locale.UK),
                time, '.'))
                .isEqualTo("12:02:14.600 \u2192 12:00:01.200 (\u0394 2m 13.4s)");
    }
}
