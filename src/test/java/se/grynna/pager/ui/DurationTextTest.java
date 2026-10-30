package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DurationTextTest {

    @Test
    void subSecondAndTensOfSecondsUseOneDecimal() {
        assertThat(DurationText.shortText(Duration.ZERO)).isEqualTo("0.0s");
        assertThat(DurationText.shortText(Duration.ofMillis(400))).isEqualTo("0.4s");
        assertThat(DurationText.shortText(Duration.ofMillis(3250))).isEqualTo("3.2s");
        assertThat(DurationText.shortText(Duration.ofMillis(9999))).isEqualTo("9.9s");
    }

    @Test
    void tenSecondsToOneMinuteUsesWholeSeconds() {
        assertThat(DurationText.shortText(Duration.ofSeconds(10))).isEqualTo("10s");
        assertThat(DurationText.shortText(Duration.ofSeconds(42))).isEqualTo("42s");
        assertThat(DurationText.shortText(Duration.ofSeconds(59))).isEqualTo("59s");
        assertThat(DurationText.shortText(Duration.ofMillis(59_999))).isEqualTo("59s");
    }

    @Test
    void oneMinuteToOneHourUsesWholeMinutes() {
        assertThat(DurationText.shortText(Duration.ofSeconds(60))).isEqualTo("1m");
        assertThat(DurationText.shortText(Duration.ofMinutes(14))).isEqualTo("14m");
        assertThat(DurationText.shortText(Duration.ofSeconds(3599))).isEqualTo("59m");
    }

    @Test
    void oneHourToOneDayUsesHoursAndNonZeroMinutes() {
        assertThat(DurationText.shortText(Duration.ofHours(1))).isEqualTo("1h");
        assertThat(DurationText.shortText(Duration.ofMinutes(130))).isEqualTo("2h 10m");
        assertThat(DurationText.shortText(Duration.ofHours(2))).isEqualTo("2h");
        assertThat(DurationText.shortText(Duration.ofSeconds(86399))).isEqualTo("23h 59m");
    }

    @Test
    void oneDayAndUpUsesDaysAndNonZeroHours() {
        assertThat(DurationText.shortText(Duration.ofDays(1))).isEqualTo("1d");
        assertThat(DurationText.shortText(Duration.ofHours(76))).isEqualTo("3d 4h");
        assertThat(DurationText.shortText(Duration.ofDays(3))).isEqualTo("3d");
        assertThat(DurationText.shortText(Duration.ofDays(3).plusMinutes(59))).isEqualTo("3d");
    }

    @Test
    void negativeDurationsUseTheAbsoluteValue() {
        assertThat(DurationText.shortText(Duration.ofSeconds(-42))).isEqualTo("42s");
        assertThat(DurationText.shortText(Duration.ofMillis(-3250))).isEqualTo("3.2s");
    }
}
