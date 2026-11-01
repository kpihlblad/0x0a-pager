package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.ui.login.FieldSuggestions.SuggestedField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes autocompletion entries for the scope selector's Narrow and Exclude token lines.
 * <p>
 * Without {@code =} in the input, completion offers up to {@value #MAX_FIELD_ENTRIES} matching fields (prefix
 * matches first, then by suggestion rank), up to {@value #MAX_VALUE_ENTRIES} matching values across all fields by
 * occurrence count descending, and a final text entry searching for the literal input; an empty input returns the
 * top suggested fields. With {@code =} in the input, the part before {@code =} is matched case-insensitively to a
 * field's short label or any of its real names, and completion offers that field's values containing the part after
 * {@code =} by count descending plus a final entry for the exact typed value when non-empty and not already listed.
 */
public final class TermCompleter {

    public static final int MAX_FIELD_ENTRIES = 6;
    public static final int MAX_VALUE_ENTRIES = 6;

    /** Draft status mark for a completion entry. */
    public enum Mark {
        NONE(""), NARROWED("\u2713"), EXCLUDED("\u2260");

        private final String symbol;

        Mark(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** One completion entry in the popup list. */
    public sealed interface Entry permits FieldEntry, ValueEntry, TextEntry {
        String display();

        Mark mark();
    }

    /** Completes a field name, turning the input into {@code label=} when accepted. */
    public record FieldEntry(LogicalField field, String label, int valueCount) implements Entry {
        @Override
        public String display() {
            return label + "  " + valueCount + " values";
        }

        @Override
        public Mark mark() {
            return Mark.NONE;
        }
    }

    /** Completes a field value, adding a narrowed or excluded field value to the draft when accepted. */
    public record ValueEntry(LogicalField field, String label, String value, long count, int sharePercent,
            Mark mark, boolean typedValue) implements Entry {
        @Override
        public String display() {
            String base = typedValue
                    ? label + "=" + value
                    : label + " = " + value + "  " + sharePercent + "%";
            return mark == Mark.NONE ? base : mark.symbol() + " " + base;
        }
    }

    /** Completes a free-text search term, adding a narrowed or excluded text term to the draft when accepted. */
    public record TextEntry(String text, Mark mark) implements Entry {
        @Override
        public String display() {
            String base = "\"" + text + "\"";
            return mark == Mark.NONE ? base : mark.symbol() + " " + base;
        }
    }

    private record ValueCandidate(SuggestedField field, int fieldRank, String value, long count, int sharePercent) {}

    private TermCompleter() {}

    /**
     * Computes completion entries for {@code rawInput} against {@code summaries} and {@code draft}.
     */
    public static List<Entry> complete(String rawInput, Collection<FieldSummary> summaries, Scope draft) {
        List<FieldGrouping.Entry> grouped = summaries == null || summaries.isEmpty()
                ? List.of()
                : FieldGrouping.group(summaries);
        return completeFromFields(rawInput, FieldSuggestions.allFields(grouped), draft);
    }

    /**
     * Computes completion entries for {@code rawInput} against pre-ranked {@code fields} and {@code draft}.
     */
    public static List<Entry> completeFromFields(String rawInput, List<SuggestedField> fields, Scope draft) {
        List<SuggestedField> safeFields = fields == null ? List.of() : fields;
        String input = rawInput == null ? "" : rawInput.strip();
        if (input.isEmpty()) {
            return emptyInputEntries(safeFields);
        }
        int eq = input.indexOf('=');
        if (eq >= 0) {
            return equalsEntries(input.substring(0, eq).strip(), input.substring(eq + 1).strip(), safeFields, draft);
        }
        return generalEntries(input, safeFields, draft);
    }

    /** Whether {@code rawInput} in the NARROW line starts with a negation prefix ({@code !} or {@code -}). */
    public static boolean hasExcludePrefix(String rawInput) {
        if (rawInput == null) {
            return false;
        }
        String stripped = rawInput.stripLeading();
        return !stripped.isEmpty() && (stripped.charAt(0) == '!' || stripped.charAt(0) == '-');
    }

    /** Strips a leading {@code !} or {@code -} negation prefix and any following leading whitespace. */
    public static String stripExcludePrefix(String rawInput) {
        if (!hasExcludePrefix(rawInput)) {
            return rawInput == null ? "" : rawInput.strip();
        }
        return rawInput.stripLeading().substring(1).stripLeading();
    }

    private static List<Entry> emptyInputEntries(List<SuggestedField> fields) {
        List<Entry> result = new ArrayList<>(MAX_FIELD_ENTRIES);
        for (SuggestedField f : fields) {
            if (!FieldSuggestions.isFilterableCandidate(f.field())) {
                continue;
            }
            result.add(new FieldEntry(f.field(), f.label(), f.valueCount()));
            if (result.size() >= MAX_FIELD_ENTRIES) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static List<Entry> generalEntries(String query, List<SuggestedField> fields, Scope draft) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Entry> entries = new ArrayList<>();

        List<Integer> matchingFieldIndices = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            SuggestedField f = fields.get(i);
            if (f.valueCount() > 0 && matchesField(f, needle)) {
                matchingFieldIndices.add(i);
            }
        }
        matchingFieldIndices.sort(Comparator
                .comparing((Integer idx) -> !isPrefixMatch(fields.get(idx), needle))
                .thenComparingInt(Integer::intValue));

        int fieldLimit = Math.min(MAX_FIELD_ENTRIES, matchingFieldIndices.size());
        for (int i = 0; i < fieldLimit; i++) {
            SuggestedField f = fields.get(matchingFieldIndices.get(i));
            entries.add(new FieldEntry(f.field(), f.label(), f.valueCount()));
        }

        List<ValueCandidate> valueCandidates = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            SuggestedField f = fields.get(i);
            long total = FieldSuggestions.totalOccurrences(f.field());
            for (Map.Entry<String, Long> ve : f.field().values().entrySet()) {
                String val = ve.getKey();
                long count = ve.getValue() != null ? ve.getValue() : 0L;
                if (val != null && !val.isBlank() && val.toLowerCase(Locale.ROOT).contains(needle)) {
                    int share = FieldSuggestions.sharePercent(count, total);
                    valueCandidates.add(new ValueCandidate(f, i, val, count, share));
                }
            }
        }
        valueCandidates.sort(Comparator
                .comparingLong(ValueCandidate::count).reversed()
                .thenComparingInt(ValueCandidate::fieldRank)
                .thenComparing(ValueCandidate::value));

        int valueLimit = Math.min(MAX_VALUE_ENTRIES, valueCandidates.size());
        for (int i = 0; i < valueLimit; i++) {
            ValueCandidate vc = valueCandidates.get(i);
            Mark mark = valueMark(vc.field().field(), vc.value(), draft);
            entries.add(new ValueEntry(vc.field().field(), vc.field().label(), vc.value(), vc.count(),
                    vc.sharePercent(), mark, false));
        }

        entries.add(new TextEntry(query, textMark(query, draft)));
        return List.copyOf(entries);
    }

    private static List<Entry> equalsEntries(String before, String after, List<SuggestedField> fields, Scope draft) {
        if (before.isEmpty()) {
            String full = "=" + after;
            return List.of(new TextEntry(full, textMark(full, draft)));
        }
        SuggestedField matched = findExactField(before, fields);
        if (matched == null) {
            if (!after.isEmpty() && FieldTerm.of(before, after).isPresent()) {
                LogicalField synthetic = new LogicalField(FieldGrouping.tokens(before), List.of(before),
                        Collections.emptySortedMap());
                return List.of(new ValueEntry(synthetic, before, after, 0L, 0,
                        valueMark(synthetic, after, draft), true));
            }
            return List.of();
        }

        String needle = after.toLowerCase(Locale.ROOT);
        long total = FieldSuggestions.totalOccurrences(matched.field());
        List<Map.Entry<String, Long>> matchingValues = new ArrayList<>();
        for (Map.Entry<String, Long> ve : matched.field().values().entrySet()) {
            String val = ve.getKey();
            if (val != null && !val.isBlank() && (needle.isEmpty() || val.toLowerCase(Locale.ROOT).contains(needle))) {
                matchingValues.add(ve);
            }
        }
        matchingValues.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));

        List<Entry> entries = new ArrayList<>(matchingValues.size() + 1);
        boolean exactListed = false;
        for (Map.Entry<String, Long> ve : matchingValues) {
            String val = ve.getKey();
            long count = ve.getValue() != null ? ve.getValue() : 0L;
            int share = FieldSuggestions.sharePercent(count, total);
            if (val.equals(after)) {
                exactListed = true;
            }
            entries.add(new ValueEntry(matched.field(), matched.label(), val, count, share,
                    valueMark(matched.field(), val, draft), false));
        }
        if (!after.isEmpty() && !exactListed) {
            entries.add(new ValueEntry(matched.field(), matched.label(), after, 0L, 0,
                    valueMark(matched.field(), after, draft), true));
        }
        return List.copyOf(entries);
    }

    private static SuggestedField findExactField(String name, List<SuggestedField> fields) {
        for (SuggestedField f : fields) {
            if (f.label().equalsIgnoreCase(name)) {
                return f;
            }
        }
        for (SuggestedField f : fields) {
            for (String realName : f.field().fields()) {
                if (realName.equalsIgnoreCase(name)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static boolean matchesField(SuggestedField field, String needle) {
        if (field.label().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String real : field.field().fields()) {
            if (real.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrefixMatch(SuggestedField field, String needle) {
        if (field.label().toLowerCase(Locale.ROOT).startsWith(needle)) {
            return true;
        }
        for (String real : field.field().fields()) {
            if (real.toLowerCase(Locale.ROOT).startsWith(needle)) {
                return true;
            }
        }
        return false;
    }

    static Mark valueMark(LogicalField field, String value, Scope draft) {
        if (FieldSuggestions.isValueNarrowed(field, value, draft)) {
            return Mark.NARROWED;
        }
        if (FieldSuggestions.isValueExcluded(field, value, draft)) {
            return Mark.EXCLUDED;
        }
        return Mark.NONE;
    }

    static Mark textMark(String text, Scope draft) {
        if (draft == null || text == null || text.isBlank()) {
            return Mark.NONE;
        }
        if (draft.narrow().contains(text)) {
            return Mark.NARROWED;
        }
        if (draft.exclude().contains(text)) {
            return Mark.EXCLUDED;
        }
        return Mark.NONE;
    }
}
