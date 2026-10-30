package se.grynna.pager;

import com.formdev.flatlaf.FlatDarkLaf;
import se.grynna.pager.ui.Fontalizer;

/**
 * 0x0A Pager's look and feel. Its fonts and colours live in {@code PagerLaf.properties} next to this class; FlatLaf loads
 * that file on top of the FlatDarkLaf defaults.
 */
public class PagerLaf extends FlatDarkLaf {
    public static boolean setup() {
        Fontalizer.installUiFonts();
        return setup(new PagerLaf());
    }

    @Override
    public String getName() {
        return "PagerLaf";
    }
}
