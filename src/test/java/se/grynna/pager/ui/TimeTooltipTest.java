package se.grynna.pager.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The time-column tooltip: the full nanosecond date-time in the
 * configured zone, plus UTC when that zone is not UTC.
 */
class TimeTooltipTest {

    private static final Instant T = Instant.parse("2017-03-01T10:00:00.123456789Z");

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        // Restore the shared singleton the way DateTimeFormatterManagerTestUtil leaves it.
        initFormatter(ZoneOffset.UTC);
    }

    @Test
    void utcZoneShowsOneFullPrecisionLine() throws ReflectiveOperationException {
        initFormatter(ZoneOffset.UTC);

        String tooltip = TimeTooltip.text(T);

        assertThat(tooltip).isEqualTo("<html><tt>2017-03-01 10:00:00,123456789 Z</tt></html>");
        assertThat(tooltip).doesNotContain("<br>");
    }

    @Test
    void nonUtcZoneShowsLocalThenUtc() throws ReflectiveOperationException {
        initFormatter(ZoneId.of("Europe/Stockholm"));

        String tooltip = TimeTooltip.text(T);

        assertThat(tooltip).isEqualTo("<html><tt>2017-03-01 11:00:00,123456789 CET</tt><br>"
                + "<tt>2017-03-01 10:00:00,123456789 Z</tt></html>");
        assertThat(tooltip).contains("123456789"); // full precision, unlike the millis time column
    }

    @Test
    void nullTimestampHasNoTooltip() {
        assertThat(TimeTooltip.text(null)).isNull();
    }

    /** The manager is a singleton that keeps its first config: drop it so this test's zone takes effect. */
    private static void initFormatter(ZoneId zone) throws ReflectiveOperationException {
        Field instance = DateTimeFormatterManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        Config config = mock(Config.class);
        when(config.getLocale()).thenReturn(Locale.of("sv", "SE"));
        when(config.getZoneId()).thenReturn(zone);
        DateTimeFormatterManager.initialize(config);
    }
}
