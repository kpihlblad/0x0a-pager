package se.grynna.pager.splunk;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The indexes a scope browses: none, one, several or all of them ({@code *}).
 * <p>
 * The scope keeps its selection as one string, the <em>selection key</em>: {@code ""} for none,
 * {@code *} for all, otherwise the index names sorted, de-duplicated and joined with {@code ,} (no
 * spaces), so {@code web} stays {@code web} and {@code b} and {@code a} give {@code a,b}. Splunk index
 * names cannot contain a comma, so the key is unambiguous. The key is what the per-key stores (filters,
 * scope terms, message fields, field-summary caches) are keyed by, which is why it must be
 * canonical: {@code parse(x.key())} equals {@code x}.
 * <p>
 * Index names are case-insensitive in Splunk, so {@link #matches(String)} and {@link #sameAs(IndexSelection)}
 * ignore case.
 */
public final class IndexSelection {

    /** No index: the scope names none, so no index predicate is rendered. */
    public static final IndexSelection NONE = new IndexSelection("");

    /** Every index. */
    public static final IndexSelection ALL = new IndexSelection("*");

    private static final String ALL_KEY = "*";

    private final String key;

    private IndexSelection(String key) {
        this.key = key;
    }

    /** Parses a selection key (or a comma-separated list typed by hand); blank gives {@link #NONE}. */
    public static IndexSelection parse(String key) {
        if (key == null || key.isBlank()) {
            return NONE;
        }
        return normalize(List.of(key.split(",")));
    }

    /** The selection of the given index names; a {@code *} among them makes it {@link #ALL}. */
    public static IndexSelection of(Collection<String> indexes) {
        return normalize(indexes == null ? List.of() : indexes);
    }

    private static IndexSelection normalize(Collection<String> names) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String name : names) {
            String trimmed = name == null ? "" : name.strip();
            if (ALL_KEY.equals(trimmed)) {
                return ALL;
            }
            if (!trimmed.isEmpty()) {
                sorted.add(trimmed);
            }
        }
        return sorted.isEmpty() ? NONE : new IndexSelection(String.join(",", sorted));
    }

    /** The selection key: {@code ""} for none, {@code *} for all, else the sorted names joined with {@code ,}. */
    public String key() {
        return key;
    }

    /** The index names; empty for {@link #NONE} and {@link #ALL}. */
    public List<String> indexes() {
        return isAll() || isNone() ? List.of() : List.of(key.split(","));
    }

    public boolean isAll() {
        return ALL_KEY.equals(key);
    }

    public boolean isNone() {
        return key.isEmpty();
    }

    /**
     * The SPL predicate that selects these indexes: none gives {@code ""}, all gives {@code index=*}, one gives
     * {@code index="a"} and several {@code (index="a" OR index="b")}.
     */
    public String predicate() {
        if (isNone()) {
            return "";
        }
        if (isAll()) {
            return "index=*";
        }
        List<String> names = indexes();
        if (names.size() == 1) {
            return SplQuery.fieldEquals("index", names.getFirst());
        }
        return names.stream().map(name -> SplQuery.fieldEquals("index", name))
                .collect(Collectors.joining(" OR ", "(", ")"));
    }

    /**
     * Whether an event from {@code eventIndex} belongs to this selection. {@link #NONE} and {@link #ALL} take every
     * event, and a null {@code eventIndex} (unknown) is not judged.
     */
    public boolean matches(String eventIndex) {
        if (isNone() || isAll() || eventIndex == null) {
            return true;
        }
        return indexes().stream().anyMatch(index -> index.equalsIgnoreCase(eventIndex));
    }

    /** Whether both selections name the same indexes, ignoring case. */
    public boolean sameAs(IndexSelection other) {
        return other != null && folded().equals(other.folded());
    }

    private String folded() {
        if (isAll() || isNone()) {
            return key;
        }
        return indexes().stream().map(index -> index.toLowerCase(Locale.ROOT)).sorted()
                .collect(Collectors.joining(","));
    }

    /** A human-readable label: empty for none, {@code all indexes} for all, else {@code a, b}. */
    public String display() {
        if (isNone()) {
            return "";
        }
        if (isAll()) {
            return "all indexes";
        }
        return String.join(", ", indexes());
    }

    /** A label for a file name: empty for none, {@code all} for all, else the names joined with {@code +}. */
    public String fileLabel() {
        if (isAll()) {
            return "all";
        }
        return String.join("+", indexes());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IndexSelection other && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
