package se.grynna.pager.splunk;

import java.util.Optional;

/**
 * A narrowing or excluded term on a field's value, {@code field="value"}. It is stored in a scope's
 * narrow or exclude list as a string: {@link #MARKER}, the field, another
 * {@link #MARKER}, then the value. The marker is a private-use character no typed text term contains, so a
 * text term that merely looks like {@code a="b"} stays a text term and the lists, chips, removal and persistence
 * handle both kinds alike.
 * <p>
 * Use {@link #of(String, String)} to build a valid term; the record itself does not check its components.
 */
public record FieldTerm(String field, String value) {

    /** Starts and separates an encoded field term; never part of a valid field or value. */
    static final char MARKER = '\uE000';

    /** The term as stored in a scope's narrow or exclude list. */
    public String encode() {
        return MARKER + field + MARKER + value;
    }

    /** The SPL predicate for this term, {@code field="value"} with the value as a literal. */
    public String spl() {
        return SplQuery.fieldEquals(field, value);
    }

    /**
     * A field term when {@code term} is an encoded one that is valid; empty for text terms and for anything
     * that is not a valid encoding.
     */
    public static Optional<FieldTerm> decode(String term) {
        if (term == null || term.isEmpty() || term.charAt(0) != MARKER) {
            return Optional.empty();
        }
        int separator = term.indexOf(MARKER, 1);
        if (separator < 0) {
            return Optional.empty();
        }
        return of(term.substring(1, separator), term.substring(separator + 1));
    }

    /** Whether {@code term} is an encoded field term. */
    public static boolean isFieldTerm(String term) {
        return decode(term).isPresent();
    }

    /** A field term, or empty when the field is not a plain name or the value is null or holds the marker. */
    public static Optional<FieldTerm> of(String field, String value) {
        if (!validField(field) || value == null || value.indexOf(MARKER) >= 0) {
            return Optional.empty();
        }
        return Optional.of(new FieldTerm(field, value));
    }

    /**
     * How a scope term reads to the user: {@code field="value"} for a field term, {@code "term"} for a text
     * term. Never shows the marker.
     */
    public static String display(String term) {
        return decode(term).map(FieldTerm::spl).orElseGet(() -> "\"" + term + "\"");
    }

    private static boolean validField(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (Character.isWhitespace(c) || c == '=' || c == '"' || c == MARKER) {
                return false;
            }
        }
        return true;
    }
}
