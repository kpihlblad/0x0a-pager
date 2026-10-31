package se.grynna.pager.ui.preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Config;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The log view's time column is rounded to milliseconds while the full nanosecond date-time stays available
 * for tooltips and details.
 */
class DateTimeFormatterManagerTest {

    private static final Instant T = Instant.parse("2017-03-01T10:00:00.123456789Z");

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        initFormatter(ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        // Restore the shared singleton the way DateTimeFormatterManagerTestUtil leaves it.
        initFormatter(ZoneOffset.UTC);
    }

    @Test
    void logTimestampsAreRoundedToMillisHalfUp() {
        assertThat(DateTimeFormatterManager.formatLogTimestamp(T)).isEqualTo("10:00:00,123");
        // half up, not truncated
        assertThat(DateTimeFormatterManager.formatLogTimestamp(Instant.parse("2017-03-01T10:00:00.1235Z")))
                .isEqualTo("10:00:00,124");
        // a fraction that rounds up to a whole second carries into the next second
        assertThat(DateTimeFormatterManager.formatLogTimestamp(Instant.parse("2017-03-01T10:00:00.999999999Z")))
                .isEqualTo("10:00:01,000");
    }

    @Test
    void logTimestampsUseTheConfiguredZone() throws ReflectiveOperationException {
        initFormatter(ZoneId.of("Europe/Stockholm"));
        assertThat(DateTimeFormatterManager.formatLogTimestamp(T)).isEqualTo("11:00:00,123");
    }

    @Test
    void roundToMillisKeepsTheInstantUntouchedSemantics() {
        assertThat(DTUtil.roundToMillis(T)).isEqualTo(Instant.parse("2017-03-01T10:00:00.123Z"));
        assertThat(DTUtil.roundToMillis(Instant.parse("2017-03-01T10:00:00.999999999Z")))
                .isEqualTo(Instant.parse("2017-03-01T10:00:01Z"));
        // the full-precision value is not modified anywhere: formatting never touches the input
        assertThat(T).isEqualTo(Instant.parse("2017-03-01T10:00:00.123456789Z"));
    }

    @Test
    void fullTimestampKeepsNanosInAnyZone() {
        assertThat(DateTimeFormatterManager.formatFullTimestamp(T, ZoneOffset.UTC))
                .isEqualTo("2017-03-01 10:00:00,123456789 Z");
        assertThat(DateTimeFormatterManager.formatFullLogTimestamp(T))
                .isEqualTo(DateTimeFormatterManager.formatFullTimestamp(T, DateTimeFormatterManager.zoneId()));
    }

    @Test
    void fullTimestampInAnotherZoneShiftsTheWallClockButKeepsNanos() {
        var stockholm = DateTimeFormatterManager.formatFullTimestamp(T, ZoneId.of("Europe/Stockholm"));
        assertThat(stockholm).isEqualTo("2017-03-01 11:00:00,123456789 CET");
        assertThat(stockholm).contains("123456789");
    }

    @Test
    void statusBarRangeDatesUseTheConfiguredLocale() throws ReflectiveOperationException {
        initFormatter(ZoneOffset.UTC, Locale.US);
        Instant start = Instant.parse("2017-04-28T09:00:00Z");
        Instant end = Instant.parse("2017-04-29T10:00:30Z");
        Instant now = Instant.parse("2026-10-26T12:00:00Z");

        assertThat(DateTimeFormatterManager.formatRange(start, end, end, now))
                .isEqualTo("4/28/17, 9:00 AM \u2192 10:00:30 AM");
        assertThat(DateTimeFormatterManager.formatRangeEndpoint(start, end, now))
                .isEqualTo("4/28/17, 9:00 AM");
        assertThat(DateTimeFormatterManager.formatRangeStep(end, start, end, now))
                .isEqualTo("1d 1h earlier (4/28/17, 9:00 AM)");
        assertThat(DateTimeFormatterManager.formatRangeFull(start, end))
                .isEqualTo("4/28/17, 9:00:00 AM \u2192 4/29/17, 10:00:30 AM (\u0394 1d 1h 00m)");
    }

    @Test
    void nullsFormatAsEmpty() {
        assertThat(DateTimeFormatterManager.formatLogTimestamp(null)).isEmpty();
        assertThat(DateTimeFormatterManager.formatFullLogTimestamp(null)).isEmpty();
        assertThat(DateTimeFormatterManager.formatFullTimestamp(null, ZoneOffset.UTC)).isEmpty();
    }

    /** The manager is a singleton that keeps its first config: drop it so this test's zone takes effect. */
    private static void initFormatter(ZoneId zone) throws ReflectiveOperationException {
        initFormatter(zone, Locale.of("sv", "SE"));
    }

    private static void initFormatter(ZoneId zone, Locale locale) throws ReflectiveOperationException {
        Field instance = DateTimeFormatterManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        Config config = mock(Config.class);
        when(config.getLocale()).thenReturn(locale);
        when(config.getZoneId()).thenReturn(zone);
        DateTimeFormatterManager.initialize(config);
    }
}
