package se.grynna.pager.ui.sprattel;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.sprattel.Blockies.Series;
import se.grynna.pager.ui.sprattel.Blockies.Tone;
import se.grynna.pager.utils.DarculaColors;
import se.grynna.pager.utils.OKLab;
import se.grynna.pager.utils.Theme;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockiesTest {

    private static final int SIZE = 5;
    private static final int SCALE = 4;

    private final Blockies blockies = Blockies.withCacheSize(16);

    @Test
    void sameSeedsReturnTheCachedImage() {
        var first = blockies.icon("pod-1|proc", "pod-1|proc/t1", Tone.BRIGHT, SIZE, SCALE);
        var second = blockies.icon("pod-1|proc", "pod-1|proc/t1", Tone.BRIGHT, SIZE, SCALE);

        assertThat(second).isSameAs(first);
        assertThat(first.getWidth()).isEqualTo(SIZE * SCALE);
    }

    @Test
    void toneAndPatternSeedArePartOfTheCacheKey() {
        var bright = blockies.icon("node", "node/t1", Tone.BRIGHT, SIZE, SCALE);
        var dim = blockies.icon("node", "node/t1", Tone.DIM, SIZE, SCALE);
        var otherThread = blockies.icon("node", "node/t2", Tone.BRIGHT, SIZE, SCALE);

        assertThat(dim).isNotSameAs(bright);
        assertThat(otherThread).isNotSameAs(bright);
    }

    @Test
    void nullColourSeedFallsBackToPatternSeed() {
        var fromPattern = blockies.icon(null, "only", Tone.DIM, SIZE, SCALE);

        assertThat(fromPattern).isSameAs(blockies.icon("only", "only", Tone.DIM, SIZE, SCALE));
    }

    @Test
    void nullPatternSeedFallsBackToColourSeed() {
        var fromColour = blockies.icon("only", null, Tone.BRIGHT, SIZE, SCALE);

        assertThat(fromColour).isSameAs(blockies.icon("only", "only", Tone.BRIGHT, SIZE, SCALE));
    }

    @Test
    void bothSeedsNullThrows() {
        assertThatThrownBy(() -> blockies.icon(null, null, Tone.BRIGHT, SIZE, SCALE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacySingleSeedApiStillWorks() {
        var icon = blockies.icon("legacy", SIZE, SCALE);

        assertThat(icon).isSameAs(blockies.icon("legacy", SIZE, SCALE));
        assertThat(icon.getWidth()).isEqualTo(SIZE * SCALE);
    }

    @Test
    void sameColourSeedGivesSameBackground() {
        var a = Blockies.palette("pod-1|proc", "pod-1|proc/t1", Tone.BRIGHT);
        var b = Blockies.palette("pod-1|proc", "pod-1|proc/t2", Tone.BRIGHT);

        assertThat(b.background()).isEqualTo(a.background());
    }

    @Test
    void differentColourSeedsGiveDifferentBackgrounds() {
        var backgrounds = IntStream.range(0, 20)
                .mapToObj(i -> Blockies.palette("node-" + i + "|proc", "node-" + i + "|proc/t", Tone.BRIGHT)
                        .background())
                .distinct()
                .count();

        assertThat(backgrounds).isEqualTo(20);
    }

    @Test
    void sameThreadPatternGivesSameForegroundAndSpotAcrossProcesses() {
        var a = Blockies.palette("node-a|p", "shared", Tone.BRIGHT);
        var b = Blockies.palette("node-b|q", "shared", Tone.BRIGHT);

        assertThat(b.color()).isEqualTo(a.color());
        assertThat(b.spot()).isEqualTo(a.spot());
        assertThat(b.background()).isNotEqualTo(a.background());
    }

    @Test
    void spotSeedEqualToPatternSeedGivesTheSamePaletteAndIcon() {
        var threeArg = Blockies.palette("node-a|p", "node-a|p/t", Tone.BRIGHT);
        var fourArg = Blockies.palette("node-a|p", "node-a|p/t", "node-a|p/t", Tone.BRIGHT);

        assertThat(fourArg).isEqualTo(threeArg);
        assertThat(blockies.icon("node-a|p", "node-a|p/t", "node-a|p/t", Tone.BRIGHT, SIZE, SCALE))
                .isSameAs(blockies.icon("node-a|p", "node-a|p/t", Tone.BRIGHT, SIZE, SCALE));
    }

    @Test
    void differentSpotSeedChangesOnlyTheSpotColour() {
        var a = Blockies.palette("node-a|p", "node-a|p/t", "node-a|p/t#r1", Tone.BRIGHT);
        var b = Blockies.palette("node-a|p", "node-a|p/t", "node-a|p/t#r2", Tone.BRIGHT);
        var plain = Blockies.palette("node-a|p", "node-a|p/t", Tone.BRIGHT);

        assertThat(b.background()).isEqualTo(a.background());
        assertThat(b.color()).isEqualTo(a.color());
        assertThat(b.spot()).isNotEqualTo(a.spot());
        assertThat(a.background()).isEqualTo(plain.background());
        assertThat(a.color()).isEqualTo(plain.color());
        assertThat(a.spot()).isNotEqualTo(plain.spot());
    }

    @Test
    void differentSpotSeedGivesADifferentIconOnTheSameShape() {
        var a = blockies.icon("node-a|p", "node-a|p/t", "node-a|p/t#r1", Tone.BRIGHT, SIZE, SCALE);
        var b = blockies.icon("node-a|p", "node-a|p/t", "node-a|p/t#r2", Tone.BRIGHT, SIZE, SCALE);

        assertThat(b).isNotSameAs(a);
        assertThat(b.getWidth()).isEqualTo(a.getWidth());
    }

    @Test
    void backgroundIsDarkerThanForegroundForEachTone() {
        for (Series series : Series.values()) {
            for (Tone tone : Tone.values()) {
                for (int i = 0; i < 10; i++) {
                    var palette = Blockies.palette("node-" + i, "node-" + i + "/t", null, tone, series);

                    assertThat(lightness(palette.background()))
                            .as("%s %s background of seed %d", series, tone, i)
                            .isLessThan(lightness(palette.color()));
                }
            }
        }
    }

    @Test
    void dimPaletteIsLessLightThanBrightForTheSameSeeds() {
        for (Series series : Series.values()) {
            for (int i = 0; i < 10; i++) {
                var bright = Blockies.palette("node-" + i, "node-" + i + "/t", null, Tone.BRIGHT, series);
                var dim = Blockies.palette("node-" + i, "node-" + i + "/t", null, Tone.DIM, series);

                assertThat(lightness(dim.color())).isLessThan(lightness(bright.color()));
                assertThat(lightness(dim.spot())).isLessThan(lightness(bright.spot()));
                assertThat(lightness(dim.background())).isLessThan(lightness(bright.background()));
            }
        }
    }

    @Test
    void pastelIsLighterThanDarkForTheSameSeeds() {
        for (Tone tone : Tone.values()) {
            for (int i = 0; i < 10; i++) {
                var dark = Blockies.palette("node-" + i, "node-" + i + "/t", "spot-" + i, tone, Series.DARK);
                var pastel = Blockies.palette("node-" + i, "node-" + i + "/t", "spot-" + i, tone, Series.PASTEL);

                assertThat(lightness(pastel.background())).isGreaterThan(lightness(dark.background()));
                assertThat(lightness(pastel.color())).isGreaterThan(lightness(dark.color()));
                assertThat(lightness(pastel.spot())).isGreaterThan(lightness(dark.spot()));
            }
        }
    }

    @Test
    void lightThemeCachesASeparateLighterIcon() {
        try {
            DarculaColors.use(Theme.DARK);
            var dark = blockies.icon("node-a|p", "node-a|p/t", Tone.BRIGHT, SIZE, SCALE);
            DarculaColors.use(Theme.LIGHT);
            var light = blockies.icon("node-a|p", "node-a|p/t", Tone.BRIGHT, SIZE, SCALE);

            assertThat(light).isNotSameAs(dark);
            assertThat(averageLightness(light)).isGreaterThan(averageLightness(dark));
        } finally {
            DarculaColors.use(Theme.DARK);
        }
    }

    private static float lightness(Color color) {
        return OKLab.colorToOklab(color).L();
    }

    private static float averageLightness(BufferedImage image) {
        double sum = 0;
        int n = image.getWidth() * image.getHeight();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                sum += lightness(new Color(image.getRGB(x, y), true));
            }
        }
        return (float) (sum / n);
    }
}
