package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.splunk.Config;

import java.time.*;
import java.time.chrono.Chronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles lenient parsing of user-entered timestamps for "goto" navigation.
 * Supports locale-aware parsing with multiple format fallbacks:
 * <ul>
 *   <li>day words: now, today, yesterday (English + a few locales)</li>
 *   <li>hour:minute (uses reference date from status bar)</li>
 *   <li>hour:minute:second</li>
 *   <li>relative days: -1d 13:00, +2d 14:30</li>
 *   <li>weekday names: monday 13:00, måndag 13:00 (locale + English)</li>
 *   <li>date only (assumes midnight)</li>
 *   <li>full datetime</li>
 * </ul>
 * Uses the configured locale and timezone from Config.
 */
@Slf4j
public class GotoHandler {

    private final Config config;
    private final Consumer<Instant> onGoto;
    private final Runnable onCancel;
    private final Supplier<Instant> referenceDateSupplier;

    // Pattern for relative day offset: -1d, +2d, -3d (with optional time after)
    private static final Pattern RELATIVE_DAY_PATTERN = Pattern.compile("^([+-]?\\d+)d\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    // Pattern for relative day offset without time: -1d, +2d
    private static final Pattern RELATIVE_DAY_ONLY_PATTERN = Pattern.compile("^([+-]?\\d+)d$",
            Pattern.CASE_INSENSITIVE);

    // Day words as an offset in days from the reference date: English always, plus the configured locale
    private static final Map<String, Integer> DAY_WORD_OFFSETS_EN = Map.of("today", 0, "yesterday", -1);
    private static final Map<String, Map<String, Integer>> DAY_WORD_OFFSETS = Map.of(
            "sv", Map.of("idag", 0, "i dag", 0, "igår", -1));
    private static final Map<String, Set<String>> NOW_WORDS = Map.of(
            "en", Set.of("now"),
            "sv", Set.of("nu"));

    /**
     * Create a new GotoHandler.
     * @param config The application config (for locale/timezone)
     * @param onGoto Called with parsed Instant when user submits valid input
     * @param onCancel Called when user cancels (Escape or Ctrl-G)
     */
    public GotoHandler(Config config, Consumer<Instant> onGoto, Runnable onCancel) {
        this(config, onGoto, onCancel, () -> Instant.now());
    }

    /**
     * Create a new GotoHandler with a reference date supplier.
     * @param config The application config (for locale/timezone)
     * @param onGoto Called with parsed Instant when user submits valid input
     * @param onCancel Called when user cancels (Escape or Ctrl-G)
     * @param referenceDateSupplier Supplies the reference date (typically from status bar's current timestamp)
     */
    public GotoHandler(Config config, Consumer<Instant> onGoto, Runnable onCancel,
            Supplier<Instant> referenceDateSupplier) {
        this.config = config;
        this.onGoto = onGoto;
        this.onCancel = onCancel;
        this.referenceDateSupplier = referenceDateSupplier;
    }

    /**
     * Handle user input submission (Return key pressed).
     * Attempts lenient parsing of the input text.
     * @param input The user-entered text
     * @return true if parsing succeeded and navigation was triggered, false otherwise
     */
    public boolean handleSubmit(String input) {
        if (input == null || input.isBlank()) {
            log.debug("Empty goto input, cancelling");
            onCancel.run();
            return false;
        }

        Optional<Instant> parsed = parseTimestamp(input.trim());
        if (parsed.isPresent()) {
            log.info("Goto: parsed '{}' as {}", input, parsed.get());
            onGoto.accept(parsed.get());
            return true;
        } else {
            log.warn("Goto: failed to parse '{}'", input);
            return false;
        }
    }

    /**
     * Handle cancel action (Escape or Ctrl-G).
     */
    public void handleCancel() {
        log.debug("Goto cancelled");
        onCancel.run();
    }

    /**
     * Get the reference date from the supplier, falling back to now if null.
     */
    private LocalDate getReferenceDate(ZoneId zone) {
        Instant ref = referenceDateSupplier.get();
        if (ref == null) {
            return LocalDate.now(zone);
        }
        return ref.atZone(zone).toLocalDate();
    }

    /**
     * Parse a timestamp string leniently, trying multiple formats in order:
     * 0. Day words: now, today, yesterday
     * 1. Relative day offset: -1d 13:00, +2d 14:30
     * 2. Weekday names: monday 13:00, måndag 13:00
     * 3. hour:minute (reference date)
     * 4. hour:minute:second (reference date)
     * 5. date only (midnight)
     * 6. full datetime
     * All parsing respects the configured locale and timezone.
     */
    public Optional<Instant> parseTimestamp(String input) {
        Locale locale = config.getLocale();
        ZoneId zone = config.getZoneId();
        LocalDate referenceDate = getReferenceDate(zone);

        // Try day words first: now / today / yesterday
        Optional<Instant> dayWordResult = parseDayWord(input, locale, zone, referenceDate);
        if (dayWordResult.isPresent()) {
            return dayWordResult;
        }

        // Try relative day patterns first
        Optional<Instant> relativeDayResult = parseRelativeDay(input, locale, zone, referenceDate);
        if (relativeDayResult.isPresent()) {
            return relativeDayResult;
        }

        // Try weekday patterns
        Optional<Instant> weekdayResult = parseWeekday(input, locale, zone, referenceDate);
        if (weekdayResult.isPresent()) {
            return weekdayResult;
        }

        // Build list of parsers to try in order
        List<ParseAttempt> attempts = buildParseAttempts(locale, zone, referenceDate);

        for (ParseAttempt attempt : attempts) {
            try {
                Instant result = attempt.parse(input);
                if (result != null) {
                    log.debug("Parsed '{}' using {}", input, attempt.description);
                    return Optional.of(result);
                }
            } catch (DateTimeParseException e) {
                log.trace("Parse attempt failed for '{}' using {}: {}", input, attempt.description, e.getMessage());
            } catch (Exception e) {
                log.trace("Unexpected error parsing '{}' using {}: {}", input, attempt.description, e.getMessage());
            }
        }

        return Optional.empty();
    }

    /**
     * Parse day words: {@code now} (the clock), {@code today} and {@code yesterday} (start of day, relative to the
     * reference date), in English and the configured locale's language.
     */
    private Optional<Instant> parseDayWord(String input, Locale locale, ZoneId zone, LocalDate referenceDate) {
        String normalized = input.trim().toLowerCase(locale);
        if (NOW_WORDS.getOrDefault("en", Set.of()).contains(normalized)
                || NOW_WORDS.getOrDefault(locale.getLanguage(), Set.of()).contains(normalized)) {
            return Optional.of(Instant.now());
        }
        Integer offset = DAY_WORD_OFFSETS_EN.get(normalized);
        if (offset == null) {
            offset = DAY_WORD_OFFSETS.getOrDefault(locale.getLanguage(), Map.of()).get(normalized);
        }
        if (offset != null) {
            Instant result = referenceDate.plusDays(offset).atStartOfDay(zone).toInstant();
            log.debug("Parsed day word '{}' as {}", input, result);
            return Optional.of(result);
        }
        return Optional.empty();
    }

    /**
     * Parse relative day expressions like "-1d 13:00", "+2d 14:30", "-3d".
     */
    private Optional<Instant> parseRelativeDay(String input, Locale locale, ZoneId zone, LocalDate referenceDate) {
        // Try pattern with time
        Matcher matcherWithTime = RELATIVE_DAY_PATTERN.matcher(input.trim());
        if (matcherWithTime.matches()) {
            try {
                int dayOffset = Integer.parseInt(matcherWithTime.group(1));
                String timeStr = matcherWithTime.group(2).trim();

                LocalDate targetDate = referenceDate.plusDays(dayOffset);
                Optional<LocalTime> time = parseTimeOnly(timeStr, locale);

                if (time.isPresent()) {
                    Instant result = LocalDateTime.of(targetDate, time.get()).atZone(zone).toInstant();
                    log.debug("Parsed relative day '{}' as {} day(s) + time {}", input, dayOffset, time.get());
                    return Optional.of(result);
                }
            } catch (NumberFormatException e) {
                log.trace("Failed to parse day offset from '{}'", input);
            }
        }

        // Try pattern without time (defaults to midnight)
        Matcher matcherDayOnly = RELATIVE_DAY_ONLY_PATTERN.matcher(input.trim());
        if (matcherDayOnly.matches()) {
            try {
                int dayOffset = Integer.parseInt(matcherDayOnly.group(1));
                LocalDate targetDate = referenceDate.plusDays(dayOffset);
                Instant result = targetDate.atStartOfDay(zone).toInstant();
                log.debug("Parsed relative day '{}' as {} day(s) at midnight", input, dayOffset);
                return Optional.of(result);
            } catch (NumberFormatException e) {
                log.trace("Failed to parse day offset from '{}'", input);
            }
        }

        return Optional.empty();
    }

    /**
     * Parse weekday expressions like "monday 13:00", "måndag 13:00".
     * The weekday refers to the same week as the reference date.
     */
    private Optional<Instant> parseWeekday(String input, Locale locale, ZoneId zone, LocalDate referenceDate) {
        // Fold with the parsing locale so the input agrees with the weekday names
        // generated by buildWeekdayMap (e.g. Turkish "SALI" -> "salı").
        String normalized = input.trim().toLowerCase(locale);

        // Build weekday name maps for locale and English
        Map<String, DayOfWeek> weekdayMap = buildWeekdayMap(locale);

        for (Map.Entry<String, DayOfWeek> entry : weekdayMap.entrySet()) {
            String weekdayName = entry.getKey();
            if (normalized.startsWith(weekdayName)) {
                DayOfWeek targetDay = entry.getValue();
                String remainder = normalized.substring(weekdayName.length()).trim();

                // Find the date for that weekday in the same week as reference date
                LocalDate targetDate = getDateForWeekday(referenceDate, targetDay, locale);

                if (remainder.isEmpty()) {
                    // Weekday only - use midnight
                    Instant result = targetDate.atStartOfDay(zone).toInstant();
                    log.debug("Parsed weekday '{}' as {} at midnight", input, targetDate);
                    return Optional.of(result);
                } else {
                    // Weekday + time
                    Optional<LocalTime> time = parseTimeOnly(remainder, locale);
                    if (time.isPresent()) {
                        Instant result = LocalDateTime.of(targetDate, time.get()).atZone(zone).toInstant();
                        log.debug("Parsed weekday '{}' as {} at {}", input, targetDate, time.get());
                        return Optional.of(result);
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Build a map of weekday names (lowercase) to DayOfWeek.
     * Includes both locale-specific names and English names.
     */
    private Map<String, DayOfWeek> buildWeekdayMap(Locale locale) {
        Map<String, DayOfWeek> map = new LinkedHashMap<>(); // Preserve insertion order

        // Add locale-specific names (full and short)
        for (DayOfWeek day : DayOfWeek.values()) {
            // Full name in locale
            String fullName = day.getDisplayName(TextStyle.FULL, locale).toLowerCase(locale);
            map.put(fullName, day);

            // Short name in locale (e.g., "mån", "mon")
            String shortName = day.getDisplayName(TextStyle.SHORT, locale).toLowerCase(locale);
            if (!shortName.equals(fullName)) {
                map.put(shortName, day);
            }
        }

        // Add English names if locale is not English
        if (!locale.getLanguage().equals("en")) {
            for (DayOfWeek day : DayOfWeek.values()) {
                String fullNameEn = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(locale);
                map.putIfAbsent(fullNameEn, day);

                String shortNameEn = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(locale);
                map.putIfAbsent(shortNameEn, day);
            }
        }

        return map;
    }

    /**
     * Get the date for a specific weekday relative to the reference date.
     * If the weekday hasn't occurred yet this week (is after the reference day),
     * returns the date from last week instead (assumes user wants past dates).
     * Uses locale-specific week definition (e.g., weeks start on Monday in Europe, Sunday in US).
     */
    private LocalDate getDateForWeekday(LocalDate referenceDate, DayOfWeek targetDay, Locale locale) {
        WeekFields weekFields = WeekFields.of(locale);

        // Get the first day of the week containing the reference date
        DayOfWeek firstDayOfWeek = weekFields.getFirstDayOfWeek();
        LocalDate startOfWeek = referenceDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek));

        // Calculate days to add to get to the target day within the current week
        int daysToAdd = (targetDay.getValue() - firstDayOfWeek.getValue() + 7) % 7;
        LocalDate targetDate = startOfWeek.plusDays(daysToAdd);

        // If the target weekday is after the reference date, use last week instead
        // (user likely wants to navigate to a past date, not future)
        if (targetDate.isAfter(referenceDate)) {
            targetDate = targetDate.minusWeeks(1);
            log.debug("Weekday {} is after reference date {}, using previous week: {}", targetDay, referenceDate,
                    targetDate);
        }

        return targetDate;
    }

    /**
     * Parse a time-only string using various formats.
     */
    private Optional<LocalTime> parseTimeOnly(String input, Locale locale) {
        // Try HH:mm
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("H:mm", locale);
            return Optional.of(LocalTime.parse(input, fmt));
        } catch (DateTimeParseException ignored) {
            // Expected when the input is not HH:mm: fall through to the next format.
        }

        // Try HH:mm:ss
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("H:mm:ss", locale);
            return Optional.of(LocalTime.parse(input, fmt));
        } catch (DateTimeParseException ignored) {
            // Expected when the input is not HH:mm:ss: fall through to the next format.
        }

        // Try locale short time
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
            return Optional.of(LocalTime.parse(normalizeInput(input), fmt));
        } catch (DateTimeParseException ignored) {
            // Expected when the input is not a locale short time: fall through to the next format.
        }

        // Try locale medium time
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale);
            return Optional.of(LocalTime.parse(normalizeInput(input), fmt));
        } catch (DateTimeParseException ignored) {
            // Expected when the input matches no known time format: report via Optional.empty().
        }

