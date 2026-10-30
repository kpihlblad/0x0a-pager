package se.grynna.pager.splunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SplQueryTest {

    @Test
    void quoteWrapsPlainValue() {
        assertThat(SplQuery.quote("timeout")).isEqualTo("\"timeout\"");
    }

    @Test
    @DisplayName("Quotes, backslashes and a trailing backslash stay literal")
    void quoteEscapesQuotesAndBackslashes() {
        assertThat(SplQuery.quote("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(SplQuery.quote("C:\\temp\\x")).isEqualTo("\"C:\\\\temp\\\\x\"");
        assertThat(SplQuery.quote("ends with\\")).isEqualTo("\"ends with\\\\\"");
        assertThat(SplQuery.quote("\\\"")).isEqualTo("\"\\\\\\\"\"");
    }

    @Test
    @DisplayName("Guided filters encode their value as a literal")
    void fieldEqualsEncodesValue() {
        assertThat(SplQuery.fieldEquals("host", "web-1")).isEqualTo("host=\"web-1\"");
        assertThat(SplQuery.fieldEquals("path", "a\"b\\")).isEqualTo("path=\"a\\\"b\\\\\"");
    }

    @Test
    void baselineAddsImplicitSearchToPlainPredicate() {
        assertThat(SplQuery.baseline("index=\"app\" host=\"web\"")).isEqualTo("search index=\"app\" host=\"web\"");
        assertThat(SplQuery.baseline("  index=app | head 100 ")).isEqualTo("search index=app | head 100");
        // "searchable" is a search term, not the search command
        assertThat(SplQuery.baseline("searchable=1")).isEqualTo("search searchable=1");
    }

    @Test
    void baselineKeepsExplicitSearchCommand() {
        assertThat(SplQuery.baseline("search index=app")).isEqualTo("search index=app");
        assertThat(SplQuery.baseline("SEARCH index=app")).isEqualTo("SEARCH index=app");
        assertThat(SplQuery.baseline("search\tindex=app")).isEqualTo("search\tindex=app");
    }

    @Test
    @DisplayName("a baseline starting with a generating command is not prefixed with search")
    void baselineKeepsLeadingPipe() {
        assertThat(SplQuery.baseline("| tstats count where index=app by _time"))
                .isEqualTo("| tstats count where index=app by _time");
        assertThat(SplQuery.baseline("  |inputlookup hosts.csv")).isEqualTo("|inputlookup hosts.csv");
        assertThat(SplQuery.withTextFilter(SplQuery.baseline("| inputlookup hosts.csv"), "web"))
                .isEqualTo("| inputlookup hosts.csv | search \"web\"");
    }

    @Test
    @DisplayName("Text search is its own stage on a simple baseline")
    void textFilterOnSimpleBaseline() {
        assertThat(SplQuery.withTextFilter("search index=app", "timeout"))
                .isEqualTo("search index=app | search \"timeout\"");
    }

    @Test
    @DisplayName("Text search does not become an argument of the baseline's last command")
    void textFilterOnPipedBaseline() {
        assertThat(SplQuery.withTextFilter("search index=app | head 100", "timeout"))
                .isEqualTo("search index=app | head 100 | search \"timeout\"");
    }

    @Test
    @DisplayName("Text search term with quotes and a trailing backslash stays literal")
    void textFilterEscapesTerm() {
        assertThat(SplQuery.withTextFilter("search index=app", "a \"b\" c\\"))
                .isEqualTo("search index=app | search \"a \\\"b\\\" c\\\\\"");
    }

    // ---- Scope rendering ----

    @Test
    @DisplayName("a scope without text terms renders exactly the baseline the index panel always built")
    void scopeWithoutTermsMatchesTheOldBaseline() {
        Scope scope = Scope.of("web", new LinkedHashMap<>(Map.of("host", "a1")));
        assertThat(SplQuery.scope(scope)).isEqualTo(SplQuery.baseline("index=\"web\" host=\"a1\""));
        assertThat(SplQuery.scope(scope)).isEqualTo("search index=\"web\" host=\"a1\"");

        assertThat(SplQuery.scope(Scope.of("web", Map.of()))).isEqualTo("search index=\"web\"");
        // No index chosen: the filters stand alone
        assertThat(SplQuery.scope(Scope.of(null, Map.of("host", "a1")))).isEqualTo("search host=\"a1\"");
        assertThat(SplQuery.scope(new Scope("web", Map.of(), List.of(), List.of(), null)))
                .isEqualTo("search index=\"web\"");
    }

    @Test
    @DisplayName("a scope over several indexes or all of them renders the selection's predicate")
    void scopeRendersASelectionOfIndexes() {
        assertThat(SplQuery.scope(Scope.of("b,a", Map.of("host", "h"))))
                .isEqualTo("search (index=\"a\" OR index=\"b\") host=\"h\"");
        assertThat(SplQuery.scope(Scope.of("*", Map.of()))).isEqualTo("search index=*");
        assertThat(SplQuery.scope(Scope.of("*", Map.of("host", "h")))).isEqualTo("search index=* host=\"h\"");
    }

    @Test
    @DisplayName("field filters keep the order they were given and blank values are skipped")
    void scopeKeepsFieldFilterOrder() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("zone", "z");
        filters.put("app", "a");
        filters.put("blank", " ");
        assertThat(SplQuery.scope(Scope.of("web", filters))).isEqualTo("search index=\"web\" zone=\"z\" app=\"a\"");
    }

    @Test
    @DisplayName("a field filter with aliases matches when any of its fields equals the value")
    void scopeRendersAliasesAsOrGroup() {
        Scope aliased = new Scope("web", Map.of("kubernetes.pod_name", "p1"), List.of(), List.of(), null,
                Map.of("kubernetes.pod_name", List.of("kubernetes_pod_name")));
        assertThat(SplQuery.scope(aliased))
                .isEqualTo("search index=\"web\" (kubernetes.pod_name=\"p1\" OR kubernetes_pod_name=\"p1\")");
        assertThat(SplQuery.scope(Scope.of("web", Map.of("kubernetes.pod_name", "p1"))))
                .isEqualTo("search index=\"web\" kubernetes.pod_name=\"p1\"");
    }

    @Test
    void fieldEqualsAnySingleOrAnyOfSeveralFields() {
        assertThat(SplQuery.fieldEqualsAny(List.of("host"), "a1")).isEqualTo("host=\"a1\"");
        assertThat(SplQuery.fieldEqualsAny(List.of("a", "b"), "v")).isEqualTo("(a=\"v\" OR b=\"v\")");
    }

    @Test
    @DisplayName("Every narrowing term and every excluded term is its own pipeline stage, narrow first")
    void scopeRendersTermsAsSeparateStages() {
        Scope scope = new Scope("web", Map.of("host", "a1"), List.of("timeout", "user 42"),
                List.of("healthcheck", "GET /ping"), null);
        assertThat(SplQuery.scope(scope)).isEqualTo("search index=\"web\" host=\"a1\""
                + " | search \"timeout\" | search \"user 42\""
                + " | search NOT \"healthcheck\" | search NOT \"GET /ping\"");
    }

    @Test
    @DisplayName("Scope terms with quotes and backslashes stay literal, also when excluded")
    void scopeEscapesTerms() {
        Scope scope = new Scope("web", Map.of(), List.of("say \"hi\""), List.of("C:\\temp\\", "ends\\"), null);
        assertThat(SplQuery.scope(scope)).isEqualTo("search index=\"web\" | search \"say \\\"hi\\\"\""
                + " | search NOT \"C:\\\\temp\\\\\" | search NOT \"ends\\\\\"");
    }

    @Test
    @DisplayName("manual SPL: the raw query comes first, then the term stages")
    void scopeAppendsStagesToManualSpl() {
        Scope manual = new Scope("web", Map.of("ignored", "x"), List.of("timeout"), List.of("noise"),
                "index=app | head 100");
        assertThat(SplQuery.scope(manual))
                .isEqualTo("search index=app | head 100 | search \"timeout\" | search NOT \"noise\"");
        assertThat(SplQuery.scope(Scope.manual("web", "| inputlookup hosts.csv")))
                .isEqualTo("| inputlookup hosts.csv");
        assertThat(SplQuery.scope(Scope.manual("web", "search index=app").withExclude("noise")))
                .isEqualTo("search index=app | search NOT \"noise\"");
    }

    @Test
    @DisplayName("Field terms render as their own stages next to the text terms, value escaped")
    void scopeRendersFieldTermsInTheirListOrder() {
        Scope scope = Scope.of("web", Map.of()).withNarrow("timeout")
                .withNarrowField("msg", "say \"hi\"\\x").withExcludeField("level", "DEBUG").withExclude("noise");
        assertThat(SplQuery.scope(scope)).isEqualTo("search index=\"web\" | search \"timeout\""
                + " | search msg=\"say \\\"hi\\\"\\\\x\" | search NOT level=\"DEBUG\" | search NOT \"noise\"");
    }

    @Test
    void aTextTermLookingLikeAFieldTermIsSearchedAsText() {
        Scope scope = Scope.of("web", Map.of()).withNarrow("level=\"DEBUG\"");
        assertThat(SplQuery.scope(scope)).isEqualTo("search index=\"web\" | search \"level=\\\"DEBUG\\\"\"");
    }

    @Test
    void scopeWithTermsStillAcceptsATextSearchOnTop() {
        Scope scope = Scope.of("web", Map.of()).withNarrow("a").withExclude("b");
        assertThat(SplQuery.withTextFilter(SplQuery.scope(scope), "c"))
                .isEqualTo("search index=\"web\" | search \"a\" | search NOT \"b\" | search \"c\"");
    }
}
