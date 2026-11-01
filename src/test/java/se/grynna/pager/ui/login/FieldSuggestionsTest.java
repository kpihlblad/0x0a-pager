package se.grynna.pager.ui.login;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.FieldSummary.Value;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.ui.login.FieldSuggestions.SuggestedField;
import se.grynna.pager.ui.login.FieldSuggestions.ValueShare;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class FieldSuggestionsTest {

    @Test
    void dropsSingleValueUniquePerEventAndSplunkBookkeepingFields() {
        List<FieldSummary> summaries = List.of(
                summary("singleValue", Map.of("only", 500L), 0.9),
                summary("blankExtraValue", Map.of("only", 500L, "   ", 10L), 0.9),
                summary("uniquePerEventId", Map.of("id-1", 1L, "id-2", 1L, "id-3", 1L), 0.9),
                summary("punct", Map.of("--::_", 20L, "--::_[]", 10L), 0.9),
                summary("validField", Map.of("a", 2L, "b", 1L), 0.5));

        List<SuggestedField> ranked = FieldSuggestions.rank(summaries);

        assertThat(ranked).extracting(SuggestedField::primary).containsExactly("validField");
    }

    @Test
    void ordersIdentityFieldsFirstInSpecifiedOrderThenByRelevance() {
        List<FieldSummary> summaries = List.of(
                summary("highRelevanceCustom", Map.of("v1", 50L, "v2", 50L), 0.99),
                summary("lowRelevanceCustom", Map.of("v1", 50L, "v2", 50L), 0.40),
                summary("host", Map.of("h1", 30L, "h2", 20L), 0.10),
                summary("kubernetes.pod_name", Map.of("p1", 10L, "p2", 10L), 0.20),
                summary("workload", Map.of("w1", 10L, "w2", 10L), 0.15),
                summary("kubernetes.container_name", Map.of("c1", 10L, "c2", 10L), 0.30),
                summary("kubernetes.labels.app", Map.of("a1", 10L, "a2", 10L), 0.25),
                summary("serviceName", Map.of("s1", 10L, "s2", 10L), 0.05),
                summary("kubernetes.namespace_name", Map.of("ns1", 10L, "ns2", 10L), 0.01));

        List<SuggestedField> ranked = FieldSuggestions.rank(summaries);

        assertThat(ranked).extracting(SuggestedField::primary).containsExactly(
                "kubernetes.namespace_name",
                "serviceName",
                "kubernetes.labels.app",
                "kubernetes.container_name",
                "workload",
                "kubernetes.pod_name",
                "host",
                "highRelevanceCustom",
                "lowRelevanceCustom");
    }

    @Test
    void suggestShowsTopSixDistinctPlusDraftFieldsInRankOrder() {
        List<FieldSummary> summaries = List.of(
                summary("namespace", Map.of("ns1", 10L, "ns2", 5L), 0.8),
                summary("service", Map.of("svc1", 10L, "svc2", 5L, "svc3", 2L), 0.8),
                summary("app", Map.of("svc1", 10L, "svc2", 5L), 0.8),
                summary("container", Map.of("c1", 10L, "c2", 5L), 0.8),
                summary("deployment", Map.of("d1", 10L, "d2", 5L), 0.8),
                summary("pod", Map.of("p1", 10L, "p2", 5L), 0.8),
                summary("host", Map.of("h1", 10L, "h2", 5L), 0.8),
                summary("severity", Map.of("INFO", 80L, "ERROR", 20L), 0.7),
                summary("region", Map.of("eu", 50L, "us", 50L), 0.5));

        Scope draft = Scope.of("main", Map.of("region", "eu")).withExcludeField("severity", "INFO");
        List<SuggestedField> suggested = FieldSuggestions.suggest(summaries, draft);

        // app repeats service's values, so host takes its place
        assertThat(suggested).extracting(SuggestedField::primary).containsExactly(
                "namespace",
                "service",
                "container",
                "deployment",
                "pod",
                "host",
                "severity",
                "region");
    }

    @Test
    void computesTopFourValuesWithRoundedSharePercentagesAndMoreCount() {
        FieldSummary level = summary("level", Map.of(
                "INFO", 800L,
                "WARN", 120L,
                "ERROR", 60L,
                "DEBUG", 15L,
                "TRACE", 5L,
                "AUDIT", 1L), 0.9);

        SuggestedField field = FieldSuggestions.rank(List.of(level)).getFirst();

        assertThat(field.label()).isEqualTo("level");
        assertThat(field.moreCount()).isEqualTo(2);
        assertThat(field.topValues()).containsExactly(
                new ValueShare("INFO", 800L, 80),
                new ValueShare("WARN", 120L, 12),
                new ValueShare("ERROR", 60L, 6),
                new ValueShare("DEBUG", 15L, 1));
    }

    private static FieldSummary summary(String name, Map<String, Long> counts, double relevance) {
        Map<String, Value> values = new TreeMap<>();
        counts.forEach((v, c) -> values.put(v, new Value(v, c)));
        return FieldSummary.of(name, values, relevance);
    }
}
