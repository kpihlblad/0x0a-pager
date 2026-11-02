package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.PagerLaf;
import se.grynna.pager.PagerLightLaf;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.splunk.LoginReason;
import se.grynna.pager.ui.login.UserLoginPanel;
import se.grynna.pager.ui.preferences.PreferencesPanel;
import se.grynna.pager.ui.time.TimePanel;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.Theme;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.UIResource;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThemeSwitchTest {

    @AfterEach
    void restoreDarkTheme() {
        DarculaColors.use(Theme.DARK);
        PagerLaf.install(Theme.DARK);
    }

    @Test
    void recolourReplacesTheExplicitColoursOfTheOldPaletteButKeepsTheLookAndFeelsOwn() {
        Color darkBackground = DarculaColors.BACKGROUND;
        Color darkBorder = DarculaColors.G3;
        JPanel explicit = new JPanel();
        explicit.setBackground(darkBackground);
        explicit.setBorder(BorderFactory.createLineBorder(darkBorder));
        JPopupMenu popup = new JPopupMenu();
        JMenuItem item = new JMenuItem("item");
        item.setForeground(DarculaColors.FOREGROUND);
        popup.add(item);
        explicit.setComponentPopupMenu(popup);
        JPanel fromLaf = new JPanel();
        fromLaf.setBackground(new ColorUIResource(darkBackground));

        Map<Color, Color> remap = DarculaColors.useAndRemap(Theme.LIGHT);
        ThemeSwitch.recolour(explicit, remap);
        ThemeSwitch.recolour(fromLaf, remap);

        assertThat(explicit.getBackground()).isEqualTo(DarculaColors.BACKGROUND).isNotEqualTo(darkBackground);
        assertThat(((LineBorder) explicit.getBorder()).getLineColor()).isEqualTo(DarculaColors.G3);
        assertThat(item.getForeground()).isEqualTo(DarculaColors.FOREGROUND);
        assertThat(fromLaf.getBackground()).isEqualTo(darkBackground);
    }

    @Test
    void applyingTheLightThemeInstallsTheLightLookAndFeel() {
        ThemeSwitch.apply(Theme.LIGHT);

        assertThat(DarculaColors.theme()).isEqualTo(Theme.LIGHT);
        assertThat(UIManager.getLookAndFeel()).isInstanceOf(PagerLightLaf.class);
    }

    @Test
    void everyExplicitColourOfTheMainSurfacesBelongsToThePaletteAndRemapsCleanly() throws Exception {
        DateTimeFormatterManagerTestUtil.initDTM();
        SwingUtilities.invokeAndWait(() -> {
            DarculaColors.use(Theme.DARK);
            PagerLaf.install(Theme.DARK);

            LogViewer viewer = new LogViewer(Clock.systemUTC());
            UserLoginPanel relogin = new UserLoginPanel(new Config(),
                    LoginReason.relogin("expired"), c -> {});
            PreferencesPanel prefs = new PreferencesPanel(new Config(), saved -> {});
            TimePanel time = new TimePanel(Fontalizer.getFont(), ZoneOffset.UTC, Clock.systemUTC(),
                    ref -> s -> Optional.empty(), mock(TimePanel.Listener.class));
            List<Component> roots = List.of(viewer, relogin, prefs, time);

            Map<Color, Color> toLight = DarculaColors.useAndRemap(Theme.LIGHT);
            Set<Color> darkPalette = toLight.keySet();
            for (Component root : roots) {
                assertExplicitColoursInPalette(root, darkPalette);
                ThemeSwitch.recolour(root, toLight);
            }

            Map<Color, Color> toDark = DarculaColors.useAndRemap(Theme.DARK);
            Set<Color> lightPalette = toDark.keySet();
            for (Component root : roots) {
                assertExplicitColoursInPalette(root, lightPalette);
                ThemeSwitch.recolour(root, toDark);
            }
        });
    }

    private static void assertExplicitColoursInPalette(Component component, Set<Color> palette) {
        if (component instanceof JComboBox<?> || component instanceof JScrollBar) {
            return;
        }
        checkColour(component, "background", component.isBackgroundSet() ? component.getBackground() : null, palette);
        checkColour(component, "foreground", component.isForegroundSet() ? component.getForeground() : null, palette);
        if (component instanceof JComponent jc) {
            checkBorder(component, jc.getBorder(), palette);
            if (jc instanceof JTextComponent text) {
                checkColour(component, "caret", text.getCaretColor(), palette);
            }
            if (jc.getComponentPopupMenu() != null) {
                assertExplicitColoursInPalette(jc.getComponentPopupMenu(), palette);
            }
        }
        if (component instanceof JMenu menu) {
            assertExplicitColoursInPalette(menu.getPopupMenu(), palette);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                assertExplicitColoursInPalette(child, palette);
            }
        }
    }

    private static void checkBorder(Component owner, Border border, Set<Color> palette) {
        if (border instanceof CompoundBorder compound) {
            checkBorder(owner, compound.getOutsideBorder(), palette);
            checkBorder(owner, compound.getInsideBorder(), palette);
        } else if (border instanceof LineBorder line) {
            checkColour(owner, "lineBorder", line.getLineColor(), palette);
        } else if (border instanceof MatteBorder matte) {
            checkColour(owner, "matteBorder", matte.getMatteColor(), palette);
        }
    }

    private static void checkColour(Component owner, String role, Color colour, Set<Color> palette) {
        if (colour == null || colour instanceof UIResource) {
            return;
        }
        assertThat(palette)
                .as("%s (%s) on %s", role, colour, owner.getClass().getName())
                .contains(colour);
    }
}
