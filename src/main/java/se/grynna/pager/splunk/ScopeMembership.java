package se.grynna.pager.splunk;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Best-effort, local answer to "would this already loaded event still be in the scope?", used to warn
 * before a scope change drops bookmarks. Splunk is not asked, so the answer is approximate.
 * <p>
 * Rules for {@link #outside(Scope, LoadedLogLine)}; an event is outside when any of them says so:
 * <ul>
 * <li><b>Index:</b> the event's {@code index} field is present and differs from the scope's index
 * (case-insensitive). No {@code index} field, or a blank scope index: not judged.</li>
 * <li><b>Field filter</b> {@code key=value}: the event's field is missing, or its value does not
 * match. Compared the way a Splunk search compares: case-insensitively, {@code *} is a wildcard.</li>
 * <li><b>Narrowing term:</b> {@code _raw} does not contain the term, case-insensitively ({@code *}
 * is a wildcard). Splunk matches whole tokens and the term was typed as a search, so this is
 * approximate: a substring of a token counts as a hit here but not in Splunk, and a term Splunk
 * would match across a segmenter boundary may be missed.</li>
 * <li><b>Excluded term:</b> {@code _raw} contains the term (same caveat).</li>
 * <li><b>Field term</b> {@code field="value"} ({@link FieldTerm}), narrowing or excluded: judged on the event's
 * field map, never on {@code _raw}. Narrowing: the field is missing or its value does not match, so the event
 * is outside. Excluded: the field's value matches, so the event is outside.</li>
 * <li><b>Manual SPL:</b> the query cannot be evaluated locally, so the index and field filters are not
 * judged and the event is kept. The narrowing and excluded terms of the scope are still stages of the
 * pipeline and are judged.</li>
 * </ul>
 * An event without {@code _raw} is not judged against the text terms.
 */
public final class ScopeMembership {

    private ScopeMembership() {}

    /** Whether {@code event} is (as far as can be told locally) not in {@code newScope}. */
    public static boolean outside(Scope newScope, LoadedLogLine event) {
        if (newScope.manualSpl() == null && outsideFields(newScope, event)) {
            return true;
        }
        for (String term : newScope.narrow()) {
            if (narrowingMisses(term, event)) {
                return true;
            }
        }
        for (String term : newScope.exclude()) {
            if (excludedHit(term, event)) {
                return true;
            }
        }
        return false;
    }

    private static boolean narrowingMisses(String term, LoadedLogLine event) {
        Optional<FieldTerm> field = FieldTerm.decode(term);
        if (field.isPresent()) {
            return !fieldEquals(field.get(), event);
        }
        String raw = event.getRaw();
        return raw != null && !contains(raw, term);
    }

    private static boolean excludedHit(String term, LoadedLogLine event) {
        Optional<FieldTerm> field = FieldTerm.decode(term);
        if (field.isPresent()) {
            return fieldEquals(field.get(), event);
        }
        String raw = event.getRaw();
        return raw != null && contains(raw, term);
    }

    /** Whether the event has the term's field with a value matching it (case-insensitive, {@code *} wildcard). */
    private static boolean fieldEquals(FieldTerm term, LoadedLogLine event) {
        Map<String, String> fields = event.getEventMap();
        String actual = fields == null ? null : fields.get(term.field());
        return actual != null && glob(term.value()).matcher(actual).matches();
    }

    /**
     * The events that fall outside {@code next} when it replaces {@code current}. The events were
     * visible in {@code current}, so a constraint that {@code next} merely keeps is not judged again (this
     * keeps the approximate term test from flagging events the real search accepted). A different index
     * or a missing current scope judges everything.
     */
    public static List<LoadedLogLine> outsideAfterChange(Scope current, Scope next, List<LoadedLogLine> events) {
        if (next.equals(current) || events.isEmpty()) {
            return List.of();
        }
        Scope judged = next;
        if (current != null && sameIndex(current, next) && current.manualSpl() == null && next.manualSpl() == null) {
            judged = new Scope(next.index(), changedFilters(current, next), without(next.narrow(), current.narrow()),
                    without(next.exclude(), current.exclude()), null, next.fieldAliases());
        } else if (current != null && sameIndex(current, next) && next.manualSpl() != null
                && next.manualSpl().equals(current.manualSpl())) {
            judged = next.withTerms(without(next.narrow(), current.narrow()),
                    without(next.exclude(), current.exclude()));
        }
        Scope scope = judged;
        return events.stream().filter(event -> outside(scope, event)).toList();
    }

    private static boolean sameIndex(Scope a, Scope b) {
        return a.indexSelection().sameAs(b.indexSelection());
    }

    private static Map<String, String> changedFilters(Scope current, Scope next) {
        Map<String, String> changed = new java.util.LinkedHashMap<>();
        next.fieldFilters().forEach((field, value) -> {
            if (!value.equals(current.fieldFilters().get(field))
                    || !next.filterFields(field).equals(current.filterFields(field))) {
                changed.put(field, value);
            }
        });
        return changed;
    }

    private static List<String> without(List<String> terms, List<String> kept) {
        return terms.stream().filter(term -> !kept.contains(term)).toList();
    }

    private static boolean outsideFields(Scope scope, LoadedLogLine event) {
        Map<String, String> fields = event.getEventMap();
        String eventIndex = fields == null ? null : fields.get("index");
        if (!scope.indexSelection().matches(eventIndex)) {
            return true;
        }
        for (Map.Entry<String, String> filter : scope.fieldFilters().entrySet()) {
            String wanted = filter.getValue();
            if (wanted == null || wanted.isBlank()) {
                continue;
            }
            Pattern pattern = glob(wanted);
            boolean matched = fields != null && scope.filterFields(filter.getKey()).stream().anyMatch(field -> {
                String actual = fields.get(field);
                return actual != null && pattern.matcher(actual).matches();
            });
            if (!matched) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String raw, String term) {
        return glob(term).matcher(raw).find();
    }

    /** Case-insensitive pattern for {@code text} where {@code *} matches any run of characters. */
    private static Pattern glob(String text) {
        StringBuilder regex = new StringBuilder();
        int start = 0;
        for (int star = text.indexOf('*'); star >= 0; star = text.indexOf('*', start)) {
            regex.append(Pattern.quote(text.substring(start, star))).append(".*");
            start = star + 1;
        }
        regex.append(Pattern.quote(text.substring(start)));
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }
}
