package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeMembershipTest {

    private static final Instant T = Instant.parse("2017-03-30T10:00:00Z");

    private static LoadedLogLine event(String raw, Map<String, String> fields) {
        LoadedLogLine line = new LoadedLogLine(MsgType.RAW, "seg", 0, "cd", "bkt", T, raw, T, T, raw, false,
                Level.INFO);
        if (fields != null) {
            line.setEvent(new TreeMap<>(fields));
        }
        return line;
    }

    private static Scope guided(String index, Map<String, String> filters, List<String> narrow, List<String> exclude) {
        return Scope.of(index, filters).withTerms(narrow, exclude);
    }

    @Test
    void anEventOfAnotherIndexIsOutside() {
        LoadedLogLine event = event("x", Map.of("index", "web"));
        assertThat(ScopeMembership.outside(Scope.of("db", Map.of()), event)).isTrue();
        assertThat(ScopeMembership.outside(Scope.of("web", Map.of()), event)).isFalse();
        assertThat(ScopeMembership.outside(Scope.of("WEB", Map.of()), event)).as("index names ignore case").isFalse();
    }

    @Test
    void anEventOfAnyIndexInASelectionOfSeveralIsInsideOnlyIfItIsOneOfThem() {
        Scope scope = Scope.of("a,b", Map.of());
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "b")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "a")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "c")))).isTrue();
    }

    @Test
    void anyEventIsInsideAllIndexes() {
        assertThat(ScopeMembership.outside(Scope.of("*", Map.of()), event("x", Map.of("index", "c")))).isFalse();
    }

    @Test
    void changingFromOneIndexToSeveralJudgesEverythingAsAScopeChange() {
        LoadedLogLine fromA = event("x", Map.of("index", "a", "host", "a1"));
        LoadedLogLine fromC = event("x", Map.of("index", "c", "host", "a1"));
        Scope current = Scope.of("a", Map.of("host", "a1"));
        Scope next = Scope.of("a,b", Map.of("host", "a1"));

        assertThat(ScopeMembership.outsideAfterChange(current, next, List.of(fromA, fromC))).containsExactly(fromC);
    }

    @Test
    void anEventWithoutAnIndexFieldOrAScopeWithoutAnIndexIsNotJudgedByIndex() {
        assertThat(ScopeMembership.outside(Scope.of("db", Map.of()), event("x", Map.of("host", "a")))).isFalse();
        assertThat(ScopeMembership.outside(Scope.of("db", Map.of()), event("x", null))).isFalse();
        assertThat(ScopeMembership.outside(Scope.of(null, Map.of()), event("x", Map.of("index", "web")))).isFalse();
        assertThat(ScopeMembership.outside(Scope.of("", Map.of()), event("x", Map.of("index", "web")))).isFalse();
    }

    @Test
    void aFieldFilterMatchesEqualValuesCaseInsensitively() {
        Scope scope = Scope.of("web", Map.of("host", "A1"));
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "host", "a1")))).isFalse();
    }

    @Test
    void aFieldFilterMismatchIsOutside() {
        Scope scope = Scope.of("web", Map.of("host", "a1"));
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "host", "a2")))).isTrue();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "host", "a1x")))).isTrue();
    }

    @Test
    void anEventWithOnlyTheAliasFieldWithTheValueIsInside() {
        Scope scope = new Scope("web", Map.of("kubernetes.pod_name", List.of("p1")), List.of(), List.of(), null,
                Map.of("kubernetes.pod_name", List.of("kubernetes_pod_name")));
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "kubernetes_pod_name", "p1"))))
                .isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "kubernetes.pod_name", "p1"))))
                .isFalse();
    }

    @Test
    void anEventWithNeitherTheFieldNorTheAliasWithTheValueIsOutside() {
        Scope scope = new Scope("web", Map.of("kubernetes.pod_name", List.of("p1")), List.of(), List.of(), null,
                Map.of("kubernetes.pod_name", List.of("kubernetes_pod_name")));
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "kubernetes_pod_name", "p2"))))
                .isTrue();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "other", "p1")))).isTrue();
    }

    @Test
    void aMultiValueFilterMatchesAnyOfItsValues() {
        Scope scope = Scope.ofValues("web", Map.of("host", List.of("a1", "a2")));
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "host", "a2")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web", "host", "a3")))).isTrue();
    }

    @Test
    void aMissingFieldIsOutside() {
        Scope scope = Scope.of("web", Map.of("host", "a1"));
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("index", "web")))).isTrue();
        assertThat(ScopeMembership.outside(scope, event("x", null))).isTrue();
    }

    @Test
    void aFieldFilterWildcardMatchesAnyRun() {
        Scope scope = Scope.of("web", Map.of("host", "a*"));
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("host", "a1")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("host", "b1")))).isTrue();
    }

    @Test
    void aBlankFilterValueIsNotJudged() {
        Map<String, String> filters = new java.util.HashMap<>();
        filters.put("host", " ");
        assertThat(ScopeMembership.outside(Scope.of("web", filters), event("x", Map.of()))).isFalse();
    }

    @Test
    void aNarrowTermMustBeInRawIgnoringCase() {
        Scope scope = guided("web", Map.of(), List.of("Timeout"), List.of());
        assertThat(ScopeMembership.outside(scope, event("read TIMEOUT after 5s", null))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("all good", null))).isTrue();
    }

    @Test
    void everyNarrowTermMustBePresent() {
        Scope scope = guided("web", Map.of(), List.of("a", "b"), List.of());
        assertThat(ScopeMembership.outside(scope, event("a b", null))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("a only", null))).isTrue();
    }

    @Test
    void anExcludedTermInRawIsOutside() {
        Scope scope = guided("web", Map.of(), List.of(), List.of("healthcheck"));
        assertThat(ScopeMembership.outside(scope, event("GET /HealthCheck 200", null))).isTrue();
        assertThat(ScopeMembership.outside(scope, event("GET /index 200", null))).isFalse();
    }

    @Test
    void aTermWildcardMatchesAnyRunAndRegexCharactersAreLiteral() {
        Scope wildcard = guided("web", Map.of(), List.of("err*out"), List.of());
        assertThat(ScopeMembership.outside(wildcard, event("error: time out", null))).isFalse();
        assertThat(ScopeMembership.outside(wildcard, event("fine", null))).isTrue();

        Scope literal = guided("web", Map.of(), List.of("a.c(1)"), List.of());
        assertThat(ScopeMembership.outside(literal, event("see a.c(1) here", null))).isFalse();
        assertThat(ScopeMembership.outside(literal, event("see abc1 here", null))).isTrue();
    }

    @Test
    void anEventWithoutRawIsNotJudgedByTerms() {
        Scope scope = guided("web", Map.of(), List.of("a"), List.of("b"));
        LoadedLogLine noRaw = new LoadedLogLine(MsgType.RAW, "seg", 0, "cd", "bkt", T, null, T, T, "m", false,
                Level.INFO);
        assertThat(ScopeMembership.outside(scope, noRaw)).isFalse();
    }

    @Test
    void aManualScopeKeepsEventsItCannotJudge() {
        Scope manual = Scope.manual("db", "index=other host=zzz");
        assertThat(ScopeMembership.outside(manual, event("x", Map.of("index", "web", "host", "a")))).isFalse();
    }

    @Test
    void theTermsOfAManualScopeAreStillJudged() {
        Scope manual = Scope.manual("db", "index=other").withNarrow("boom");
        assertThat(ScopeMembership.outside(manual, event("quiet", null))).isTrue();
        assertThat(ScopeMembership.outside(manual, event("boom", null))).isFalse();
    }

    @Test
    void outsideAfterChangeJudgesOnlyWhatTheChangeAdds() {
        // "slow" was already narrowed: the real search accepted these events, whatever the local test says.
        Scope current = guided("web", Map.of("host", "a1"), List.of("slo*w"), List.of());
        Scope next = current.withExclude("noise");
        LoadedLogLine kept = event("slow", Map.of("host", "a1"));
        LoadedLogLine tokenised = event("sl0w (matched by Splunk, not by substring)", Map.of("host", "a1"));
        LoadedLogLine noisy = event("noise", Map.of("host", "a1"));

        assertThat(ScopeMembership.outsideAfterChange(current, next, List.of(kept, tokenised, noisy)))
                .containsExactly(noisy);
        assertThat(ScopeMembership.outside(next, tokenised)).as("a full check would flag it").isTrue();
    }

    @Test
    void outsideAfterChangeJudgesEverythingForAnotherIndexOrNoCurrentScope() {
        LoadedLogLine event = event("x", Map.of("index", "web", "host", "a1"));
        Scope current = Scope.of("web", Map.of("host", "a1"));
        Scope other = Scope.of("db", Map.of("host", "a1"));

        assertThat(ScopeMembership.outsideAfterChange(current, other, List.of(event))).containsExactly(event);
        assertThat(ScopeMembership.outsideAfterChange(null, other, List.of(event))).containsExactly(event);
    }

    @Test
    void outsideAfterChangeJudgesAChangedFieldFilter() {
        Scope current = Scope.of("web", Map.of("host", "a1", "app", "x"));
        Scope next = Scope.of("web", Map.of("host", "a2", "app", "x"));
        LoadedLogLine a1 = event("x", Map.of("host", "a1", "app", "x"));
        LoadedLogLine a2 = event("x", Map.of("host", "a2", "app", "x"));

        assertThat(ScopeMembership.outsideAfterChange(current, next, List.of(a1, a2))).containsExactly(a1);
    }

    @Test
    void outsideAfterChangeIsEmptyForTheSameScopeOrNoEvents() {
        Scope scope = guided("web", Map.of("host", "a1"), List.of("zzz"), List.of());
        assertThat(ScopeMembership.outsideAfterChange(scope, scope, List.of(event("x", null)))).isEmpty();
        assertThat(ScopeMembership.outsideAfterChange(scope, scope.withNarrow("q"), List.of())).isEmpty();
    }

    @Test
    void outsideAfterChangeToAManualScopeKeepsEverythingButNewTerms() {
        Scope current = Scope.of("web", Map.of("host", "a1"));
        Scope manual = Scope.manual("web", "index=web | head 5");
        LoadedLogLine event = event("x", Map.of("host", "zzz"));

        assertThat(ScopeMembership.outsideAfterChange(current, manual, List.of(event))).isEmpty();
    }

    @Test
    void aNarrowingFieldTermKeepsAnEventWithAnEqualValueCaseInsensitively() {
        Scope scope = Scope.of("web", Map.of()).withNarrowField("level", "debug");
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("level", "DEBUG")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("level", "INFO")))).isTrue();
    }

    @Test
    void aNarrowingFieldTermWildcardMatchesTheValue() {
        Scope scope = Scope.of("web", Map.of()).withNarrowField("host", "a*");
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("host", "a12")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("host", "b12")))).isTrue();
    }

    @Test
    void aNarrowingFieldTermOutsideAnEventWithoutTheField() {
        Scope scope = Scope.of("web", Map.of()).withNarrowField("level", "DEBUG");
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("host", "a1")))).isTrue();
        assertThat(ScopeMembership.outside(scope, event("x", null))).isTrue();
    }

    @Test
    void aFieldTermIsNotMatchedAgainstRaw() {
        Scope scope = Scope.of("web", Map.of()).withNarrowField("level", "DEBUG");
        assertThat(ScopeMembership.outside(scope, event("level=\"DEBUG\"", Map.of("level", "INFO")))).isTrue();
        assertThat(ScopeMembership.outside(scope, event(null, Map.of("level", "DEBUG")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event(null, Map.of("level", "INFO")))).isTrue();
    }

    @Test
    void anExcludedFieldTermDropsAnEventWithAnEqualValue() {
        Scope scope = Scope.of("web", Map.of()).withExcludeField("level", "DEBUG");
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("level", "debug")))).isTrue();
        assertThat(ScopeMembership.outside(scope, event("x", Map.of("level", "INFO")))).isFalse();
        assertThat(ScopeMembership.outside(scope, event("x", null))).isFalse();
    }

    @Test
    void textTermsAreJudgedAsBeforeAlongsideFieldTerms() {
        Scope scope = Scope.of("web", Map.of()).withNarrow("timeout").withNarrowField("level", "DEBUG")
                .withExclude("healthcheck");
        LoadedLogLine ok = event("timeout happened", Map.of("level", "DEBUG"));
        assertThat(ScopeMembership.outside(scope, ok)).isFalse();
        assertThat(ScopeMembership.outside(scope, event("timeout happened", Map.of("level", "INFO")))).isTrue();
        assertThat(ScopeMembership.outside(scope, event("timeout healthcheck", Map.of("level", "DEBUG")))).isTrue();
        assertThat(ScopeMembership.outside(scope, event("no match", Map.of("level", "DEBUG")))).isTrue();
    }
}
