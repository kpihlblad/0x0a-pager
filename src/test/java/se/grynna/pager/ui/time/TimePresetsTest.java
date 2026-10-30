package se.grynna.pager.ui.time;

import org.junit.jupiter.api.Test;
import se.grynna.pager.ui.time.TimePresets.Preset;
import se.grynna.pager.ui.time.TimePresets.Row;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimePresetsTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
    /** Wednesday 2017-05-03 12:03:14 local (CEST, +02:00). */
    private static final Instant AT = Instant.parse("2017-05-03T10:03:14Z");
    private static final Instant NOW = Instant.parse("2017-05-03T10:20:00Z");

    private static Preset preset(Row row, String label) {
        return row.presets().stream().filter(p -> p.label().equals(label)).findFirst().orElseThrow();
    }

    private static Row row(String titlePrefix) {
        return TimePresets.rows(AT, NOW, ZONE).stream().filter(r -> r.title().startsWith(titlePrefix)).findFirst()
                .orElseThrow();
    }

    @Test
    void rowsInOrderWithAtInTheAroundTitle() {
        List<Row> rows = TimePresets.rows(AT, NOW, ZONE);

        assertThat(rows).extracting(Row::title).containsExactly("AROUND 12:03:14", "THIS", "LAST", "FROM AT");
    }

    @Test
    void aroundIsCentredOnAtAndEndsAtNowWhenItWouldPassIt() {
        Row around = row("AROUND");

        assertThat(preset(around, "±5m")).isEqualTo(new Preset("±5m", AT.minusSeconds(300), AT.plusSeconds(300)));
        assertThat(preset(around, "±1h").to()).isNull();
    }

    @Test
    void thisUsesClockAlignedBucketsAndIsoWeeks() {
        Row bucket = row("THIS");

        assertThat(preset(bucket, "5 min")).isEqualTo(
                new Preset("5 min", Instant.parse("2017-05-03T10:00:00Z"), Instant.parse("2017-05-03T10:05:00Z")));
        assertThat(preset(bucket, "4 hours").from()).isEqualTo(Instant.parse("2017-05-03T10:00:00Z"));
        assertThat(preset(bucket, "4 hours").to()).isNull();
        assertThat(preset(bucket, "week").from()).isEqualTo(Instant.parse("2017-04-30T22:00:00Z"));
    }

    @Test
    void lastEndsAtNow() {
        assertThat(preset(row("LAST"), "24h")).isEqualTo(new Preset("24h", NOW.minusSeconds(86_400), null));
    }

    @Test
    void fromAtClampsStartAndKnowsTodayAndYesterday() {
        Row fromAt = row("FROM AT");

        assertThat(preset(fromAt, "+15m")).isEqualTo(new Preset("+15m", AT, AT.plusSeconds(900)));
        assertThat(preset(fromAt, "1971 → AT").fromIsStart()).isTrue();
        assertThat(preset(fromAt, "everything")).isEqualTo(new Preset("everything", TimePresets.START, null));
        assertThat(preset(fromAt, "yesterday")).isEqualTo(new Preset("yesterday",
                Instant.parse("2017-05-01T22:00:00Z"), Instant.parse("2017-05-02T22:00:00Z")));
        assertThat(preset(fromAt, "today").to()).isNull();
    }

    @Test
    void startBeforeTheMinimumBoundaryBecomesStart() {
        Instant early = Instant.parse("1971-01-01T00:00:30Z");

        Row around = TimePresets.rows(early, NOW, ZONE).getFirst();

        assertThat(around.presets().getFirst().fromIsStart()).isTrue();
    }
}
