package se.grynna.pager.utils;

import java.awt.*;

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

    public static final Color BACKGROUND = new Color(40, 42, 52);
    public static final Color BACKGROUND_PLUS = new Color(50, 52, 62);
    public static final Color BACKGROUND_OUTSIDE = new Color(30, 32, 42);
    /** Every other row of a list (scope page field filters), between BACKGROUND and BACKGROUND_PLUS. */
    public static final Color LIST_STRIPE = new Color(44, 46, 56);
    /** The list row under the mouse. */
    public static final Color LIST_HOVER = BACKGROUND_PLUS;

    public static final Color BACKGROUND_WARNING = new Color(58, 42, 44);

    public static final Color CURRENT_LINE = new Color(68, 71, 90);
    public static final Color FOREGROUND = new Color(0xf0, 0xf0, 0xe8);
    // 248 = 0xf8
    // 242 = 0x
    public static final Color GREY = new Color(128, 128, 124);
    public static final Color GREY_DARK_TRANSLUCENT = new Color(100, 100, 98, 100);

    public static final Color COMMENT = new Color(98, 114, 164);
    public static final Color CYAN = new Color(139, 233, 253);
    public static final Color GREEN = new Color(0x62, 0xb5, 0x43);
    public static final Color GREEN_ANDROID_TEXT = new Color(0xa4, 0xc6, 0x39);
    public static final Color PURPLE = new Color(189, 147, 249);
    public static final Color RED = new Color(0xc0, 0x25, 0x05);
    /** Light red of the Exclude actions, {@link #RED}'s hue at OKLCH L 0.78: readable on dark backgrounds. */
    public static final Color EXCLUDE_ACCENT = accent(RED, 0.78f, 0.14f);
    public static final Color PINK = new Color(0xf9, 0x8b, 0x9e);
    public static final Color YELLOW = new Color(221, 220, 110);
    public static final Color CARET = YELLOW;;
    public static final Color ORANGE = new Color(0xf4, 0xaf, 0x3d);
    public static final Color BLUE_TEXT = new Color(0x40, 0xb6, 0xe0);
    public static final Color BLUE = new Color(0x37, 0x5a, 0x81);
    public static final Color MIDNIGHT_BLUE = new Color(33, 66, 107); //
    /** Light blue of the Narrow actions, {@link #MIDNIGHT_BLUE}'s hue at OKLCH L 0.80: readable on dark backgrounds. */
    public static final Color NARROW_ACCENT = accent(MIDNIGHT_BLUE, 0.80f, 0.10f);
    /** Light blue-grey of the Search action, {@link #COMMENT}'s hue and chroma at OKLCH L 0.78. */
    public static final Color SEARCH_ACCENT = accent(COMMENT, 0.78f, 0.08f);
    /** Muted dark amber for the Matches view status bar — keeps light text readable on Darcula. */
    public static final Color MATCHES_BAR = new Color(77, 60, 28);
    /** Amber accent of the Matches view: edge stripe and date rules. */
    public static final Color MATCHES_ACCENT = new Color(0xe8, 0xa3, 0x3d);
    /** Dim amber for gap hairlines between events in the Matches view. */
    public static final Color MATCHES_ACCENT_DIM = new Color(0x7a, 0x5b, 0x2e);
    /** Log background nudged about 6 % toward {@link #MATCHES_ACCENT} in OKLab: a subtle mode tint. */
    public static final Color BACKGROUND_MATCHES = OKLab.oklabToColor(mixOklab(BACKGROUND, MATCHES_ACCENT, 0.06f));
    /** Teal of the bookmarks: the row background of bookmarked events and the bookmarks view accents. */
    public static final Color BOOKMARK = new Color(0x3f, 0xb6, 0xa8);
    /** Log background moved about 18 % toward {@link #BOOKMARK} in OKLab: the row fill of bookmarked events (Log and Matches views). */
    public static final Color BOOKMARK_ROW = OKLab.oklabToColor(mixOklab(BACKGROUND, BOOKMARK, 0.18f));
    /** Cursor line on a bookmarked row: {@link #CURRENT_LINE} moved 40 % toward {@link #BOOKMARK} in OKLab, so it stays teal. */
    public static final Color BOOKMARK_CURRENT_LINE = OKLab.oklabToColor(mixOklab(CURRENT_LINE, BOOKMARK, 0.4f));
    /** Fill of the default key button: the footer background moved 10 % toward {@link #ORANGE} in OKLab. */
    public static final Color KEY_BUTTON_DEFAULT = OKLab.oklabToColor(mixOklab(BACKGROUND_OUTSIDE, ORANGE, 0.10f));
    /** Hover/press fill of the default key button: 18 % toward {@link #ORANGE}. */
    public static final Color KEY_BUTTON_DEFAULT_HOVER = OKLab
            .oklabToColor(mixOklab(BACKGROUND_OUTSIDE, ORANGE, 0.18f));
    /** Log background nudged about 6 % toward {@link #BOOKMARK} in OKLab: the bookmarks view tint. */
    public static final Color BACKGROUND_BOOKMARKS = OKLab.oklabToColor(mixOklab(BACKGROUND, BOOKMARK, 0.06f));
    /** Dim teal for gap hairlines between events in the Bookmarks view. */
    public static final Color BOOKMARK_DIM = new Color(0x2d, 0x6d, 0x66);
    /** Muted dark teal for the Bookmarks view status bar, the counterpart of {@link #MATCHES_BAR}. */
    public static final Color BOOKMARKS_BAR = new Color(28, 66, 62);
    /** Log background nudged about 6 % toward {@link #BLUE_TEXT} in OKLab: the event details tint. */
    public static final Color BACKGROUND_DETAILS = OKLab.oklabToColor(mixOklab(BACKGROUND, BLUE_TEXT, 0.06f));
    public static final Color DARK_RETRO_VGA_BLUE = new Color(0x04, 0x1f, 0x63); // #1e1ea2

    // Additional colors for better login styling
    public static final Color BORDER_HIGHLIGHT = new Color(0x6a, 0x9f, 0xd9);

    //  #3c3f41

    // almost black - dark-mode
    public static final Color BLACK = new Color(0x10, 0x10, 0x10);
    public static final Color G0 = new Color(0x1e, 0x20, 0x21);
    public static final Color G1 = new Color(0x21, 0x21, 0x21);
    public static final Color G2 = new Color(0x30, 0x32, 0x34);
    public static final Color G3 = new Color(0x3c, 0x3f, 0x41);
    public static final Color G4 = new Color(0x46, 0x49, 0x4b);
    public static final Color G5 = new Color(0x4e, 0x50, 0x52);
    public static final Color G6 = new Color(0x5c, 0x5e, 0x6a);
    public static final Color G7 = new Color(0x8c, 0x8e, 0x9a);
    public static final Color G8 = new Color(0xcc, 0xce, 0xda);
    public static final Color G9 = new Color(0xec, 0xee, 0xfa);

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
