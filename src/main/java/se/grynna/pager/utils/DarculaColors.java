package se.grynna.pager.utils;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The colours of the application. The fields hold the palette of the current {@link Theme}; {@link #use(Theme)}
 * replaces them, so read a field where it is painted or set, and never cache it in a field of your own.
 */
@SuppressWarnings("ConvertToBasicLatin")
public class DarculaColors {
    /*
        Background		#282a36	40 42 54	231° 15% 18%
    Current Line		#44475a	68 71 90	232° 14% 31%
    Foreground		#f8f8f2	248 248 242	60° 30% 96%
    Comment		#6272a4	98 114 164	225° 27% 51%
    Cyan		#8be9fd	139 233 253	191° 97% 77%
    Green		#50fa7b	80 250 123	135° 94% 65%
    Orange		#ffb86c	255 184 108	31° 100% 71%
    Pink		#ff79c6	255 121 198	326° 100% 74%
    Purple		#bd93f9	189 147 249	265° 89% 78%
    Red		#ff5555	255 85 85	0° 100% 67%
    Yellow		#f1fa8c	241 250 140	65° 92% 76%
         */

    private static Theme current;

    public static Color BACKGROUND;
    public static Color BACKGROUND_PLUS;
    public static Color BACKGROUND_OUTSIDE;
    /** Every other row of a list (scope page field filters), between BACKGROUND and BACKGROUND_PLUS. */
    public static Color LIST_STRIPE;
    /** The list row under the mouse. */
    public static Color LIST_HOVER;

    public static Color BACKGROUND_WARNING;

    public static Color CURRENT_LINE;
    public static Color FOREGROUND;
    public static Color GREY;
    public static Color GREY_DARK_TRANSLUCENT;

    public static Color COMMENT;
    public static Color CYAN;
    public static Color GREEN;
    public static Color GREEN_ANDROID_TEXT;
    public static Color PURPLE;
    public static Color RED;
    /** Light red of the Exclude actions, {@link #RED}'s hue at a lightness that reads on the theme's status bar. */
    public static Color EXCLUDE_ACCENT;
    public static Color PINK;
    public static Color YELLOW;
    public static Color CARET;
    public static Color ORANGE;
    /** {@link #ORANGE} at 60 % intensity: the dim phase of the status bar's activity blip. */
    public static Color ORANGE_DIM;
    public static Color BLUE_TEXT;
    public static Color BLUE;
    public static Color MIDNIGHT_BLUE;
    /** Accent of the Narrow actions, {@link #MIDNIGHT_BLUE}'s hue at a lightness that reads on the theme's status bar. */
    public static Color NARROW_ACCENT;
    /** Accent of the Search action, {@link #COMMENT}'s hue and chroma at a lightness that reads on the theme's background. */
    public static Color SEARCH_ACCENT;
    /** The status bar of the Matches view, with text that reads on it. */
    public static Color MATCHES_BAR;
    /** Accent of the Matches view: edge stripe and date rules. */
    public static Color MATCHES_ACCENT;
    /** Dim accent for gap hairlines between events in the Matches view. */
    public static Color MATCHES_ACCENT_DIM;
    /** Log background nudged about 6 % toward {@link #MATCHES_ACCENT} in OKLab: a subtle mode tint. */
    public static Color BACKGROUND_MATCHES;
    /** Teal of the bookmarks: the row background of bookmarked events and the bookmarks view accents. */
    public static Color BOOKMARK;
    /** Log background moved about 18 % toward {@link #BOOKMARK} in OKLab: the row fill of bookmarked events (Log and Matches views). */
    public static Color BOOKMARK_ROW;
    /** Cursor line on a bookmarked row: {@link #CURRENT_LINE} moved 40 % toward {@link #BOOKMARK} in OKLab, so it stays teal. */
    public static Color BOOKMARK_CURRENT_LINE;
    /** Fill of the default key button: the footer background moved 10 % toward {@link #ORANGE} in OKLab. */
    public static Color KEY_BUTTON_DEFAULT;
    /** Hover/press fill of the default key button: 18 % toward {@link #ORANGE}. */
    public static Color KEY_BUTTON_DEFAULT_HOVER;
    /** Log background nudged about 6 % toward {@link #BOOKMARK} in OKLab: the bookmarks view tint. */
    public static Color BACKGROUND_BOOKMARKS;
    /** Dim teal for gap hairlines between events in the Bookmarks view. */
    public static Color BOOKMARK_DIM;
    /** The status bar of the Bookmarks view, the counterpart of {@link #MATCHES_BAR}. */
    public static Color BOOKMARKS_BAR;
    /** Log background nudged about 6 % toward {@link #BLUE_TEXT} in OKLab: the event details tint. */
    public static Color BACKGROUND_DETAILS;
    public static Color DARK_RETRO_VGA_BLUE;

    public static Color BORDER_HIGHLIGHT;

    public static Color BLACK;
    public static Color G0;
    public static Color G1;
    public static Color G2;
    public static Color G3;
    public static Color G4;
    public static Color G5;
    public static Color G6;
    public static Color G7;
    public static Color G8;
    public static Color G9;

    /** Background of the text fields and boxes of the time panel. */
    public static Color FIELD_FILL;
    /** Shading of the alternate bands of the minimap. Subtle, under the amber. */
    public static Color GREY_BAND;
    /** Fill of a selected range in the time panel calendar. */
    public static Color RANGE_FILL;
    /** Fill of a range preview in the time panel calendar. */
    public static Color PREVIEW_FILL;
    /** Fill of a hovered calendar day. */
    public static Color HOVER_FILL;
    /**
     * Background of the other occurrences of the selected text. In the dark theme it is one unit away from
     * {@link #COMMENT} so that the palette remap in {@link #useAndRemap(Theme)} stays unambiguous.
     */
    public static Color SELECTION_ECHO;
    /**
     * Background of search and narrowed-term highlights in the log text. In the dark theme it is one unit away from
     * {@link #YELLOW} so that the palette remap in {@link #useAndRemap(Theme)} stays unambiguous.
     */
    public static Color MATCH_HIGHLIGHT;
    /**
     * Text colour on {@link #MATCH_HIGHLIGHT}: dark ink in both themes so the warm gold fill stays readable. In the
     * dark theme it is one unit away from {@link #BACKGROUND}.
     */
    public static Color MATCH_HIGHLIGHT_TEXT;
    /** Blue-white glare of the minimap and the input flash: the range outline, its fill and its glow. */
    public static Color GLOW;
    /** The veil over the log view while a modal layer is open. */
    public static Color VEIL;

    static {
        use(Theme.DARK);
    }

    /** The theme whose palette the fields currently hold. */
    public static Theme theme() {
        return current;
    }

    /**
     * Replaces the palette with that of {@code theme}, including the colours derived from it. Call on the EDT.
     */
    public static void use(Theme theme) {
        boolean light = theme == Theme.LIGHT;
        current = theme;

        BACKGROUND = pick(light, new Color(0x282a34), new Color(0xfafaf8));
        BACKGROUND_PLUS = pick(light, new Color(0x32343e), new Color(0xf0f0ec));
        BACKGROUND_OUTSIDE = pick(light, new Color(0x1e202a), new Color(0xe6e6e0));
        LIST_STRIPE = pick(light, new Color(0x2c2e38), new Color(0xf4f4f0));
        LIST_HOVER = BACKGROUND_PLUS;

        BACKGROUND_WARNING = pick(light, new Color(0x3a2a2c), new Color(0xfbe4e2));

        CURRENT_LINE = pick(light, new Color(0x44475a), new Color(0xd6dcea));
        FOREGROUND = pick(light, new Color(0xf0f0e8), new Color(0x1e2028));
        GREY = pick(light, new Color(0x80807c), new Color(0x6e6e6a));
        GREY_DARK_TRANSLUCENT = pick(light, new Color(0x64646462, true), new Color(0x7878746e, true));

        COMMENT = pick(light, new Color(0x6272a4), new Color(0x4a5a8c));
        CYAN = pick(light, new Color(0x8be9fd), new Color(0x0b6f8c));
        GREEN = pick(light, new Color(0x62b543), new Color(0x2e7d32));
        GREEN_ANDROID_TEXT = pick(light, new Color(0xa4c639), new Color(0x5a7d12));
        PURPLE = pick(light, new Color(0xbd93f9), new Color(0x6a3fb0));
        RED = pick(light, new Color(0xc02505), new Color(0xc02505));
        PINK = pick(light, new Color(0xf98b9e), new Color(0xb0245a));
        YELLOW = pick(light, new Color(0xdddc6e), new Color(0x8a6a00));
        CARET = YELLOW;
        ORANGE = pick(light, new Color(0xf4af3d), new Color(0xa85a00));
        BLUE_TEXT = pick(light, new Color(0x40b6e0), new Color(0x0b5f8f));
        BLUE = pick(light, new Color(0x375a81), new Color(0xb8d0ea));
        MIDNIGHT_BLUE = pick(light, new Color(0x21426b), new Color(0xd6e4f5));
        MATCHES_BAR = pick(light, new Color(0x4d3c1c), new Color(0xf6e7bf));
        MATCHES_ACCENT = pick(light, new Color(0xe8a33d), new Color(0xb8741a));
        MATCHES_ACCENT_DIM = pick(light, new Color(0x7a5b2e), new Color(0xe0c58f));
        BOOKMARK = pick(light, new Color(0x3fb6a8), new Color(0x138a7e));
        BOOKMARK_DIM = pick(light, new Color(0x2d6d66), new Color(0x9fd6cc));
        BOOKMARKS_BAR = pick(light, new Color(0x1c423e), new Color(0xcdeee8));
        DARK_RETRO_VGA_BLUE = pick(light, new Color(0x041f63), new Color(0x041f63));

        BORDER_HIGHLIGHT = pick(light, new Color(0x6a9fd9), new Color(0x2f6fb8));

        BLACK = pick(light, new Color(0x101010), new Color(0x101010));
        G0 = pick(light, new Color(0x1e2021), new Color(0xe1e1e1));
        G1 = pick(light, new Color(0x212121), new Color(0xdedede));
        G2 = pick(light, new Color(0x303234), new Color(0xcfcfcf));
        G3 = pick(light, new Color(0x3c3f41), new Color(0xc3c3c3));
        G4 = pick(light, new Color(0x46494b), new Color(0xb9b9b9));
        G5 = pick(light, new Color(0x4e5052), new Color(0xb1b1b1));
        G6 = pick(light, new Color(0x5c5e6a), new Color(0xa3a3a3));
        G7 = pick(light, new Color(0x8c8e9a), new Color(0x737373));
        G8 = pick(light, new Color(0xccceda), new Color(0x333333));
        G9 = pick(light, new Color(0xeceefa), new Color(0x131313));

        FIELD_FILL = pick(light, new Color(0x1c1d24), new Color(0xe9e9e4));
        SELECTION_ECHO = pick(light, new Color(0x6272a5), new Color(0xc4d6f0));
        MATCH_HIGHLIGHT = pick(light, new Color(0xdddc6f), new Color(0xf2d974));
        MATCH_HIGHLIGHT_TEXT = pick(light, new Color(0x282a35), new Color(0x1e2027));
        GLOW = pick(light, new Color(0xc8e4ff), new Color(0x3b82d0));
        VEIL = pick(light, new Color(0x141414), new Color(0xf0f0f0));
        HOVER_FILL = pick(light, new Color(0x16ffffff, true), new Color(0x16000000, true));

        EXCLUDE_ACCENT = accent(RED, light ? 0.45f : 0.65f, 0.20f);
        NARROW_ACCENT = accent(MIDNIGHT_BLUE, light ? 0.42f : 0.80f, 0.10f);
        SEARCH_ACCENT = accent(COMMENT, light ? 0.45f : 0.78f, 0.08f);
        ORANGE_DIM = new Color((int) (ORANGE.getRed() * 0.6), (int) (ORANGE.getGreen() * 0.6),
                (int) (ORANGE.getBlue() * 0.6));
        GREY_BAND = withAlpha(GREY, 60);
        RANGE_FILL = withAlpha(BLUE, 130);
        PREVIEW_FILL = withAlpha(BLUE, 70);

        BACKGROUND_MATCHES = OKLab.oklabToColor(mixOklab(BACKGROUND, MATCHES_ACCENT, 0.06f));
        BOOKMARK_ROW = OKLab.oklabToColor(mixOklab(BACKGROUND, BOOKMARK, 0.18f));
        BOOKMARK_CURRENT_LINE = OKLab.oklabToColor(mixOklab(CURRENT_LINE, BOOKMARK, 0.4f));
        KEY_BUTTON_DEFAULT = OKLab.oklabToColor(mixOklab(BACKGROUND_OUTSIDE, ORANGE, 0.10f));
        KEY_BUTTON_DEFAULT_HOVER = OKLab.oklabToColor(mixOklab(BACKGROUND_OUTSIDE, ORANGE, 0.18f));
        BACKGROUND_BOOKMARKS = OKLab.oklabToColor(mixOklab(BACKGROUND, BOOKMARK, 0.06f));
        BACKGROUND_DETAILS = OKLab.oklabToColor(mixOklab(BACKGROUND, BLUE_TEXT, 0.06f));
    }

    /**
     * Switches to {@code theme} like {@link #use(Theme)} and returns, for each colour of the previous palette, the
     * colour that replaces it. Two colours of the previous palette that share a value must also share their
     * replacement; otherwise a component's colour could not be mapped to one new colour.
     *
     * @throws IllegalStateException when that does not hold
     */
    public static Map<Color, Color> useAndRemap(Theme theme) {
        Map<String, Color> before = values();
        use(theme);
        Map<String, Color> after = values();
        Map<Color, Color> remap = new HashMap<>();
        for (Map.Entry<String, Color> entry : before.entrySet()) {
            Color to = after.get(entry.getKey());
            Color previous = remap.putIfAbsent(entry.getValue(), to);
            if (previous != null && !previous.equals(to)) {
                throw new IllegalStateException(entry.getKey() + " shares " + entry.getValue()
                        + " with another colour, but the two map to different colours in " + theme);
            }
        }
        return remap;
    }

    private static Map<String, Color> values() {
        Map<String, Color> values = new LinkedHashMap<>();
        for (Field field : DarculaColors.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Color.class) {
                try {
                    values.put(field.getName(), (Color) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return values;
    }

    private static Color pick(boolean light, Color dark, Color lightColor) {
        return light ? lightColor : dark;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** The hue of {@code source} at the given OKLCH lightness and chroma, clipped into the sRGB gamut. */
    private static Color accent(Color source, float lightness, float chroma) {
        OKLab.LCH hue = OKLab.colorToOklch(source);
        return OKLab.oklchToColor(OKLab.mapToGamut(new OKLab.LCH(lightness, chroma, hue.H())));
    }

    private static OKLab.Lab mixOklab(Color from, Color to, float t) {
        OKLab.Lab a = OKLab.colorToOklab(from);
        OKLab.Lab b = OKLab.colorToOklab(to);
        return new OKLab.Lab(a.L() + (b.L() - a.L()) * t, a.a() + (b.a() - a.a()) * t, a.b() + (b.b() - a.b()) * t);
    }
}
