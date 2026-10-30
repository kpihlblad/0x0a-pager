package se.grynna.pager.splunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The Splunk side of one logical request (one load): every search job it starts, by SID, and
 * whether the request has been cancelled. One request may start several jobs: a relogin retry
 * runs a new attempt with a new SID, so no SID is ever reused.
 * <p>
 * SIDs are chosen by 0x0A Pager before the create request ({@code JobArgs.setId}), so a job whose
 * create outcome is unknown can still be cancelled. Once {@link #cancelRequested()} has run, no new
 * attempt may start ({@link #register} returns false) and a creator that returns late learns from
 * {@link #created} that it must cancel its job.
 * <p>
 * A SID counts as possibly searching on Splunk until it is proven otherwise: its search is done,
 * the create was never sent or was rejected, or Splunk confirmed it gone. A cancelled ticket with no
 * possibly searching SID is <em>quiet</em> ({@link #whenQuiet()}); only then has the request
 * stopped costing Splunk search capacity.
 * <p>
 * Thread-safe. Methods are brief updates under this object's monitor and never call out while
 * holding it; {@link #whenQuiet()} completes after the monitor is released. Callers may hold their
 * own locks (lock order: caller's lock, then the ticket).
 */
public final class JobTicket {

    /** Lifecycle of one SID. */
    enum SidState {
        /** The create may be in flight: its outcome is not known yet. */
        REGISTERED,
        /** The create request was never sent: nothing exists on Splunk. */
        NOT_SENT,
        /** Splunk answered the create with an error: no job was created. */
        REJECTED,
        /** The create failed without an answer (timeout, reset): Splunk may have the job. */
        AMBIGUOUS,
        /** The create returned: the job exists. */
        CREATED,
        /** The search finished (DONE): no search load, only results stored on Splunk. */
        DONE,
        /** Cancelled on Splunk (confirmed) or known to no longer exist. */
        GONE;

        boolean maySearch() {
            return this == REGISTERED || this == AMBIGUOUS || this == CREATED;
        }

        /** A cancel request can reach a job in this state. */
        boolean cancellable() {
            return this == AMBIGUOUS || this == CREATED || this == DONE;
        }
    }

    private static final class Sid {
        SidState state = SidState.REGISTERED;
        boolean cancelWanted;
    }

    private final String prefix = "pager_" + UUID.randomUUID().toString().replace("-", "");
    private final Map<String, Sid> sids = new LinkedHashMap<>();
    private final CompletableFuture<Void> quiet = new CompletableFuture<>();
    private int attempts;
    private boolean cancelled;
    private boolean cancelLoopRunning;
    private boolean cancelWorkSignalled;

    /** A fresh SID for the next attempt, unique across tickets, attempts and app restarts. */
    synchronized String nextSid() {
        return prefix + "_" + (++attempts);
    }

    /**
     * Record that an attempt is about to create a job with {@code sid}.
     *
     * @return false when the ticket is already cancelled: the caller must not create the job
     */
    public synchronized boolean register(String sid) {
        if (cancelled) {
            return false;
        }
        if (sids.containsKey(sid)) {
            throw new IllegalStateException("SID reused: " + sid);
        }
        sids.put(sid, new Sid());
        notifyAll();
        return true;
    }

    /**
     * The create request for {@code sid} returned: the job exists.
     *
     * @return false when the job must not be used (the ticket was cancelled or the attempt
     *         abandoned meanwhile): it is a cancel target
     */
    public boolean created(String sid) {
        synchronized (this) {
            Sid s = sid(sid);
            if (s.state == SidState.REGISTERED || s.state == SidState.AMBIGUOUS) {
                s.state = SidState.CREATED;
            }
            if (cancelled) {
                s.cancelWanted = true;
            }
            cancelWorkSignalled |= s.cancelWanted;
            notifyAll();
            return !s.cancelWanted;
        }
    }

    /** The create request for {@code sid} was not sent: nothing exists on Splunk. */
    public void notSent(String sid) {
        resolve(sid, SidState.NOT_SENT);
    }

    /** Splunk rejected the create request for {@code sid}: no job exists. */
    public void rejected(String sid) {
        resolve(sid, SidState.REJECTED);
    }

    /** The search of {@code sid} is done: it no longer costs Splunk search capacity. */
    public void done(String sid) {
        resolve(sid, SidState.DONE);
    }

    /** Splunk confirmed that {@code sid} is cancelled or no longer exists. */
    public void gone(String sid) {
        resolve(sid, SidState.GONE);
    }

    private void resolve(String sid, SidState next) {
        boolean nowQuiet;
        synchronized (this) {
            Sid s = sid(sid);
            // GONE is final; a late report from the creator cannot revive the job
            if (s.state != SidState.GONE) {
                s.state = next;
            }
            if (next == SidState.GONE || next == SidState.NOT_SENT || next == SidState.REJECTED) {
                s.cancelWanted = false;
            }
            notifyAll();
            nowQuiet = isQuiet();
        }
        if (nowQuiet) {
            quiet.complete(null);
        }
    }

    /**
     * The create of {@code sid} failed without an answer; Splunk may or may not have the job, now or
     * later. It stays possibly searching until a cancel succeeds or its search is reported done.
     */
    synchronized void createFailed(String sid) {
        Sid s = sid(sid);
        if (s.state == SidState.REGISTERED) {
            s.state = SidState.AMBIGUOUS;
            s.cancelWanted = true;
            cancelWorkSignalled = true;
        }
        notifyAll();
    }

    /**
     * The attempt of {@code sid} ended without handing its results over: whatever it created
     * (still searching, or done with results stored) should be cancelled.
     */
    synchronized void attemptFailed(String sid) {
        Sid s = sid(sid);
        if (s.state == SidState.REGISTERED || s.state.cancellable()) {
            s.cancelWanted = true;
            cancelWorkSignalled = true;
        }
        notifyAll();
    }

    private Sid sid(String sid) {
        Sid s = sids.get(sid);
        if (s == null) {
            throw new IllegalStateException("Unknown SID: " + sid);
        }
        return s;
    }

    /**
     * Cancel the whole request: no further attempt may start and every possibly searching job
     * becomes a cancel target. Idempotent.
     *
     * @return the SIDs that may still be searching
     */
    public List<String> cancelRequested() {
        boolean nowQuiet;
        List<String> searching;
        synchronized (this) {
            cancelled = true;
            for (Sid s : sids.values()) {
                if (s.state.maySearch()) {
                    s.cancelWanted = true;
                    cancelWorkSignalled = true;
                }
            }
            notifyAll();
            searching = matching(s -> s.state.maySearch());
            nowQuiet = isQuiet();
        }
        if (nowQuiet) {
            quiet.complete(null);
        }
        return searching;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * True when the request is cancelled (no new job can appear) and none of its SIDs can be
     * searching any more.
     */
    public synchronized boolean isQuiet() {
        return cancelled && sids.values().stream().noneMatch(s -> s.state.maySearch());
    }

    /** Completes (once, never exceptionally) when the ticket becomes {@linkplain #isQuiet() quiet}. */
    public CompletableFuture<Void> whenQuiet() {
        return quiet;
    }

    /** SIDs that may still be searching on Splunk. */
    synchronized List<String> searching() {
        return matching(s -> s.state.maySearch());
    }

    /** SIDs to send a cancel to now. */
    synchronized List<String> cancelTargets() {
        return matching(s -> s.cancelWanted && s.state.cancellable());
    }

    /** A cancel of a done job was tried; it is not retried (only results are left on Splunk). */
    synchronized void cancelTried(String sid) {
        Sid s = sid(sid);
        if (s.state == SidState.DONE) {
            s.cancelWanted = false;
        }
    }

    private List<String> matching(Predicate<Sid> condition) {
        List<String> result = new ArrayList<>();
        sids.forEach((sid, s) -> {
            if (condition.test(s)) {
                result.add(sid);
            }
        });
        return result;
    }

    /** Every SID registered so far, in attempt order. */
    synchronized List<String> allSids() {
        return List.copyOf(sids.keySet());
    }

    synchronized SidState state(String sid) {
        Sid s = sids.get(sid);
        return s == null ? null : s.state;
    }

    // ---- cancel loop bookkeeping (one loop per ticket, owned by SplunkClient) ----

    /** @return true when the caller must start the (only) cancel loop of this ticket */
    synchronized boolean startCancelLoop() {
        cancelWorkSignalled = true;
        notifyAll();
        if (cancelLoopRunning) {
            return false;
        }
        cancelLoopRunning = true;
        return true;
    }

    /** Ends the loop when there is nothing left to cancel; new work starts a new loop. */
    synchronized boolean endCancelLoopIfIdle() {
        if (!cancelTargets().isEmpty()) {
            return false;
        }
        cancelLoopRunning = false;
        return true;
    }

    synchronized void endCancelLoop() {
        cancelLoopRunning = false;
    }

    /** Called at the start of a cancel pass: work signalled from now on triggers another pass. */
    synchronized void takeCancelWork() {
        cancelWorkSignalled = false;
    }

    /**
     * Wait up to {@code millis} unless new cancel work arrives (or {@link #wake()}).
     *
     * @return true when woken by new work, false when the wait ran out
     */
    synchronized boolean awaitCancelWork(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        long remaining;
        while (!cancelWorkSignalled) {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            wait(Math.max(1, remaining / 1_000_000));
        }
        return true;
    }

    synchronized void wake() {
        cancelWorkSignalled = true;
        notifyAll();
    }

    /**
     * Wait until none of {@code sidsToSettle} can be searching any more.
     *
     * @return false on timeout
     */
    synchronized boolean awaitNotSearching(List<String> sidsToSettle, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (sidsToSettle.stream().anyMatch(sid -> sid(sid).state.maySearch())) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            wait(Math.max(1, remaining / 1_000_000));
        }
        return true;
    }

    @Override
    public synchronized String toString() {
        StringBuilder text = new StringBuilder("JobTicket{");
        sids.forEach(
                (sid, s) -> text.append(sid).append('=').append(s.state).append(s.cancelWanted ? "(cancel) " : " "));
        return text.append(cancelled ? "cancelled}" : "}").toString();
    }
}
