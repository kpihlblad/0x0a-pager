package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeTest {
    private final Scope base = Scope.of("web", Map.of("host", "a1"));

    @Test
    void collectionsAreCopiedAndImmutable() {
        List<String> narrow = new ArrayList<>(List.of("a"));
        List<String> hostValues = new ArrayList<>(List.of("a1"));
        Map<String, List<String>> filters = new java.util.HashMap<>(Map.of("host", hostValues));
        Scope scope = new Scope("web", filters, narrow, null, null);
        narrow.add("b");
        hostValues.add("a2");
        filters.put("x", List.of("y"));
        assertThat(scope.narrow()).containsExactly("a");
        assertThat(scope.fieldFilters()).containsOnlyKeys("host");
        assertThat(scope.fieldFilters().get("host")).containsExactly("a1");
        assertThat(scope.exclude()).isEmpty();
        assertThatThrownBy(() -> scope.narrow().add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.fieldFilters().put("c", List.of("d")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.fieldFilters().get("host").add("a3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankAndDuplicateTermsAndFilterValuesAreDropped() {
        Map<String, List<String>> filters = new java.util.LinkedHashMap<>();
        filters.put("pod", List.of("p1", " ", "p1", "", "p2"));
        filters.put("empty", List.of(" ", ""));
        Scope scope = new Scope("web", filters, List.of("a", " ", "a", ""), List.of("b", "b"), null);
        assertThat(scope.fieldFilters()).containsExactlyEntriesOf(Map.of("pod", List.of("p1", "p2")));
        assertThat(scope.narrow()).containsExactly("a");
        assertThat(scope.exclude()).containsExactly("b");
    }

    @Test
    void withNarrowAndWithExcludeAddAndAreIdempotent() {
        Scope narrowed = base.withNarrow("timeout");
        assertThat(narrowed.narrow()).containsExactly("timeout");
        assertThat(narrowed.withNarrow("timeout")).isSameAs(narrowed);
        assertThat(base.withNarrow(" ")).isSameAs(base);
        assertThat(base.narrow()).isEmpty();

        Scope excluded = narrowed.withExclude("noise");
        assertThat(excluded.narrow()).containsExactly("timeout");
        assertThat(excluded.exclude()).containsExactly("noise");
        assertThat(excluded.withExclude("noise")).isSameAs(excluded);
    }

    @Test
    void aTermMovesBetweenNarrowAndExcludeInsteadOfCancellingItself() {
        Scope scope = base.withNarrow("x").withExclude("x");
        assertThat(scope.narrow()).isEmpty();
        assertThat(scope.exclude()).containsExactly("x");
        assertThat(scope.withNarrow("x").exclude()).isEmpty();
    }

    @Test
    void withoutRemovesFromEitherListAndKeepsTheRest() {
        Scope scope = base.withNarrow("a").withNarrow("b").withExclude("c");
        assertThat(scope.without("a").narrow()).containsExactly("b");
        assertThat(scope.without("c").exclude()).isEmpty();
        assertThat(scope.without("missing")).isSameAs(scope);
        assertThat(scope.without("a").index()).isEqualTo("web");
        assertThat(scope.hasTerm("b")).isTrue();
        assertThat(base.hasTerms()).isFalse();
    }

    @Test
    void manualSplSurvivesTermEdits() {
        Scope manual = Scope.manual("web", "index=app").withNarrow("a");
        assertThat(manual.manualSpl()).isEqualTo("index=app");
        assertThat(manual.without("a").manualSpl()).isEqualTo("index=app");
    }

    @Test
    void wildcardOnlyTermsCannotBeExcluded() {
        assertThat(Scope.isExcludable("timeout")).isTrue();
        assertThat(Scope.isExcludable("time*")).isTrue();
        assertThat(Scope.isExcludable("*")).isFalse();
        assertThat(Scope.isExcludable("**")).isFalse();
        assertThat(Scope.isExcludable(" * ")).isFalse();
        assertThat(Scope.isExcludable("")).isFalse();
        assertThat(Scope.isExcludable(null)).isFalse();
    }

    @Test
    void aGuidedNarrowedFieldBecomesAFieldFilterRenderedInThePredicate() {
        Scope scope = Scope.of("web", Map.of()).withNarrowField("level", "DEBUG");

        assertThat(scope.fieldFilters()).containsExactlyEntriesOf(Map.of("level", List.of("DEBUG")));
        assertThat(scope.narrow()).isEmpty();
        assertThat(SplQuery.scope(scope)).isEqualTo("search index=\"web\" level=\"DEBUG\"");
    }

    @Test
    void aManualScopeKeepsANarrowedFieldAsASearchStage() {
        Scope scope = Scope.manual("web", "search index=web").withNarrowField("level", "DEBUG");

        assertThat(scope.fieldFilters()).isEmpty();
        assertThat(scope.narrow()).containsExactly(FieldTerm.of("level", "DEBUG").orElseThrow().encode());
        assertThat(SplQuery.scope(scope)).isEqualTo("search index=web | search level=\"DEBUG\"");
    }

    @Test
    void aLegacyNarrowFieldTermIsNormalisedIntoAFieldFilter() {
        String debug = FieldTerm.of("level", "DEBUG").orElseThrow().encode();
        String info = FieldTerm.of("level", "INFO").orElseThrow().encode();
        String a2 = FieldTerm.of("host", "a2").orElseThrow().encode();
        Scope scope = new Scope("web", Map.of("host", List.of("a1", "a2")), List.of(debug, "timeout", info, a2),
                List.of(), null);

        assertThat(scope.fieldFilters()).isEqualTo(Map.of("host", List.of("a1", "a2"), "level", List.of("DEBUG")));
        assertThat(scope.narrow()).containsExactly("timeout", info);
    }

    @Test
    void withFieldValueAddsAlternativesOnKeyOrAliasAndRemovesMatchingExclude() {
        Scope scope = aliased.withExcludeField("level", "ERROR")
                .withFieldValue("hostname", "a2")
                .withFieldValue("level", "ERROR");

        assertThat(scope.fieldFilters())
                .containsExactly(Map.entry("host", List.of("a1", "a2")), Map.entry("level", List.of("ERROR")));
        assertThat(scope.exclude()).isEmpty();
        assertThat(scope.withFieldValue("host", "a1")).isSameAs(scope);
    }

    @Test
    void withoutFieldValueRemovesOneValueAndDropsTheFilterWhenTheLastValueGoes() {
        Scope multi = aliased.withFieldValue("host", "a2");

        Scope oneLeft = multi.withoutFieldValue("hostname", "a1");
        assertThat(oneLeft.fieldFilters()).containsExactlyEntriesOf(Map.of("host", List.of("a2")));
        assertThat(oneLeft.fieldAliases()).isEqualTo(aliased.fieldAliases());

        Scope empty = oneLeft.withoutFieldValue("host", "a2");
        assertThat(empty.fieldFilters()).isEmpty();
        assertThat(empty.fieldAliases()).isEmpty();
        assertThat(oneLeft.withoutFieldValue("host", "missing")).isSameAs(oneLeft);
    }

    @Test
    void narrowingOnAnAliasReplacesMultiValueFilterAndKeepsItsAliases() {
        Scope scope = aliased.withFieldValue("host", "a2").withNarrowField("hostname", "b2");

        assertThat(scope.fieldFilters()).containsExactlyEntriesOf(Map.of("host", List.of("b2")));
        assertThat(scope.fieldAliases()).isEqualTo(aliased.fieldAliases());
    }

    @Test
    void excludingAFilteredValueRemovesOnlyThatValueAndExcludesIt() {
        Scope multi = base.withFieldValue("host", "a2").withExcludeField("host", "a1");
        assertThat(multi.fieldFilters()).containsExactlyEntriesOf(Map.of("host", List.of("a2")));
        assertThat(multi.exclude()).containsExactly(FieldTerm.of("host", "a1").orElseThrow().encode());

        Scope scope = base.withExcludeField("host", "a1");
        assertThat(scope.fieldFilters()).isEmpty();
        assertThat(scope.exclude()).containsExactly(FieldTerm.of("host", "a1").orElseThrow().encode());
    }

    @Test
    void withoutFilterRemovesTheFilterAndItsAliases() {
        assertThat(aliased.withoutFilter("host").fieldFilters()).isEmpty();
        assertThat(aliased.withoutFilter("host").fieldAliases()).isEmpty();
        assertThat(aliased.withoutFilter("missing")).isSameAs(aliased);
    }

    @Test
    void anInvalidFieldTermLeavesTheScopeUnchanged() {
        Scope scope = Scope.of("web", Map.of());
        assertThat(scope.withNarrowField("a b", "x")).isSameAs(scope);
        assertThat(scope.withFieldValue("a b", "x")).isSameAs(scope);
        assertThat(scope.withExcludeField("level", null)).isSameAs(scope);
    }

    @Test
    void aNarrowedValueThatEqualsTheFilterIsUnchanged() {
        Scope scope = Scope.of("web", Map.of("level", "DEBUG"));
        assertThat(scope.withNarrowField("level", "DEBUG")).isSameAs(scope);
    }

    @Test
    void aFieldTermIsExcludableEvenWhenItsValueIsAWildcard() {
        String term = FieldTerm.of("level", "*").orElseThrow().encode();
        assertThat(Scope.isExcludable(term)).isTrue();
    }

    private final Scope aliased = new Scope("web", Map.of("host", List.of("a1")), List.of(), List.of(), null,
            Map.of("host", List.of("hostname")));

    @Test
    void aliasesAreNormalizedToFilterKeysWithoutBlanksSelfReferencesOrDuplicates() {
        Map<String, List<String>> aliases = new java.util.HashMap<>();
        aliases.put("host", new ArrayList<>(List.of("hostname", "hostname", "host", " ", "")));
        aliases.put("zone", List.of());
        aliases.put("app", List.of("application"));
        Scope scope = new Scope("web", Map.of("host", List.of("a1"), "zone", List.of("z")), List.of(), List.of(), null,
                aliases);

        assertThat(scope.fieldAliases()).containsOnlyKeys("host");
        assertThat(scope.fieldAliases().get("host")).containsExactly("hostname");
        assertThatThrownBy(() -> scope.fieldAliases().put("x", List.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new Scope("web", Map.of("host", List.of("a1")), List.of(), List.of(), null, null).fieldAliases())
                .isEmpty();
        assertThat(base.fieldAliases()).isEmpty();
    }

    @Test
    void aliasesAreCarriedThroughDerivedScopes() {
        assertThat(aliased.withNarrow("timeout").fieldAliases()).isEqualTo(aliased.fieldAliases());
        assertThat(aliased.withExclude("timeout").fieldAliases()).isEqualTo(aliased.fieldAliases());
        assertThat(aliased.withNarrowField("level", "INFO").fieldAliases()).isEqualTo(aliased.fieldAliases());
        assertThat(aliased.withExcludeField("level", "INFO").fieldAliases()).isEqualTo(aliased.fieldAliases());
        assertThat(aliased.withNarrow("timeout").without("timeout").fieldAliases())
                .isEqualTo(aliased.fieldAliases());
        assertThat(aliased.withTerms(List.of("a"), List.of()).fieldAliases()).isEqualTo(aliased.fieldAliases());
    }

    @Test
    void filterFieldsListTheKeyThenItsAliases() {
        assertThat(aliased.filterFields("host")).containsExactly("host", "hostname");
        assertThat(base.filterFields("host")).containsExactly("host");
    }

    @Test
    void filtersOnTheKeysAndTheirAliases() {
        assertThat(aliased.filtersOn("host")).isTrue();
        assertThat(aliased.filtersOn("hostname")).isTrue();
        assertThat(aliased.filtersOn("app")).isFalse();
        assertThat(aliased.filtersOn(null)).isFalse();
    }
}
