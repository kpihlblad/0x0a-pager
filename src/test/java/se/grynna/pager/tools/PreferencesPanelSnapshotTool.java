package se.grynna.pager.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.ui.preferences.PreferencesPanel;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.ZoneId;
import java.util.Locale;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dev tool, not a test: renders the preferences box ({@link PreferencesPanel}) headless with canned values and
 * writes it as a PNG, so its look can be reviewed without a display.
 *
 * <pre>
 * mvn test -Dtest=PreferencesPanelSnapshotTool -Dtest.excludedGroups=none -Dsurefire.failIfNoSpecifiedTests=false \
 *     [-Dsnapshot.out=/tmp/opencode/preferencespanel.png]
 * </pre>
 */
@Tag("tool")
class PreferencesPanelSnapshotTool {

    @Test
    void render() throws Exception {
        String out = System.getProperty("snapshot.out", "/tmp/opencode/preferencespanel.png");

        Config config = mock(Config.class);
        when(config.getZoneId()).thenReturn(ZoneId.of("Europe/Stockholm"));
        when(config.getLocale()).thenReturn(Locale.forLanguageTag("sv-SE"));
        when(config.getKeyHints()).thenReturn("DESKTOP");
        when(config.getShowMinimap()).thenReturn(true);

        SwingUtilities.invokeAndWait(() -> {
            PagerLaf.setup();
            PreferencesPanel panel = new PreferencesPanel(config, saved -> {});
            Dimension size = panel.getPreferredSize();
            int width = size.width + 80;
            int height = size.height + 80;
            JPanel backdrop = new JPanel(new GridBagLayout());
            backdrop.setBackground(new Color(20, 20, 20));
            backdrop.add(panel);
            backdrop.setSize(width, height);
            layout(backdrop);
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

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                layout(child);
            }
        }
    }
}
