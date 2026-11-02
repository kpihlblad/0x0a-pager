package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FieldSamplingTest {

    @Test
    void capsTheSampleBeforeSummarising() {
        String query = FieldSampling.query("k8s_prod", 0);

        assertThat(query).startsWith("search index=\"k8s_prod\" | head 10000 | fieldsummary");
        assertThat(query).contains("maxvals=" + FieldSampling.MAX_VALUES);
        assertThat(query.indexOf("| head")).isLessThan(query.indexOf("| fieldsummary"));
    }

    @Test
    void includesFieldFiltersAndNarrowExcludeStagesInScopeQuery() {
        Scope scope = Scope.of("k8s_prod", java.util.Map.of("app", "orders"))
                .withNarrow("timeout")
                .withExcludeField("level", "DEBUG");

        assertThat(FieldSampling.query(scope, 0)).startsWith(
                "search index=\"k8s_prod\" app=\"orders\" | search \"timeout\" | search NOT level=\"DEBUG\""
                        + " | head 10000 | fieldsummary");
    }

    @Test
    void excludesFreeTextFields() {
        assertThat(FieldSampling.query("main", 0)).endsWith("\"kubernetes.pod_uid\", \"linecount\")")
                .contains("| where NOT field IN (\"@timestamp\", ");
    }

    @Test
    void quotesTheIndexAsALiteral() {
        assertThat(FieldSampling.query("a\"b", 0)).startsWith("search index=\"a\\\"b\" | head");
    }

    @Test
    void severalIndexesOrAllOfThemAreSampledWithTheSelectionPredicate() {
        assertThat(FieldSampling.query("b,a", 0)).startsWith("search (index=\"a\" OR index=\"b\") | head 10000 |");
        assertThat(FieldSampling.query("*", 0)).startsWith("search index=* | head 10000 |");
        assertThat(FieldSampling.latestEventQuery("b,a"))
                .isEqualTo("| tstats max(_time) as latest where (index=\"a\" OR index=\"b\")");
        assertThat(FieldSampling.latestEventQuery("*")).isEqualTo("| tstats max(_time) as latest where index=*");
        assertThat(FieldSampling.latestEventFallbackQuery("*"))
                .isEqualTo("search index=* | head 1 | eval latest=_time | fields latest");
    }

    @Test
    void eachDepthSamplesTenTimesMore() {
        assertThat(FieldSampling.sampleCap(0)).isEqualTo(10_000);
        assertThat(FieldSampling.sampleCap(1)).isEqualTo(100_000);
        assertThat(FieldSampling.sampleCap(2)).isEqualTo(1_000_000);
        assertThat(FieldSampling.enoughEvents(0)).isEqualTo(1_000);
        assertThat(FieldSampling.enoughEvents(2)).isEqualTo(100_000);
        assertThat(FieldSampling.query("main", 1)).contains("| head 100000 |");
    }

    @Test
    void depthIsClampedToItsRange() {
        assertThat(FieldSampling.sampleCap(-1)).isEqualTo(FieldSampling.sampleCap(0));
        assertThat(FieldSampling.sampleCap(FieldSampling.MAX_DEPTH + 5))
                .isEqualTo(FieldSampling.sampleCap(FieldSampling.MAX_DEPTH));
    }

    @Test
    void labelsSampleSizes() {
        assertThat(FieldSampling.sizeLabel(10_000)).isEqualTo("10k");
        assertThat(FieldSampling.sizeLabel(100_000)).isEqualTo("100k");
        assertThat(FieldSampling.sizeLabel(1_000_000)).isEqualTo("1M");
        assertThat(FieldSampling.sizeLabel(1_500)).isEqualTo("1500");
    }

    @Test
    void windowsGrowFromAnHourToAYear() {
        assertThat(FieldSampling.LOOKBACKS).first().isEqualTo(Duration.ofHours(1));
        assertThat(FieldSampling.LOOKBACKS).last().isEqualTo(Duration.ofDays(365));
        assertThat(FieldSampling.LOOKBACKS).isSorted();
    }

    @Test
    void aBigEnoughSampleStopsWidening() {
        assertThat(FieldSampling.isDone(0, FieldSampling.enoughEvents(0), 0)).isTrue();
        assertThat(FieldSampling.isDone(0, FieldSampling.sampleCap(0), 0)).isTrue();
    }

    @Test
    void aDeeperSampleKeepsWideningUntilItIsBigEnough() {
        assertThat(FieldSampling.isDone(0, FieldSampling.enoughEvents(1) - 1, 1)).as("just under enough at depth 1")
                .isFalse();
        assertThat(FieldSampling.isDone(0, FieldSampling.enoughEvents(0), 1)).as("depth 0's enough is not enough")
                .isFalse();
        assertThat(FieldSampling.isDone(0, FieldSampling.enoughEvents(1), 1)).isTrue();
    }

    @Test
    void aSmallSampleWidensUntilTheLastWindow() {
        int last = FieldSampling.STEPS - 1;
        for (int step = 0; step < last; step++) {
            assertThat(FieldSampling.isDone(step, 0, 0)).isFalse();
            assertThat(FieldSampling.isDone(step, FieldSampling.enoughEvents(0) - 1, 0)).isFalse();
        }
        assertThat(FieldSampling.isDone(last, 0, 0)).isTrue();
    }

    @Test
    void windowsEndJustPastTheNewestEventAndTheLastIsAllTime() {
        Instant anchor = Instant.parse("2017-12-31T23:59:58.500Z");

        assertThat(FieldSampling.windowEnd(anchor)).isAfter(anchor);
        assertThat(FieldSampling.windowStart(0, anchor)).isEqualTo(anchor.minus(Duration.ofHours(1)));
        assertThat(FieldSampling.windowStart(FieldSampling.LOOKBACKS.size() - 1, anchor))
                .isEqualTo(anchor.minus(Duration.ofDays(365)));
        assertThat(FieldSampling.windowStart(FieldSampling.STEPS - 1, anchor))
                .isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY);
        assertThat(FieldSampling.STEPS).isEqualTo(FieldSampling.LOOKBACKS.size() + 1);
    }

    @Test
    void aLookbackBeforeTheFloorStartsAtTheFloor() {
        Instant anchor = SearchBoundaries.MIN_MINIMUM_BOUNDARY.plus(Duration.ofDays(10));

        assertThat(FieldSampling.windowStart(FieldSampling.LOOKBACKS.size() - 1, anchor))
                .isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY);
    }

    @Test
    void theNewestEventComesFromIndexMetadata() {
        assertThat(FieldSampling.latestEventQuery("k8s_prod"))
                .isEqualTo("| tstats max(_time) as latest where index=\"k8s_prod\"");
    }

    @Test
    void theFallbackReadsTheNewestEvent() {
        assertThat(FieldSampling.latestEventFallbackQuery("k8s_prod"))
                .isEqualTo("search index=\"k8s_prod\" | head 1 | eval latest=_time | fields latest");
    }

    @Test
    void parsesSplunkEpochSeconds() {
        assertThat(SplunkClient.parseEpochSeconds("1514764798")).contains(Instant.parse("2017-12-31T23:59:58Z"));
        assertThat(SplunkClient.parseEpochSeconds(" 1514764798.250 "))
                .contains(Instant.parse("2017-12-31T23:59:58.250Z"));
        assertThat(SplunkClient.parseEpochSeconds(null)).isEmpty();
        assertThat(SplunkClient.parseEpochSeconds("")).isEmpty();
        assertThat(SplunkClient.parseEpochSeconds("n/a")).isEmpty();
    }
}
