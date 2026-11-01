package se.grynna.pager.splunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What log is being browsed: an index, its field filters and the text terms folded
 * into the scope. Everything else (browsing, search, matches) works inside it.
 * <p>
 * {@code index} is the {@link IndexSelection#key() selection key}: one index, several
 * ({@code a,b}), all ({@code *}) or none ({@code null} or blank). Use {@link #indexSelection()}
 * to render or judge it.
 * <p>
 * {@code fieldFilters} maps each filtered field to its non-empty list of allowed values (ORed
 * together: {@code (field="a" OR field="b")}). Blank and duplicate values are dropped; a filter
 * with no non-blank values is dropped.
 * <p>
 * {@code narrow} terms keep only events containing the term, {@code exclude} terms
 * drop them. In advanced mode {@code manualSpl} is the raw query; it is {@code null}
 * for the guided (structured) scope. {@link SplQuery#scope(Scope)} renders it.
 * <p>
 * In a guided scope a narrowing field term ({@link FieldTerm}) is normalized into
 * {@code fieldFilters} when no filter covers that field yet, dropped when the covering filter
 * already contains the value, or kept as a narrowing term when the covering filter has other
 * values. A manual scope keeps it as a term.
 * <p>
 * {@code fieldAliases} lists, per field filter, the other real field names that the
 * filter also accepts (e.g. {@code kubernetes_pod_name} for {@code kubernetes.pod_name}):
 * the filter matches when any of its fields equals any of its values. Aliases of keys that are
 * not field filters are dropped.
 * <p>
 * Immutable: the collections are copied. Field filters and their values keep their insertion order,
 * because it is the order they appear in the rendered query. Blank and duplicate
 * terms are dropped.
 */
public record Scope(String index, Map<String, List<String>> fieldFilters, List<String> narrow, List<String> exclude,
        String manualSpl, Map<String, List<String>> fieldAliases) {

    public Scope {
        fieldFilters = filters(fieldFilters);
        narrow = terms(narrow);
        exclude = terms(exclude);
        Map<String, List<String>> filterAliases = aliases(fieldAliases, fieldFilters);
        if (manualSpl == null) {
            Map<String, List<String>> filters = new LinkedHashMap<>(fieldFilters);
            List<String> kept = new ArrayList<>(narrow.size());
            for (String term : narrow) {
                Optional<FieldTerm> field = FieldTerm.decode(term);
                if (field.isEmpty()) {
                    kept.add(term);
                    continue;
                }
                String key = coveringKey(field.get().field(), filters, filterAliases);
                if (key == null) {
                    List<String> normalized = terms(List.of(field.get().value()));
                    if (!normalized.isEmpty()) {
                        filters.put(field.get().field(), normalized);
                    }
                } else if (!filters.get(key).contains(field.get().value())) {
                    kept.add(term);
                }
            }
            fieldFilters = Collections.unmodifiableMap(filters);
            narrow = List.copyOf(kept);
        }
        fieldAliases = aliases(filterAliases, fieldFilters);
    }

    /** A scope without field aliases. */
    public Scope(String index, Map<String, List<String>> fieldFilters, List<String> narrow, List<String> exclude,
            String manualSpl) {
        this(index, fieldFilters, narrow, exclude, manualSpl, Map.of());
    }

    /** A guided scope without text terms, with one value per field filter. */
    public static Scope of(String index, Map<String, String> fieldFilters) {
        Map<String, List<String>> multi = new LinkedHashMap<>();
        if (fieldFilters != null) {
            fieldFilters.forEach((field, value) -> {
                if (field != null && value != null) {
                    multi.put(field, List.of(value));
                }
            });
        }
        return new Scope(index, multi, List.of(), List.of(), null);
    }

    /** A guided scope without text terms, with one or more ORed values per field filter. */
    public static Scope ofValues(String index, Map<String, List<String>> fieldFilters) {
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

    private static Map<String, List<String>> filters(Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        filters.forEach((key, values) -> {
            if (key == null || key.isBlank()) {
                return;
            }
            List<String> kept = terms(values);
            if (!kept.isEmpty()) {
                result.put(key, kept);
            }
        });
        return Collections.unmodifiableMap(result);
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

    private static Map<String, List<String>> aliases(Map<String, List<String>> aliases,
            Map<String, List<String>> filters) {
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

    /** The key of the field filter that covers {@code field} (the key itself or one of its aliases), or null. */
    private static String coveringKey(String field, Map<String, List<String>> filters,
            Map<String, List<String>> aliases) {
        if (filters.containsKey(field)) {
            return field;
        }
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            if (filters.containsKey(entry.getKey()) && entry.getValue().contains(field)) {
                return entry.getKey();
            }
        }
        return null;
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

    /**
     * The same scope also accepting events whose {@code field} equals {@code value} as an OR alternative on the
     * field filter that covers {@code field} (or a new filter); unchanged when invalid or blank. A manual scope gets
     * a narrowing term. Removes an identical excluded field term.
     */
    public Scope withFieldValue(String field, String value) {
        Optional<FieldTerm> term = FieldTerm.of(field, value);
        if (term.isEmpty() || value.isBlank()) {
            return this;
        }
        if (manualSpl != null) {
            return withNarrow(term.get().encode());
        }
        String key = coveringKey(field, fieldFilters, fieldAliases);
        String encoded = term.get().encode();
        if (key != null && fieldFilters.get(key).contains(value) && !exclude.contains(encoded)) {
            return this;
        }
        Map<String, List<String>> filters = new LinkedHashMap<>(fieldFilters);
        String targetKey = key != null ? key : field;
        List<String> values = new ArrayList<>(filters.getOrDefault(targetKey, List.of()));
        if (!values.contains(value)) {
            values.add(value);
        }
        filters.put(targetKey, List.copyOf(values));
        List<String> excluded = new ArrayList<>(exclude);
        excluded.remove(encoded);
        return new Scope(index, filters, narrow, excluded, manualSpl, fieldAliases);
    }

    /**
     * The same scope without {@code value} in the field filter that covers {@code key}; drops the filter when its
     * last value goes. Unchanged when no filter covers {@code key} or it does not contain {@code value}.
     */
    public Scope withoutFieldValue(String key, String value) {
        if (key == null || value == null) {
            return this;
        }
        String covering = coveringKey(key, fieldFilters, fieldAliases);
        if (covering == null) {
            return this;
        }
        List<String> current = fieldFilters.get(covering);
        if (!current.contains(value)) {
            return this;
        }
        if (current.size() == 1) {
            return withoutFilter(covering);
        }
        List<String> remaining = new ArrayList<>(current);
        remaining.remove(value);
        Map<String, List<String>> filters = new LinkedHashMap<>(fieldFilters);
        filters.put(covering, List.copyOf(remaining));
        return new Scope(index, filters, narrow, exclude, manualSpl, fieldAliases);
    }

    /**
     * The same scope also requiring events whose {@code field} equals {@code value}; unchanged when invalid. A guided
     * scope sets the field filter that covers {@code field} to {@code [value]} (or adds one); a manual scope gets a
     * narrowing term.
     */
    public Scope withNarrowField(String field, String value) {
        Optional<FieldTerm> term = FieldTerm.of(field, value);
        if (term.isEmpty() || value.isBlank()) {
            return this;
        }
        if (manualSpl != null) {
            return withNarrow(term.get().encode());
        }
        String key = coveringKey(field, fieldFilters, fieldAliases);
        String encoded = term.get().encode();
        if (key != null && List.of(value).equals(fieldFilters.get(key)) && !exclude.contains(encoded)) {
            return this;
        }
        Map<String, List<String>> filters = new LinkedHashMap<>(fieldFilters);
        filters.put(key != null ? key : field, List.of(value));
        List<String> excluded = new ArrayList<>(exclude);
        excluded.remove(encoded);
        return new Scope(index, filters, narrow, excluded, manualSpl, fieldAliases);
    }

    /**
     * The same scope also dropping events whose {@code field} equals {@code value}; unchanged when invalid. A guided
     * scope whose field filter contains {@code value} removes that value (and the filter when it was the last), as a
     * narrowing term moves to the excluded ones.
     */
    public Scope withExcludeField(String field, String value) {
        Optional<FieldTerm> term = FieldTerm.of(field, value);
        if (term.isEmpty()) {
            return this;
        }
        String key = manualSpl == null ? coveringKey(field, fieldFilters, fieldAliases) : null;
        if (key != null && fieldFilters.get(key).contains(value)) {
            return withoutFieldValue(key, value).withExclude(term.get().encode());
        }
        return withExclude(term.get().encode());
    }

    /** The same scope without the field filter {@code key} and its aliases; unchanged when there is no such filter. */
    public Scope withoutFilter(String key) {
        if (!fieldFilters.containsKey(key)) {
            return this;
        }
        Map<String, List<String>> filters = new LinkedHashMap<>(fieldFilters);
        filters.remove(key);
        return new Scope(index, filters, narrow, exclude, manualSpl, fieldAliases);
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
