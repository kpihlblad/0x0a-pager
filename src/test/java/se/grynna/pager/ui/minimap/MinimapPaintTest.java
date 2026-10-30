package se.grynna.pager.ui.minimap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.splunk.SearchSession;
import se.grynna.pager.utils.DarculaColors;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The strip painted into a BufferedImage (no window): 6 x 300 px, so 100 slices of 3 px. The log segment has
 * 300 rows, one event each, so row r is pixel y = r. Slices and day bands span the full width.
 */
class MinimapPaintTest {

    private static final int W = 6;
    private static final int H = 300;
    private static final int ROWS = 300;
    /** An even epoch day (2017-03-31), so the day bands below start clear. */
    private static final Instant T0 = Instant.parse("2017-03-31T00:00:00Z");
    private static final Color DAY_BAND = new Color(128, 128, 124, 60);

    private Minimap minimap;
    private MinimapController controller;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicReference<List<LoadedLogLine>> bookmarks = new AtomicReference<>(List.of());
    private final AtomicReference<Long> bookmarksVersion = new AtomicReference<>(0L);
    private final Queue<Runnable> backgroundQueue = new ArrayDeque<>();
    private final Queue<Runnable> edtQueue = new ArrayDeque<>();
    private boolean directBackground = true;
    private boolean directEdt = true;

    @BeforeEach
    void setUp() {
        minimap = new Minimap();
        minimap.setSize(Minimap.GLOW_REACH + W, H);
        Executor background = task -> {
            if (directBackground) {
                task.run();
            } else {
                backgroundQueue.add(task);
            }
        };
        Executor edt = task -> {
            if (directEdt) {
                task.run();
            } else {
                edtQueue.add(task);
            }
        };
        controller = new MinimapController(minimap, background, edt, enabled::get, () -> ZoneId.of("UTC"),
                bookmarks::get, bookmarksVersion::get);
    }

    @Test
    @DisplayName("matches paint amber at the slices of their rows, and only there")
    void matchesPaintAmberAtTheirRows() {
        showRows(snapshot(1, rows(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)));

        BufferedImage image = paint();
        assertThat(isAmber(image, W - 1, 1)).isTrue();
        assertThat(isAmber(image, W - 1, 10)).isTrue();
        assertThat(alpha(image, W - 1, 150)).isZero();
        assertThat(alpha(image, W - 1, 299)).isZero();
        // Slices span the full strip width, like the visible-range outline.
        assertThat(isAmber(image, 0, 1)).isTrue();
        assertThat(isAmber(image, W - 1, 1)).isTrue();
    }

    @Test
    @DisplayName("a segment numbered around its jump target (rows before it negative) paints its matches")
    void matchesAtNegativeRows() {
        // Rows -150..149, one event each; row r sits at pixel y = r + 150. The log opens at its latest event, so
        // the matches found by a search are at negative rows.
        int first = -150;
        List<SearchHit> hits = new ArrayList<>();
        for (int row : new int[]{-150, -100, -1, 0, 100}) {
            hits.add(SearchHit.from(lineAt(row, first)));
        }

        controller.showRows(snapshot(1, hits), first, first + ROWS, T0, T0.plusSeconds(ROWS),
                row -> row >= first && row < first + ROWS ? Optional.of(lineAt(row, first)) : Optional.empty(), this,
                120, 149);

        BufferedImage image = paint();
        assertThat(isAmber(image, W - 1, 1)).as("row -150").isTrue();
        assertThat(isAmber(image, W - 1, 49)).as("row -100").isTrue();
        assertThat(isAmber(image, W - 1, 149)).as("row -1").isTrue();
        assertThat(isAmber(image, W - 1, 151)).as("row 0").isTrue();
        assertThat(isAmber(image, W - 1, 250)).as("row 100").isTrue();
        assertThat(alpha(image, W - 1, 100)).as("row -50 has no match").isZero();
    }

    @Test
    @DisplayName("a denser slice is more opaque than a sparser one")
    void denserSlicesAreMoreOpaque() {
        // Rows 0-2 (slice 0): three matches; row 150 (slice 50): one.
        showRows(snapshot(1, rows(0, 1, 2, 150)));

        BufferedImage image = paint();
        assertThat(alpha(image, W - 1, 1)).isGreaterThan(alpha(image, W - 1, 151));
        assertThat(alpha(image, W - 1, 151)).isGreaterThan(0);
    }

