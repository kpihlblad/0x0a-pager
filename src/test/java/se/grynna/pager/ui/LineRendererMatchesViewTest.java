package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.ui.sprattel.LoadingAnimationTimer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static se.grynna.pager.utils.DarculaColors.BACKGROUND;
import static se.grynna.pager.utils.DarculaColors.FOREGROUND;
import static se.grynna.pager.utils.DarculaColors.MATCHES_ACCENT;

/**
 * The Matches view only paints: the tint, the edge stripe and the rules must not move
 * the text columns that click mapping and the horizontal scroll are based on.
 */
class LineRendererMatchesViewTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 40;

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @Test
    void matchesViewDoesNotMoveTextColumns() {
        BufferedImage logView = render(false);
        BufferedImage matchesView = render(true);

        SortedSet<Integer> logColumns = textColumns(logView);
        assertThat(logColumns).isNotEmpty();
        assertThat(textColumns(matchesView)).isEqualTo(logColumns);
        assertThat(logColumns.first()).isGreaterThanOrEqualTo(LineRenderer.iconSpaceWidth(charHeight()));
    }

    @Test
    void ruleLabelNeedsPaddingClearOfTheText() {
        assertThat(LineRenderer.labelFits(300, 200)).isTrue();
        assertThat(LineRenderer.labelFits(300, 292)).isTrue(); // exactly two paddings clear
        assertThat(LineRenderer.labelFits(300, 293)).isFalse();
    }

    @Test
    void dateTagIsAnInvertedAccentTagBelowTheRuleLine() {
        BufferedImage image = render(true, new GapRule.Date(LocalDate.of(2017, 3, 1)));
        int accent = MATCHES_ACCENT.getRGB() & 0xFFFFFF;
        int charH = charHeight();

        for (int x = 0; x < WIDTH; x++) {
            assertThat(image.getRGB(x, 0) & 0xFFFFFF).as("rule line at column %d", x).isEqualTo(accent);
            assertThat(image.getRGB(x, 1) & 0xFFFFFF).as("rule line at column %d", x).isEqualTo(accent);
        }

        int labelWidth = fontMetrics().stringWidth("Wed 2017-03-01");
        int tagLeft = WIDTH - labelWidth - 8;
        int tagPixels = 0;
        int accentPixels = 0;
        int textPixels = 0;
        for (int x = tagLeft; x < WIDTH; x++) {
            for (int y = 2; y < charH; y++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                tagPixels++;
                if (rgb == accent) accentPixels++;
                if (rgb == (BACKGROUND.getRGB() & 0xFFFFFF)) textPixels++;
            }
        }
        assertThat(accentPixels).as("tag fill").isGreaterThan(tagPixels / 2);
        assertThat(textPixels).as("dark label text on the tag").isPositive();
    }

    @Test
    void firstRowDateTagIsLeftOutWhenTheRowTextReachesIt() {
        String longText = "w".repeat(WIDTH);
        BufferedImage withRule = render(true, new GapRule.Date(LocalDate.of(2017, 3, 1)), longText);
        BufferedImage withoutRule = render(true, null, longText);
        int charH = charHeight();

        for (int x = 0; x < WIDTH; x++) {
            for (int y = 2; y < charH; y++) {
                assertThat(withRule.getRGB(x, y)).as("pixel (%d, %d) under the rule", x, y)
                        .isEqualTo(withoutRule.getRGB(x, y));
            }
        }
    }

    private static FontMetrics fontMetrics() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
                .getFontMetrics(Fontalizer.getFont());
    }

    private static BufferedImage render(boolean matchesView) {
        return render(matchesView, null, "hello world");
    }

    private static BufferedImage render(boolean matchesView, GapRule rule) {
        return render(matchesView, rule, "hello world");
    }

    private static BufferedImage render(boolean matchesView, GapRule rule, String message) {
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
            renderer.setMatchesView(matchesView);
            renderer.paintBackground(g2d, WIDTH, HEIGHT);

            var event = new LoadedLogLine(MsgType.JSON, "segment1", 0, "cd0", "bkt", Instant.parse(
                    "2017-03-01T10:00:00Z"), "raw", Instant.parse("2017-03-01T10:00:00Z"),
                    Instant.parse("2017-03-01T10:00:00Z"), message, false, Level.INFO);
            var line = new DecoratedLogLine(event);
            line.setGapRule(rule);
            renderer.render(g2d, line, null, null, 0, 0);
        } finally {
            g2d.dispose();
        }
        return image;
    }

    /** Columns holding fully covered message text, i.e. pixels in exactly the foreground colour. */
    private static SortedSet<Integer> textColumns(BufferedImage image) {
        SortedSet<Integer> columns = new TreeSet<>();
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == (FOREGROUND.getRGB() & 0xFFFFFF)) {
                    columns.add(x);
                }
            }
        }
        return columns;
    }

    private static LogViewer.CharacterSize characterSize(FontMetrics metrics) {
        int ascent = metrics.getAscent();
        int descent = metrics.getDescent();
        return new LogViewer.CharacterSize(metrics.charWidth('W'), ascent + descent, ascent, descent);
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
