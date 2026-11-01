package se.grynna.pager.ui;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.FieldTerm;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.splunk.Scope;
import se.grynna.pager.ui.details.EventDetails;
import se.grynna.pager.ui.details.EventDetails.Field;
import se.grynna.pager.ui.details.EventDetails.ScopeMark;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class DetailsFieldsTest {

    private static final Instant T = Instant.parse("2017-03-04T10:11:12Z");

    @Test
    void theScopeMarksFollowTheFieldTermsAndTheFieldFilters() {
        Scope scope = Scope.of("web", Map.of("host", "h1")).withNarrowField("level", "INFO")
                .withExcludeField("thread", "main").withNarrow("text term");

        assertThat(DetailsFields.scopeMark(scope, "level")).isEqualTo(ScopeMark.NARROW);
        assertThat(DetailsFields.scopeMark(scope, "thread")).isEqualTo(ScopeMark.EXCLUDE);
        assertThat(DetailsFields.scopeMark(scope, "host")).isEqualTo(ScopeMark.NARROW);
        assertThat(DetailsFields.scopeMark(scope, "msg")).isEqualTo(ScopeMark.NONE);
        assertThat(DetailsFields.scopeMark(scope, "text term")).as("a text term is not a field").isEqualTo(
                ScopeMark.NONE);
        assertThat(DetailsFields.scopeMark(null, "level")).isEqualTo(ScopeMark.NONE);
    }

    @Test
    void aFieldThatOnlyAnAliasNamesGetsTheNarrowMark() {
        Scope scope = new Scope("web", Map.of("kubernetes.pod_name", List.of("p1")), List.of(), List.of(), null,
                Map.of("kubernetes.pod_name", List.of("kubernetes_pod_name")));

        assertThat(DetailsFields.scopeMark(scope, "kubernetes_pod_name")).isEqualTo(ScopeMark.NARROW);
        assertThat(DetailsFields.scopeMark(scope, "kubernetes.pod_name")).isEqualTo(ScopeMark.NARROW);
        assertThat(DetailsFields.scopeMark(scope, "host")).isEqualTo(ScopeMark.NONE);
    }

    @Test
    void aFieldThatCannotBeFilteredOnSaysWhy() {
        EventDetails details = EventDetails.of(event(
                "{\"a\":\"x\",\"list\":[\"y\"],\"empty\":\"\",\"multi\":\"l1\\nl2\"}",
                Map.of("_cd", "0:1", "index", "web", "host", "")), name -> false, name -> ScopeMark.NONE);

        assertThat(DetailsFields.notFilterableReason(field(details, "a"), "narrow on")).isNull();
        assertThat(DetailsFields.notFilterableReason(field(details, "index"), "narrow on")).isNull();
        assertThat(DetailsFields.notFilterableReason(field(details, "_cd"), "narrow on"))
                .isEqualTo("Can't narrow on _cd: internal field");
        assertThat(DetailsFields.notFilterableReason(field(details, "list[0]"), "exclude"))
                .isEqualTo("Can't exclude list[0]: array element");
        assertThat(DetailsFields.notFilterableReason(field(details, "empty"), "narrow on"))
                .isEqualTo("Can't narrow on empty: empty value");
        assertThat(DetailsFields.notFilterableReason(field(details, "host"), "narrow on"))
                .isEqualTo("Can't narrow on host: empty value");
        assertThat(DetailsFields.notFilterableReason(field(details, "multi"), "narrow on"))
                .isEqualTo("Can't narrow on multi: multi-line value");
        assertThat(DetailsFields.notFilterableReason(field(details, "_raw"), "narrow on"))
                .isEqualTo("Can't narrow on _raw: internal field");
    }

    @Test
    void onlyFieldsOfAJsonEventCanBeMessageFields() {
        EventDetails json = EventDetails.of(event("{\"a\":\"x\",\"list\":[\"y\"]}", Map.of("index", "web")),
                name -> false, name -> ScopeMark.NONE);
        EventDetails raw = EventDetails.of(
                new LoadedLogLine(MsgType.RAW, "seg", 0, "cd", "bkt", T, "plain", T, T, "plain", false, Level.INFO),
                name -> false, name -> ScopeMark.NONE);

        assertThat(DetailsFields.canBeMessageField(json, field(json, "a"))).isTrue();
        assertThat(DetailsFields.canBeMessageField(json, field(json, "list[0]"))).isFalse();
        assertThat(DetailsFields.canBeMessageField(json, field(json, "index"))).isFalse();
        assertThat(DetailsFields.canBeMessageField(json, field(json, "_raw"))).isFalse();
        assertThat(DetailsFields.canBeMessageField(raw, field(raw, "_raw"))).isFalse();
        assertThat(DetailsFields.canBeMessageField(null, field(json, "a"))).isFalse();
    }

    @Test
    void theMessageFieldsToggleByAppendingAndRemoving() {
        assertThat(DetailsFields.toggled(List.of("msg", "log"), "level")).containsExactly("msg", "log", "level");
        assertThat(DetailsFields.toggled(List.of("msg", "log", "level"), "log")).containsExactly("msg", "level");
        assertThat(DetailsFields.toggled(List.of("msg"), "msg")).isEmpty();
    }

    @Test
    void termTextsAreWhatTheScopeSays() {
        EventDetails details = EventDetails.of(event("{\"q\":\"say \\\"hi\\\"\",\"long\":\"" + "x".repeat(40) + "\"}",
                null), name -> false, name -> ScopeMark.NONE);

        assertThat(DetailsFields.termText(field(details, "q"))).isEqualTo(FieldTerm.of("q", "say \"hi\"").get().spl());
        assertThat(DetailsFields.shortTermText(field(details, "long")))
                .isEqualTo("long=\"" + "x".repeat(RowContextMenu.VALUE_MAX) + "…\"");
    }

    @Test
    void rowLabelsGroupThousandsWithSpaces() {
        assertThat(DetailsFields.rowLabel(7)).isEqualTo("row 7");
        assertThat(DetailsFields.rowLabel(1234)).isEqualTo("row 1 234");
        assertThat(DetailsFields.rowLabel(12345)).isEqualTo("row 12 345");
        assertThat(DetailsFields.rowLabel(1234567)).isEqualTo("row 1 234 567");
        assertThat(DetailsFields.rowLabel(-1234)).isEqualTo("row -1 234");
        assertThat(DetailsFields.rowLabel(0)).isEqualTo("row 0");
    }

    private static Field field(EventDetails details, String name) {
        return details.field(name).orElseThrow(() -> new AssertionError("no field " + name));
    }

    private static LoadedLogLine event(String raw, Map<String, String> eventMap) {
        LoadedLogLine line = new LoadedLogLine(MsgType.JSON, "seg", 0, "cd", "bkt", T, raw, T, T, "msg", false,
                Level.INFO);
        line.setEvent(eventMap == null ? null : new TreeMap<>(eventMap));
        return line;
    }
}
