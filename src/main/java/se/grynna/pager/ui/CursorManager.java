package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import raven.toast.Notifications;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.splunk.Boundary;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LogLine;
import se.grynna.pager.splunk.SearchHit;
import se.grynna.pager.ui.clip.ClipboardAccess;
import se.grynna.pager.ui.clip.Copier;
import se.grynna.pager.utils.DarculaColors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static se.grynna.pager.ui.Cursor.MAXLINE_LENGTH;
import static se.grynna.pager.ui.DecoratedLogLine.getPreambleLength;

/**
 * CursorManager handles the cursor and mark positions in the log viewer.
 * <p>
 * The main mode is scrolling content up and down, like a Unix pager (less/more) ie the full
 * content is scrolled on input and mouse wheel scrolls/swipes. (up down, left right) in
 * scroll-lock mode.
 * <p>
 * A secondary mode scroll-lock is enabled when text has been selected, with editor-like cursor movement
 * with an vi/emacs-like virtual cursor that remembers the last horizontal position on shorter lines if
 * continuing on longer lines above/below.
 * <p>
 * Text can be selected by
 * - click and drag
 * - shift + keyboard navigation (arrows, page up/down, ctrl-a, ctrl-e, home, end, ctrl + left/right/page up/down)
 * - ctrl-space (set mark emacs-style) which also enables perma-select mode.
 * <p>
 * Whenever text is selected scroll-lock is disabled.
 * <p>
 * The cursor is switched on and off with the cursor-mode toggle (F7, {@link #toggleCursor()}).
 * Escape (and ctrl-g) only clear a selection ({@link #clearSelection()}); they never hide the cursor.
 * <p>
 * Coordinates - UP is negative, DOWN is positive
 */
@Slf4j
public class CursorManager {

    private static final int LIMIT_TOP_LINE = Integer.MIN_VALUE;
    private static final int LIMIT_BOTTOM_LINE = Integer.MAX_VALUE;

    // Nice Ratio for positioning target line in upper ;portion of screen
    private static final double NICE_PLACEMENT_RATIO = 0.46;
    // Keep lines visible when scrolling near boundaries
    private static final double NICE_SCROLL_RATIO = 0.45;
    // How far from each end of a selection the selection span looks for a row with a time
    private static final int SELECTION_SPAN_SCAN_ROWS = 64;

    public InputState getState() {

        boolean init = logProvider instanceof Mood;

        if (init) {
            return scrollLock ? InputState.INIT : InputState.INIT_CURSOR;
        } else {
            return scrollLock ? InputState.SCROLL : InputState.CURSOR;
        }
    }

    public enum PagePosition {
        TOP, BOTTOM
    }

    /** The display times of the first and last selected row, in selection order. */
    public record SelectionSpan(Instant first, Instant last) {}

    /**
     * Immutable snapshot of the state needed to restore the screen
     * pixel-identically after a provider swap: the active provider, page start,
     * cursor and mark (line + column), horizontal offset, scroll lock and the
     * navigation highlight. See {@link #captureViewState()} and
     * {@link #swapProvider(LogDataProvider, ViewState)}.
     * <p>
     * {@code scrollLock} is captured rather than the derived {@link InputState}:
     * the cursor input state (SCROLL/CURSOR and their INIT variants) is computed by
     * {@link #getState()} from {@code scrollLock} and the provider type, so
     * {@code scrollLock} is the source of truth and restores {@code getState()}
     * exactly when the provider type is unchanged.
     * <p>
     * The selection (cursor + mark) is captured. The transient selection-gesture
     * and double-click memory ({@code selectingInProgress}, {@code prevMark},
     * {@code prevCursor}) is deliberately not: it only matters mid-gesture.
     */
    public record ViewState(
            LogDataProvider provider,
            int pageStart,
            Integer cursorLine,
            Integer cursorCol,
            Integer markLine,
            Integer markCol,
            int horizontalOffset,
            boolean scrollLock,
            Integer navHighlightLine) {}

    private final Object cursorLock = new Object();
    private se.grynna.pager.ui.LogDataProvider logProvider;
    private final LogViewPort viewPort;

    private ClipboardAccess clipboard = ClipboardAccess.system();
    private static final String COPIED_TO_CLIPBOARD = "Copied to clipboard";
    private Consumer<String> copiedNotifier = CursorManager::showCopiedToast;

    private int pageStart = 0;
    private int leftOfPage = 0;

    private int linesPerPage = 0;
    private int charsPerLine = 0;

    public void updateSize(int linesPerPage, int charsPerLine) {
        this.linesPerPage = linesPerPage;
        this.charsPerLine = charsPerLine;
    }

    // Cursor is hidden by default (empty), spawned when needed
    private Cursor cursor = null;
    private Cursor mark = null;

    // Remembers the previous single-line selection so that handleDoubleClick can
    // detect a double-click inside a recently cleared selection and expand it.
    // Needed because mousePressed (clickCount=1) clears mark before mouseClicked fires.
    private Cursor prevMark = null;
    private Cursor prevCursor = null;

    private boolean scrollLock = true; // True: scroll mode, False: cursor mode

    // Where the cursor was when cursor mode was last switched off, so switching it on returns there
    private Integer lastCursorLine = null;
    private int lastCursorCol = 0;

    // Temporary navigation highlight - shown after goto/jump, cleared on scroll/cursor move
    private Integer navHighlightLine = null;

    // True while user is actively extending a selection (mouse drag or shift+key).
    // Selection-echo highlights are suppressed until the gesture finishes.
    private boolean selectingInProgress = false;

    public CursorManager(LogDataProvider logProvider, LogViewPort viewPort) {
        this.viewPort = viewPort;
        setLogProvider(logProvider);
    }

    public void setLogProvider(LogDataProvider logProvider) {
        synchronized (cursorLock) {
            pageStart = 0;
            leftOfPage = 0;
            cursor = null;
            mark = null;
            lastCursorLine = null;
            scrollLock = true;
            navHighlightLine = null;
            this.logProvider = logProvider;
            // Keep the viewport's rendering offset in sync with leftOfPage, which
            // click/copy coordinates are computed from.
            viewPort.setHorizontalOffset(leftOfPage);
        }
    }

    /**
     * Captures the current view state: active provider, page start, cursor and
     * mark (line + column), horizontal offset, scroll lock and navigation
     * highlight — everything needed to restore the screen pixel-identically after
     * a provider swap.
     */
    public ViewState captureViewState() {
        synchronized (cursorLock) {
            return new ViewState(logProvider, pageStart, cursor == null ? null : cursor.line,
                    cursor == null ? null : cursor.col(), mark == null ? null : mark.line,
                    mark == null ? null : mark.col(), leftOfPage, scrollLock, navHighlightLine);
        }
    }

