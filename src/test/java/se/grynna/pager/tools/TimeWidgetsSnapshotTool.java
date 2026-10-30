package se.grynna.pager.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.time.DateTimeField;
import se.grynna.pager.ui.time.DateTimeSegments.Segment;
import se.grynna.pager.ui.time.MonthBox;
import se.grynna.pager.utils.DarculaColors;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Dev tool, not a test: renders the time panel widgets ({@link DateTimeField}, {@link MonthBox}) headless with
 * canned values and writes them as a PNG, so their look can be reviewed without a display.
 *
 * <pre>
 * mvn test -Dtest=TimeWidgetsSnapshotTool -Dtest.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false \
 *     [-Dsnapshot.out=/tmp/opencode/timewidgets.png]
 * </pre>
 */
@Tag("tool")
class TimeWidgetsSnapshotTool {

    @Test
    void render() throws Exception {
        String out = System.getProperty("snapshot.out", "/tmp/opencode/timewidgets.png");
        ZoneId zone = ZoneId.of("UTC");
        Instant now = Instant.parse("2017-05-18T09:30:00Z");
        Clock clock = Clock.fixed(now, zone);

        SwingUtilities.invokeAndWait(() -> {
            PagerLaf.setup();
            Font font = Fontalizer.getFont();

            DateTimeField from = new DateTimeField(font, zone, s -> Optional.empty());
            from.setValue(Instant.parse("2017-05-01T11:00:00Z"));
            DateTimeField to = new DateTimeField(font, zone, s -> Optional.empty());
            to.setSymbol(DateTimeField.Symbol.NOW);
            // hasFocus() is false headless: paint the focused look through an override.
            DateTimeField focused = new DateTimeField(font, zone, s -> Optional.empty()) {
                @Override
                public boolean hasFocus() {
                    return true;
                }
            };
            focused.setValue(Instant.parse("2017-05-01T12:03:14Z"));
            focused.setFocusedSegment(Segment.MINUTE);
            DateTimeField invalid = new DateTimeField(font, zone, s -> Optional.empty());
            invalid.setValue(Instant.parse("2017-04-30T10:00:00Z"));
            invalid.setInvalid(true);
            DateTimeField preview = new DateTimeField(font, zone, s -> Optional.empty());
            preview.setPreview(Instant.parse("2017-05-01T11:58:14Z"));

            MonthBox month = new MonthBox(font, zone, clock);
            month.setMonth(YearMonth.of(2017, 5));
            month.setRange(LocalDate.of(2017, 5, 1), LocalDate.of(2017, 5, 10));
            month.setFocusDay(LocalDate.of(2017, 5, 1));

            JPanel fields = new JPanel(new GridLayout(0, 1, 0, 10));
            fields.setOpaque(false);
            fields.add(row(font, "FROM", from));
            fields.add(row(font, "TO", to));
            fields.add(row(font, "AT (focus)", focused));
            fields.add(row(font, "invalid", invalid));
            fields.add(row(font, "preview", preview));

            JPanel card = new JPanel(new FlowLayout(FlowLayout.LEFT, 24, 16));
            card.setBackground(DarculaColors.BACKGROUND_PLUS);
            card.add(fields);
            card.add(month);

            Dimension size = card.getPreferredSize();
            int width = size.width + 40;
            int height = size.height + 40;
            JPanel backdrop = new JPanel(new GridBagLayout());
            backdrop.setBackground(new Color(20, 20, 20));
            backdrop.add(card);
            backdrop.setSize(width, height);
            layout(backdrop);

            // Hover the minute segment's ▲ of the focused field and a day of the month box.
            int fx = focused.getPreferredSize().width * 15 / 19;
            focused.dispatchEvent(new MouseEvent(focused, MouseEvent.MOUSE_MOVED, 0, 0, fx, 3, 0, false));
            month.dispatchEvent(new MouseEvent(month, MouseEvent.MOUSE_MOVED, 0, 0, month.getWidth() / 2,
                    month.getHeight() / 2, 0, false));

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            backdrop.print(g);
            g.dispose();
            try {
                File file = new File(out);
                file.getParentFile().mkdirs();
                ImageIO.write(img, "png", file);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("Wrote " + out);
    }

    private static JComponent row(Font font, String label, JComponent field) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(font.deriveFont(11f).deriveFont(Font.BOLD));
        l.setForeground(DarculaColors.COMMENT);
        l.setPreferredSize(new Dimension(90, l.getPreferredSize().height));
        p.add(l);
        p.add(field);
        return p;
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                layout(child);
            }
        }
    }
}
