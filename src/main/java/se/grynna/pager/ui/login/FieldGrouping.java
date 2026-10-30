package se.grynna.pager.ui.login;

import se.grynna.pager.splunk.FieldSummary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Merges Splunk field names that share a token path (dotted JSON-path and flattened names) into
 * {@link LogicalField}s and arranges them under at most two levels of headings, so each row can show a short label.
 * <p>
 * Names split on every {@code .} and {@code _}, case-sensitively ({@code extracted_eventType} and
 * {@code extracted_eventtype} are different fields). A first token shared by {@value #MIN_GROUP} or more fields
 * becomes a heading, and inside it a second token shared by as many becomes a subheading; the rest of a name is
 * shown as written. Ungrouped fields come first; inside a group its direct rows come before its subgroups.
 */
public final class FieldGrouping {

    static final int MIN_GROUP = 3;

    private static final Comparator<List<String>> ORDER = FieldGrouping::compareTokens;

    public sealed interface Entry permits Heading, Row {}

    /** A heading; depth 0 is the top level, depth 1 a subgroup inside a top-level heading. */
    public record Heading(int depth, String text) implements Entry {}

    /** A field row under {@code depth} headings (0, 1 or 2) showing {@code label}. */
    public record Row(int depth, String label, LogicalField field) implements Entry {}

    private record Slot(List<String> key, String subgroup, LogicalField field) {}

    private FieldGrouping() {}

    public static List<Entry> group(Collection<FieldSummary> summaries) {
        List<LogicalField> logical = logicalFields(summaries);

        Map<String, Integer> topCounts = new HashMap<>();
        for (LogicalField f : logical) {
            if (hasTopLevel(f)) {
                topCounts.merge(f.tokens().get(0), 1, Integer::sum);
            }
        }
        Set<String> topGroups = new HashSet<>();
        topCounts.forEach((token, count) -> {
            if (count >= MIN_GROUP) {
                topGroups.add(token);
            }
        });

        List<Entry> out = new ArrayList<>();
        Map<String, List<LogicalField>> members = new TreeMap<>();
        for (LogicalField f : logical) {
            if (hasTopLevel(f) && topGroups.contains(f.tokens().get(0))) {
                members.computeIfAbsent(f.tokens().get(0), k -> new ArrayList<>()).add(f);
            } else {
                out.add(new Row(0, f.primary(), f));
            }
        }
        for (var group : members.entrySet()) {
            out.add(new Heading(0, group.getKey()));
            addGroupMembers(out, group.getKey(), group.getValue());
        }
        return out;
    }

    private static void addGroupMembers(List<Entry> out, String top, List<LogicalField> members) {
        Map<String, Integer> subCounts = new HashMap<>();
        for (LogicalField f : members) {
            if (hasSubLevel(f)) {
                subCounts.merge(f.tokens().get(1), 1, Integer::sum);
            }
        }
        Set<String> subGroups = new HashSet<>();
        subCounts.forEach((token, count) -> {
            if (count >= MIN_GROUP) {
                subGroups.add(token);
            }
        });

        Map<String, List<LogicalField>> subMembers = new TreeMap<>();
        List<Slot> slots = new ArrayList<>();
        for (LogicalField f : members) {
            if (hasSubLevel(f) && subGroups.contains(f.tokens().get(1))) {
                subMembers.computeIfAbsent(f.tokens().get(1), k -> new ArrayList<>()).add(f);
            } else {
                slots.add(new Slot(f.tokens(), null, f));
            }
        }
        for (String sub : subMembers.keySet()) {
            slots.add(new Slot(List.of(top, sub), sub, null));
        }
        // Direct rows first: after a subgroup a direct row would read as part of it
        slots.sort(Comparator.comparing((Slot s) -> s.subgroup() != null).thenComparing(Slot::key, ORDER));

        for (Slot slot : slots) {
            if (slot.subgroup() == null) {
                out.add(new Row(1, label(slot.field().primary(), 1), slot.field()));
            } else {
                out.add(new Heading(1, slot.subgroup()));
                for (LogicalField f : subMembers.get(slot.subgroup())) {
                    out.add(new Row(2, label(f.primary(), 2), f));
                }
            }
        }
    }

    static List<String> tokens(String name) {
        return List.of(name.split("[._]", -1));
    }

    private static List<LogicalField> logicalFields(Collection<FieldSummary> summaries) {
        Map<String, Map<String, Long>> valuesByName = new TreeMap<>();
        for (FieldSummary s : summaries) {
            Map<String, Long> values = valuesByName.computeIfAbsent(s.getField(), k -> new TreeMap<>());
            if (s.getValues() != null) {
                s.getValues().forEach((key, value) -> values.merge(key, value.count(), Long::sum));
            }
        }

        Map<List<String>, List<String>> namesByTokens = new HashMap<>();
        for (String name : valuesByName.keySet()) {
            namesByTokens.computeIfAbsent(tokens(name), k -> new ArrayList<>()).add(name);
        }

        List<LogicalField> logical = new ArrayList<>();
        for (var entry : namesByTokens.entrySet()) {
            SortedMap<String, Long> values = new TreeMap<>();
            for (String name : entry.getValue()) {
                valuesByName.get(name).forEach((value, count) -> values.merge(value, count, Long::sum));
            }
            logical.add(new LogicalField(entry.getKey(), entry.getValue(), values));
        }
        logical.sort(Comparator.comparing(LogicalField::tokens, ORDER));
        return logical;
    }

    private static boolean hasTopLevel(LogicalField f) {
        return f.tokens().size() >= 2 && !f.tokens().get(0).isEmpty();
    }

    private static boolean hasSubLevel(LogicalField f) {
        return f.tokens().size() >= 3 && !f.tokens().get(1).isEmpty();
    }

    /** Strips the first {@code depth} tokens from the name; the rest is shown verbatim. */
    private static String label(String primary, int depth) {
        int index = -1;
        for (int i = 0; i < depth; i++) {
            index = nextSeparator(primary, index + 1);
            if (index < 0) {
                return primary;
            }
        }
        return depth == 0 ? primary : primary.substring(index + 1);
    }

    private static int nextSeparator(String name, int from) {
        for (int i = from; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.' || c == '_') {
                return i;
            }
        }
        return -1;
    }

    private static int compareTokens(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }
}
