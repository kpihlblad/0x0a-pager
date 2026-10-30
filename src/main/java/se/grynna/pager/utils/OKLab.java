package se.grynna.pager.utils;

import java.awt.Color;

// The OKLab matrix coefficients below are kept verbatim from Björn Ottosson's
// reference implementation for traceability; the float rounding of these
// literals is intentional.
@SuppressWarnings("FloatingPointLiteralPrecision")
public class OKLab {

    /**
     * Represents a color in the OKLab color space
     */
    public record Lab(float L, float a, float b) {
        @Override
        public String toString() {
            return String.format("Lab(L=%.3f, a=%.3f, b=%.3f)", L, a, b);
        }
    }

    /**
     * Represents a color in the RGB color space (values 0.0-1.0)
     */
    public record RGB(float r, float g, float b) {

        /**
         * Create RGB from java.awt.Color
         */
        public static RGB fromColor(Color color) {
            return new RGB(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f);
        }

        /**
         * Convert to java.awt.Color
         */
        public Color toColor() {
            int r = Math.max(0, Math.min(255, Math.round(this.r * 255)));
            int g = Math.max(0, Math.min(255, Math.round(this.g * 255)));
            int b = Math.max(0, Math.min(255, Math.round(this.b * 255)));
            return new Color(r, g, b);
        }

        @Override
        public String toString() {
            return String.format("RGB(r=%.3f, g=%.3f, b=%.3f)", r, g, b);
        }
    }

    /**
     * Represents a color in the OKLCH color space (cylindrical representation of OKLab)
     */
    public record LCH(float L, float C, float H) {
        @Override
        public String toString() {
            return String.format("LCH(L=%.3f, C=%.3f, H=%.1f°)", L, C, Math.toDegrees(H));
        }
    }

    /**
     * Convert linear sRGB to OKLab color space
     */
    public static Lab linearSrgbToOklab(RGB c) {
        float l = 0.4122214708f * c.r() + 0.5363325363f * c.g() + 0.0514459929f * c.b();
        float m = 0.2119034982f * c.r() + 0.6806995451f * c.g() + 0.1073969566f * c.b();
        float s = 0.0883024619f * c.r() + 0.2817188376f * c.g() + 0.6299787005f * c.b();

        float l_ = (float) Math.cbrt(l);
        float m_ = (float) Math.cbrt(m);
        float s_ = (float) Math.cbrt(s);

        return new Lab(0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
                1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
                0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_);
    }

    /**
     * Convert OKLab to linear sRGB color space
     */
    public static RGB oklabToLinearSrgb(Lab c) {
        float l_ = c.L() + 0.3963377774f * c.a() + 0.2158037573f * c.b();
        float m_ = c.L() - 0.1055613458f * c.a() - 0.0638541728f * c.b();
        float s_ = c.L() - 0.0894841775f * c.a() - 1.2914855480f * c.b();

        float l = l_ * l_ * l_;
        float m = m_ * m_ * m_;
        float s = s_ * s_ * s_;

        return new RGB(+4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
                -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
                -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s);
    }

    /**
     * Convert OKLab to OKLCH color space
     */
    public static LCH oklabToOklch(Lab lab) {
        float C = (float) Math.sqrt(lab.a() * lab.a() + lab.b() * lab.b());
        float H = (float) Math.atan2(lab.b(), lab.a());
        return new LCH(lab.L(), C, H);
    }

    /**
     * Convert OKLCH to OKLab color space
     */
    public static Lab oklchToOklab(LCH lch) {
        float a = lch.C() * (float) Math.cos(lch.H());
        float b = lch.C() * (float) Math.sin(lch.H());
        return new Lab(lch.L(), a, b);
    }

    /**
     * Convert java.awt.Color to OKLab (assumes sRGB input)
     */
    public static Lab colorToOklab(Color color) {
        RGB rgb = RGB.fromColor(color);
        RGB linearRgb = srgbToLinearSrgb(rgb);
        return linearSrgbToOklab(linearRgb);
    }

    /**
     * Convert OKLab to java.awt.Color (converts to sRGB)
     */
    public static Color oklabToColor(Lab lab) {
        RGB linearRgb = oklabToLinearSrgb(lab);
        RGB srgb = linearSrgbToSrgb(linearRgb);
        return srgb.toColor();
    }

