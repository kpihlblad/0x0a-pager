package com.splunk;

import java.util.Map;
import java.util.Objects;

/**
 * Real {@link Event} instances for tests.
 * <p>
 * {@code Event}'s constructor is package-private, so a test outside this package cannot
 * {@code new} one. Mocks are avoided: every call on a mock — including {@code containsKey} from
 * {@code LogEventMapper.matching()} — records a stack trace, which makes large fixtures slow.
 */
public final class TestEvents {

    private TestEvents() {}

    /**
     * An event whose fields are {@code fields}. {@code get} returns those values; a key that was
     * not put returns null, as a real Splunk event does. The event is independent of later changes
     * to {@code fields}.
     *
     * @param fields field name to single value; not null
     * @return a real event, not a mock
     */
    public static Event event(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields");
        Event event = new Event();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            event.putSingleOrDelimited(field.getKey(), field.getValue());
        }
        return event;
    }
}
