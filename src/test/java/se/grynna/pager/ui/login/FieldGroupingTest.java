package se.grynna.pager.ui.login;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.ui.login.FieldGrouping.Entry;
import se.grynna.pager.ui.login.FieldGrouping.Heading;
import se.grynna.pager.ui.login.FieldGrouping.Row;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FieldGroupingTest {

    private static final List<String> CORPUS = List.of(
            "@timestamp", "apiClientNamespace", "apiClientPod", "customerId", "eventName", "event_key", "exception",
            "extracted_eventType", "extracted_eventtype", "host", "id", "index", "initialSenderID",
            "kubernetes.container_id", "kubernetes.container_image", "kubernetes.container_image_id",
            "kubernetes.container_name", "kubernetes.labels.app", "kubernetes.labels.app.kubernetes.io/name",
            "kubernetes.labels.app.kubernetes.io/part-of", "kubernetes.labels.app.kubernetes.io/version",
            "kubernetes.labels.environment", "kubernetes.labels.k8s.example.com/deployment-resource",
            "kubernetes.labels.k8s.example.com/deployment-resource-type", "kubernetes.labels.pod-template-hash",
            "kubernetes.labels.team", "kubernetes.namespace_name", "kubernetes.pod_name", "kubernetes.pod_node_name",
            "kubernetes.pod_owner", "kubernetes.pod_uid", "linecount", "logger", "message", "moduleName",
            "monitored_entity", "partial", "punct", "serviceName", "serviceRequestID", "sessionID", "severity",
            "source", "sourceAddress", "source_type", "sourcetype", "splunk_server", "stream", "threadID", "userId",
            "version", "workload", "kubernetes_container_image", "kubernetes_container_name", "kubernetes_labels_app",
            "kubernetes_labels_app_name", "kubernetes_labels_app_part_of", "kubernetes_namespace_name",
            "kubernetes_pod_name", "kubernetes_pod_node_name", "kubernetes_pod_owner", "kubernetes_pod_uid");

    @Test
    void tokensSplitOnDotAndUnderscoreKeepingEmptyTokens() {
        assertThat(FieldGrouping.tokens("_time")).containsExactly("", "time");
        assertThat(FieldGrouping.tokens("kubernetes.labels.app.kubernetes.io/name"))
                .containsExactly("kubernetes", "labels", "app", "kubernetes", "io/name");
        assertThat(FieldGrouping.tokens("extracted_eventType")).containsExactly("extracted", "eventType");
    }

    @Test
    void dottedAndFlatNamesMergeIntoOneLogicalField() {
        List<LogicalField> merged = rowFields(FieldGrouping.group(summaries(CORPUS)));

        List<LogicalField> aliased = merged.stream().filter(f -> f.fields().size() == 2).toList();
        assertThat(aliased).hasSize(8);
        assertThat(aliased).allSatisfy(f -> {
            assertThat(f.fields()).anyMatch(n -> n.startsWith("kubernetes."));
            assertThat(f.fields()).anyMatch(n -> n.startsWith("kubernetes_"));
            assertThat(f.primary()).startsWith("kubernetes.");
        });
        assertThat(aliased).extracting(LogicalField::primary).contains(
                "kubernetes.container_image", "kubernetes.container_name", "kubernetes.labels.app",
                "kubernetes.namespace_name", "kubernetes.pod_name", "kubernetes.pod_node_name",
                "kubernetes.pod_owner", "kubernetes.pod_uid");
    }

    @Test
    void caseDifferingNamesDoNotMergeAndSmallGroupsDoNotHeadBlocks() {
        List<Entry> entries = FieldGrouping.group(summaries(CORPUS));

        assertThat(rowLabel(entries, "extracted_eventType")).isEqualTo("extracted_eventType");
        assertThat(rowLabel(entries, "extracted_eventtype")).isEqualTo("extracted_eventtype");
        assertThat(rowLabel(entries, "source")).isEqualTo("source");
        assertThat(rowLabel(entries, "source_type")).isEqualTo("source_type");
        assertThat(rowLabel(entries, "splunk_server")).isEqualTo("splunk_server");
        assertThat(entries).filteredOn(e -> e instanceof Heading h && h.depth() == 0)
                .containsExactly(new Heading(0, "kubernetes"));
    }

    @Test
    void kubernetesGroupListsItsDirectRowsBeforeItsSubgroups() {
        List<String> rendered = render(FieldGrouping.group(summaries(CORPUS)));

        int kubernetes = rendered.indexOf("H0:kubernetes");
        assertThat(rendered.subList(kubernetes + 1, rendered.size())).containsExactly(
                "R1:namespace_name",
                "H1:container",
                "R2:id",
                "R2:image",
                "R2:image_id",
                "R2:name",
                "H1:labels",
                "R2:app",
                "R2:app.kubernetes.io/name",
                "R2:app.kubernetes.io/part-of",
                "R2:app.kubernetes.io/version",
                "R2:app_name",
                "R2:app_part_of",
                "R2:environment",
                "R2:k8s.example.com/deployment-resource",
                "R2:k8s.example.com/deployment-resource-type",
                "R2:pod-template-hash",
                "R2:team",
                "H1:pod",
                "R2:name",
                "R2:node_name",
                "R2:owner",
                "R2:uid");
    }

    @Test
    void labelsStripOneTokenPerHeadingLevel() {
        List<Entry> entries = FieldGrouping.group(summaries(CORPUS));

        assertThat(rowLabel(entries, "kubernetes.pod_name")).isEqualTo("name");
        assertThat(rowLabel(entries, "kubernetes.labels.app.kubernetes.io/name")).isEqualTo("app.kubernetes.io/name");
        assertThat(rowLabel(entries, "kubernetes.namespace_name")).isEqualTo("namespace_name");
        assertThat(rowLabel(entries, "kubernetes.container_image_id")).isEqualTo("image_id");
        assertThat(rowLabel(entries, "kubernetes_labels_app_name")).isEqualTo("app_name");
        assertThat(rowLabel(entries, "kubernetes_pod_node_name")).isEqualTo("node_name");
    }

    @Test
    void mergingSumsEqualValuesAndUnionsDistinctOnes() {
        List<Entry> entries = FieldGrouping.group(List.of(
                field("kubernetes.pod_name", Map.of("a", 2L, "b", 1L)),
                field("kubernetes_pod_name", Map.of("a", 3L, "c", 4L))));

        LogicalField pod = rowFields(entries).getFirst();
        assertThat(pod.primary()).isEqualTo("kubernetes.pod_name");
        assertThat(pod.values()).containsExactly(Map.entry("a", 5L), Map.entry("b", 1L), Map.entry("c", 4L));
    }

    @Test
    void fieldNamedOnlyAsHeadingTokenStaysUngrouped() {
        List<Entry> entries = FieldGrouping.group(summaries(List.of(
                "kubernetes", "kubernetes.pod_name", "kubernetes.pod_owner", "kubernetes.pod_uid")));

        assertThat(render(entries)).containsExactly(
                "R0:kubernetes",
                "H0:kubernetes",
                "H1:pod",
                "R2:name",
                "R2:owner",
                "R2:uid");
    }

    private static List<LogicalField> rowFields(List<Entry> entries) {
        return entries.stream()
                .filter(e -> e instanceof Row)
                .map(e -> ((Row) e).field())
                .toList();
    }

    private static String rowLabel(List<Entry> entries, String name) {
        return entries.stream()
                .filter(e -> e instanceof Row r && r.field().fields().contains(name))
                .map(e -> ((Row) e).label())
                .findFirst()
                .orElseThrow();
    }

    private static List<String> render(List<Entry> entries) {
        return entries.stream().map(e -> switch (e) {
            case Heading h -> "H" + h.depth() + ":" + h.text();
            case Row r -> "R" + r.depth() + ":" + r.label();
        }).toList();
    }

    private static List<FieldSummary> summaries(List<String> names) {
        return names.stream().map(n -> field(n, Map.of())).toList();
    }

    private static FieldSummary field(String name, Map<String, Long> counts) {
        FieldSummary summary = mock(FieldSummary.class);
        when(summary.getField()).thenReturn(name);
        Map<String, FieldSummary.Value> values = new TreeMap<>();
        counts.forEach((value, count) -> values.put(value, new FieldSummary.Value(value, count)));
        when(summary.getValues()).thenReturn(values);
        return summary;
    }
}
