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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static se.grynna.pager.utils.DarculaColors.BACKGROUND;
import static se.grynna.pager.utils.DarculaColors.BACKGROUND_BOOKMARKS;
import static se.grynna.pager.utils.DarculaColors.BACKGROUND_MATCHES;
import static se.grynna.pager.utils.DarculaColors.BOOKMARK;
import static se.grynna.pager.utils.DarculaColors.BOOKMARK_DIM;
import static se.grynna.pager.utils.DarculaColors.FOREGROUND;
import static se.grynna.pager.utils.DarculaColors.MATCHES_ACCENT;

/** The Bookmarks view paints a teal tint and stripe and teal rules, and never moves the text columns. */
class LineRendererBookmarksViewTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 40;

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @Test
    void theBackgroundIsTheTealTintNotTheLogOrMatchesOne() {
        BufferedImage bookmarks = render(LineRenderer.ViewKind.BOOKMARKS, false, null);
        BufferedImage matches = render(LineRenderer.ViewKind.MATCHES, false, null);
        BufferedImage log = render(LineRenderer.ViewKind.LOG, false, null);

        int x = WIDTH - 1;
        int y = HEIGHT - 1;
        assertThat(rgb(bookmarks, x, y)).isEqualTo(rgb(BACKGROUND_BOOKMARKS));
        assertThat(rgb(matches, x, y)).isEqualTo(rgb(BACKGROUND_MATCHES));
        assertThat(rgb(log, x, y)).isNotEqualTo(rgb(BACKGROUND_BOOKMARKS));
        assertThat(rgb(BACKGROUND_BOOKMARKS)).isNotEqualTo(rgb(BACKGROUND));
    }

    @Test
    void theStripeIsAFullHeightTealBarInTheMargin() {
        BufferedImage image = render(LineRenderer.ViewKind.BOOKMARKS, false, null);

        for (int x = 0; x < LineRenderer.MATCHES_STRIPE_WIDTH; x++) {
            for (int y = 0; y < charHeight(); y++) {
                assertThat(rgb(image, x, y)).as("stripe at column %d, row %d", x, y).isEqualTo(rgb(BOOKMARK));
            }
        }
        BufferedImage matches = render(LineRenderer.ViewKind.MATCHES, false, null);
        assertThat(rgb(matches, 0, 0)).isEqualTo(rgb(MATCHES_ACCENT));
    }

    @Test
    void thePerRowBookmarkFillIsSkippedBecauseEveryRowIsBookmarked() {
        BufferedImage marked = render(LineRenderer.ViewKind.BOOKMARKS, true, null);
        BufferedImage plain = render(LineRenderer.ViewKind.BOOKMARKS, false, null);

        assertThat(sameImage(marked, plain)).isTrue();
        // The log view still paints the bookmark row fill.
        assertThat(sameImage(render(LineRenderer.ViewKind.LOG, true, null),
                render(LineRenderer.ViewKind.LOG, false, null))).isFalse();
    }

    @Test
    void textColumnsAreTheSameInEveryView() {
        SortedSet<Integer> log = textColumns(render(LineRenderer.ViewKind.LOG, false, null));

        assertThat(log).isNotEmpty();
        assertThat(textColumns(render(LineRenderer.ViewKind.BOOKMARKS, false, null))).isEqualTo(log);
        assertThat(textColumns(render(LineRenderer.ViewKind.BOOKMARKS, true, null))).isEqualTo(log);
        assertThat(textColumns(render(LineRenderer.ViewKind.MATCHES, false, null))).isEqualTo(log);
    }

    @Test
    void dateAndGapRulesAreTeal() {
        BufferedImage date = render(LineRenderer.ViewKind.BOOKMARKS, false, new GapRule.Date(LocalDate.of(2017, 3, 1)));
        BufferedImage gap = render(LineRenderer.ViewKind.BOOKMARKS, false, new GapRule.Gap(Duration.ofMinutes(5)));

        int x = 100;
        assertThat(rgb(date, x, 0)).isEqualTo(rgb(BOOKMARK));
        assertThat(rgb(date, x, 1)).isEqualTo(rgb(BOOKMARK));
        assertThat(rgb(gap, x, 0)).isEqualTo(rgb(BOOKMARK_DIM));

        BufferedImage amber = render(LineRenderer.ViewKind.MATCHES, false, new GapRule.Date(LocalDate.of(2017, 3, 1)));
        assertThat(rgb(amber, x, 0)).isEqualTo(rgb(MATCHES_ACCENT));
    }

    @Test
    void setMatchesViewIsTheOldTwoWaySwitch() {
        assertThat(sameImage(render(LineRenderer.ViewKind.MATCHES, false, null), renderLegacy(true))).isTrue();
        assertThat(sameImage(render(LineRenderer.ViewKind.LOG, false, null), renderLegacy(false))).isTrue();
    }

    private static BufferedImage renderLegacy(boolean matchesView) {
        return paint(renderer -> renderer.setMatchesView(matchesView), false, null);
    }

    private static BufferedImage render(LineRenderer.ViewKind kind, boolean bookmarked, GapRule rule) {
        return paint(renderer -> renderer.setViewKind(kind), bookmarked, rule);
    }

    private static BufferedImage paint(java.util.function.Consumer<LineRenderer> mode, boolean bookmarked,
            GapRule rule) {
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
            mode.accept(renderer);
            renderer.paintBackground(g2d, WIDTH, HEIGHT);

            var event = new LoadedLogLine(MsgType.JSON, "segment1", 0, "cd0", "bkt", Instant.parse(
                    "2017-03-01T10:00:00Z"), "raw", Instant.parse("2017-03-01T10:00:00Z"),
                    Instant.parse("2017-03-01T10:00:00Z"), "hello world", false, Level.INFO);
            var line = new DecoratedLogLine(event);
            line.setBookmarked(bookmarked);
            if (rule != null) {
                line.setGapRule(rule);
            }
            renderer.render(g2d, line, null, null, 0, 0);
        } finally {
            g2d.dispose();
        }
        return image;
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        for (int x = 0; x < a.getWidth(); x++) {
            for (int y = 0; y < a.getHeight(); y++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

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

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    private static int rgb(Color color) {
        return color.getRGB() & 0xFFFFFF;
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
