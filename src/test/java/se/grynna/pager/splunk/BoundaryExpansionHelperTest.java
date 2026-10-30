package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BoundaryExpansionHelperTest {

    private static final Instant NOW = Instant.parse("2026-10-26T13:47:00Z");

    @Test
    void theFinalEarlierStepIsExactlyTheAbsoluteMinimum() {
        for (String boundary : new String[]{"2015-06-28T00:00:00Z", "2014-03-28T01:00:00Z",
                "2017-12-26T22:30:20.123456789Z"}) {
            assertThat(BoundaryExpansionHelper.calculateExpansion(BoundaryType.BEFORE, Instant.parse(boundary), 5, NOW))
                    .as(boundary).isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY);
        }
    }

    @Test
    void stepsGrowFromADayAndNeverPassTheLimits() {
        Instant from = Instant.parse("2017-06-15T00:00:00Z");
        assertThat(BoundaryExpansionHelper.calculateExpansion(BoundaryType.BEFORE, from, 1, NOW))
                .isBefore(from).isAfter(from.minusSeconds(3 * 86400));
        assertThat(BoundaryExpansionHelper.calculateExpansion(BoundaryType.AFTER, from, 6, NOW)).isEqualTo(NOW);
    }
}
