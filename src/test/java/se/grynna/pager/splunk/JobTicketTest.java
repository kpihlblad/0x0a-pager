package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobTicketTest {

    @Test
    void registerAfterCancelIsRefusedSoNoJobIsCreated() {
        JobTicket ticket = new JobTicket();
        assertThat(ticket.cancelRequested()).isEmpty();

        assertThat(ticket.register(ticket.nextSid())).isFalse();
        assertThat(ticket.isQuiet()).as("cancelled before any job: nothing to wait for").isTrue();
    }

    @Test
    void cancelReturnsSearchingSidsIsIdempotentAndBlocksLaterAttempts() {
        JobTicket ticket = new JobTicket();
        String first = ticket.nextSid();
        assertThat(ticket.register(first)).isTrue();

        assertThat(ticket.cancelRequested()).containsExactly(first);
        assertThat(ticket.cancelRequested()).as("idempotent").containsExactly(first);
        assertThat(ticket.register(ticket.nextSid())).as("a new attempt after cancel").isFalse();
        assertThat(ticket.isQuiet()).as("the registered create may still arrive").isFalse();
    }

    @Test
    void createdAfterCancelTellsTheCreatorToCancelItsJob() {
        JobTicket ticket = new JobTicket();
        String sid = ticket.nextSid();
        ticket.register(sid);
        ticket.cancelRequested();

        assertThat(ticket.created(sid)).isFalse();
        assertThat(ticket.isCancelled()).isTrue();
        assertThat(ticket.searching()).containsExactly(sid);
    }

    @Test
    void sidsAreUniquePerAttemptAndUseOnlySplunkSafeCharacters() {
        JobTicket a = new JobTicket();
        JobTicket b = new JobTicket();
        String a1 = a.nextSid();
        String a2 = a.nextSid();
        String b1 = b.nextSid();

        assertThat(List.of(a1, a2, b1)).doesNotHaveDuplicates()
                .allSatisfy(sid -> assertThat(sid).matches("pager_[0-9a-f]{32}_\\d+"));
        assertThat(a1).endsWith("_1");
        assertThat(a2).endsWith("_2");
        a.register(a1);
        assertThatThrownBy(() -> a.register(a1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doneAndGoneSidsAreQuietButFailedCreatesAreNot() {
        JobTicket ticket = new JobTicket();
        String done = ticket.nextSid();
        String failed = ticket.nextSid();
        ticket.register(done);
        ticket.created(done);
        ticket.done(done);
        ticket.register(failed);
        ticket.createFailed(failed);

        assertThat(ticket.cancelRequested()).as("a failed create is ambiguous").containsExactly(failed);
        assertThat(ticket.isQuiet()).isFalse();
        ticket.gone(failed);
        assertThat(ticket.isQuiet()).isTrue();
        ticket.created(failed);
        assertThat(ticket.state(failed)).as("a confirmed cancel is final").isEqualTo(JobTicket.SidState.GONE);
    }

    @Test
    void anAttemptNotSentLeavesNothingToCancel() {
        JobTicket ticket = new JobTicket();
        String sid = ticket.nextSid();
        ticket.register(sid);
        ticket.notSent(sid);

        assertThat(ticket.cancelTargets()).isEmpty();
        assertThat(ticket.cancelRequested()).isEmpty();
        assertThat(ticket.isQuiet()).isTrue();
    }

    @Test
    void aRejectedCreateLeavesNothingSearching() {
        JobTicket ticket = new JobTicket();
        String sid = ticket.nextSid();
        ticket.register(sid);
        ticket.rejected(sid);

        assertThat(ticket.cancelRequested()).isEmpty();
        assertThat(ticket.whenQuiet()).isCompleted();
    }

    @Test
    void quietOnlyOnceCancelledAndEverySidIsProvenNotSearching() {
        JobTicket ticket = new JobTicket();
        String done = ticket.nextSid();
        String ambiguous = ticket.nextSid();
        ticket.register(done);
        ticket.created(done);
        ticket.done(done);
        ticket.register(ambiguous);
        ticket.createFailed(ambiguous);

        assertThat(ticket.whenQuiet()).as("not cancelled yet").isNotDone();
        ticket.cancelRequested();
        assertThat(ticket.whenQuiet()).as("an ambiguous create may have made a job").isNotDone();
        assertThat(ticket.cancelTargets()).containsExactly(ambiguous);

        ticket.gone(ambiguous);
        assertThat(ticket.whenQuiet()).isCompleted();
    }

    @Test
    void aCreateReturningAfterItsAttemptFailedIsACancelTarget() {
        JobTicket ticket = new JobTicket();
        String sid = ticket.nextSid();
        ticket.register(sid);
        ticket.attemptFailed(sid); // the worker stopped waiting for the create

        assertThat(ticket.created(sid)).as("the job must not be used").isFalse();
        assertThat(ticket.cancelTargets()).containsExactly(sid);
        assertThat(ticket.isCancelled()).as("a failed attempt does not cancel the request").isFalse();
    }

    /**
     * Creator (register + created) races cancelRequested. Every SID that reached created is either
     * returned by cancelRequested or its creator learns of the cancel; no job escapes both.
     */
    @Test
    void everyCreatedJobIsSeenByTheCancellerOrByItsCreator() throws Exception {
        for (int i = 0; i < 1000; i++) {
            JobTicket ticket = new JobTicket();
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicReference<String> createdSid = new AtomicReference<>();
            AtomicBoolean creatorSawCancel = new AtomicBoolean();
            Thread creator = Thread.ofPlatform().start(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    String sid = ticket.nextSid();
                    if (ticket.register(sid)) {
                        createdSid.set(sid);
                        creatorSawCancel.set(!ticket.created(sid));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            start.await(5, TimeUnit.SECONDS);
            List<String> toCancel = ticket.cancelRequested();
            creator.join(5_000);

            String sid = createdSid.get();
            if (sid != null) {
                assertThat(toCancel.contains(sid) || creatorSawCancel.get())
                        .as("iteration %d: created job %s escaped cancellation", i, sid).isTrue();
            }
        }
    }
}
