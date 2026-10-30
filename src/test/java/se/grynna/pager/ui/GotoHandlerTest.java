package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import se.grynna.pager.splunk.Config;

import java.time.*;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for GotoHandler's lenient timestamp parsing.
 */
class GotoHandlerTest {

    @Mock
    private Config config;

    private AtomicReference<Instant> capturedTimestamp;
    private AtomicReference<Boolean> cancelCalled;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        capturedTimestamp = new AtomicReference<>();
        cancelCalled = new AtomicReference<>(false);

        // Default to Swedish locale and Stockholm timezone
        when(config.getLocale()).thenReturn(Locale.of("sv", "SE"));
        when(config.getZoneId()).thenReturn(ZoneId.of("Europe/Stockholm"));
    }

    private GotoHandler createHandler() {
        return new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true));
    }

    @Test
    void parseTimeOnly_HourMinute() {
        GotoHandler handler = createHandler();

        Optional<Instant> result = handler.parseTimestamp("14:30");

        assertTrue(result.isPresent(), "Should parse HH:mm format");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(14, parsed.getHour());
        assertEquals(30, parsed.getMinute());
        assertEquals(LocalDate.now(ZoneId.of("Europe/Stockholm")), parsed.toLocalDate());
    }

    @Test
    void parseTimeOnly_HourMinuteSecond() {
        GotoHandler handler = createHandler();

        Optional<Instant> result = handler.parseTimestamp("14:30:45");

        assertTrue(result.isPresent(), "Should parse HH:mm:ss format");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(14, parsed.getHour());
        assertEquals(30, parsed.getMinute());
        assertEquals(45, parsed.getSecond());
    }

    @Test
    void parseIsoDate() {
        GotoHandler handler = createHandler();

        Optional<Instant> result = handler.parseTimestamp("2025-12-25");

        assertTrue(result.isPresent(), "Should parse ISO date");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(2025, parsed.getYear());
        assertEquals(12, parsed.getMonthValue());
        assertEquals(25, parsed.getDayOfMonth());
        assertEquals(0, parsed.getHour(), "Should default to midnight");
    }

    @Test
    void parseIsoInstant() {
        GotoHandler handler = createHandler();

        Optional<Instant> result = handler.parseTimestamp("2025-12-25T14:30:00Z");

        assertTrue(result.isPresent(), "Should parse ISO instant");
        assertEquals(Instant.parse("2025-12-25T14:30:00Z"), result.get());
    }

    @Test
    void handleSubmit_validInput() {
        GotoHandler handler = createHandler();

        boolean success = handler.handleSubmit("14:30");

        assertTrue(success, "Should return true on successful parse");
        assertNotNull(capturedTimestamp.get(), "Should call onGoto callback");
        assertFalse(cancelCalled.get(), "Should not call onCancel");
    }

    @Test
    void handleSubmit_invalidInput() {
        GotoHandler handler = createHandler();

        boolean success = handler.handleSubmit("not a timestamp");

        assertFalse(success, "Should return false on parse failure");
        assertNull(capturedTimestamp.get(), "Should not call onGoto callback");
        assertFalse(cancelCalled.get(), "Should not call onCancel on invalid input");
    }

    @Test
    void handleSubmit_emptyInput() {
        GotoHandler handler = createHandler();

        boolean success = handler.handleSubmit("");

        assertFalse(success, "Should return false on empty input");
        assertTrue(cancelCalled.get(), "Should call onCancel on empty input");
    }

    @Test
    void handleCancel() {
        GotoHandler handler = createHandler();

        handler.handleCancel();

        assertTrue(cancelCalled.get(), "Should call onCancel callback");
        assertNull(capturedTimestamp.get(), "Should not call onGoto callback");
    }

    @Test
    void parseWithEnglishLocale() {
        when(config.getLocale()).thenReturn(Locale.US);
        when(config.getZoneId()).thenReturn(ZoneId.of("America/New_York"));

        GotoHandler handler = createHandler();

        // US short date format: M/d/yy
        Optional<Instant> result = handler.parseTimestamp("12/25/25");

        assertTrue(result.isPresent(), "Should parse US date format");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("America/New_York"));
        assertEquals(2025, parsed.getYear());
        assertEquals(12, parsed.getMonthValue());
        assertEquals(25, parsed.getDayOfMonth());
    }

    @Test
    void parseWithReferenceDate() {
        // Create handler with specific reference date (2025-06-15)
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("14:30");

        assertTrue(result.isPresent(), "Should parse time with reference date");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(2025, parsed.getYear());
        assertEquals(6, parsed.getMonthValue());
        assertEquals(15, parsed.getDayOfMonth());
        assertEquals(14, parsed.getHour());
        assertEquals(30, parsed.getMinute());
    }

    @Test
    void parseDayWord_todayAndYesterdayUseTheReferenceDate() {
        // Reference date: 2025-06-15
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();
        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        assertEquals(ZonedDateTime.of(2025, 6, 15, 0, 0, 0, 0, ZoneId.of("Europe/Stockholm")).toInstant(),
                handler.parseTimestamp("today").orElseThrow());
        assertEquals(ZonedDateTime.of(2025, 6, 14, 0, 0, 0, 0, ZoneId.of("Europe/Stockholm")).toInstant(),
                handler.parseTimestamp("Yesterday").orElseThrow());
    }

    @Test
    void parseDayWord_swedishLocale() {
        // sv-SE is the configured locale; reference date 2025-06-15
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();
        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        assertEquals(ZonedDateTime.of(2025, 6, 14, 0, 0, 0, 0, ZoneId.of("Europe/Stockholm")).toInstant(),
                handler.parseTimestamp("igår").orElseThrow());
        assertEquals(ZonedDateTime.of(2025, 6, 15, 0, 0, 0, 0, ZoneId.of("Europe/Stockholm")).toInstant(),
                handler.parseTimestamp("i dag").orElseThrow());
    }

    @Test
    void parseDayWord_nowIsTheClock() {
        GotoHandler handler = createHandler();

        Optional<Instant> result = handler.parseTimestamp("now");

        assertTrue(result.isPresent(), "Should parse 'now'");
        assertTrue(result.get().isAfter(Instant.now().minusSeconds(60))
                && result.get().isBefore(Instant.now().plusSeconds(60)), "Should be about now");
    }

    @Test
    void parseRelativeDay_minus1d() {
        // Reference date: 2025-06-15
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("-1d 13:00");

        assertTrue(result.isPresent(), "Should parse -1d 13:00");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(2025, parsed.getYear());
        assertEquals(6, parsed.getMonthValue());
        assertEquals(14, parsed.getDayOfMonth(), "Should be one day before reference");
        assertEquals(13, parsed.getHour());
        assertEquals(0, parsed.getMinute());
    }

    @Test
    void parseRelativeDay_plus2d() {
        // Reference date: 2025-06-15
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("+2d 09:30");

        assertTrue(result.isPresent(), "Should parse +2d 09:30");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(17, parsed.getDayOfMonth(), "Should be two days after reference");
        assertEquals(9, parsed.getHour());
        assertEquals(30, parsed.getMinute());
    }

    @Test
    void parseRelativeDay_noTime() {
        // Reference date: 2025-06-15
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("-1d");

        assertTrue(result.isPresent(), "Should parse -1d without time");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(14, parsed.getDayOfMonth(), "Should be one day before reference");
        assertEquals(0, parsed.getHour(), "Should default to midnight");
    }

    @Test
    void parseWeekday_monday() {
        // Reference date: 2025-06-15 (Sunday)
        // In Swedish locale, week starts on Monday, so Monday of that week is 2025-06-09
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("monday 14:00");

        assertTrue(result.isPresent(), "Should parse 'monday 14:00'");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(DayOfWeek.MONDAY, parsed.getDayOfWeek());
        assertEquals(14, parsed.getHour());
    }

    @Test
    void parseWeekday_swedishLocale() {
        // Reference date: 2025-06-15 (Sunday)
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        // Swedish weekday name
        Optional<Instant> result = handler.parseTimestamp("måndag 14:00");

        assertTrue(result.isPresent(), "Should parse Swedish weekday 'måndag 14:00'");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(DayOfWeek.MONDAY, parsed.getDayOfWeek());
        assertEquals(14, parsed.getHour());
    }

    @Test
    void parseWeekday_noTime() {
        // Reference date: 2025-06-15 (Sunday)
        // Friday is 2025-06-13 (in the past, same week)
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("friday");

        assertTrue(result.isPresent(), "Should parse weekday without time");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(DayOfWeek.FRIDAY, parsed.getDayOfWeek());
        assertEquals(13, parsed.getDayOfMonth(), "Should be Friday June 13");
        assertEquals(0, parsed.getHour(), "Should default to midnight");
    }

    @Test
    void parseWeekday_futureWeekdayUsesLastWeek() {
        // Reference date: 2025-06-11 (Wednesday)
        // "friday" would be 2025-06-13 (future), so should use last week: 2025-06-06
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 11, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("friday 14:00");

        assertTrue(result.isPresent(), "Should parse future weekday");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(DayOfWeek.FRIDAY, parsed.getDayOfWeek());
        assertEquals(6, parsed.getDayOfMonth(), "Should be last Friday (June 6), not upcoming Friday");
        assertEquals(14, parsed.getHour());
    }

    @Test
    void parseWeekday_turkishUppercaseName() {
        // Reference date: 2025-06-15 (Sunday), Turkish weeks start on Monday.
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 15, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        Locale originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try {
            when(config.getLocale()).thenReturn(Locale.of("tr", "TR"));

            GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                    () -> referenceInstant);

            // "SALI" is the Turkish uppercase form of "Salı" (Tuesday); folding with
            // Locale.ROOT would yield "sali" and fail to match the display name.
            Optional<Instant> result = handler.parseTimestamp("SALI 14:00");

            assertTrue(result.isPresent(), "Should parse Turkish weekday 'SALI 14:00'");
            ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
            assertEquals(DayOfWeek.TUESDAY, parsed.getDayOfWeek());
            assertEquals(10, parsed.getDayOfMonth(), "Should be Tuesday June 10 (same week)");
            assertEquals(14, parsed.getHour());
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void parseWeekday_pastWeekdayUsesSameWeek() {
        // Reference date: 2025-06-13 (Friday)
        // "wednesday" is 2025-06-11 (past), so should use same week
        Instant referenceInstant = ZonedDateTime.of(2025, 6, 13, 10, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
                .toInstant();

        GotoHandler handler = new GotoHandler(config, capturedTimestamp::set, () -> cancelCalled.set(true),
                () -> referenceInstant);

        Optional<Instant> result = handler.parseTimestamp("wednesday 09:00");

        assertTrue(result.isPresent(), "Should parse past weekday");
        ZonedDateTime parsed = result.get().atZone(ZoneId.of("Europe/Stockholm"));
        assertEquals(DayOfWeek.WEDNESDAY, parsed.getDayOfWeek());
        assertEquals(11, parsed.getDayOfMonth(), "Should be Wednesday June 11 (same week)");
        assertEquals(9, parsed.getHour());
    }
}
