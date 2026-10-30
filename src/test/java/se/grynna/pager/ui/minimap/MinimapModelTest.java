package se.grynna.pager.ui.minimap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.minimap.MinimapModel.Band;
import se.grynna.pager.ui.minimap.MinimapModel.BandUnit;
import se.grynna.pager.ui.minimap.MinimapModel.Span;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MinimapModelTest {

    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    private static final long DAY = 86_400L;

    @Test
    @DisplayName("matches are bucketed into height/sliceHeight slices; out-of-span positions are ignored")
    void bucketing() {
        var model = MinimapModel.build(new Span(0, 100), 10, 2, new long[]{5, 6, 25, 90, 99, 100, -1, 99}, null,
                null, OptionalLong.empty(), null);

        assertThat(model.sliceCount()).isEqualTo(5);
        assertThat(model.sliceCounts()).containsExactly(2, 1, 0, 0, 3);
    }

    @Test
    @DisplayName("alpha is 0 for empty slices and 0.55 + 0.45 sqrt(count / max) otherwise")
    void alphaScale() {
        var model = MinimapModel.build(new Span(0, 100), 10, 2, new long[]{5, 6, 25, 90, 99, 98}, null, null,
                OptionalLong.empty(),

                null);

        float[] alphas = model.alphas();
        assertThat(alphas[0]).isEqualTo(0.55f + 0.45f * (float) Math.sqrt(2.0 / 3), within(1e-6f))
                .as("count 2 of max 3");
        assertThat(alphas[1]).isEqualTo(0.55f + 0.45f * (float) Math.sqrt(1.0 / 3), within(1e-6f));
        assertThat(alphas[2]).isZero();
        assertThat(alphas[3]).isZero();
        assertThat(alphas[4]).isEqualTo(1.0f).as("densest slice");
        assertThat(alphas[1]).isGreaterThan(0.55f + 0.45f / 3).as("sqrt: sparse slices are brighter than linear");
    }

    @Test
    @DisplayName("the densest slice gets full opacity; a lone match elsewhere is scaled, not clamped to the minimum")
    void extremeAlphas() {
        var model = MinimapModel.build(new Span(0, 100), 10, 2, new long[]{1, 1, 1, 1, 60}, null, null,
                OptionalLong.empty(), null);

        assertThat(model.alphas()[0]).isEqualTo(1.0f);
        assertThat(model.alphas()[3]).isEqualTo(0.55f + 0.45f * (float) Math.sqrt(1.0 / 4), within(1e-6f))
                .as("one match against four");
    }

    @Test
    @DisplayName("a single match among a dense slice stays visible at the minimum opacity or above")
    void minimumAlpha() {
        long[] matches = new long[11];
        matches[0] = 1;
        Arrays.fill(matches, 1, 11, 90);
        var model = MinimapModel.build(new Span(0, 100), 10, 2, matches, null, null, OptionalLong.empty(), null);

        assertThat(model.alphas()[0]).isEqualTo(0.55f + 0.45f * (float) Math.sqrt(1.0 / 10), within(1e-6f));
        assertThat(model.alphas()[4]).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("empty input gives empty slices, no ticks, and the full visible range")
    void emptyInput() {
        var model = MinimapModel.build(new Span(0, 100), 30, 3, null, null, null, OptionalLong.empty(), null);

        assertThat(model.sliceCount()).isEqualTo(10);
        assertThat(model.sliceCounts()).containsOnly(0);
        assertThat(model.alphas()).containsOnly(0f);
        assertThat(model.shadedBands()).isEmpty();
        assertThat(model.bookmarkYs()).isEmpty();
        assertThat(model.visibleFromY()).isZero();
        assertThat(model.visibleToY()).isEqualTo(30);
    }

    @Test
    @DisplayName("a strip shorter than one slice has a single slice holding all matches")
    void singleSlice() {
        var model = MinimapModel.build(new Span(0, 10), 2, 3, new long[]{0, 9, 4}, null, null, OptionalLong.empty(),
                null);

        assertThat(model.sliceCount()).isEqualTo(1);
        assertThat(model.sliceCounts()).containsExactly(3);
        assertThat(model.alphas()).containsExactly(1.0f);
    }

    @Test
    @DisplayName("no matches inside the span: every slice is empty")
    void outOfSpanOnly() {
        var model = MinimapModel.build(new Span(100, 200), 10, 2, new long[]{0, 99, 200, 500}, null, null,
                OptionalLong.empty(), null);

        assertThat(model.sliceCounts()).containsOnly(0);
        assertThat(model.alphas()).containsOnly(0f);
    }

    @Test
    @DisplayName("visible range maps to pixel rows and is at least one pixel tall")
    void visibleRange() {
        var model = MinimapModel.build(new Span(0, 1000), 100, 2, null, null, null, OptionalLong.empty(),
                new Span(500, 501));

        assertThat(model.visibleFromY()).isEqualTo(50);
        assertThat(model.visibleToY()).isEqualTo(51);
    }

    @Test
    @DisplayName("visible range covering the span, and a range past the span end, stay inside the strip")
    void visibleRangeClamped() {
        var full = MinimapModel.build(new Span(0, 1000), 100, 2, null, null, null, OptionalLong.empty(),
                new Span(0, 1000));
        assertThat(full.visibleFromY()).isZero();
        assertThat(full.visibleToY()).isEqualTo(100);

        var past = MinimapModel.build(new Span(0, 1000), 100, 2, null, null, null, OptionalLong.empty(),
                new Span(990, 5000));
        assertThat(past.visibleFromY()).isEqualTo(99);
        assertThat(past.visibleToY()).isEqualTo(100);
    }

    @Test
    @DisplayName("bookmark ticks map to pixel rows, ignore positions outside the span, and are distinct")
    void bookmarkTicks() {
        var model = MinimapModel.build(new Span(0, 100), 10, 2, null, new long[]{0, 50, 51, 99, 100, -5}, null,
                OptionalLong.empty(),

                null);

        assertThat(model.bookmarkYs()).containsExactly(0, 5, 9);
    }

    @Test
    @DisplayName("day bands when the days are at least 8 px tall; odd days shaded, even days clear")
    void dayBands() {
        var model = build(days(MAR_31, 10), 100, midnights(MAR_31, MAR_31 + 10), MAR_31);

        assertThat(model.bandUnit()).isEqualTo(BandUnit.DAY);
        assertThat(model.shadedBands()).containsExactly(new Band(10, 20), new Band(30, 40), new Band(50, 60),
                new Band(70, 80), new Band(90, 100));
    }

    @Test
    @DisplayName("same-row day changes are all counted, so later bands keep their day's shade")
    void sameRowDayChangesKeepTheirIndex() {
        // Changes at 500 and 501 share pixel row 50 (a zero-height band); 700 is row 70.
        var model = MinimapModel.build(new Span(0, 1000), 100, 2, null, null, new long[]{500, 501, 700},
                OptionalLong.of(MAR_31), null);

        assertThat(model.bandUnit()).isEqualTo(BandUnit.DAY);
        assertThat(model.shadedBands()).containsExactly(new Band(70, 100));
    }

    @Test
    @DisplayName("week bands when the days are too thin: boundaries on Mondays, shade by ISO week")
    void weekBands() {
        // 31 Mar .. 30 Apr 2017: Mondays 3, 10, 17, 24 Apr; weeks from 27 Mar are indexed 2465, 2466, ...
        var model = build(days(MAR_31, 30), 100, midnights(MAR_31, MAR_31 + 30), MAR_31);

        assertThat(model.bandUnit()).isEqualTo(BandUnit.WEEK);
        assertThat(model.shadedBands()).containsExactly(new Band(0, 10), new Band(33, 56), new Band(80, 100));
    }

    @Test
    @DisplayName("month bands when the weeks are too thin; shaded on odd months")
    void monthBands() {
        // 31 Mar .. 28 Jul 2017: boundaries 1 Apr, 1 May, 1 Jun, 1 Jul; April and June shaded.
        var model = build(days(MAR_31, 120), 100, midnights(MAR_31, MAR_31 + 120), MAR_31);

        assertThat(model.bandUnit()).isEqualTo(BandUnit.MONTH);
        assertThat(model.shadedBands()).containsExactly(new Band(0, 25), new Band(51, 76));
    }

    @Test
    @DisplayName("year bands when the months are too thin; shaded on odd years")
    void yearBands() {
        // 1 Jun 2016 .. 1 Jun 2019: boundaries 1 Jan 2017, 2018, 2019; 2017 and 2019 shaded.
        long start = LocalDate.of(2016, 6, 1).toEpochDay();
        var model = build(days(start, 1095), 100, midnights(start, start + 1095), start);

        assertThat(model.bandUnit()).isEqualTo(BandUnit.YEAR);
        assertThat(model.shadedBands()).containsExactly(new Band(19, 52), new Band(86, 100));
    }

    @Test
    @DisplayName("no bands when even years are too dense for the strip")
    void noBandsWhenYearsAreTooDense() {
        // Ten years need ten bands; 40 px fits five.
        long start = LocalDate.of(2010, 1, 1).toEpochDay();
        var model = build(days(start, 3652), 40, midnights(start, start + 3652), start);

        assertThat(model.bandUnit()).isNull();
        assertThat(model.shadedBands()).isEmpty();
    }

    @Test
    @DisplayName("a finer unit that is too thin with no boundary of the next unit inside gives no bands")
    void noBoundaryOfTheChosenUnit() {
        // 4 Apr .. 10 Apr 2017: five day changes are too many for 20 px, and the only Monday (10 Apr) is the end.
        long start = MAR_31 + 4;
        var model = build(days(start, 6), 20, midnights(start, start + 6), start);

        assertThat(model.bandUnit()).isNull();
        assertThat(model.shadedBands()).isEmpty();
    }

    @Test
    @DisplayName("no boundary inside a single day gives no bands, odd or even")
    void singleDayHasNoBands() {
        for (long day : new long[]{MAR_31, MAR_31 - 1}) {
            var model = build(days(day, 1), 100, midnights(day, day + 1), day);

            assertThat(model.bandUnit()).as("day %d", day).isNull();
            assertThat(model.shadedBands()).as("day %d", day).isEmpty();
        }
    }

    @Test
    @DisplayName("ISO weeks start on Monday, and the week index is constant within a week")
    void weekIndexFollowsMondays() {
        long monday = LocalDate.of(1970, 1, 5).toEpochDay();
        assertThat(BandUnit.WEEK.startsUnit(monday)).isTrue();
        assertThat(BandUnit.WEEK.startsUnit(monday - 1)).isFalse();
        assertThat(BandUnit.WEEK.startsUnit(monday + 1)).isFalse();

        assertThat(BandUnit.WEEK.indexOf(monday + 6)).as("Sunday, same week").isEqualTo(BandUnit.WEEK.indexOf(monday));
        assertThat(BandUnit.WEEK.indexOf(monday + 7)).as("next Monday, next week")
                .isEqualTo(BandUnit.WEEK.indexOf(monday) + 1);
    }

    @Test
    @DisplayName("a band keeps its shade when the span grows earlier, for weeks and months")
    void shadeIsStableWhenGrowingEarlier() {
        // Weeks: the span from 31 Mar, then from the Monday 27 Mar before it.
        var weeks = build(days(MAR_31, 30), 100, midnights(MAR_31, MAR_31 + 30), MAR_31);
        var weeksEarlier = build(days(MAR_31 - 4, 34), 100, midnights(MAR_31 - 4, MAR_31 + 30), MAR_31 - 4);
        assertThat(shadedAt(weeks, MAR_31 + 5)).as("week of 3 Apr").isFalse();
        assertThat(shadedAt(weeksEarlier, MAR_31 + 5)).as("week of 3 Apr, grown").isFalse();
        assertThat(shadedAt(weeks, MAR_31 + 12)).as("week of 10 Apr").isTrue();
        assertThat(shadedAt(weeksEarlier, MAR_31 + 12)).as("week of 10 Apr, grown").isTrue();

        // Months: the span from 1 Apr, then from 1 Mar before it.
        long apr1 = LocalDate.of(2017, 4, 1).toEpochDay();
        var months = build(days(apr1, 91), 100, midnights(apr1, apr1 + 91), apr1);
        var monthsEarlier = build(days(apr1 - 31, 122), 100, midnights(apr1 - 31, apr1 + 91), apr1 - 31);
        assertThat(shadedAt(months, apr1 + 14)).as("April").isTrue();
        assertThat(shadedAt(monthsEarlier, apr1 + 14)).as("April, grown").isTrue();
        assertThat(shadedAt(months, apr1 + 44)).as("May").isFalse();
        assertThat(shadedAt(monthsEarlier, apr1 + 44)).as("May, grown").isFalse();
    }

    @Test
    @DisplayName("span over instants uses epoch nanoseconds and matches map by time")
    void timeAxis() {
        Instant from = Instant.parse("2026-10-25T00:00:00Z");
        Instant to = Instant.parse("2026-10-26T00:00:00Z");
        Span span = Span.ofInstants(from, to);
        long noon = MinimapModel.nanos(Instant.parse("2026-10-25T12:00:00.000000001Z"));

        var model = MinimapModel.build(span, 10, 2, new long[]{noon}, null, null, OptionalLong.empty(), null);

        assertThat(model.sliceCounts()).containsExactly(0, 0, 1, 0, 0);
    }

    @Test
    @DisplayName("invalid span and heights are rejected")
    void invalidArguments() {
        assertThatThrownBy(() -> new Span(5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> MinimapModel.build(new Span(0, 10), 0, 2, null, null, null, OptionalLong.empty(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("date changes are local midnights strictly inside the range, across midnight in Stockholm")
    void dateChangesAcrossMidnight() {
        Instant from = Instant.parse("2026-01-10T12:00:00Z");
        Instant to = Instant.parse("2026-01-12T12:00:00Z");

        List<Instant> changes = MinimapModel.dateChanges(from, to, STOCKHOLM);

        assertThat(changes).containsExactly(Instant.parse("2026-01-10T23:00:00Z"),
                Instant.parse("2026-01-11T23:00:00Z"));
    }

    @Test
    @DisplayName("date changes on the DST end day (25 h day) and start day (23 h day) in Stockholm")
    void dateChangesDst() {
        // 2026-10-25 ends DST: local midnight 25 Oct is 22:00Z (CEST), 26 Oct is 23:00Z (CET).
        assertThat(MinimapModel.dateChanges(Instant.parse("2026-10-24T12:00:00Z"),
                Instant.parse("2026-10-26T12:00:00Z"), STOCKHOLM)).containsExactly(
                        Instant.parse("2026-10-24T22:00:00Z"), Instant.parse("2026-10-25T23:00:00Z"));

        // 2026-03-29 starts DST: local midnight 29 Mar is 23:00Z on 28 Mar (CET), 30 Mar is 22:00Z on 29 Mar (CEST).
        assertThat(MinimapModel.dateChanges(Instant.parse("2026-03-28T12:00:00Z"),
                Instant.parse("2026-03-30T12:00:00Z"), STOCKHOLM)).containsExactly(
                        Instant.parse("2026-03-28T23:00:00Z"), Instant.parse("2026-03-29T22:00:00Z"));
    }

    @Test
    @DisplayName("no date change within one local day, and bounds are exclusive")
    void dateChangesNone() {
        assertThat(MinimapModel.dateChanges(Instant.parse("2026-10-25T05:00:00Z"),
                Instant.parse("2026-10-25T20:00:00Z"), STOCKHOLM)).isEmpty();
        assertThat(MinimapModel.dateChanges(Instant.parse("2026-10-24T22:00:00Z"),
                Instant.parse("2026-10-25T05:00:00Z"), STOCKHOLM)).isEmpty();
        assertThat(MinimapModel.dateChanges(Instant.parse("2026-10-25T05:00:00Z"),
                Instant.parse("2026-10-25T05:00:00Z"), STOCKHOLM)).isEmpty();
        assertThat(MinimapModel.dateChanges(Instant.parse("2026-10-24T22:00:00Z"),
                Instant.parse("2026-10-25T22:00:00Z"), STOCKHOLM)).isEmpty();
    }

    private static final long MAR_31 = LocalDate.of(2017, 3, 31).toEpochDay();

    /** Axis in seconds from the epoch: a span of whole days from the day {@code firstDay}. */
    private static Span days(long firstDay, int count) {
        return new Span(firstDay * DAY, (firstDay + count) * DAY);
    }

    /** Midnights (axis positions) strictly after {@code firstDay} and before {@code endDay}. */
    private static long[] midnights(long firstDay, long endDay) {
        return LongStream.range(firstDay + 1, endDay).map(day -> day * DAY).toArray();
    }

    private static MinimapModel build(Span span, int heightPx, long[] dateChanges, long firstDay) {
        return MinimapModel.build(span, heightPx, 2, null, null, dateChanges, OptionalLong.of(firstDay), null);
    }

    /** Whether the noon of {@code day} lies in a shaded band. */
    private static boolean shadedAt(MinimapModel model, long day) {
        Span span = model.span();
        int y = (int) ((day * DAY + DAY / 2 - span.start()) * (double) model.heightPx() / span.length());
        return model.shadedBands().stream().anyMatch(band -> y >= band.top() && y < band.bottom());
    }
}
