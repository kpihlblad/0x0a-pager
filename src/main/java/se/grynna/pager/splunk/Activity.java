package se.grynna.pager.splunk;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Who emitted an event, from coarse to fine: machine, process, thread and request (trace, request or correlation id;
 * one unit of work, possibly hopping threads). The machine is the pod when k8s coordinates are present, else the host
 * or node; {@code origin} (Splunk sourcetype/source) stands in when neither machine nor process is known. Any part may
 * be null.
 */
public record Activity(String machine, String process, String thread, String request, String origin,
        String processTerm) implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final Activity NONE = new Activity(null, null, null, null, null, null);

    /** Seed for the icon's base colour: the machine and process, else the origin; null when nothing is known. */
    public String colourSeed() {
        if (machine == null && process == null) {
            return origin;
        }
        return (machine == null ? "" : machine) + "|" + (process == null ? "" : process);
    }

    /**
     * Seed for the icon's shape and foreground: the colour seed plus the thread (the request when there is no thread);
     * null when nothing is known.
     */
    public String patternSeed() {
        String base = colourSeed();
        String unit = thread != null ? thread : request;
        if (unit == null) {
            return base;
        }
        return (base == null ? "" : base) + "/" + unit;
    }

    /** Seed for the icon's spot colour: the pattern seed plus the request; equals {@link #patternSeed()} without one. */
    public String spotSeed() {
        String pattern = patternSeed();
        if (request == null) {
            return pattern;
        }
        return pattern + "#" + request;
    }

    /** How much of an activity to narrow to, coarse to fine. */
    public enum Level {
        /** The same machine and process (or origin). */
        PROCESS("process"),
        /** The same thread in the same process. */
        THREAD("thread"),
        /** The same request or trace, across threads, processes and machines. */
        REQUEST("request");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * The identity at {@code level}; two events belong to the same activity at that level when their keys are equal.
     * Null when this activity has nothing at that level. REQUEST is global on purpose: a trace crosses services.
     */
    public String key(Level level) {
        return switch (level) {
            case PROCESS -> colourSeed();
            case THREAD -> thread == null ? null : colourSeed() + "/" + thread;
            case REQUEST -> request;
        };
    }

    /** The value shown for {@code level}, e.g. the thread name; null when absent. */
    public String value(Level level) {
        return switch (level) {
            case PROCESS -> machine == null && process == null
                    ? origin
                    : process == null ? machine : process;
            case THREAD -> thread;
            case REQUEST -> request;
        };
    }

    /** Without a process term (it only matters for narrowing). */
    public Activity(String machine, String process, String thread, String request, String origin) {
        this(machine, process, thread, request, origin, null);
    }

    /**
     * Literal text terms that identify this activity at {@code level} when folded into the scope — as Narrow terms
     * (ANDed) to keep its events, or as Exclude terms ({@code NOT}) to drop them: the process term (the most
     * specific process identity found literally in the event text), plus the thread for THREAD; the request alone
     * for REQUEST. Empty when the level cannot be expressed.
     */
    public List<String> narrowTerms(Level level) {
        var terms = new ArrayList<String>();
        switch (level) {
            case PROCESS -> addIfPresent(terms, processTerm);
            case THREAD -> {
                if (thread != null) {
                    addIfPresent(terms, processTerm);
                    terms.add(thread);
                }
            }
            case REQUEST -> addIfPresent(terms, request);
        }
        return List.copyOf(terms);
    }

    private static void addIfPresent(List<String> terms, String term) {
        if (term != null && !term.isBlank()) {
            terms.add(term);
        }
    }

    /** The finest level present below REQUEST: THREAD when known, else PROCESS; null when neither is. */
    public Level defaultNarrowLevel() {
        if (key(Level.THREAD) != null) {
            return Level.THREAD;
        }
        return key(Level.PROCESS) != null ? Level.PROCESS : null;
    }

    public boolean isEmpty() {
        return colourSeed() == null && thread == null && request == null;
    }

    /** Human-readable form, e.g. {@code compute-12 › pod-0 › scheduler-10 › 4bf92f35}. */
    public String describe() {
        var joiner = new StringJoiner(" › ");
        if (machine != null) joiner.add(machine);
        if (process != null) joiner.add(process);
        if (machine == null && process == null && origin != null) joiner.add(origin);
        if (thread != null) joiner.add(thread);
        if (request != null) joiner.add(request);
        return joiner.toString();
    }
}
