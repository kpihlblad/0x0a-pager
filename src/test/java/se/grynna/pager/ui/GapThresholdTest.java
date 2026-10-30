package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GapThresholdTest {

    private static final Instant START = Instant.parse("2024-09-17T08:00:00Z");

    private static List<Instant> uniform(Duration spacing, int count) {
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            times.add(START.plus(spacing.multipliedBy(i)));
        }
        return times;
    }

    @Test
    void uniformSpacingGivesEightTimesTheSpacing() {
        Optional<Duration> threshold = GapThreshold.adaptive(uniform(Duration.ofSeconds(30), 20));

        assertThat(threshold).contains(Duration.ofMinutes(4));
    }

    @Test
    void uniformSpacingNeverExceedsThresholdInTheList() {
        List<Instant> times = uniform(Duration.ofMinutes(2), 50);

        Duration threshold = GapThreshold.adaptive(times).orElseThrow();

        for (int i = 1; i < times.size(); i++) {
            assertThat(Duration.between(times.get(i - 1), times.get(i))).isLessThanOrEqualTo(threshold);
        }
    }

    @Test
    void burstsGiveThresholdBelowTheSeparation() {
        List<Instant> times = new ArrayList<>();
        for (int burst = 0; burst < 5; burst++) {
            Instant base = START.plus(Duration.ofMinutes(10).multipliedBy(burst));
            for (int i = 0; i < 4; i++) {
                times.add(base.plusMillis(100L * i));
            }
        }

        Duration threshold = GapThreshold.adaptive(times).orElseThrow();

        assertThat(threshold).isLessThan(Duration.ofMinutes(10));
        assertThat(Duration.between(times.get(3), times.get(4))).isGreaterThan(threshold);
    }

    @Test
    void equalNeighboursAreIgnoredForTheMedian() {
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            times.add(START.plusSeconds(60L * i));
            times.add(START.plusSeconds(60L * i));
        }

        assertThat(GapThreshold.adaptive(times)).contains(Duration.ofMinutes(8));
    }

    @Test
    void allEqualTimesGiveNoThreshold() {
        List<Instant> times = List.of(START, START, START, START);

        assertThat(GapThreshold.adaptive(times)).isEmpty();
    }

    @Test
    void clampsToOneSecondMinimum() {
        assertThat(GapThreshold.adaptive(uniform(Duration.ofMillis(10), 20))).contains(Duration.ofSeconds(1));
    }

    @Test
    void clampsToOneHourMaximum() {
        assertThat(GapThreshold.adaptive(uniform(Duration.ofHours(2), 20))).contains(Duration.ofHours(1));
    }

    @Test
    void fewerThanThreeTimesGiveNoThreshold() {
        assertThat(GapThreshold.adaptive(List.of())).isEmpty();
        assertThat(GapThreshold.adaptive(List.of(START))).isEmpty();
        assertThat(GapThreshold.adaptive(List.of(START, START.plusSeconds(5)))).isEmpty();
    }

    @Test
    void trackerComputesFirstCallAndKeepsValueWhileGrowingLessThanTenPercent() {
        GapThreshold.Tracker tracker = new GapThreshold.Tracker();
        List<Instant> times = uniform(Duration.ofSeconds(10), 100);

        Optional<Duration> first = tracker.update(times.subList(0, 50), false);
        assertThat(first).contains(Duration.ofSeconds(80));

        // Spacing changes, but 54 < 55 (+10 %): cached value must be returned.
        List<Instant> wider = new ArrayList<>(times.subList(0, 50));
        for (int i = 50; i < 54; i++) {
            wider.add(START.plusSeconds(1000L * i));
        }
        assertThat(tracker.update(wider, false)).isEqualTo(first);
    }

    @Test
    void trackerRecomputesAtTenPercentGrowth() {
        GapThreshold.Tracker tracker = new GapThreshold.Tracker();
        tracker.update(uniform(Duration.ofSeconds(10), 50), false);

        List<Instant> wider = uniform(Duration.ofSeconds(100), 55);
        assertThat(tracker.update(wider, false)).contains(Duration.ofMinutes(13).plusSeconds(20));
    }

    @Test
    void trackerRecomputesWhenFinishedFlips() {
        GapThreshold.Tracker tracker = new GapThreshold.Tracker();
        tracker.update(uniform(Duration.ofSeconds(10), 50), false);

        List<Instant> same = uniform(Duration.ofSeconds(100), 50);
        assertThat(tracker.update(same, false)).contains(Duration.ofSeconds(80));
        assertThat(tracker.update(same, true)).contains(Duration.ofMinutes(13).plusSeconds(20));
        assertThat(tracker.update(uniform(Duration.ofSeconds(10), 50), true))
                .contains(Duration.ofMinutes(13).plusSeconds(20));
    }
}
