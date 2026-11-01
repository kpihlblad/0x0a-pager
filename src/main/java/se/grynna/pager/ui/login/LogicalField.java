package se.grynna.pager.ui.login;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One filterable field after merging the real Splunk field names that share the same token path, e.g.
 * {@code kubernetes.pod_name} and {@code kubernetes_pod_name}.
 *
 * @param tokens    the field name split on {@code .} and {@code _}
 * @param fields    the real field names, sorted naturally; the first one is the {@link #primary()}
 * @param values    the union of all members' values with their counts summed
 * @param relevance the highest filtering relevance score among the merged field summaries
 */
public record LogicalField(List<String> tokens, List<String> fields, SortedMap<String, Long> values,
        double relevance) {

    public LogicalField {
        tokens = List.copyOf(tokens);
        fields = List.copyOf(fields);
        values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }

    public LogicalField(List<String> tokens, List<String> fields, SortedMap<String, Long> values) {
        this(tokens, fields, values, 0.0);
    }

    public String primary() {
        return fields.getFirst();
    }

    public List<String> aliases() {
        return fields.size() <= 1 ? List.of() : fields.subList(1, fields.size());
    }
}
