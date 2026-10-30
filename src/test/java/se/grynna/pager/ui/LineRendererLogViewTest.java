package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.Placeholder;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;
import se.grynna.pager.ui.sprattel.LoadingAnimationTimer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static se.grynna.pager.utils.DarculaColors.MATCHES_ACCENT;
import static se.grynna.pager.utils.DarculaColors.MIDNIGHT_BLUE;

/**
 * Log view: a date turnover between two loaded rows is drawn like the Matches view's date rule
 * (zone-aware local day). Selected rows form one continuous block (no seam at the row tops).
 */
class LineRendererLogViewTest {

    private static final int WIDTH = 400;
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @AfterEach
    void restoreUtc() throws ReflectiveOperationException {
        // initDTM() is a no-op once the singleton exists; replace it, or the Stockholm zone leaks into later tests.
        initZone(ZoneId.of("UTC"));
    }

    @Test
    void dateTurnoverBetweenRowsGetsDateRule() {
        var rows = List.of(
                row(event(1, "2017-03-01T10:00:00Z", false)),
                row(event(2, "2017-03-02T10:00:00Z", false)));

        BufferedImage image = render(rows);

        assertRuleAt(image, 1, true);
    }

    @Test
    void dateRulesOnAdjacentRowsAreThinAndTranslucent() {
        var rows = List.of(
                row(event(1, "2017-03-01T10:00:00Z", false)),
                row(event(2, "2017-03-02T10:00:00Z", false)),
                row(event(3, "2017-03-03T10:00:00Z", false)),
                row(event(4, "2017-03-03T11:00:00Z", false)));

        BufferedImage image = render(rows);

        for (int rowIndex : new int[]{1, 2}) {
            assertRuleAt(image, rowIndex, false);
            int top = rowIndex * charHeight();
            // Past the text: half accent over the black background, and only one pixel row.
            int pixel = image.getRGB(WIDTH / 2, top);
            assertThat(new Color(pixel).getRed()).as("row %d", rowIndex)
                    .isBetween(MATCHES_ACCENT.getRed() / 2 - 2, MATCHES_ACCENT.getRed() / 2 + 2)
                    .isPositive();
            assertThat(image.getRGB(WIDTH / 2, top + 1) & 0xFFFFFF).isNotEqualTo(pixel & 0xFFFFFF);
        }
    }

    @Test
    void sameLocalDateGetsNoRule() {
        var rows = List.of(
                row(event(1, "2017-03-01T10:00:00Z", false)),
                row(event(2, "2017-03-01T11:00:00Z", false)));

        assertRuleAt(render(rows), 1, false);
    }

    @Test
    void dayChangeIsJudgedInTheDisplayZone() throws ReflectiveOperationException {
        initZone(STOCKHOLM);
        // 22:30Z is 23:30 local on the 1st; 23:30Z is 00:30 local on the 2nd: a turnover in Stockholm only.
        var turnover = List.of(
                row(event(1, "2017-03-01T22:30:00Z", false)),
                row(event(2, "2017-03-01T23:30:00Z", false)));
        assertRuleAt(render(turnover), 1, true);

        // 23:30Z and 23:45Z are both on the 2nd in Stockholm: no rule between them.
        var sameLocal = List.of(
                row(event(3, "2017-03-01T23:30:00Z", false)),
                row(event(4, "2017-03-01T23:45:00Z", false)));
        assertRuleAt(render(sameLocal), 1, false);
    }

    @Test
    void continuationRowsNeverGetADateRule() {
        var rows = List.of(
                row(event(1, "2017-03-01T23:50:00Z", false)),
                row(event(2, "2017-03-02T00:10:00Z", true)));

        assertRuleAt(render(rows), 1, false);
    }

    @Test
    void placeholdersAndBoundariesGetNoDateRule() {
        var placeholderBefore = List.of(
                row(event(1, "2017-03-01T10:00:00Z", false)),
                row(new Placeholder(2, "")));
        assertRuleAt(render(placeholderBefore), 1, false);

        var placeholderAfter = List.of(
                row(new Placeholder(1, "")),
                row(event(2, "2017-03-02T10:00:00Z", false)));
        assertRuleAt(render(placeholderAfter), 1, false);
    }

