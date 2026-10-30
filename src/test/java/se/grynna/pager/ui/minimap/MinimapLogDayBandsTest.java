package se.grynna.pager.ui.minimap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Log view's day bands: {@link MinimapController#showRows} finds the local midnights of the loaded segment
 * as rows (a row at or after each midnight), on the background, and paints them. Painted, so the pixels say
 * where a day starts. The strip is 6 x 300 px; the 300 rows are one event each, 864 s apart (100 rows a day),
 * so row r is pixel y = r, 31 Mar is rows 0-99 (even, clear), 1 Apr rows 100-199 (odd, shaded), 2 Apr 200-299.
 */
class MinimapLogDayBandsTest {

    private static final int W = 6;
    private static final int H = 300;
    private static final int ROWS = 300;
    private static final Color DAY_BAND = new Color(128, 128, 124, 60);
    private static final Instant T0 = Instant.parse("2017-03-31T00:00:00Z");
    private static final Instant MIDNIGHT_1_APR = T0.plusSeconds(86_400);
    private static final Duration STEP = Duration.ofSeconds(864);
    private static final IntFunction<Instant> EVENLY = row -> T0.plus(STEP.multipliedBy(row));

    private ZoneId zone = ZoneId.of("UTC");
    private Minimap minimap;
    private MinimapController controller;

    @BeforeEach
    void setUp() {
        minimap = new Minimap();
        minimap.setSize(Minimap.GLOW_REACH + W, H);
        Executor direct = Runnable::run;
        controller = new MinimapController(minimap, direct, direct, () -> true, () -> zone, () -> List.of(),
                () -> 0L);
    }

    @Test
    @DisplayName("each local midnight inside the segment starts a new day at its row")
    void turnoversStartTheNewDayAtTheirRows() {
        show(0, ROWS, EVENLY, row -> true);

        BufferedImage image = paint();
        assertThat(alpha(image, 0, 50)).as("31 Mar").isZero();
        assertThat(isBand(image, 0, 150)).as("1 Apr").isTrue();
        assertThat(isBand(image, W - 1, 150)).as("1 Apr, full width").isTrue();
        assertThat(alpha(image, 0, 250)).as("2 Apr").isZero();
    }

    @Test
    @DisplayName("a row exactly at midnight belongs to the new day; the row a nanosecond before it does not")
    void midnightIsTheStartOfTheDay() {
        IntFunction<Instant> around = row -> switch (row) {
            case 99 -> MIDNIGHT_1_APR.minusNanos(1);
            case 100 -> MIDNIGHT_1_APR;
            default -> EVENLY.apply(row);
        };

        show(0, ROWS, around, row -> true);

        BufferedImage image = paint();
        assertThat(alpha(image, 0, 99)).as("23:59:59.999999999 on 31 Mar").isZero();
        assertThat(isBand(image, 0, 100)).as("00:00:00 on 1 Apr").isTrue();
    }

    @Test
    @DisplayName("a segment with no local midnight has no day bands")
    void noTurnoverNoBands() {
        IntFunction<Instant> morning = row -> T0.plusSeconds(10 * 3600L).plusSeconds(120L * row);

        show(0, ROWS, morning, row -> true);

        assertThat(hasAnyBand(paint())).isFalse();
    }

    @Test
    @DisplayName("midnights are local to the configured zone, not UTC")
    void turnoversUseTheZone() {
        zone = ZoneId.of("Europe/Stockholm");

        show(0, ROWS, EVENLY, row -> true);

        // Local midnight 1 Apr is 31 Mar 22:00 UTC (row 92, the first row at or after it); 2 Apr is row 192.
        BufferedImage image = paint();
        assertThat(alpha(image, 0, 91)).as("row 91 is 21:50 UTC, still 31 Mar local").isZero();
        assertThat(isBand(image, 0, 92)).as("row 92 is 22:08 UTC, 1 Apr local").isTrue();
        assertThat(isBand(image, 0, 191)).as("1 Apr local, still").isTrue();
        assertThat(alpha(image, 0, 192)).as("2 Apr local").isZero();
    }

    @Test
    @DisplayName("a midnight before the segment's first row is not a turnover of its rows")
    void midnightBeforeTheSegmentIsIgnored() {

        // Rows 150-299 only: their first row is 1 Apr 12:00, so 1 Apr's midnight (row 100) lies outside the
        // segment. Only 2 Apr's midnight (row 200, y = 100 on the 150-row strip) is a turnover.
        show(150, ROWS, EVENLY, row -> true);

        BufferedImage image = paint();
        assertThat(isBand(image, 0, 50)).as("1 Apr is shaded from the top").isTrue();
        assertThat(alpha(image, 0, 150)).as("2 Apr is clear").isZero();
    }

    @Test
    @DisplayName("a midnight whose row is not loaded (at the segment's unloaded end) draws no band, earlier ones do")
    void unloadedTurnoverIsNotDrawn() {

        // Rows 200-299 (2 Apr) are not loaded: the 2 Apr midnight has no row to sit on, so it is dropped.
        show(0, ROWS, EVENLY, row -> row < 200);

        BufferedImage image = paint();
        assertThat(alpha(image, 0, 99)).as("31 Mar").isZero();
        assertThat(isBand(image, 0, 150)).as("1 Apr").isTrue();
    }

    /** One segment [startLine, endLineExclusive): rows are {@code time} of the row, loaded where {@code loaded}. */
    private void show(int startLine, int endLineExclusive, IntFunction<Instant> time, IntPredicate loaded) {
        controller.showRows(null, startLine, endLineExclusive, time.apply(startLine),
                time.apply(endLineExclusive - 1), row -> loaded.test(row)
                        ? Optional.<LogLine>of(line(row, time))
                        : Optional.<LogLine>empty(),
                this, 1, 0);
    }

    private static LoadedLogLine line(int row, IntFunction<Instant> time) {
        Instant at = time.apply(row);
        return new LoadedLogLine(LoadedLogLine.MsgType.RAW, "seg", row, "cb" + row, "bkt", at, "raw", at, at, "msg",
                false, null);
    }

    /** The strip only, without the glow gutter. */
    private BufferedImage paint() {
        BufferedImage image = new BufferedImage(minimap.getWidth(), H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            minimap.paint(g);
        } finally {
            g.dispose();
        }
        return image.getSubimage(Minimap.GLOW_REACH, 0, W, H);
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    /** The day-band shade (see MinimapPaintTest), allowing for premultiplied rounding. */
    private static boolean isBand(BufferedImage image, int x, int y) {
        Color c = new Color(image.getRGB(x, y), true);
        return Math.abs(c.getAlpha() - DAY_BAND.getAlpha()) <= 1 && Math.abs(c.getRed() - DAY_BAND.getRed()) <= 1
                && Math.abs(c.getGreen() - DAY_BAND.getGreen()) <= 1
                && Math.abs(c.getBlue() - DAY_BAND.getBlue()) <= 1;
    }

    private static boolean hasAnyBand(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alpha(image, x, y) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
