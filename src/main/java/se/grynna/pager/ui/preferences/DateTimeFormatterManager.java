package se.grynna.pager.ui.preferences;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.ui.RangeText;
import se.grynna.pager.ui.SelectionSpanText;

import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Centralized manager for datetime formatting with locale and zone support.
 * Provides the formatters:
 * - logFormatterWithMillis: the log viewer's time column (time only, rounded to millis)
 * - fullFormatterWithNanos: full date-time with nanos, for the time-column tooltip and log details
 * - formatter: For UI displays (standard precision)
 *
 * All respect the user's configured locale and timezone from preferences.
 */
@Slf4j
public class DateTimeFormatterManager {

    private static DateTimeFormatterManager instance;

    private final Config config;
    private DateTimeFormatter logFormatterWithMillis; // For the log viewer's time column
    private DateTimeFormatter fullFormatterWithNanos; // For the time-column tooltip and log details
    private DateTimeFormatter formatter; // For UI displays
    private DateTimeFormatter dateTimeWithMillis; // For the selection span (date and time)
    private DateTimeFormatter timeWithMillis; // For the selection span (same day)
    private final List<Runnable> changeListeners = new ArrayList<>();

    private DateTimeFormatterManager(Config config) {
        this.config = config;
        configure();
    }

    /**
     * Initialize the singleton instance with a Config object.
     * Must be called before getInstance().
     */
    public static synchronized DateTimeFormatterManager initialize(Config config) {
        if (instance == null) {
            instance = new DateTimeFormatterManager(config);
            log.debug("DateTimeFormatterManager initialized with locale: {}, zone: {}", config.getLocale(),
                    config.getZoneId());
        }
        return instance;
    }

    /**
     * Get the singleton instance. Must be initialized first.
     */
    public static DateTimeFormatterManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("DateTimeFormatterManager not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * The configured display zone, the one every timestamp in the UI is shown in.
     */
    public static ZoneId zoneId() {
        return getInstance().config.getZoneId();
    }

    /**
     * Format an Instant for the log viewer's time column: rounded to milliseconds (display only, the
     * {@link Instant} itself keeps full precision), using the configured zone.
     */
    public static String formatLogTimestamp(Instant instant) {
        if (instant == null) return "";
        var mgr = getInstance();
        return mgr.logFormatterWithMillis.withZone(mgr.config.getZoneId()).format(DTUtil.roundToMillis(instant));
    }

    /**
     * Format an Instant as a full date-time with nanosecond precision in the configured zone (log details).
     */
    public static String formatFullLogTimestamp(Instant instant) {
        return formatFullTimestamp(instant, zoneId());
    }

    /**
     * Format an Instant as a full date-time with nanosecond precision in {@code zone}: the time-column
     * tooltip shows this in the configured zone and in UTC.
     */
    public static String formatFullTimestamp(Instant instant, ZoneId zone) {
        if (instant == null) return "";
        return getInstance().fullFormatterWithNanos.withZone(zone).format(instant);
    }

    /**
     * Format an Instant for UI display (without nanos, using configured zone).
     */
    public static String formatTimestamp(Instant instant) {
        if (instant == null) return "--";
        var mgr = getInstance();
        return mgr.formatter.withZone(mgr.config.getZoneId()).format(instant);
        //            .replace('\u202F', ' ')
        //            .replace('\u00A0', ' ');
    }

    /**
     * Format the time range of a selection (with millis, using configured zone and locale), e.g.
     * {@code 12:00:01.200 → 12:02:14.600 (Δ 2m 13.4s)}.
     */
    public static String formatSelectionSpan(Instant first, Instant last) {
        var mgr = getInstance();
        var decimalSeparator = DecimalFormatSymbols.getInstance(mgr.config.getLocale()).getDecimalSeparator();
        return SelectionSpanText.range(first, last, mgr.config.getZoneId(), mgr.dateTimeWithMillis,
                mgr.timeWithMillis, decimalSeparator);
    }

    /**
     * The search range in the configured zone, e.g. {@code 11:00:00 → 12:10:00} or {@code everything}.
     */
    public static String formatRange(Instant start, Instant end, Instant anchor, Instant now) {
        return RangeText.range(start, end, anchor, now, zoneId());
    }

    /**
     * The Y/N button text for widening a range edge to a suggested boundary, e.g. {@code 1h earlier (11:00:00)}.
     */
    public static String formatRangeStep(Instant edge, Instant suggested, Instant anchor, Instant now) {
        return RangeText.step(edge, suggested, anchor, now, zoneId());
    }

    /**
     * One range endpoint in the configured zone, e.g. {@code 11:00:00}, {@code now} or {@code start}.
     */
    public static String formatRangeEndpoint(Instant t, Instant anchor, Instant now) {
        return RangeText.endpoint(t, anchor, now, zoneId());
    }

    /**
     * The full absolute range in the configured zone, for tooltips.
     */
    public static String formatRangeFull(Instant start, Instant end) {
        return RangeText.full(start, end, zoneId());
    }

    /**
     * Register a listener to be notified when the formatter changes.
     * Listeners are called on the same thread that triggers the refresh.
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Remove a change listener.
     */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    /**
     * Refresh formatters from config and notify all listeners.
     * Should be called after locale or timezone changes.
     */
    public void refresh() {
        Locale newLocale = config.getLocale();
        log.debug("Refreshing formatters with locale: {}, zone: {}", newLocale, config.getZoneId());
        configure();
        notifyListeners();
    }

    private void configure() {
        var locale = config.getLocale();
        this.logFormatterWithMillis = DTUtil.createTimeFormatterWithMillis(locale);
        this.fullFormatterWithNanos = DTUtil.createFullFormatterWithNanos(locale);
        this.formatter = DTUtil.createDateTimeFormatter(locale);
        this.dateTimeWithMillis = DTUtil.createDateTimeFormatterWithMillis(locale);
        this.timeWithMillis = DTUtil.createTimeFormatterWithMillis(locale);
    }

    private void notifyListeners() {
        log.debug("Notifying {} listeners of formatter change", changeListeners.size());
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.error("Error notifying formatter change listener", e);
            }
        }
    }
}