    @Test
    void selectedRowsFormOneContinuousBlockWhenSelectedUpwards() {
        // Upwards: the cursor (highlighted) row is the top one, the anchor row below it.
        assertSelectedRowsContinuous(true);
    }

    @Test
    void selectedRowsFormOneContinuousBlockWhenSelectedDownwards() {
        // Downwards: the cursor row is the bottom one; the highlight is not on the top row.
        assertSelectedRowsContinuous(false);
    }

    private static void assertSelectedRowsContinuous(boolean cursorOnTop) {
        int charH = charHeight();
        var rows = new ArrayList<DecoratedLogLine>();
        for (int i = 0; i < 3; i++) {
            var line = row(event(i + 1, "2017-03-01T10:00:0" + i + "Z", false));
            line.setSelectionStart(0);
            line.setSelectionEnd(Integer.MAX_VALUE);
            rows.add(line);
        }
        rows.get(cursorOnTop ? 0 : 2).setHighlighted(true);

        BufferedImage image = render(rows);

        int midnight = MIDNIGHT_BLUE.getRGB() & 0xFFFFFF;
        for (int y = 0; y < 3 * charH; y++) {
            assertThat(image.getRGB(WIDTH - 3, y) & 0xFFFFFF)
                    .as("selection pixel row %d (cursor on top: %s)", y, cursorOnTop)
                    .isEqualTo(midnight);
        }
    }

    private static void assertRuleAt(BufferedImage image, int rowIndex, boolean expected) {
        int accent = MATCHES_ACCENT.getRGB() & 0xFFFFFF;
        int top = rowIndex * charHeight();
        int accentPixels = 0;
        for (int x = 0; x < WIDTH; x++) {
            if ((image.getRGB(x, top) & 0xFFFFFF) == accent && (image.getRGB(x, top + 1) & 0xFFFFFF) == accent) {
                accentPixels++;
            }
        }
        if (expected) {
            assertThat(accentPixels).as("date rule columns at row %d", rowIndex).isEqualTo(WIDTH);
        } else {
            assertThat(accentPixels).as("date rule columns at row %d", rowIndex).isZero();
        }
    }

    private static DecoratedLogLine row(LogLine logLine) {
        return new DecoratedLogLine(logLine);
    }

    private static LoadedLogLine event(int lineNumber, String time, boolean continuation) {
        Instant instant = Instant.parse(time);
        return new LoadedLogLine(MsgType.JSON, "segment1", lineNumber, "cd" + lineNumber, "bkt", instant, "raw",
                instant, instant, "hello world", continuation, Level.INFO);
    }

    /** The manager is a singleton that keeps its first config: drop it so this test's zone takes effect. */
    private static void initZone(ZoneId zone) throws ReflectiveOperationException {
        Field instance = DateTimeFormatterManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        Config config = mock(Config.class);
        when(config.getLocale()).thenReturn(Locale.of("sv", "SE"));
        when(config.getZoneId()).thenReturn(zone);
        DateTimeFormatterManager.initialize(config);
    }

    /** Renders the rows top to bottom, each with its neighbours, like the LogViewer paint loop. */
    private static BufferedImage render(List<DecoratedLogLine> rows) {
        int charH = charHeight();
        int height = rows.size() * charH;
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, WIDTH, height);
            Font font = Fontalizer.getFont();
            g2d.setFont(font);
            FontMetrics metrics = g2d.getFontMetrics(font);
            var renderer = new LineRenderer(new LoadingAnimationTimer(new JPanel(), ""), metrics,
                    new Rectangle(0, 0, WIDTH, height));
            renderer.setCharSize(new LogViewer.CharacterSize(metrics.charWidth('W'), charH,
                    metrics.getAscent(), metrics.getDescent()));
            renderer.setMatchesView(false);
            for (int i = 0; i < rows.size(); i++) {
                DecoratedLogLine prev = i > 0 ? rows.get(i - 1) : null;
                DecoratedLogLine next = i + 1 < rows.size() ? rows.get(i + 1) : null;
                renderer.render(g2d, rows.get(i), next, prev, 0, i * charH);
            }
        } finally {
            g2d.dispose();
        }
        return image;
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
