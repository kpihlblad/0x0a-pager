package se.grynna.pager.splunk;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Building blocks for generated SPL. All user-supplied values that end up in a
 * query go through {@link #quote(String)} so quotes and backslashes stay literal.
 */
public final class SplQuery {

    private SplQuery() {}

    /**
     * Encode a value as an SPL double-quoted string literal. Backslashes are
     * escaped before quotes, so a value ending in a backslash cannot escape the
     * closing quote.
     */
    public static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    /**
     * Turn a user-entered query into a runnable search. A plain predicate gets
     * the implicit {@code search} command; a query that already starts with
     * {@code search} or with a pipe (a generating command such as
     * {@code | tstats}) is kept as it is.
     */
    public static String baseline(String query) {
        String trimmed = query.strip();
        if (trimmed.startsWith("|") || (trimmed.regionMatches(true, 0, "search", 0, 6)
                && (trimmed.length() == 6 || Character.isWhitespace(trimmed.charAt(6))))) {
            return trimmed;
        }
        return "search " + trimmed;
    }

    /** A {@code field="value"} predicate with the value encoded as a literal. */
    public static String fieldEquals(String field, String value) {
        return field + "=" + quote(value);
    }

    /**
     * A predicate that matches when any of {@code fields} equals {@code value}: {@link #fieldEquals} for one field,
     * {@code (a="v" OR b="v")} for several. {@code fields} must not be empty.
     */
    public static String fieldEqualsAny(List<String> fields, String value) {
        return fieldEqualsAny(fields, List.of(value));
    }

    /**
     * A predicate that matches when any of {@code fields} equals any of {@code values}: {@link #fieldEquals} for one
     * field and one value, {@code (a="v1" OR a="v2" OR b="v1" OR b="v2")} for several. Neither list may be empty.
     */
    public static String fieldEqualsAny(List<String> fields, List<String> values) {
        if (fields.size() == 1 && values.size() == 1) {
            return fieldEquals(fields.get(0), values.get(0));
        }
        return fields.stream()
                .flatMap(field -> values.stream().map(value -> fieldEquals(field, value)))
                .collect(Collectors.joining(" OR ", "(", ")"));
    }

    /**
     * Restrict the events produced by {@code baselineQuery} to those containing
     * {@code term}. The term is applied as its own {@code | search} stage after
     * the whole baseline pipeline, so it never becomes an argument to the
     * baseline's last command (e.g. {@code | head 100}).
     */
    public static String withTextFilter(String baselineQuery, String term) {
        return baselineQuery + " | search " + quote(term);
    }

    /**
     * Render narrowing and excluded terms as pipeline stages: {@code  | search "term"} per narrow term and
     * {@code  | search NOT "term"} (or {@code field="value"}) per excluded term, or empty when both lists are empty.
     */
    public static String termStages(List<String> narrow, List<String> exclude) {
        StringBuilder stages = new StringBuilder();
        for (String term : narrow) {
            stages.append(" | search ").append(termPredicate(term));
        }
        for (String term : exclude) {
            stages.append(" | search NOT ").append(termPredicate(term));
        }
        return stages.toString();
    }

    /**
     * Render a {@link Scope} as the baseline query of the log. The index and field
     * filters (or the manual SPL) come first, exactly as the baseline has always been
     * built; then every narrowing term is its own {@code | search "term"} stage and
     * every excluded term its own {@code | search NOT "term"} stage, so a term never
     * becomes an argument of a previous command and is encoded as a literal.
     * A field term ({@link FieldTerm}) renders as {@code | search field="value"} (or {@code NOT}) in the same
     * position. A scope without text terms renders to {@link #baseline(String)} of its predicate.
     */
    public static String scope(Scope scope) {
        return baseline(scope.manualSpl() != null ? scope.manualSpl() : predicate(scope))
                + termStages(scope.narrow(), scope.exclude());
    }

    private static String termPredicate(String term) {
        return FieldTerm.decode(term).map(FieldTerm::spl).orElseGet(() -> quote(term));
    }

    /**
     * {@code index="x" field="value" ...}, skipping a blank index and blank values. A field filter with several values
     * or aliases renders as {@code (field="v1" OR field="v2" OR alias="v1" OR alias="v2")}.
     */
    private static String predicate(Scope scope) {
        StringBuilder predicate = new StringBuilder();
        String indexPredicate = scope.indexSelection().predicate();
        if (!indexPredicate.isEmpty()) {
            predicate.append(indexPredicate);
        }
        scope.fieldFilters().forEach((field, values) -> {
            List<String> nonBlank = values.stream().filter(v -> v != null && !v.isBlank()).toList();
            if (!nonBlank.isEmpty()) {
                if (!predicate.isEmpty()) {
                    predicate.append(' ');
                }
                predicate.append(fieldEqualsAny(scope.filterFields(field), nonBlank));
            }
        });
        return predicate.toString();
    }
}
