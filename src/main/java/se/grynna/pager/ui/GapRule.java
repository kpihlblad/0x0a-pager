package se.grynna.pager.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The rule drawn between two consecutive events: nothing, a gap marker, or a date marker.
 * A day change always wins over a gap.
 */
public sealed interface GapRule {

    /** No rule between the two events. */
    record None() implements GapRule {}

    /** The gap between the events is longer than the threshold. */
    record Gap(Duration duration) implements GapRule {}

    /** The local day changes; {@code date} is the local date of the later event. */
    record Date(LocalDate date) implements GapRule {}

    /**
     * @param prev      earlier event time, or null
     * @param next      later event time, or null
     * @param threshold gap threshold from {@link GapThreshold}; empty means only date rules
     * @param zone      zone in which the local day is compared
     * @return the rule to draw between {@code prev} and {@code next}
     */
    static GapRule between(Instant prev, Instant next, Optional<Duration> threshold, ZoneId zone) {
        if (prev == null || next == null || prev.compareTo(next) == 0) {
            return new None();
        }
        LocalDate prevDate = prev.atZone(zone).toLocalDate();
        LocalDate nextDate = next.atZone(zone).toLocalDate();
        if (!prevDate.equals(nextDate)) {
            return new Date(nextDate);
        }
        Duration gap = Duration.between(prev, next);
        if (threshold.isPresent() && gap.compareTo(threshold.get()) > 0) {
            return new Gap(gap);
        }
        return new None();
    }
}
