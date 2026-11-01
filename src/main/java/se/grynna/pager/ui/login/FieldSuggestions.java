package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.splunk.Scope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ranks index fields for the scope selector's suggested-fields grid and autocompletion, and computes each field's
 * top values with their sample share percentages.
 * <p>
 * Candidate fields must have at least two distinct non-blank values and a top value count greater than one (which
 * drops single-value fields and per-event identifiers or timestamps). Fields that identify the event writer come
 * first in a fixed order matched against the field's normalised last name token, followed by the remaining fields
 * ordered by {@link FieldSummary#getRelevance()}.
 */
public final class FieldSuggestions {

    public static final int MAX_SUGGESTED = 6;
    public static final int MAX_SUGGESTED_VALUES = 4;
    public static final int MAX_POPUP_VALUES = 12;
    static final int REDUNDANT_SHARE_PERCENT = 80;
    /** Splunk's own per-event fields; they describe the indexing, not the event's source. */
    private static final Set<String> SPLUNK_BOOKKEEPING = Set.of("punct", "linecount", "timestartpos",
            "timeendpos", "splunk_server", "splunk_server_group", "eventtype", "tag", "date_hour", "date_mday",
            "date_minute", "date_month", "date_second", "date_wday", "date_year", "date_zone");

    static final List<String> IDENTITY_TOKENS = List.of(
            "namespace",
            "service",
            "servicename",
            "app",
            "application",
            "appname",
            "container",
            "containername",
            "deployment",
            "workload",
            "pod",
            "podname",
            "host",
            "hostname");

    private static final Comparator<SuggestedField> RANK_ORDER = Comparator
            .comparingInt((SuggestedField f) -> identityRank(f.field()))
            .thenComparing(Comparator.comparingDouble((SuggestedField f) -> f.field().relevance()).reversed())
            .thenComparing(SuggestedField::primary);

    /** A sampled value with its occurrence count and rounded percentage of the field's total sampled occurrences. */
    public record ValueShare(String value, long count, int sharePercent) {}

    /** A ranked field with its short display label, top sampled values, and count of remaining values. */
    public record SuggestedField(String label, LogicalField field, List<ValueShare> topValues, int moreCount) {

        public SuggestedField {
            topValues = List.copyOf(topValues);
        }

        public String primary() {
            return field.primary();
        }

        public int valueCount() {
            return nonBlankValueCount(field);
        }
    }

    private FieldSuggestions() {}

    /**
     * Ranks all filterable fields from {@code summaries}: drops single-value and unique-per-event fields, orders
     * identity fields first and the rest by relevance descending.
     */
    public static List<SuggestedField> rank(Collection<FieldSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        return rankEntries(FieldGrouping.group(summaries));
    }

    /**
     * Ranks all filterable fields from pre-grouped {@link FieldGrouping.Entry} items.
     */
    public static List<SuggestedField> rankEntries(List<FieldGrouping.Entry> entries) {
        List<SuggestedField> candidates = new ArrayList<>();
        for (SuggestedField item : allFields(entries)) {
            if (isFilterableCandidate(item.field())) {
                candidates.add(item);
            }
        }
        candidates.sort(RANK_ORDER);
        return List.copyOf(candidates);
    }

    /**
     * All logical fields from {@code entries} in suggestion rank order (filterable candidates first, followed by
     * any remaining fields ordered by relevance and name).
     */
    public static List<SuggestedField> allFields(List<FieldGrouping.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<FieldGrouping.Row> rows = new ArrayList<>();
        Map<String, Integer> labelCounts = new HashMap<>();
        for (FieldGrouping.Entry entry : entries) {
            if (entry instanceof FieldGrouping.Row row) {
                rows.add(row);
                labelCounts.merge(row.label(), 1, Integer::sum);
            }
        }
        Map<String, Integer> depth1Counts = new HashMap<>();
        for (FieldGrouping.Row row : rows) {
            String l1 = row.depth() == 2 ? FieldGrouping.label(row.field().primary(), 1) : row.label();
            depth1Counts.merge(l1, 1, Integer::sum);
        }

        List<SuggestedField> all = new ArrayList<>(rows.size());
        for (FieldGrouping.Row row : rows) {
            String label = shortLabel(row, labelCounts, depth1Counts);
            all.add(toSuggestedField(label, row.field(), MAX_SUGGESTED_VALUES));
        }
        all.sort(Comparator
                .comparing((SuggestedField f) -> !isFilterableCandidate(f.field()))
                .thenComparing(RANK_ORDER));
        return List.copyOf(all);
    }

    /**
     * Returns the top {@value #MAX_SUGGESTED} suggested fields plus any additional fields from {@code summaries}
     * that have a narrowed or excluded value in {@code draft}, in rank order.
     */
    public static List<SuggestedField> suggest(Collection<FieldSummary> summaries, Scope draft) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        return suggestFromEntries(FieldGrouping.group(summaries), draft);
    }

    /**
     * Returns the top {@value #MAX_SUGGESTED} suggested fields from {@code entries} plus any additional fields
     * that have a value in {@code draft}, in rank order.
     */
    public static List<SuggestedField> suggestFromEntries(List<FieldGrouping.Entry> entries, Scope draft) {
        List<SuggestedField> all = allFields(entries);
        List<SuggestedField> ranked = all.stream()
                .filter(f -> isFilterableCandidate(f.field()))
                .toList();

        Set<String> includedPrimaries = new LinkedHashSet<>();
        List<Set<String>> shownValues = new ArrayList<>();
        for (SuggestedField candidate : ranked) {
            if (includedPrimaries.size() >= MAX_SUGGESTED) {
                break;
            }
            Set<String> values = candidate.field().values().keySet();
            if (shownValues.stream().noneMatch(shown -> isRedundant(values, shown))) {
                includedPrimaries.add(candidate.primary());
                shownValues.add(values);
            }
        }
        if (draft != null) {
            for (SuggestedField item : all) {
                if (isInDraft(item.field(), draft)) {
                    includedPrimaries.add(item.primary());
                }
            }
        }
        return all.stream()
                .filter(f -> includedPrimaries.contains(f.primary()))
                .toList();
    }

    /**
     * Whether a field with {@code values} repeats a field already suggested: at least
     * {@value #REDUNDANT_SHARE_PERCENT}% of its values occur in {@code shown} (e.g. {@code app} next to
     * {@code container_name} on Kubernetes).
     */
    static boolean isRedundant(Set<String> values, Set<String> shown) {
        if (values.isEmpty()) {
            return false;
        }
        long common = values.stream().filter(shown::contains).count();
        return common * 100 >= (long) REDUNDANT_SHARE_PERCENT * values.size();
    }

    /** Builds a {@link SuggestedField} with up to {@code maxValues} top values by count descending. */
    public static SuggestedField toSuggestedField(String label, LogicalField field, int maxValues) {
        List<ValueShare> top = topValues(field, maxValues);
        int totalValues = nonBlankValueCount(field);
        int more = Math.max(0, totalValues - top.size());
        return new SuggestedField(label, field, top, more);
    }

    /**
     * Up to {@code limit} non-blank values of {@code field} ordered by count descending (then value ascending),
     * each with its rounded percentage of the field's total sampled occurrences.
     */
    public static List<ValueShare> topValues(LogicalField field, int limit) {
        long sum = totalOccurrences(field);
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        field.values().forEach((value, count) -> {
            if (value != null && !value.isBlank()) {
                entries.add(Map.entry(value, count));
            }
        });
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        int n = Math.min(limit, entries.size());
        List<ValueShare> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Long> e = entries.get(i);
            int share = sum > 0 ? (int) Math.round(e.getValue() * 100.0 / sum) : 0;
            result.add(new ValueShare(e.getKey(), e.getValue(), share));
        }
        return List.copyOf(result);
    }

    /** Total sampled occurrences across all values of {@code field}. */
    public static long totalOccurrences(LogicalField field) {
        long sum = 0;
        for (Long count : field.values().values()) {
            if (count != null && count > 0) {
                sum += count;
            }
        }
        return sum;
    }

    /** Rounded share percentage for {@code count} out of {@code totalOccurrences}. */
    public static int sharePercent(long count, long totalOccurrences) {
        return totalOccurrences > 0 ? (int) Math.round(count * 100.0 / totalOccurrences) : 0;
    }

    /**
     * Whether {@code field} is worth suggesting: not one of Splunk's per-event bookkeeping fields, at least two
     * non-blank values and a top value count greater than one.
     */
    public static boolean isFilterableCandidate(LogicalField field) {
        if (field.fields().stream().anyMatch(SPLUNK_BOOKKEEPING::contains)) {
            return false;
        }
        int count = 0;
        long maxOccurrences = 0;
        for (var entry : field.values().entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                count++;
                long c = entry.getValue() != null ? entry.getValue() : 0L;
                if (c > maxOccurrences) {
                    maxOccurrences = c;
                }
            }
        }
        return count >= 2 && maxOccurrences > 1;
    }

    /**
     * Index of {@code field} in {@link #IDENTITY_TOKENS}, or {@link Integer#MAX_VALUE} when it is not an identity
     * field.
     */
    public static int identityRank(LogicalField field) {
        List<String> tokens = field.tokens();
        if (tokens.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String last = normalizeToken(tokens.getLast());
        if ("name".equals(last) && tokens.size() >= 2) {
            String prev = normalizeToken(tokens.get(tokens.size() - 2));
            String combined = prev + "name";
            int idx = IDENTITY_TOKENS.indexOf(combined);
            if (idx >= 0) {
                return idx;
            }
            if ("namespace".equals(prev)) {
                return IDENTITY_TOKENS.indexOf("namespace");
            }
            return Integer.MAX_VALUE;
        }
        int idx = IDENTITY_TOKENS.indexOf(last);
        return idx >= 0 ? idx : Integer.MAX_VALUE;
    }

    static String normalizeToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '_' && c != '.' && c != '-') {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** Whether {@code draft} has any narrowed or excluded value for {@code field} (primary or alias). */
    public static boolean isInDraft(LogicalField field, Scope draft) {
        if (draft == null) {
            return false;
        }
        for (String name : field.fields()) {
            if (draft.filtersOn(name)) {
                return true;
            }
        }
        for (String term : draft.narrow()) {
            Optional<FieldTerm> ft = FieldTerm.decode(term);
            if (ft.isPresent() && field.fields().contains(ft.get().field())) {
                return true;
            }
        }
        for (String term : draft.exclude()) {
            Optional<FieldTerm> ft = FieldTerm.decode(term);
            if (ft.isPresent() && field.fields().contains(ft.get().field())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code value} of {@code field} is currently narrowed in {@code draft}. */
    public static boolean isValueNarrowed(LogicalField field, String value, Scope draft) {
        if (draft == null || value == null) {
            return false;
        }
        for (var entry : draft.fieldFilters().entrySet()) {
            List<String> covered = draft.filterFields(entry.getKey());
            for (String f : field.fields()) {
                if (covered.contains(f) && entry.getValue().contains(value)) {
                    return true;
                }
            }
        }
        for (String term : draft.narrow()) {
            Optional<FieldTerm> ft = FieldTerm.decode(term);
            if (ft.isPresent() && field.fields().contains(ft.get().field()) && ft.get().value().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code value} of {@code field} is currently excluded in {@code draft}. */
    public static boolean isValueExcluded(LogicalField field, String value, Scope draft) {
        if (draft == null || value == null) {
            return false;
        }
        for (String term : draft.exclude()) {
            Optional<FieldTerm> ft = FieldTerm.decode(term);
            if (ft.isPresent() && field.fields().contains(ft.get().field()) && ft.get().value().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reconciles {@code scope}'s field filter keys and aliases with the known {@link LogicalField}s so filters saved
     * under an alias name use the primary field name and carry all aliases in {@link Scope#fieldAliases()}.
     */
    public static Scope reconcileAliases(Scope scope, Collection<LogicalField> logicalFields) {
        if (scope == null || logicalFields == null || logicalFields.isEmpty()) {
            return scope;
        }
        Map<String, LogicalField> byAnyName = new HashMap<>();
        for (LogicalField lf : logicalFields) {
            for (String name : lf.fields()) {
                byAnyName.put(name, lf);
            }
        }
        Map<String, List<String>> filters = new LinkedHashMap<>();
        Map<String, List<String>> aliases = new LinkedHashMap<>(scope.fieldAliases());
        for (var entry : scope.fieldFilters().entrySet()) {
            LogicalField lf = byAnyName.get(entry.getKey());
            if (lf != null) {
                String primary = lf.primary();
                List<String> merged = new ArrayList<>(filters.getOrDefault(primary, List.of()));
                for (String v : entry.getValue()) {
                    if (!merged.contains(v)) {
                        merged.add(v);
                    }
                }
                filters.put(primary, List.copyOf(merged));
                if (!lf.aliases().isEmpty()) {
                    aliases.put(primary, lf.aliases());
                }
            } else {
                filters.put(entry.getKey(), entry.getValue());
            }
        }
        return new Scope(scope.index(), filters, scope.narrow(), scope.exclude(), scope.manualSpl(), aliases);
    }

    /** Adds {@code value} as a narrowed value for {@code field} on {@code scope}, preserving its aliases. */
    public static Scope withNarrowFieldValue(Scope scope, LogicalField field, String value) {
        Scope updated = scope.withFieldValue(field.primary(), value);
        if (!field.aliases().isEmpty() && updated.fieldFilters().containsKey(field.primary())) {
            Map<String, List<String>> aliases = new LinkedHashMap<>(updated.fieldAliases());
            aliases.put(field.primary(), field.aliases());
            return new Scope(updated.index(), updated.fieldFilters(), updated.narrow(), updated.exclude(),
                    updated.manualSpl(), aliases);
        }
        return updated;
    }

    /** Excludes {@code value} for {@code field} on {@code scope}, removing any matching narrowed value or exclude on aliases. */
    public static Scope withExcludeFieldValue(Scope scope, LogicalField field, String value) {
        Scope cleaned = scope.withoutFieldValue(field.primary(), value);
        for (String name : field.fields()) {
            cleaned = cleaned.withoutFieldValue(name, value);
        }
        return cleaned.withExcludeField(field.primary(), value);
    }

    /** Removes any exclude on {@code value} for {@code field} (primary or alias) from {@code scope}. */
    public static Scope withoutExcludeFieldValue(Scope scope, LogicalField field, String value) {
        Scope result = scope;
        for (String name : field.fields()) {
            Optional<FieldTerm> ft = FieldTerm.of(name, value);
            if (ft.isPresent()) {
                result = result.without(ft.get().encode());
            }
        }
        return result;
    }

    private static int nonBlankValueCount(LogicalField field) {
        int count = 0;
        for (String key : field.values().keySet()) {
            if (key != null && !key.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static String shortLabel(FieldGrouping.Row row, Map<String, Integer> labelCounts,
            Map<String, Integer> depth1Counts) {
        if (row.depth() == 2 && labelCounts.getOrDefault(row.label(), 0) > 1) {
            String l1 = FieldGrouping.label(row.field().primary(), 1);
            if (depth1Counts.getOrDefault(l1, 0) <= 1) {
                return l1;
            }
            return row.field().primary();
        }
        return row.label();
    }
}