    /**
     * Switches to {@code provider}. With a non-null {@code restore}, the page
     * start, cursor/mark, horizontal offset, scroll lock and navigation highlight
     * are restored from it (clamped into the new provider's valid row range).
     * With {@code restore == null} this behaves exactly
     * like {@link #setLogProvider(LogDataProvider)} (full reset).
     */
    public void swapProvider(LogDataProvider provider, ViewState restore) {
        if (restore == null) {
            setLogProvider(provider);
            return;
        }
        synchronized (cursorLock) {
            this.logProvider = provider;
            pageStart = clampPageStart(restore.pageStart());
            cursor = restoreCursor(restore.cursorLine(), restore.cursorCol());
            mark = restoreCursor(restore.markLine(), restore.markCol());
            leftOfPage = clampHorizontalOffset(restore.horizontalOffset());
            scrollLock = restore.scrollLock();
            navHighlightLine = restore.navHighlightLine();
            prevMark = null;
            prevCursor = null;
            lastCursorLine = null;
            selectingInProgress = false;
            viewPort.setHorizontalOffset(leftOfPage);
        }
        viewPort.updateVisibleLines();
    }

    Optional<Cursor> getCursor() {
        if (cursor == null) {
            return Optional.empty();
        }
        return Optional.of(cursor);
    }

    Optional<Cursor> getMark() {
        if (mark == null) {
            return Optional.empty();
        }
        return Optional.of(mark);
    }

    /** The cursor's line number, or empty when no cursor is present. */
    public Optional<Integer> cursorLine() {
        return cursor == null ? Optional.empty() : Optional.of(cursor.line);
    }

    /**
     * Scrolls content in scroll mode (no cursor visible).
     */
    public void scrollContent(int deltaLines, int deltaCols)
            throws TemporarySearchException, SearchException, StorageException {
        log.trace("scrollContent (CL) {} {}", deltaLines, deltaCols);

        // Clear temporary navigation highlight on any scroll action
        navHighlightLine = null;

        if (deltaLines != 0) {
            synchronized (cursorLock) {
                if (deltaLines < 0 && LIMIT_TOP_LINE - deltaLines >= pageStart) return;
                if (deltaLines > 0 && LIMIT_BOTTOM_LINE - deltaLines <= pageStart) return;
                pageStart += snapToBoundary(deltaLines);
            }
            log.trace("scroll toplineonpage {} to {}", deltaLines, pageStart);
        }
        int lop = -1;
        synchronized (cursorLock) {
            if (deltaCols != 0) {
                leftOfPage += deltaCols;
                if (leftOfPage < 0) {
                    leftOfPage = 0;
                }
                if (leftOfPage > MAXLINE_LENGTH) {
                    leftOfPage = MAXLINE_LENGTH;
                }
                lop = leftOfPage;
            }
        }
        if (lop >= 0) {
            viewPort.setHorizontalOffset(lop);
        }

        log.trace("scrolling content toplineonpage {}", lop);
        viewPort.updateVisibleLines();
    }

    /**
     * Scrolls so {@code row} is in the middle of the page, as far as the boundaries allow. A relative
     * {@link #scrollContent} (what the mouse wheel does), so the cursor, the mark and the mode stay as they are,
     * the navigation highlight is cleared and {@code snapToBoundary} applies. Does nothing when the page is already
     * there.
     */
    public void scrollCentreTo(long row) throws TemporarySearchException, SearchException, StorageException {
        long delta;
        synchronized (cursorLock) {
            delta = row - linesPerPage / 2 - pageStart;
        }
        if (delta != 0) {
            scrollContent((int) Math.clamp(delta, LIMIT_TOP_LINE + 1, LIMIT_BOTTOM_LINE), 0);
        }
    }

    /**
     * Moves cursor, optionally extending selection, with edge-based scrolling.
     */
    public void moveCursor(int deltaLines, int deltaCols, boolean extendSelection)
            throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {

            // Clear temporary navigation highlight on any cursor movement
            navHighlightLine = null;

            if (scrollLock && !extendSelection) {
                scrollContent(deltaLines, deltaCols); // In scroll mode, non-shift moves scroll content
                return;
            }

            log.trace("** Move cursor: delta ({},{}), extendSelection={}", deltaLines, deltaCols, extendSelection);

            // Spawn cursor if not present (e.g., first Shift + Arrow)
            spawnCursorIfNotPresent();

            // Handle selection
            if (extendSelection && mark == null) {
                mark = new Cursor(cursor);

                setScrollLock(false); // Enter cursor mode
                log.debug("Setting mark at {}", mark);
            } else if (!extendSelection && mark != null) {
                mark = null;
                log.debug("clearing mark, clear selection");
            }
            if (extendSelection) {
                selectingInProgress = true;
            }

            // Move cursor: clamp the row first, so the column is measured on the
            // row the cursor actually ends up on (not a boundary row past the edge).
            cursor.deltaLine(deltaLines);
            if (logProvider instanceof MatchesDataProvider) {
                if (deltaLines > 0 && cursor.line > bottomLine()) logProvider.unfreezeAfterBoundary();
                if (deltaLines < 0 && cursor.line < topLine()) logProvider.unfreezeBeforeBoundary();
            }
            cursor.clampLine(bottomLine(), topLine());
            int maxCol = maxCol();
            cursor.deltaCol(deltaCols);

            cursor.clampCol(maxCol);

            // scroll (move) topline if cursor is outside the viewport
            scrollToCursor();
        }
        if (!extendSelection && scrollLock) {
            setScrollLock(true);
        }

