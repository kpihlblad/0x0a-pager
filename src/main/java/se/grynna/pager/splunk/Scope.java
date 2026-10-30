package se.grynna.pager.splunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What log is being browsed: an index, its field filters and the text terms folded
 * into the scope. Everything else (browsing, search, matches) works inside it.
 * <p>
 * {@code index} is the {@link IndexSelection#key() selection key}: one index, several
 * ({@code a,b}), all ({@code *}) or none ({@code null} or blank). Use {@link #indexSelection()}
 * to render or judge it.
 * <p>
 * {@code narrow} terms keep only events containing the term, {@code exclude} terms
 * drop them. In advanced mode {@code manualSpl} is the raw query; it is {@code null}
 * for the guided (structured) scope. {@link SplQuery#scope(Scope)} renders it.
 * <p>
 * {@code fieldAliases} lists, per field filter, the other real field names that the
 * filter also accepts (e.g. {@code kubernetes_pod_name} for {@code kubernetes.pod_name}):
 * the filter matches when any of its fields equals the value. Aliases of keys that are
 * not field filters are dropped.
 * <p>
 * Immutable: the collections are copied. Field filters keep their insertion order,
 * because it is the order they appear in the rendered query. Blank and duplicate
 * terms are dropped.
 */
public record Scope(String index, Map<String, String> fieldFilters, List<String> narrow, List<String> exclude,
        String manualSpl, Map<String, List<String>> fieldAliases) {

    public Scope {
        fieldFilters = fieldFilters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fieldFilters));
        narrow = terms(narrow);
        exclude = terms(exclude);
        fieldAliases = aliases(fieldAliases, fieldFilters);
    }

    /** A scope without field aliases. */
    public Scope(String index, Map<String, String> fieldFilters, List<String> narrow, List<String> exclude,
            String manualSpl) {
        this(index, fieldFilters, narrow, exclude, manualSpl, Map.of());
    }

    /** A guided scope without text terms. */
    public static Scope of(String index, Map<String, String> fieldFilters) {
        return new Scope(index, fieldFilters, List.of(), List.of(), null);
    }

    /** An advanced-mode scope: the raw query, with no text terms yet. */
    public static Scope manual(String index, String manualSpl) {
        return new Scope(index, Map.of(), List.of(), List.of(), manualSpl);
    }

    /** The indexes this scope browses, parsed from its selection key. */
    public IndexSelection indexSelection() {
        return IndexSelection.parse(index);
    }

    private static List<String> terms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(terms.size());
        for (String term : terms) {
            if (term != null && !term.isBlank() && !result.contains(term)) {
                result.add(term);
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, List<String>> aliases(Map<String, List<String>> aliases, Map<String, String> filters) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (aliases != null) {
            aliases.forEach((key, names) -> {
                if (key == null || !filters.containsKey(key) || names == null) {
                    return;
                }
                List<String> kept = new ArrayList<>(names.size());
                for (String name : names) {
                    if (name != null && !name.isBlank() && !name.equals(key) && !kept.contains(name)) {
                        kept.add(name);
                    }
                }
                if (!kept.isEmpty()) {
                    result.put(key, List.copyOf(kept));
                }
            });
        }
        return Collections.unmodifiableMap(result);
    }

    /** The real fields a field filter matches: {@code key} first, then its aliases. */
    public List<String> filterFields(String key) {
        List<String> aliases = fieldAliases.get(key);
        if (aliases == null) {
            return List.of(key);
        }
        List<String> fields = new ArrayList<>(aliases.size() + 1);
        fields.add(key);
        fields.addAll(aliases);
        return List.copyOf(fields);
    }

    /** Whether {@code field} is a field filter's key or one of its aliases. */
    public boolean filtersOn(String field) {
        if (field == null) {
            return false;
        }
        return fieldFilters.containsKey(field)
                || fieldAliases.values().stream().anyMatch(names -> names.contains(field));
    }

    public boolean hasTerms() {
        return !narrow.isEmpty() || !exclude.isEmpty();
    }

    public boolean hasTerm(String term) {
        return narrow.contains(term) || exclude.contains(term);
    }

    /**
     * The same scope also requiring {@code term}. A term that was excluded is moved:
     * requiring and excluding the same term would leave nothing.
     */
    public Scope withNarrow(String term) {
        if (term == null || term.isBlank() || narrow.contains(term)) {
            return this;
        }
        List<String> narrowed = new ArrayList<>(narrow);
        narrowed.add(term);
        List<String> excluded = new ArrayList<>(exclude);
        excluded.remove(term);
        return new Scope(index, fieldFilters, narrowed, excluded, manualSpl, fieldAliases);
    }

    /** The same scope also dropping events containing {@code term}; moves a narrowing term. */
    public Scope withExclude(String term) {
        if (term == null || term.isBlank() || exclude.contains(term)) {
            return this;
        }
        List<String> excluded = new ArrayList<>(exclude);
        excluded.add(term);
        List<String> narrowed = new ArrayList<>(narrow);
        narrowed.remove(term);
        return new Scope(index, fieldFilters, narrowed, excluded, manualSpl, fieldAliases);
    }

    /** The same scope also requiring events whose {@code field} equals {@code value}; unchanged when invalid. */
    public Scope withNarrowField(String field, String value) {
        return FieldTerm.of(field, value).map(term -> withNarrow(term.encode())).orElse(this);
    }

    /** The same scope also dropping events whose {@code field} equals {@code value}; unchanged when invalid. */
    public Scope withExcludeField(String field, String value) {
        return FieldTerm.of(field, value).map(term -> withExclude(term.encode())).orElse(this);
    }

    /** The same scope without {@code term}, narrowing or excluded. */
    public Scope without(String term) {
        if (!hasTerm(term)) {
            return this;
        }
        List<String> narrowed = new ArrayList<>(narrow);
        narrowed.remove(term);
        List<String> excluded = new ArrayList<>(exclude);
        excluded.remove(term);
        return new Scope(index, fieldFilters, narrowed, excluded, manualSpl, fieldAliases);
    }

    /** The same scope with its text terms replaced. */
    public Scope withTerms(List<String> narrow, List<String> exclude) {
        return new Scope(index, fieldFilters, narrow, exclude, manualSpl, fieldAliases);
    }

    /**
     * Whether {@code term} may be excluded: a field term always may; a text term needs at least one
     * character that is not a wildcard or whitespace ({@code *} alone would drop every event).
     */
    public static boolean isExcludable(String term) {
        if (term == null) {
            return false;
        }
        if (FieldTerm.isFieldTerm(term)) {
            return true;
        }
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c != '*' && !Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }
}
