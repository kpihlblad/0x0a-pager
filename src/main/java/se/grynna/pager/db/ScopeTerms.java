package se.grynna.pager.db;

import java.util.List;

/** The text terms folded into an index's scope: narrowing terms and excluded terms. */
public record ScopeTerms(List<String> narrow, List<String> exclude) {
    public static final ScopeTerms EMPTY = new ScopeTerms(List.of(), List.of());

    public ScopeTerms {
        narrow = narrow == null ? List.of() : List.copyOf(narrow);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public boolean isEmpty() {
        return narrow.isEmpty() && exclude.isEmpty();
    }
}
