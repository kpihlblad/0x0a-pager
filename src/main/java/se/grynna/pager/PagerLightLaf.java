package se.grynna.pager;

import com.formdev.flatlaf.FlatLightLaf;

/**
 * 0x0A Pager's light look and feel, the counterpart of {@link PagerLaf}. Its fonts and colours live in
 * {@code PagerLightLaf.properties} next to this class.
 */
public class PagerLightLaf extends FlatLightLaf {
    @Override
    public String getName() {
        return "PagerLightLaf";
    }
}
