package se.grynna.pager.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.splunk.*;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static se.grynna.pager.splunk.Level.INFO;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.JSON;

// INSTRUCTIONS: prefer AssertJ assertThat over JUnit's assertEquals

class CursorManagerBoundariesTest {

    public static final Instant TIMESTAMP = Instant.parse("2024-09-17T08:21:00Z");
    public static final String MSG_20 = "abcdefghijklmnopqrst";
    private static final long LONG_LONG_TIME = 10L * 365L * 24L * 60L * 60L * 1000L; // 10 years-ish in milliseconds
    private static final Instant A_LONG_LONG_TIME_AGO = TIMESTAMP.minusMillis(LONG_LONG_TIME);
    private static final Instant IN_A_LONG_LONG_TIME = TIMESTAMP.plusMillis(LONG_LONG_TIME);

    private LogDataProvider logProvider;
    private LogViewPort viewPort;
    private CursorManager cursorManager;

    @BeforeEach
    void setUp() throws TemporarySearchException, SearchException, StorageException {
        logProvider = mock(LogDataProvider.class);
        when(logProvider.getMaxOobPerSide()).thenReturn(1);
        viewPort = mock(LogViewPort.class);
        cursorManager = new CursorManager(logProvider, viewPort);

        Config config = mock(Config.class);
        when(config.getLocale()).thenReturn(Locale.ENGLISH);
        when(config.getZoneId()).thenReturn(ZoneId.of("UTC"));
        DateTimeFormatterManager.initialize(config);
    }

    @Test
    void scrollContentWithinBounds() throws Exception {
        setupLines(null, null, null);
        cursorManager.updateSize(10, 10);
        cursorManager.scrollContent(5, 0);
        verify(viewPort).updateVisibleLines();
    }

    @Test
    void snapToBeforeBoundary_whenScrollingTooFarBack() throws Exception {
        // Setup: before boundary at line 100
        setupLines(0, null, null);
        cursorManager.updateSize(10, 80); // 10 lines per page, 80 chars per line

        cursorManager.scrollContent(-10, 0);

        int actualTopLine = cursorManager.getPageStart();
        org.assertj.core.api.Assertions.assertThat(actualTopLine).as("topLineOnPage should snap to before boundary")
                .isEqualTo(-6);
    }

    @Test
    void snapToNowBoundary_whenScrollingTooFarForward() throws Exception {
        // Setup: now boundary at line 150, maxOobPerSide = 2, so max bottom = 148
        // With 10 lines per page, max topLine = 148 - 10 = 138
        setupLines(null, null, 15);
        cursorManager.updateSize(10, 80);

        cursorManager.scrollContent(20, 0);

        // Should be snapped to 138
        int actualTopLine = cursorManager.getPageStart();
        org.assertj.core.api.Assertions.assertThat(actualTopLine)
                .as("topLineOnPage should snap to keep bottom within now boundary + maxOobPerSide").isEqualTo(11);
    }

    @Test
    void snapToNowBoundary_whenNowIsCloserThanAfter() throws Exception {
        setupLines(null, 300, 150);
        cursorManager.updateSize(10, 80);

        cursorManager.scrollContent(500, 0);

        // Should snap to now boundary: 150 - 2 - 10 = 138
        int actualTopLine = cursorManager.getPageStart();
        org.assertj.core.api.Assertions.assertThat(actualTopLine)
                .as("Should use now boundary when it's more restrictive than after boundary").isEqualTo(146);
    }

    @Test
    void scrollContent_snapsToBeforeBoundary() throws Exception {
        setupLines(-100, null, null);
        cursorManager.updateSize(10, 80);

        cursorManager.scrollContent(-150, 0);

        int actualTopLine = cursorManager.getPageStart();
        org.assertj.core.api.Assertions.assertThat(actualTopLine).as("Scrolling should respect before boundary")
                .isEqualTo(-106);
    }

    @Test
    void scrollContent_snapsToAfterBoundary() throws Exception {
        setupLines(null, 200, null);
        cursorManager.updateSize(10, 80);

        // Start at line 180 (valid position)
        cursorManager.setPageStart(180);

        // Try to scroll forward 15 lines (would go to 195, exceeding boundary)
        cursorManager.scrollContent(25, 0);

        int actualTopLine = cursorManager.getPageStart();
        org.assertj.core.api.Assertions.assertThat(actualTopLine).as("Scrolling should respect after boundary")
                .isEqualTo(196);
    }

