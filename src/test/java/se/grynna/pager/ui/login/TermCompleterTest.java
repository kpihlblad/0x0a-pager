package se.grynna.pager.ui.login;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.FieldSummary.Value;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.ui.login.TermCompleter.Entry;
import se.grynna.pager.ui.login.TermCompleter.FieldEntry;
import se.grynna.pager.ui.login.TermCompleter.Mark;
import se.grynna.pager.ui.login.TermCompleter.TextEntry;
import se.grynna.pager.ui.login.TermCompleter.ValueEntry;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class TermCompleterTest {

    @Test
    void offersMatchingFieldsThenValuesByCountDescThenLiteralTextEntry() {
        List<FieldSummary> summaries = List.of(
                summary("service", Map.of("order-api", 40L, "payment-api", 60L), 0.9),
                summary("pod", Map.of("order-api-x2", 12L, "order-api-k1", 28L), 0.8),
                summary("orderType", Map.of("retail", 50L, "wholesale", 50L), 0.7));

        List<Entry> entries = TermCompleter.complete("order", summaries, Scope.ofValues("main", Map.of()));

        assertThat(entries).hasSize(5);
        assertThat(entries.get(0)).isEqualTo(new FieldEntry(
                ((FieldEntry) entries.get(0)).field(), "orderType", 2));
        assertThat(((ValueEntry) entries.get(1)).label()).isEqualTo("service");
        assertThat(((ValueEntry) entries.get(1)).value()).isEqualTo("order-api");
        assertThat(((ValueEntry) entries.get(2)).label()).isEqualTo("pod");
        assertThat(((ValueEntry) entries.get(2)).value()).isEqualTo("order-api-k1");
        assertThat(((ValueEntry) entries.get(3)).label()).isEqualTo("pod");
        assertThat(((ValueEntry) entries.get(3)).value()).isEqualTo("order-api-x2");
        assertThat(entries.get(4)).isEqualTo(new TextEntry("order", Mark.NONE));
    }

    @Test
    void fieldMatchesRankPrefixMatchesBeforeSubstringMatchesEvenWithHigherSuggestionRank() {
        List<FieldSummary> summaries = List.of(
                summary("apiClientPod", Map.of("p1", 10L, "p2", 10L), 0.95),
                summary("pod", Map.of("p1", 10L, "p2", 10L), 0.50),
                summary("podOwner", Map.of("o1", 10L, "o2", 10L), 0.40));

        List<Entry> entries = TermCompleter.complete("pod", summaries, Scope.ofValues("main", Map.of()));

        List<String> fieldLabels = entries.stream()
                .filter(FieldEntry.class::isInstance)
                .map(FieldEntry.class::cast)
                .map(FieldEntry::label)
                .toList();
        assertThat(fieldLabels).containsExactly("pod", "podOwner", "apiClientPod");
    }

    @Test
    void equalsModeFiltersFieldValuesByCountDescAndAppendsTypedValueWhenNotListed() {
        List<FieldSummary> summaries = List.of(
                summary("service", Map.of("order-api", 40L, "order-worker", 60L, "payment", 100L), 0.9));

        List<Entry> filtered = TermCompleter.complete("service=order", summaries, Scope.ofValues("main", Map.of()));
        assertThat(filtered).extracting(Entry::display).containsExactly(
                "service = order-worker  30%",
                "service = order-api  20%",
                "service=order");

        List<Entry> exactMatch = TermCompleter.complete("service=order-api", summaries,
                Scope.ofValues("main", Map.of()));
        assertThat(exactMatch).extracting(Entry::display).containsExactly(
                "service = order-api  20%");
    }

    @Test
    void matchesMergedFieldByShortLabelOrAnyAliasRealName() {
        List<FieldSummary> summaries = List.of(
                summary("kubernetes.pod_name", Map.of("pod-a", 10L, "pod-b", 20L), 0.8),
                summary("kubernetes_pod_name", Map.of("pod-a", 5L, "pod-c", 30L), 0.8),
                summary("kubernetes.pod_uid", Map.of("u1", 10L, "u2", 10L), 0.5),
                summary("kubernetes.pod_owner", Map.of("o1", 10L, "o2", 10L), 0.5));

        List<Entry> byFlatAlias = TermCompleter.complete("kubernetes_pod_name=pod-", summaries,
                Scope.ofValues("main", Map.of()));
        assertThat(byFlatAlias).filteredOn(ValueEntry.class::isInstance)
                .map(ValueEntry.class::cast)
                .extracting(ValueEntry::value)
                .containsExactly("pod-c", "pod-b", "pod-a", "pod-");

        List<Entry> byShortLabel = TermCompleter.complete("name=pod-c", summaries,
                Scope.ofValues("main", Map.of()));
        assertThat(byShortLabel).singleElement().satisfies(e -> {
            ValueEntry ve = (ValueEntry) e;
            assertThat(ve.field().primary()).isEqualTo("kubernetes.pod_name");
            assertThat(ve.value()).isEqualTo("pod-c");
            assertThat(ve.typedValue()).isFalse();
        });
    }

    @Test
    void marksValuesAndTextAlreadyNarrowedOrExcludedInDraft() {
        List<FieldSummary> summaries = List.of(
                summary("level", Map.of("INFO", 80L, "DEBUG", 15L, "ERROR", 5L), 0.9));
        Scope draft = Scope.of("main", Map.of("level", "INFO"))
                .withExcludeField("level", "DEBUG")
                .withNarrow("timeout");

        List<Entry> levelEntries = TermCompleter.complete("level=", summaries, draft);
        assertThat(levelEntries).map(ValueEntry.class::cast)
                .extracting(ValueEntry::value, ValueEntry::mark)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INFO", Mark.NARROWED),
                        org.assertj.core.groups.Tuple.tuple("DEBUG", Mark.EXCLUDED),
                        org.assertj.core.groups.Tuple.tuple("ERROR", Mark.NONE));

        List<Entry> textEntries = TermCompleter.complete("timeout", summaries, draft);
        assertThat(textEntries.getLast()).isEqualTo(new TextEntry("timeout", Mark.NARROWED));
    }

    private static FieldSummary summary(String name, Map<String, Long> counts, double relevance) {
        Map<String, Value> values = new TreeMap<>();
        counts.forEach((v, c) -> values.put(v, new Value(v, c)));
        return FieldSummary.of(name, values, relevance);
    }
}
