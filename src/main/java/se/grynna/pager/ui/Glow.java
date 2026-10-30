package se.grynna.pager.ui;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * A soft glare around a rectangle: the glow the minimap draws around the visible range, generalised. Pure, so
 * it can be rendered once and cached by the caller.
 */
public final class Glow {

    private Glow() {}

    /**
     * The glow around a {@code width} x {@code height} rectangle, in an image {@code reach} px larger on every
     * side; the rectangle sits at offset ({@code reach}, {@code reach}) and is left clear. A pixel at Euclidean
     * distance {@code d} (&gt; 0) from the rectangle gets {@code color} at alpha {@code peak * (1 - d / reach)^2}
     * when {@code d < reach}, and is transparent otherwise. Negative sizes count as 0.
     */
    public static BufferedImage render(int width, int height, int reach, Color color, float peak) {
        int w = Math.max(0, width);
        int h = Math.max(0, height);
        int r = Math.max(0, reach);
        BufferedImage image = new BufferedImage(Math.max(1, w + 2 * r), Math.max(1, h + 2 * r),
                BufferedImage.TYPE_INT_ARGB);
        if (r == 0) {
            return image;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            int dy = Math.max(0, Math.max(r - y, y - (r + h - 1)));
            for (int x = 0; x < image.getWidth(); x++) {
                int dx = Math.max(0, Math.max(r - x, x - (r + w - 1)));
                if (dx == 0 && dy == 0) {
                    continue;
                }
                double dist = Math.hypot(dx, dy);
                if (dist < r) {
                    double fade = 1 - dist / r;
                    int alpha = Math.round(255 * (float) (peak * fade * fade));
                    image.setRGB(x, y, withAlpha(color, alpha).getRGB());
                }
            }
        }
        return image;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
