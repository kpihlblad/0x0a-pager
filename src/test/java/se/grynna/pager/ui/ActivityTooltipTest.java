package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Activity;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityTooltipTest {

    @Test
    void everyKnownLevelGetsALine() {
        String text = ActivityTooltip.text(
                new Activity("compute-12.dev01", "casbatch[casbatch-7f9c-x2]", "scheduler-10", "4bf92f3577b34da6",
                        "ignored-origin"));

        assertThat(text).startsWith("<html>").endsWith("</html>");
        assertThat(rows(text)).containsExactly("machine|compute-12.dev01", "process|casbatch[casbatch-7f9c-x2]",
                "thread|scheduler-10", "request|4bf92f3577b34da6");
    }

    @Test
    void unknownLevelsAreLeftOut() {
        assertThat(rows(ActivityTooltip.text(new Activity("compute-12", null, null, "abc", null))))
                .containsExactly("machine|compute-12", "request|abc");
    }

    @Test
    void theOriginShowsAsSourceWhenItStandsInForMachineAndProcess() {
        assertThat(rows(ActivityTooltip.text(new Activity(null, null, "t-1", null, "app:json"))))
                .containsExactly("source|app:json", "thread|t-1");
    }

    @Test
    void theOriginIsLeftOutWhenAMachineOrProcessIsKnown() {
        assertThat(rows(ActivityTooltip.text(new Activity(null, "casbatch", null, null, "app:json"))))
                .containsExactly("process|casbatch");
    }

    @Test
    void nothingKnownReadsNoActivityFound() {
        assertThat(ActivityTooltip.text(Activity.NONE)).isEqualTo("<html>no activity found</html>");
        assertThat(ActivityTooltip.text(null)).isEqualTo("<html>no activity found</html>");
        assertThat(ActivityTooltip.text(Activity.NONE)).doesNotContain("<table");
    }

    @Test
    void valuesAreHtmlEscaped() {
        String text = ActivityTooltip.text(new Activity(null, "<b>proc</b> & \"co\"", null, null, null));

        assertThat(text).contains("&lt;b&gt;proc&lt;/b&gt; &amp; &quot;co&quot;").doesNotContain("<b>");
    }

    /** The label|value pairs of the table rows. */
    private static java.util.List<String> rows(String html) {
        return html.lines().flatMap(l -> java.util.Arrays.stream(l.split("<tr>"))).skip(1)
                .map(row -> row.replaceAll("&nbsp;", "").replaceAll("</td><td>", "|").replaceAll("<[^>]+>", ""))
                .toList();
    }
}