    @Test
    void allowScrollingWithinBoundaries() throws Exception {
        setupLines(100, 200, null);
        cursorManager.updateSize(10, 80);

        // Scroll to valid positions between boundaries
        cursorManager.setPageStart(120);
        org.assertj.core.api.Assertions.assertThat(cursorManager.getPageStart())
                .as("Should allow scrolling to 120 (within boundaries)").isEqualTo(120);

        cursorManager.setPageStart(150);
        org.assertj.core.api.Assertions.assertThat(cursorManager.getPageStart())
                .as("Should allow scrolling to 150 (within boundaries)").isEqualTo(150);

        cursorManager.setPageStart(180);
        org.assertj.core.api.Assertions.assertThat(cursorManager.getPageStart())
                .as("Should allow scrolling to 180 (within boundaries)").isEqualTo(180);
    }

    @Test
    void noBoundaries_allowsFreeScrolling() throws Exception {
        setupLines(null, null, null);
        cursorManager.updateSize(10, 80);

        // Should allow scrolling to any reasonable position
        cursorManager.setPageStart(1000);
        org.assertj.core.api.Assertions.assertThat(cursorManager.getPageStart())
                .as("Should allow scrolling to 1000 with no boundaries").isEqualTo(1000);

        cursorManager.setPageStart(-500);
        org.assertj.core.api.Assertions.assertThat(cursorManager.getPageStart())
                .as("Should allow scrolling to -500 with no boundaries").isEqualTo(-500);
    }

    @Test
    void extremeDownwardScroll_clampsDeltaInsteadOfWrapping() throws Exception {
        setupLines(null, Integer.MIN_VALUE, null);
        cursorManager.updateSize(10, 80);
        cursorManager.setPageStart(Integer.MAX_VALUE - 2);

        cursorManager.scrollContent(1, 0);

        // snappedDelta = 1 - (MAX_VALUE - 2 + 1 + 3 - (MIN_VALUE - 1)) = -4294967297,
        // clamped to MIN_VALUE so pageStart moves by exactly Integer.MIN_VALUE.
        assertThat(cursorManager.getPageStart()).isEqualTo(-3);
    }

    @Test
    void extremeUpwardScroll_clampsDeltaInsteadOfWrapping() throws Exception {
        setupLines(Integer.MAX_VALUE, null, null);
        cursorManager.updateSize(10, 80);
        cursorManager.setPageStart(Integer.MIN_VALUE + 3);

        cursorManager.scrollContent(-2, 0);

        // snappedDelta = -2 + (MAX_VALUE - (MIN_VALUE + 9)), clamped to MAX_VALUE
        assertThat(cursorManager.getPageStart()).isEqualTo(2);
    }

    @Test
    void noBoundaryObjectsAtAll_movesAndScrollsWithoutNpe() throws Exception {
        // Before any time range exists the provider returns null boundaries (e.g. Space/PgDn at startup)
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(invocation -> {
            int lineNo = invocation.getArgument(0);
            var time = TIMESTAMP.plusMillis(lineNo);
            return new LoadedLogLine(JSON, "segment1", lineNo, "cb1", "bkt", time, "{msg=\"" + MSG_20 + "\"}", time,
                    time, MSG_20, false, INFO);
        });
        cursorManager.updateSize(10, 80);

        cursorManager.moveCursor(10, 0, false);
        cursorManager.scrollContent(10, 0);
        cursorManager.scrollContent(-30, 0);

        verify(viewPort, atLeastOnce()).updateVisibleLines();
    }

