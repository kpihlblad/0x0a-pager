package se.grynna.pager.db;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The saved scope settings of one index: field filters, the text terms folded into
 * the scope (narrow / exclude) and the message fields of its JSON events.
 * <p>
 * Serialized with Java serialization by the MVStore. The {@code serialVersionUID} is
 * the value computed for the class before {@code narrow}/{@code exclude} existed, so
 * records written by older versions still deserialize; the new fields are then
 * {@code null} (no constructor runs), which the accessors read as empty, or as the
 * default message fields for {@code messageFields}.
 */
@Data
@NoArgsConstructor
public class FieldFilters implements Serializable {
    private static final long serialVersionUID = -9099912745439620526L;

    private TreeMap<String, String> filters = new TreeMap<>();
    private ArrayList<String> narrow = new ArrayList<>();
    private ArrayList<String> exclude = new ArrayList<>();
    /** Null when the index uses the default message fields. */
    @Getter(AccessLevel.NONE)
    private ArrayList<String> messageFields;

    public FieldFilters(Map<String, String> filters) {
        this.filters = filters != null ? new TreeMap<>(filters) : new TreeMap<>();
    }

    public FieldFilters(Map<String, String> filters, ScopeTerms terms) {
        this(filters);
        setTerms(terms);
    }

    public FieldFilters(Map<String, String> filters, ScopeTerms terms, List<String> messageFields) {
        this(filters, terms);
        setMessageFields(messageFields);
    }

    /**
     * The saved message fields in order, or null when the index uses the default ones.
     * Empty when the index has no message fields at all.
     */
    public List<String> getMessageFieldsOrNull() {
        return messageFields == null ? null : List.copyOf(messageFields);
    }

    /** Set the message fields; null means the default message fields. */
    public void setMessageFields(List<String> fields) {
        this.messageFields = fields == null ? null : new ArrayList<>(fields);
    }

    public void putFilter(String field, String value) {
        if (value == null || value.trim().isEmpty()) {
            filters.remove(field);
        } else {
            filters.put(field, value);
        }
    }

    public String getFilter(String field) {
        return filters.get(field);
    }

    /** Narrowing terms; empty for a record saved before they existed. */
    public List<String> getNarrow() {
        return narrow == null ? List.of() : List.copyOf(narrow);
    }

    /** Excluded terms; empty for a record saved before they existed. */
    public List<String> getExclude() {
        return exclude == null ? List.of() : List.copyOf(exclude);
    }

    public ScopeTerms terms() {
        return new ScopeTerms(getNarrow(), getExclude());
    }

    public void setTerms(ScopeTerms terms) {
        ScopeTerms safe = terms != null ? terms : ScopeTerms.EMPTY;
        this.narrow = new ArrayList<>(safe.narrow());
        this.exclude = new ArrayList<>(safe.exclude());
    }

    /** True when no field filter, text term or message field list is saved. */
    public boolean isEmpty() {
        return filters.isEmpty() && getNarrow().isEmpty() && getExclude().isEmpty() && messageFields == null;
    }

    /** Number of field filters. */
    public int size() {
        return filters.size();
    }
}
