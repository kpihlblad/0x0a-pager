package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Activity.Level;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityTest {

    private static final Activity FULL = new Activity("compute-12", "casbatch", "scheduler-10", "4bf92f35", "src");

    @Test
    void keysGetFinerWithTheLevel() {
        assertThat(FULL.key(Level.PROCESS)).isEqualTo("compute-12|casbatch");
        assertThat(FULL.key(Level.THREAD)).isEqualTo("compute-12|casbatch/scheduler-10");
        assertThat(FULL.key(Level.REQUEST)).isEqualTo("4bf92f35");
    }

    @Test
    void theRequestKeyIsGlobalAcrossMachinesProcessesAndThreads() {
        Activity elsewhere = new Activity("other-host", "billing", "pool-3", "4bf92f35", null);

        assertThat(elsewhere.key(Level.REQUEST)).isEqualTo(FULL.key(Level.REQUEST));
        assertThat(elsewhere.key(Level.PROCESS)).isNotEqualTo(FULL.key(Level.PROCESS));
        assertThat(elsewhere.key(Level.THREAD)).isNotEqualTo(FULL.key(Level.THREAD));
    }

    @Test
    void theSameThreadNameInAnotherProcessIsAnotherThreadKey() {
        Activity otherPod = new Activity("compute-13", "casbatch", "scheduler-10", null, null);

        assertThat(otherPod.key(Level.THREAD)).isNotEqualTo(FULL.key(Level.THREAD));
    }

    @Test
    void valuesAreTheShownPartOfEachLevel() {
        assertThat(FULL.value(Level.PROCESS)).isEqualTo("casbatch");
        assertThat(FULL.value(Level.THREAD)).isEqualTo("scheduler-10");
        assertThat(FULL.value(Level.REQUEST)).isEqualTo("4bf92f35");
    }

    @Test
    void theProcessValueFallsBackToTheMachineAndThenTheOrigin() {
        assertThat(new Activity("compute-12", null, null, null, "src").value(Level.PROCESS)).isEqualTo("compute-12");
        assertThat(new Activity(null, null, null, null, "src").value(Level.PROCESS)).isEqualTo("src");
        assertThat(new Activity(null, null, null, null, "src").key(Level.PROCESS)).isEqualTo("src");
    }

    @Test
    void missingPartsGiveNullKeysAndValues() {
        Activity processOnly = new Activity(null, "casbatch", null, null, null);

        assertThat(processOnly.key(Level.PROCESS)).isNotNull();
        assertThat(processOnly.key(Level.THREAD)).isNull();
        assertThat(processOnly.key(Level.REQUEST)).isNull();
        assertThat(processOnly.value(Level.THREAD)).isNull();
        assertThat(processOnly.value(Level.REQUEST)).isNull();

        for (Level level : Level.values()) {
            assertThat(Activity.NONE.key(level)).as("NONE %s key", level).isNull();
            assertThat(Activity.NONE.value(level)).as("NONE %s value", level).isNull();
        }
    }

    @Test
    void theDefaultFollowLevelIsThreadThenProcessThenNone() {
        assertThat(FULL.defaultNarrowLevel()).isEqualTo(Level.THREAD);
        assertThat(new Activity("m", "p", null, "r", null).defaultNarrowLevel()).isEqualTo(Level.PROCESS);
        assertThat(new Activity(null, null, null, null, "src").defaultNarrowLevel()).isEqualTo(Level.PROCESS);
        assertThat(new Activity(null, null, null, "r", null).defaultNarrowLevel()).isNull();
        assertThat(Activity.NONE.defaultNarrowLevel()).isNull();
    }

    @Test
    void narrowTermsAreTheLiteralTermsOfEachLevel() {
        Activity withTerm = new Activity("compute-12", "casbatch[pod-1]", "scheduler-10", "4bf9", null, "pod-1");

        assertThat(withTerm.narrowTerms(Level.PROCESS)).containsExactly("pod-1");
        assertThat(withTerm.narrowTerms(Level.THREAD)).containsExactly("pod-1", "scheduler-10");
        assertThat(withTerm.narrowTerms(Level.REQUEST)).containsExactly("4bf9");

        assertThat(FULL.narrowTerms(Level.PROCESS)).as("no process term").isEmpty();
        assertThat(FULL.narrowTerms(Level.THREAD)).containsExactly("scheduler-10");
        assertThat(Activity.NONE.narrowTerms(Level.THREAD)).isEmpty();
        assertThat(Activity.NONE.narrowTerms(Level.REQUEST)).isEmpty();
    }

    @Test
    void levelsHaveLabels() {
        assertThat(Level.PROCESS.label()).isEqualTo("process");
        assertThat(Level.THREAD.label()).isEqualTo("thread");
        assertThat(Level.REQUEST.label()).isEqualTo("request");
    }

    @Test
    void describeJoinsTheKnownPartsFromCoarseToFine() {
        assertThat(FULL.describe()).isEqualTo("compute-12 › casbatch › scheduler-10 › 4bf92f35");
        assertThat(new Activity(null, null, null, null, "src").describe()).isEqualTo("src");
        assertThat(Activity.NONE.describe()).isEmpty();
    }
}
