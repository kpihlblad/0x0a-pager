package se.grynna.pager.ui;

import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.ui.details.EventDetails;
import se.grynna.pager.ui.details.EventDetails.Field;
import se.grynna.pager.ui.details.EventDetails.ScopeMark;
import se.grynna.pager.ui.details.EventDetails.Section;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules of the field actions in the event details: what a field's scope
 * mark is, why a field cannot be narrowed on, which fields can be message fields and how the list changes. Pure, so
 * they run without a viewer.
 */
final class DetailsFields {

    private DetailsFields() {}

    /**
     * The mark a field gets in the gutter: {@code NARROW} / {@code EXCLUDE} when the scope has a field term on
     * {@code field} (narrow and exclude lists), {@code NARROW} also for a field filter of the index; else {@code NONE}.
     */
    static ScopeMark scopeMark(Scope scope, String field) {
        if (scope == null || field == null) {
            return ScopeMark.NONE;
        }
        if (hasFieldTerm(scope.exclude(), field)) {
            return ScopeMark.EXCLUDE;
        }
        if (hasFieldTerm(scope.narrow(), field) || scope.filtersOn(field)) {
            return ScopeMark.NARROW;
        }
        return ScopeMark.NONE;
    }

    private static boolean hasFieldTerm(List<String> terms, String field) {
        return terms.stream().map(FieldTerm::decode)
                .anyMatch(term -> term.isPresent() && term.get().field().equals(field));
    }

    /**
     * Why {@code field="value"} cannot be folded into the scope, worded as a notice ({@code Can't narrow on _cd:
     * internal field}), or null when it can.
     *
     * @param action what was tried, {@code narrow on} or {@code exclude}
     */
    static String notFilterableReason(Field field, String action) {
        if (field.filterable() && FieldTerm.of(field.name(), field.value()).isPresent()) {
            return null;
        }
        String reason;
        if (field.name().indexOf('[') >= 0) {
            reason = "array element";
        } else if (field.section() == Section.RAW) {
            reason = "internal field";
        } else if (field.value().isEmpty()) {
            reason = "empty value";
        } else if (field.value().indexOf('\n') >= 0 || field.value().indexOf('\r') >= 0) {
            reason = "multi-line value";
        } else if (field.section() != Section.FIELDS) {
            reason = "internal field";
        } else {
            reason = "not a plain field name";
        }
        return "Can't " + action + " " + field.name() + ": " + reason;
    }

    /** {@code row 12 345}: the position label of an event in the log, the row grouped in thousands with spaces. */
    static String rowLabel(int row) {
        String digits = Integer.toString(Math.abs(row));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(digits.charAt(i));
        }
        return "row " + (row < 0 ? "-" : "") + grouped;
    }

    /** {@code level="DEBUG"} as the scope will say it; the plain form when the pair is not a valid field term. */
    static String termText(Field field) {
        return FieldTerm.of(field.name(), field.value()).map(FieldTerm::spl)
                .orElseGet(() -> field.name() + "=\"" + field.value() + "\"");
    }

    /** {@link #termText} with the value cut to {@link RowContextMenu#VALUE_MAX} characters, for menu items. */
    static String shortTermText(Field field) {
        return field.name() + "=\"" + RowContextMenu.elide(field.value().replace('\n', ' ')) + "\"";
    }

    /**
     * Whether {@code field} may be added to or removed from the message fields: a FIELDS field of a JSON event whose
     * path has no array index (the message fields address dot paths).
     */
    static boolean canBeMessageField(EventDetails details, Field field) {
        return details != null && details.msgType() == MsgType.JSON && field.section() == Section.FIELDS
                && field.name().indexOf('[') < 0;
    }

    /** {@code fields} without {@code name} when it is in the list, else with it appended. */
    static List<String> toggled(List<String> fields, String name) {
        List<String> result = new ArrayList<>(fields);
        if (!result.remove(name)) {
            result.add(name);
        }
        return List.copyOf(result);
    }
}
