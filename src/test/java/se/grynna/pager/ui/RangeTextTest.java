package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchBoundaries;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RangeTextTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);

    @Test
    void aStartCloseToNowIsATimeNotNow() {
        Instant start = NOW.minusSeconds(120);
        assertThat(range(start, NOW, NOW, NOW, UTC)).isEqualTo("11:58:00 \u2192 now");
    }

    @Test
    void sameDayEndpointsAreTimeOnly() {
        Instant start = Instant.parse("2017-04-28T11:00:00Z");
        Instant end = Instant.parse("2017-04-28T12:10:00Z");

        assertThat(range(start, end, start, NOW, UTC)).isEqualTo("11:00:00 \u2192 12:10:00");
    }

    @Test
    void crossDayEndpointUsesDateAndMinute() {
        Instant start = Instant.parse("2017-04-28T09:00:00Z");
        Instant end = Instant.parse("2017-04-29T10:00:30Z");

        assertThat(range(start, end, end, NOW, UTC)).isEqualTo("2017-04-28 09:00 \u2192 10:00:30");
        assertThat(endpoint(end, start, NOW, UTC)).isEqualTo("2017-04-29 10:00:30");
    }

    @Test
    void secondsAreShownOnlyWhenNotZero() {
        Instant start = Instant.parse("2017-04-28T09:00:00Z");
        Instant end = Instant.parse("2017-04-29T10:00:00Z");

        Instant otherDay = Instant.parse("2017-04-29T00:00:00Z");

        assertThat(endpoint(start, otherDay, NOW, UTC)).isEqualTo("2017-04-28 09:00");
        assertThat(endpoint(start.plusSeconds(30), otherDay, NOW, UTC)).isEqualTo("2017-04-28 09:00:30");
        assertThat(endpoint(end, start, NOW, UTC)).isEqualTo("2017-04-29 10:00");
    }

    @Test
    void anchorDateDecidesTimeOnly() {
        Instant start = Instant.parse("2017-04-28T09:00:00Z");
        Instant end = Instant.parse("2017-04-29T10:00:00Z");

        assertThat(range(start, end, end, NOW, UTC)).isEqualTo("2017-04-28 09:00 \u2192 10:00:00");
    }

    @Test
    void nullAnchorUsesEndDate() {
        Instant start = Instant.parse("2017-04-27T23:00:00Z");
        Instant end = Instant.parse("2017-04-28T01:00:00Z");

        assertThat(range(start, end, null, NOW, UTC)).isEqualTo("2017-04-27 23:00 \u2192 01:00:00");
    }

    @Test
    void localDateDiffersFromUtcDate() {
        Instant start = Instant.parse("2017-04-27T23:30:00Z");
        Instant end = Instant.parse("2017-04-28T06:00:00Z");

        assertThat(range(start, end, end, NOW, STOCKHOLM)).isEqualTo("01:30:00 \u2192 08:00:00");
        assertThat(range(start, end, end, NOW, UTC)).isEqualTo("2017-04-27 23:30 \u2192 06:00:00");
    }

    @Test
    void endNearNowReadsNowOnBothSides() {
        Instant start = Instant.parse("2017-04-28T11:00:00Z");
        Instant now = Instant.parse("2017-04-28T12:10:00Z");
        Duration slack = SearchRangeOffer.LATER_SLACK;

        assertThat(endpoint(now.minus(slack), start, now, UTC)).isEqualTo("now");
        assertThat(endpoint(now.plus(slack), start, now, UTC)).isEqualTo("now");
        assertThat(endpoint(now.minus(slack).minusSeconds(1), start, now, UTC)).isEqualTo("12:04:59");
        assertThat(endpoint(now.plus(slack).plusSeconds(1), start, now, UTC)).isEqualTo("12:15:01");
        assertThat(range(start, now.minus(Duration.ofMinutes(4)), start, now, UTC))
                .isEqualTo("11:00:00 \u2192 now");
    }

    @Test
    void startBoundaryReadsTheMinimumYear() {
        Instant end = Instant.parse("2017-04-28T12:10:00Z");

        assertThat(endpoint(SearchBoundaries.MIN_MINIMUM_BOUNDARY, end, NOW, UTC)).isEqualTo("1971");
        assertThat(endpoint(SearchBoundaries.MIN_MINIMUM_BOUNDARY.minusSeconds(3600), end, NOW, UTC))
                .isEqualTo("1971");
        assertThat(range(SearchBoundaries.MIN_MINIMUM_BOUNDARY, end, end, NOW, UTC))
                .isEqualTo("1971 \u2192 12:10:00");
    }

    @Test
    void startToNowIsEverything() {
        Instant now = Instant.parse("2017-04-28T12:10:00Z");

        assertThat(range(SearchBoundaries.MIN_MINIMUM_BOUNDARY, now, now, now, UTC)).isEqualTo("everything");
        assertThat(range(SearchBoundaries.MIN_MINIMUM_BOUNDARY, now.minus(Duration.ofMinutes(10)), now, now,
                UTC)).isEqualTo("1971 \u2192 12:00:00");
    }

    @Test
    void stepEarlierAndLater() {
        Instant edge = Instant.parse("2017-04-28T12:10:00Z");

        assertThat(step(edge, edge.minus(Duration.ofHours(1)), edge, NOW, UTC))
                .isEqualTo("1h earlier (11:10:00)");
        assertThat(step(edge, edge.plus(Duration.ofHours(2)), edge, NOW, UTC))
                .isEqualTo("2h later (14:10:00)");
    }

    @Test
    void stepToStartAndToNow() {
        Instant edge = Instant.parse("2017-04-28T12:10:00Z");

        assertThat(step(edge, SearchBoundaries.MIN_MINIMUM_BOUNDARY, edge, NOW, UTC))
                .isEqualTo("back to 1971");
        assertThat(step(edge, NOW, edge, NOW, UTC)).isEqualTo("up to now");
    }

    @Test
    void stepShowsDateWhenAwayFromAnchor() {
        Instant edge = Instant.parse("2017-04-28T09:00:00Z");
        Instant suggested = Instant.parse("2017-04-25T09:00:00Z");

        assertThat(step(edge, suggested, edge, NOW, UTC)).isEqualTo("3d earlier (2017-04-25 09:00)");
    }

    @Test
    void coarseDeltaUnits() {
        Instant edge = Instant.parse("2017-04-30T12:10:00Z");

        assertThat(step(edge, edge.minusSeconds(45), edge, NOW, UTC)).startsWith("45s earlier (");
        assertThat(step(edge, edge.minus(Duration.ofMinutes(10)), edge, NOW, UTC))
                .startsWith("10m earlier (");
        assertThat(step(edge, edge.minus(Duration.ofMinutes(90)), edge, NOW, UTC))
                .startsWith("1h 30m earlier (");
        assertThat(step(edge, edge.minus(Duration.ofDays(3)), edge, NOW, UTC))
                .startsWith("3d earlier (");
        assertThat(step(edge, edge.minus(Duration.ofDays(2).plusHours(4)), edge, NOW, UTC))
                .startsWith("2d 4h earlier (");
    }

    @Test
    void coarseTruncatesLowerUnits() {
        assertThat(RangeText.coarse(Duration.ofSeconds(3_600 + 5))).isEqualTo("1h");
        assertThat(RangeText.coarse(Duration.ofSeconds(3 * 86_400 + 4 * 3_600 + 30 * 60))).isEqualTo("3d 4h");
        assertThat(RangeText.coarse(Duration.ofSeconds(10 * 60 + 30))).isEqualTo("10m 30s");
        assertThat(RangeText.coarse(Duration.ZERO)).isEqualTo("0s");
    }

    @Test
    void fullTooltipUsesLongFormatAndDuration() {
        Instant start = Instant.parse("2017-04-28T09:00:00Z");
        Instant end = Instant.parse("2017-04-28T10:01:30Z");

        assertThat(full(start, end, UTC))
                .isEqualTo("2017-04-28 09:00:00 \u2192 2017-04-28 10:01:30 (\u0394 1h 01m 30s)");
    }

    @Test
    void fullTooltipInConfiguredZone() {
        Instant start = Instant.parse("2017-04-27T23:30:00Z");
        Instant end = Instant.parse("2017-04-28T06:00:00Z");

        assertThat(full(start, end, STOCKHOLM))
                .isEqualTo("2017-04-28 01:30:00 \u2192 2017-04-28 08:00:00 (\u0394 6h 30m 00s)");
    }

    @Test
    void everythingSentinelsAreExposed() {
        assertThat(RangeText.NOW).isEqualTo("now");
        assertThat(RangeText.START).isEqualTo("1971")
                .isEqualTo(String.valueOf(SearchBoundaries.MIN_MINIMUM_BOUNDARY.atZone(ZoneOffset.UTC).getYear()));
        assertThat(RangeText.EVERYTHING).isEqualTo("everything");
    }

    private static String range(Instant start, Instant end, Instant anchor, Instant now, ZoneId zone) {
        return RangeText.range(start, end, anchor, now, zone, TIME, MINUTE, SECOND);
    }

    private static String endpoint(Instant t, Instant anchor, Instant now, ZoneId zone) {
        return RangeText.endpoint(t, anchor, now, zone, TIME, MINUTE, SECOND);
    }

    private static String step(Instant edge, Instant suggested, Instant anchor, Instant now, ZoneId zone) {
        return RangeText.step(edge, suggested, anchor, now, zone, TIME, MINUTE, SECOND);
    }

    private static String full(Instant start, Instant end, ZoneId zone) {
        return RangeText.full(start, end, zone, SECOND);
    }
}
