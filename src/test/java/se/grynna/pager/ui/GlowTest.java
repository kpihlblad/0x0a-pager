package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class GlowTest {

    private static final int W = 5;
    private static final int H = 4;
    private static final int REACH = 10;
    private static final Color COLOR = new Color(200, 228, 255);
    private static final float PEAK = 0.55f;

    private static final BufferedImage IMAGE = Glow.render(W, H, REACH, COLOR, PEAK);

    @Test
    void sizeIsRectanglePlusReachOnEverySide() {
        assertThat(IMAGE.getWidth()).isEqualTo(W + 2 * REACH);
        assertThat(IMAGE.getHeight()).isEqualTo(H + 2 * REACH);
    }

    @Test
    void rectangleItselfIsClear() {
        for (int y = REACH; y < REACH + H; y++) {
            for (int x = REACH; x < REACH + W; x++) {
                assertThat(alpha(x, y)).as("(%d,%d)", x, y).isZero();
            }
        }
    }

    @Test
    void alphaFallsOffWithDistance() {
        int y = REACH + 1;
        int atOne = alpha(REACH - 1, y);
        int atHalf = alpha(REACH - REACH / 2, y);
        assertThat(atOne).isGreaterThan(atHalf);
        assertThat(atHalf).isGreaterThan(0);
    }

    @Test
    void alphaMatchesThePeakCurve() {
        int d = 3;
        double fade = 1 - d / (double) REACH;
        int expected = Math.round(255 * (float) (PEAK * fade * fade));
        assertThat(alpha(REACH - d, REACH + 1)).isEqualTo(expected);
    }

    @Test
    void transparentAtAndBeyondReach() {
        int y = REACH + 1;
        assertThat(alpha(0, y)).as("distance = reach").isZero();
        assertThat(alpha(0, 0)).isZero();
    }

    @Test
    void cornerFallsOffByEuclideanDistance() {
        int corner = alpha(REACH - 5, REACH - 5);
        int edge = alpha(REACH - 7, REACH + 1);
        assertThat(corner).as("corner at 7.07 px vs edge at 7 px").isLessThan(edge);
        assertThat(corner).isGreaterThan(0);
    }

    @Test
    void colourRgbIsPreserved() {
        Color pixel = new Color(IMAGE.getRGB(REACH - 1, REACH + 1), true);
        assertThat(pixel.getRed()).isEqualTo(COLOR.getRed());
        assertThat(pixel.getGreen()).isEqualTo(COLOR.getGreen());
        assertThat(pixel.getBlue()).isEqualTo(COLOR.getBlue());
    }

    @Test
    void negativeSizesCountAsZero() {
        BufferedImage image = Glow.render(-3, -2, 4, COLOR, PEAK);

        assertThat(image.getWidth()).isEqualTo(8);
        assertThat(image.getHeight()).isEqualTo(8);
    }

    @Test
    void zeroReachGivesJustTheClearRectangle() {
        BufferedImage image = Glow.render(W, H, 0, COLOR, PEAK);

        assertThat(image.getWidth()).isEqualTo(W);
        assertThat(image.getHeight()).isEqualTo(H);
        assertThat(new Color(image.getRGB(0, 0), true).getAlpha()).isZero();
    }

    @Test
    void emptyImageIsAtLeastOnePixel() {
        BufferedImage image = Glow.render(0, 0, 0, COLOR, PEAK);

        assertThat(image.getWidth()).isEqualTo(1);
        assertThat(image.getHeight()).isEqualTo(1);
    }

    private static int alpha(int x, int y) {
        return new Color(IMAGE.getRGB(x, y), true).getAlpha();
    }
}