        return Optional.empty();
    }

    private List<ParseAttempt> buildParseAttempts(Locale locale, ZoneId zone, LocalDate referenceDate) {
        List<ParseAttempt> attempts = new ArrayList<>();

        // 1. Time only: HH:mm (reference date)
        attempts.add(new ParseAttempt("time HH:mm", input -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("H:mm", locale);
            LocalTime time = LocalTime.parse(input, fmt);
            return LocalDateTime.of(referenceDate, time).atZone(zone).toInstant();
        }));

        // 2. Time with seconds: HH:mm:ss (reference date)
        attempts.add(new ParseAttempt("time HH:mm:ss", input -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("H:mm:ss", locale);
            LocalTime time = LocalTime.parse(input, fmt);
            return LocalDateTime.of(referenceDate, time).atZone(zone).toInstant();
        }));

        // 3. Locale-specific short time
        attempts.add(new ParseAttempt("locale short time", input -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
            LocalTime time = LocalTime.parse(normalizeInput(input), fmt);
            return LocalDateTime.of(referenceDate, time).atZone(zone).toInstant();
        }));

        // 4. Locale-specific medium time
        attempts.add(new ParseAttempt("locale medium time", input -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale);
            LocalTime time = LocalTime.parse(normalizeInput(input), fmt);
            return LocalDateTime.of(referenceDate, time).atZone(zone).toInstant();
        }));

        // 5. Date only (locale short) - midnight
        attempts.add(new ParseAttempt("locale short date", input -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale);
            LocalDate date = LocalDate.parse(normalizeInput(input), fmt);
            return date.atStartOfDay(zone).toInstant();
        }));

        // 6. ISO date (yyyy-MM-dd) - midnight
        attempts.add(new ParseAttempt("ISO date", input -> {
            LocalDate date = LocalDate.parse(input);
            return date.atStartOfDay(zone).toInstant();
        }));

        // 7. Full datetime (locale short)
        attempts.add(new ParseAttempt("locale short datetime", input -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale);
            LocalDateTime dt = LocalDateTime.parse(normalizeInput(input), fmt);
            return dt.atZone(zone).toInstant();
        }));

        // 8. Full datetime (locale medium)
        attempts.add(new ParseAttempt("locale medium datetime", input -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale);
            LocalDateTime dt = LocalDateTime.parse(normalizeInput(input), fmt);
            return dt.atZone(zone).toInstant();
        }));

        // 9. Locale short date + medium time (common format used in UI)
        attempts.add(new ParseAttempt("locale short date + medium time", input -> {
            Chronology chrono = Chronology.ofLocale(locale);
            String pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, FormatStyle.MEDIUM,
                    chrono, locale);
            pattern = pattern.replace("\u202F", " ");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern, locale);
            LocalDateTime dt = LocalDateTime.parse(normalizeInput(input), fmt);
            return dt.atZone(zone).toInstant();
        }));

        // 10. ISO datetime
        attempts.add(new ParseAttempt("ISO datetime", input -> {
            LocalDateTime dt = LocalDateTime.parse(input);
            return dt.atZone(zone).toInstant();
        }));

        // 11. ISO instant (with Z or offset)
        attempts.add(new ParseAttempt("ISO instant", Instant::parse));

        return attempts;
    }

    /**
     * Normalize input by replacing various unicode spaces with regular spaces.
     */
    private String normalizeInput(String input) {
        return input.replace('\u202F', ' ') // narrow no-break space
                .replace('\u00A0', ' '); // no-break space
    }

    /**
     * Helper class for parse attempts with description for logging.
     */
    private static class ParseAttempt {
        final String description;
        final java.util.function.Function<String, Instant> parser;

        ParseAttempt(String description, java.util.function.Function<String, Instant> parser) {
            this.description = description;
            this.parser = parser;
        }

        Instant parse(String input) {
            return parser.apply(input);
        }
    }
}
