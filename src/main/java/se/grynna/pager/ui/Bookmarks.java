package se.grynna.pager.ui;

import se.grynna.pager.splunk.EventOrder;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.SearchHit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The session's bookmarked events, keyed by event identity ({@link SearchHit}).
 * <p>
 * Each value is an event-level {@link LoadedLogLine} (whole message, {@code raw},
 * {@code eventMap}); callers pass lines they will not mutate afterwards. Entries
 * are kept sorted by {@link EventOrder#searchHitComparator()}.
 * <p>
 * Pure model with no Swing dependency, but EDT-confined: call it from the EDT only.
 * <p>
 * Besides the events it tracks which of them have not yet left the app: a bookmark is <em>unsaved</em>
 * from the moment it is added until it is exported or copied ({@link #markSaved}, {@link #markAllSaved}),
 * and {@link #unsavedCount()} is what the quit and logout guard asks. Removing a bookmark removes it
 * from the unsaved set too.
 * <p>
 * Listeners are invoked synchronously, on the thread of the mutating call, after the
 * change is applied and only when something actually changed.
 */
public final class Bookmarks {

    /** What a {@link #toggle(List)} call did. */
    public enum Action {
        ADDED, REMOVED, UNCHANGED
    }

    /**
     * Result of a toggle.
     *
     * @param action  whether events were added, removed, or nothing changed
     * @param changed number of distinct events added or removed
     * @param size    number of bookmarks after the toggle
     */
    public record ToggleResult(Action action, int changed, int size) {}

    private final TreeMap<SearchHit, LoadedLogLine> events = new TreeMap<>(EventOrder.searchHitComparator());
    private final Set<SearchHit> unsaved = new TreeSet<>(EventOrder.searchHitComparator());
    private final List<Runnable> listeners = new ArrayList<>();
    private long version;

    /**
     * Toggle the given events: if every distinct event is bookmarked, remove them all;
     * otherwise add the ones that are missing. Events that share an identity count once;
     * the first occurrence is stored.
     *
     * @param lines event-level lines, one per event
     * @return what changed and the resulting size
     */
    public ToggleResult toggle(List<LoadedLogLine> lines) {
        Objects.requireNonNull(lines, "lines");
        Map<SearchHit, LoadedLogLine> distinct = new LinkedHashMap<>();
        for (LoadedLogLine line : lines) {
            distinct.putIfAbsent(SearchHit.from(Objects.requireNonNull(line, "line")), line);
        }
        if (distinct.isEmpty()) {
            return new ToggleResult(Action.UNCHANGED, 0, events.size());
        }

        if (distinct.keySet().stream().allMatch(events::containsKey)) {
            distinct.keySet().forEach(this::forget);
            changed();
            return new ToggleResult(Action.REMOVED, distinct.size(), events.size());
        }

        int added = 0;
        for (Map.Entry<SearchHit, LoadedLogLine> entry : distinct.entrySet()) {
            if (!events.containsKey(entry.getKey())) {
                events.put(entry.getKey(), entry.getValue());
                unsaved.add(entry.getKey());
                added++;
            }
        }
        changed();
        return new ToggleResult(Action.ADDED, added, events.size());
    }

    /**
     * Remove the bookmarks with these identities; unknown ones are ignored. One change notification
     * when anything was removed.
     *
     * @return how many bookmarks were removed
     */
    public int removeAll(Collection<SearchHit> hits) {
        Objects.requireNonNull(hits, "hits");
        int removed = 0;
        for (SearchHit hit : hits) {
            if (events.containsKey(hit)) {
                forget(hit);
                removed++;
            }
        }
        if (removed > 0) {
            changed();
        }
        return removed;
    }

    private void forget(SearchHit hit) {
        events.remove(hit);
        unsaved.remove(hit);
    }

    /**
     * Bookmarks added since all of them last left the app: not yet exported or copied. Not a change of
     * the bookmarks themselves, so it never notifies listeners.
     */
    public int unsavedCount() {
        return unsaved.size();
    }

    /** Every bookmark has left the app (copied or exported): nothing is unsaved. */
    public void markAllSaved() {
        unsaved.clear();
    }

    /** These events have left the app (copied or exported); events that are not bookmarks are ignored. */
    public void markSaved(Collection<LoadedLogLine> saved) {
        for (LoadedLogLine line : Objects.requireNonNull(saved, "saved")) {
            unsaved.remove(SearchHit.from(line));
        }
    }

    public boolean contains(SearchHit hit) {
        return events.containsKey(Objects.requireNonNull(hit, "hit"));
    }

    /** Immutable snapshot of the bookmarked events, in {@link EventOrder} order. */
    public List<LoadedLogLine> events() {
        return List.copyOf(events.values());
    }

    public int size() {
        return events.size();
    }

    /** Increases on every change; views compare it to detect a stale snapshot. */
    public long version() {
        return version;
    }

    /** Remove all bookmarks. A no-op (no version bump, no notification) when already empty. */
    public void clear() {
        unsaved.clear();
        if (!events.isEmpty()) {
            events.clear();
            changed();
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void changed() {
        version++;
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }
}
