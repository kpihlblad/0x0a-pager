package se.grynna.pager.ui;

import com.formdev.flatlaf.util.FontUtils;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.text.AttributedCharacterIterator;

import static java.util.Objects.requireNonNull;

@Slf4j
public class Fontalizer {

    // fontLigatures": "'calt', 'ss05', 'ss06', 'ss07', 'ss10', 'liga'",

    private static final String FONT_PATH = "/fonts/MonaspaceNeonNF-Regular.otf";
    private static final String FONT_BOlD_PATH = "/fonts/MonaspaceNeonNF-Bold.otf";
    /** Family names as the font files declare them; referenced from {@code PagerLaf.properties}. */
    public static final String UI_FAMILY = "IBM Plex Sans";
    public static final String UI_FAMILY_SEMIBOLD = "IBM Plex Sans SemiBold";

    private static final Font font;
    private static final Font boldFont;

    private Fontalizer() {}

    public static Font getFont() {
        return font;
    }

    public static Font getBoldFont() {
        return boldFont;
    }

    /**
     * Registers the bundled IBM Plex Sans faces with FlatLaf. They are loaded lazily, the first time a FlatLaf
     * font value ({@code defaultFont}, {@code semibold.font}, ...) names the family. Call before the look and feel
     * is set up.
     */
    public static void installUiFonts() {
        FontUtils.registerFontFamilyLoader(UI_FAMILY,
                () -> installFonts("Regular", "Italic", "Bold"));
        FontUtils.registerFontFamilyLoader(UI_FAMILY_SEMIBOLD, () -> installFonts("SemiBold"));
    }

    private static void installFonts(String... styles) {
        for (String style : styles) {
            String path = "/fonts/IBMPlexSans-" + style + ".ttf";
            if (!FontUtils.installFont(requireNonNull(Fontalizer.class.getResource(path), path))) {
                log.error("Failed to install UI font {}", path);
            }
        }
    }

    /**
     * The proportional font for chrome (menus, tooltips, popups): FlatLaf's {@code defaultFont}; the monospace
     * font without FlatLaf.
     */
    public static Font getUiFont() {
        Font ui = UIManager.getFont("defaultFont");
        return ui != null ? ui : font;
    }

    /** The semibold UI font for headings and field labels; the bold monospace font without FlatLaf. */
    public static Font getUiSemiboldFont() {
        Font ui = UIManager.getFont("semibold.font");
        return ui != null ? ui : boldFont;
    }

    public static FontMetrics getFontMetrics(Graphics g) {
        return g.getFontMetrics(font);
    }

    public static FontMetrics getBoldFontMetrics(Graphics g) {
        return g.getFontMetrics(boldFont);
    }

    static {
        try {

            var baseFont = Font.createFont(Font.TRUETYPE_FONT,
                    requireNonNull(Fontalizer.class.getResourceAsStream(FONT_PATH)));
            for (AttributedCharacterIterator.Attribute a : baseFont.getAvailableAttributes()) {
                log.debug("Font attribute: {}", a);
            }
            font = baseFont.deriveFont(13f);

            var baseBoldFont = Font.createFont(Font.TRUETYPE_FONT,
                    requireNonNull(Fontalizer.class.getResourceAsStream(FONT_BOlD_PATH)));
            boldFont = baseBoldFont.deriveFont(13f);

        } catch (Exception e) {
            log.error("Error loading fonts ({}, {})", FONT_PATH, FONT_BOlD_PATH, e);
            throw new IllegalStateException("Can't load fonts", e);
        }
    }
}
