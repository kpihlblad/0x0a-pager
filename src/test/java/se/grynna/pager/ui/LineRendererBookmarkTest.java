package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.ui.sprattel.LoadingAnimationTimer;
import se.grynna.pager.utils.OKLab;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static se.grynna.pager.utils.DarculaColors.BACKGROUND;
import static se.grynna.pager.utils.DarculaColors.BACKGROUND_BOOKMARKS;
import static se.grynna.pager.utils.DarculaColors.BOOKMARK_CURRENT_LINE;
import static se.grynna.pager.utils.DarculaColors.BOOKMARK_ROW;
import static se.grynna.pager.utils.DarculaColors.CURRENT_LINE;
import static se.grynna.pager.utils.DarculaColors.FOREGROUND;
import static se.grynna.pager.utils.DarculaColors.MATCHES_ACCENT;
import static se.grynna.pager.utils.DarculaColors.MIDNIGHT_BLUE;
import static se.grynna.pager.utils.DarculaColors.ORANGE;
import static se.grynna.pager.utils.DarculaColors.RED;

/**
 * A bookmarked event paints its rows with a teal row background across the full width (log and Matches views;
 * the Bookmarks view tint says it already). Selection wins over it, the cursor line blends with it, and the text
 * columns never move.
 */
class LineRendererBookmarkTest {

    private static final int WIDTH = 1600;
    private static final int HEIGHT = 40;
    /** The preamble and message end well before this column, so pixels from here on are row background only. */
    private static final int PAST_TEXT_X = 1200;

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @Test
    void bookmarkedRowIsFilledWithTheTealRowAcrossTheWidth() {
        for (LineRenderer.ViewKind kind : new LineRenderer.ViewKind[]{LineRenderer.ViewKind.LOG,
                LineRenderer.ViewKind.MATCHES}) {
            BufferedImage image = render(kind, true, false, -1, -1);
            for (int x = PAST_TEXT_X; x < WIDTH; x++) {
                for (int y = 0; y < charHeight(); y++) {
                    assertThat(rgb(image, x, y)).as("%s row at column %d, row %d", kind, x, y)
                            .isEqualTo(rgb(BOOKMARK_ROW));
                }
            }
        }
    }

    @Test
    void matchesStripeStaysAboveTheBookmarkedRow() {
        BufferedImage image = render(LineRenderer.ViewKind.MATCHES, true, false, -1, -1);

        for (int y = 0; y < charHeight(); y++) {
            for (int x = 0; x < LineRenderer.MATCHES_STRIPE_WIDTH; x++) {
                assertThat(rgb(image, x, y)).as("stripe at column %d, row %d", x, y).isEqualTo(rgb(MATCHES_ACCENT));
            }
        }
    }

    @Test
    void bookmarksViewKeepsItsTintAndHasNoPerRowBackground() {
        BufferedImage marked = render(LineRenderer.ViewKind.BOOKMARKS, true, false, -1, -1);
        BufferedImage plain = render(LineRenderer.ViewKind.BOOKMARKS, false, false, -1, -1);

        assertThat(rgb(marked, PAST_TEXT_X, 0)).isEqualTo(rgb(BACKGROUND_BOOKMARKS));
        assertThat(countOf(marked, BOOKMARK_ROW)).isZero();
        assertThat(sameImage(marked, plain)).isTrue();
    }

    @Test
    void unbookmarkedRowsPaintNoBookmarkRowAtAll() {
        for (LineRenderer.ViewKind kind : LineRenderer.ViewKind.values()) {
            assertThat(countOf(render(kind, false, false, -1, -1), BOOKMARK_ROW)).as("%s", kind).isZero();
            assertThat(countOf(render(kind, false, true, -1, -1), BOOKMARK_ROW)).as("%s cursor", kind).isZero();
        }
    }

    @Test
    void theRowFillDoesNotMoveTheTextColumns() {
        for (LineRenderer.ViewKind kind : LineRenderer.ViewKind.values()) {
            assertThat(textColumns(render(kind, true, false, -1, -1)))
                    .as("%s", kind).isEqualTo(textColumns(render(kind, false, false, -1, -1)));
            assertThat(textColumns(render(kind, true, true, -1, -1)))
                    .as("%s cursor", kind).isEqualTo(textColumns(render(kind, false, true, -1, -1)));
        }
        assertThat(textColumns(render(LineRenderer.ViewKind.LOG, false, false, -1, -1))).isNotEmpty();
    }

    @Test
    void selectionPaintsOverTheBookmarkRow() {
        BufferedImage image = render(LineRenderer.ViewKind.LOG, true, false, 0, 5);
        int textStartX = LineRenderer.iconSpaceWidth(charHeight());
        int selectionEnd = textStartX + charWidth() * 5;

        assertThat(countOf(image, MIDNIGHT_BLUE)).isPositive();
        assertThat(countOfIn(image, BOOKMARK_ROW, textStartX, selectionEnd)).isZero();
        assertThat(rgb(image, PAST_TEXT_X, 0)).isEqualTo(rgb(BOOKMARK_ROW));
    }

