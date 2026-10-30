package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Activity;

import static org.assertj.core.api.Assertions.assertThat;

class LineRendererIconSeedsTest {

    @Test
    void activityWithThreadSeedsColourFromProcessAndPatternFromThread() {
        var seeds = LineRenderer.iconSeeds(new Activity("m", "p", "t", null, "o"), "message");

        assertThat(seeds).isEqualTo(new LineRenderer.IconSeeds("m|p", "m|p/t", "m|p/t"));
    }

    @Test
    void activityWithoutThreadUsesColourSeedForBoth() {
        var seeds = LineRenderer.iconSeeds(new Activity("m", "p", null, null, null), "message");

        assertThat(seeds).isEqualTo(new LineRenderer.IconSeeds("m|p", "m|p", "m|p"));
    }

    @Test
    void requestGoesToSpotSeedOnly() {
        var seeds = LineRenderer.iconSeeds(new Activity("m", "p", "t", "r", null), "message");

        assertThat(seeds).isEqualTo(new LineRenderer.IconSeeds("m|p", "m|p/t", "m|p/t#r"));
    }

    @Test
    void requestWithoutThreadIsTheSpotAndPatternUnit() {
        var seeds = LineRenderer.iconSeeds(new Activity("m", "p", null, "r", null), "message");

        assertThat(seeds).isEqualTo(new LineRenderer.IconSeeds("m|p", "m|p/r", "m|p/r#r"));
    }

    @Test
    void originStandsInWhenMachineAndProcessAreUnknown() {
        var seeds = LineRenderer.iconSeeds(new Activity(null, null, "t", null, "syslog:x"), "message");

        assertThat(seeds).isEqualTo(new LineRenderer.IconSeeds("syslog:x", "syslog:x/t", "syslog:x/t"));
    }

    @Test
    void threadWithoutAnyColourSourceUsesThreadAsBothSeeds() {
        var seeds = LineRenderer.iconSeeds(new Activity(null, null, "t", null, null), "message");

        assertThat(seeds).isEqualTo(new LineRenderer.IconSeeds("/t", "/t", "/t"));
    }

    @Test
    void noActivityFallsBackToMessage() {
        assertThat(LineRenderer.iconSeeds(Activity.NONE, "hello"))
                .isEqualTo(new LineRenderer.IconSeeds("hello", "hello", "hello"));
        assertThat(LineRenderer.iconSeeds(null, "hello"))
                .isEqualTo(new LineRenderer.IconSeeds("hello", "hello", "hello"));
    }

    @Test
    void blankOrMissingMessageWithoutActivityGivesNoIcon() {
        assertThat(LineRenderer.iconSeeds(Activity.NONE, "   ")).isNull();
        assertThat(LineRenderer.iconSeeds(Activity.NONE, "")).isNull();
        assertThat(LineRenderer.iconSeeds(Activity.NONE, null)).isNull();
        assertThat(LineRenderer.iconSeeds(null, null)).isNull();
    }
}