        log.trace("Cursor at {}", cursor);
        viewPort.updateVisibleLines();
    }

    private long bottomLine() {
        return Long.min(boundaryLine(logProvider.getAfterBoundary(), LIMIT_BOTTOM_LINE),
                boundaryLine(logProvider.getNowBoundary(), LIMIT_BOTTOM_LINE)) - 1;
    }

    private long topLine() {
        return boundaryLine(logProvider.getBeforeBoundary(), LIMIT_TOP_LINE);
    }

    /** A missing boundary (none exists yet) or one without a line imposes no limit: {@code unbounded}. */
    private static long boundaryLine(Boundary boundary, int unbounded) {
        return boundary == null ? unbounded : boundary.line().orElse(unbounded);
    }

    /** Clamps a horizontal offset into the renderable range {@code [0, MAXLINE_LENGTH]}. */
    private int clampHorizontalOffset(int offset) {
        return Math.clamp(offset, 0, MAXLINE_LENGTH);
    }

    /**
     * Clamps a page start into the provider's valid row range so the page (or as
     * much of it as exists) shows valid rows: {@code [topLine, bottomLine -
     * linesPerPage + 1]}, pinned to the top when there are fewer valid rows than a
     * page.
     */
    private int clampPageStart(int pageStart) {
        long top = topLine();
        long bottom = bottomLine();
        long maxPageStart = bottom - linesPerPage + 1;
        if (maxPageStart < top) {
            maxPageStart = top;
        }
        return (int) Math.clamp(pageStart, top, maxPageStart);
    }

    /**
     * Rebuilds a cursor from captured (line, column), clamping the line into the
     * provider's valid row range first and only then the column onto the line it
     * actually lands on.
     */
    private Cursor restoreCursor(Integer line, Integer col) {
        if (line == null) {
            return null;
        }
        Cursor restored = new Cursor(line, col == null ? 0 : col);
        restored.clampLine(bottomLine(), topLine());
        try {
            restored.clampCol(maxColFor(restored.line));
        } catch (TemporarySearchException | SearchException | StorageException e) {
            // Column width unavailable; the column stays within its hard bound.
        }
        return restored;
    }

    public void setPageStart(int pageStart) throws TemporarySearchException, SearchException, StorageException {
        this.pageStart = pageStart;
    }

    public int getPageStart() {
        return pageStart;
    }

    /** Programmatic streaming update: preserve independent anchors without scrolling to the cursor. */
    void reanchor(Reanchor.Placement placement) {
        synchronized (cursorLock) {
            pageStart = placement.pageStart();
            cursor = restoreCursor(placement.cursor(), cursor == null ? 0 : cursor.col());
            mark = restoreCursor(placement.mark(), mark == null ? 0 : mark.col());
            navHighlightLine = placement.highlight();
        }
    }

    /**
     * The viewport row (0 = top) a line would be drawn at for the current page
     * start. The result may lie outside {@code [0, linesPerPage)} — callers decide
     * whether that means the line is off-screen.
     */
    public int screenRowOf(int line) {
        return line - pageStart;
    }

    /**
     * Navigate to the log entry closest to the given timestamp.
     * Uses the log data provider's jump() method to find the line.
     * The target line is centered at the golden ratio position (upper half of screen)
     * and temporarily highlighted until the next scroll or cursor movement.
     *
     * @param timestamp The target timestamp
     */
    public void gotoTimestamp(Instant timestamp) {
        try {
            log.info("Navigating to timestamp: {}", timestamp);
            int line = logProvider.jump(timestamp);

            gotoLine(line);

            viewPort.updateVisibleLines();
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.error("Failed to navigate to timestamp: {}", timestamp, e);
        }
    }

    public void gotoSearchHit(SearchHit hit) {
        try {
            log.info("Navigating to search hit: {}", hit);
            int line = logProvider.jump(hit);
            gotoLine(line);
            viewPort.updateVisibleLines();
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.error("Failed to navigate to search hit: {}", hit, e);
        }
    }

    /** Replace the log's browsing range and navigate to its first event. */
    public boolean gotoRange(Instant from, Instant to) {
        try {
            int line = logProvider.jump(from, from, to);
            gotoLine(line);
            viewPort.updateVisibleLines();
            return true;
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.error("Failed to navigate to range: {} – {}", from, to, e);
            return false;
        }
    }

    /**
     * Ensures a cursor exists at {@code line} and switches to cursor mode. The
     * line is clamped into the provider's valid rows and scrolled into view.
     * <p>
     * The Matches view always has a cursor; this
     * seeds it on entry and moves it to the next/previous event for F3/Shift-F3.
     */
    public void ensureCursorAt(int line) {
        synchronized (cursorLock) {
            cursor = new Cursor(line, cursor != null ? cursor.col() : 0);
            cursor.clampLine(bottomLine(), topLine());
            setScrollLock(false);
        }
        try {
            scrollToCursor();
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.warn("ensureCursorAt: failed to scroll cursor into view", e);
        }
        viewPort.updateVisibleLines();
    }

    /**
     * The rows the selection touches, in order; empty without a selection. The same rows
     * {@link #isLineSelected} reports, so a row whose only selected part is its line break is left out.
     */
    List<Integer> selectedRows() {
        synchronized (cursorLock) {
            return selectedRowRange().map(rows -> IntStream.rangeClosed(rows.first(), rows.last()).boxed().toList())
                    .orElse(List.of());
        }
    }

    /** First and last selected row, both inclusive. */
    private record RowRange(int first, int last) {}

    /** The rows of the selection (see {@link #selectedRows()}), without building a list; empty without a selection. */
    private Optional<RowRange> selectedRowRange() {
        if (cursor == null || mark == null || cursor.compareTo(mark) == 0) {
            return Optional.empty();
        }
        Cursor start = cursor.compareTo(mark) < 0 ? cursor : mark;
        Cursor end = start == cursor ? mark : cursor;
        int last = start.line != end.line && end.col() == 0 ? end.line - 1 : end.line;
        return Optional.of(new RowRange(start.line, last));
    }

    /**
     * The time range of the selection: from the first selected row with a display time to the last one. Empty when
     * the selection covers fewer than two rows, or when either end finds no timed row within
     * {@value #SELECTION_SPAN_SCAN_ROWS} rows, or both find the same row.
     */
    public Optional<SelectionSpan> selectionSpan()
            throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {
            Optional<RowRange> rows = selectedRowRange().filter(range -> range.last() > range.first());
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            RowRange range = rows.get();
            Optional<TimedRow> first = firstTimedRow(range.first(), range.last(), 1);
            Optional<TimedRow> last = firstTimedRow(range.last(), range.first(), -1);
            if (first.isEmpty() || last.isEmpty() || first.get().row() == last.get().row()) {
                return Optional.empty();
            }
            return Optional.of(new SelectionSpan(first.get().time(), last.get().time()));
        }
    }

    /** Scans from {@code from} towards {@code to} (inclusive, direction given by {@code step}) for a timed row. */
    private Optional<TimedRow> firstTimedRow(int from, int to, int step)
            throws TemporarySearchException, SearchException, StorageException {
        for (int i = 0; i < SELECTION_SPAN_SCAN_ROWS; i++) {
            int row = from + i * step;
            if (step > 0 ? row > to : row < to) {
                break;
            }
            if (getLogProviderLine(row) instanceof LoadedLogLine loaded && loaded.getDisplayTime() != null) {
                return Optional.of(new TimedRow(row, loaded.getDisplayTime()));
            }
        }
        return Optional.empty();
    }

    private record TimedRow(int row, Instant time) {}

    public boolean hasNavigationHighlight() {
        synchronized (cursorLock) {
            return navHighlightLine != null;
        }
    }

    /** Drop the temporary navigation highlight, keeping the page where it is. */
    public void clearNavigationHighlight() {
        synchronized (cursorLock) {
            if (navHighlightLine == null) {
                return;
            }
            navHighlightLine = null;
        }
        viewPort.updateVisibleLines();
    }

    /**
     * Navigate to a specific line number with golden ratio centering.
     * The target line is positioned at the golden ratio in the upper half of the screen
     * and temporarily highlighted until the next scroll or cursor movement.
     *
     * @param line The target line number
     */
    public void gotoLine(int line) {

        // Center line at golden ratio position in the upper half of the page
        var center = (int) (linesPerPage * NICE_PLACEMENT_RATIO);
        var offsetFromCenter = (int) (line - center);
        log.info("Navigating to line with highlight: {}, goldenOffset {}, pageStart {}, linesPerPage {}", line, center,
                offsetFromCenter, linesPerPage);

        gotoLineInternal(line, offsetFromCenter);
    }

    /**
     * Navigate to a specific line number, placing it at the given viewport row
     * (0 = top) instead of the golden-ratio row. The screen row is clamped into
     * {@code [0, linesPerPage-1]} and the resulting page start is clamped into the
     * provider's valid row range, so a row that would fall before the first or
     * after the last valid row is placed at the nearest reachable position.
     *
     * @param line      the target line number
     * @param screenRow the viewport row to place it on (0 = top)
     */
    public void gotoLine(int line, int screenRow) {
        int clampedScreenRow = Math.clamp(screenRow, 0, maxScreenRow());
        gotoLineInternal(line, clampPageStart(line - clampedScreenRow));
    }

    private void gotoLineInternal(int line, int pageStart) {
        synchronized (cursorLock) {
            this.pageStart = pageStart;

            // Set temporary navigation highlight on the target line
            navHighlightLine = line;

            // Clear cursor/mark and reset to scroll mode (mirrors gotoLine(int))
            cursor = null;
            mark = null;
            scrollLock = true;
        }
        viewPort.updateVisibleLines();
    }

    private int maxScreenRow() {
        return Math.max(0, linesPerPage - 1);
    }

    private void scrollToCursor() throws TemporarySearchException, SearchException, StorageException {
        var minCursorLine = lineFromVisible(1);
        var maxCursorLine = (pageStart + linesPerPage) - 2;

        int scrollDelta = 0;
        if (cursor.line < minCursorLine) {
            scrollDelta = cursor.line - minCursorLine;
        } else if (cursor.line > maxCursorLine) {
            scrollDelta = cursor.line - maxCursorLine;
        }

        log.trace("""
                scollCursorIntoView
                   linesPerPage {}
                   topLineOnPage {}
                   cursor.line {}
                   minCursorLine {}
                   maxCursorLine {}
                   scrollDelta {}""", linesPerPage, pageStart, cursor.line, minCursorLine, maxCursorLine, scrollDelta);

        if (scrollDelta != 0) {
            pageStart += snapToBoundary(scrollDelta);

            log.trace("cursor moved toplineonpage by {} to {}", scrollDelta, pageStart);
            viewPort.updateVisibleLines();
        }

        // scroll (move) leftOfPage if cursor is outside the viewport
        int rightLimit = leftOfPage + charsPerLine - 3;
        int leftLimit = leftOfPage + 3;
        int distanceToLeft = cursor.col() - leftLimit;
        int distanceToRight = rightLimit - cursor.col();
        log.trace("""
                moveCursor
                   leftOfPage {}
                   cursor.col {}
                   cursorOnPage {}
                   leftLimit {}
                   distanceToLeft {}
                   rightLimit {}
                    distanceToRight {}""", leftOfPage, cursor.col(), "-", leftLimit, distanceToLeft, rightLimit,
                distanceToRight);
        if (distanceToLeft < 0) {
            leftOfPage += distanceToLeft;
            if (leftOfPage < 0) leftOfPage = 0;
            viewPort.setHorizontalOffset(leftOfPage);
            log.trace("leftOfPage moving {} to {}", distanceToLeft, leftOfPage);
        } else if (distanceToRight < 0) {
            leftOfPage -= distanceToRight;
            viewPort.setHorizontalOffset(leftOfPage);
            log.trace("leftOfPage moving {} to {}", distanceToRight, leftOfPage);
        }
    }

    /**
     * Sets cursor from mouse click, clears selection, enters scroll mode.
     */
    public void setCursorFromClick(int visibleLine, int visibleCol, boolean dragging)
            throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {

            //            if (cursor == null) {
            //                cursor = new Cursor(topLineOnPage + visibleLine, leftOfPage + col);
            //            }
            int col = colFromVisible(visibleCol);

            if (dragging) {
                selectingInProgress = true;
                if (mark == null) {
                    mark = new Cursor(cursor);
                }
                cursor = new Cursor(lineFromVisible(visibleLine), col);
            } else {
                selectingInProgress = false;
                // Save selection before clearing — handleDoubleClick may need it for expansion
                prevMark = mark;
                prevCursor = (mark != null) ? cursor : null;
                cursor = new Cursor(lineFromVisible(visibleLine), col);
                setScrollLock(false);
                mark = null; // Clear selection
            }
            cursor.clampLine(bottomLine(), topLine());
            cursor.clampCol(maxCol());
        }
        viewPort.updateVisibleLines();
    }

    /**
     * Called on mouseReleased or shift key-released to signal end of a selection gesture.
     * Clears the selecting-in-progress flag so selection-echo highlights appear.
     */
    public void selectionDone() {
        synchronized (cursorLock) {
            if (!selectingInProgress) {
                return;
            }
            selectingInProgress = false;
        }
        viewPort.updateVisibleLines();
    }

    private int lineFromVisible(int visibleLine) {
        int line = pageStart + visibleLine;
        return line;
    }

    private int colFromVisible(int visibleCol) {
        int col = leftOfPage + visibleCol;
        return col;
    }

    /**
     * True when a click at {@code visibleCol} lands in the preamble (icon, timestamp,
     * level) of the line rather than in its message text.
     */
    public boolean isPreambleClick(int visibleCol) {
        synchronized (cursorLock) {
            return isPreambleColumn(colFromVisible(visibleCol), getPreambleLength());
        }
    }

    /** Pure decision behind {@link #isPreambleClick(int)}: columns before the message text. */
    static boolean isPreambleColumn(int col, int preambleLength) {
        return col >= 0 && col < preambleLength;
    }

    /**
     * Sets mark at current cursor position (Ctrl-Space).
     */
    public void setMarkAtCursor() {
        synchronized (cursorLock) {
            spawnCursorIfNotPresent();
            mark = new Cursor(cursor);
            setScrollLock(false); // Enter cursor mode
        }
        viewPort.updateVisibleLines();
    }

    private void spawnCursorIfNotPresent() {
        if (cursor == null) {
            cursor = new Cursor(lineFromVisible(indexAtHalf(linesPerPage)), 0);
        }
    }

    /** True while cursor mode is on (the cursor is shown); false in page mode. */
    public boolean isCursorMode() {
        synchronized (cursorLock) {
            return !scrollLock;
        }
    }

    /** Cursor mode on → off (page mode), page mode → on (the cursor appears). */
    public void toggleCursor() {
        if (isCursorMode()) {
            hideCursor();
        } else {
            showCursor();
        }
    }

    /**
     * Switches cursor mode on. The cursor returns to where it was when it was last hidden if that
     * row is still on the page, else it appears on the top visible row; the page does not move.
     */
    public void showCursor() {
        synchronized (cursorLock) {
            if (!scrollLock) {
                return;
            }
            int topRow = lineFromVisible(1);
            int bottomRow = pageStart + linesPerPage - 2;
            boolean restore = lastCursorLine != null && lastCursorLine >= topRow && lastCursorLine <= bottomRow;
            cursor = new Cursor(restore ? lastCursorLine : topRow, restore ? lastCursorCol : 0);
            cursor.clampLine(bottomLine(), topLine());
            try {
                cursor.clampCol(maxCol());
            } catch (TemporarySearchException | SearchException | StorageException e) {
                // Column width unavailable; the column stays within its hard bound.
            }
            mark = null;
            setScrollLock(false);
        }
        viewPort.updateVisibleLines();
    }

    /**
     * Switches cursor mode off: the cursor and any selection go, and the arrow keys scroll again.
     *
     * @return true if the cursor was shown
     */
    public boolean hideCursor() {
        synchronized (cursorLock) {
            if (this.scrollLock) {
                return false;
            }

            if (cursor != null) {
                lastCursorLine = cursor.line;
                lastCursorCol = cursor.col();
            }
            cursor = null;
            mark = null;
            setScrollLock(true);
            viewPort.updateVisibleLines();
            return true;
        }
    }

    /**
     * Clears the selection or mark and keeps the cursor where it is.
     *
     * @return true if there was a selection (or mark) to clear
     */
    public boolean clearSelection() {
        synchronized (cursorLock) {
            if (mark == null) {
                return false;
            }
            mark = null;
            selectingInProgress = false;
        }
        viewPort.updateVisibleLines();
        return true;
    }

    int maxCol() throws TemporarySearchException, SearchException, StorageException {

        return maxColFor(cursor.line);
    }

    private int maxColFor(int line) throws TemporarySearchException, SearchException, StorageException {
        return decorate(getLogProviderLine(line), line).formattedLine().stripTrailing().length();
    }

    private LogLine getLogProviderLine(int line) throws TemporarySearchException, SearchException, StorageException {
        // edge == top or bottom line on the page
        boolean edge = (line == pageStart) || (line == pageStart + linesPerPage - 1);
        return logProvider.getLine(line, edge);
    }

    public List<se.grynna.pager.ui.DecoratedLogLine> getVisibleLines()
            throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {
            int endLine = min(lineFromVisible(linesPerPage), LIMIT_BOTTOM_LINE);
            List<se.grynna.pager.ui.DecoratedLogLine> lines = new ArrayList<>(linesPerPage);

            // Build the list of active text highlights (selection echo, search, etc.)
            var highlights = activeHighlights();

            for (int lineNo = pageStart; lineNo < endLine; lineNo++) {
                LogLine line = getLogProviderLine(lineNo);
                lines.add(decorate(line, lineNo, highlights));
            }

            if (endLine > pageStart) {
                logProvider.preload(pageStart, endLine - 1);
            }

            return lines;
        }
    }

    /**
     * Reduce scroll to keep max out-of-boundary (oob) lines visible, in the direction of scrolling.
     *
     * @param scrollDelta the requested scroll delta (positive scrolls towards later
     *                    lines, negative towards earlier ones)
     * @return the reduced scroll delta that keeps the maximum out-of-boundary lines visible
     * @throws TemporarySearchException if a recoverable search error occurs
     * @throws SearchException          if a non-recoverable search error occurs
     * @throws StorageException         if reading or writing cached rows fails
     */
    private int snapToBoundary(int scrollDelta) throws TemporarySearchException, SearchException, StorageException {

        // Keep at least some lines visible in the page.
        // when scrolling UP, allow scrolling until maxBoundary/topLine are oob below pageStart
        // when scrolling DOWN, allow scrolling until maxBoundary/bottomLine are oob above pageStart + linesPerPage
        // Additional scrolling will unfreeeze the boundary

        if (scrollDelta > 0) {

            long bottomLine = bottomLine();
            long pageLimit = (long) (linesPerPage * NICE_SCROLL_RATIO);
            long oobLines = ((long) pageStart) + scrollDelta + pageLimit - bottomLine;
            if (oobLines > 0) {
                // pageStart/scrollDelta are ints, but the sum can exceed the int range;
                // keep the calculation in long and clamp before narrowing back.
                long snappedDelta = scrollDelta - oobLines;
                log.debug("Snapping scroll DOWN from {} to {}", scrollDelta, snappedDelta, oobLines);
                scrollDelta = Math.clamp(snappedDelta, LIMIT_TOP_LINE, LIMIT_BOTTOM_LINE);
                logProvider.unfreezeAfterBoundary();
            } else {
                log.debug("Scroll DOWN pageStart {}, bottomLine {}, oobLines {} within limit {}", pageStart, bottomLine,
                        oobLines, pageLimit);
            }
        } else if (scrollDelta < 0) {
            long topLine = topLine();
            long pageLimit = (long) (linesPerPage * (1.0 - NICE_SCROLL_RATIO));
            long oobLines = ((long) topLine) - (pageStart + pageLimit) - scrollDelta;
            if (oobLines > 0) {
                // Likewise for the UP direction: widen before applying the delta.
                long snappedDelta = (long) scrollDelta + oobLines;
                log.debug("Snapping scroll UP from {} to {}, oobLines {}, withing limit {}", scrollDelta, snappedDelta,
                        oobLines, pageLimit);
                scrollDelta = Math.clamp(snappedDelta, LIMIT_TOP_LINE, LIMIT_BOTTOM_LINE);
                logProvider.unfreezeBeforeBoundary();
            } else {
                log.debug("Scroll UP pageStart {}, topLine {}, oobLines {}", pageStart, topLine, oobLines);
            }
        }
        return scrollDelta;
    }

    Pair<Integer, Integer> isLineSelected(int lineNumber) {

        if (mark == null) {
            return null;
        }
        if (cursor == null) {
            return null;
        }

        var cmp = cursor.compareTo(mark);

        Cursor min, max;
        if (cmp == 0) {
            // log.debug("Cursor and mark are equal");
            return null;
        } else if (cmp < 0) {
            min = cursor;
            max = mark;
        } else {
            min = mark;
            max = cursor;
        }

        if (lineNumber < min.line || lineNumber > max.line) {
            // log.debug("Line {} is not selected: minLine={}, maxLine={}", lineNumber, min.line, max.line);
            return null;
        }

        int preambleLength = getPreambleLength();
        int selStart = min.col() < preambleLength ? 0 : preambleLength;

        // Calculate if the line is fully selected or partially selected.
        // 0 indicates that the beginning of the line is selected
        // maxint indicates that the end of the line is selected-

        if (min.line == max.line) {
            // Single line selected

            if (min.col() == max.col()) {
                // No selection
                return null;
            }

            return Pair.of(min.col(), max.col());
        } else if (lineNumber == min.line) {
            // First line
            return Pair.of(min.col(), LIMIT_BOTTOM_LINE);
        } else if (lineNumber == max.line) {
            // Last line
            if (max.col() == 0) {
                // No selection
                return null;
            }
            return Pair.of(selStart, max.col());
        } else {
            // Middle lines are fully selected (from selStart to max.col())
            return Pair.of(selStart, LIMIT_BOTTOM_LINE);
        }
    }

    private se.grynna.pager.ui.DecoratedLogLine decorate(LogLine logLine, int line) {
        return decorate(logLine, line, List.of());
    }

    private se.grynna.pager.ui.DecoratedLogLine decorate(LogLine logLine, int line, List<TextHighlight> highlights) {
        se.grynna.pager.ui.DecoratedLogLine dl = new se.grynna.pager.ui.DecoratedLogLine(logLine);
        if (cursor != null && cursor.line == line) {
            dl.setHighlighted(true);
            dl.setCursorPosition(cursor.col());
        }
        // Apply temporary navigation highlight (from goto/jump operations)
        if (navHighlightLine != null && navHighlightLine == line) {
            dl.setHighlighted(true);
        }
        var selected = isLineSelected(line);
        if (selected != null) {
            dl.setSelectionStart(selected.getLeft());
            dl.setSelectionEnd(selected.getRight());
        } else {
            dl.setSelectionStart(-1);
            dl.setSelectionEnd(-1);
        }
        dl.setHighlights(highlights);
        dl.setStyleSpans(logProvider.styleSpans(line));
        return dl;
    }

    /**
     * Builds the active highlight patterns: selection echo for single-line selections
     * and (future) search term highlights.
     */
    private List<TextHighlight> activeHighlights() throws TemporarySearchException, SearchException, StorageException {

        List<TextHighlight> highlights = new ArrayList<>();

        // Selection echo — only for completed single-line selections with non-blank text.
        // Suppressed while the user is actively extending (mouse drag or shift+key).
        if (!selectingInProgress && cursor != null && mark != null && cursor.line == mark.line
                && cursor.col() != mark.col()) {
            int selLine = cursor.line;
            LogLine logLine = getLogProviderLine(selLine);
            if (logLine instanceof LoadedLogLine) {
                var dl = new DecoratedLogLine(logLine);
                dl.setSelectionStart(Math.min(cursor.col(), mark.col()));
                dl.setSelectionEnd(Math.max(cursor.col(), mark.col()));
                String selectedText = dl.selected();
                if (selectedText != null && !selectedText.isBlank()) {
                    highlights.add(new TextHighlight(selectedText, DarculaColors.SELECTION_ECHO, null));
                }
            }
        }

        // Search term and narrowed scope terms — case-insensitive, because Splunk search is too.
        viewPort.getHighlightTerms().stream().filter(term -> !term.isBlank()).forEach(
                term -> highlights.add(new TextHighlight(term, DarculaColors.MATCH_HIGHLIGHT,
                        DarculaColors.MATCH_HIGHLIGHT_TEXT, false)));

        return highlights;
    }

    static int indexAtHalf(int size) {
        return (size - 1) / 2;
    }

    private void setScrollLock(boolean scrollLock) {
        this.scrollLock = scrollLock;
        viewPort.onScrollLockChanged(scrollLock);
    }

    public LoadedLogLine getLogLineAt(int cursorLine)
            throws TemporarySearchException, SearchException, StorageException {

        var line = getLogProviderLine(cursorLine);
        return switch (line) {
            case LoadedLogLine ll -> ll;
            default -> null;
        };
    }

    /** The loaded line under the cursor; empty without a cursor, on a line that is not loaded, or when loading fails. */
    public Optional<LoadedLogLine> cursorLogLine() {
        Optional<Cursor> at = getCursor();
        if (at.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(getLogLineAt(at.get().line));
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.warn("cursorLogLine: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Return a list of pairs - date + msg for the selection.
     *
     */
    public List<String> selection() throws TemporarySearchException, SearchException, StorageException {

        List<String> selection = new ArrayList<>();
        if (mark == null || cursor == null) {
            return selection;
        }

        int startLine = min(cursor.line, mark.line);
        int endLine = max(cursor.line, mark.line);

        for (int line = startLine; line <= endLine; line++) {
            LogLine tline = getLogProviderLine(line);
            if (tline instanceof LoadedLogLine logLine) {

                var decoratedLine = decorate(logLine, line);
                var selected = decoratedLine.selected();
                selection.add(selected);
            }
        }
        return selection;
    }

    public void copyToClipboard() throws TemporarySearchException, SearchException, StorageException {
        log.debug("Copying to clipboard lines {} - {}", cursor, mark);

        var selection = selection();
        if (selection.isEmpty()) {
            return;
        }
        clipboard.setContents(Copier.transferable(selection));
        copiedNotifier.accept(rowsMessage(selection.size()));
    }

    /** Copy {@code lines} (one per row) to the clipboard, as Copy does for a selection. */
    void copyText(List<String> lines) {
        clipboard.setContents(Copier.transferable(lines));
        copiedNotifier.accept(rowsMessage(lines.size()));
    }

    /** Put prepared clipboard contents (a copy-as format) on the clipboard. */
    void copyContents(java.awt.datatransfer.Transferable contents) {
        clipboard.setContents(contents);
        copiedNotifier.accept(COPIED_TO_CLIPBOARD);
    }

    private static String rowsMessage(int rows) {
        return rows == 1 ? "Copied 1 row" : "Copied " + rows + " rows";
    }

    private static void showCopiedToast(String message) {
        Notifications.getInstance().show(Notifications.Type.SUCCESS, Notifications.Location.TOP_CENTER, message);
    }

    /**
     * The selected text when the selection sits on one row (drag or shift-arrows within a line), else
     * empty: no selection, or one spanning several rows.
     */
    public Optional<String> singleRowSelectionText()
            throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {
            if (mark == null || cursor == null || cursor.line != mark.line || cursor.col() == mark.col()) {
                return Optional.empty();
            }
            var selection = selection();
            return selection.size() == 1 ? Optional.ofNullable(selection.getFirst()) : Optional.empty();
        }
    }

    /** Where Copy puts the selection; the system clipboard unless a test replaces it. */
    void setClipboard(ClipboardAccess clipboard) {
        this.clipboard = clipboard;
    }

    /** Receives the confirmation text after a copy; the toast unless a test replaces it. */
    void setCopiedNotifier(Consumer<String> notifier) {
        this.copiedNotifier = notifier;
    }

    // Character class hierarchy for word selection and expansion.
    // Lower class = tighter grouping. Expansion breaches one class level at a time.
    //   1: Word chars — letters, digits, decimal separators (. or , between two digits)
    //   2: Printable symbols unlikely to be separators — / * # € $ & ! ? ^ \ | ~ + % @ _ -
    //   3: Separators — : ; , . (when not decimal separators)
    //   4: Space
    //   5: Brackets and quotes — ( ) { } [ ] " '
    private static final String BRACKET_CHARS = "(){}[]\"'";
    private static final String SEPARATOR_CHARS = ":;,.";
    private static final String SYMBOL_CHARS = "/*#€$&!?^\\|~+%@_-";

    /**
     * Returns the character class (1-5) for a character at the given position in the line.
     * Decimal separators (. or , between two digits) are treated as class 1 (word).
     */
    private int charClass(String line, int pos) {
        char c = line.charAt(pos);
        if (Character.isLetterOrDigit(c)) return 1;
        if (c == ' ') return 4;
        if (BRACKET_CHARS.indexOf(c) >= 0) return 5;
        // Check if . or , is a decimal separator (between two digits)
        if ((c == '.' || c == ',') && isDecimalSeparator(line, pos)) return 1;
        if (SEPARATOR_CHARS.indexOf(c) >= 0) return 3;
        if (SYMBOL_CHARS.indexOf(c) >= 0) return 2;
        // Any other char defaults to separator
        return 3;
    }

    /**
     * Returns true if the char at pos is a . or , sandwiched between two digits.
     */
    private boolean isDecimalSeparator(String line, int pos) {
        return pos > 0 && pos < line.length() - 1 && Character.isDigit(line.charAt(pos - 1))
                && Character.isDigit(line.charAt(pos + 1));
    }

    /**
     * Returns true if left and right chars form a matching pair:
     * same-char quotes ("", '') or bracket pairs ([], (), {}).
     */
    private boolean isMatchingPair(char left, char right) {
        if (left == right) return true; // quotes: "" ''
        return (left == '[' && right == ']') || (left == '(' && right == ')') || (left == '{' && right == '}');
    }

    /**
     * Selects the initial word/token under the cursor.
     * Selects a run of chars that all share the same class as the clicked char.
     * For class 1 (word), also includes adjacent decimal separators.
     */
    private Pair<Integer, Integer> findWordBounds(String line, int col) {
        int len = line.length();
        if (col < 0 || col >= len) return null;

        int clickedClass = charClass(line, col);
        int start = col, end = col;

        // Expand left through same-class chars
        while (start > 0 && charClass(line, start - 1) == clickedClass) {
            start--;
        }
        // Expand right through same-class chars
        while (end < len && charClass(line, end) == clickedClass) {
            end++;
        }
        return Pair.of(start, end);
    }

    public void handleDoubleClick(int visibleLine, int visibleCol, int clickCount)
            throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {
            // Convert screen coordinates to absolute document coordinates.
            // mark/cursor/prevMark/prevCursor all use absolute coordinates (document-level),
            // which are also direct indices into the formattedLine() string.
            int line = lineFromVisible(visibleLine);
            int col = colFromVisible(visibleCol);

            if (clickCount == 2) {
                // mousePressed (clickCount=1) already cleared mark/cursor via setCursorFromClick,
                // but we saved the previous selection in prevMark/prevCursor.
                boolean widen = wasInsidePreviousSelection(line, col);
                if (widen) {
                    mark = prevMark;
                    cursor = prevCursor;
                }
                selectRegion(line, col, widen);
            } else if (clickCount == 3) {
                // Select whole line
                cursor = new Cursor(line, lineText(line).length());
                mark = new Cursor(line, 0);
            }
            endSelectionGesture();
        }
        viewPort.updateVisibleLines();
    }

    /**
     * Selects the region under the cursor, as a double-click does at the pointer. A one-line selection
     * on the cursor's line is widened one class level instead, so repeated calls grow the selection.
     */
    public void selectRegionAtCursor() throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {
            spawnCursorIfNotPresent();
            int line = cursor.line;
            selectRegion(line, cursor.col(), mark != null && mark.line == line);
            endSelectionGesture();
        }
        viewPort.updateVisibleLines();
    }

    /**
     * The shared step of a double-click and a double-tap: selects the run of same-class characters around
     * {@code col}, or, with {@code widen}, grows the current selection one class level. Off the text the
     * selection stays as it was.
     */
    private void selectRegion(int line, int col, boolean widen)
            throws TemporarySearchException, SearchException, StorageException {
        String lineText = lineText(line);
        if (widen) {
            expandSelection(lineText);
            return;
        }
        Pair<Integer, Integer> bounds = findWordBounds(lineText, col);
        if (bounds != null) {
            mark = new Cursor(line, bounds.getLeft());
            cursor = new Cursor(line, bounds.getRight());
        }
    }

    private String lineText(int line) throws TemporarySearchException, SearchException, StorageException {
        return decorate(getLogProviderLine(line), line).formattedLine();
    }

    /** Ends a multi-click or multi-tap selection: forgets the pre-click selection and enters cursor mode. */
    private void endSelectionGesture() {
        prevMark = null;
        prevCursor = null;
        selectingInProgress = false;
        setScrollLock(false);
    }

    /**
     * Checks whether the given absolute position was inside the previous selection
     * (saved before mousePressed cleared it).
     */
    private boolean wasInsidePreviousSelection(int line, int col) {
        if (prevMark == null || prevCursor == null) return false;
        if (prevMark.line != prevCursor.line) return false;
        if (prevMark.line != line) return false;
        int selLeft = Math.min(prevMark.col(), prevCursor.col());
        int selRight = Math.max(prevMark.col(), prevCursor.col());
        return col >= selLeft && col < selRight;
    }

    /**
     * Expands the current selection outward by one class level.
     *
     * Computes what each side would look like after breaching its boundary class,
     * then picks the side with the smaller gain for gradual zoom-out.
     * Special case: matching bracket/quote chars (class 5) on both sides are
     * consumed together as a pair.
     *
     * Character class hierarchy (lower = tighter grouping):
     *   1: letters, digits, decimal separators
     *   2: printable symbols (/ * # = etc)
     *   3: separators (: ; , .)
     *   4: space
     *   5: brackets/quotes — consumed as matching pairs
     */
    private void expandSelection(String lineText) {
        int left = Math.min(mark.col(), cursor.col());
        int right = Math.max(mark.col(), cursor.col());
        int len = lineText.length();

        if (left == 0 && right >= len) return; // already full line

        boolean canExpandLeft = left > 0;
        boolean canExpandRight = right < len;

        if (!canExpandLeft && !canExpandRight) return;

        // Special case: matching bracket/quote pairs on both sides
        if (canExpandLeft && canExpandRight) {
            char leftBoundary = lineText.charAt(left - 1);
            char rightBoundary = lineText.charAt(right);
            if (charClass(lineText, left - 1) == 5 && charClass(lineText, right) == 5
                    && isMatchingPair(leftBoundary, rightBoundary)) {
                int newLeft = left - 1;
                while (newLeft > 0 && lineText.charAt(newLeft - 1) == leftBoundary)
                    newLeft--;
                int newRight = right + 1;
                while (newRight < len && lineText.charAt(newRight) == rightBoundary)
                    newRight++;
                setSelectionLeft(newLeft);
                setSelectionRight(newRight);
                return;
            }
        }

        // Find the boundary class on each side and compute potential expansion
        int leftClass = canExpandLeft ? charClass(lineText, left - 1) : Integer.MAX_VALUE;
        int rightClass = canExpandRight ? charClass(lineText, right) : Integer.MAX_VALUE;
        int breachClass = Math.min(leftClass, rightClass);

        // Compute how far each side would expand through chars with class <= breachClass
        int newLeft = left;
        while (newLeft > 0 && charClass(lineText, newLeft - 1) <= breachClass) {
            newLeft--;
        }
        int newRight = right;
        while (newRight < len && charClass(lineText, newRight) <= breachClass) {
            newRight++;
        }

        int leftGain = left - newLeft;
        int rightGain = newRight - right;

        if (leftGain == 0 && rightGain == 0) return;

        // Expand only one side — the one with the smaller gain for gradual zoom-out
        if (leftGain > 0 && (rightGain == 0 || leftGain <= rightGain)) {
            setSelectionLeft(newLeft);
        } else {
            setSelectionRight(newRight);
        }
    }

    // mark and cursor are on the same line during expansion; either may be the
    // left endpoint depending on drag direction, so move whichever one is.
    private void setSelectionLeft(int col) {
        if (mark.col() <= cursor.col()) {
            mark = new Cursor(mark.line, col);
        } else {
            cursor = new Cursor(cursor.line, col);
        }
    }

    private void setSelectionRight(int col) {
        if (mark.col() <= cursor.col()) {
            cursor = new Cursor(cursor.line, col);
        } else {
            mark = new Cursor(mark.line, col);
        }
    }

    public void setHorizontalOffset(int offset) {
        synchronized (cursorLock) {
            leftOfPage = offset;
            viewPort.setHorizontalOffset(offset);
        }
        viewPort.updateVisibleLines();
    }

    public int getLinesPerPage() {
        return linesPerPage;
    }

    /**
     * Scrolls to position a specified line at the given position in the viewport.
     * Similar to scrollCursorIntoView but for positioning any line.
     *
     * @param lineIndex the visible line index (0-based from top of viewport)
     * @param position  TOP or BOTTOM position in the viewport
     */
    public void scrollLineIntoView(int lineIndex, PagePosition position)
            throws TemporarySearchException, SearchException, StorageException {
        synchronized (cursorLock) {
            int scrollAmount = 0;

            switch (position) {
                case TOP -> {
                    if (lineIndex > 0) {
                        scrollAmount = -lineIndex;
                        log.trace("Scrolling line at index {} to top (scroll amount: {})", lineIndex, scrollAmount);
                    }
                }
                case BOTTOM -> {
                    int targetLineIndex = linesPerPage - 1;
                    if (lineIndex < targetLineIndex) {
                        scrollAmount = targetLineIndex - lineIndex;
                        log.trace("Scrolling line at index {} to bottom (scroll amount: {})", lineIndex, scrollAmount);
                    }
                }
            }

            if (scrollAmount != 0) {
                scrollContent(scrollAmount, 0);
            }
        }
    }

    public static void main(String[] args) {

        foo(Integer.MAX_VALUE, Integer.MIN_VALUE);
        foo(100, 0);
        foo(0, 100);
        foo(-50, -25);
    }

    static void foo(int max, int min) {
        long contentLines = (long) max - (long) min;
        System.out.printf("max %11d min %11d contentLines %11d%n", max, min, contentLines);
    }
}