    @Test
    void cursorLineOnABookmarkedRowIsTheTealBlend() {
        BufferedImage image = render(LineRenderer.ViewKind.LOG, true, true, -1, -1);

        assertThat(rgb(image, PAST_TEXT_X, 0)).isEqualTo(rgb(BOOKMARK_CURRENT_LINE));
        assertThat(BOOKMARK_CURRENT_LINE).isNotEqualTo(BOOKMARK_ROW).isNotEqualTo(CURRENT_LINE);
        assertThat(BOOKMARK_CURRENT_LINE.getGreen() - BOOKMARK_CURRENT_LINE.getRed()).isGreaterThanOrEqualTo(30);
        assertThat(countOf(render(LineRenderer.ViewKind.LOG, false, true, -1, -1), CURRENT_LINE)).isPositive();
    }

    @Test
    void theTealRowIsVisibleButKeepsTheTextReadable() {
        assertThat(OKLab.deltaE(BACKGROUND, BOOKMARK_ROW)).isGreaterThanOrEqualTo(0.04f);
        assertThat(contrast(FOREGROUND, BOOKMARK_ROW)).isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(ORANGE, BOOKMARK_ROW)).isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(FOREGROUND, BOOKMARK_CURRENT_LINE)).isGreaterThanOrEqualTo(4.5);
        // RED already sits below 4.5 on the plain background (3.8:1, not changed here); the row must not drop it much.
        assertThat(contrast(RED, BOOKMARK_ROW)).isGreaterThanOrEqualTo(2.5);
    }

    private static BufferedImage render(LineRenderer.ViewKind kind, boolean bookmarked, boolean cursor,
            int selectionStart, int selectionEnd) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, WIDTH, HEIGHT);
            Font font = Fontalizer.getFont();
            g2d.setFont(font);
            FontMetrics metrics = g2d.getFontMetrics(font);
            var renderer = new LineRenderer(new LoadingAnimationTimer(new JPanel(), ""), metrics,
                    new Rectangle(0, 0, WIDTH, HEIGHT));
            renderer.setCharSize(characterSize(metrics));
            renderer.setViewKind(kind);
            renderer.paintBackground(g2d, WIDTH, HEIGHT);

            var event = new LoadedLogLine(MsgType.JSON, "segment1", 0, "cd0", "bkt", Instant.parse(
                    "2017-03-01T10:00:00Z"), "raw", Instant.parse("2017-03-01T10:00:00Z"),
                    Instant.parse("2017-03-01T10:00:00Z"), "hello world", false, Level.INFO);
            var line = new DecoratedLogLine(event);
            line.setBookmarked(bookmarked);
            line.highlighted = cursor;
            line.selectionStart = selectionStart;
            line.selectionEnd = selectionEnd;
            renderer.render(g2d, line, null, null, 0, 0);
        } finally {
            g2d.dispose();
        }
        return image;
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static int countOf(BufferedImage image, Color color) {
        return countOfIn(image, color, 0, image.getWidth());
    }

    private static int countOfIn(BufferedImage image, Color color, int fromX, int toX) {
        int count = 0;
        for (int x = fromX; x < toX; x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (rgb(image, x, y) == rgb(color)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        for (int x = 0; x < a.getWidth(); x++) {
            for (int y = 0; y < a.getHeight(); y++) {
                if (rgb(a, x, y) != rgb(b, x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Columns holding fully covered message text, i.e. pixels in exactly the foreground colour. */
    private static SortedSet<Integer> textColumns(BufferedImage image) {
        SortedSet<Integer> columns = new TreeSet<>();
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (rgb(image, x, y) == rgb(FOREGROUND)) {
                    columns.add(x);
                }
            }
        }
        return columns;
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG relative luminance. */
    private static double luminance(Color color) {
        double[] channels = {color.getRed() / 255.0, color.getGreen() / 255.0, color.getBlue() / 255.0};
        double sum = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < channels.length; i++) {
            double c = channels[i];
            double linear = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
            sum += weights[i] * linear;
        }
        return sum;
    }

    private static int rgb(Color color) {
        return color.getRGB() & 0xFFFFFF;
    }

    private static LogViewer.CharacterSize characterSize(FontMetrics metrics) {
        int ascent = metrics.getAscent();
        int descent = metrics.getDescent();
        return new LogViewer.CharacterSize(metrics.charWidth('W'), ascent + descent, ascent, descent);
    }

    private static int charWidth() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        try {
            return g2d.getFontMetrics(Fontalizer.getFont()).charWidth('W');
        } finally {
            g2d.dispose();
        }
    }

    private static int charHeight() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        try {
            FontMetrics metrics = g2d.getFontMetrics(Fontalizer.getFont());
            return metrics.getAscent() + metrics.getDescent();
        } finally {
            g2d.dispose();
        }
    }
}