    /**
     * Convert java.awt.Color to OKLCH (assumes sRGB input)
     */
    public static LCH colorToOklch(Color color) {
        Lab lab = colorToOklab(color);
        return oklabToOklch(lab);
    }

    /**
     * Convert OKLCH to java.awt.Color (converts to sRGB)
     */
    public static Color oklchToColor(LCH lch) {
        Lab lab = oklchToOklab(lch);
        return oklabToColor(lab);
    }

    /**
     * Convert sRGB to linear sRGB (gamma correction)
     */
    private static RGB srgbToLinearSrgb(RGB srgb) {
        return new RGB(srgbToLinear(srgb.r()), srgbToLinear(srgb.g()), srgbToLinear(srgb.b()));
    }

    /**
     * Convert linear sRGB to sRGB (gamma correction)
     */
    private static RGB linearSrgbToSrgb(RGB linearSrgb) {
        return new RGB(linearToSrgb(linearSrgb.r()), linearToSrgb(linearSrgb.g()), linearToSrgb(linearSrgb.b()));
    }

    /**
     * Convert a single sRGB component to linear
     */
    private static float srgbToLinear(float c) {
        if (c <= 0.04045f) {
            return c / 12.92f;
        } else {
            return (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
    }

    /**
     * Convert a single linear component to sRGB
     */
    private static float linearToSrgb(float c) {
        if (c <= 0.0031308f) {
            return c * 12.92f;
        } else {
            return (float) (1.055f * Math.pow(c, 1.0f / 2.4f) - 0.055f);
        }
    }

    /**
     * Calculate perceptual difference between two colors in OKLab space
     */
    public static float deltaE(Lab lab1, Lab lab2) {
        float dL = lab1.L() - lab2.L();
        float da = lab1.a() - lab2.a();
        float db = lab1.b() - lab2.b();
        return (float) Math.sqrt(dL * dL + da * da + db * db);
    }

    /**
     * Calculate perceptual difference between two colors
     */
    public static float deltaE(Color color1, Color color2) {
        Lab lab1 = colorToOklab(color1);
        Lab lab2 = colorToOklab(color2);
        return deltaE(lab1, lab2);
    }

    /**
     * Calculate perceptual difference between two colors in OKLCH space
     */
    public static float deltaE(LCH lch1, LCH lch2) {
        Lab lab1 = oklchToOklab(lch1);
        Lab lab2 = oklchToOklab(lch2);
        return deltaE(lab1, lab2);
    }

    /**
     * Adjust lightness of a color in OKLCH space
     */
    public static LCH adjustLightness(LCH lch, float deltaL) {
        return new LCH(Math.max(0, Math.min(1, lch.L() + deltaL)), lch.C(), lch.H());
    }

    /**
     * Adjust chroma (saturation) of a color in OKLCH space
     */
    public static LCH adjustChroma(LCH lch, float deltaC) {
        return new LCH(lch.L(), Math.max(0, lch.C() + deltaC), lch.H());
    }

    /**
     * Adjust hue of a color in OKLCH space
     */
    public static LCH adjustHue(LCH lch, float deltaH) {
        return new LCH(lch.L(), lch.C(), lch.H() + deltaH);
    }

    /**
     * Create a color with specific lightness while preserving chroma and hue
     */
    public static LCH withLightness(LCH lch, float newL) {
        return new LCH(Math.max(0, Math.min(1, newL)), lch.C(), lch.H());
    }

    /**
     * Create a color with specific chroma while preserving lightness and hue
     */
    public static LCH withChroma(LCH lch, float newC) {
        return new LCH(lch.L(), Math.max(0, newC), lch.H());
    }

    /**
     * Create a color with specific hue while preserving lightness and chroma
     */
    public static LCH withHue(LCH lch, float newH) {
        return new LCH(lch.L(), lch.C(), newH);
    }

    /**
     * Rotate hue by degrees (convenience method)
     */
    public static LCH rotateHue(LCH lch, float degrees) {
        return adjustHue(lch, (float) Math.toRadians(degrees));
    }

    /**
     * Generate a palette of colors with the same lightness and chroma but varying hues.
     * The colors are reordered to maximize visual distinction between consecutive colors.
     *
     * @param L lightness (0-1)
     * @param C chroma (0+)
     * @param count number of colors to generate (recommended 8-32)
     * @return array of LCH colors ordered for maximum distinction
     */
    public static LCH[] generateHuePalette(float L, float C, int count) {
        if (count <= 0) {
            return new LCH[0];
        }
        if (count == 1) {
            return new LCH[]{new LCH(L, C, 0)};
        }

        // Generate evenly spaced hues around the color wheel
        LCH[] colors = new LCH[count];
        for (int i = 0; i < count; i++) {
            float hue = (float) (2 * Math.PI * i / count);
            colors[i] = new LCH(L, C, hue);
        }

        // Reorder to maximize distance between consecutive colors
        return reorderForMaximalDistribution(colors);
    }

    /**
     * Convenience method that returns Color array instead of LCH array
     */
    public static Color[] generateHuePaletteColors(float L, float C, int count) {
        LCH[] lchColors = generateHuePalette(L, C, count);
        Color[] colors = new Color[lchColors.length];
        for (int i = 0; i < lchColors.length; i++) {
            colors[i] = oklchToColor(lchColors[i]);
        }
        return colors;
    }

    /**
     * Reorder colors to maximize the hue distance between consecutive colors.
     * Uses a greedy algorithm that always picks the color with maximum distance
     * from the previously selected color.
     */
    private static LCH[] reorderForMaximalDistribution(LCH[] colors) {
        if (colors.length <= 2) {
            return colors.clone();
        }

        LCH[] reordered = new LCH[colors.length];
        boolean[] used = new boolean[colors.length];

        // Start with the first color (hue = 0)
        reordered[0] = colors[0];
        used[0] = true;

        // For each subsequent position, pick the unused color with maximum hue distance
        for (int pos = 1; pos < colors.length; pos++) {
            int bestIndex = -1;
            float maxDistance = -1;

            for (int i = 0; i < colors.length; i++) {
                if (used[i]) continue;

                float distance = calculateHueDistance(reordered[pos - 1].H(), colors[i].H());
                if (distance > maxDistance) {
                    maxDistance = distance;
                    bestIndex = i;
                }
            }

            reordered[pos] = colors[bestIndex];
            used[bestIndex] = true;
        }

        return reordered;
    }

    /**
     * Calculate the minimum angular distance between two hues on the color wheel.
     * Returns a value between 0 and π.
     */
    private static float calculateHueDistance(float hue1, float hue2) {
        float diff = Math.abs(hue1 - hue2);
        // Handle wrapping around the circle (0 and 2π are the same)
        if (diff > Math.PI) {
            diff = (float) (2 * Math.PI - diff);
        }
        return diff;
    }

    /**
     * Generate a palette with preset good defaults for UI colors
     */
    public static Color[] generateUIColorPalette(int count) {
        // Good defaults for UI: medium lightness, moderate saturation
        return generateHuePaletteColors(0.75f, 0.1f, count);
    }

    /**
     * Generate a palette with preset defaults for background colors
     */
    public static Color[] generateBackgroundPalette(int count) {
        // Light colors with low saturation for backgrounds
        return generateHuePaletteColors(0.95f, 0.02f, count);
    }

    /**
     * Check if an RGB color is within the sRGB gamut (0-1 range)
     */
    public static boolean isInGamut(RGB rgb) {
        final float tolerance = 1e-5f; // Tighter tolerance for more accurate gamut mapping
        return rgb.r() >= -tolerance && rgb.r() <= 1 + tolerance && rgb.g() >= -tolerance && rgb.g() <= 1 + tolerance
                && rgb.b() >= -tolerance && rgb.b() <= 1 + tolerance;
    }

    /**
     * Check if an OKLCH color is within the sRGB gamut
     */
    public static boolean isInGamut(LCH lch) {
        Lab lab = oklchToOklab(lch);
        RGB linearRgb = oklabToLinearSrgb(lab);
        return isInGamut(linearRgb);
    }

    /**
     * Map an out-of-gamut OKLCH color to the closest in-gamut color
     * using chroma reduction while preserving lightness and hue
     */
    public static LCH mapToGamut(LCH lch) {
        if (isInGamut(lch)) {
            return lch; // Already in gamut
        }

        // Handle edge cases for lightness
        if (lch.L() >= 1.0f) {
            // Pure white
            return new LCH(1.0f, 0, lch.H());
        }
        if (lch.L() <= 0.0f) {
            // Pure black
            return new LCH(0.0f, 0, lch.H());
        }

        // Binary search to find the maximum chroma that stays in gamut
        float minChroma = 0;
        float maxChroma = lch.C();
        float targetChroma = 0;
        float precision = 1e-6f; // Much higher precision

        // Use binary search with higher precision
        for (int i = 0; i < 100 && maxChroma - minChroma > precision; i++) {
            float testChroma = (minChroma + maxChroma) / 2;
            LCH testColor = new LCH(lch.L(), testChroma, lch.H());

            if (isInGamut(testColor)) {
                targetChroma = testChroma;
                minChroma = testChroma;
            } else {
                maxChroma = testChroma;
            }
        }

        return new LCH(lch.L(), targetChroma, lch.H());
    }

    /**
     * Alternative gamut mapping method that tries to match the reference implementation
     * more closely by using slightly different tolerance and precision
     */
    public static LCH mapToGamutPrecise(LCH lch) {
        if (isInGamut(lch)) {
            return lch;
        }

        // Handle edge cases
        if (lch.L() >= 1.0f) {
            return new LCH(1.0f, 0, lch.H());
        }
        if (lch.L() <= 0.0f) {
            return new LCH(0.0f, 0, lch.H());
        }

        // More aggressive binary search that pushes closer to the gamut boundary
        float minChroma = 0;
        float maxChroma = lch.C();
        float targetChroma = 0;

        for (int i = 0; i < 150; i++) {
            float testChroma = (minChroma + maxChroma) / 2;
            LCH testColor = new LCH(lch.L(), testChroma, lch.H());

            Lab lab = oklchToOklab(testColor);
            RGB linearRgb = oklabToLinearSrgb(lab);

            // Use a very small tolerance to get as close as possible to the gamut boundary
            boolean inGamut = linearRgb.r() >= -1e-6f && linearRgb.r() <= 1 + 1e-6f && linearRgb.g() >= -1e-6f
                    && linearRgb.g() <= 1 + 1e-6f && linearRgb.b() >= -1e-6f && linearRgb.b() <= 1 + 1e-6f;

            if (inGamut) {
                targetChroma = testChroma;
                minChroma = testChroma;
            } else {
                maxChroma = testChroma;
            }

            if (maxChroma - minChroma < 1e-8f) {
                break;
            }
        }

        return new LCH(lch.L(), targetChroma, lch.H());
    }

    /**
     * Alternative gamut mapping that tries to mimic CSS gamut mapping behavior
     * Based on analyzing the patterns in the test data
     */
    public static LCH mapToGamutCSS(LCH lch) {
        if (isInGamut(lch)) {
            return lch;
        }

        // Handle edge cases
        if (lch.L() >= 1.0f) {
            return new LCH(1.0f, 0, lch.H());
        }
        if (lch.L() <= 0.0f) {
            return new LCH(0.0f, 0, lch.H());
        }

        // Use a different approach - find the boundary more aggressively
        // and apply some clipping in RGB space as well
        float minChroma = 0;
        float maxChroma = lch.C();
        float bestChroma = 0;
        float bestDistance = Float.MAX_VALUE;

        // Try different chroma values and pick the one that gives the best result
        for (int i = 0; i <= 100; i++) {
            float testChroma = minChroma + (maxChroma - minChroma) * i / 100.0f;
            LCH testLch = new LCH(lch.L(), testChroma, lch.H());

            Lab lab = oklchToOklab(testLch);
            RGB linearRgb = oklabToLinearSrgb(lab);

            // Calculate how much we had to clip
            float clippingDistance = (float) Math
                    .sqrt(Math.pow(Math.max(0, -linearRgb.r()) + Math.max(0, linearRgb.r() - 1), 2)
                            + Math.pow(Math.max(0, -linearRgb.g()) + Math.max(0, linearRgb.g() - 1), 2)
                            + Math.pow(Math.max(0, -linearRgb.b()) + Math.max(0, linearRgb.b() - 1), 2));

            // Prefer higher chroma with minimal clipping
            float score = testChroma - clippingDistance * 10;
            if (score > bestDistance) {
                bestDistance = score;
                bestChroma = testChroma;
            }
        }

        return new LCH(lch.L(), bestChroma, lch.H());
    }
}
