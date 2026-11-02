package se.grynna.pager.ui;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.splunk.*;
import se.grynna.pager.ui.clip.InMemoryClipboard;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static se.grynna.pager.splunk.Level.INFO;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.JSON;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.RAW;
import static se.grynna.pager.ui.DecoratedLogLine.getPreambleLength;

// INSTRUCTIONS: prefer AssertJ assertThat over JUnit's assertEquals

class CursorManagerTest {

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
        viewPort = mock(LogViewPort.class);
        cursorManager = new CursorManager(logProvider, viewPort);

        DateTimeFormatterManagerTestUtil.initDTM();

        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer((invocation) -> {
            int lineNo = 12345; // invocation.getArgument(0);

            var time = TIMESTAMP;
            return new LoadedLogLine(JSON, "segment1", lineNo, "cb1", "bkt", time, "{msg=\"" + MSG_20 + "\"}", time,
                    time, MSG_20, false, INFO);
        });

        when(logProvider.getBeforeBoundary()).thenReturn(new Boundary(BoundaryType.BEFORE, A_LONG_LONG_TIME_AGO));
        when(logProvider.getAfterBoundary()).thenReturn(new Boundary(BoundaryType.AFTER, IN_A_LONG_LONG_TIME));
        when(logProvider.getNowBoundary()).thenReturn(new Boundary(BoundaryType.NOW, IN_A_LONG_LONG_TIME));
    }

    @Test
    void scrollContentWithinBounds() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.scrollContent(5, 0);
        verify(viewPort).updateVisibleLines();
    }

    @Test
    void scrollContentBeyondUpperBound() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.scrollContent(Integer.MIN_VALUE, 0);
    }

    @Test
    void scrollContentBeyondLowerBound() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.scrollContent(Integer.MAX_VALUE, 0);
    }

    @Test
    void scrollCentreToPutsTheRowInTheMiddleOfThePageWithoutTouchingTheCursor() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(1, 0, false);
        Integer cursorLine = cursorManager.cursorLine().orElse(null);

        cursorManager.scrollCentreTo(100);

        assertThat(cursorManager.getPageStart()).isEqualTo(95);
        assertThat(cursorManager.cursorLine().orElse(null)).isEqualTo(cursorLine);
        assertThat(cursorManager.hasNavigationHighlight()).isFalse();
    }

    @Test
    void scrollCentreToDoesNothingWhenThePageIsAlreadyThere() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.setPageStart(95);
        clearInvocations(viewPort);

        cursorManager.scrollCentreTo(100);

        assertThat(cursorManager.getPageStart()).isEqualTo(95);
        verify(viewPort, never()).updateVisibleLines();
    }

    @Test
    void moveCursorWithoutSelection() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(1, 0, false);
        verify(viewPort).updateVisibleLines();
    }

    @Test
    void moveCursorWithSelection() throws Exception {
        cursorManager.updateSize(10, 10);

        cursorManager.moveCursor(1, 0, true);

        // At this point, the mark should be at line 4 ((linesPerPage-1)/2)
        // and the cursor at line 5 col 0, ie nothing is selected on line 5.
        // Illustration:
        // 0
        // 1
        // 2
        // 3
        // 4 M0
        // 5 C0
        // 6
        // 7
        // 8
        // 9

        assertThat(cursorManager.getMark()).isNotNull();

        var lines = cursorManager.getVisibleLines();

        // all rows should be unselected execpt row 5
        for (int i = 0; i < lines.size(); i++) {
            var l = lines.get(i);

            if (i == 4) {
                assertThat(l.getSelectionStart()).isEqualTo(0);
                assertThat(l.getSelectionEnd()).isGreaterThan(1);
            } else {
                assertThat(l.getSelectionStart()).as("col %d should not be selected", i).isEqualTo(-1);
                assertThat(l.getSelectionEnd()).as("line %d should not be selected", i).isEqualTo(-1);
            }
        }
    }

    @Test
    void returnsNullWhenMarkOrCursorIsAbsent() {
        cursorManager.updateSize(10, 10);
        var selection = cursorManager.isLineSelected(5);
        assertThat(selection).isNull();
    }

    @Test
    void returnsNullIfLineOutsideSelectionRange() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(0, 0, true);
        // at this point, the cursor is at line 4 ((linesPerPage-1)/2)
        cursorManager.moveCursor(2, 0, true);

        // 3
        // 4 x
        // 5
        // 6
        // 7

        assertThat(cursorManager.isLineSelected(3)).isNull();
        assertThat(cursorManager.isLineSelected(4)).isNotNull();
        assertThat(cursorManager.isLineSelected(5)).isNotNull();
        assertThat(cursorManager.isLineSelected(6)).isNull();
    }

    @Test
    void returnsExactColumnsForSingleLineSelection() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(5, 0, true);
        var selection = cursorManager.isLineSelected(5);
        assertThat(selection.getLeft()).isZero();
        assertThat(selection.getRight()).isGreaterThan(0);
    }

    @Test
    void returnsSelectionForFirstLine() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(5, 0, false);
        cursorManager.moveCursor(-2, 0, true);

        assertThat(cursorManager.isLineSelected(7).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(7).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(8).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(8).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(9)).isNull();
    }

    @Test
    void returnsPartialSelectionForFirstLine() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(5, 0, false);
        cursorManager.moveCursor(-2, 0, true);
        cursorManager.moveCursor(0, 3, true);

        assertThat(cursorManager.isLineSelected(7).getLeft()).isEqualTo(3);
        assertThat(cursorManager.isLineSelected(7).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(8).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(8).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(9)).isNull();
    }

    @Test
    void returnsPartialSelectionForLastLine() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(4, 0, true);
        cursorManager.moveCursor(0, 4, true);

        assertThat(cursorManager.isLineSelected(0)).isNull();
        assertThat(cursorManager.isLineSelected(1)).isNull();
        assertThat(cursorManager.isLineSelected(2)).isNull();
        assertThat(cursorManager.isLineSelected(3)).isNull();

        assertThat(cursorManager.isLineSelected(4).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(4).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(5).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(5).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(6).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(6).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(7).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(7).getRight()).isEqualTo(Integer.MAX_VALUE);
        assertThat(cursorManager.isLineSelected(8).getLeft()).isEqualTo(0);
        assertThat(cursorManager.isLineSelected(8).getRight()).isEqualTo(4);
        assertThat(cursorManager.isLineSelected(9)).isNull();

        //
        // 0
        // 1
        // 2
        // 3
        // 4 xxxxxx>
        // 5 xxxxxx>
        // 6 xxxxxx>
        // 7 xxxxxx>
        // 8 xxxxxx>
        // 9 xxxx

        var vl = cursorManager.getVisibleLines();
        assertThat(vl.size()).isEqualTo(10);

        // first line selected is 4
        assertThat(vl.get(0).getSelectionStart()).isEqualTo(-1);
        assertThat(vl.get(0).getSelectionEnd()).isEqualTo(-1);
        assertThat(vl.get(1).getSelectionStart()).isEqualTo(-1);
        assertThat(vl.get(1).getSelectionEnd()).isEqualTo(-1);
        assertThat(vl.get(2).getSelectionStart()).isEqualTo(-1);
        assertThat(vl.get(2).getSelectionEnd()).isEqualTo(-1);

        assertThat(vl.get(3).getSelectionStart()).isEqualTo(-1);
        assertThat(vl.get(3).getSelectionEnd()).isEqualTo(-1);
        assertThat(vl.get(4).getSelectionStart()).isEqualTo(0);
        assertThat(vl.get(4).getSelectionEnd()).isEqualTo(Integer.MAX_VALUE);
        assertThat(vl.get(5).getSelectionStart()).isEqualTo(0);
        assertThat(vl.get(5).getSelectionEnd()).isEqualTo(Integer.MAX_VALUE);
        assertThat(vl.get(6).getSelectionStart()).isEqualTo(0);
        assertThat(vl.get(6).getSelectionEnd()).isEqualTo(Integer.MAX_VALUE);
        assertThat(vl.get(7).getSelectionStart()).isEqualTo(0);
        assertThat(vl.get(7).getSelectionEnd()).isEqualTo(Integer.MAX_VALUE);
        assertThat(vl.get(8).getSelectionStart()).isEqualTo(0);
        assertThat(vl.get(8).getSelectionEnd()).isEqualTo(4);
        assertThat(vl.get(9).getSelectionStart()).isEqualTo(-1);
        assertThat(vl.get(9).getSelectionEnd()).isEqualTo(-1);
    }

    @Test
    void cursorScrollsPAge() throws Exception {
        cursorManager.updateSize(5, 5);
        var vl = cursorManager.getVisibleLines();
        assertThat(vl.size()).isEqualTo(5);
        assertThat(vl.getFirst().getLogLine().line()).isEqualTo(12345);

        cursorManager.setMarkAtCursor();
        assertThat(cursorManager.getMark().get().line).isEqualTo(2);
        assertThat(cursorManager.getMark().get().col()).isEqualTo(0);

        cursorManager.moveCursor(3, 0, true);
        // at this point, the cursor is at line 2+3 = 5 ((linesPerPage-1)/2)+3
        // the page should scroll so that line 3 is the next to last visible line
        assertThat(cursorManager.getCursor().get().line).isEqualTo(5);
        assertThat(cursorManager.getCursor().get().col()).isEqualTo(0);

        vl = cursorManager.getVisibleLines();
        assertThat(vl.size()).isEqualTo(5);
        assertThat(vl.getFirst().getLogLine().line()).isEqualTo(12345);
    }

    @Test
    void setCursorFromClick() throws Exception {
        cursorManager.updateSize(10, 10);

        cursorManager.setCursorFromClick(5, 3, false);
        cursorManager.setCursorFromClick(6, 5, true);
        assertThat(cursorManager.getMark().get().line).isEqualTo(5);
        assertThat(cursorManager.getMark().get().col()).isEqualTo(3);
        assertThat(cursorManager.getCursor().get().line).isEqualTo(6);
        assertThat(cursorManager.getCursor().get().col()).isEqualTo(5);

        var vl = cursorManager.getVisibleLines();
        assertThat(vl.size()).isEqualTo(10);
        assertThat(vl.get(5).isHighlighted()).isFalse();
        assertThat(vl.get(6).isHighlighted()).isTrue();
        assertThat(vl.get(7).isHighlighted()).isFalse();

        assertThat(vl.get(4).getSelectionStart()).isEqualTo(-1);
        assertThat(vl.get(4).getSelectionEnd()).isEqualTo(-1);
        assertThat(vl.get(5).getSelectionStart()).isEqualTo(3);
        assertThat(vl.get(5).getSelectionEnd()).isEqualTo(Integer.MAX_VALUE);
        assertThat(vl.get(6).getSelectionStart()).isEqualTo(0);
        assertThat(vl.get(6).getSelectionEnd()).isEqualTo(5);
        assertThat(vl.get(7).getSelectionStart()).isEqualTo(-1);
        assertThat(vl.get(7).getSelectionEnd()).isEqualTo(-1);
        assertThat(vl.get(8).getSelectionStart()).isEqualTo(-1);
    }

    @Test
    void copyToClipboardReportsTheSelectedRowCount() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.setClipboard(new InMemoryClipboard());
        var copied = new ArrayList<String>();
        cursorManager.setCopiedNotifier(copied::add);

        cursorManager.setCursorFromClick(1, 0, false);
        cursorManager.setCursorFromClick(3, 2, true);
        cursorManager.copyToClipboard();

        assertThat(copied).containsExactly("Copied 3 rows");
    }

    @Test
    void setMarkAtCursor() {
        cursorManager.updateSize(10, 10);
        cursorManager.setMarkAtCursor();
        assertThat(cursorManager.getMark().isPresent()).isTrue();
    }

    @Test
    void hideCursorClearsCursorAndSelection() throws TemporarySearchException, SearchException, StorageException {

        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(3, 2, false);

        cursorManager.hideCursor();
        assertThat(cursorManager.getCursor().isPresent()).isFalse();
        assertThat(cursorManager.getMark().isPresent()).isFalse();
    }

    @Test
    void getVisibleLines() throws Exception {

        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer((invocation) -> {
            int lineNo = invocation.getArgument(0);
            return new LoadedLogLine(RAW, "segment1", lineNo, "cb1", "bkt", null, "raw1", null, null, "log1", false,
                    INFO);
        });
        cursorManager.updateSize(10, 10);
        var lines = cursorManager.getVisibleLines();
        assertThat(lines.size()).isEqualTo(10);
    }

    @Test
    void isLineSelected() throws TemporarySearchException, SearchException, StorageException {
        cursorManager.updateSize(10, 10);
        cursorManager.moveCursor(0, 1, true);
        cursorManager.moveCursor(0, 1, false);
        cursorManager.moveCursor(0, 4, true);
        var selectedLine = cursorManager.isLineSelected(4);
        Assertions.assertNotNull(selectedLine);
        Assertions.assertEquals(2, selectedLine.getLeft());
        Assertions.assertEquals(6, selectedLine.getRight());

        var selection2 = cursorManager.selection();
        Assertions.assertNotNull(selection2);
        System.out.println("selection2 = " + selection2);
    }

    @Test
    void totalMessage() throws TemporarySearchException, SearchException, StorageException {
        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(0, 0, false);

        var expected = DecoratedLogLine.format(12345, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), MSG_20);

        cursorManager.setCursorFromClick(0, expected.length(), true);

        var selectedLine = cursorManager.isLineSelected(0);
        Assertions.assertNotNull(selectedLine);
        Assertions.assertEquals(0, selectedLine.getLeft());
        Assertions.assertEquals(expected.length(), selectedLine.getRight());

        var selection = cursorManager.selection();
        System.out.println("selection = " + selection);
        Assertions.assertNotNull(selection);
        assertThat(selection.size()).isEqualTo(1);
        assertThat(selection.getFirst()).isEqualTo(expected);
        assertThat(selection.getFirst().length()).isEqualTo(expected.length());

        cursorManager.setCursorFromClick(0, 0, false);
        cursorManager.moveCursor(0, 1, true);

        for (int i = 1; i < expected.length(); i++) {
            var selectedLine2 = cursorManager.selection();
            System.out.println("r:" + selectedLine2.getFirst());
            Assertions.assertNotNull(selectedLine2);
            Assertions.assertEquals(expected.substring(0, i), selectedLine2.getFirst());
            cursorManager.moveCursor(0, 1, true);
        }
    }

    @Test
    void selectionCombinationsSingleLine() throws TemporarySearchException, SearchException, StorageException {

        cursorManager.updateSize(10, 10);

        String testString = DecoratedLogLine.format(12345, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP),
                MSG_20);

        // test all permutations of the string
        for (int i = 0; i < testString.length(); i++) {
            for (int j = i; j < testString.length(); j++) {
                String substring = testString.substring(i, j);
                System.out.printf("%2d %2d '%s'%n", i, j, substring);
                cursorManager.setCursorFromClick(0, i, false);
                cursorManager.setCursorFromClick(0, j, true);
                var selectedLine = cursorManager.selection().getFirst();

                assertThat(selectedLine).isEqualTo(substring);
            }
        }
    }

    @Test
    void selectionCombinationsTwoLine() throws TemporarySearchException, SearchException, StorageException {

        cursorManager.updateSize(10, 10);

        // test all permutations of the string
        int testLength = DecoratedLogLine.format(0, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), MSG_20)
                .length();

        for (int i = 0; i < testLength; i++) {
            for (int j = i; j < testLength; j++) {
                cursorManager.setCursorFromClick(0, i, false);
                cursorManager.setCursorFromClick(1, j, true);

                // first line should be from i to end of line
                String testString = DecoratedLogLine.format(12345,
                        DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), MSG_20);

                var fl = testString.substring(i, testLength);
                var sl = testString.substring(0, j);

                // Skip preamble if min cursor is > preamble
                if (i >= getPreambleLength()) {
                    fl = testString.substring(getPreambleLength() + (i - getPreambleLength()));
                    sl = sl.substring(getPreambleLength());
                }

                System.out.printf("fl: %2d -- '%s'%n", i, fl);
                var firstLine = cursorManager.selection().get(0);
                assertThat(firstLine).isEqualTo(fl);

                var secondLine = cursorManager.selection().get(1);
                System.out.printf("sl: -- %2d '%s'%n", j, sl);
                assertThat(secondLine).isEqualTo(sl);
            }
        }
    }

    @Test
    void selectionCombinationsThreeLine() throws TemporarySearchException, SearchException, StorageException {

        cursorManager.updateSize(10, 10);

        // test all permutations of the string
        int testLength = DecoratedLogLine.format(0, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), MSG_20)
                .length();

        for (int i = 0; i < testLength; i++) {
            for (int j = i; j < testLength; j++) {
                cursorManager.setCursorFromClick(0, i, false);
                cursorManager.setCursorFromClick(2, j, true);
                var firstLine = cursorManager.selection().getFirst();

                // first line should be from i to end of line
                String testString = DecoratedLogLine.format(12345,
                        DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), MSG_20);
                var fl = testString.substring(i, testLength);
                System.out.printf("fl: %2d -- '%s'%n", i, fl);
                assertThat(firstLine).isEqualTo(fl);
                // second line should be complete line
                var sl = testString;
                var tl = testString.substring(0, j);

                // Skip preamble if min cursor is > preamble
                if (i >= getPreambleLength()) {
                    // fl = testString.substring(getPreambleLength() + (i - getPreambleLength()));
                    sl = sl.substring(getPreambleLength());
                    tl = tl.substring(getPreambleLength());
                }

                var secondLine = cursorManager.selection().get(1);
                System.out.printf("sl: -- %2d '%s'%n", j, sl);
                assertThat(secondLine).isEqualTo(sl);
                // third line should be from start of line to j
                var thirdLine = cursorManager.selection().get(2);
                System.out.printf("tl: -- %2d '%s'%n", j, tl);
                assertThat(thirdLine).isEqualTo(tl);
            }
        }
    }

    // --- Double-click word selection and progressive expansion tests ---

    /**
     * Helper: configures the mock log provider to return a line with the given message text.
     */
    private void setLineMessage(String msg) throws TemporarySearchException, SearchException, StorageException {
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> {
            int lineNo = 12345;
            return new LoadedLogLine(JSON, "segment1", lineNo, "cb1", "bkt", TIMESTAMP, "{msg=\"" + msg + "\"}",
                    TIMESTAMP, TIMESTAMP, msg, false, INFO);
        });
    }

    /**
     * Helper: returns the currently selected text on visible line 0 (single-line selection).
     */
    private String selectedText() throws TemporarySearchException, SearchException, StorageException {
        var sel = cursorManager.selection();
        if (sel.isEmpty()) return "";
        return sel.getFirst();
    }

    /**
     * Helper: returns the column of the formatted line where the message starts.
     */
    private int msgOffset(String msg) {
        String formatted = DecoratedLogLine.format(12345, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), msg);
        return formatted.indexOf(msg);
    }

    /**
     * Simulates the real AWT double-click sequence:
     * 1. mousePressed (clickCount=1) calls setCursorFromClick which clears selection but saves prev
     * 2. mouseClicked (clickCount=2) calls handleDoubleClick which checks prevSelection
     */
    private void simulateDoubleClick(int visibleLine, int visibleCol) throws Exception {
        cursorManager.setCursorFromClick(visibleLine, visibleCol, false);
        cursorManager.handleDoubleClick(visibleLine, visibleCol, 2);
    }

    @Test
    void doubleClickSelectsWordWithColonAsBoundary() throws Exception {
        // "time=17:30" — clicking on "17" should select just "17", not "17:30"
        String msg = "time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        // Double-click on the "1" of "17"
        simulateDoubleClick(0, offset + 5);

        assertThat(selectedText()).isEqualTo("17");
    }

    @Test
    void doubleClickSelectsWordAfterColon() throws Exception {
        // Clicking on "30" in "17:30" should select just "30"
        String msg = "time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        simulateDoubleClick(0, offset + 8);

        assertThat(selectedText()).isEqualTo("30");
    }

    @Test
    void doubleClickOnDelimiterSelectsSameCharRun() throws Exception {
        // Clicking on ':' selects just ':'  (class 3 separator)
        String msg = "time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        simulateDoubleClick(0, offset + 7); // the ':' between 17 and 30
        assertThat(selectedText()).isEqualTo(":");
    }

    @Test
    void doubleClickOnSpacesSelectsAllConsecutiveSpaces() throws Exception {
        String msg = "hello   world";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        simulateDoubleClick(0, offset + 5); // first space
        assertThat(selectedText()).isEqualTo("   ");
    }

    @Test
    void doubleClickOnWordSelectsWord() throws Exception {
        String msg = "event=\"34\" and time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        // Double-click on "34"
        simulateDoubleClick(0, offset + 7);

        assertThat(selectedText()).isEqualTo("34");
    }

    @Test
    void doubleClickOnIPSelectsFullIP() throws Exception {
        // Dots between digits are decimal separators (class 1), so entire IP is one word
        String msg = "10.7.149.2 connected";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        simulateDoubleClick(0, offset + 3); // on '7'
        assertThat(selectedText()).isEqualTo("10.7.149.2");
    }

    @Test
    void selectRegionAtCursorSelectsTheWordUnderTheCursor() throws Exception {
        String msg = "event=\"34\" and time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        cursorManager.setCursorFromClick(0, offset + 7, false);
        cursorManager.selectRegionAtCursor();

        assertThat(selectedText()).isEqualTo("34");
    }

    @Test
    void selectRegionAtCursorWidensTheSelectionOnEachCall() throws Exception {
        String msg = "time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        cursorManager.setCursorFromClick(0, offset + 5, false);
        cursorManager.selectRegionAtCursor();
        assertThat(selectedText()).isEqualTo("17");

        cursorManager.selectRegionAtCursor();
        assertThat(selectedText()).isEqualTo("17:30");
    }

    @Test
    void expansionFromTimeField() throws Exception {
        // time=17:30 user=kalle
        // Classes:  t(1)i(1)m(1)e(1) =(3) 1(1)7(1) :(3) 3(1)0(1)  (4) u(1)s(1)e(1)r(1) =(3) k(1)...
        // From "17": boundaries =(3) and :(3), breach 3, right gain(3) < left gain(5) → right
        String msg = "time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        int clickCol = offset + 5; // on "1" of "17"
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).isEqualTo("17");

        // Breach class 3: right ":30" gain=3 < left "=time" gain=5 → expand right
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).isEqualTo("17:30");

        // Now left =(3), right ' '(4). Breach 3. Only left can expand through ≤3.
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).isEqualTo("time=17:30");

        // Left at msg start, right ' '(4). Breach 4, expand right through everything.
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).isEqualTo("time=17:30 user=kalle");
    }

    @Test
    void expansionWithNginxLogLine() throws Exception {
        // Nginx access log date: [08/Mar/2026:20:31:56 +0000]
        // Clicking on 2026:
        //   2026 → 08/Mar/2026 (breach class 2 left via /) → 08/Mar/2026:20:31:56 (breach class 3 right via :)
        //   → 08/Mar/2026:20:31:56 +0000 (breach class 4 right via space) → [08/Mar/2026:20:31:56 +0000] (matching
        // brackets)
        String msg = "[08/Mar/2026:20:31:56 +0000] done";
        setLineMessage(msg);
        cursorManager.updateSize(10, 120);
        int offset = msgOffset(msg);

        // "2026" starts at msg index 8
        int clickCol = offset + 8;
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 1: word").isEqualTo("2026");

        // Left /(2), right :(3). Breach 2. Left expands through ≤2 to [. Right: : is 3 > 2, no gain.
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 2: breach symbols").isEqualTo("08/Mar/2026");

        // Left [(5), right :(3). Breach 3. Left: [(5)>3, no gain. Right expands through all ≤3.
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 3: breach separators").isEqualTo("08/Mar/2026:20:31:56");

        // Left [(5), right ' '(4). Breach 4. Right expands through ≤4 to ].
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 4: breach space").isEqualTo("08/Mar/2026:20:31:56 +0000");

        // Left [(5), right ](5). Matching brackets!
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 5: matching brackets").isEqualTo("[08/Mar/2026:20:31:56 +0000]");
    }

    @Test
    void expansionFromGET() throws Exception {
        // "GET / HTTP/1.1"  — note 1.1 has decimal separator so it's one word
        String msg = "\"GET / HTTP/1.1\" 200";
        setLineMessage(msg);
        cursorManager.updateSize(10, 120);
        int offset = msgOffset(msg);

        // "GET" starts at msg index 1
        int clickCol = offset + 1;
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 1: word").isEqualTo("GET");

        // Left "(5), right ' '(4). Breach 4. Right expands through ≤4 to ".
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 2: breach space").isEqualTo("GET / HTTP/1.1");

        // Left "(5), right "(5). Matching quotes!
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).as("step 3: matching quotes").isEqualTo("\"GET / HTTP/1.1\"");
    }

    @Test
    void expansionFromUserEqualsKalle() throws Exception {
        // From "user": left ' '(4), right =(3). Breach min(3,4)=3.
        // Left: ' '(4)>3, no gain. Right: =(3) + kalle(1) → gain=6.
        String msg = "time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        int clickCol = offset + 11; // on "u" of "user"
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).isEqualTo("user");

        // Breach 3: only right can expand (left is space=4). Right: "=kalle"
        simulateDoubleClick(0, clickCol);
        assertThat(selectedText()).isEqualTo("user=kalle");
    }

    @Test
    void tripleClickSelectsWholeLine() throws Exception {
        String msg = "some log line";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);

        cursorManager.handleDoubleClick(0, 5, 3);
        var sel = selectedText();
        // Triple-click selects the entire formatted line
        String fullLine = DecoratedLogLine.format(12345, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), msg);
        assertThat(sel).isEqualTo(fullLine);
    }

    @Test
    void doubleClickOutsideSelectionDoesNewWordSelect() throws Exception {
        // If we have a selection but click outside it, it should do a fresh word select
        String msg = "hello world";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        // Select "hello"
        simulateDoubleClick(0, offset);
        assertThat(selectedText()).isEqualTo("hello");

        // Click on "world" — outside existing selection, should do fresh select
        simulateDoubleClick(0, offset + 6);
        assertThat(selectedText()).isEqualTo("world");
    }

    @Test
    void expansionIsIndependentOfDragDirection() throws Exception {
        // A right-to-left selection of "17" must expand exactly like a left-to-right one
        String msg = "time=17:30 user=kalle";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);
        int clickCol = offset + 5;

        cursorManager.setCursorFromClick(0, offset + 5, false);
        cursorManager.setCursorFromClick(0, offset + 7, true);
        var forward = new ArrayList<String>();
        forward.add(selectedText());
        for (int i = 0; i < 3; i++) {
            simulateDoubleClick(0, clickCol);
            forward.add(selectedText());
        }

        cursorManager.setCursorFromClick(0, offset + 7, false);
        cursorManager.setCursorFromClick(0, offset + 5, true);
        var reverse = new ArrayList<String>();
        reverse.add(selectedText());
        for (int i = 0; i < 3; i++) {
            simulateDoubleClick(0, clickCol);
            reverse.add(selectedText());
        }

        assertThat(forward).containsExactly("17", "17:30", "time=17:30", "time=17:30 user=kalle");
        assertThat(reverse).isEqualTo(forward);
    }

    @Test
    void pageUpWorksInViewportsTallerThan100Rows() throws Exception {
        // Page Up passes -linesPerPage, which must not throw in viewports taller than 100 rows
        cursorManager.updateSize(150, 80);

        cursorManager.moveCursor(-cursorManager.getLinesPerPage(), 0, false);
        assertThat(cursorManager.getPageStart()).isEqualTo(-150);

        cursorManager.setPageStart(0);
        cursorManager.moveCursor(-cursorManager.getLinesPerPage(), 0, true);
        assertThat(cursorManager.getMark().orElseThrow().line).isEqualTo(74);
        assertThat(cursorManager.getCursor().orElseThrow().line).isEqualTo(74 - 150);
    }

    @Test
    void providerChangeResetsRenderingAndSelectionOffsetsTogether() throws Exception {
        // After horizontal scrolling, replacing the provider resets the viewport's rendering offset
        // as well as the offset clicks are mapped with.
        var renderedOffset = new int[]{-1};
        var recordingViewPort = new AbstractLogViewPort() {
            @Override
            public void setHorizontalOffset(int leftOfPage) {
                renderedOffset[0] = leftOfPage;
            }
        };
        var cm = new CursorManager(logProvider, recordingViewPort);
        assertThat(renderedOffset[0]).isZero();
        cm.updateSize(10, 20);

        cm.scrollContent(0, 12);
        assertThat(renderedOffset[0]).isEqualTo(12);

        cm.setLogProvider(logProvider);
        assertThat(renderedOffset[0]).isZero();

        // Select visible columns 3..8: must be exactly what is rendered there
        cm.setCursorFromClick(0, 3, false);
        cm.setCursorFromClick(0, 8, true);
        String formatted = DecoratedLogLine.format(12345, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP),
                MSG_20);
        assertThat(cm.selection()).containsExactly(formatted.substring(renderedOffset[0] + 3, renderedOffset[0] + 8));
    }

    @Test
    void expansionStopsWhenFullLine() throws Exception {
        String msg = "abc";
        setLineMessage(msg);
        cursorManager.updateSize(10, 80);
        int offset = msgOffset(msg);

        simulateDoubleClick(0, offset);
        assertThat(selectedText()).isEqualTo("abc");

        // Expanding further goes left into the preamble (timestamp), eventually the whole line
        String fullLine = DecoratedLogLine.format(12345, DateTimeFormatterManager.formatLogTimestamp(TIMESTAMP), msg);
        // Keep expanding until the entire line is selected
        for (int i = 0; i < 20; i++) {
            simulateDoubleClick(0, offset);
        }
        assertThat(selectedText()).isEqualTo(fullLine);

        // Now truly stops — full line selected, no more to expand
        simulateDoubleClick(0, offset);
        assertThat(selectedText()).isEqualTo(fullLine);
    }

    // --- Selection span (time range shown in the status bar) ---

    private static Instant rowTime(int row) {
        return TIMESTAMP.plusSeconds(row);
    }

    private static LoadedLogLine timedRow(int lineNo, Instant displayTime) {
        return new LoadedLogLine(JSON, "segment1", lineNo, "cb1", "bkt", displayTime, "{msg=\"" + MSG_20 + "\"}",
                displayTime, displayTime, MSG_20, false, INFO);
    }

    private void rowsAre(IntFunction<LogLine> rows) throws TemporarySearchException, SearchException, StorageException {
        when(logProvider.getLine(anyInt(), anyBoolean())).thenAnswer(inv -> rows.apply(inv.getArgument(0)));
    }

    @Test
    void selectionSpanIsEmptyWithoutSelection() throws Exception {
        cursorManager.updateSize(10, 10);
        assertThat(cursorManager.selectionSpan()).isEmpty();
    }

    @Test
    void selectionSpanIsEmptyOnOneRow() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(2, 0, false);
        cursorManager.moveCursor(0, 3, true);
        assertThat(cursorManager.selectionSpan()).isEmpty();
    }

    @Test
    void selectionSpanRunsFromFirstToLastSelectedRow() throws Exception {
        rowsAre(row -> timedRow(row, rowTime(row)));
        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(2, 0, false);
        cursorManager.moveCursor(3, 0, true);

        assertThat(cursorManager.selectionSpan())
                .contains(new CursorManager.SelectionSpan(rowTime(2), rowTime(4)));
    }

    @Test
    void selectionSpanLeavesOutARowThatTheSelectionEndsAtColumnZeroOf() throws Exception {
        rowsAre(row -> timedRow(row, rowTime(row)));
        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(2, 0, false);
        cursorManager.moveCursor(2, 0, true);

        assertThat(cursorManager.selectionSpan())
                .contains(new CursorManager.SelectionSpan(rowTime(2), rowTime(3)));
    }

    @Test
    void selectionSpanSkipsRowsWithoutATimeFromTheEnds() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(2, 0, false);
        cursorManager.moveCursor(5, 0, true);
        rowsAre(row -> switch (row) {
            case 2, 6 -> Placeholder.from(row, "not a log line");
            case 3 -> timedRow(row, null);
            default -> timedRow(row, rowTime(row));
        });

        assertThat(cursorManager.selectionSpan())
                .contains(new CursorManager.SelectionSpan(rowTime(4), rowTime(5)));
    }

    @Test
    void selectionSpanIsEmptyWhenBothEndsFindTheSameRow() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(2, 0, false);
        cursorManager.moveCursor(5, 0, true);
        rowsAre(row -> row == 4 ? timedRow(row, rowTime(row)) : Placeholder.from(row, "no time"));

        assertThat(cursorManager.selectionSpan()).isEmpty();
    }

    @Test
    void selectionSpanLooksAtMostSixtyFourRowsFromEachEnd() throws Exception {
        cursorManager.updateSize(10, 10);
        cursorManager.setCursorFromClick(2, 0, false);
        cursorManager.moveCursor(199, 0, true); // rows 2..200
        rowsAre(row -> row == 65 || row == 137 ? timedRow(row, rowTime(row)) : Placeholder.from(row, "no time"));

        assertThat(cursorManager.selectionSpan())
                .contains(new CursorManager.SelectionSpan(rowTime(65), rowTime(137)));

        rowsAre(row -> row == 66 || row == 136 ? timedRow(row, rowTime(row)) : Placeholder.from(row, "no time"));

        assertThat(cursorManager.selectionSpan()).isEmpty();
    }
}
