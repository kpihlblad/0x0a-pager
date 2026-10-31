package se.grynna.pager.ui.preferences;

import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.chrono.Chronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.Locale;

public class DTUtil {

    /** Short date and long time with nanosecond precision: the full date-time of tooltips and details. */
    static DateTimeFormatter createFullFormatterWithNanos(Locale locale) {

        return formatterWithFraction(locale, FormatStyle.SHORT, FormatStyle.LONG, 9);
    }

    /** Short date and medium time with millisecond precision, e.g. for the selection span in the status bar. */
    public static DateTimeFormatter createDateTimeFormatterWithMillis(Locale locale) {
        return formatterWithFraction(locale, FormatStyle.SHORT, FormatStyle.MEDIUM, 3);
    }

    /** Medium time with millisecond precision, without the date: the log view's time column. */
    public static DateTimeFormatter createTimeFormatterWithMillis(Locale locale) {
        return formatterWithFraction(locale, null, FormatStyle.MEDIUM, 3);
    }

    /**
     * Rounds to the nearest millisecond, half up, carrying into the next second when the fraction rounds up to
     * a whole second. Display only: the stored {@link Instant} keeps its nanosecond precision.
     */
    static Instant roundToMillis(Instant instant) {
        long millis = (instant.getNano() + 500_000L) / 1_000_000L;
        return millis == 1000
                ? Instant.ofEpochSecond(instant.getEpochSecond() + 1)
                : Instant.ofEpochSecond(instant.getEpochSecond(), millis * 1_000_000L);
    }

    private static DateTimeFormatter formatterWithFraction(Locale locale, FormatStyle dateStyle, FormatStyle timeStyle,
            int fractionDigits) {
        Chronology chrono = Chronology.ofLocale(locale);

        String pat = DateTimeFormatterBuilder.getLocalizedDateTimePattern(dateStyle, timeStyle, chrono, locale);

        // locale specific decimal separator
        var decimalSeparator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        var secondAndFractionPart = "ss" + decimalSeparator + "S".repeat(fractionDigits);

        // replace ss with fraction-of-second for better precision demo
        pat = pat.replace("ss", secondAndFractionPart);
        // replace narrow no-break space with regular space for compatibility
        pat = pat.replace("\u202F", " ");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pat, locale);
        return formatter;
    }

    static DateTimeFormatter createDateTimeFormatter(Locale locale) {
        return createLocalizedFormatter(locale, FormatStyle.SHORT, FormatStyle.MEDIUM);
    }

    /** Short date and short time, for absolute range endpoints whose seconds are zero. */
    static DateTimeFormatter createDateTimeFormatterWithoutSeconds(Locale locale) {
        return createLocalizedFormatter(locale, FormatStyle.SHORT, FormatStyle.SHORT);
    }

    /** Medium time without the date, for range endpoints on the first visible line's date. */
    static DateTimeFormatter createTimeFormatter(Locale locale) {
        return createLocalizedFormatter(locale, null, FormatStyle.MEDIUM);
    }

    private static DateTimeFormatter createLocalizedFormatter(Locale locale, FormatStyle dateStyle,
            FormatStyle timeStyle) {
        Chronology chrono = Chronology.ofLocale(locale);
        String pat = DateTimeFormatterBuilder.getLocalizedDateTimePattern(dateStyle, timeStyle, chrono, locale);

        // replace narrow no-break space with regular space for compatibility
        pat = pat.replace("\u202F", " ");
        return DateTimeFormatter.ofPattern(pat, locale);
    }
}
