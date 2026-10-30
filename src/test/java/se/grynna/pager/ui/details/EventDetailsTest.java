package se.grynna.pager.ui.details;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.ui.details.EventDetails.Field;
import se.grynna.pager.ui.details.EventDetails.ScopeMark;
import se.grynna.pager.ui.details.EventDetails.Section;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static se.grynna.pager.ui.details.DetailsFixtures.*;

class EventDetailsTest {

    private static List<String> names(EventDetails details, Section section) {
        return details.fields().stream().filter(f -> f.section() == section).map(Field::name).toList();
    }

    private static Field field(EventDetails details, String name) {
        return details.field(name).orElseThrow();
    }

    @Test
    void nestedObjectsAndArraysAreFlattenedToPaths() {
        String raw = """
                {"a":{"b":{"c":1}},"arr":[{"x":"1"},"s",[2]],"empty":{},"none":[],"nul":null,"msg":"hi","t":true}""";

        EventDetails details = details(event(raw, null, "hi", Level.INFO));

        assertThat(names(details, Section.FIELDS)).containsExactly("a.b.c", "arr[0].x", "arr[1]", "arr[2][0]", "empty",
                "msg", "none", "nul", "t");
        assertThat(field(details, "a.b.c").value()).isEqualTo("1");
        assertThat(field(details, "arr[0].x").value()).isEqualTo("1");
        assertThat(field(details, "arr[1]").value()).isEqualTo("s");
        assertThat(field(details, "arr[2][0]").value()).isEqualTo("2");
        assertThat(field(details, "empty").value()).isEqualTo("{}");
        assertThat(field(details, "none").value()).isEqualTo("[]");
        assertThat(field(details, "nul").value()).isEqualTo("null");
        assertThat(field(details, "t").value()).isEqualTo("true");
    }

    @Test
    void eventMapAndJsonAreUnitedAndDeduplicatedWithTheEventMapWinning() {
        String raw = "{\"level\":\"warn\",\"msg\":\"json\",\"extra\":\"x\"}";
        Map<String, String> map = Map.of("level", "ERROR", "msg", "from-map", "only_map", "m");

        EventDetails details = details(event(raw, map, "m", Level.ERROR));

        assertThat(names(details, Section.FIELDS)).containsExactly("extra", "level", "msg", "only_map");
        assertThat(field(details, "level").value()).isEqualTo("ERROR");
        assertThat(field(details, "msg").value()).isEqualTo("from-map");
        assertThat(field(details, "extra").value()).isEqualTo("x");
    }

    @Test
    void aJsonFieldWithASplunkMetadataNameIsNotListedTwice() {
        String raw = "{\"host\":\"in-json\",\"x\":\"1\"}";

        EventDetails details = details(event(raw, Map.of("host", "splunk-host"), "m", Level.INFO));

        assertThat(details.fields().stream().filter(f -> f.name().equals("host"))).hasSize(1);
        assertThat(field(details, "host").section()).isEqualTo(Section.SPLUNK);
        assertThat(field(details, "host").value()).isEqualTo("splunk-host");
    }

    @Test
    void internalFieldsGoToTheSplunkSection() {
        Map<String, String> map = Map.ofEntries(Map.entry("_bkt", "b"), Map.entry("_time", "t"),
                Map.entry("index", "i"),
                Map.entry("host", "h"), Map.entry("source", "s"), Map.entry("sourcetype", "st"),
                Map.entry("linecount", "1"), Map.entry("splunk_server", "srv"), Map.entry("punct", "--"),
                Map.entry("timestartpos", "0"), Map.entry("timeendpos", "9"), Map.entry("date_hour", "10"),
                Map.entry("eventtype", "e"), Map.entry("tag::eventtype", "t"), Map.entry("_raw", "ignored"),
                Map.entry("other", "o"));

        EventDetails details = details(event(null, map, "m", Level.INFO));

        assertThat(names(details, Section.FIELDS)).containsExactly("other");
        assertThat(names(details, Section.SPLUNK)).containsExactly("_bkt", "_time", "date_hour", "eventtype", "host",
                "index", "linecount", "punct", "source", "sourcetype", "splunk_server", "tag::eventtype",
                "timeendpos", "timestartpos");
        assertThat(details.fields()).noneMatch(f -> f.name().equals("_raw"));
    }

    @Test
    void sectionsComeInOrderAndEachIsSortedCaseInsensitively() {
        Map<String, String> map = Map.of("Zed", "1", "alpha", "2", "Beta", "3", "_si", "x", "host", "h");

        EventDetails details = details(event("{\"gamma\":1}", map, "m", Level.INFO));

        assertThat(details.fields()).extracting(Field::name).containsExactly("alpha", "Beta", "gamma", "Zed", "_si",
                "host", "_raw");
        assertThat(details.fields()).extracting(Field::section).containsExactly(Section.FIELDS, Section.FIELDS,
                Section.FIELDS, Section.FIELDS, Section.SPLUNK, Section.SPLUNK, Section.RAW);
    }

    @Test
    void rawOfAJsonObjectIsPrettyPrinted() throws Exception {
        String raw = "{\"a\":1,\"b\":{\"c\":[1,2]}}";

        EventDetails details = details(event(raw, null, "m", Level.INFO));

        Field rawField = field(details, "_raw");
        assertThat(rawField.section()).isEqualTo(Section.RAW);
        assertThat(rawField.value()).contains("\n").contains("  \"a\" : 1");
        assertThat(new ObjectMapper().readTree(rawField.value())).isEqualTo(new ObjectMapper().readTree(raw));
    }