    /**
     * Loaded rows 0..9 like the real provider: BEFORE boundary at the first loaded
     * row, AFTER at the exclusive end, and empty boundary rows outside.
     */
    private void setupSegment() throws TemporarySearchException, SearchException, StorageException {
        var bb = new Boundary(BoundaryType.BEFORE, TIMESTAMP, 0);
        var ab = new Boundary(BoundaryType.AFTER, TIMESTAMP.plusMillis(10), 10);
        when(logProvider.getBeforeBoundary()).thenReturn(bb);
        when(logProvider.getAfterBoundary()).thenReturn(ab);
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, IN_A_LONG_LONG_TIME));
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(invocation -> {
            int lineNo = invocation.getArgument(0);
            if (lineNo < 0) return new BeforeBoundaryLine(lineNo, bb);
            if (lineNo >= 10) return new AfterBoundaryLine(lineNo, ab);
            var time = TIMESTAMP.plusMillis(lineNo);
            return new LoadedLogLine(JSON, "segment1", lineNo, "cb1", "bkt", time, "{msg=\"" + MSG_20 + "\"}", time,
                    time, MSG_20, false, INFO);
        });
    }

    @Test
    void movingPastTheLastRowKeepsTheColumn() throws Exception {
        // The row is clamped before its width is measured
        setupSegment();
        cursorManager.updateSize(10, 80);

        cursorManager.setCursorFromClick(9, 15, false);
        cursorManager.moveCursor(1, 0, false);
        var cursor = cursorManager.getCursor().orElseThrow();
        assertThat(cursor.line).isEqualTo(9);
        assertThat(cursor.col()).isEqualTo(15);
    }

    @Test
    void movingPastTheFirstRowKeepsTheColumn() throws Exception {
        setupSegment();
        cursorManager.updateSize(10, 80);

        cursorManager.setCursorFromClick(1, 15, false);
        cursorManager.moveCursor(-3, 0, false);
        var cursor = cursorManager.getCursor().orElseThrow();
        assertThat(cursor.line).isEqualTo(0);
        assertThat(cursor.col()).isEqualTo(15);
    }

    @Test
    void shiftDownPastTheLastRowSelectsUpToTheColumn() throws Exception {
        setupSegment();
        cursorManager.updateSize(10, 80);

        cursorManager.setCursorFromClick(8, 15, false);
        cursorManager.moveCursor(3, 0, true);
        var cursor = cursorManager.getCursor().orElseThrow();
        assertThat(cursor.line).isEqualTo(9);
        assertThat(cursor.col()).isEqualTo(15);
        assertThat(cursorManager.isLineSelected(9).getRight()).isEqualTo(15);
    }

    @Test
    void shiftUpPastTheFirstRowSelectsFromTheColumn() throws Exception {
        setupSegment();
        cursorManager.updateSize(10, 80);

        cursorManager.setCursorFromClick(2, 15, false);
        cursorManager.moveCursor(-5, 0, true);
        var cursor = cursorManager.getCursor().orElseThrow();
        assertThat(cursor.line).isEqualTo(0);
        assertThat(cursor.col()).isEqualTo(15);
        assertThat(cursorManager.isLineSelected(0).getLeft()).isEqualTo(15);
    }

    @Test
    void clickBelowTheLastRowKeepsTheColumn() throws Exception {
        setupSegment();
        cursorManager.updateSize(15, 80);

        cursorManager.setCursorFromClick(12, 15, false);
        var cursor = cursorManager.getCursor().orElseThrow();
        assertThat(cursor.line).isEqualTo(9);
        assertThat(cursor.col()).isEqualTo(15);
    }

    private void setupLines(Integer beforeBoundary, Integer afterBoundary, Integer nowBoundary)
            throws TemporarySearchException, SearchException, StorageException {

        if (beforeBoundary != null) {
            var bb = new Boundary(BoundaryType.BEFORE, TIMESTAMP.plusMillis(beforeBoundary), beforeBoundary);
            var bbl = new BeforeBoundaryLine(beforeBoundary, bb);
            when(logProvider.getLine(eq(beforeBoundary), anyBoolean())).thenReturn(bbl);
            when(logProvider.getBeforeBoundary()).thenReturn(bb);
        } else {
            when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, A_LONG_LONG_TIME_AGO));
        }

        if (afterBoundary != null) {
            var ab = new Boundary(BoundaryType.AFTER, TIMESTAMP.plusMillis(afterBoundary), afterBoundary);
            var abl = new AfterBoundaryLine(afterBoundary, ab);
            when(logProvider.getLine(eq(afterBoundary), anyBoolean())).thenReturn(abl);
            when(logProvider.getAfterBoundary()).thenReturn(ab);
        } else {
            when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, IN_A_LONG_LONG_TIME));
        }

        if (nowBoundary != null) {
            var nb = new Boundary(BoundaryType.NOW, TIMESTAMP.plusMillis(nowBoundary), nowBoundary);
            var nbl = new NowBoundaryLine(nowBoundary, nb);
            when(logProvider.getLine(eq(nowBoundary), anyBoolean())).thenReturn(nbl);
            when(logProvider.getNowBoundary()).thenReturn(nb);
        } else {
            when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, IN_A_LONG_LONG_TIME));
        }

        // Mock regular log lines
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer((invocation) -> {
            int lineNo = invocation.getArgument(0);
            var time = TIMESTAMP.plusMillis(lineNo);
            return new LoadedLogLine(JSON, "segment1", lineNo, "cb1", "bkt", time, "{msg=\"" + MSG_20 + "\"}", time,
                    time, MSG_20, false, INFO);
        });
    }
}
