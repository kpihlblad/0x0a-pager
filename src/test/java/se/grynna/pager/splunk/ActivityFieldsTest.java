package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.ActivityFields.SourceField;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityFieldsTest {

    private static final ActivityFields NO_FIELDS = new ActivityFields(null, null, null, null, null);

    @Test
    void theStructuredProcessPartsAreFieldFiltersAndTheSniffedThreadIsATextTerm() {
        ActivityFields fields = new ActivityFields(new SourceField("kubernetes.pod_name", "api-7f9"),
                new SourceField("kubernetes.container_name", "api"), null, null, null);
        Activity activity = new Activity("api-7f9", "api", "scheduler-10", null, null, "api");

        assertThat(fields.termsFor(activity, Activity.Level.PROCESS).fields())
                .containsExactly(new SourceField("kubernetes.pod_name", "api-7f9"),
                        new SourceField("kubernetes.container_name", "api"));
        assertThat(fields.termsFor(activity, Activity.Level.PROCESS).texts()).isEmpty();
        assertThat(fields.termsFor(activity, Activity.Level.THREAD).fields())
                .containsExactly(new SourceField("kubernetes.pod_name", "api-7f9"),
                        new SourceField("kubernetes.container_name", "api"));
        assertThat(fields.termsFor(activity, Activity.Level.THREAD).texts()).containsExactly("scheduler-10");
    }

    @Test
    void anActivityWithNoStructuredPartNarrowsByItsTextTermsOnly() {
        Activity activity = new Activity("m1", "p1", "t1", "r1", null, "p1");

        assertThat(NO_FIELDS.termsFor(activity, Activity.Level.PROCESS).texts()).containsExactly("p1");
        assertThat(NO_FIELDS.termsFor(activity, Activity.Level.THREAD).texts()).containsExactly("p1", "t1");
        assertThat(NO_FIELDS.termsFor(activity, Activity.Level.REQUEST).texts()).containsExactly("r1");
        assertThat(NO_FIELDS.termsFor(activity, Activity.Level.REQUEST).fields()).isEmpty();
    }

    @Test
    void aFieldSplunkCannotFilterOnIsReplacedByTheTextTerm() {
        ActivityFields fields = new ActivityFields(null, null, null, null, new SourceField("trace id", "4bf9"));
        Activity activity = new Activity(null, null, null, "4bf9", null, null);

        assertThat(fields.termsFor(activity, Activity.Level.REQUEST).fields()).isEmpty();
        assertThat(fields.termsFor(activity, Activity.Level.REQUEST).texts()).containsExactly("4bf9");
    }
}