    @Test
    void rawThatIsNotAJsonObjectIsKeptAsIs() {
        for (String raw : List.of("plain text, not json", "[1,2]", "42", "{broken")) {
            EventDetails details = details(event(raw, null, "m", Level.INFO));

            assertThat(field(details, "_raw").value()).isEqualTo(raw);
            assertThat(names(details, Section.FIELDS)).isEmpty();
        }
    }

    @Test
    void nullEventMapRawAndMessageGiveAnEmptyButValidModel() {
        LoadedLogLine line = event(null, null, null, null);

        EventDetails details = details(line);

        assertThat(details.fields()).isEmpty();
        assertThat(details.message()).isEmpty();
        assertThat(details.level()).isNull();
        assertThat(details.field("anything")).isEmpty();
        assertThat(details.indexOf("anything")).isEqualTo(-1);
    }

    @Test
    void headerFactsAreCarriedOver() {
        LoadedLogLine line = event("{\"msg\":\"m\",\"host\":\"h1\"}", Map.of("host", "h1"), "hello", Level.WARNING);

        EventDetails details = details(line);

        assertThat(details.level()).isEqualTo(Level.WARNING);
        assertThat(details.displayTime()).isEqualTo(DISPLAY_TIME);
        assertThat(details.splunkTime()).isEqualTo(SPLUNK_TIME);
        assertThat(details.indexTime()).isEqualTo(INDEX_TIME);
        assertThat(details.msgType()).isEqualTo(MsgType.JSON);
        assertThat(details.message()).isEqualTo("hello");
        assertThat(details.activity()).isNotNull();
        assertThat(details.timesDiffer()).isTrue();
    }

    @Test
    void timesDifferOnlyWhenTheInstantsDiffer() {
        LoadedLogLine same = event(null, null, "m", Level.INFO);
        same.setMsgTime(same.getSplunkTime());
        LoadedLogLine noDisplay = event(null, null, "m", Level.INFO);
        noDisplay.setMsgTime(null);

        assertThat(details(same).timesDiffer()).isFalse();
        assertThat(details(noDisplay).timesDiffer()).isFalse();
    }

    @Test
    void filterableMeansFieldsOrScopeMetadataWithASingleLineValue() {
        Map<String, String> map = Map.of("plain", "v", "blank", "", "multi", "a\nb", "_bkt", "b", "host", "h", "index",
                "i", "source", "s", "sourcetype", "st", "linecount", "1", "splunk_server", "srv");

        EventDetails details = details(event("{\"j\":\"x\"}", map, "m", Level.INFO));

        assertThat(field(details, "plain").filterable()).isTrue();
        assertThat(field(details, "j").filterable()).isTrue();
        assertThat(field(details, "blank").filterable()).isFalse();
        assertThat(field(details, "multi").filterable()).isFalse();
        for (String name : List.of("host", "index", "source", "sourcetype")) {
            assertThat(field(details, name).filterable()).as(name).isTrue();
        }
        for (String name : List.of("_bkt", "linecount", "splunk_server", "_raw")) {
            assertThat(field(details, name).filterable()).as(name).isFalse();
        }
        assertThat(EventDetails.isFilterable(field(details, "plain"))).isTrue();
        assertThat(EventDetails.isFilterable(field(details, "_bkt"))).isFalse();
    }

    @Test
    void messageFieldAndScopeMarkArePropagatedByName() {
        Map<String, String> map = Map.of("host", "h", "msg", "m", "level", "INFO");

        EventDetails details = EventDetails.of(event("{\"x\":1}", map, "m", Level.INFO),
                name -> Set.of("msg", "host", "x").contains(name),
                name -> name.equals("level") ? ScopeMark.EXCLUDE : name.equals("host") ? ScopeMark.NARROW : null);

        assertThat(field(details, "msg").inMessage()).isTrue();
        assertThat(field(details, "x").inMessage()).isTrue();
        assertThat(field(details, "level").inMessage()).isFalse();
        assertThat(field(details, "host").inMessage()).as("only FIELDS can be message fields").isFalse();
        assertThat(field(details, "level").mark()).isEqualTo(ScopeMark.EXCLUDE);
        assertThat(field(details, "host").mark()).isEqualTo(ScopeMark.NARROW);
        assertThat(field(details, "msg").mark()).isEqualTo(ScopeMark.NONE);
        assertThat(field(details, "_raw").mark()).isEqualTo(ScopeMark.NONE);
    }

    @Test
    void fieldLookupFindsByNameAndPosition() {
        EventDetails details = ofFields("b", "1", "a", "2");

        assertThat(details.indexOf("a")).isEqualTo(0);
        assertThat(details.indexOf("b")).isEqualTo(1);
        assertThat(details.field("b")).map(Field::value).contains("1");
        assertThat(details.count(Section.FIELDS)).isEqualTo(2);
        assertThat(details.count(Section.RAW)).isEqualTo(1);
    }

    @Test
    void isInternalRecognisesMetadataNames() {
        for (String name : List.of("_time", "_bkt", "_cd", "_indextime", "_si", "index", "host", "source", "sourcetype",
                "linecount", "splunk_server", "punct", "timestartpos", "timeendpos", "date_month", "eventtype",
                "tag::x")) {
            assertThat(EventDetails.isInternal(name)).as(name).isTrue();
        }
        for (String name : List.of("_raw", "level", "message", "kubernetes.host", "hosts", "dated")) {
            assertThat(EventDetails.isInternal(name)).as(name).isFalse();
        }
    }
}
