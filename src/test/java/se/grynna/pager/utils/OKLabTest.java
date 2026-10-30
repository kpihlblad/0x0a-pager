package se.grynna.pager.utils;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

public class OKLabTest {

    private static final float TOLERANCE = 2.0f; // Allow 2 RGB units difference (out of 255)

    @Test
    public void testOklchToHexConversions() {
        // Test data: OKLCH values and their expected hex colors
        TestCase[] testCases = {new TestCase(0.939f, 0.0242f, 0f, "#fae5ea"),
                new TestCase(0.939f, 0.0242f, 15f, "#fbe5e6"), new TestCase(0.939f, 0.0242f, 30f, "#fbe5e1"),
                new TestCase(0.939f, 0.0242f, 60f, "#f8e7db"), new TestCase(0.939f, 0.0242f, 90f, "#f1ebd9"),
                new TestCase(0.939f, 0.0242f, 120f, "#e8eedc"), new TestCase(0.939f, 0.0242f, 150f, "#e0f0e2"),
                new TestCase(0.939f, 0.0242f, 180f, "#daf1eb"), new TestCase(0.939f, 0.0242f, 210f, "#d9f0f4"),
                new TestCase(0.939f, 0.0242f, 240f, "#ddedfa"), new TestCase(0.939f, 0.0242f, 270f, "#e4ebfc"),
                new TestCase(0.939f, 0.0242f, 300f, "#ede8f9"), new TestCase(0.939f, 0.0242f, 330f, "#f5e5f3"),
                new TestCase(0.939f, 0.0242f, 360f, "#fae5ea"), // Should match 0°
                new TestCase(0.939f, 0.0614f, 123.82f, "#e2f2c6")};

        for (TestCase testCase : testCases) {
            testOklchConversion(testCase);
        }
    }

    private void testOklchConversion(TestCase testCase) {
        // Convert degrees to radians for our LCH record
        float hueRadians = (float) Math.toRadians(testCase.hueDegrees);

        // Create OKLCH color
        OKLab.LCH lch = new OKLab.LCH(testCase.L, testCase.C, hueRadians);

        // Convert to Color
        Color actualColor = OKLab.oklchToColor(lch);

        // Parse expected hex color
        Color expectedColor = parseHexColor(testCase.expectedHex);

        // Compare colors with tolerance
        assertColorEquals(expectedColor, actualColor, testCase.toString());

        // Print results for verification
        System.out.printf("OKLCH(%.3f, %.4f, %.2f°) -> Expected: %s, Actual: %s, Delta: %.2f\n", testCase.L, testCase.C,
                testCase.hueDegrees, testCase.expectedHex, colorToHex(actualColor),
                colorDistance(expectedColor, actualColor));
    }

    @Test
    public void testRoundTripConversion() {
        // Test that Color -> OKLCH -> Color preserves the original color
        Color[] testColors = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK, Color.YELLOW, Color.CYAN,
                Color.MAGENTA, Color.GRAY};