    @Test
    @DisplayName("nothing is painted before the first request, after hide(), or in the Matches view without a search")
    void nothingUntilShown() {
        assertThat(isEmpty(paint())).isTrue();

        showRows(snapshot(1, rows(0, 1, 2)));
        assertThat(isEmpty(paint())).isFalse();

        controller.hide();
        assertThat(isEmpty(paint())).isTrue();

        controller.showTime(null, null, null);
        assertThat(isEmpty(paint())).isTrue();
    }

    @Test
    @DisplayName("without a search the strip still shows the outline and bookmark ticks, but no amber")
    void stripWithoutASearch() {
        bookmarks.set(List.of(line(100)));
        bookmarksVersion.set(1L);

        controller.showRows(null, 0, ROWS, T0, T0.plusSeconds(ROWS), this::peek, this, 0, 29);

        BufferedImage image = paint();
        assertThat(isOutline(image, 0, 0)).isTrue();
        assertThat(isOutline(image, 0, 29)).isTrue();
        assertThat(new Color(image.getRGB(3, 100), true)).isEqualTo(DarculaColors.BOOKMARK);
        assertThat(hasAmber(image)).isFalse();
    }

    @Test
    @DisplayName("a search that starts or ends changes only the amber, the strip stays")
    void searchComesAndGoes() {
        controller.showRows(null, 0, ROWS, T0, T0.plusSeconds(ROWS), this::peek, this, 250, 279);
        assertThat(hasAmber(paint())).isFalse();

        showRows(snapshot(1, rows(5, 200)));
        BufferedImage withSearch = paint();
        assertThat(isAmber(withSearch, W - 1, 4)).isTrue();
        assertThat(isAmber(withSearch, W - 1, 199)).isTrue();
        assertThat(isOutline(withSearch, 0, 250)).isTrue();

        controller.showRows(null, 0, ROWS, T0, T0.plusSeconds(ROWS), this::peek, this, 250, 279);
        BufferedImage ended = paint();
        assertThat(hasAmber(ended)).isFalse();
        assertThat(isOutline(ended, 0, 250)).isTrue();
    }

    @Test
    @DisplayName("the Matches view uses the time axis of the covered window")
    void matchesViewUsesTheTimeAxis() {
        // Window one hour; the first quarter of the hour holds matches and the last minute one more.
        Instant end = T0.plusSeconds(3600);
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            hits.add(hitAt(T0.plusSeconds(60L * i), "c" + i));
        }
        hits.add(hitAt(end.minusSeconds(30), "last"));
        SearchSession.Snapshot snapshot = new SearchSession.Snapshot(1, hits, Collections.nCopies(hits.size(), null),
                SearchSession.State.COMPLETED, T0, end, false, false, false);

        controller.showTime(snapshot, T0.plusSeconds(1800), T0.plusSeconds(2000));

