package se.grynna.pager.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.time.TimePanel;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Dev tool, not a test: renders the {@link TimePanel} headless at 1000 px and 800 px width (stacked) with canned
 * values and writes a PNG, so its look can be reviewed without a display.
 *
 * <pre>
 * mvn test -Dtest=TimePanelSnapshotTool -Dtest.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false \
 *     [-Dsnapshot.out=/tmp/opencode/timepanel.png]
 * </pre>
 */
@Tag("tool")
class TimePanelSnapshotTool {

    private static final int[] WIDTHS = {1000, 800};
    private static final int GAP = 20;

    @Test
    void render() throws Exception {
        String out = System.getProperty("snapshot.out", "/tmp/opencode/timepanel.png");
        ZoneId zone = ZoneId.of("UTC");
        Clock clock = Clock.fixed(Instant.parse("2017-05-18T09:30:00Z"), zone);

        SwingUtilities.invokeAndWait(() -> {
            PagerLaf.setup();
            Font font = Fontalizer.getFont();
            TimePanel.Listener none = new TimePanel.Listener() {
                @Override
                public void go(Instant at) {}

                @Override
                public void setRange(Instant from, Instant to) {}

                @Override
                public void close() {}
            };

            TimePanel[] panels = new TimePanel[WIDTHS.length];
            int height = GAP;
            for (int i = 0; i < WIDTHS.length; i++) {
                TimePanel p = new TimePanel(font, zone, clock, ref -> s -> Optional.empty(), none);
                p.open(i == 0 ? TimePanel.Focus.AT : TimePanel.Focus.FROM, Instant.parse("2017-05-01T12:03:14Z"),
                        Instant.parse("2017-05-01T11:00:00Z"),
                        i == 0 ? null : Instant.parse("2017-04-30T10:00:00Z"));
                p.setSize(WIDTHS[i], p.preferredHeight(WIDTHS[i]));
                layout(p);
                panels[i] = p;
                height += p.getHeight() + GAP;
            }

            BufferedImage img = new BufferedImage(WIDTHS[0] + 2 * GAP, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(20, 20, 20));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int y = GAP;
            for (TimePanel p : panels) {
                Graphics2D pg = (Graphics2D) g.create(GAP, y, p.getWidth(), p.getHeight());
                p.print(pg);
                pg.dispose();
                y += p.getHeight() + GAP;
            }
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

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                layout(child);
            }
        }
    }
}
