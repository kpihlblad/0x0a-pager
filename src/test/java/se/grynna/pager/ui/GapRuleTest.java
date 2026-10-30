package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GapRuleTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final Optional<Duration> FIVE_MINUTES = Optional.of(Duration.ofMinutes(5));

    @Test
    void sameDaySmallGapGivesNone() {
        Instant prev = Instant.parse("2024-09-17T10:00:00Z");
        Instant next = Instant.parse("2024-09-17T10:01:00Z");

        assertThat(GapRule.between(prev, next, FIVE_MINUTES, UTC)).isInstanceOf(GapRule.None.class);
    }

    @Test
    void bigGapGivesGapWithExactDurationKeepingNanos() {
        Instant prev = Instant.parse("2024-09-17T10:00:00.000000001Z");
        Instant next = Instant.parse("2024-09-17T10:20:00.000000123Z");

        GapRule rule = GapRule.between(prev, next, FIVE_MINUTES, UTC);

        assertThat(rule).isEqualTo(new GapRule.Gap(Duration.ofMinutes(20).plusNanos(122)));
    }

    @Test
    void gapExactlyAtThresholdIsNotMarked() {
        Instant prev = Instant.parse("2024-09-17T10:00:00Z");
        Instant next = Instant.parse("2024-09-17T10:05:00Z");

        assertThat(GapRule.between(prev, next, FIVE_MINUTES, UTC)).isInstanceOf(GapRule.None.class);
    }

    @Test
    void midnightCrossingGivesDateEvenWithTinyGap() {
        Instant prev = Instant.parse("2024-09-17T23:59:59.999999999Z");
        Instant next = Instant.parse("2024-09-18T00:00:00Z");

        GapRule rule = GapRule.between(prev, next, Optional.of(Duration.ofHours(1)), UTC);

        assertThat(rule).isEqualTo(new GapRule.Date(LocalDate.of(2024, 9, 18)));
    }

    @Test
    void dateWinsOverGap() {
        Instant prev = Instant.parse("2024-09-17T23:00:00Z");
        Instant next = Instant.parse("2024-09-18T06:00:00Z");

        GapRule rule = GapRule.between(prev, next, FIVE_MINUTES, UTC);

        assertThat(rule).isEqualTo(new GapRule.Date(LocalDate.of(2024, 9, 18)));
    }

    @Test
    void zoneDecidesWhetherTheDayChanged() {
        Instant prev = Instant.parse("2024-09-17T21:50:00Z"); // 23:50 Stockholm
        Instant next = Instant.parse("2024-09-17T22:10:00Z"); // 00:10 Stockholm, next day

        assertThat(GapRule.between(prev, next, Optional.empty(), UTC)).isInstanceOf(GapRule.None.class);
        assertThat(GapRule.between(prev, next, Optional.empty(), STOCKHOLM))
                .isEqualTo(new GapRule.Date(LocalDate.of(2024, 9, 18)));
    }

    @Test
    void equalTimesGiveNone() {
        Instant t = Instant.parse("2024-09-17T10:00:00Z");

        assertThat(GapRule.between(t, t, Optional.of(Duration.ZERO), UTC)).isInstanceOf(GapRule.None.class);
        assertThat(GapRule.between(t, Instant.parse("2024-09-17T10:00:00Z"), FIVE_MINUTES, UTC))
                .isInstanceOf(GapRule.None.class);
    }

    @Test
    void nullTimesGiveNone() {
        Instant t = Instant.parse("2024-09-17T10:00:00Z");

        assertThat(GapRule.between(null, t, FIVE_MINUTES, UTC)).isInstanceOf(GapRule.None.class);
        assertThat(GapRule.between(t, null, FIVE_MINUTES, UTC)).isInstanceOf(GapRule.None.class);
    }

    @Test
    void emptyThresholdGivesOnlyDateRules() {
        Instant prev = Instant.parse("2024-09-17T10:00:00Z");
        Instant next = Instant.parse("2024-09-17T20:00:00Z");
        Instant nextDay = Instant.parse("2024-09-18T10:00:00Z");

        assertThat(GapRule.between(prev, next, Optional.empty(), UTC)).isInstanceOf(GapRule.None.class);
        assertThat(GapRule.between(prev, nextDay, Optional.empty(), UTC))
                .isEqualTo(new GapRule.Date(LocalDate.of(2024, 9, 18)));
    }
}