        BufferedImage image = paint();
        assertThat(isAmber(image, W - 1, 2)).isTrue();
        assertThat(alpha(image, W - 1, 100)).isZero();
        assertThat(isAmber(image, W - 1, 298)).isTrue();
    }

    @Test
    @DisplayName("a truncated edge gets a dim dashed cap")
    void truncatedEdgeGetsADimCap() {
        List<SearchHit> hits = List.of(hitAt(T0.plusSeconds(1800), "mid"));
        SearchSession.Snapshot snapshot = new SearchSession.Snapshot(1, hits, Collections.nCopies(1, null),
                SearchSession.State.RUNNING, T0, T0.plusSeconds(3600), true, false, false);

        controller.showTime(snapshot, null, null);

        BufferedImage image = paint();
        assertThat(new Color(image.getRGB(0, 0), true)).isEqualTo(DarculaColors.MATCHES_ACCENT_DIM);
        assertThat(alpha(image, 0, H - 1)).isZero();
    }

    @Test
    @DisplayName("local days are solid bands across the full width, odd days shaded, even days clear")
    void dayBandsAlternate() {
        // Three local days in UTC: 31 Mar (even, clear), 1 Apr (odd, shaded), 2 Apr (even, clear).
        controller.showTime(timeSnapshot(T0, T0.plusSeconds(72 * 3600)), null, null);

        BufferedImage image = paint();
        // Midnights at y = 100 and 200 (the span is one nanosecond longer than 72 h).
        assertThat(image.getRGB(0, 50) >>> 24).as("31 Mar").isZero();
        assertThat(isBand(image, 0, 150)).as("1 Apr").isTrue();
        assertThat(isBand(image, W - 1, 150)).as("1 Apr, full width").isTrue();
        assertThat(image.getRGB(0, 250) >>> 24).as("2 Apr").isZero();
    }

    @Test
    @DisplayName("a shaded day stays shaded when the span grows at its start: the shade follows the date")
    void dayParityIsStableWhenGrowingEarlier() {
        // Starts on 1 Apr (odd): the top band is shaded.
        controller.showTime(timeSnapshot(T0.plusSeconds(24 * 3600), T0.plusSeconds(72 * 3600)), null, null);
        BufferedImage later = paint();
        assertThat(isBand(later, 0, 50)).as("1 Apr at the top").isTrue();
        assertThat(later.getRGB(0, 250) >>> 24).as("2 Apr").isZero();

        // The span grows to 31 Mar (even): 1 Apr is still shaded, now second from the top.
        controller.showTime(timeSnapshot(T0, T0.plusSeconds(72 * 3600)), null, null);
        BufferedImage earlier = paint();
        assertThat(earlier.getRGB(0, 50) >>> 24).as("31 Mar").isZero();
        assertThat(isBand(earlier, 0, 150)).as("1 Apr keeps its shade").isTrue();
        assertThat(earlier.getRGB(0, 250) >>> 24).as("2 Apr").isZero();
    }

    @Test
    @DisplayName("a match over a shaded day is still amber")
    void matchOverBandStaysAmber() {
        // One match at noon on 1 Apr (an odd, shaded day) against 20 on 2 Apr: its slice is the faint 29 % one.
        List<SearchHit> hits = new ArrayList<>();
        hits.add(hitAt(T0.plusSeconds(36 * 3600), "noon"));
        for (int i = 0; i < 20; i++) {
            hits.add(hitAt(T0.plusSeconds(60 * 3600), "d" + i));
        }
        SearchSession.Snapshot snapshot = new SearchSession.Snapshot(1, hits, Collections.nCopies(hits.size(), null),
                SearchSession.State.COMPLETED, T0, T0.plusSeconds(72 * 3600), false, false, false);

        controller.showTime(snapshot, null, null);

        // Slice 49 (y 147-149) holds the noon match.
        Color over = new Color(paint().getRGB(0, 148), true);
        assertThat(over.getRed()).as("red over the band").isGreaterThan(over.getBlue() + 60);
        assertThat(over.getGreen()).isGreaterThan(over.getBlue());
    }

    @Test
    @DisplayName("a span over 20 years gets no day bands")
    void noDayBandsOnAVeryLongSpan() {
        SearchSession.Snapshot snapshot = new SearchSession.Snapshot(1, List.of(), List.of(),
                SearchSession.State.COMPLETED, T0, T0.plusSeconds(21L * 366 * 24 * 3600), false, false, false);
        controller.showTime(snapshot, null, null);

        BufferedImage image = paint();
        for (int y = 0; y < H; y++) {
            assertThat(image.getRGB(0, y) >>> 24).as("row %d", y).isZero();
        }
    }

    @Test
    @DisplayName("a year of days too thin for days gets month bands, shaded on odd months")
    void monthBandsOnAYear() {
        // 31 Mar 2017 .. 31 Mar 2018 in 300 px: 365 days are too thin, 52 Mondays too, so the months are used.
        controller.showTime(timeSnapshot(T0, T0.plusSeconds(365L * 24 * 3600)), null, null);

        BufferedImage image = paint();
        // April is y 0..25 of the 365 days, its middle (about day 15) at y 12; May is clear, y 25..51.
        assertThat(isBand(image, 0, 12)).as("April").isTrue();
        assertThat(image.getRGB(0, 37) >>> 24).as("May").isZero();
    }

    @Test
    @DisplayName("the visible-range outline follows the viewport")
    void visibleRangeOutlineMoves() {
        SearchSession.Snapshot snapshot = snapshot(1, rows(5, 200));
        showRows(snapshot, 0, 29);

        BufferedImage top = paint();
        assertThat(isOutline(top, 0, 0)).isTrue();
        assertThat(isOutline(top, 0, 29)).isTrue();
        assertThat(alpha(top, 0, 150)).isZero();

        showRows(snapshot, 150, 179);

        BufferedImage scrolled = paint();
        assertThat(isOutline(scrolled, 0, 150)).isTrue();
        assertThat(isOutline(scrolled, 0, 179)).isTrue();
        assertThat(alpha(scrolled, 0, 0)).isZero();
        assertThat(alpha(scrolled, 0, 29)).isZero();
    }

    @Test
    @DisplayName("a scroll does not recompute the rows")
    void aScrollOnlyMovesTheRange() {
        directBackground = false;
        SearchSession.Snapshot snapshot = snapshot(1, rows(5));
        showRows(snapshot, 0, 29);
        assertThat(backgroundQueue).hasSize(1);
        backgroundQueue.poll().run();

        showRows(snapshot, 100, 129);
        showRows(snapshot, 200, 229);

        assertThat(backgroundQueue).isEmpty();
    }

    @Test
    @DisplayName("a bookmarked event gets a teal tick")
    void bookmarkTick() {
        bookmarks.set(List.of(line(100)));
        bookmarksVersion.set(1L);

        showRows(snapshot(1, rows(5)));

        BufferedImage image = paint();
        assertThat(new Color(image.getRGB(3, 100), true)).isEqualTo(DarculaColors.BOOKMARK);
        assertThat(new Color(image.getRGB(W - 1, 100), true)).isEqualTo(DarculaColors.BOOKMARK);
        assertThat(alpha(image, 3, 101)).isZero();
    }

    @Test
    @DisplayName("a changed bookmark set is picked up without a new snapshot")
    void bookmarkChangeRecomputes() {
        SearchSession.Snapshot snapshot = snapshot(1, rows(5));
        showRows(snapshot);
        assertThat(alpha(paint(), 3, 100)).isZero();

        bookmarks.set(List.of(line(100)));
        bookmarksVersion.set(1L);
        showRows(snapshot);

        assertThat(new Color(paint().getRGB(3, 100), true)).isEqualTo(DarculaColors.BOOKMARK);
    }

    @Test
    @DisplayName("the glow fades over GLOW_REACH px out of the visible range, into the gutter and above and below")
    void glowFades() {
        showRows(snapshot(1, rows(5)), 250, 279);

        BufferedImage image = paintFull();
        int strip = Minimap.GLOW_REACH;
        assertThat(alpha(image, strip - 1, 260)).as("1 px left").isCloseTo(glowAlpha(1), within(1));
        assertThat(alpha(image, strip + 2, 249)).as("1 px above, over the strip").isCloseTo(glowAlpha(1), within(1));
        assertThat(alpha(image, strip + 2, 280)).as("1 px below, over the strip").isCloseTo(glowAlpha(1), within(1));
        assertThat(alpha(image, strip - 7, 260)).as("7 px left").isCloseTo(glowAlpha(7), within(1));
        assertThat(alpha(image, 0, 260)).as("GLOW_REACH px left").isZero();
        assertThat(alpha(image, strip + 2, 250 - Minimap.GLOW_REACH)).as("GLOW_REACH px above").isZero();
        assertThat(alpha(image, strip - 1, 100)).as("far from the range").isZero();
    }

    @Test
    @DisplayName("inside the visible range the strip is filled lighter than outside")
    void insideTheRangeIsFilled() {
        showRows(snapshot(1, rows(5)), 250, 279);

        BufferedImage image = paint();
        assertThat(alpha(image, 2, 260)).as("inside, empty").isCloseTo(46, within(1));
        assertThat(alpha(image, 2, 100)).as("outside, empty").isZero();
    }

    @Test
    @DisplayName("only the glow paints in the gutter: a bookmark tick stays in the strip")
    void gutterHoldsOnlyTheGlow() {
        bookmarks.set(List.of(line(100)));
        bookmarksVersion.set(1L);
        showRows(snapshot(1, rows(5)), 250, 279);

        BufferedImage image = paintFull();
        assertThat(alpha(image, 3, 100)).isZero();
        assertThat(new Color(image.getRGB(Minimap.GLOW_REACH + 3, 100), true)).isEqualTo(DarculaColors.BOOKMARK);
    }

    @Test
    @DisplayName("the glow image is reused while the range keeps its height and the strip its width")
    void glowIsReused() {
        showRows(snapshot(1, rows(5)), 250, 279);
        paint();
        int renders = minimap.glowRenders();

        showRows(snapshot(1, rows(5)), 200, 229);
        paint();
        assertThat(minimap.glowRenders()).as("same height, moved").isEqualTo(renders);

        showRows(snapshot(1, rows(5)), 200, 239);
        paint();
        assertThat(minimap.glowRenders()).as("taller").isEqualTo(renders + 1);
    }

    @Test
    @DisplayName("mouse events pass through: contains() is false everywhere")
    void containsIsFalse() {
        showRows(snapshot(1, rows(0, 1, 2)));

        assertThat(minimap.contains(W - 1, 1)).isFalse();
        assertThat(minimap.contains(0, 0)).isFalse();
        assertThat(minimap.contains(W / 2, H / 2)).isFalse();
        assertThat(minimap.getComponentAt(W - 1, 1)).isNotSameAs(minimap);
    }

    @Test
    @DisplayName("turning the preference off hides the strip, turning it on brings it back")
    void preferenceToggle() {
        SearchSession.Snapshot snapshot = snapshot(1, rows(0, 1, 2));
        showRows(snapshot);
        assertThat(isEmpty(paint())).isFalse();

        enabled.set(false);
        showRows(snapshot);
        assertThat(isEmpty(paint())).isTrue();

        enabled.set(true);
        showRows(snapshot);
        assertThat(isEmpty(paint())).isFalse();
    }

    @Test
    @DisplayName("the row lookup runs on the background executor, not on the caller")
    void rowLookupIsBackground() {
        directBackground = false;

        showRows(snapshot(1, rows(0, 1, 2)));
        assertThat(isEmpty(paint())).as("nothing until the background task ran").isTrue();
        assertThat(backgroundQueue).hasSize(1);

        backgroundQueue.poll().run();
        assertThat(isEmpty(paint())).isFalse();
    }

    @Test
    @DisplayName("a result computed for an older snapshot is dropped")
    void staleResultIsIgnored() {
        directBackground = false;
        showRows(snapshot(1, rows(0, 1, 2))); // old: top
        Runnable older = backgroundQueue.poll();
        showRows(snapshot(2, rows(200, 201, 202))); // newer: bottom
        Runnable newer = backgroundQueue.poll();

        newer.run();
        older.run();

        BufferedImage image = paint();
        assertThat(isAmber(image, W - 1, 201)).isTrue();
        assertThat(alpha(image, W - 1, 1)).as("the older result must not overwrite the newer").isZero();
    }

    @Test
    @DisplayName("a result that was computed but not yet delivered is dropped when a newer request came in")
    void staleDeliveryIsIgnored() {
        directEdt = false;
        showRows(snapshot(1, rows(0, 1, 2)));
        Runnable olderDelivery = edtQueue.poll();
        showRows(snapshot(2, rows(200, 201, 202)));
        Runnable newerDelivery = edtQueue.poll();

        newerDelivery.run();
        olderDelivery.run();

        BufferedImage image = paint();
        assertThat(isAmber(image, W - 1, 201)).isTrue();
        assertThat(alpha(image, W - 1, 1)).isZero();
    }

    @Test
    @DisplayName("hiding drops the work in flight")
    void hideDropsPendingWork() {
        directBackground = false;
        showRows(snapshot(1, rows(0, 1, 2)));
        Runnable pending = backgroundQueue.poll();

        controller.hide();
        pending.run();

        assertThat(isEmpty(paint())).isTrue();
    }

    // === helpers ===

    /** The viewport far from the rows the tests look at, so its outline does not cover them. */
    private void showRows(SearchSession.Snapshot snapshot) {
        showRows(snapshot, 250, 279);
    }

    private void showRows(SearchSession.Snapshot snapshot, int visibleFirst, int visibleLast) {
        controller.showRows(snapshot, 0, ROWS, T0, T0.plusSeconds(ROWS), this::peek, this, visibleFirst, visibleLast);
    }

    private Optional<LogLine> peek(int row) {
        return row >= 0 && row < ROWS ? Optional.of(line(row)) : Optional.empty();
    }

    private static LoadedLogLine line(int row) {
        Instant time = T0.plusSeconds(row);
        return new LoadedLogLine(LoadedLogLine.MsgType.RAW, "seg", row, "cb" + row, "bkt", time, "raw", time, time,
                "msg", false, null);
    }

    private static LoadedLogLine lineAt(int row, int firstRow) {
        Instant time = T0.plusSeconds(row - firstRow);
        return new LoadedLogLine(LoadedLogLine.MsgType.RAW, "seg", row, "cb" + (row - firstRow), "bkt", time, "raw",
                time, time, "msg", false, null);
    }

    private static SearchHit hitAt(Instant time, String cd) {
        return new SearchHit(time, time, "bkt", cd);
    }

    private static List<SearchHit> rows(int... rows) {
        List<SearchHit> hits = new ArrayList<>();
        for (int row : rows) {
            hits.add(SearchHit.from(line(row)));
        }
        return hits;
    }

    private static SearchSession.Snapshot snapshot(long version, List<SearchHit> hits) {
        return new SearchSession.Snapshot(version, hits, Collections.nCopies(hits.size(), null),
                SearchSession.State.COMPLETED, T0, T0.plusSeconds(ROWS), false, false, false);
    }

    /** The strip only: the gutter of the glow is left out, so the strip's coordinates are 0..W-1. */
    private BufferedImage paint() {
        return paintFull().getSubimage(Minimap.GLOW_REACH, 0, W, H);
    }

    /** The whole component, glow gutter included; the strip starts at x = GLOW_REACH. */
    private BufferedImage paintFull() {
        BufferedImage image = new BufferedImage(minimap.getWidth(), H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            minimap.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The glow alpha at Euclidean distance {@code d} px from the range: 0.55 * (1 - d / GLOW_REACH)^2 of 255. */
    private static int glowAlpha(double d) {
        return (int) Math.round(255 * 0.55 * Math.pow(1 - d / Minimap.GLOW_REACH, 2));
    }

    private static SearchSession.Snapshot timeSnapshot(Instant from, Instant to) {
        List<SearchHit> hits = List.of(hitAt(from.plusSeconds(60), "a"));
        return new SearchSession.Snapshot(1, hits, Collections.nCopies(1, null), SearchSession.State.COMPLETED, from,
                to, false, false, false);
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    private static boolean isEmpty(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alpha(image, x, y) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Amber by hue: the visible-range fill and the day bands blend into the amber a little. */
    private static boolean isAmber(BufferedImage image, int x, int y) {
        Color c = new Color(image.getRGB(x, y), true);
        return c.getAlpha() > 0 && c.getRed() > 200 && c.getGreen() > 120 && c.getGreen() < 200
                && c.getBlue() < 120;
    }

    private static boolean hasAmber(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (isAmber(image, x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The day-band shade, allowing for the rounding of premultiplied ARGB storage. */
    private static boolean isBand(BufferedImage image, int x, int y) {
        Color c = new Color(image.getRGB(x, y), true);
        return Math.abs(c.getAlpha() - DAY_BAND.getAlpha()) <= 1 && Math.abs(c.getRed() - DAY_BAND.getRed()) <= 1
                && Math.abs(c.getGreen() - DAY_BAND.getGreen()) <= 1
                && Math.abs(c.getBlue() - DAY_BAND.getBlue()) <= 1;
    }

    /** The opaque GLOW outline of the visible range. */
    private static boolean isOutline(BufferedImage image, int x, int y) {
        Color c = new Color(image.getRGB(x, y), true);
        return c.getAlpha() == 255 && c.getRed() == 200 && c.getGreen() == 228 && c.getBlue() == 255;
    }
}