        for (Color originalColor : testColors) {
            OKLab.LCH lch = OKLab.colorToOklch(originalColor);
            Color roundTripColor = OKLab.oklchToColor(lch);

            assertColorEquals(originalColor, roundTripColor,
                    "Round trip conversion failed for " + colorToHex(originalColor));
        }
    }

    @Test
    public void testHueWrapping() {
        // Test that 0° and 360° produce the same result
        OKLab.LCH lch0 = new OKLab.LCH(0.5f, 0.1f, 0f);
        OKLab.LCH lch360 = new OKLab.LCH(0.5f, 0.1f, (float) Math.toRadians(360));

        Color color0 = OKLab.oklchToColor(lch0);
        Color color360 = OKLab.oklchToColor(lch360);

        assertColorEquals(color0, color360, "0° and 360° should produce the same color");
    }

    @Test
    public void testColorManipulation() {
        // Test OKLCH color manipulation utilities
        Color baseColor = parseHexColor("#ff0000"); // Red
        OKLab.LCH baseLch = OKLab.colorToOklch(baseColor);

        // Test lightness adjustment
        OKLab.LCH lighter = OKLab.adjustLightness(baseLch, 0.2f);
        assertTrue(lighter.L() > baseLch.L(), "Lightness should increase");

        // Test chroma adjustment
        OKLab.LCH moreSaturated = OKLab.adjustChroma(baseLch, 0.1f);
        assertTrue(moreSaturated.C() > baseLch.C(), "Chroma should increase");

        // Test hue rotation
        OKLab.LCH rotated = OKLab.rotateHue(baseLch, 180f);
        float expectedHue = baseLch.H() + (float) Math.toRadians(180);
        assertEquals(expectedHue, rotated.H(), 0.001f, "Hue should be rotated by 180°");
    }

    @Test
    public void testHuePaletteGeneration() {
        // Test palette generation with different counts
        int[] testCounts = {1, 4, 8, 12, 16};

        for (int count : testCounts) {
            OKLab.LCH[] palette = OKLab.generateHuePalette(0.7f, 0.15f, count);
            assertEquals(count, palette.length, "Palette should have correct count");

            // All colors should have the same L and C
            for (OKLab.LCH color : palette) {
                assertEquals(0.7f, color.L(), 0.001f, "All colors should have same lightness");
                assertEquals(0.15f, color.C(), 0.001f, "All colors should have same chroma");
            }

            // Test that colors are reasonably distributed
            if (count > 1) {
                testPaletteDistribution(palette);
            }
        }
    }

    @Test
    public void testPaletteDistribution() {
        // Generate a larger palette to test distribution quality
        OKLab.LCH[] palette = OKLab.generateHuePalette(0.75f, 0.1f, 8);

        System.out.println("\nPalette distribution test (8 colors):");
        for (int i = 0; i < palette.length; i++) {
            Color color = OKLab.oklchToColor(palette[i]);
            System.out.printf("Color %d: %s (hue: %.1f°) -> %s\n", i, palette[i], Math.toDegrees(palette[i].H()),
                    colorToHex(color));
        }

        // Test that consecutive colors have reasonable hue distance
        float totalDistance = 0;
        float minDistance = Float.MAX_VALUE;

        for (int i = 1; i < palette.length; i++) {
            float distance = calculateHueDistance(palette[i - 1].H(), palette[i].H());
            totalDistance += distance;
            minDistance = Math.min(minDistance, distance);
        }

        float avgDistance = totalDistance / (palette.length - 1);
        System.out.printf("Average hue distance: %.2f°, Min distance: %.2f°\n", Math.toDegrees(avgDistance),
                Math.toDegrees(minDistance));

        // Minimum distance should be reasonable (not too small)
        assertTrue(Math.toDegrees(minDistance) > 20, "Consecutive colors should have at least 20° hue separation");
    }

    @Test
    public void testPresetPalettes() {
        // Test UI palette
        Color[] uiPalette = OKLab.generateUIColorPalette(6);
        assertEquals(6, uiPalette.length, "UI palette should have correct count");

        // Test background palette
        Color[] bgPalette = OKLab.generateBackgroundPalette(4);
        assertEquals(4, bgPalette.length, "Background palette should have correct count");

        System.out.println("\nUI Palette (6 colors):");
        for (int i = 0; i < uiPalette.length; i++) {
            System.out.printf("UI Color %d: %s\n", i, colorToHex(uiPalette[i]));
        }

        System.out.println("\nBackground Palette (4 colors):");
        for (int i = 0; i < bgPalette.length; i++) {
            System.out.printf("BG Color %d: %s\n", i, colorToHex(bgPalette[i]));
        }
    }

    @Test
    public void testOutOfGamutColors() {
        // Test out-of-gamut colors and their expected fallbacks
        TestCase[] outOfGamutCases = {new TestCase(0.4323f, 0.1847f, 99.47f, "#5f5000"),
                new TestCase(0.8439f, 0.2809f, 215.94f, "#00e4ff"),
                // Additional test cases
                new TestCase(0.3923f, 0.1083f, 196.88f, "#005355"), new TestCase(0.439f, 0.134f, 215.94f, "#005e74"),
                new TestCase(0.439f, 0.1998f, 215.94f, "#005f74"), new TestCase(0.439f, 0.3045f, 215.94f, "#005e74")};

        System.out.println("\nOut-of-gamut color test:");
        for (TestCase testCase : outOfGamutCases) {
            float hueRadians = (float) Math.toRadians(testCase.hueDegrees);
            OKLab.LCH lch = new OKLab.LCH(testCase.L, testCase.C, hueRadians);

            Color actualColor = OKLab.oklchToColor(lch);
            Color gamutMappedColor = OKLab.oklchToColor(OKLab.mapToGamut(lch));
            Color preciseGamutMappedColor = OKLab.oklchToColor(OKLab.mapToGamutPrecise(lch));
            Color cssGamutMappedColor = OKLab.oklchToColor(OKLab.mapToGamutCSS(lch));
            Color expectedColor = parseHexColor(testCase.expectedHex);

            System.out.printf("OKLCH(%.4f, %.4f, %.2f°):\n", testCase.L, testCase.C, testCase.hueDegrees);
            System.out.printf("  Expected: %s\n", testCase.expectedHex);
            System.out.printf("  Clamped:  %s\n", colorToHex(actualColor));
            System.out.printf("  Gamut mapped: %s\n", colorToHex(gamutMappedColor));
            System.out.printf("  Precise mapped: %s\n", colorToHex(preciseGamutMappedColor));
            System.out.printf("  CSS mapped: %s\n", colorToHex(cssGamutMappedColor));

            // Check if the color is actually out of gamut by converting to linear RGB
            OKLab.Lab lab = OKLab.oklchToOklab(lch);
            OKLab.RGB linearRgb = OKLab.oklabToLinearSrgb(lab);

            boolean outOfGamut = linearRgb.r() < 0 || linearRgb.r() > 1 || linearRgb.g() < 0 || linearRgb.g() > 1
                    || linearRgb.b() < 0 || linearRgb.b() > 1;

            System.out.printf("  Linear RGB: (%.3f, %.3f, %.3f) - Out of gamut: %s\n", linearRgb.r(), linearRgb.g(),
                    linearRgb.b(), outOfGamut);

            // Show the gamut-mapped LCH values
            OKLab.LCH mappedLch = OKLab.mapToGamut(lch);
            OKLab.LCH preciseMappedLch = OKLab.mapToGamutPrecise(lch);
            OKLab.LCH cssMappedLch = OKLab.mapToGamutCSS(lch);
            System.out.printf("  Original LCH: L=%.4f, C=%.4f, H=%.2f°\n", lch.L(), lch.C(), Math.toDegrees(lch.H()));
            System.out.printf("  Mapped LCH:   L=%.4f, C=%.4f, H=%.2f°\n", mappedLch.L(), mappedLch.C(),
                    Math.toDegrees(mappedLch.H()));
            System.out.printf("  Precise LCH:  L=%.4f, C=%.4f, H=%.2f°\n", preciseMappedLch.L(), preciseMappedLch.C(),
                    Math.toDegrees(preciseMappedLch.H()));
            System.out.printf("  CSS LCH:      L=%.4f, C=%.4f, H=%.2f°\n", cssMappedLch.L(), cssMappedLch.C(),
                    Math.toDegrees(cssMappedLch.H()));

            // Calculate distances to expected color
            float distanceClamped = colorDistance(expectedColor, actualColor);
            float distanceMapped = colorDistance(expectedColor, gamutMappedColor);
            float distancePrecise = colorDistance(expectedColor, preciseGamutMappedColor);
            float distanceCSS = colorDistance(expectedColor, cssGamutMappedColor);

            System.out.printf("  Distance to expected - Clamped: %.2f, Mapped: %.2f, Precise: %.2f, CSS: %.2f\n",
                    distanceClamped, distanceMapped, distancePrecise, distanceCSS);
            System.out.println();
        }
    }

    @Test
    public void testCloseInGamutColors() {
        // Test colors that should be close to the gamut boundary
        TestCase[] closeColors = {new TestCase(0.5523f, 0.3045f, 277.35f, "#5a44ff"),
                new TestCase(0.5257f, 0.1724f, 49.7f, "#b14400"), new TestCase(0.9257f, 0.1724f, 86.76f, "#ffe179")};

        System.out.println("\nClose-to-gamut color test:");
        for (TestCase testCase : closeColors) {
            float hueRadians = (float) Math.toRadians(testCase.hueDegrees);
            OKLab.LCH lch = new OKLab.LCH(testCase.L, testCase.C, hueRadians);

            Color actualColor = OKLab.oklchToColor(lch);
            Color expectedColor = parseHexColor(testCase.expectedHex);

            boolean inGamut = OKLab.isInGamut(lch);
            float distance = colorDistance(expectedColor, actualColor);

            System.out.printf("OKLCH(%.4f, %.4f, %.2f°): Expected: %s, Actual: %s, In gamut: %s, Distance: %.2f\n",
                    testCase.L, testCase.C, testCase.hueDegrees, testCase.expectedHex, colorToHex(actualColor), inGamut,
                    distance);
        }
    }

    @Test
    public void testGamutMapping() {
        // Test that gamut mapping preserves lightness and hue while reducing chroma
        OKLab.LCH outOfGamut = new OKLab.LCH(0.5f, 0.5f, (float) Math.toRadians(120)); // Very saturated green
        OKLab.LCH mapped = OKLab.mapToGamut(outOfGamut);

        // Lightness and hue should be preserved
        assertEquals(outOfGamut.L(), mapped.L(), 0.001f, "Lightness should be preserved");
        assertEquals(outOfGamut.H(), mapped.H(), 0.001f, "Hue should be preserved");

        // Chroma should be reduced
        assertTrue(mapped.C() < outOfGamut.C(), "Chroma should be reduced");

        // Mapped color should be in gamut
        assertTrue(OKLab.isInGamut(mapped), "Mapped color should be in gamut");

        System.out.println("Gamut mapping test:");
        System.out.printf("  Original: %s (in gamut: %s)\n", outOfGamut, OKLab.isInGamut(outOfGamut));
        System.out.printf("  Mapped:   %s (in gamut: %s)\n", mapped, OKLab.isInGamut(mapped));
    }

    private void testPaletteDistribution(OKLab.LCH[] palette) {
        // Test that the first few colors are well distributed
        if (palette.length >= 4) {
            // First 4 colors should be roughly 90° apart in ideal case
            float dist1 = calculateHueDistance(palette[0].H(), palette[1].H());
            float dist2 = calculateHueDistance(palette[1].H(), palette[2].H());
            float dist3 = calculateHueDistance(palette[2].H(), palette[3].H());

            // All should be reasonably large (> 45° for good distribution)
            assertTrue(Math.toDegrees(dist1) > 45, "First colors should be well separated");
            assertTrue(Math.toDegrees(dist2) > 45, "First colors should be well separated");
            assertTrue(Math.toDegrees(dist3) > 45, "First colors should be well separated");
        }
    }

    private float calculateHueDistance(float hue1, float hue2) {
        float diff = Math.abs(hue1 - hue2);
        if (diff > Math.PI) {
            diff = (float) (2 * Math.PI - diff);
        }
        return diff;
    }

    // Helper methods
    private void assertColorEquals(Color expected, Color actual, String message) {
        float distance = colorDistance(expected, actual);
        assertTrue(distance <= TOLERANCE,
                String.format("%s - Expected: %s, Actual: %s, Distance: %.2f (tolerance: %.1f)", message,
                        colorToHex(expected), colorToHex(actual), distance, TOLERANCE));
    }

    private float colorDistance(Color c1, Color c2) {
        int dr = c1.getRed() - c2.getRed();
        int dg = c1.getGreen() - c2.getGreen();
        int db = c1.getBlue() - c2.getBlue();
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private Color parseHexColor(String hex) {
        // Remove # if present
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }

        if (hex.length() != 6) {
            throw new IllegalArgumentException("Hex color must be 6 characters: " + hex);
        }

        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);

        return new Color(r, g, b);
    }

    private String colorToHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    // Test case data structure
    private static class TestCase {
        final float L;
        final float C;
        final float hueDegrees;
        final String expectedHex;

        TestCase(float L, float C, float hueDegrees, String expectedHex) {
            this.L = L;
            this.C = C;
            this.hueDegrees = hueDegrees;
            this.expectedHex = expectedHex;
        }

        @Override
        public String toString() {
            return String.format("OKLCH(%.3f, %.4f, %.2f°) -> %s", L, C, hueDegrees, expectedHex);
        }
    }
}
