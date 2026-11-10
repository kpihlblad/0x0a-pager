package se.grynna.pager.ui.stars;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The 24x21 Commodore 64 "Up, Up, and Away!" hot air balloon sprite from Chapter 6
 * of the Commodore 64 User's Guide, stored as the original 63-byte VIC-II sprite
 * bitmap and painted pixel-by-pixel by {@code LineRenderer}.
 */
public final class C64BalloonSprite {

    /** C64 light blue / cyan border colour (#70a4b2). */
    public static final Color COLOR = new Color(0x70a4b2);

    /** Width of the VIC-II sprite in sprite pixels (3 bytes per row). */
    public static final int PIXEL_WIDTH = 24;
    /** Height of the VIC-II sprite in sprite pixels. */
    public static final int PIXEL_HEIGHT = 21;

    /** Number of monospace text rows spanned by the sprite tile grid. */
    public static final int TILE_ROWS = 3;
    /** Number of monospace character columns spanned by the sprite tile grid. */
    public static final int TILE_COLS = 6;

    /**
     * Private Use Area base codepoint for row 0 of the sprite; rows 0..2 use
     * {@code ROW_BASE_CHAR + tileRow} at the leftmost column of the 6-column footprint.
     */
    public static final char ROW_BASE_CHAR = '\uE064';

    /**
     * Original 63-byte sprite data from lines 200-240 of "Up, Up, and Away!":
     * 21 rows of 3 bytes (MSB first).
     */
    private static final int[] SPRITE_BYTES = {
            0, 127, 0,
            1, 255, 192,
            3, 255, 224,
            3, 231, 224,
            7, 217, 240,
            7, 223, 240,
            7, 217, 240,
            3, 231, 224,
            3, 255, 224,
            3, 255, 224,
            2, 255, 160,
            1, 127, 64,
            1, 62, 64,
            0, 156, 128,
            0, 156, 128,
            0, 73, 0,
            0, 73, 0,
            0, 62, 0,
            0, 62, 0,
            0, 62, 0,
            0, 28, 0
    };

    private C64BalloonSprite() {}

    /** True when {@code ch} is one of the 3 row marker codepoints for the C64 balloon sprite. */
    public static boolean isRowMarker(char ch) {
        return ch >= ROW_BASE_CHAR && ch < ROW_BASE_CHAR + TILE_ROWS;
    }

    /** Returns the sprite tile row (0..2) encoded by {@code ch}, or -1 if not a marker. */
    public static int tileRow(char ch) {
        return isRowMarker(ch) ? ch - ROW_BASE_CHAR : -1;
    }

    /** True when the sprite pixel at {@code (px, py)} in {@code [0, 24) x [0, 21)} is set. */
    public static boolean isPixelSet(int px, int py) {
        if (px < 0 || px >= PIXEL_WIDTH || py < 0 || py >= PIXEL_HEIGHT) {
            return false;
        }
        int byteValue = SPRITE_BYTES[py * 3 + (px >> 3)];
        int bitMask = 1 << (7 - (px & 7));
        return (byteValue & bitMask) != 0;
    }

    /**
     * Multi-line placement template for {@link ASCIINightSkyGenerator}: 3 rows of 6 columns,
     * with the row marker in column 0 and non-overlapping {@code ♦} placeholders in columns 1..5.
     */
    public static String skyObjectTemplate() {
        String pad = "♦".repeat(TILE_COLS - 1);
        StringBuilder sb = new StringBuilder(TILE_ROWS * (TILE_COLS + 1));
        for (int r = 0; r < TILE_ROWS; r++) {
            if (r > 0) {
                sb.append('\n');
            }
            sb.append((char) (ROW_BASE_CHAR + r)).append(pad);
        }
        return sb.toString();
    }

    /**
     * Paints any C64 balloon sprite row slices present on {@code text} (which is already
     * truncated/shifted by {@code horizontalScroll}).
     */
    public static void paintLineSlices(Graphics2D g2d, String text, int textStartX, int rowY, int charWidth,
            int charHeight) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int col = 0, n = text.length(); col < n; col++) {
            int tileRow = tileRow(text.charAt(col));
            if (tileRow >= 0) {
                paintTileRow(g2d, tileRow, textStartX + col * charWidth, rowY, charWidth, charHeight);
            }
        }
    }

    /**
     * Replaces any sprite row markers in {@code text} with spaces so {@code Graphics2D.drawString}
     * never paints a missing-glyph box under the pixel-rendered sprite.
     */
    public static String stripRowMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        for (int i = 0, n = text.length(); i < n; i++) {
            if (isRowMarker(text.charAt(i))) {
                char[] chars = text.toCharArray();
                for (int j = i; j < n; j++) {
                    if (isRowMarker(chars[j])) {
                        chars[j] = ' ';
                    }
                }
                return new String(chars);
            }
        }
        return text;
    }

    /**
     * Paints tile row {@code tileRow} (0..2) of the 24x21 sprite at {@code (x, rowY)}.
     * The full 3-row tile has height {@code 3 * charHeight} and width {@code 6 * charWidth};
     * each sprite pixel is mapped to crisp integer coordinates so adjacent rows meet seamlessly.
     */
    public static void paintTileRow(Graphics2D g2d, int tileRow, int x, int rowY, int charWidth, int charHeight) {
        if (tileRow < 0 || tileRow >= TILE_ROWS || charWidth <= 0 || charHeight <= 0) {
            return;
        }
        int totalHeight = TILE_ROWS * charHeight;
        int totalWidth = TILE_COLS * charWidth;
        int spriteTopY = rowY - tileRow * charHeight;

        Object prevAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2d.setColor(COLOR);
        try {
            int pyStart = (tileRow * PIXEL_HEIGHT) / TILE_ROWS;
            int pyEnd = ((tileRow + 1) * PIXEL_HEIGHT) / TILE_ROWS;
            for (int py = pyStart; py < pyEnd; py++) {
                int y0 = spriteTopY + (py * totalHeight) / PIXEL_HEIGHT;
                int y1 = spriteTopY + ((py + 1) * totalHeight) / PIXEL_HEIGHT;
                int h = Math.max(1, y1 - y0);
                int px = 0;
                while (px < PIXEL_WIDTH) {
                    if (!isPixelSet(px, py)) {
                        px++;
                        continue;
                    }
                    int runStart = px;
                    while (px < PIXEL_WIDTH && isPixelSet(px, py)) {
                        px++;
                    }
                    int x0 = x + (runStart * totalWidth) / PIXEL_WIDTH;
                    int x1 = x + (px * totalWidth) / PIXEL_WIDTH;
                    g2d.fillRect(x0, y0, Math.max(1, x1 - x0), h);
                }
            }
        } finally {
            if (prevAA != null) {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAA);
            }
        }
    }
}
