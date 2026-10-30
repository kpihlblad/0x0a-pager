package se.grynna.pager.ui;

import se.grynna.pager.splunk.Config;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.ZoneId;
import java.util.Locale;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DateTimeFormatterManagerTestUtil {
    /** {@code HH:mm:ss} of {@code t} in the zone the formatter manager shows (it is a singleton: the first test to initialise it picks the zone). */
    public static String localTime(java.time.Instant t) {
        return t.atZone(DateTimeFormatterManager.zoneId())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT));
    }

    /** {@code uuuu-MM-dd HH:mm} of {@code t} in the zone the formatter manager shows. */
    public static String localDateMinute(java.time.Instant t) {
        return t.atZone(DateTimeFormatterManager.zoneId())
                .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT));
    }

    public static void initDTM() {
        Config config = mock(Config.class);
        when(config.getLocale()).thenReturn(Locale.of("sv", "SE"));
        when(config.getZoneId()).thenReturn(ZoneId.of("UTC"));
        DateTimeFormatterManager.initialize(config);
    }
}
