package se.grynna.pager.ui;

import se.grynna.pager.splunk.SearchSession;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Connects the one search session the UI is showing to an EDT update handler.
 * <p>
 * Session listeners fire on the searcher thread and post to the EDT, so an
 * update can already be queued when the user cancels or replaces the search.
 * Each queued update re-checks on the EDT that its session is still the bound
 * one before it is delivered, and binding a new session (or unbinding) detaches
 * the previous listener so an obsolete session stops posting at all.
 * <p>
 * {@link #bind} and {@link #unbind} must be called on the EDT (the thread that
 * runs {@code edt}).
 */
final class SearchUpdateBinding {

    private final Executor edt;
    private SearchSession session;
    private Consumer<SearchSession> listener;

    SearchUpdateBinding(Executor edt) {
        this.edt = edt;
    }

    /** Show updates from {@code newSession} only; any previously bound session is detached. */
    void bind(SearchSession newSession, Consumer<SearchSession> onUpdate) {
        unbind();
        Consumer<SearchSession> posting = new Consumer<>() {
            @Override
            // Identity comparisons are intentional: `session` is the session
            // token currently bound, and `listener == this` verifies this
            // consumer is still the bound listener before delivering.
            @SuppressWarnings("ReferenceEquality")
            public void accept(SearchSession updated) {
                edt.execute(() -> {
                    if (session == updated && listener == this) {
                        onUpdate.accept(updated);
                    }
                });
            }
        };
        session = newSession;
        listener = posting;
        newSession.addUpdateListener(posting);
    }

    /** Stop delivering updates; queued updates for the old session are dropped. */
    void unbind() {
        if (session != null && listener != null) {
            session.removeUpdateListener(listener);
        }
        session = null;
        listener = null;
    }

    /** True if {@code candidate} is the session whose updates are delivered. */
    // Identity comparison is intentional: a session is bound by instance, so
    // only that exact SearchSession is the bound one.
    @SuppressWarnings("ReferenceEquality")
    boolean isBound(SearchSession candidate) {
        return candidate != null && candidate == session;
    }
}
