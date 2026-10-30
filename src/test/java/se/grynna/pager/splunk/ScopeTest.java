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
        Map<String, String> filters = new java.util.HashMap<>(Map.of("host", "a1"));
        Scope scope = new Scope("web", filters, narrow, null, null);
        narrow.add("b");
        filters.put("x", "y");
        assertThat(scope.narrow()).containsExactly("a");
        assertThat(scope.fieldFilters()).containsOnlyKeys("host");
        assertThat(scope.exclude()).isEmpty();
        assertThatThrownBy(() -> scope.narrow().add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.fieldFilters().put("c", "d")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankAndDuplicateTermsAreDropped() {
        Scope scope = new Scope("web", Map.of(), List.of("a", " ", "a", ""), List.of("b", "b"), null);
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
    void fieldTermsAreAddedToTheSameListsAndRemovedByWithout() {
        Scope scope = Scope.of("web", Map.of()).withNarrowField("level", "DEBUG")
                .withExcludeField("host", "a*");
        String debug = FieldTerm.of("level", "DEBUG").orElseThrow().encode();
        String host = FieldTerm.of("host", "a*").orElseThrow().encode();

        assertThat(scope.narrow()).containsExactly(debug);
        assertThat(scope.exclude()).containsExactly(host);
        assertThat(scope.hasTerm(debug)).isTrue();
        assertThat(scope.without(debug).narrow()).isEmpty();
        assertThat(scope.without(host).exclude()).isEmpty();
    }

    @Test
    void anInvalidFieldTermLeavesTheScopeUnchanged() {
        Scope scope = Scope.of("web", Map.of());
        assertThat(scope.withNarrowField("a b", "x")).isSameAs(scope);
        assertThat(scope.withExcludeField("level", null)).isSameAs(scope);
    }

    @Test
    void aFieldTermMovesBetweenNarrowAndExclude() {
        Scope scope = Scope.of("web", Map.of()).withNarrowField("level", "DEBUG");
        Scope excluded = scope.withExcludeField("level", "DEBUG");

        assertThat(excluded.narrow()).isEmpty();
        assertThat(excluded.exclude()).containsExactly(FieldTerm.of("level", "DEBUG").orElseThrow().encode());
    }

    @Test
    void aFieldTermIsExcludableEvenWhenItsValueIsAWildcard() {
        String term = FieldTerm.of("level", "*").orElseThrow().encode();
        assertThat(Scope.isExcludable(term)).isTrue();
    }

    private final Scope aliased = new Scope("web", Map.of("host", "a1"), List.of(), List.of(), null,
            Map.of("host", List.of("hostname")));

    @Test
    void aliasesAreNormalizedToFilterKeysWithoutBlanksSelfReferencesOrDuplicates() {
        Map<String, List<String>> aliases = new java.util.HashMap<>();
        aliases.put("host", new ArrayList<>(List.of("hostname", "hostname", "host", " ", "")));
        aliases.put("zone", List.of());
        aliases.put("app", List.of("application"));
        Scope scope = new Scope("web", Map.of("host", "a1", "zone", "z"), List.of(), List.of(), null, aliases);

        assertThat(scope.fieldAliases()).containsOnlyKeys("host");
        assertThat(scope.fieldAliases().get("host")).containsExactly("hostname");
        assertThatThrownBy(() -> scope.fieldAliases().put("x", List.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new Scope("web", Map.of("host", "a1"), List.of(), List.of(), null, null).fieldAliases()).isEmpty();
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
