package se.grynna.pager.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DarculaColorsTest {

    @Test
    void theActionAccentsReachAaContrastOnTheOutsideAndTheMidnightBlueBackgrounds() {
        assertThat(contrast(DarculaColors.EXCLUDE_ACCENT, DarculaColors.BACKGROUND_OUTSIDE))
                .isGreaterThanOrEqualTo(4.5);
        for (Color accent : new Color[]{DarculaColors.NARROW_ACCENT, DarculaColors.SEARCH_ACCENT}) {
            assertThat(contrast(accent, DarculaColors.BACKGROUND_OUTSIDE)).isGreaterThanOrEqualTo(4.5);
            assertThat(contrast(accent, DarculaColors.MIDNIGHT_BLUE)).isGreaterThanOrEqualTo(4.5);
        }
    }

    @AfterEach
    void restoreDarkPalette() {
        DarculaColors.use(Theme.DARK);
    }

    @Test
    void theLightPaletteReachesAaContrastForTheTextAndTheActionAccents() {
        DarculaColors.use(Theme.LIGHT);
        assertThat(contrast(DarculaColors.FOREGROUND, DarculaColors.BACKGROUND)).isGreaterThanOrEqualTo(4.5);
        for (Color accent : new Color[]{DarculaColors.NARROW_ACCENT, DarculaColors.EXCLUDE_ACCENT,
                DarculaColors.SEARCH_ACCENT}) {
            assertThat(contrast(accent, DarculaColors.BACKGROUND_OUTSIDE)).isGreaterThanOrEqualTo(4.5);
            assertThat(contrast(accent, DarculaColors.MIDNIGHT_BLUE)).isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    void switchingBackToTheDarkPaletteRestoresEveryColour() {
        Color darkBackground = DarculaColors.BACKGROUND;
        Color darkAccent = DarculaColors.EXCLUDE_ACCENT;

        DarculaColors.useAndRemap(Theme.LIGHT);
        Color lightBackground = DarculaColors.BACKGROUND;
        Map<Color, Color> back = DarculaColors.useAndRemap(Theme.DARK);

        assertThat(DarculaColors.BACKGROUND).isEqualTo(darkBackground);
        assertThat(DarculaColors.EXCLUDE_ACCENT).isEqualTo(darkAccent);
        assertThat(back).containsEntry(lightBackground, darkBackground);
    }

    /** The WCAG 2.x contrast ratio of two sRGB colours. */
    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
