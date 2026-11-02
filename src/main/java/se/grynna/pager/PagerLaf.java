package se.grynna.pager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.Theme;

import javax.swing.*;

/**
 * 0x0A Pager's look and feel. Its fonts and colours live in {@code PagerLaf.properties} next to this class; FlatLaf loads
 * that file on top of the FlatDarkLaf defaults. The light theme uses {@link PagerLightLaf}.
 */
public class PagerLaf extends FlatDarkLaf {
    public static boolean setup() {
        return setup(Theme.DARK);
    }

    /** Installs the look and feel of {@code theme} at startup; the palette must already match the theme. */
    public static boolean setup(Theme theme) {
        return install(theme);
    }

    /**
     * Installs the fonts and the look and feel of {@code theme}. Open windows keep their current UI until
     * {@link FlatLaf#updateUI()} is called.
     */
    public static boolean install(Theme theme) {
        Fontalizer.installUiFonts();
        boolean installed = FlatLaf.setup(theme == Theme.LIGHT ? new PagerLightLaf() : new PagerLaf());
        UIManager.put("Button.default.foreground", DarculaColors.ORANGE);
        return installed;
    }

    @Override
    public String getName() {
        return "PagerLaf";
    }
}
