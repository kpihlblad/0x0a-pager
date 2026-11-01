package se.grynna.pager.splunk;

import se.grynna.pager.splunk.Activity.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The structured fields an {@link Activity} was taken from, one per slot; a slot is null when its value was sniffed
 * from text or is absent. Produced by {@link ActivityExtractor#sourceFields}.
 *
 * @param machine     the machine's field; {@code host} when the Splunk host was the fallback
 * @param processName the process name's field
 * @param processId   the process id's field
 * @param thread      the thread's field
 * @param request     the request's field
 */
public record ActivityFields(SourceField machine, SourceField processName, SourceField processId, SourceField thread,
        SourceField request) {

    /** A structured field: its name as written (a dotted JSON path or a Splunk field name) and its value. */
    public record SourceField(String name, String value) {}

    /**
     * The scope terms that identify an activity at a level: field filters for the structured parts and literal text
     * terms for the sniffed ones.
     */
    public record Terms(List<SourceField> fields, List<String> texts) {

        public boolean isEmpty() {
            return fields.isEmpty() && texts.isEmpty();
        }
    }

    /**
     * The terms of {@code activity} at {@code level}. PROCESS and THREAD use the machine, process name and process id
     * fields when any of them is structured, else the process text term. THREAD adds the thread field, or its text
     * when sniffed, and is empty when the activity has no thread. REQUEST uses the request field, else its text.
     */
    public Terms termsFor(Activity activity, Level level) {
        List<SourceField> fields = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        switch (level) {
            case PROCESS -> addProcess(activity, fields, texts);
            case THREAD -> {
                if (activity.thread() != null) {
                    addProcess(activity, fields, texts);
                    SourceField threadField = usable(thread, activity.thread());
                    if (threadField != null) {
                        fields.add(threadField);
                    } else {
                        texts.add(activity.thread());
                    }
                }
            }
            case REQUEST -> {
                SourceField requestField = usable(request, activity.request());
                if (requestField != null) {
                    fields.add(requestField);
                } else if (activity.request() != null) {
                    texts.add(activity.request());
                }
            }
        }
        return new Terms(List.copyOf(fields), List.copyOf(texts));
    }

    private void addProcess(Activity activity, List<SourceField> fields, List<String> texts) {
        List<SourceField> process = Stream.of(usable(machine, activity.machine()), usable(processName),
                usable(processId))
                .filter(Objects::nonNull)
                .toList();
        if (process.isEmpty()) {
            if (activity.processTerm() != null) {
                texts.add(activity.processTerm());
            }
        } else {
            fields.addAll(process);
        }
    }

    /** The field when it holds the activity's {@code value} and is {@link #usable(SourceField)}, else null. */
    private static SourceField usable(SourceField field, String value) {
        return field != null && field.value().equals(value) ? usable(field) : null;
    }

    /** The field when Splunk accepts it as a field filter, else null: the text term is used instead. */
    private static SourceField usable(SourceField field) {
        return field != null && FieldTerm.of(field.name(), field.value()).isPresent() ? field : null;
    }
}
