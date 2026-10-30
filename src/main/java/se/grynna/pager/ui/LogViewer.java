package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import raven.toast.Notifications;
import se.grynna.pager.db.DatabaseManager;
import se.grynna.pager.db.ScopeTerms;
import se.grynna.pager.exceptions.ExceptionMessageFormatter;
import se.grynna.pager.exceptions.SearchException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.exceptions.TemporarySearchException;
import se.grynna.pager.export.ExportController;
import se.grynna.pager.export.ExportEvent;
import se.grynna.pager.export.ExportFormat;
import se.grynna.pager.export.ExportNotices;
import se.grynna.pager.export.ExportOptions;
import se.grynna.pager.splunk.*;
import se.grynna.pager.splunk.SearchSession.State;
import se.grynna.pager.splunk.cert.CertificateChallenge;
import se.grynna.pager.splunk.cert.TrustDecision;
import se.grynna.pager.ui.clip.ExportTransferable;
import se.grynna.pager.ui.details.DetailsPanel;
import se.grynna.pager.ui.details.EventDetails;
import se.grynna.pager.ui.login.BookmarkScopeGuard;
import se.grynna.pager.ui.login.IndexPanel;
import se.grynna.pager.ui.login.UserLoginPanel;
import se.grynna.pager.ui.minimap.Minimap;
import se.grynna.pager.ui.minimap.MinimapController;
import se.grynna.pager.ui.minimap.MinimapModel;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;
import se.grynna.pager.ui.preferences.PreferencesPanel;
import se.grynna.pager.ui.sprattel.LoadingAnimationTimer;
import se.grynna.pager.ui.time.TimePanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.runAsync;
import static javax.swing.SwingUtilities.invokeLater;
import static javax.swing.SwingUtilities.isEventDispatchThread;
import static se.grynna.pager.utils.DarculaColors.BACKGROUND;

/**
 * LogViewer is the main UI component that manages the log viewing experience,
 * login flow, and coordination with SearchManager.
 *
 * <h2>Architecture: Direct Login + ReloginProvider</h2>
 * <ul>
 *   <li>Initial login: User enters credentials, SearchManager is created with them</li>
 *   <li>Session recovery: SplunkClient calls ReloginProvider when session is lost</li>
 * </ul>
 *
 * <h2>Login Flow</h2>
 * <pre>
 * Initial Login:
 *   User presses 'L' → actionLogin()
 *   → showLoginOverlay(INITIAL_LOGIN)
 *   → User enters credentials
 *   → new SearchManager(credentials, reloginProvider, ...)
 *   → handleLoginSuccess()
 *   → showIndexPanel()
 *   → User selects index
 *   → handleSessionStart()
 *   → Shows log view
 *
 * Session Recovery (SplunkClient detects error):
 *   SplunkClient → reloginProvider.requestPassword(errorMsg, callback)
 *   → handleRelogin(errorMsg, callback)
 *   → showReloginOverlay() - fields locked except password
 *   → User enters password
 *   → callback.accept(password)
 *   → SplunkClient reconnects and retries operation
 * </pre>
 *
 * <h2>Relogin Overlay UI</h2>
 * When session recovery is needed:
 * <ul>
 *   <li>Semi-transparent dark overlay (80% opacity) shows over current view</li>
 *   <li>Login panel with locked host/port/username fields</li>
 *   <li>Only password is editable</li>
 *   <li>Error message displayed explaining why re-login needed</li>
 *   <li>On success: overlay removed, operation retries automatically</li>
 *   <li>On cancel: overlay removed, operation fails</li>
 * </ul>
 *
 * @see SearchManager
 * @see ReloginProvider
 * @see UserLoginPanel
 */
@Slf4j
public class LogViewer extends JPanel
        implements LogViewPort, ScrolLockListener, LogDataProvider.BoundaryExpansionListener {

    private final Object logLock;

    /** True when the welcome screen (Mood) is shown — search and goto are disabled */
    private boolean isInMoodMode() {
        return logProvider instanceof Mood;
    }

    public static final int VPAD = 0;
    public static final int HPAD = 10;
    private final Clock clock;
    private final Mood mood;
    private final JLayeredPane layeredPane; // Layered pane for proper z-ordering of overlays
    private final JPanel viewPanel;
    private final InputHandler inputHandler;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    /** Periodic disk-usage status update; scheduled in addNotify, cancelled in removeNotify. EDT-confined. */
    private ScheduledFuture<?> diskUsageUpdate;

    private final FloatingScrollBar scrollBar;
    /** Match-density strip over the right edge of the log, fed by {@link #updateMinimap()}; it scrolls the log. */
    private final Minimap minimap = new Minimap();
    /** The inline event details, laid over the log area while {@link #detailsOpen}; the view underneath stays. */
    private final DetailsPanel detailsPanel = new DetailsPanel();
    private boolean detailsOpen;
    /** The event-level line the details show; null while they are closed. */
    private LoadedLogLine detailsEvent;
    /** The row the shown event starts on in the view underneath. */
    private int detailsRow;
    /** The label last given to the panel (where the event is), to see when a list update moved it. */
    private String detailsLabel = "";
    /** A previous/next step waiting for rows to load: true for next. Retried on every visible-lines update. */
    private Boolean pendingDetailsStep;
    /** The details to reopen once the log has reloaded at an event (after a message-fields change), or null. */
    private PendingDetails pendingDetailsReopen;
    private final MinimapController minimapController;
    private LineRenderer renderer;
    private final CursorManager cursorManager;
    private LogDataProvider logProvider;

    /** An event of the log to show the details of as soon as its row is loaded, with the field to select. */
    private record PendingDetails(int row, SearchHit hit, String field) {}

    /** What the details show: the event, the row it starts on in the view underneath, and where it is in that view. */
    private record DetailsTarget(LoadedLogLine event, int row, String label) {}

    /** Which lens is shown: the full log, the search's matching events, or the bookmarked events. */
    enum ViewMode {
        LOG, MATCHES, BOOKMARKS
    }

    /**
     * Lets one end-of-search notice through per {@link SearchSession}. The session
     * notifies once per update, and updates queued on the EDT all read the same
     * final state, so a finished search would otherwise toast several times.
     */
    static final class SearchNoticeGate {
        private SearchSession noticed;

        /** True the first time {@code session} is offered; false for every later offer. */
        boolean first(SearchSession session) {
            if (session == null || session == noticed) {
                return false;
            }
            noticed = session;
            return true;
        }
    }

    private ViewMode viewMode = ViewMode.LOG;
    /** The log viewport held before entering the Matches view, for Esc-restore. */
    private CursorManager.ViewState savedLogState;
    /** The last term the user submitted in the search prompt, active or not. */
    private String lastSubmittedTerm;
    /** The session's bookmarks: cleared on logout; a scope change keeps them unless they fall outside it (after confirmation). */
    private final Bookmarks bookmarks = new Bookmarks();
    /** Writes Copy-as and Export files off the EDT; the outcome comes back as a notice or a failure toast. */
    private ExportController exports = new ExportController(ExportController.newBackgroundExecutor(),
            SwingUtilities::invokeLater, text -> notice(StatusBar.Severity.INFO, text), LogViewer::failureToast);
    /** Whether the "<key> shows bookmarks" hint has been appended to a notice in this session. */
    private boolean bookmarkHintShown;
    /** What Y does for the open status-bar scope question (bookmarks outside the new scope); null when none. */
    private Runnable pendingScopeChange;
    /** Asks what to do about unsaved bookmarks on quit and logout; replaced in tests. */
    private UnsavedBookmarksGuard.Chooser unsavedChooser = UnsavedBookmarksGuard.dialog(() -> this);
    private final UnsavedBookmarksGuard unsavedGuard = new UnsavedBookmarksGuard(bookmarks::unsavedCount,
            (purpose, count) -> unsavedChooser.choose(purpose, count), new UnsavedBookmarksGuard.Actions() {
                @Override
                public void copyBookmarks() {
                    LogViewer.this.copyBookmarks();
                }

                @Override
                public void exportBookmarks(Runnable afterSuccessfulWrite) {
                    openExportDialog(ExportSources.Source.BOOKMARKS, afterSuccessfulWrite);
                }
            });
    private Reanchor.View pendingMatchesAnchor;
    /** The in-memory provider backing the Matches view; null when not in MATCHES. */
    private MatchesDataProvider matchesProvider;
    /** The in-memory provider backing the Bookmarks view; null unless in BOOKMARKS. */
    private BookmarksDataProvider bookmarksProvider;
    /** The view the Bookmarks view was opened from (LOG or MATCHES); where Esc goes back to. */
    private ViewMode bookmarksOrigin = ViewMode.LOG;
    /** That view's viewport, restored exactly by Esc. For MATCHES the matches provider stays in {@link #matchesProvider}. */
    private CursorManager.ViewState bookmarksOriginState;
    /** Where the cursor goes when the next bookmark removal refreshes the view; null leaves it to {@link Reanchor}. */
    private Reanchor.Anchor bookmarksCursorTarget;

    // private int linesPerPage;
    private int horizontalOffset = 0;
    private List<DecoratedLogLine> visibleLines;

    // UI Components
    private final Font font = Fontalizer.getFont();

    CharacterSize charSize = null;

    private final LoadingAnimationTimer spinnerAnimation;
    private final StatusBar statusBar;

    private SearchManager searchManager;
    private UserLoginPanel userLoginPanel;
    private IndexPanel indexPanel;
    private JPanel loginOverlay; // Semi-transparent overlay for login panel
    private JPanel preferencesOverlay; // Semi-transparent overlay for preferences panel
    private JPanel helpOverlay; // Semi-transparent overlay for the keyboard help
    private TimePanel timePanel; // The time drawer over the bottom of the log, null when closed
    private final Config config = new Config();

    // Tracks pending logout so new login waits for DB files to be released
    private volatile CompletableFuture<Void> logoutFuture;

    private final AtomicReference<BoundaryRequest> currentBoundaryPrompt = new AtomicReference<>();

    // Search navigation anchor: advance from the selected hit rather than viewport top.
    private final SearchNavigation searchNavigation = new SearchNavigation();
    private final SearchUpdateBinding searchUpdates = new SearchUpdateBinding(SwingUtilities::invokeLater);
    /** Provisional matches of a refining search (instant refinement); empty unless one is being answered. */
    private final ProvisionalMatches provisional = new ProvisionalMatches();
    /** Keeps a finished search's "no matches" / "failed" toast to one per search. */
    private final SearchNoticeGate endNotices = new SearchNoticeGate();
    /** The last visible-lines failure toasted; cleared by the next successful update. */
    private String lastVisibleLinesFailure;
    private SearchHit firstVisibleSearchHit;

    // Automatic first-match jump after a search submit. Armed with the origin and
    // the viewport anchor at submit time; dropped when the jump happens or the
    // user takes over (F3, scrolling, cancelling or replacing the search).
    private SearchSession pendingFirstMatchSession;
    private Instant pendingFirstMatchOrigin;
    private SearchHit pendingFirstMatchAnchor;
    /** How many times this search has already widened later / earlier. Drives the next suggestion. */
    private int laterRangeExpansions;

    private int earlierRangeExpansions;
    /** The zero-match window already offered for widening, so repeated updates of it do not ask again. */
    private SearchSession noMatchesOfferedSession;
    private Instant noMatchesOfferedStart;
    private Instant noMatchesOfferedEnd;
    /**
     * Start of the stretch the search may extend itself without asking: the search's own start, then each
     * answered question ({@link se.grynna.pager.splunk.ExpansionAutoApprove}, as for the log's boundaries).
     */
    private Instant searchExtendClockStart;

    // Flag to center on login instruction when Mood is first displayed with valid dimensions
    private boolean pendingMoodCentering = true;

    public LogViewer(Clock clock) {

        this.clock = clock;
        this.logLock = new Object();
        this.mood = new Mood(clock);
        this.logProvider = mood;

        // Initialize DateTimeFormatterManager FIRST before creating any UI components
        DateTimeFormatterManager.initialize(config);
        KeyMap.setPreferredFlavour(KeyFlavour.fromName(config.getKeyHints()));

        // Register listener for formatter changes after UI components are created
        DateTimeFormatterManager.getInstance().addChangeListener(this::updateVisibleLines);

        // Create SearchManager (initialized later after login)

        this.visibleLines = new ArrayList<>();
        setLayout(new BorderLayout());

        // Create layered pane for proper z-ordering of overlays
        layeredPane = new JLayeredPane() {
            @Override
            public Dimension getPreferredSize() {
                // Propagate viewPanel's preferred size so pack() works correctly
                return viewPanel != null ? viewPanel.getPreferredSize() : new Dimension(1024, 768);
            }
        };
        layeredPane.setLayout(null); // Use absolute positioning within layered pane

        // Main view panel
        viewPanel = new ViewPanel();
        viewPanel.setBackground(BACKGROUND);

        // Add viewPanel to the default layer of the layered pane
        layeredPane.add(viewPanel, JLayeredPane.DEFAULT_LAYER);
        layeredPane.add(minimap, Integer.valueOf(JLayeredPane.DEFAULT_LAYER + 10));
        minimapController = new MinimapController(minimap, MinimapController.newBackgroundExecutor(),
                SwingUtilities::invokeLater, config::getShowMinimap, DateTimeFormatterManager::zoneId,
                bookmarks::events, bookmarks::version);

        // Add the layered pane to the center (overlay will be added on top when needed)
        add(layeredPane, BorderLayout.CENTER);

        // Spinner animation
        spinnerAnimation = new LoadingAnimationTimer(viewPanel, "");
        renderer = new LineRenderer(spinnerAnimation, getFontMetrics(font), viewPanel.getBounds());

        // Combined status bar and input bar
        statusBar = new StatusBar(font, this, () -> System.nanoTime() / 1_000_000, clock);

        // Add the status bar to the bottom
        add(statusBar, BorderLayout.SOUTH);
        statusBar.setTimeGroupListener(
                half -> toggleTimePanel(half == TimeGroup.Half.AT ? TimePanel.Focus.AT : TimePanel.Focus.FROM));
        cursorManager = new CursorManager(logProvider, this);
        // Scroll bar
        scrollBar = new FloatingScrollBar(cursorManager);
        scrollBar.setFontMetrics(getFontMetrics(font));
        scrollBar.setRenderArea(viewPanel.getBounds());
        scrollBar.installListener(viewPanel);

        inputHandler = new InputHandler(cursorManager, this);
        inputHandler.setup(viewPanel, this);
        minimap.setNavigator(this::centerOnMinimapPosition);
        // The strip takes the mouse, so the wheel over it goes to the log's own wheel handler.
        minimap.addMouseWheelListener(e -> viewPanel.dispatchEvent(SwingUtilities.convertMouseEvent(minimap, e,
                viewPanel)));

        viewPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                renderer = new LineRenderer(spinnerAnimation, viewPanel.getFontMetrics(font), viewPanel.getBounds());
                scrollBar.setRenderArea(viewPanel.getBounds());
                updateLinesPerPage();
            }
        });

        // Resize children when layeredPane is resized (null layout requires manual sizing)
        layeredPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                Dimension size = layeredPane.getSize();
                viewPanel.setBounds(0, 0, size.width, size.height);
                if (indexPanel != null) {
                    indexPanel.setBounds(0, 0, size.width, size.height);
                }
                if (loginOverlay != null) {
                    loginOverlay.setBounds(0, 0, size.width, size.height);
                }
                if (preferencesOverlay != null) {
                    preferencesOverlay.setBounds(0, 0, size.width, size.height);
                }
                if (helpOverlay != null) {
                    helpOverlay.setBounds(0, 0, size.width, size.height);
                }
                layoutTimePanel();
                detailsPanel.setBounds(0, 0, size.width, size.height);
                layoutMinimap();
                layeredPane.revalidate();
            }
        });

        detailsPanel.setFieldMenuHandler((field, point) -> showDetailsFieldMenu(point));

        bookmarks.addListener(() -> statusBar.setBookmarkCount(bookmarks.size()));
        bookmarks.addListener(this::onBookmarksChanged);
        bookmarks.addListener(this::updateMinimap);

        setFocusable(true);
        requestFocusInWindow();

        revalidate();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // Schedule base directory size update every 30 seconds while displayable
        if (diskUsageUpdate == null) {
            diskUsageUpdate = executor.scheduleAtFixedRate(new DiskUsageStatus(DatabaseManager::getBaseDirSizeFormatted,
                    statusBar::setBaseDirSize, SwingUtilities::invokeLater), 0, 30,
                    java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Override
    public void removeNotify() {
        // addNotify/removeNotify can repeat (e.g. frame re-realization), so only
        // cancel the task here; addNotify reschedules it on the same executor.
        if (diskUsageUpdate != null) {
            diskUsageUpdate.cancel(false);
            diskUsageUpdate = null;
        }
        super.removeNotify();
    }

    /**
     * Centers the view on the "Press L to login" instruction line when showing the Mood provider.
     * Uses golden ratio positioning and temporary highlight.
     */
    private void centerOnMoodLoginInstruction() {
        if (logProvider instanceof Mood moodProvider) {
            int loginLine = moodProvider.getLoginInstructionLine();
            cursorManager.gotoLine(loginLine);
        }
    }

    @Override
    public void updateVisibleLines() {
        try {
            lastVisibleLinesFailure = null;
            visibleLines = cursorManager.getVisibleLines();
            if (viewMode == ViewMode.MATCHES && matchesProvider != null) {
                decorateEventRows(matchesProvider, visibleLines);
            } else if (viewMode == ViewMode.BOOKMARKS && bookmarksProvider != null) {
                decorateEventRows(bookmarksProvider, visibleLines);
            }
            // Every row of the Bookmarks view is bookmarked: no per-row mark there.
            if (viewMode != ViewMode.BOOKMARKS) {
                decorateBookmarks(visibleLines, bookmarks);
            }

            // Get loaded segment statistics
            var logStatistics = logProvider.getStatistics();

            if (log.isTraceEnabled()) {
                log.trace("Visible lines start time: {}",
                        DateTimeFormatterManager.formatTimestamp(logStatistics.startTime()));
            }

            // set scrollbar and first timestamp
            Instant firstTimestamp = null;
            firstVisibleSearchHit = null;
            int maxLen = 0;
            for (var line : visibleLines) {

                if (firstTimestamp == null && line.getLogLine() instanceof LoadedLogLine loadedLine) {
                    firstTimestamp = loadedLine.getDisplayTime();
                    firstVisibleSearchHit = SearchHit.from(loadedLine);
                }

                var len = line.lineLength();
                if (len > maxLen) {
                    maxLen = len;
                }
            }

            scrollBar.setMaxLineLength(maxLen);
            statusBar.setFirstVisibleTimestamp(firstTimestamp);
            statusBar.setSelectionSpan(selectionSpanOrNull());
            if (isEventDispatchThread()) {
                pushBrowsingRange();
            }

            // Scrolling or moving the cursor clears the navigation highlight.
            // The "no more matches" note belongs to that highlight, not to the new viewport.
            // In the Matches view the cursor is the selection, not the F3 highlight.
            if (isEventDispatchThread() && viewMode == ViewMode.LOG && !cursorManager.hasNavigationHighlight()) {
                searchNavigation.releaseStuck();
                statusBar.clearSearchPosition();
            }
            // Scrolling or moving the cursor before the first match is known means
            // the user took over; an automatic jump must not yank the view away.
            if (isEventDispatchThread() && pendingFirstMatchSession != null
                    && !Objects.equals(firstVisibleSearchHit, pendingFirstMatchAnchor)) {
                clearFirstMatchJump();
            }
            if (isEventDispatchThread() && (detailsOpen || pendingDetailsReopen != null)) {
                syncDetails();
            }

        } catch (Throwable e) {
            log.error("Error updating visible lines", e);
            String message = ExceptionMessageFormatter.formatException(e);
            // Repaints call this often: one toast per failure, not one per repaint.
            if (!Objects.equals(message, lastVisibleLinesFailure)) {
                lastVisibleLinesFailure = message;
                failureToast(message);
            }
        }
        updateMinimap();
        repaint();
    }

    /**
     * Tell the status bar the log's browsing range: the provider's before and after boundaries (the after side
     * reads {@code now} while it follows it). Nothing on the welcome screen. Every visible-lines update comes
     * through here, so boundary expansions, jumps, logins and logouts all reach the status bar; it ignores a
     * range it already shows.
     */
    private void pushBrowsingRange() {
        Instant start = null;
        Instant end = null;
        if (!isInMoodMode()) {
            var before = logProvider.getBeforeBoundary();
            var after = logProvider.getAfterBoundary();
            if (before != null && after != null) {
                start = before.instant();
                end = after.instant();
            }
        }
        statusBar.setBrowsingRange(start, end);
    }

    private CursorManager.SelectionSpan selectionSpanOrNull() {
        try {
            return cursorManager.selectionSpan().orElse(null);
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.debug("Selection span unavailable", e);
            return null;
        }
    }

    /** Attach the between-event rule and the suffix to each visible row of an event-list view. */
    private static void decorateEventRows(EventListProvider provider, List<DecoratedLogLine> lines) {
        for (DecoratedLogLine line : lines) {
            if (line.getLogLine() instanceof LoadedLogLine loaded) {
                line.setGapRule(provider.ruleBefore(loaded.getLineNumber()));
                line.setSuffix(provider.suffixAt(loaded.getLineNumber()));
            }
        }
    }

    /** Mark every visible row of a bookmarked event; only loaded rows can belong to one. */
    static void decorateBookmarks(List<DecoratedLogLine> lines, Bookmarks bookmarks) {
        if (bookmarks.size() == 0) {
            return;
        }
        for (DecoratedLogLine line : lines) {
            if (line.getLogLine() instanceof LoadedLogLine loaded) {
                line.setBookmarked(bookmarks.contains(SearchHit.from(loaded)));
            }
        }
    }

    /**
     * Point the minimap at what the current view shows. The strip is always there in the Log and Matches
     * views (visible-range outline, day bands, bookmark ticks); the matches are added, by row in the log
     * view and by time in the Matches view, only while a search is active. Hidden in the Bookmarks view and
     * in Mood mode. Cheap on a scroll (the visible range only); the row lookups run off the EDT in
     * {@link MinimapController}.
     */
    private void updateMinimap() {
        if (!isEventDispatchThread()) {
            invokeLater(this::updateMinimap);
            return;
        }
        try {
            SearchSession session = currentSearchSession();
            SearchSession.Snapshot matches = session != null && !session.isDiscarded() && statusBar.isSearchActive()
                    ? session.snapshot()
                    : null;
            if (viewMode == ViewMode.BOOKMARKS || isInMoodMode()) {
                minimapController.hide();
            } else if (viewMode == ViewMode.MATCHES) {
                if (matchesProvider == null || matches == null) {
                    minimapController.hide();
                } else {
                    Instant first = null;
                    Instant last = null;
                    for (DecoratedLogLine line : visibleLines) {
                        if (line.getLogLine() instanceof LoadedLogLine loaded && loaded.getSplunkTime() != null) {
                            first = first == null ? loaded.getSplunkTime() : first;
                            last = loaded.getSplunkTime();
                        }
                    }
                    minimapController.showTime(matchesProvider.snapshot(), first, last);
                }
            } else {
                SegmentInfo segment = logProvider.getSegmentInfo();
                int firstRow = Integer.MAX_VALUE;
                int lastRow = Integer.MIN_VALUE;
                for (DecoratedLogLine line : visibleLines) {
                    firstRow = Math.min(firstRow, line.getLogLine().line());
                    lastRow = Math.max(lastRow, line.getLogLine().line());
                }
                if (segment == null) {
                    minimapController.hide();
                } else {
                    minimapController.showRows(matches, segment.startLine(), segment.endLineExclusive(),
                            segment.startTime(), segment.endTime(), logProvider::peekLine, logProvider, firstRow,
                            lastRow);
                }
            }
            layoutMinimap();
        } catch (RuntimeException e) {
            log.warn("Minimap update failed", e);
        }
    }

    /**
     * A click or drag on the minimap: scroll so the viewport is centred on {@code position}, by row in the log
     * view and by time in the Matches view (the first event at or after it). The scroll the mouse wheel does
     * ({@link CursorManager#scrollCentreTo}), so the cursor and the selection stay, the F3 highlight clears and
     * {@link #updateVisibleLines()} cancels a pending first-match jump.
     */
    private void centerOnMinimapPosition(long position) {
        try {
            if (viewMode == ViewMode.MATCHES && matchesProvider != null) {
                var row = matchesProvider.rowAtOrAfter(MinimapModel.instantOf(position));
                if (row.isPresent()) {
                    cursorManager.scrollCentreTo(row.getAsInt());
                }
            } else if (viewMode == ViewMode.LOG && !isInMoodMode()) {
                cursorManager.scrollCentreTo(position);
            }
        } catch (Exception e) {
            log.error("Minimap navigation failed", e);
        }
    }

    /** The strip: half a character wide (at least 4 px) at the right edge, above the horizontal thumb. */
    private void layoutMinimap() {
        int stripWidth = Math.max(4, (charSize != null ? charSize.width() : 8) / 2);
        int width = stripWidth + Minimap.GLOW_REACH;
        int height = Math.max(0, layeredPane.getHeight() - scrollBar.occupiedHeight());
        Rectangle bounds = new Rectangle(Math.max(0, layeredPane.getWidth() - width), 0, width, height);
        if (!bounds.equals(minimap.getBounds())) {
            minimap.setBounds(bounds);
        }
    }

    /** The minimap component, for tests. */
    Minimap minimap() {
        return minimap;
    }

    boolean isHorizontalScrollbarVisible() {
        return scrollBar.isScrollbarVisible();
    }

    CursorManager cursorManager() {
        return cursorManager;
    }

    private void updateLinesPerPage() {
        if (charSize == null) {
            // not initialized yet
            return;
        }
        var linesPerPage = Math.max(1, viewPanel.getHeight() / charSize.height);
        var charsPerLine = Math.max(1, viewPanel.getWidth() / charSize.width);
        cursorManager.updateSize(linesPerPage, charsPerLine);

        // Center on login instruction when Mood is first displayed with valid dimensions
        if (pendingMoodCentering && linesPerPage > 1) {
            pendingMoodCentering = false;
            // Defer to ensure all layout is complete
            SwingUtilities.invokeLater(this::centerOnMoodLoginInstruction);
        } else {
            updateVisibleLines();
        }
    }

    @Override
    public void actionLogin() { // Shows IndexPanel with login overlay on top - unified approach for all login scenarios
        showIndexPanelWithLoginOverlay();
    }

    /**
     * ReloginProvider implementation - called by SplunkClient when session is lost.
     * Shows login overlay with locked fields (only password editable).
     * Must NOT be called from EDT since it blocks waiting for password.
     */
    CompletableFuture<String> handleRelogin(String errorMessage) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("handleRelogin must not be called on the EDT");
        }

        CompletableFuture<String> passwordFuture = new CompletableFuture<>();

        showReloginOverlayLater(LoginReason.relogin(errorMessage), passwordFuture, SplunkCredentials::password);
        return passwordFuture;
    }

    /**
     * ReloginProvider implementation - called by SplunkClient when Splunk cannot be reached.
     * Shows the relogin overlay with nothing editable: retry keeps the session.
     */
    CompletableFuture<Void> handleUnreachable(String errorMessage) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("handleUnreachable must not be called on the EDT");
        }
        CompletableFuture<Void> retryFuture = new CompletableFuture<>();
        showReloginOverlayLater(LoginReason.unreachable(errorMessage), retryFuture, credentials -> null);
        return retryFuture;
    }

    private <T> void showReloginOverlayLater(LoginReason reason, CompletableFuture<T> answerFuture,
            Function<SplunkCredentials, T> answer) {
        SwingUtilities.invokeLater(() -> {
            try {
                showReloginOverlay(reason, answerFuture, answer);
            } catch (RuntimeException | Error e) {
                // Nobody else can answer a prompt that never appeared: release the waiter
                log.error("Failed to show relogin overlay", e);
                answerFuture.completeExceptionally(e);
            }
        });
    }

    /** The {@link ReloginProvider} of this viewer: password prompt and unreachable prompt. */
    private ReloginProvider reloginProvider() {
        return new ReloginProvider() {
            @Override
            public CompletableFuture<String> requestPassword(String errorMessage) {
                return handleRelogin(errorMessage);
            }

            @Override
            public CompletableFuture<Void> requestRetry(String errorMessage) {
                return handleUnreachable(errorMessage);
            }
        };
    }

    /**
     * CertificateDecisionProvider implementation - called by {@link SplunkClient} when the
     * server presents a certificate that the system trust store rejects, typically a
     * self-signed certificate from a local/test Splunk image.
     *
     * <p>Shows a modal trust dialog on the EDT and blocks the connecting (non-EDT) thread
     * until the user decides whether the certificate may be trusted.
     */
    private CompletableFuture<TrustDecision> handleCertificateTrust(CertificateChallenge challenge) {
        if (isEventDispatchThread()) {
            throw new IllegalStateException("handleCertificateTrust must not be called on the EDT");
        }

        CompletableFuture<TrustDecision> decisionFuture = new CompletableFuture<>();

        SwingUtilities.invokeLater(() -> CertificateTrustDialog.show(this, challenge, decisionFuture::complete));

        return decisionFuture;
    }

    /**
     * Shows relogin overlay with password-only entry (host/user locked).
     * Called when SplunkClient detects session error.
     */
    // Identity comparison is intentional: a login overlay is a specific Swing component instance.
    @SuppressWarnings("ReferenceEquality")
    private <T> void showReloginOverlay(LoginReason reason, CompletableFuture<T> passwordFuture,
            Function<SplunkCredentials, T> answer) {
        if (!isEventDispatchThread()) {
            throw new IllegalStateException("showReloginOverlay must be called on the EDT");
        }

        // The request was withdrawn (superseded or shut down) before the prompt appeared
        if (passwordFuture.isDone()) {
            return;
        }

        // The overlay this prompt shows; set below, before any user input can arrive
        AtomicReference<JPanel> shownOverlay = new AtomicReference<>();

        // Wrap CompletableFuture to accept SplunkCredentials; the overlay closes when the future completes
        Consumer<SplunkCredentials> credentialsConsumer = credentials -> {
            if (credentials != null) {
                passwordFuture.complete(answer.apply(credentials));
            } else {
                passwordFuture.completeExceptionally(new CancellationException("Login cancelled by user"));
            }
        };

        JPanel previousOverlay = loginOverlay;
        try {
            shownOverlay.set(showLoginOverlay(reason, credentialsConsumer));
        } catch (RuntimeException | Error e) {
            // Construction failed part-way: do not leave a half-built modal overlay behind
            if (loginOverlay != previousOverlay) {
                dismissReloginOverlay(loginOverlay);
            }
            throw e;
        }

        // Close the overlay however the request ends (answered, cancelled by the user,
        // withdrawn by an interrupted or shut-down recovery). Only this prompt's own
        // overlay is closed; a replacement overlay is left alone.
        passwordFuture.whenComplete((password, error) -> SwingUtilities
                .invokeLater(() -> dismissReloginOverlay(shownOverlay.get())));
    }

    /**
     * Closes the given relogin overlay if it is still the one shown and re-enables the
     * underlying components. Must run on the EDT.
     */
    private void dismissReloginOverlay(JPanel overlay) {
        if (!closeLoginOverlay(overlay)) {
            return;
        }
        if (indexPanel != null) {
            indexPanel.setEnabled(true);
        }
        if (viewPanel != null) {
            viewPanel.setEnabled(true);
        }
        validate();
        repaint();
    }

    /**
     * Show login flow - starts with login overlay, then IndexPanel after successful login.
     * Called when user initiates login action (press 'L').
     */
    private void showIndexPanelWithLoginOverlay() {
        showLoginForInitialCredentials(LoginReason.INITIAL);
    }

    /**
     * Shows login overlay for initial login. If login fails, re-shows the dialog with error message.
     * All fields are editable (unlike relogin which locks host/user).
     */
    private void showLoginForInitialCredentials(LoginReason reason) {
        if (!isEventDispatchThread()) {
            throw new IllegalStateException("showLoginForInitialCredentials must be called on the EDT");
        }

        // If already logged in, go directly to index panel
        if (searchManager != null) {
            showIndexPanel();
            return;
        }

        // Show login overlay for initial login
        showLoginOverlay(reason, credentials -> {
            if (credentials == null) {
                // User cancelled - just close overlay
                SwingUtilities.invokeLater(this::handleLoginCancel);
                return;
            }

            try {
                // Wait for any pending logout to finish so DB file locks are released.
                // Never proceed while a timed-out logout may still own the store files.
                if (!waitForPendingLogout()) {
                    throw new StorageException("Previous logout is still in progress — please try again in a moment");
                }

                // Create and initialize SearchManager with credentials and relogin provider
                searchManager = new SearchManager(credentials, reloginProvider(), this::onLogUpdated, clock,
                        this::handleCertificateTrust);
                searchManager.getSplunkClient()
                        .setBusyListener(
                                (detail, gaveUp) -> SwingUtilities.invokeLater(() -> busyToast(detail, gaveUp)));
                SwingUtilities.invokeLater(() -> {
                    handleLoginSuccess();
                    showIndexPanel();
                });
            } catch (Exception e) {
                log.error("Failed to create SearchManager", e);
                String errorMessage = ExceptionMessageFormatter.formatException(e);
                // Re-show login dialog with error message
                SwingUtilities.invokeLater(() -> {
                    // Close current overlay before re-opening
                    closeLoginOverlay();
                    showLoginForInitialCredentials(LoginReason.initialWithError(errorMessage));
                });
            }
        });
    }

    /**
     * Show IndexPanel (SearchManager must be initialized).
     */
    private void showIndexPanel() {
        if (!isEventDispatchThread()) {
            throw new IllegalStateException("showIndexPanel must be called on the EDT");
        }
        closeDetails();
        closeTimePanel();
        statusBar.setModalIndex(true);

        // Remove existing indexPanel if present
        if (indexPanel != null) {
            layeredPane.remove(indexPanel);
        }
        if (userLoginPanel != null) {
            layeredPane.remove(userLoginPanel);
        }

        // Disable viewPanel to prevent key events from being processed
        if (viewPanel != null) {
            viewPanel.setEnabled(false);
        }

        // Create and show IndexPanel in the layeredPane (above viewPanel)
        indexPanel = new IndexPanel(searchManager, charSize, clock);
        indexPanel.setOnLogout(this::requestLogout);
        indexPanel.setBookmarkGuard(bookmarkScopeGuard());
        indexPanel.setOnLoginComplete(this::loginComplete);
        indexPanel.setOnCancel(this::handleIndexCancel);

        // Set bounds and add to PALETTE_LAYER (above DEFAULT but below MODAL)
        indexPanel.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
        layeredPane.add(indexPanel, JLayeredPane.PALETTE_LAYER);

        validate();
        repaint();
        indexPanel.requestFocusInWindow();
    }

    /**
     * Shows login overlay with proper event blocking and z-ordering.
     *
     * <h3>Swing Best Practices for Translucent Overlays:</h3>
     * <ul>
     *   <li><b>Disable underlying components</b> - Prevents hover effects and button rendering on top</li>
     *   <li><b>Event blocking</b> - Override processMouseEvent/processMouseMotionEvent and consume events</li>
     *   <li><b>Graphics2D.create()</b> - Proper graphics context disposal to avoid rendering artifacts</li>
     *   <li><b>RenderingHints</b> - Enable antialiasing for smooth rendering (helps with WSL/remote displays)</li>
     *   <li><b>Z-order control</b> - setComponentZOrder(0) ensures overlay is topmost</li>
     *   <li><b>Focus management</b> - requestFocusInWindow() ensures keyboard events go to overlay</li>
     * </ul>
     * These techniques prevent underlying components from painting on top during mouse-over,
     * which is particularly important for WSL, remote X11, and unusual graphics pipelines.
     */
    private JPanel showLoginOverlay(LoginReason loginReason, Consumer<SplunkCredentials> loginConsumer) {

        if (!isEventDispatchThread()) {
            throw new IllegalStateException("showLoginOverlay must be called on the EDT");
        }

        closeDetails();
        // Replace any existing overlay; the modal-login state follows the overlay
        closeLoginOverlay();
        statusBar.setModalLogin(true);

        // Disable underlying components to prevent interaction and painting issues
        if (indexPanel != null) {
            indexPanel.setEnabled(false);
        }
        if (viewPanel != null) {
            viewPanel.setEnabled(false);
        }

        closeTimePanel();
        // Create semi-transparent overlay with event blocking
        loginOverlay = new JPanel() {
            {
                // Enable event processing in constructor
                enableEvents(java.awt.AWTEvent.MOUSE_EVENT_MASK | java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK);
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // Draw semi-transparent background
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    // Enable antialiasing for smooth rendering
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f));
                    g2d.setColor(new Color(20, 20, 20));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                } finally {
                    g2d.dispose();
                }
            }

            // Block all mouse events from reaching underlying components
            @Override
            protected void processMouseEvent(java.awt.event.MouseEvent e) {
                super.processMouseEvent(e);
                e.consume(); // Consume event to prevent propagation
            }

            @Override
            protected void processMouseMotionEvent(java.awt.event.MouseEvent e) {
                super.processMouseMotionEvent(e);
                e.consume();
            }
        };

        // Make overlay opaque to ensure proper rendering and event handling
        loginOverlay.setOpaque(false);
        loginOverlay.setLayout(new GridBagLayout());

        // Create login panel
        userLoginPanel = new UserLoginPanel(config, loginReason, loginConsumer);

        // Center the login panel in the overlay
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        loginOverlay.add(userLoginPanel, gbc);

        // Set overlay bounds to cover entire layeredPane and add at MODAL_LAYER
        loginOverlay.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
        layeredPane.add(loginOverlay, JLayeredPane.MODAL_LAYER);

        validate();
        repaint();

        // Request focus on the appropriate login field
        // The panel itself handles which field to focus
        userLoginPanel.focusDefaultField();

        return loginOverlay;
    }

    /**
     * Removes the login overlay, if any, and ends the modal-login state with it.
     */
    private void closeLoginOverlay() {
        closeLoginOverlay(loginOverlay);
    }

    /**
     * Removes the given login overlay if it is still the one shown, and ends the
     * modal-login state with it. A replaced overlay is left alone.
     *
     * @return true if the overlay was the current one and was closed
     */
    // Identity comparison is intentional: a login overlay is a specific Swing
    // component instance; a replaced overlay must be left alone.
    @SuppressWarnings("ReferenceEquality")
    private boolean closeLoginOverlay(JPanel overlay) {
        if (overlay == null || overlay != loginOverlay) {
            return false;
        }
        layeredPane.remove(loginOverlay);
        loginOverlay = null;
        userLoginPanel = null;
        statusBar.setModalLogin(false);
        return true;
    }

    /**
     * Handle successful login - removes overlay and initializes SearchManager.
     * IndexPanel is already showing underneath.
     */
    private void handleLoginSuccess() {
        try {

            if (!isEventDispatchThread()) {
                throw new IllegalStateException("handleLoginSuccess must be called on the EDT");
            }

            // Remove overlay and re-enable underlying components
            closeLoginOverlay();

            // Re-enable underlying components
            if (indexPanel != null) {
                indexPanel.setEnabled(true);
            }
            if (viewPanel != null) {
                viewPanel.setEnabled(true);
            }

            validate();
            repaint();

            notice(StatusBar.Severity.INFO, "Successfully connected to Splunk");
        } catch (Exception e) {
            log.error("Error initializing SearchManager", e);
            failureToast(ExceptionMessageFormatter.formatException(e));
            handleLoginCancel();
        }
    }

    /**
     * Removes overlay and re-enables underlying components.
     */
    private void handleLoginCancel() {

        if (!isEventDispatchThread()) {
            throw new IllegalStateException("handleLoginCancel must be called on the EDT");
        }

        // Remove overlay and re-enable underlying components
        closeLoginOverlay();

        // If IndexPanel is showing, remove it (viewPanel is always in the layeredPane)
        if (indexPanel != null) {
            layeredPane.remove(indexPanel);
            indexPanel = null;
        }

        // Re-enable viewPanel
        if (viewPanel != null) {
            viewPanel.setEnabled(true);
        }

        validate();
        repaint();

        // Focus viewPanel
        if (viewPanel != null) {
            viewPanel.requestFocusInWindow();
        }
    }

    /**
     * Waits for any pending async logout to complete so MVStore file locks are released.
     * Called on background threads before creating a new SearchManager.
     *
     * @return true if no logout is pending anymore and login may proceed; false if a
     *         previous logout is still running after the wait — in that case it may
     *         still own the store files and a new login must not proceed
     */
    private boolean waitForPendingLogout() {
        CompletableFuture<Void> pending = logoutFuture;
        if (pending != null && !pending.isDone()) {
            log.info("Waiting for previous logout to complete before new login...");
            try {
                pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for previous logout");
                return false;
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn(
                        "Timed out waiting for previous logout; refusing login while it may still own the database files");
                return false;
            } catch (Exception e) {
                // The logout task itself is done (possibly with an error), so it no
                // longer runs — a new login may proceed. If it failed to release the
                // store files, the DatabaseManager open will fail safely with a
                // DatabaseLockedException instead of deleting anything.
                log.warn("Previous logout completed with error: {}", e.getMessage());
            }
        }
        return true;
    }

    /** Logout from the index panel: asks first when bookmarks were never exported or copied. */
    void requestLogout() {
        unsavedGuard.guard(UnsavedBookmarksGuard.Purpose.LOG_OUT, this::handleLogout);
    }

    /** The window is closing: run {@code exit} unless the user keeps the app open to save bookmarks. */
    public void requestQuit(Runnable exit) {
        unsavedGuard.guard(UnsavedBookmarksGuard.Purpose.QUIT, exit);
    }

    /** Replace the quit/logout question (tests). */
    void setUnsavedChooser(UnsavedBookmarksGuard.Chooser chooser) {
        this.unsavedChooser = chooser;
    }

    /** Replace the export writer (tests). */
    void setExportController(ExportController exports) {
        this.exports = exports;
    }

    /**
     * Handles logout: closes SearchManager, resets state, shows Mood view.
     * After logout, user can log in again by pressing 'L'.
     * Heavy cleanup operations run on background thread to avoid blocking UI.
     */
    private void handleLogout() {
        if (!isEventDispatchThread()) {
            throw new IllegalStateException("handleLogout must be called on the EDT");
        }

        closeDetails();
        pendingDetailsReopen = null;

        // Capture reference before nulling
        final SearchManager sm = searchManager;
        searchManager = null;

        // The provider is about to be replaced with Mood: drop the Matches and Bookmarks views (before the
        // bookmarks are cleared, so the open view does not refresh over them).
        clearEventViewState();
        dropScopeQuestion();
        // Bookmarks live for the session only; the next login starts with none.
        bookmarks.clear();
        bookmarkHintShown = false;

        // Cancel any running search — SearchManager owns the active Searcher
        searchUpdates.unbind();
        provisional.clear();
        searchNavigation.clear();
        clearFirstMatchJump();
        if (sm != null) {
            sm.cancelSearch();
        }
        statusBar.setSearchActive(false, null);
        statusBar.clearSearchInfo();
        statusBar.setBrowsingRange(null, null);
        closeTimePanel();

        // Remove index panel if present
        if (indexPanel != null) {
            layeredPane.remove(indexPanel);
            indexPanel = null;
        }

        // Remove login overlay if present
        closeLoginOverlay();

        // Reset log data provider to Mood immediately
        synchronized (logLock) {
            logProvider = mood;
            cursorManager.setLogProvider(logProvider);
        }

        // Re-enable viewPanel
        if (viewPanel != null) {
            viewPanel.setEnabled(true);
        }

        validate();
        repaint();
        updateVisibleLines();

        // Focus viewPanel
        if (viewPanel != null) {
            viewPanel.requestFocusInWindow();
        }

        // Perform heavy cleanup on background thread to avoid blocking UI
        if (sm != null) {
            logoutFuture = runAsync(() -> {
                try {
                    sm.logout();
                    log.info("SearchManager logout complete");
                } catch (Exception e) {
                    log.warn("Error during logout cleanup: {}", e.getMessage());
                }
            });
        }

        log.debug("Logout UI reset completed");
        log.info("Handling logout");
        statusBar.setModalLogin(false);
        statusBar.setModalIndex(false);
    }

    /**
     * Handle cancel from IndexPanel
     */
    private void handleIndexCancel() {

        if (!isEventDispatchThread()) {
            throw new IllegalStateException("handleIndexCancel must be called on the EDT");
        }

        if (indexPanel != null) {
            layeredPane.remove(indexPanel);
            indexPanel = null;
        }

        // Re-enable viewPanel
        if (viewPanel != null) {
            viewPanel.setEnabled(true);
        }

        validate();
        repaint();
        viewPanel.requestFocusInWindow();

        invokeLater(() -> statusBar.setModalIndex(false));
    }

    @Override
    public void loginComplete(SearchManager searchManager) {
        if (!isEventDispatchThread()) {
            throw new IllegalStateException("loginComplete must be called on the EDT");
        }

        try {

            if (searchManager == null) {
                log.warn("Login complete with null SearchManager");
                return;
            }

            inputHandler.seedClipboard();

            closeDetails();
            pendingDetailsReopen = null;
            // A new provider replaces any Matches or Bookmarks view.
            clearEventViewState();
            dropScopeQuestion();
            // Applying a scope cancelled the search in the manager; end its status too.
            if (statusBar.isSearchActive() && searchManager.getActiveSearchSession() == null) {
                resetSearchUi();
            }
            // The index panel set the scope before handing over; show its terms.
            refreshScopeChips();

            log.info("Setting search manager load progress callback");
            searchManager.setLoadProgressCallback(
                    progress -> SwingUtilities.invokeLater(() -> statusBar.updateSearchProgress(progress)));

            synchronized (logLock) {
                // SearchManager is already our instance, just update the data provider
                logProvider = searchManager.getDataProvider();
                cursorManager.setLogProvider(logProvider);
                // A new provider starts its own range; it is not a growth of the previous one.
                statusBar.setBrowsingRange(null, null);

                // Register this LogViewer as the boundary expansion listener
                logProvider.setBoundaryExpansionListener(this);
            }

            // Remove index panel and show view panel
            if (indexPanel != null) {
                layeredPane.remove(indexPanel);
                indexPanel = null;
            }

            // Re-enable viewPanel
            if (viewPanel != null) {
                viewPanel.setEnabled(true);
            }

            validate();
            repaint();
            viewPanel.requestFocusInWindow();

            log.debug("Login backgroundTask started");
            var line = logProvider.jumpToLatest();

            // set line in the golden ration (from the bottom) of the page
            var linesPerPage = cursorManager.getLinesPerPage();
            var topLine = line - linesPerPage;
            cursorManager.setPageStart(topLine);

            log.info("Login backgroundTask. LogData: {}", logProvider);

            updateVisibleLines();

        } catch (Throwable ex) {
            log.error("Login backgroundTask failed", ex);
            var msg = ExceptionMessageFormatter.formatException(ex);
            failureToast(msg);
            log.error(msg, ex);
        } finally {
            log.debug("LoginComplete UI update finished");
            statusBar.setModalIndex(false);
        }
    }

    @Override
    public void setHorizontalOffset(int i) {
        // min horizontal offset = 0
        horizontalOffset = Math.max(0, i);
        log.debug("horizontal offset: {}", horizontalOffset);
        // No repaint here — callers always follow with updateVisibleLines()
        // which repaints after both offset and visible lines are consistent.
    }

    /**
     * The currently active search session, owned by SearchManager, or null if
     * no search is running or we are not logged in.
     */
    private SearchSession currentSearchSession() {
        return searchManager != null ? searchManager.getActiveSearchSession() : null;
    }

    @Override
    public Optional<String> lastSubmittedSearchTerm() {
        return Optional.ofNullable(lastSubmittedTerm);
    }

    @Override
    public Optional<String> getActiveSearchTerm() {
        SearchSession session = currentSearchSession();
        if (session == null || !statusBar.isSearchActive()) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.getQuery()).filter(q -> !q.isBlank());
    }

    /**
     * The active search term: only search criteria highlight text. The scope (index, field filters, narrowing and
     * excluded terms) never does.
     */
    @Override
    public List<String> getHighlightTerms() {
        return getActiveSearchTerm().stream().toList();
    }

    @Override
    public boolean isSearchInputActive() {
        return statusBar.isSearchInputActive();
    }

    @Override
    public boolean isMatchesView() {
        return viewMode == ViewMode.MATCHES;
    }

    @Override
    public boolean isBookmarksView() {
        return viewMode == ViewMode.BOOKMARKS;
    }

    @Override
    public void toggleMatches() {
        if (isInMoodMode()) return;

        SearchSession session = currentSearchSession();
        boolean searchActive = session != null && statusBar.isSearchActive();
        if (viewMode == ViewMode.BOOKMARKS) {
            showMatchesFromBookmarks(searchActive);
            return;
        }
        if (!searchActive) {
            showEmptyMatches();
            return;
        }
        enterMatches();
    }

    /**
     * F4 / {@code &} / the Matches tab in the Bookmarks view: go to the Matches view of the active search,
     * or, with none, to the empty Matches view. Opened from the Matches view that is just the way back;
     * opened from the log, the Matches view is entered from there.
     */
    private void showMatchesFromBookmarks(boolean searchActive) {
        ViewMode origin = bookmarksOrigin;
        leaveBookmarks();
        if (!searchActive) {
            showEmptyMatches();
        } else if (origin == ViewMode.LOG) {
            enterMatches();
        }
    }

    /** The empty Matches view, with the standard search prompt open on top of it. */
    private void showEmptyMatches() {
        if (viewMode == ViewMode.LOG) {
            enterEmptyMatches();
        }
        setSearchInputActive(true);
    }

    /** Whether the Matches view shown is the empty one, with no search behind it. */
    private boolean isEmptyMatchesView() {
        return viewMode == ViewMode.MATCHES && matchesProvider != null && matchesProvider.isPlaceholder();
    }

    /**
     * The Log ⇄ Matches menu entry: toggles the Matches view from the log, and
     * shows the selected match in the log from the Matches view (as F4 does there).
     */
    public void toggleMatchesOrShowInLog() {
        if (viewMode == ViewMode.MATCHES) {
            showMatchInLog();
        } else {
            toggleMatches();
        }
    }

    /**
     * Show the event under the cursor in the log view (Enter/F4/{@code &} in the Matches view, Enter in
     * the Bookmarks view). The exact jump lands on the event's first log row; the cursor's offset
     * within the event ({@code k}) is added, and that row is placed on the same screen row the cursor
     * had. A match becomes the F3 anchor. From the Bookmarks view this leaves both views, whichever one
     * it was opened from, and lands in the log.
     */
    @Override
    public void showMatchInLog() {
        EventListProvider list = eventListProvider();
        if (list == null) return;
        boolean fromMatches = viewMode == ViewMode.MATCHES;

        // Page (scroll-lock) mode has no cursor: use the first event row on screen.
        var cursorLine = cursorManager.cursorLine();
        int cursorRow;
        if (cursorLine.isPresent()) {
            cursorRow = cursorLine.get();
        } else {
            int top = cursorManager.getPageStart();
            int last = top + Math.max(1, cursorManager.getLinesPerPage());
            cursorRow = top;
            while (cursorRow < last && eventAt(list, cursorRow) == null)
                cursorRow++;
        }
        SearchHit hit = eventAt(list, cursorRow);
        if (hit == null) return;
        int offsetWithin = list.offsetWithin(cursorRow);
        int screenRow = cursorManager.screenRowOf(cursorRow);

        // Back to the log viewport we left; the jump below re-positions on top of it.
        leaveEventViews();

        // A bookmark is a match only when the active search found it too.
        SearchSession session = currentSearchSession();
        boolean asMatch = fromMatches
                || (session != null && statusBar.isSearchActive() && session.indexOf(hit) >= 0);
        if (asMatch) {
            searchNavigation.select(hit);
        }
        try {
            int firstRow = logProvider.jump(hit);
            cursorManager.gotoLine(FlipPlacement.logRow(firstRow, offsetWithin), screenRow);
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.error("showMatchInLog: jump failed", e);
        }
        if (asMatch) {
            publishSearchPosition(session);
        }
    }

    /** The provider of the event-list view being shown, or null in the log view. */
    private EventListProvider eventListProvider() {
        return switch (viewMode) {
            case MATCHES -> matchesProvider;
            case BOOKMARKS -> bookmarksProvider;
            case LOG -> null;
        };
    }

    /** The hit of {@code row}, or null when the row is outside the list's event rows. */
    private static SearchHit eventAt(EventListProvider list, int row) {
        return row < 0 || row >= list.rows().rowCount() ? null : list.hitAt(row);
    }

    /**
     * Bookmark or unbookmark the events under the selection (every event it touches), or else the
     * cursor's event. In page mode there is no cursor and nothing to bookmark.
     */
    @Override
    public void toggleBookmark() {
        if (isInMoodMode()) return;
        if (detailsOpen) {
            toggleDetailsBookmark();
            return;
        }

        LogDataProvider provider = activeProvider();
        List<Integer> rows = cursorManager.selectedRows();
        boolean fromCursor = rows.isEmpty();
        if (fromCursor) {
            rows = cursorManager.cursorLine().map(List::of).orElse(List.of());
        }
        // The event-list views hold whole events already (also in first-line mode, where a row shows only a line).
        List<LoadedLogLine> events = provider instanceof EventListProvider list
                ? rows.stream().filter(row -> row >= 0 && row < list.rows().rowCount())
                        .map(row -> list.rows().event(list.rows().eventAt(row))).toList()
                : rows.stream().map(row -> EventLines.eventOf(provider::peekLine, row)).flatMap(Optional::stream)
                        .toList();
        if (events.isEmpty()) {
            notice(StatusBar.Severity.INFO, "Place the cursor on a line to bookmark it");
            return;
        }
        if (viewMode == ViewMode.BOOKMARKS && bookmarksProvider != null) {
            bookmarksCursorTarget = removalTarget(bookmarksProvider, rows);
        }

        Bookmarks.ToggleResult result = bookmarks.toggle(events);
        announceBookmarkToggle(result);
        if (fromCursor && result.action() == Bookmarks.Action.ADDED) {
            moveCursorToNextEvent(provider, rows.getFirst());
        }
        updateVisibleLines();
    }

    /** The notice (and the tab flash) for what a bookmark toggle did. */
    private void announceBookmarkToggle(Bookmarks.ToggleResult result) {
        int count = result.changed();
        String text = switch (result.action()) {
            case ADDED -> count == 1 ? "Bookmarked" : count + " bookmarked";
            case REMOVED -> count == 1 ? "Bookmark removed" : count + " bookmarks removed";
            case UNCHANGED -> null;
        };
        if (text != null) {
            if (!bookmarkHintShown && viewMode != ViewMode.BOOKMARKS) {
                bookmarkHintShown = true;
                text += " · " + KeyMap.preferred().label(UiAction.BOOKMARKS_VIEW) + " shows bookmarks";
            }
            notice(StatusBar.Severity.INFO, text);
        }
        if (result.action() == Bookmarks.Action.ADDED && viewMode != ViewMode.BOOKMARKS) {
            statusBar.flashBookmarks();
        }
    }

    /** Longest event (in rows) walked over to find the next event's first row; beyond it, step one row. */
    private static final int MAX_EVENT_ROWS = 10_000;

    /**
     * Move the cursor to the first row of the event after the one on {@code row}, so repeated bookmarking
     * walks down event by event. In the log the walk uses only loaded rows (never loads); an unloaded row
     * ends it there.
     */
    private void moveCursorToNextEvent(LogDataProvider provider, int row) {
        int next;
        if (provider instanceof EventListProvider list) {
            EventRows eventRows = list.rows();
            if (row < 0 || row >= eventRows.rowCount()) return;
            int event = eventRows.eventAt(row);
            if (event + 1 >= eventRows.eventCount()) return;
            next = eventRows.rowOf(event + 1, 0);
        } else {
            next = row + 1;
            while (next - row < MAX_EVENT_ROWS && provider.peekLine(next)
                    .filter(line -> line instanceof LoadedLogLine loaded && loaded.isContinuation()).isPresent()) {
                next++;
            }
        }
        try {
            cursorManager.moveCursor(next - row, 0, false);
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.warn("Could not move the cursor to the next event after bookmarking", e);
        }
    }

    /** Whether the open log has any loaded lines to bookmark (the menu entry's enabled state). */
    boolean hasLogLines() {
        return !isInMoodMode() && activeProvider().getStatistics().loadedLines() > 0;
    }

    /** The session's bookmarks, for the bookmarks view and tests. */
    Bookmarks bookmarks() {
        return bookmarks;
    }

    /** {@code a}: narrow to the cursor row's thread, or its process when it has no thread (a click on the icon opens the activity popup). */
    @Override
    public void narrowToCursorActivity() {
        LoadedLogLine row = cursorRowForNarrow();
        if (row == null) {
            return;
        }
        Activity.Level level = row.resolvedActivity().defaultNarrowLevel();
        if (level == null) {
            notice(StatusBar.Severity.INFO, "No activity found on this row");
            return;
        }
        narrowToActivity(level, row);
    }

    /** {@code A}: narrow to the cursor row's request. */
    @Override
    public void narrowToCursorRequest() {
        LoadedLogLine row = cursorRowForNarrow();
        if (row == null) {
            return;
        }
        if (row.resolvedActivity().request() == null) {
            notice(StatusBar.Severity.INFO, "This row has no request id");
            return;
        }
        narrowToActivity(Activity.Level.REQUEST, row);
    }

    /**
     * Narrow the scope to the activity of the loaded row {@code row} at {@code level}: every
     * {@link Activity#narrowTerms} term not already in the scope is folded in as one scope change (the same one
     * Narrow makes, so the bookmark question, the chips and the undo are the same). The log reloads at the row's
     * event, highlighted. A notice says why when the level has no terms or the scope has them all. In the Matches and
     * Bookmarks views the change leaves the view for the log, as Narrow does.
     */
    public void narrowToActivity(Activity.Level level, int row) {
        LoadedLogLine line = loadedLineAt(row);
        if (line == null) {
            notice(StatusBar.Severity.INFO, cantNarrow(level));
            return;
        }
        narrowToActivity(level, line);
    }

    private void narrowToActivity(Activity.Level level, LoadedLogLine line) {
        Scope scope = scopeOrWarn();
        if (scope == null) {
            return;
        }
        List<String> terms = line.resolvedActivity().narrowTerms(level);
        if (terms.isEmpty()) {
            notice(StatusBar.Severity.INFO, cantNarrow(level));
            return;
        }
        Scope narrowed = scope;
        for (String term : terms) {
            narrowed = narrowed.withNarrow(term);
        }
        if (narrowed.equals(scope)) {
            notice(StatusBar.Severity.INFO, "Already narrowed to this row's " + level.label());
            return;
        }
        SearchHit hit = SearchHit.from(line);
        Scope next = narrowed;
        confirmBookmarksThenChangeScope(scope, next, () -> applyScope(next, hit, hit.splunkTime(), true));
    }

    /**
     * Exclude the activity of the loaded row {@code row} at {@code level} from the scope: every
     * {@link Activity#narrowTerms} term not already excluded is folded in as one Exclude ({@code NOT}) term. The
     * log reloads at its top row, like Exclude does.
     */
    public void excludeFromActivity(Activity.Level level, int row) {
        LoadedLogLine line = loadedLineAt(row);
        if (line == null) {
            notice(StatusBar.Severity.INFO, cantExclude(level));
            return;
        }
        excludeFromActivity(level, line);
    }

    private void excludeFromActivity(Activity.Level level, LoadedLogLine line) {
        Scope scope = scopeOrWarn();
        if (scope == null) {
            return;
        }
        List<String> terms = line.resolvedActivity().narrowTerms(level);
        if (terms.isEmpty()) {
            notice(StatusBar.Severity.INFO, cantExclude(level));
            return;
        }
        Scope excluded = scope;
        for (String term : terms) {
            if (!Scope.isExcludable(term)) {
                notice(StatusBar.Severity.WARN, "Cannot exclude \"%s\": use a plain term".formatted(term));
                return;
            }
            excluded = excluded.withExclude(term);
        }
        if (excluded.equals(scope)) {
            notice(StatusBar.Severity.INFO, "Already excluded this row's " + level.label());
            return;
        }
        Scope next = excluded;
        confirmBookmarksThenChangeScope(scope, next, () -> applyScope(next, null, topRowTime(), false));
    }

    private static String cantNarrow(Activity.Level level) {
        return "Can't narrow to this row's " + level.label();
    }

    private static String cantExclude(Activity.Level level) {
        return "Can't exclude from this row's " + level.label();
    }

    /** The loaded line under the cursor, or null (with a notice) when there is none. */
    private LoadedLogLine cursorRowForNarrow() {
        if (isInMoodMode()) {
            return null;
        }
        Optional<Integer> cursorRow = cursorManager.cursorLine();
        LoadedLogLine line = cursorRow.isPresent() ? loadedLineAt(cursorRow.get()) : null;
        if (line == null) {
            notice(StatusBar.Severity.INFO, "Place the cursor on a line to narrow to its activity");
        }
        return line;
    }

    private LoadedLogLine loadedLineAt(int row) {
        try {
            return cursorManager.getLogLineAt(row);
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.warn("loadedLineAt {}: {}", row, e.getMessage());
            return null;
        }
    }

    /** Right-click: the row menu at the pointer. The input handler has already moved the cursor if needed. */
    @Override
    public void showRowContextMenu(int x, int y) {
        JPopupMenu menu = rowContextMenu();
        if (menu != null && !GraphicsEnvironment.isHeadless()) {
            menu.show(viewPanel, x, y);
        }
    }

    /** Shift-F10 and the context-menu key: the row menu just below the cursor row, as Copy as… is placed. */
    @Override
    public void showRowContextMenuAtCursor() {
        JPopupMenu menu = rowContextMenu();
        if (menu == null) {
            notice(StatusBar.Severity.INFO, "Place the cursor on a line to open its menu");
            return;
        }
        if (!GraphicsEnvironment.isHeadless()) {
            Point corner = copyAsCorner(menu.getPreferredSize(), viewPanel.getSize());
            menu.show(viewPanel, corner.x, corner.y);
        }
    }

    /** The row menu of the cursor row, built but not shown; null without a cursor on a loaded line. */
    JPopupMenu rowContextMenu() {
        if (isInMoodMode()) {
            return null;
        }
        Optional<Integer> cursorRow = cursorManager.cursorLine();
        LoadedLogLine line = cursorRow.isPresent() ? loadedLineAt(cursorRow.get()) : null;
        if (line == null) {
            return null;
        }
        Activity activity = line.resolvedActivity();
        List<RowContextMenu.LevelItem> levels = activityLevels(activity);
        SearchSession session = currentSearchSession();
        boolean searchMatches = session != null && statusBar.isSearchActive() && session.hitCount() > 0;
        var model = new RowContextMenu.Model(levels, activity.defaultNarrowLevel(),
                bookmarks.contains(SearchHit.from(line)), searchMatches, KeyMap.preferred());
        int row = cursorRow.get();
        return RowContextMenu.build(model, new RowContextMenu.Actions() {
            @Override
            public void narrowTo(Activity.Level level) {
                narrowToActivity(level, row);
            }

            @Override
            public void excludeFrom(Activity.Level level) {
                excludeFromActivity(level, row);
            }

            @Override
            public void toggleBookmark() {
                LogViewer.this.toggleBookmark();
            }

            @Override
            public void copy() {
                copySelectionOrLine(row);
            }

            @Override
            public void copyAs() {
                showCopyAsMenu();
            }

            @Override
            public void narrowToMatches() {
                narrowScopeToSearch();
            }

            @Override
            public void excludeMatches() {
                excludeSearchFromScope();
            }

            @Override
            public void details() {
                openLogLineDetails(line);
            }
        });
    }

    /** The levels the activity can be narrowed or excluded at, coarse to fine, with the value to show for each. */
    private static List<RowContextMenu.LevelItem> activityLevels(Activity activity) {
        List<RowContextMenu.LevelItem> levels = new ArrayList<>();
        for (Activity.Level level : new Activity.Level[]{Activity.Level.THREAD, Activity.Level.PROCESS,
                Activity.Level.REQUEST}) {
            if (!activity.narrowTerms(level).isEmpty()) {
                levels.add(new RowContextMenu.LevelItem(level, activity.value(level)));
            }
        }
        return levels;
    }

    /** Left-click on a row's icon: the activity popup at the pointer. */
    @Override
    public void showActivityMenu(int x, int y) {
        JPopupMenu menu = activityMenu();
        if (menu != null && !GraphicsEnvironment.isHeadless()) {
            menu.show(viewPanel, x, y);
        }
    }

    /**
     * The activity popup of the cursor row — Narrow and Exclude per level — built but not shown; null
     * without a cursor on a loaded line.
     */
    JPopupMenu activityMenu() {
        if (isInMoodMode()) {
            return null;
        }
        Optional<Integer> cursorRow = cursorManager.cursorLine();
        LoadedLogLine line = cursorRow.isPresent() ? loadedLineAt(cursorRow.get()) : null;
        if (line == null) {
            return null;
        }
        Activity activity = line.resolvedActivity();
        int row = cursorRow.get();
        return ActivityMenu.build(activityLevels(activity), activity.defaultNarrowLevel(), KeyMap.preferred(),
                new ActivityRows.Actions() {
                    @Override
                    public void narrowTo(Activity.Level level) {
                        narrowToActivity(level, row);
                    }

                    @Override
                    public void excludeFrom(Activity.Level level) {
                        excludeFromActivity(level, row);
                    }
                });
    }

    /** Ctrl-C from the row menu: the selection, or the line under the cursor when nothing is selected. */
    private void copySelectionOrLine(int row) {
        try {
            if (!cursorManager.selectedRows().isEmpty()) {
                cursorManager.copyToClipboard();
                return;
            }
            LoadedLogLine line = cursorManager.getLogLineAt(row);
            if (line != null) {
                cursorManager.copyText(List.of(new DecoratedLogLine(line).formattedLine().stripTrailing()));
            }
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.warn("copySelectionOrLine: {}", e.getMessage());
        }
    }

    /** The rows currently painted, with their decorations; for tests. */
    List<DecoratedLogLine> visibleLines() {
        return visibleLines;
    }

    /**
     * The tooltip of the icon at {@code (x, y)} of the view panel: the activity of the loaded row there. Null
     * elsewhere on the row, on placeholders and boundaries. Reads the row from {@link #visibleLines} (no I/O).
     */
    String activityTooltipAt(int x, int y) {
        if (charSize == null || x < 0 || y < 0 || x >= LineRenderer.iconSpaceWidth(charSize.height())) {
            return null;
        }
        int index = y / charSize.height();
        if (index >= visibleLines.size() || !(visibleLines.get(index).getLogLine() instanceof LoadedLogLine loaded)) {
            return null;
        }
        return ActivityTooltip.text(loaded.resolvedActivity());
    }

    /**
     * The tooltip of the time column at {@code (x, y)} of the view panel: the row's full timestamp in the
     * configured zone, plus UTC when that zone is not UTC. Null off the column, on continuation
     * rows, placeholders and boundaries. Reads the row from {@link #visibleLines} (no I/O).
     */
    String timeColumnTooltipAt(int x, int y) {
        if (charSize == null || x < 0 || y < 0) {
            return null;
        }
        int index = y / charSize.height();
        if (index >= visibleLines.size()
                || !(visibleLines.get(index).getLogLine() instanceof LoadedLogLine loaded)
                || loaded.isContinuation()) {
            return null;
        }
        int textStartX = LineRenderer.iconSpaceWidth(charSize.height());
        int columnChars = Math.max(0, DecoratedLogLine.getPreambleLength() - horizontalOffset);
        if (columnChars == 0 || x < textStartX || x >= textStartX + columnChars * charSize.width()) {
            return null;
        }
        return TimeTooltip.text(loaded.getDisplayTime());
    }

    /** The provider the view paints: the Matches snapshot or the bookmarks in their views, else the log. */
    private LogDataProvider activeProvider() {
        EventListProvider list = eventListProvider();
        return list != null ? list : logProvider;
    }

    /**
     * Leave the event-list view, restoring the exact viewport held before entering, with no movement
     * (Esc/Ctrl-G): the Bookmarks view goes back to the view it was opened from (log or Matches), the
     * Matches view to the log. The first view step of the cancel chain, after the search-input prompt.
     */
    @Override
    public boolean backToLog() {
        if (viewMode == ViewMode.BOOKMARKS) {
            leaveBookmarks();
            return true;
        }
        if (viewMode != ViewMode.MATCHES) return false;
        leaveEventViews();
        return true;
    }

    /**
     * The LOG tab: leave every event-list view for the log, restoring the log viewport held before the first of
     * them was entered. Does nothing in the log itself or while the welcome screen is shown.
     */
    public void showLog() {
        if (viewMode == ViewMode.LOG || isInMoodMode()) return;
        leaveEventViews();
        updateVisibleLines();
    }

    /**
     * The DETAILS tab: open the details of the line under the cursor, as Enter does, or close them when they are open.
     * Without a cursor line, a notice says what to do.
     */
    public void openCursorDetails() {
        if (isInMoodMode() || closeDetails()) return;
        Optional<LoadedLogLine> line = cursorManager.cursorLogLine();
        if (line.isPresent()) {
            openLogLineDetails(line.get());
        } else {
            notice(StatusBar.Severity.INFO, "Place the cursor on a line to see its details");
        }
    }

    /**
     * Enter the Matches view: capture the log viewport, choose the anchor event,
     * swap in a provider over the current session's snapshot, and place the
     * cursor on the anchor's nearest match at the anchor's screen row.
     */
    private void enterMatches() {
        if (viewMode != ViewMode.LOG) return;
        SearchSession session = currentSearchSession();
        if (session == null) return;
        closeDetails();

        CursorManager.ViewState logState = cursorManager.captureViewState();
        savedLogState = logState;

        MatchesAnchor.Choice anchor = MatchesAnchor.choose(cursorManager.hasNavigationHighlight(),
                searchNavigation.current(), logState.navHighlightLine(), cursorEventHit(logState.cursorLine()),
                logState.cursorLine(), firstVisibleSearchHit, logState.pageStart());

        MatchesDataProvider provider = createMatchesProvider(session);
        matchesProvider = provider;
        viewMode = ViewMode.MATCHES;
        statusBar.setMatchesMode(true);

        cursorManager.swapProvider(provider, null);
        // No anchor (no cursor, highlight or visible match): the match nearest the top of the page.
        int row = anchor.hit() != null
                ? FlipPlacement.matchesRow(provider, anchor.hit())
                : statusBar.getFirstVisibleTimestamp().map(provider::jump).orElse(0);
        cursorManager.gotoLine(row, anchor.screenRow());
        cursorManager.ensureCursorAt(row);
        updateVisibleLines();
    }

    /**
     * Enter the Matches view with no search behind it: capture the log viewport and swap in a placeholder
     * provider that says so. A search submitted from it takes over (see {@link #executeSearch}).
     */
    private void enterEmptyMatches() {
        if (viewMode != ViewMode.LOG) return;
        closeDetails();
        savedLogState = cursorManager.captureViewState();
        MatchesDataProvider provider = MatchesDataProvider.placeholder(
                () -> MatchesDataProvider.emptyHint(KeyMap.preferred()));
        matchesProvider = provider;
        viewMode = ViewMode.MATCHES;
        statusBar.setMatchesMode(true);

        cursorManager.swapProvider(provider, null);
        cursorManager.gotoLine(0, 0);
        updateVisibleLines();
    }

    /**
     * Open the Bookmarks view from the log or the Matches view: remember that view and its viewport, swap
     * in a provider over the session's bookmarks, and put the cursor on the bookmark nearest the event
     * the cursor (or the top row) was on.
     */
    private void enterBookmarks() {
        if (viewMode == ViewMode.BOOKMARKS || isInMoodMode()) return;
        closeDetails();
        if (statusBar.isSearchInputActive()) {
            setSearchInputActive(false);
        }

        CursorManager.ViewState origin = cursorManager.captureViewState();
        MatchesAnchor.Choice anchor = MatchesAnchor.choose(
                viewMode == ViewMode.LOG && cursorManager.hasNavigationHighlight(), searchNavigation.current(),
                origin.navHighlightLine(), cursorEventHit(origin.cursorLine()), origin.cursorLine(),
                firstVisibleSearchHit, origin.pageStart());

        bookmarksOrigin = viewMode;
        bookmarksOriginState = origin;
        BookmarksDataProvider provider = createBookmarksProvider();
        bookmarksProvider = provider;
        viewMode = ViewMode.BOOKMARKS;
        statusBar.setBookmarkCount(bookmarks.size());
        statusBar.setBookmarksMode(true);

        cursorManager.swapProvider(provider, null);
        int row = 0;
        if (provider.rows().rowCount() > 0 && anchor.hit() != null) {
            row = provider.rowOfHit(anchor.hit());
            if (row < 0) {
                row = provider.jump(anchor.hit().splunkTime());
            }
        }
        cursorManager.gotoLine(row, anchor.screenRow());
        cursorManager.ensureCursorAt(row);
        updateVisibleLines();
    }

    private BookmarksDataProvider createBookmarksProvider() {
        return new BookmarksDataProvider(bookmarks.events(), config.getMatchesFirstLineOnly(),
                () -> BookmarksDataProvider.emptyHint(KeyMap.preferred()));
    }

    @Override
    public void showBookmarks() {
        if (viewMode == ViewMode.BOOKMARKS || isInMoodMode()) return;
        enterBookmarks();
    }

    /** Whether the Bookmarks view can be opened now (the menu entry's enabled state). */
    boolean canShowBookmarks() {
        return !isInMoodMode() && viewMode != ViewMode.BOOKMARKS;
    }

    /**
     * Leave the Bookmarks view for the view it was opened from, restoring that viewport exactly. A
     * Matches view that missed search updates meanwhile is brought up to date around the same anchors.
     */
    private void leaveBookmarks() {
        if (viewMode != ViewMode.BOOKMARKS) return;
        closeDetails();
        ViewMode origin = bookmarksOrigin == ViewMode.MATCHES && matchesProvider != null
                ? ViewMode.MATCHES
                : ViewMode.LOG;
        CursorManager.ViewState state = bookmarksOriginState;
        if (origin == ViewMode.MATCHES) {
            restoreMatches(state);
        } else if (state != null && state.provider() == logProvider) {
            cursorManager.swapProvider(logProvider, state);
        } else if (logProvider != null) {
            cursorManager.swapProvider(logProvider, null);
        }
        resetBookmarksState(origin);
        updateVisibleLines();
        // A zero-match search that finished while the bookmarks covered the view has not asked yet.
        offerExtendForNoMatches(currentSearchSession());
    }

    /** Put the Matches view back as it was before the Bookmarks view covered it. */
    private void restoreMatches(CursorManager.ViewState state) {
        SearchSession session = currentSearchSession();
        boolean stale = !matchesProvider.isPlaceholder() && session != null
                && (session.version() > matchesProvider.snapshot().version()
                        || matchesProvider.firstLineOnly() != config.getMatchesFirstLineOnly());
        if (!stale || state == null) {
            cursorManager.swapProvider(matchesProvider, state);
            return;
        }
        Reanchor.View anchors = Reanchor.capture(matchesProvider, state);
        if (matchesProvider.firstLineOnly() != config.getMatchesFirstLineOnly()) {
            matchesProvider = createMatchesProvider(session);
        } else {
            matchesProvider.refresh(provisional.merged(session).snapshot());
        }
        cursorManager.swapProvider(matchesProvider, state);
        cursorManager.reanchor(Reanchor.place(matchesProvider, anchors));
    }

    /**
     * Leave every event-list view for the log, restoring the log viewport held before the first of them
     * was entered. Used when the log itself takes over: showing an event in the log, cancelling the
     * search, a scope change.
     */
    private void leaveEventViews() {
        closeDetails();
        if (viewMode == ViewMode.LOG) return;
        CursorManager.ViewState logState = viewMode == ViewMode.BOOKMARKS && bookmarksOrigin == ViewMode.LOG
                ? bookmarksOriginState
                : savedLogState;
        if (logState != null && logState.provider() == logProvider) {
            cursorManager.swapProvider(logProvider, logState);
        } else if (logProvider != null) {
            cursorManager.swapProvider(logProvider, null);
        }
        clearEventViewState();
    }

    /** Reset the event-list view state only; the caller owns the provider swap. */
    private void clearEventViewState() {
        closeDetails();
        if (viewMode == ViewMode.BOOKMARKS) {
            resetBookmarksState(bookmarksOrigin);
        }
        if (viewMode == ViewMode.MATCHES) {
            viewMode = ViewMode.LOG;
            matchesProvider = null;
            savedLogState = null;
            pendingMatchesAnchor = null;
            statusBar.setMatchesMode(false);
        }
    }

    /** Reset the Bookmarks-view state and fall back to {@code next}; the caller owns the provider swap. */
    private void resetBookmarksState(ViewMode next) {
        viewMode = next;
        bookmarksProvider = null;
        bookmarksOrigin = ViewMode.LOG;
        bookmarksOriginState = null;
        bookmarksCursorTarget = null;
        statusBar.setBookmarksMode(false);
    }

    /**
     * The bookmarks changed (a removal in the Bookmarks view, or a logout clearing them): rebuild the rows
     * and put the view back by event identity so it stays still. After a removal the cursor goes to the
     * event after the removed one, or the one before when it was the last.
     */
    private void onBookmarksChanged() {
        if (viewMode != ViewMode.BOOKMARKS || bookmarksProvider == null) return;
        Reanchor.View anchors = Reanchor.capture(bookmarksProvider, cursorManager.captureViewState());
        if (bookmarksCursorTarget != null) {
            anchors = new Reanchor.View(anchors.top(), anchors.topScreenRow(), bookmarksCursorTarget, null, null,
                    anchors.oldPageStart());
            bookmarksCursorTarget = null;
        }
        bookmarksProvider.refresh(bookmarks.events());
        cursorManager.reanchor(Reanchor.place(bookmarksProvider, anchors));
        statusBar.setBookmarkCount(bookmarks.size());
        updateVisibleLines();
    }

    /**
     * Where the cursor goes when the events of {@code rows} are removed from the list: the first event
     * after them, else the last one before them; null when nothing is left.
     */
    private static Reanchor.Anchor removalTarget(EventListProvider list, List<Integer> rows) {
        EventRows eventRows = list.rows();
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (int row : rows) {
            if (row < 0 || row >= eventRows.rowCount()) continue;
            int event = eventRows.eventAt(row);
            first = Math.min(first, event);
            last = Math.max(last, event);
        }
        if (last < 0) return null;
        int target = last + 1 < eventRows.eventCount() ? last + 1 : first - 1;
        return target < 0 ? null : new Reanchor.Anchor(list.hitAt(eventRows.rowOf(target, 0)), 0);
    }

    @Override
    public boolean copyAllBookmarks() {
        if (viewMode != ViewMode.BOOKMARKS) return false;
        copyBookmarks();
        return true;
    }

    /**
     * Copy every bookmarked event whole, in order, as the Bookmarks view shows them (the Ctrl-C-without-selection
     * path and the ☰ "Copy bookmarks" entry). The copied bookmarks count as saved.
     */
    public void copyBookmarks() {
        List<LoadedLogLine> events = bookmarks.events();
        if (events.isEmpty()) {
            notice(StatusBar.Severity.INFO, "No bookmarks to copy");
            return;
        }
        cursorManager.copyText(BookmarksDataProvider.wholeEventText(events));
        bookmarks.markAllSaved();
        notice(StatusBar.Severity.INFO, BookmarkNotices.copied(events.size()));
    }

    /** Whether there are bookmarks to copy (the menu entry's enabled state). */
    boolean hasBookmarks() {
        return bookmarks.size() > 0;
    }

    /** Copy as…: the format menu just below the cursor row, at the message text; centred when there is no cursor. */
    @Override
    public void showCopyAsMenu() {
        int eventCount = ExportSources.selection(activeProvider(), cursorManager.selectedRows(),
                cursorManager.cursorLine()).size();
        JPopupMenu menu = CopyAsMenu.build(eventCount, this::copyAs);
        Dimension popup = menu.getPreferredSize();
        Dimension viewer = viewPanel.getSize();
        Point corner = copyAsCorner(popup, viewer);
        menu.show(viewPanel, corner.x, corner.y);
    }

    private Point copyAsCorner(Dimension popup, Dimension viewer) {
        int row = cursorManager.cursorLine().map(cursorManager::screenRowOf).orElse(-1);
        if (charSize == null || row < 0 || row >= visibleLines.size()) {
            return CopyAsMenu.centred(popup, viewer);
        }
        DecoratedLogLine cursorRow = visibleLines.get(row);
        int column = cursorRow.getCursorPosition() >= 0 ? cursorRow.getCursorPosition() : cursorRow.getPreambleEnd();
        int textX = LineRenderer.iconSpaceWidth(charSize.height())
                + charSize.width() * Math.max(0, column - horizontalOffset);
        return CopyAsMenu.placement(row * charSize.height(), charSize.height(), textX, popup, viewer);
    }

    /** Copy the selection's events, or the cursor's event, in {@code format}: the Copy as… choice. */
    void copyAs(ExportFormat format) {
        List<ExportEvent> events = ExportSources.selection(activeProvider(), cursorManager.selectedRows(),
                cursorManager.cursorLine());
        if (events.isEmpty()) {
            notice(StatusBar.Severity.INFO, ExportNotices.NOTHING_TO_COPY);
            return;
        }
        cursorManager.copyContents(ExportTransferable.of(events, format, exportOptions()));
        bookmarks.markSaved(ExportSources.selectionEvents(activeProvider(), cursorManager.selectedRows(),
                cursorManager.cursorLine()));
        notice(StatusBar.Severity.INFO, ExportNotices.copied(events.size(), format));
    }

    /** The Export dialog; the sources without events are disabled inside it. */
    void showExportDialog() {
        openExportDialog(null, null);
    }

    /** The Export dialog with {@code preselected} chosen first, e.g. Bookmarks from the Bookmarks view. */
    void showExportDialog(ExportSources.Source preselected) {
        openExportDialog(preselected, null);
    }

    /**
     * The Export dialog with {@code preselected} chosen first (null: the first available source).
     * {@code afterSuccessfulWrite} (may be null) runs once the file was written, never after a failure.
     */
    private void openExportDialog(ExportSources.Source preselected, Runnable afterSuccessfulWrite) {
        ExportDialog dialog = new ExportDialog(SwingUtilities.getWindowAncestor(this), availableExportSources(),
                exportIndex(), preselected, choice -> exportChosen(choice, afterSuccessfulWrite));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** Write the chosen events; once written they count as saved, then {@code afterSuccessfulWrite} runs. */
    void exportChosen(ExportDialog.Choice choice, Runnable afterSuccessfulWrite) {
        List<LoadedLogLine> written = exportedLines(choice.source());
        exports.export(exportEvents(choice.source()), choice.format(), exportOptions(), choice.file(), () -> {
            bookmarks.markSaved(written);
            if (afterSuccessfulWrite != null) {
                afterSuccessfulWrite.run();
            }
        });
    }

    /** The event-level lines behind an export source, for their identities. */
    private List<LoadedLogLine> exportedLines(ExportSources.Source source) {
        return switch (source) {
            case SELECTION -> ExportSources.selectionEvents(activeProvider(), cursorManager.selectedRows(),
                    cursorManager.cursorLine());
            case MATCHES -> {
                SearchSession.Snapshot snapshot = activeSearchSnapshot();
                yield snapshot == null ? List.of() : snapshot.lines().stream().filter(Objects::nonNull).toList();
            }
            case BOOKMARKS -> bookmarks.events();
        };
    }

    private EnumSet<ExportSources.Source> availableExportSources() {
        EnumSet<ExportSources.Source> available = EnumSet.noneOf(ExportSources.Source.class);
        if (cursorManager.cursorLine().isPresent() || !cursorManager.selectedRows().isEmpty()) {
            available.add(ExportSources.Source.SELECTION);
        }
        SearchSession.Snapshot snapshot = activeSearchSnapshot();
        if (snapshot != null && snapshot.lines().stream().anyMatch(Objects::nonNull)) {
            available.add(ExportSources.Source.MATCHES);
        }
        if (bookmarks.size() > 0) {
            available.add(ExportSources.Source.BOOKMARKS);
        }
        return available;
    }

    private List<ExportEvent> exportEvents(ExportSources.Source source) {
        return switch (source) {
            case SELECTION -> ExportSources.selection(activeProvider(), cursorManager.selectedRows(),
                    cursorManager.cursorLine());
            case MATCHES -> ExportSources.matches(activeSearchSnapshot());
            case BOOKMARKS -> ExportSources.bookmarks(bookmarks);
        };
    }

    private ExportOptions exportOptions() {
        return new ExportOptions(getActiveSearchTerm().orElse(null), DateTimeFormatterManager.zoneId());
    }

    /** The index of the open scope, for the suggested file name; null when there is none. */
    private String exportIndex() {
        Scope scope = searchManager != null ? searchManager.scope() : null;
        return scope != null ? scope.indexSelection().fileLabel() : null;
    }

    /** The snapshot of the search whose term is active, or null when no search is active. */
    private SearchSession.Snapshot activeSearchSnapshot() {
        SearchSession session = currentSearchSession();
        return session != null && statusBar.isSearchActive() ? session.snapshot() : null;
    }

    /**
     * Re-anchor the Matches view after a session update: the (event, offset) of the
     * top visible row and of the cursor are remembered, the snapshot is refreshed,
     * and the rows are restored by hit identity so the content under the eye stays
     * still. The page, cursor, mark and highlight are anchored independently.
     */
    private void refreshMatches(SearchSession session) {
        if (matchesProvider == null) return;
        SearchSession.Snapshot shown = provisional.merged(session).snapshot();
        if (shown.version() <= matchesProvider.snapshot().version()) return;
        Reanchor.View anchors = pendingMatchesAnchor != null
                ? pendingMatchesAnchor
                : Reanchor.capture(matchesProvider, cursorManager.captureViewState());
        matchesProvider.refresh(shown);
        cursorManager.reanchor(Reanchor.place(matchesProvider, anchors));
        if (matchesProvider.rows().rowCount() > 0) pendingMatchesAnchor = null;
        if (cursorManager.cursorLine().isEmpty() && matchesProvider.rows().rowCount() > 0) {
            cursorManager.ensureCursorAt(matchesProvider.jump(session.getSearchOrigin()));
        }
        updateVisibleLines();
    }

    /**
     * Rebuild an open Matches view after the whole-events / first-line preference
     * changed. The selected match and the screen position are re-anchored by identity.
     */
    private void applyMatchesPreference() {
        if (viewMode == ViewMode.BOOKMARKS && bookmarksProvider != null
                && bookmarksProvider.firstLineOnly() != config.getMatchesFirstLineOnly()) {
            Reanchor.View anchors = Reanchor.capture(bookmarksProvider, cursorManager.captureViewState());
            bookmarksProvider = createBookmarksProvider();
            cursorManager.swapProvider(bookmarksProvider, cursorManager.captureViewState());
            cursorManager.reanchor(Reanchor.place(bookmarksProvider, anchors));
            updateVisibleLines();
            return;
        }
        if (viewMode != ViewMode.MATCHES || matchesProvider == null) return;
        SearchSession session = currentSearchSession();
        if (session == null || matchesProvider.isPlaceholder()
                || matchesProvider.firstLineOnly() == config.getMatchesFirstLineOnly()) {
            return;
        }

        Reanchor.View anchors = Reanchor.capture(matchesProvider, cursorManager.captureViewState());
        matchesProvider = createMatchesProvider(session);
        cursorManager.swapProvider(matchesProvider, null);
        cursorManager.reanchor(Reanchor.place(matchesProvider, anchors));
        if (cursorManager.cursorLine().isEmpty() && matchesProvider.rows().rowCount() > 0) {
            cursorManager.ensureCursorAt(matchesProvider.jump(session.getSearchOrigin()));
        }
        updateVisibleLines();
    }

    private MatchesDataProvider createMatchesProvider(SearchSession session) {
        MatchesDataProvider provider = new MatchesDataProvider(provisional.merged(session).snapshot(),
                se.grynna.pager.splunk.SearchBoundaries.MIN_MINIMUM_BOUNDARY, searchManager.latestSearchableInstant(),
                config.getMatchesFirstLineOnly());
        provider.setBoundaryExpansionListener(request -> {
            // CursorManager unfreezes a boundary when a scroll tries to pass it.
            if (viewMode == ViewMode.MATCHES && matchesProvider == provider && session == currentSearchSession()) {
                maybeOfferSearchExtend(session, request.type() == se.grynna.pager.splunk.BoundaryType.AFTER
                        ? SearchNavigation.Edge.LATER
                        : SearchNavigation.Edge.EARLIER);
            }
        });
        return provider;
    }

    /** Move the Matches-view cursor to the first row of the next/previous event. */
    private void moveMatchesCursor(boolean forward) {
        if (matchesProvider == null || matchesProvider.isPlaceholder()) return;
        int eventCount = matchesProvider.rows().eventCount();
        if (eventCount == 0) {
            SearchSession empty = currentSearchSession();
            maybeOfferSearchExtend(empty, forward ? SearchNavigation.Edge.LATER : SearchNavigation.Edge.EARLIER,
                    empty != null && empty.hitCount() == 0);
            return;
        }
        var cursorLine = cursorManager.cursorLine();
        if (cursorLine.isEmpty()) return;
        int eventIndex = matchesProvider.rows()
                .eventAt(Math.clamp(cursorLine.get(), 0, matchesProvider.rows().rowCount() - 1));
        int nextEvent = eventIndex + (forward ? 1 : -1);
        if (nextEvent < 0 || nextEvent >= eventCount) {
            maybeOfferSearchExtend(currentSearchSession(),
                    forward ? SearchNavigation.Edge.LATER : SearchNavigation.Edge.EARLIER);
            return;
        }
        cursorManager.ensureCursorAt(matchesProvider.rows().rowOf(nextEvent, 0));
    }

    /** Move the Bookmarks-view cursor to the first row of the next/previous bookmark (F3 / Shift-F3). */
    private void moveBookmarksCursor(boolean forward) {
        if (bookmarksProvider == null || bookmarksProvider.rows().eventCount() == 0) return;
        EventRows rows = bookmarksProvider.rows();
        var cursorLine = cursorManager.cursorLine();
        if (cursorLine.isEmpty()) {
            cursorManager.ensureCursorAt(cursorManager.getPageStart());
            return;
        }
        int event = rows.eventAt(Math.clamp(cursorLine.get(), 0, rows.rowCount() - 1)) + (forward ? 1 : -1);
        if (event >= 0 && event < rows.eventCount()) {
            cursorManager.ensureCursorAt(rows.rowOf(event, 0));
        }
    }

    /** Rebuild the Matches view over a fresh session (Ctrl-F refine from MATCHES). */
    private void replaceMatchesProvider(SearchSession session, Instant explicitOrigin) {
        Reanchor.View anchors = Reanchor.capture(matchesProvider, cursorManager.captureViewState());
        if (explicitOrigin != null) {
            Reanchor.Anchor target = new Reanchor.Anchor(SearchHit.at(session.getSearchOrigin()), 0);
            int screenRow = cursorManager.cursorLine().map(cursorManager::screenRowOf).orElse(0);
            anchors = new Reanchor.View(target, screenRow, target, null, target, 0);
        }
        matchesProvider = createMatchesProvider(session);
        cursorManager.swapProvider(matchesProvider, null);
        cursorManager.reanchor(Reanchor.place(matchesProvider, anchors));
        pendingMatchesAnchor = matchesProvider.rows().rowCount() == 0 ? anchors : null;
        updateVisibleLines();
    }

    /** The event of the cursor line, or null when absent or not a loaded line. */
    private SearchHit cursorEventHit(Integer line) {
        if (line == null) return null;
        try {
            LoadedLogLine logLine = cursorManager.getLogLineAt(line);
            return logLine != null ? SearchHit.from(logLine) : null;
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.warn("cursorEventHit: {}", e.getMessage());
            return null;
        }
    }

    /** The hit of {@code row}, or null when the row is outside the matches rows. */
    private SearchHit matchAt(int row) {
        if (matchesProvider == null || row < 0 || row >= matchesProvider.rows().rowCount()) {
            return null;
        }
        return matchesProvider.hitAt(row);
    }

    @Override
    public void executeSearch(String searchTerm) {
        executeSearch(searchTerm, false);
    }

    @Override
    public void executeSearch(String searchTerm, boolean openMatches) {
        executeSearch(searchTerm, openMatches, null);
    }

    public void executeSearch(String searchTerm, boolean openMatches, Instant explicitOrigin) {
        executeSearch(searchTerm, openMatches, explicitOrigin, false);
    }

    private void executeSearch(String searchTerm, boolean openMatches, Instant explicitOrigin,
            boolean useBrowsingRange) {
        if (searchManager == null) {
            notice(StatusBar.Severity.WARN, "Not connected. Press L to login first.");
            return;
        }

        if (searchTerm == null || searchTerm.isBlank()) {
            return;
        }
        lastSubmittedTerm = searchTerm.strip();

        // A search from the Bookmarks view starts from the view it was opened from.
        leaveBookmarks();

        // A search from the empty Matches view starts from the log it was opened from, and shows its matches.
        if (isEmptyMatchesView()) {
            leaveEventViews();
            openMatches = true;
        }

        // A search submitted from the Matches view (Ctrl-F refine) keeps the view.
        boolean refineFromMatches = viewMode == ViewMode.MATCHES;

        // Same anchor precedence as entering Matches; refine uses its selected event.
        CursorManager.ViewState viewState = cursorManager.captureViewState();
        SearchHit anchor = (refineFromMatches && explicitOrigin == null)
                ? matchAt(viewState.cursorLine() != null ? viewState.cursorLine() : viewState.pageStart())
                : (explicitOrigin == null
                        ? MatchesAnchor.choose(cursorManager.hasNavigationHighlight(), searchNavigation.current(),
                                viewState.navHighlightLine(), cursorEventHit(viewState.cursorLine()),
                                viewState.cursorLine(), firstVisibleSearchHit, viewState.pageStart()).hit()
                        : null);
        Instant origin = explicitOrigin != null
                ? explicitOrigin
                : (anchor != null
                        ? anchor.splunkTime()
                        : statusBar.getFirstVisibleTimestamp().orElse(clock.instant()));

        // A term that refines the active search is answered locally at once (provisional matches);
        // taken before the manager discards the outgoing session.
        SearchRefinement.Provisional refined = SearchRefinement.Provisional.EMPTY;
        SearchSession outgoing = currentSearchSession();
        if (!useBrowsingRange && outgoing != null && statusBar.isSearchActive()
                && SearchRefinement.isRefinement(outgoing.getQuery(), searchTerm)) {
            refined = SearchRefinement.provisional(provisional.merged(outgoing).snapshot(), searchTerm);
        }

        // Detach the outgoing search before anything else, so none of its queued
        // updates can land on the new one's status.
        searchUpdates.unbind();
        provisional.clear();

        // SearchManager cancels any previous search and becomes the owner of the new one
        Searcher searcher = explicitOrigin == null || useBrowsingRange
                ? searchManager.search(searchTerm, origin)
                : searchManager.searchAround(searchTerm, origin);
        searchNavigation.clear();
        clearFirstMatchJump();
        laterRangeExpansions = 0;
        earlierRangeExpansions = 0;
        searchExtendClockStart = clock.instant();
        statusBar.clearSearchExtendPrompt();
        statusBar.clearSearchRecentrePrompt();

        // Transition from SEARCH_INPUT to SEARCH_ACTIVE — shows the query, non-editable, F3 navigates.
        // Done before the search starts so its first update renders on the new summary.
        statusBar.setSearchActive(true, searchTerm);

        // Listen for incremental updates on the EDT; only this session's updates are delivered
        searchUpdates.bind(searcher.getSession(), this::onSearchSessionUpdate);
        if (refined.size() > 0) {
            provisional.bind(searcher.getSession(), refined);
            publishSearchCount(searcher.getSession());
        }

        // Jump to the first match at/after the origin as soon as that is provable.
        // Not for Matches, which anchors itself, or a user-set range, which stays at its first event.
        if (viewMode == ViewMode.LOG && !openMatches && !useBrowsingRange) {
            armFirstMatchJump(searcher.getSession(), searcher.getSession().getSearchOrigin());
        }

        // Start streaming results from Splunk in background
        searcher.start();

        if (refineFromMatches) {
            // Ctrl-F refine from the Matches view: stay in Matches, fresh provider over the new session.
            replaceMatchesProvider(searcher.getSession(), explicitOrigin);
        } else if (openMatches) {
            // Shift-Enter, or Enter in the empty Matches view: open the Matches view.
            enterMatches();
        }

        log.info("Search started for '{}'", searchTerm);
    }

    /**
     * Called on EDT when the SearchSession has new hits or state changes.
     * Updates the status bar hit count and shows completion notification.
     */
    // Identity comparison is intentional: a cancelled or replaced search
    // session is a different SearchSession instance; only updates for the exact
    // session now on screen may rewrite its status.
    @SuppressWarnings("ReferenceEquality")
    private void onSearchSessionUpdate(SearchSession session) {
        // A cancelled or replaced search must not rewrite the status of the one
        // now on screen. The binding already dropped detached sessions;
        // this also guards against the manager having moved on.
        if (!searchUpdates.isBound(session) || session != currentSearchSession() || session.isDiscarded()
                || !statusBar.isSearchActive()) {
            return;
        }
        // A match may have arrived past the edge F3 was stuck on.
        searchNavigation.releaseIfMovedPast(session);
        statusBar.setTextSearchPhase(textSearchPhase(session.getState()));
        statusBar.setSearchTruncation(session.isTruncated(SearchSession.Side.EARLIER),
                session.isTruncated(SearchSession.Side.LATER), session.snapshot().tooManyMatchesWithinSecond());
        SearchRefinement.Merged shown = provisional.merged(session);
        statusBar.setSearchProvisional(shown.provisionalCount() > 0);
        publishSearchPosition(session);
        updateMinimap();
        statusBar.updateSearchHitCount(shown.snapshot().size(), session.getState() != State.RUNNING,
                session.windowStart(), session.windowEnd());

        if (viewMode == ViewMode.MATCHES) {
            // Refresh the matches rows and keep the anchor still.
            refreshMatches(session);
        } else {
            // Only after the status reflects the new hits: the jump publishes "n of m".
            jumpToFirstMatch(session);
        }

        if (session.getState() != State.RUNNING) {
            // The session has answered everything it will: only confirmed matches remain.
            provisional.clear();
        }

        switch (session.getState()) {
            case COMPLETED -> {
                // No toast for zero matches: the centre says "No matches · searched …", the prompt may ask.
                if (session.hitCount() == 0) {
                    offerExtendForNoMatches(session);
                }
            }
            case ERROR -> {
                if (endNotices.first(session)) {
                    failureToast("Search failed: " + session.getErrorMessage());
                }
            }
            case CANCELLED, RUNNING -> {
                /* silent */
            }
        }
    }

    /** The zero-match offer to make for {@code session}: earlier first, later when earlier has nothing to ask. */
    private SearchRangeOffer.Decision zeroMatchDecision(SearchSession session) {
        for (SearchNavigation.Edge edge : new SearchNavigation.Edge[]{SearchNavigation.Edge.EARLIER,
                SearchNavigation.Edge.LATER}) {
            SearchRangeOffer.Decision decision = decideSearchExtend(session, edge);
            if (decision.ask()) {
                return decision;
            }
        }
        return null;
    }

    @SuppressWarnings("ReferenceEquality")
    private boolean zeroMatchWindowAlreadyOffered(SearchSession session) {
        return session == noMatchesOfferedSession && Objects.equals(session.windowStart(), noMatchesOfferedStart)
                && Objects.equals(session.windowEnd(), noMatchesOfferedEnd);
    }

    private boolean zeroMatchOfferApplies(SearchSession session) {
        return session != null && searchManager != null && viewMode != ViewMode.BOOKMARKS
                && statusBar.isSearchActive()
                && !session.isDiscarded() && session.getState() == State.COMPLETED && session.hitCount() == 0;
    }

    /** A finished empty search whose window has not been offered for widening yet (F3 then asks, not re-prompts). */
    private boolean zeroMatchOfferPending(SearchSession session) {
        return zeroMatchOfferApplies(session) && !zeroMatchWindowAlreadyOffered(session)
                && zeroMatchDecision(session) != null;
    }

    /**
     * A finished search that found nothing in its window widens it at once, since F3 has nothing to step to and
     * so can never reach the usual end-of-matches question. The search covers the log's browsing range up to
     * its boundary; there it does what the log does at its boundary: a search that is still young
     * ({@link se.grynna.pager.splunk.ExpansionAutoApprove}) extends itself, an older one asks "No matches for
     * "x" · search" with the step button "1h earlier (11:00:00)?". Earlier first, later when earlier is at the minimum
     * boundary; nothing when the window covers all searchable time. Either way the same session continues over the new slice, and
     * an empty result there is offered again (bigger step). Asked once per covered window, so repeated updates of
     * the same finished state stay quiet and N ends the offers.
     */
    private void offerExtendForNoMatches(SearchSession session) {
        if (!zeroMatchOfferApplies(session) || statusBar.isSearchInputActive() || timePanel != null
                || zeroMatchWindowAlreadyOffered(session)) {
            return;
        }
        if (statusBar.hasSearchExtendPrompt() || statusBar.hasSearchRecentrePrompt()
                || currentBoundaryPrompt.get() != null) {
            return;
        }
        noMatchesOfferedSession = session;
        noMatchesOfferedStart = session.windowStart();
        noMatchesOfferedEnd = session.windowEnd();
        SearchRangeOffer.Decision decision = zeroMatchDecision(session);
        if (decision == null) {
            return;
        }
        if (decision.kind() == SearchRangeOffer.Kind.EXTEND
                && se.grynna.pager.splunk.ExpansionAutoApprove.shortSince(searchExtendClockStart, clock.instant())) {
            log.info("Search for '{}' found nothing yet: extending to {} without asking", session.getQuery(),
                    decision.suggested());
            startSearchExtension(session,
                    new BoundaryRequest(decision.direction(), decision.suggested(), (type, accepted, boundary) -> {}));
            return;
        }
        maybeOfferSearchExtend(session, decision.direction() == se.grynna.pager.splunk.BoundaryType.AFTER
                ? SearchNavigation.Edge.LATER
                : SearchNavigation.Edge.EARLIER, true);
    }

    @Override
    public void searchNext() {
        if (isInMoodMode()) return;

        if (viewMode == ViewMode.MATCHES) {
            moveMatchesCursor(true);
            return;
        }
        if (viewMode == ViewMode.BOOKMARKS) {
            moveBookmarksCursor(true);
            return;
        }

        if (statusBar.isSearchActive() && currentSearchSession() != null) {
            navigateSearch(true);
        } else {
            setSearchInputActive(true);
        }
    }

    @Override
    public void searchPrevious() {
        if (isInMoodMode()) return;

        if (viewMode == ViewMode.MATCHES) {
            moveMatchesCursor(false);
            return;
        }
        if (viewMode == ViewMode.BOOKMARKS) {
            moveBookmarksCursor(false);
            return;
        }

        if (statusBar.isSearchActive() && currentSearchSession() != null) {
            navigateSearch(false);
        } else {
            setSearchInputActive(true);
        }
    }

    @Override
    public boolean searchEndedWithoutMatches() {
        if (isInMoodMode() || viewMode == ViewMode.BOOKMARKS || !statusBar.isSearchActive()
                || isYesNoPromptShowing()) {
            return false;
        }
        SearchSession session = currentSearchSession();
        // While widening is still to be offered, F3 asks that question instead of reopening the prompt.
        return session != null && session.getState() != State.RUNNING && session.hitCount() == 0
                && !zeroMatchOfferPending(session);
    }

    /**
     * Navigate to the next or previous search hit.
     * <p>
     * Advances from the selected hit while its navigation highlight is active
     * Golden-ratio centering puts that hit below the viewport top, so the
     * next step must not anchor on {@link #firstVisibleSearchHit}. Manual
     * scrolling or cursor movement clears the highlight; the following step
     * anchors on the current viewport event. Navigation does not wrap. At either
     * end the view stays put and the search status says there are no more
     * matches that way — not "no more logs", which already means the time range
     * should be extended.
     */
    private void navigateSearch(boolean forward) {
        // Pressing F3 means the user chose where to go; the automatic jump is done.
        clearFirstMatchJump();
        SearchSession session = currentSearchSession();
        if (session == null || session.hitCount() == 0) {
            // Status-bar hint, not a toast: repeated F3 presses must not stack notices.
            if (session != null) {
                statusBar.setSearchPosition(null, 0, session.getState() == State.RUNNING
                        ? "searching… no matches yet"
                        : "no matches");
                // A finished empty search whose widening was never offered (something was in the way) asks now.
                offerExtendForNoMatches(session);
            }
            return;
        }
        if (statusBar.hasSearchExtendPrompt()) {
            return;
        }

        SearchNavigation.Step step = searchNavigation.advance(session, cursorManager.hasNavigationHighlight(),
                firstVisibleSearchHit, forward);
        if (step.hit() != null) {
            gotoSearchHit(step.hit());
        }
        publishSearchPosition(session);
        maybeOfferSearchExtend(session, step.edge());
    }

    /**
     * A finished search that still has time outside the window it queried asks
     * to widen that window, the same way walking off the loaded log asks to
     * extend the time range. Y searches only the new slice and keeps the matches
     * already found.
     */
    private void maybeOfferSearchExtend(SearchSession session, SearchNavigation.Edge edge) {
        maybeOfferSearchExtend(session, edge, false);
    }

    private SearchRangeOffer.Decision decideSearchExtend(SearchSession session, SearchNavigation.Edge edge) {
        int used = edge == SearchNavigation.Edge.LATER ? laterRangeExpansions : earlierRangeExpansions;
        return viewMode == ViewMode.MATCHES
                ? SearchRangeOffer.matchesEdge(edge, session.snapshot(),
                        se.grynna.pager.splunk.SearchBoundaries.MIN_MINIMUM_BOUNDARY,
                        searchManager.latestSearchableInstant(), used + 1, searchNavigation.declinedExtend())
                : SearchRangeOffer.decide(edge, session.getState(), session.windowStart(), session.windowEnd(),
                        se.grynna.pager.splunk.SearchBoundaries.MIN_MINIMUM_BOUNDARY,
                        searchManager.latestSearchableInstant(), used + 1, searchNavigation.declinedExtend(),
                        session.isTruncated(SearchSession.Side.EARLIER),
                        session.isTruncated(SearchSession.Side.LATER));
    }

    /** @return whether a prompt was shown; {@code noMatches} words it for a search that found nothing */
    private boolean maybeOfferSearchExtend(SearchSession session, SearchNavigation.Edge edge, boolean noMatches) {
        if (session == null || edge == SearchNavigation.Edge.NONE || searchManager == null
                || statusBar.hasSearchExtendPrompt() || statusBar.hasSearchRecentrePrompt()) {
            return false;
        }
        if (currentBoundaryPrompt.get() != null) {
            return false;
        }
        SearchRangeOffer.Decision decision = decideSearchExtend(session, edge);
        if (!decision.ask()) {
            return false;
        }
        if (decision.kind() == SearchRangeOffer.Kind.RECENTRE) {
            statusBar.showSearchRecentrePrompt(
                    new BoundaryRequest(decision.direction(), decision.suggested(), (type, accepted, boundary) -> {}));
            return true;
        }
        String direction = decision.direction() == se.grynna.pager.splunk.BoundaryType.AFTER ? "later" : "earlier";
        statusBar.showSearchExtendPrompt(
                new BoundaryRequest(decision.direction(), decision.suggested(), (type, accepted, boundary) -> {}),
                direction, noMatches ? session.getQuery() : null);
        return true;
    }

    /**
     * Stop the Splunk query. Matches already found stay, and search mode stays open.
     */
    public void stopTextSearch() {
        if (searchManager != null) {
            searchManager.stopSearch();
        }
    }

    private static SearchStatusText.Phase textSearchPhase(State state) {
        return switch (state) {
            case RUNNING -> SearchStatusText.Phase.RUNNING;
            case CANCELLED -> SearchStatusText.Phase.STOPPED;
            case ERROR -> SearchStatusText.Phase.FAILED;
            case COMPLETED -> SearchStatusText.Phase.COMPLETE;
        };
    }

    /** Whether provisional (locally computed) matches are currently held; a test seam. */
    boolean hasProvisionalMatches() {
        return provisional.isActive();
    }

    /** Show the count of {@code session} at once, with the {@code ~} while provisional matches are included. */
    private void publishSearchCount(SearchSession session) {
        SearchRefinement.Merged shown = provisional.merged(session);
        statusBar.setSearchProvisional(shown.provisionalCount() > 0);
        statusBar.updateSearchHitCount(shown.snapshot().size(), session.getState() != State.RUNNING,
                session.windowStart(), session.windowEnd());
    }

    /**
     * Show "3 of 12", and when F3 cannot move, why. The note lives in the status
     * bar so it stays visible and a repeated keypress does not stack toasts over
     * the log.
     */
    private void publishSearchPosition(SearchSession session) {
        boolean atEdge = searchNavigation.stuckEdge() != SearchNavigation.Edge.NONE;
        if (!atEdge && !cursorManager.hasNavigationHighlight()) {
            statusBar.clearSearchPosition();
            return;
        }
        SearchHit shown = searchNavigation.current();
        Integer matchNumber = null;
        if (shown != null) {
            int index = session.indexOf(shown);
            if (index >= 0) {
                matchNumber = index + 1;
            }
        }
        statusBar.setSearchPosition(matchNumber, provisional.merged(session).snapshot().size(),
                searchNavigation.edgeNote(session));
    }

    private void gotoSearchHit(SearchHit hit) {
        if (cursorManager != null) {
            cursorManager.gotoSearchHit(hit);
        }
    }

    private void armFirstMatchJump(SearchSession session, Instant origin) {
        pendingFirstMatchSession = session;
        pendingFirstMatchOrigin = origin;
        pendingFirstMatchAnchor = firstVisibleSearchHit;
    }

    private void clearFirstMatchJump() {
        pendingFirstMatchSession = null;
        pendingFirstMatchOrigin = null;
        pendingFirstMatchAnchor = null;
    }

    /**
     * Jump to the first match at/after the origin once {@link FirstMatchJump}
     * can prove it is the nearest one. This is the same move F3 makes, so the
     * hit is centered, highlighted and becomes the anchor the next F3 advances
     * from.
     */
    // Identity comparison is intentional: only the session armed at submit time
    // may consume the pending jump.
    @SuppressWarnings("ReferenceEquality")
    private void jumpToFirstMatch(SearchSession session) {
        if (viewMode != ViewMode.LOG) {
            return;
        }
        if (pendingFirstMatchSession != session || pendingFirstMatchOrigin == null) {
            return;
        }
        Optional<SearchHit> first = FirstMatchJump.decide(session.snapshot(), pendingFirstMatchOrigin);
        if (first.isEmpty()) {
            return;
        }
        SearchHit hit = first.get();
        clearFirstMatchJump();
        searchNavigation.select(hit);
        gotoSearchHit(hit);
        publishSearchPosition(session);
    }

    // ------------------------------------------------------------------
    // Scope changes. EDT only.
    // ------------------------------------------------------------------

    /**
     * Narrow: fold the active search's term into the scope, so the log itself now holds
     * only matching events. Needs an active search with at least one match; otherwise
     * nothing changes. The log reloads at the selected match (the F3 match, the Matches
     * cursor, or the first visible match) and keeps that term highlighted.
     */
    public void narrowScopeToSearch() {
        Scope scope = scopeOrWarn();
        SearchSession session = scope != null ? currentSearchSession() : null;
        if (scope == null) return;
        if (session == null || !statusBar.isSearchActive()) {
            notice(StatusBar.Severity.WARN, "Nothing to narrow: start a search first");
            return;
        }
        String term = session.getQuery();
        if (session.hitCount() == 0 || term == null || term.isBlank()) {
            notice(StatusBar.Severity.WARN, "Nothing would be left");
            return;
        }
        Scope narrowed = scope.withNarrow(term);
        if (narrowed.equals(scope)) {
            notice(StatusBar.Severity.INFO, "Already narrowed to \"%s\"".formatted(term));
            return;
        }
        SearchHit hit = ScopeChange.chooseNarrowHit(session.snapshot(), narrowCandidates(session),
                session.getSearchOrigin());
        confirmBookmarksThenChangeScope(scope, narrowed,
                () -> applyScope(narrowed, hit, hit != null ? hit.splunkTime() : session.getSearchOrigin(), true));
    }

    /**
     * Exclude: drop the events matching the active search's term from the scope. The
     * term must be plain (a wildcard-only term would drop everything). The log reloads
     * at the time of its top row.
     */
    public void excludeSearchFromScope() {
        Scope scope = scopeOrWarn();
        SearchSession session = scope != null ? currentSearchSession() : null;
        if (scope == null) return;
        if (session == null || !statusBar.isSearchActive()) {
            notice(StatusBar.Severity.WARN, "Nothing to exclude: start a search first");
            return;
        }
        String term = session.getQuery();
        if (!Scope.isExcludable(term)) {
            notice(StatusBar.Severity.WARN, "Cannot exclude \"%s\": use a plain term".formatted(term));
            return;
        }
        if (session.hitCount() == 0 && session.getState() == State.COMPLETED) {
            notice(StatusBar.Severity.WARN, "Nothing to exclude: no matches");
            return;
        }
        Scope excluded = scope.withExclude(term);
        if (excluded.equals(scope)) {
            notice(StatusBar.Severity.INFO, "Already excluded \"%s\"".formatted(term));
            return;
        }
        confirmBookmarksThenChangeScope(scope, excluded, () -> {
            // Back in the log first: its top row is the position to keep.
            leaveEventViews();
            applyScope(excluded, null, topRowTime(), false);
        });
    }

    /** Remove a narrowing or excluded term from the scope; the log reloads at its top row time. */
    public void removeScopeTerm(String term) {
        Scope scope = scopeOrWarn();
        if (scope == null) return;
        if (term == null || !scope.hasTerm(term)) {
            return;
        }
        leaveEventViews();
        applyScope(scope.without(term), null, topRowTime(), false);
    }

    /**
     * Run {@code apply}, which makes {@code next} the scope, once bookmarks that would fall outside it are
     * dealt with: with none outside it runs at once; otherwise a status-bar Y/N question is asked and
     * Y applies the scope and drops those bookmarks, N changes nothing. Widening changes need no check.
     */
    private void confirmBookmarksThenChangeScope(Scope current, Scope next, Runnable apply) {
        List<LoadedLogLine> outside = ScopeMembership.outsideAfterChange(current, next, bookmarks.events());
        if (outside.isEmpty()) {
            apply.run();
            return;
        }
        if (isYesNoPromptShowing()) {
            notice(StatusBar.Severity.WARN, "Answer the open question first");
            return;
        }
        pendingScopeChange = () -> {
            List<SearchHit> drop = ScopeMembership.outsideAfterChange(current, next, bookmarks.events()).stream()
                    .map(SearchHit::from).toList();
            apply.run();
            int removed = bookmarks.removeAll(drop);
            if (removed > 0) {
                notice(StatusBar.Severity.INFO, BookmarkNotices.removed(removed));
            }
        };
        statusBar.showScopeConfirmPrompt(BookmarkNotices.outsidePrompt(outside.size()));
        notice(StatusBar.Severity.INFO, BookmarkNotices.keepHint());
        viewPanel.requestFocusInWindow();
    }

    private boolean finishScopeQuestion(boolean accepted) {
        Runnable change = pendingScopeChange;
        dropScopeQuestion();
        if (change == null) {
            return false;
        }
        if (accepted) {
            change.run();
        } else {
            notice(StatusBar.Severity.INFO, "Scope unchanged");
        }
        return true;
    }

    /** Forget the open scope question (a logout or a new scope makes its answer meaningless). */
    private void dropScopeQuestion() {
        pendingScopeChange = null;
        statusBar.clearScopeConfirmPrompt();
    }

    /** The index panel's check and drop for bookmarks outside the scope it is about to apply. */
    BookmarkScopeGuard bookmarkScopeGuard() {
        return new BookmarkScopeGuard() {
            @Override
            public int outside(Scope newScope) {
                return ScopeMembership.outsideAfterChange(currentScope(), newScope, bookmarks.events()).size();
            }

            @Override
            public void drop(Scope newScope) {
                List<SearchHit> hits = ScopeMembership.outsideAfterChange(currentScope(), newScope, bookmarks.events())
                        .stream().map(SearchHit::from).toList();
                int removed = bookmarks.removeAll(hits);
                if (removed > 0) {
                    notice(StatusBar.Severity.INFO, BookmarkNotices.removed(removed));
                }
            }
        };
    }

    private Scope currentScope() {
        return searchManager != null ? searchManager.scope() : null;
    }

    private Scope scopeOrWarn() {
        Scope scope = searchManager != null ? searchManager.scope() : null;
        if (scope == null) {
            notice(StatusBar.Severity.WARN, "Not connected. Press L to login first.");
        }
        return scope;
    }

    /**
     * Failures only (search, load, login, storage): a toast that can be missed while the user is
     * elsewhere. State and hints go through {@link #notice}.
     */
    private static void failureToast(String message) {
        Notifications.getInstance().show(Notifications.Type.ERROR, Notifications.Location.TOP_CENTER, message);
    }

    /** Splunk refused a search with HTTP 503: its concurrent-search quota is reached. */
    private static void busyToast(String reason, boolean gaveUp) {
        String detail = StringUtils.abbreviate(reason, 200);
        if (gaveUp) {
            Notifications.getInstance().show(Notifications.Type.ERROR, Notifications.Location.TOP_CENTER, 8000,
                    "Splunk is still busy, gave up. Try again later: " + detail);
        } else {
            Notifications.getInstance().show(Notifications.Type.WARNING, Notifications.Location.TOP_CENTER, 6000,
                    "Splunk search quota reached, retrying: " + detail);
        }
    }

    /** State or hint about the current search, scope or view: a transient status-bar notice. EDT only. */
    private void notice(StatusBar.Severity severity, String message) {
        statusBar.showNotice(message, severity);
    }

    /** Matches to land on after narrowing, in priority order. */
    private List<SearchHit> narrowCandidates(SearchSession session) {
        List<SearchHit> candidates = new ArrayList<>();
        if (viewMode == ViewMode.MATCHES) {
            Integer cursorLine = cursorManager.cursorLine().orElse(null);
            candidates.add(matchAt(cursorLine != null ? cursorLine : cursorManager.getPageStart()));
        } else {
            if (cursorManager.hasNavigationHighlight()) {
                candidates.add(searchNavigation.current());
            }
            candidates.add(cursorEventHit(cursorManager.cursorLine().orElse(null)));
            SearchSession.Snapshot snapshot = session.snapshot();
            for (DecoratedLogLine line : visibleLines) {
                if (line.getLogLine() instanceof LoadedLogLine loaded) {
                    SearchHit hit = SearchHit.from(loaded);
                    if (snapshot.indexOf(hit) >= 0) {
                        candidates.add(hit);
                        break;
                    }
                }
            }
        }
        return candidates;
    }

    /** Splunk time of the first loaded line on screen, falling back to the status bar, then now. */
    private Instant topRowTime() {
        try {
            for (DecoratedLogLine line : cursorManager.getVisibleLines()) {
                if (line.getLogLine() instanceof LoadedLogLine loaded) {
                    return loaded.getSplunkTime();
                }
            }
        } catch (TemporarySearchException | SearchException | StorageException | RuntimeException e) {
            log.warn("topRowTime: {}", e.getMessage());
        }
        return statusBar.getFirstVisibleTimestamp().orElse(clock.instant());
    }

    /**
     * Make {@code newScope} the scope: this is a provider generation change. Leaves the
     * Matches view, ends the search, saves the text terms with the index's field filters,
     * has the manager drop the loaded log (supersedes in-flight loads, clears the
     * segment cache) and then reloads around {@code targetHit} (exact identity) or
     * {@code target} (time). A narrowed position is highlighted like a jump.
     */
    private void applyScope(Scope newScope, SearchHit targetHit, Instant target, boolean highlightTarget) {
        dropScopeQuestion();
        leaveEventViews();
        resetSearchUi();
        saveScopeTerms(newScope);
        // Cancels the search, supersedes the loads and clears the cache; the reload below jumps.
        searchManager.setScope(newScope);
        refreshScopeChips();
        reloadLogAt(targetHit, target, highlightTarget);
    }

    /**
     * The reload after a provider generation change (a scope or a message-fields change): show the log provider,
     * jump to {@code targetHit} (exact identity) or to {@code target} (time) and place the page there, highlighted
     * like a jump or just at the top.
     *
     * @return the row jumped to, or -1 when the jump failed
     */
    private int reloadLogAt(SearchHit targetHit, Instant target, boolean highlightTarget) {
        int line = -1;
        try {
            cursorManager.setLogProvider(logProvider);
            Instant latest = searchManager.latestSearchableInstant();
            if (targetHit != null && !targetHit.splunkTime().isAfter(latest)) {
                line = logProvider.jump(targetHit);
            } else {
                line = logProvider.jump(ScopeChange.clampToLatest(target, latest));
            }
            if (highlightTarget) {
                cursorManager.gotoLine(line);
            } else {
                cursorManager.setPageStart(line);
            }
        } catch (TemporarySearchException | SearchException | StorageException | RuntimeException e) {
            log.error("reloadLogAt: reload failed", e);
            failureToast(ExceptionMessageFormatter.formatException(e));
            line = -1;
        }
        updateVisibleLines();
        return line;
    }

    /** Mirror the manager's narrowing and excluded terms into the status row chips. EDT only. */
    private void refreshScopeChips() {
        Scope scope = searchManager != null ? searchManager.scope() : null;
        if (scope == null) {
            statusBar.setScopeTerms(List.of(), List.of());
            return;
        }
        statusBar.setScopeTerms(scope.narrow(), scope.exclude());
    }

    /** Persist the scope's text terms like field filters; the write happens in the background. */
    private void saveScopeTerms(Scope scope) {
        if (scope.index() == null || scope.index().isBlank()) {
            return;
        }
        String index = scope.index();
        searchManager.storeScopeTerms(index, new ScopeTerms(scope.narrow(), scope.exclude()))
                .whenComplete((ignored, e) -> {
                    if (e != null) {
                        log.error("saveScopeTerms: Failed for index '{}'", index, e);
                        invokeLater(() -> failureToast(
                                "Failed to save scope terms: " + ExceptionMessageFormatter.formatException(e)));
                    }
                });
    }

    @Override
    public void showPreferences() {
        showPreferencesOverlay();
    }

    /**
     * Toggles the keyboard help overlay. Unlike the preferences overlay, this one
     * does not disable the view panel or steal focus: it stays driven by the
     * global key bindings so Esc/Ctrl-G close it through the cancel chain and
     * {@code h} toggles it back off.
     */
    @Override
    public void showKeyboardHelp() {
        if (helpOverlay != null) {
            closeHelpOverlay();
        } else {
            showHelpOverlay();
        }
    }

    /**
     * Closes the keyboard help overlay if it is shown, as the first step of the
     * cancel chain (the view is re-enabled and repainted when the overlay goes).
     */
    @Override
    public boolean closeKeyboardHelp() {
        if (helpOverlay == null) {
            return false;
        }
        closeHelpOverlay();
        return true;
    }

    private void showHelpOverlay() {
        if (!isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::showHelpOverlay);
            return;
        }

        // Remove any existing help overlay before adding the fresh one.
        if (helpOverlay != null) {
            layeredPane.remove(helpOverlay);
        }

        // Semi-transparent overlay that blocks mouse input; keyboard stays on the
        // view panel so the cancel chain and `h` toggle keep working.
        helpOverlay = new JPanel() {
            {
                enableEvents(java.awt.AWTEvent.MOUSE_EVENT_MASK | java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK);
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f));
                    g2d.setColor(new Color(20, 20, 20));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                } finally {
                    g2d.dispose();
                }
            }

            @Override
            protected void processMouseEvent(java.awt.event.MouseEvent e) {
                super.processMouseEvent(e);
                e.consume();
            }

            @Override
            protected void processMouseMotionEvent(java.awt.event.MouseEvent e) {
                super.processMouseMotionEvent(e);
                e.consume();
            }
        };

        helpOverlay.setOpaque(false);
        helpOverlay.setLayout(new GridBagLayout());

        HelpOverlay content = new HelpOverlay(KeyMap.preferred(), inputHandler.boundActions());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        helpOverlay.add(content, gbc);

        helpOverlay.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
        layeredPane.add(helpOverlay, JLayeredPane.MODAL_LAYER);

        validate();
        repaint();
    }

    private void closeHelpOverlay() {
        if (helpOverlay != null) {
            layeredPane.remove(helpOverlay);
            helpOverlay = null;
            validate();
            repaint();
            viewPanel.requestFocusInWindow();
        }
    }

    /**
     * Shows the preferences overlay for configuring date/time display settings.
     * Uses the same overlay pattern as the login panel for consistent UX.
     */
    private void showPreferencesOverlay() {
        if (!isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::showPreferencesOverlay);
            return;
        }

        // Remove existing preferences overlay if present
        if (preferencesOverlay != null) {
            layeredPane.remove(preferencesOverlay);
        }

        // Disable underlying components to prevent interaction
        if (viewPanel != null) {
            viewPanel.setEnabled(false);
        }

        // Create semi-transparent overlay with event blocking
        preferencesOverlay = new JPanel() {
            {
                // Enable event processing for all input types
                enableEvents(java.awt.AWTEvent.MOUSE_EVENT_MASK | java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
                        | java.awt.AWTEvent.KEY_EVENT_MASK);
                // Make focusable so it can capture key events
                setFocusable(true);
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.80f));
                    g2d.setColor(new Color(20, 20, 20));
                    g2d.fillRect(0, 0, getWidth(), getHeight());
                } finally {
                    g2d.dispose();
                }
            }

            @Override
            protected void processMouseEvent(java.awt.event.MouseEvent e) {
                super.processMouseEvent(e);
                e.consume();
            }

            @Override
            protected void processMouseMotionEvent(java.awt.event.MouseEvent e) {
                super.processMouseMotionEvent(e);
                e.consume();
            }

            @Override
            protected void processKeyEvent(java.awt.event.KeyEvent e) {
                // Let the preferences panel handle its own keys, consume others
                super.processKeyEvent(e);
            }
        };

        preferencesOverlay.setOpaque(false);
        preferencesOverlay.setLayout(new GridBagLayout());

        // Create preferences panel with callback to close overlay
        PreferencesPanel preferencesPanel = new PreferencesPanel(config, saved -> {
            closePreferencesOverlay();
            if (saved) {
                // Notify user and update display settings
                notice(StatusBar.Severity.INFO, "Preferences saved.");
                applyMatchesPreference();
                updateMinimap();
                statusBar.updateState();
            }
        });

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        preferencesOverlay.add(preferencesPanel, gbc);

        preferencesOverlay.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
        layeredPane.add(preferencesOverlay, JLayeredPane.MODAL_LAYER);

        validate();
        repaint();

        // Focus the preferences panel so it receives key events
        preferencesPanel.requestFocusInWindow();
    }

    /**
     * Closes the preferences overlay and re-enables the underlying view.
     */
    private void closePreferencesOverlay() {
        if (preferencesOverlay != null) {
            layeredPane.remove(preferencesOverlay);
            preferencesOverlay = null;

            // Re-enable underlying components
            if (viewPanel != null) {
                viewPanel.setEnabled(true);
            }

            validate();
            repaint();
            viewPanel.requestFocusInWindow();
        }
    }

    @Override
    public CharacterSize getCharacterSize() {
        return charSize;
    }

    public void onLogUpdated() {
        // Update search spinner in status bar
        SwingUtilities.invokeLater(this::updateVisibleLines);
    }

    @Override
    public void onScrollLockChanged(boolean scrollLock) {
        statusBar.updateState();
    }

    public InputState getState() {
        return cursorManager.getState();
    }

    public record CharacterSize(int width, int height, int ascent, int descent) {}

    // Inner view panel for rendering
    private class ViewPanel extends JPanel {
        ViewPanel() {
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            String tooltip = activityTooltipAt(event.getX(), event.getY());
            return tooltip != null ? tooltip : timeColumnTooltipAt(event.getX(), event.getY());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2d = (Graphics2D) g;

            if (charSize == null) {
                var metrics = Fontalizer.getFontMetrics(g2d);

                // Use consistent character width measurement with 'M' for monospace
                var charWidth = metrics.charWidth('W');
                var ascent = metrics.getAscent();
                var descent = metrics.getDescent();

                // Includes leading, but we skip that when rendering
                // mainly to get fully selected lines without gaps.
                var reportedHeight = metrics.getHeight();
                var charHeight = ascent + descent;
                charSize = new CharacterSize(charWidth, charHeight, ascent, descent);

                log.info("Font metrics - charWidth: {} charHeight: {}, ascent: {}, descent: {}, reportedHeight: {}",
                        charWidth, charHeight, ascent, descent, reportedHeight);
                updateLinesPerPage();
            }

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean unloadedLines = false;
            int y = 0;

            renderer.setCharSize(charSize);
            renderer.setViewKind(switch (viewMode) {
                case LOG -> LineRenderer.ViewKind.LOG;
                case MATCHES -> LineRenderer.ViewKind.MATCHES;
                case BOOKMARKS -> LineRenderer.ViewKind.BOOKMARKS;
            });
            renderer.paintBackground(g2d, getWidth(), getHeight());
            DecoratedLogLine currentLine = null;
            DecoratedLogLine prevLine = null;
            for (DecoratedLogLine nextLine : visibleLines) {

                if (currentLine == null) {
                    // advance to next line
                    currentLine = nextLine;
                    continue;
                }

                unloadedLines |= currentLine.getLogLine() instanceof Placeholder;

                renderer.render(g2d, currentLine, nextLine, prevLine, horizontalOffset, y);

                y += charSize.height;
                prevLine = currentLine;
                currentLine = nextLine;
            }
            // final line
            renderer.render(g2d, currentLine, null, prevLine, horizontalOffset, y);

            // Draw the scroll bar
            scrollBar.paintScrollBar(g2d);

            // start/stop spinner timer if unloaded lines
            spinnerAnimation.loadingIndicator(unloadedLines);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1024, 768);
        }
    }

    @Override
    public void setSearchInputActive(boolean active) {
        if (active && isInMoodMode()) return;

        if (active) {
            // The Bookmarks view never shows the search prompt: searching happens in the log.
            if (viewMode == ViewMode.BOOKMARKS) {
                leaveEventViews();
                updateVisibleLines();
            }
            closeTimePanel();
            log.debug("Entering search input mode");
        }
        statusBar.setSearchInputActive(active);
    }

    @Override
    public void openSearchPrompt(String prefill) {
        setSearchInputActive(true);
        // Mood/logout may have refused the prompt; no pre-fill without a field.
        if (statusBar.isSearchInputActive()) {
            statusBar.prefillSearchInput(prefill);
        }
    }

    @Override
    public void offerSelectionToSearchPrompt(String selected) {
        statusBar.offerSelectionToSearchInput(selected);
    }

    /** True while cursor mode is on (F7). */
    public boolean isCursorMode() {
        return cursorManager.isCursorMode();
    }

    /** Switch cursor mode on or off (F7, `v`, Scroll Lock or the status bar's scroll-lock indicator). */
    public void toggleCursorMode() {
        cursorManager.toggleCursor();
    }

    @Override
    public boolean isYesNoPromptShowing() {
        return pendingScopeChange != null || currentBoundaryPrompt.get() != null
                || statusBar.hasSearchExtendPrompt() || statusBar.hasSearchRecentrePrompt();
    }

    /**
     * Opens the time panel docked to the top of the status bar: AT at the first visible time, FROM / TO at
     * the browsing range (TO follows now while the range does). Nothing on the welcome screen or under the login or
     * index overlays; an open panel only moves the focus.
     */
    @Override
    public void openTimePanel(TimePanel.Focus focus) {
        if (!isEventDispatchThread()) {
            invokeLater(() -> openTimePanel(focus));
            return;
        }
        if (!canOpenTimePanel()) {
            return;
        }
        if (timePanel == null) {
            closeKeyboardHelp();
            setSearchInputActive(false);
            timePanel = new TimePanel(font, DateTimeFormatterManager.zoneId(), clock,
                    ref -> new GotoHandler(config, at -> {}, () -> {}, ref)::parseTimestamp,
                    timePanelListener(this));
            // Swallow clicks on the panel's empty areas, so they do not reach the log underneath.
            timePanel.addMouseListener(new java.awt.event.MouseAdapter() {});
            timePanel.addMouseWheelListener(e -> {});
            layeredPane.add(timePanel, JLayeredPane.PALETTE_LAYER);
            layoutTimePanel();
            layeredPane.revalidate();
            layeredPane.repaint();
        }
        Instant from = null;
        Instant to = null;
        var before = logProvider.getBeforeBoundary();
        var after = logProvider.getAfterBoundary();
        var now = logProvider.getNowBoundary();
        if (before != null) {
            from = before.instant();
        }
        if (after != null && now != null && after.instant().isBefore(now.instant())) {
            to = after.instant();
        }
        timePanel.open(focus, statusBar.getFirstVisibleTimestamp().orElse(null), from, to);
    }

    /** Logged in and browsing, with no login or index overlay over the log. */
    boolean canOpenTimePanel() {
        return !isInMoodMode() && loginOverlay == null && indexPanel == null;
    }

    /** A click on the time group: opens the panel, or closes it when it is open. */
    void toggleTimePanel(TimePanel.Focus focus) {
        if (!closeTimePanel()) {
            openTimePanel(focus);
        }
    }

    @Override
    public boolean isTimePanelOpen() {
        return timePanel != null;
    }

    @Override
    public boolean closeTimePanel() {
        if (timePanel == null) {
            return false;
        }
        layeredPane.remove(timePanel);
        timePanel = null;
        layeredPane.revalidate();
        layeredPane.repaint();
        viewPanel.requestFocusInWindow();
        return true;
    }

    /** Full width, docked to the bottom of the layered pane (the top edge of the status bar). */
    private void layoutTimePanel() {
        if (timePanel == null) {
            return;
        }
        int width = layeredPane.getWidth();
        int height = Math.min(timePanel.preferredHeight(width), layeredPane.getHeight());
        timePanel.setBounds(0, layeredPane.getHeight() - height, width, height);
        timePanel.validate();
    }

    /** What the time panel's keys do: each closes the panel first, so the log has the focus again. */
    static TimePanel.Listener timePanelListener(LogViewPort viewPort) {
        return new TimePanel.Listener() {
            @Override
            public void go(Instant at) {
                viewPort.closeTimePanel();
                viewPort.gotoTimestamp(at);
            }

            @Override
            public void setRange(Instant from, Instant to) {
                viewPort.closeTimePanel();
                viewPort.setRange(from, to);
            }

            @Override
            public void close() {
                viewPort.closeTimePanel();
            }
        };
    }

    /** Replace the browsing range and restart any active text search. EDT only. */
    @Override
    public void setRange(Instant from, Instant to) {
        if (!isEventDispatchThread()) {
            throw new IllegalStateException("setRange must run on the EDT");
        }
        if (isInMoodMode() || searchManager == null) {
            notice(StatusBar.Severity.WARN, "Not connected. Press L to login first.");
            return;
        }
        Instant start = from;
        if (start != null && start.isBefore(SearchBoundaries.MIN_MINIMUM_BOUNDARY)) {
            start = SearchBoundaries.MIN_MINIMUM_BOUNDARY;
        }
        Instant now = logProvider.getNowBoundary().instant();
        Instant end = to == null || to.isAfter(now) ? now : to;
        if (start == null || !start.isBefore(end)) {
            notice(StatusBar.Severity.WARN, "Range start must be before its effective end");
            return;
        }

        boolean keepMatches = viewMode == ViewMode.MATCHES;
        SearchSession active = currentSearchSession();
        String query = statusBar.isSearchActive()
                ? (active != null ? active.getQuery() : lastSubmittedTerm)
                : null;
        clearFirstMatchJump();
        // Range navigation belongs to the log provider, not an event-list provider.
        leaveEventViews();
        statusBar.resetRangeBaseline();
        try {
            if (!cursorManager.gotoRange(start, to)) return;
        } catch (IllegalArgumentException e) {
            notice(StatusBar.Severity.WARN, e.getMessage());
            return;
        }
        if (query != null) {
            executeSearch(query, keepMatches, start, true);
        } else if (keepMatches) {
            enterEmptyMatches();
        }
        updateVisibleLines();
    }

    @Override
    public void gotoTimestamp(Instant timestamp) {
        log.info("Navigating to timestamp: {}", timestamp);

        if (viewMode == ViewMode.BOOKMARKS && bookmarksProvider != null) {
            int row = bookmarksProvider.jump(timestamp);
            cursorManager.gotoLine(row);
            cursorManager.ensureCursorAt(row);
            return;
        }

        if (isEmptyMatchesView()) {
            notice(StatusBar.Severity.INFO, "No matches to go to");
            return;
        }

        if (viewMode == ViewMode.MATCHES && matchesProvider != null) {
            SearchSession session = currentSearchSession();
            SearchRangeOffer.Decision decision = SearchRangeOffer.matchesGoto(session.snapshot(), timestamp);
            if (decision.ask()) {
                statusBar.showSearchRecentrePrompt(new BoundaryRequest(
                        decision.direction(), decision.suggested(), (type, accepted, boundary) -> {}));
                return;
            }
            int row = matchesProvider.jump(timestamp);
            cursorManager.gotoLine(row);
            cursorManager.ensureCursorAt(row);
            return;
        }

        // Navigate to the timestamp
        if (cursorManager != null) {
            cursorManager.gotoTimestamp(timestamp);
        }
    }

    @Override
    public boolean cancelSearchMode() {
        // If the user is typing in the search input, just close the input bar.
        // Leaving the Matches view (backToLog) and cancelling the whole search are
        // later, separate steps of the cancel chain.
        if (statusBar.isSearchInputActive()) {
            log.debug("Search input cancelled");
            setSearchInputActive(false);
            return true;
        }
        return false;
    }

    /**
     * Drop the search's UI state: detach its updates, forget the navigation anchor and
     * the pending first-match jump, and clear the search status. The search itself is
     * cancelled by {@link SearchManager}.
     */
    private void resetSearchUi() {
        searchUpdates.unbind();
        provisional.clear();
        searchNavigation.clear();
        clearFirstMatchJump();
        statusBar.clearSearchExtendPrompt();
        statusBar.clearSearchRecentrePrompt();
        statusBar.setSearchActive(false, null);
        statusBar.clearSearchInfo();
    }

    @Override
    public void clearSearchTransient(String endKey) {
        log.debug("Search transient state cleared; {} again ends the search", endKey);
        cursorManager.clearNavigationHighlight();
        searchNavigation.releaseStuck();
        statusBar.clearSearchPosition();
        notice(StatusBar.Severity.INFO, "%s again to end search".formatted(endKey));
    }

    @Override
    public void cancelSearch() {
        log.debug("Cancel search requested");
        // Exit the Matches view first, restoring the log viewport we left.
        leaveEventViews();
        // Detach first: an update queued before the cancel must not restore the old summary.
        resetSearchUi();
        // Cancel background search if running — SearchManager owns the Searcher
        if (searchManager != null) {
            searchManager.cancelSearch();
        }
        // Also cancel any data provider search
        runAsync(() -> logProvider.cancelSearch()).exceptionally(e -> {
            log.warn("Failed to cancel data provider search", e);
            return null;
        });
    }

    @Override
    public void openLogLineDetails(LoadedLogLine logLine) {
        if (isInMoodMode() || logLine == null) {
            return;
        }
        DetailsTarget target = detailsTargetAt(cursorManager.cursorLine().orElse(logLine.line()), logLine);
        if (target != null) {
            showDetails(target);
        }
    }

    @Override
    public boolean isDetailsOpen() {
        return detailsOpen;
    }

    @Override
    public DetailsPanel detailsPanel() {
        return detailsOpen ? detailsPanel : null;
    }

    /** The event the details show, for tests; null while they are closed. */
    LoadedLogLine detailsEvent() {
        return detailsEvent;
    }

    /**
     * The event of {@code row} in the view underneath, the row it starts on and where it is: the whole event of a
     * Matches or Bookmarks row, in the log the loaded rows of the event joined ({@code fallback} when the event
     * cannot be joined). Null when the row holds no event.
     */
    private DetailsTarget detailsTargetAt(int row, LoadedLogLine fallback) {
        EventListProvider list = eventListProvider();
        if (list != null) {
            EventRows rows = list.rows();
            if (row < 0 || row >= rows.rowCount()) {
                return null;
            }
            int index = rows.eventAt(row);
            String kind = viewMode == ViewMode.BOOKMARKS ? "bookmark " : "match ";
            return new DetailsTarget(rows.event(index), rows.rowOf(index, 0),
                    kind + (index + 1) + " of " + rows.eventCount());
        }
        LogDataProvider provider = logProvider;
        LoadedLogLine event = EventLines.eventOf(provider::peekLine, row).orElse(fallback);
        if (event == null) {
            return null;
        }
        int first = row;
        for (int walked = 0; walked < MAX_EVENT_ROWS && provider.peekLine(first)
                .filter(line -> line instanceof LoadedLogLine loaded && loaded.isContinuation())
                .isPresent(); walked++) {
            first--;
        }
        return new DetailsTarget(event, first, DetailsFields.rowLabel(first));
    }

    /** Show {@code target} in the details panel, opening it over the view first when it is closed. */
    private void showDetails(DetailsTarget target) {
        List<String> messageFields = searchManager != null ? searchManager.messageFields() : null;
        List<String> inMessage = messageFields != null ? messageFields : LogEventMapper.DEFAULT_MESSAGE_FIELDS;
        Scope scope = currentScope();
        detailsEvent = target.event();
        detailsRow = target.row();
        detailsLabel = target.label();
        detailsPanel.setDetails(
                EventDetails.of(target.event(), inMessage::contains, name -> DetailsFields.scopeMark(scope, name)),
                target.label());
        if (!detailsOpen) {
            detailsOpen = true;
            detailsPanel.setBounds(0, 0, layeredPane.getWidth(), layeredPane.getHeight());
            layeredPane.add(detailsPanel, Integer.valueOf(JLayeredPane.DEFAULT_LAYER + 20));
            statusBar.setDetailsMode(true);
        }
        layeredPane.repaint();
        viewPanel.requestFocusInWindow();
    }

    /**
     * Close the details (Esc, Ctrl-G, Enter, the DETAILS tab, and whatever takes the view away: logout, a scope or
     * index change, the login overlay, leaving or entering an event view). The view underneath was never touched, so
     * it only needs a repaint.
     */
    @Override
    public boolean closeDetails() {
        if (!detailsOpen) {
            return false;
        }
        detailsOpen = false;
        detailsEvent = null;
        pendingDetailsStep = null;
        layeredPane.remove(detailsPanel);
        detailsPanel.setDetails(null, "");
        statusBar.setDetailsMode(false);
        layeredPane.revalidate();
        layeredPane.repaint();
        viewPanel.repaint();
        viewPanel.requestFocusInWindow();
        return true;
    }

    /**
     * Runs after every visible-lines update while the details are open (or about to reopen). In the Matches and
     * Bookmarks views the shown event is the cursor's, so a list update (a new match, a removed bookmark) moves the
     * details along; in the log it retries the step or reopening that was waiting for rows to load.
     */
    private void syncDetails() {
        if (pendingDetailsReopen != null) {
            reopenPendingDetails();
        }
        if (!detailsOpen) {
            return;
        }
        EventListProvider list = eventListProvider();
        if (list != null) {
            if (list.rows().rowCount() == 0) {
                closeDetails();
                return;
            }
            Optional<Integer> cursor = cursorManager.cursorLine();
            DetailsTarget target = cursor.isPresent() ? detailsTargetAt(cursor.get(), null) : null;
            if (target != null && (!target.label().equals(detailsLabel)
                    || !SearchHit.from(target.event()).equals(SearchHit.from(detailsEvent)))) {
                showDetails(target);
            }
        } else if (pendingDetailsStep != null) {
            boolean forward = pendingDetailsStep;
            pendingDetailsStep = null;
            stepDetails(forward, true);
        }
    }

    /** Previous / next event of the view underneath; the cursor of that view moves with the details. */
    @Override
    public void detailsStep(boolean forward) {
        if (!detailsOpen) {
            return;
        }
        pendingDetailsStep = null;
        stepDetails(forward, false);
    }

    /**
     * In the log: the first row of the next (previous) event, skipping continuation rows. A row that is still
     * loading leaves the step pending ({@link #syncDetails} retries it on the next update; {@code retry} keeps the
     * notice from repeating); a boundary row says there is no more.
     */
    private void stepDetails(boolean forward, boolean retry) {
        EventListProvider list = eventListProvider();
        if (list != null) {
            stepListDetails(list, forward);
            return;
        }
        int direction = forward ? 1 : -1;
        int row = detailsRow + direction;
        try {
            for (int walked = 0; walked < MAX_EVENT_ROWS; walked++, row += direction) {
                switch (logProvider.getLine(row, false)) {
                    case LoadedLogLine loaded -> {
                        if (!loaded.isContinuation()) {
                            moveDetailsTo(row);
                            return;
                        }
                    }
                    case Placeholder ignored -> {
                        pendingDetailsStep = forward;
                        if (!retry) {
                            notice(StatusBar.Severity.INFO, "Loading…");
                        }
                        return;
                    }
                    default -> {
                        notice(StatusBar.Severity.INFO, "No more logs");
                        return;
                    }
                }
            }
        } catch (TemporarySearchException | SearchException | StorageException e) {
            log.warn("stepDetails: {}", e.getMessage());
        }
    }

    /** In the Matches and Bookmarks views: the next (previous) event of the list, as F3 steps. */
    private void stepListDetails(EventListProvider list, boolean forward) {
        EventRows rows = list.rows();
        if (rows.rowCount() == 0) {
            return;
        }
        int row = Math.clamp(cursorManager.cursorLine().orElse(detailsRow), 0, rows.rowCount() - 1);
        int next = rows.eventAt(row) + (forward ? 1 : -1);
        if (next < 0 || next >= rows.eventCount()) {
            String edge = viewMode == ViewMode.BOOKMARKS
                    ? (forward ? "Last bookmark" : "First bookmark")
                    : (forward ? "No more later matches" : "No more earlier matches");
            notice(StatusBar.Severity.INFO, edge);
            return;
        }
        moveDetailsTo(rows.rowOf(next, 0));
    }

    /** Put the underlying view's cursor on {@code row}, scrolled into view, and show that row's event. */
    private void moveDetailsTo(int row) {
        cursorManager.ensureCursorAt(row);
        DetailsTarget target = detailsTargetAt(row, null);
        if (target != null) {
            showDetails(target);
        }
    }

    /** Ctrl-C in the details: the selected field's value. */
    @Override
    public void detailsCopyValue() {
        detailsPanel.selectedField().ifPresent(field -> copyDetailsText(field.value(), field.name()));
    }

    private void copyDetailsText(String text, String shown) {
        cursorManager.copyContents(new java.awt.datatransfer.StringSelection(text));
        notice(StatusBar.Severity.INFO, "Copied " + shown);
    }

    private void toggleDetailsBookmark() {
        if (detailsEvent == null) {
            return;
        }
        if (viewMode == ViewMode.BOOKMARKS && bookmarksProvider != null) {
            bookmarksCursorTarget = removalTarget(bookmarksProvider, cursorManager.cursorLine().stream().toList());
        }
        announceBookmarkToggle(bookmarks.toggle(List.of(detailsEvent)));
        updateVisibleLines();
    }

    @Override
    public void detailsNarrowField() {
        changeScopeByField(true);
    }

    @Override
    public void detailsExcludeField() {
        changeScopeByField(false);
    }

    /**
     * {@code +} / {@code -}: fold {@code field="value"} of the selected field into the scope. A field that
     * cannot be filtered on, or a term the scope already has, says so and changes nothing. Otherwise the details
     * close and the scope changes like Narrow / Exclude do: after the bookmark question, reloading at the shown
     * event (narrow) or at the top row's time (exclude).
     */
    private void changeScopeByField(boolean narrow) {
        EventDetails.Field field = detailsOpen ? detailsPanel.selectedField().orElse(null) : null;
        if (field == null || detailsEvent == null) {
            return;
        }
        String reason = DetailsFields.notFilterableReason(field, narrow ? "narrow on" : "exclude");
        if (reason != null) {
            notice(StatusBar.Severity.INFO, reason);
            return;
        }
        Scope scope = scopeOrWarn();
        if (scope == null) {
            return;
        }
        Scope next = narrow
                ? scope.withNarrowField(field.name(), field.value())
                : scope.withExcludeField(field.name(), field.value());
        if (next.equals(scope)) {
            notice(StatusBar.Severity.INFO,
                    (narrow ? "Already narrowed to " : "Already excluded ") + DetailsFields.termText(field));
            return;
        }
        SearchHit hit = SearchHit.from(detailsEvent);
        closeDetails();
        confirmBookmarksThenChangeScope(scope, next, () -> {
            if (narrow) {
                applyScope(next, hit, hit.splunkTime(), true);
            } else {
                // Back in the log first: its top row is the position to keep.
                leaveEventViews();
                applyScope(next, null, topRowTime(), false);
            }
        });
    }

    /** {@code f}: add the selected field to the message fields, or remove it. */
    @Override
    public void detailsToggleMessageField() {
        EventDetails.Field field = detailsOpen ? detailsPanel.selectedField().orElse(null) : null;
        if (field == null) {
            return;
        }
        if (!DetailsFields.canBeMessageField(detailsPanel.details(), field)) {
            notice(StatusBar.Severity.INFO, "Only JSON fields can be in the message");
            return;
        }
        if (searchManager == null) {
            scopeOrWarn();
            return;
        }
        List<String> current = searchManager.messageFields();
        List<String> next = DetailsFields.toggled(current, field.name());
        changeMessageFields(next, "Message fields: " + (next.size() > current.size() ? "+ " : "\u2212 ")
                + field.name());
    }

    /**
     * Make {@code fields} (null: the default) the index's message fields: stored in the background, applied as a
     * provider generation change, and the log reloaded at the event shown, which then reopens in the details on
     * the same field so the {@code ✉} mark is seen to move.
     */
    private void changeMessageFields(List<String> fields, String message) {
        SearchManager manager = searchManager;
        if (manager == null || detailsEvent == null) {
            return;
        }
        List<String> stored = fields == null || fields.equals(LogEventMapper.DEFAULT_MESSAGE_FIELDS) ? null : fields;
        List<String> effective = stored == null ? LogEventMapper.DEFAULT_MESSAGE_FIELDS : stored;
        if (effective.equals(manager.messageFields())) {
            return;
        }
        SearchHit hit = SearchHit.from(detailsEvent);
        String selected = detailsPanel.selectedField().map(EventDetails.Field::name).orElse(null);
        Scope scope = manager.scope();
        closeDetails();
        dropScopeQuestion();
        leaveEventViews();
        resetSearchUi();
        if (scope != null && scope.index() != null && !scope.index().isBlank()) {
            String index = scope.index();
            CompletableFuture<Void> storing = manager.storeMessageFields(index, stored);
            if (storing != null) {
                storing.whenComplete((ignored, e) -> {
                    if (e != null) {
                        log.error("changeMessageFields: Failed for index '{}'", index, e);
                        invokeLater(() -> failureToast(
                                "Failed to save message fields: " + ExceptionMessageFormatter.formatException(e)));
                    }
                });
            }
        }
        // Cancels the search, supersedes the loads and clears the cache; the reload below jumps.
        manager.setMessageFields(stored);
        notice(StatusBar.Severity.INFO, message);
        int row = reloadLogAt(hit, hit.splunkTime(), true);
        if (row >= 0) {
            pendingDetailsReopen = new PendingDetails(row, hit, selected);
            syncDetails();
        }
    }

    /**
     * Reopen the details at the event the log reloaded at, as soon as its row is loaded. The reload parked the page
     * there without a cursor; the cursor goes on the event's first row, so Esc returns with it there. Gives up when
     * the user has moved the cursor elsewhere meanwhile.
     */
    private void reopenPendingDetails() {
        PendingDetails pending = pendingDetailsReopen;
        if (isInMoodMode() || viewMode != ViewMode.LOG
                || cursorManager.cursorLine().filter(row -> row != pending.row()).isPresent()) {
            pendingDetailsReopen = null;
            return;
        }
        if (logProvider.peekLine(pending.row()).isEmpty()) {
            return;
        }
        pendingDetailsReopen = null;
        cursorManager.ensureCursorAt(pending.row());
        DetailsTarget target = detailsTargetAt(pending.row(), null);
        if (target != null) {
            showDetails(target);
            if (pending.field() != null) {
                int index = detailsPanel.details().indexOf(pending.field());
                if (index >= 0) {
                    detailsPanel.select(index);
                }
            }
        }
    }

    /** The field menu of the selected field, built but not shown; null without a selected field. */
    JPopupMenu detailsFieldMenu() {
        EventDetails.Field field = detailsOpen ? detailsPanel.selectedField().orElse(null) : null;
        if (field == null) {
            return null;
        }
        SearchManager manager = searchManager;
        boolean custom = manager != null && !LogEventMapper.DEFAULT_MESSAGE_FIELDS.equals(manager.messageFields());
        var model = new FieldContextMenu.Model(field,
                manager != null && DetailsFields.canBeMessageField(detailsPanel.details(), field), custom,
                KeyMap.preferred());
        return FieldContextMenu.build(model, new FieldContextMenu.Actions() {
            @Override
            public void narrow() {
                detailsNarrowField();
            }

            @Override
            public void exclude() {
                detailsExcludeField();
            }

            @Override
            public void toggleMessage() {
                detailsToggleMessageField();
            }

            @Override
            public void resetMessage() {
                changeMessageFields(null, "Message fields: reset");
            }

            @Override
            public void copyValue() {
                copyDetailsText(field.value(), field.name());
            }

            @Override
            public void copyTerm() {
                String term = DetailsFields.termText(field);
                copyDetailsText(term, term);
            }
        });
    }

    /** The context-menu key in the details: the field menu just below the selected field. */
    @Override
    public void showDetailsFieldMenu() {
        Rectangle bounds = detailsPanel.selectedFieldBounds();
        showDetailsFieldMenu(new Point(Math.min(bounds.x + 2 * HPAD, Math.max(0, detailsPanel.getWidth() - 40)),
                bounds.y + bounds.height));
    }

    private void showDetailsFieldMenu(Point at) {
        JPopupMenu menu = detailsFieldMenu();
        if (menu != null && !GraphicsEnvironment.isHeadless()) {
            menu.show(detailsPanel, at.x, at.y);
        }
    }

    @Override
    public void requestBoundaryExpansion(BoundaryRequest boundaryRequest) {

        var old = currentBoundaryPrompt.getAndSet(boundaryRequest);

        if (old != null) {
            log.debug("cancelling previous boundary expansion prompt: {}", old);
            old.accept(false);
        }

        SwingUtilities.invokeLater(() -> {
            log.info("Boundary expansion requested: {}", boundaryRequest);

            // Show boundary expansion prompt in status bar (handles all UI)
            statusBar.showBoundaryExpansionPrompt(boundaryRequest);

            // Request focus to capture keyboard input
            viewPanel.requestFocusInWindow();
        });
    }

    @Override
    public boolean actionAcceptBoundaryExpansion(boolean accepted) {
        if (pendingScopeChange != null) {
            return finishScopeQuestion(accepted);
        }
        if (currentBoundaryPrompt.get() == null && statusBar.hasSearchRecentrePrompt()) {
            return finishSearchRecentre(accepted);
        }
        if (currentBoundaryPrompt.get() == null && statusBar.hasSearchExtendPrompt()) {
            return finishSearchExtend(accepted);
        }
        log.debug("Boundary expansion action: accepted={}, disabling prompt", accepted);
        statusBar.disableBoundaryExpansionPrompt();

        var request = currentBoundaryPrompt.getAndSet(null);
        if (request != null) {

            runAsync(() -> {
                request.accept(accepted);
                SwingUtilities.invokeLater(this::updateVisibleLines);
            }).exceptionally(e -> {
                log.error("Failed to apply boundary expansion decision", e);
                return null;
            });
            return true;
        } else {
            log.info("Boundary expansion action: no callback registered");
            return false;
        }
    }

    private boolean finishSearchExtend(boolean accepted) {
        if (!statusBar.hasSearchExtendPrompt()) {
            return false;
        }
        // The request is held by the status bar; read it before clearing the prompt.
        BoundaryRequest request = statusBar.searchExtendRequest();
        statusBar.clearSearchExtendPrompt();
        if (request == null) {
            return false;
        }
        SearchSession session = currentSearchSession();
        if (!accepted) {
            searchExtendClockStart = clock.instant();
            searchNavigation.declineExtend(request.type() == se.grynna.pager.splunk.BoundaryType.AFTER
                    ? SearchNavigation.Edge.LATER
                    : SearchNavigation.Edge.EARLIER);
            if (session != null) {
                publishSearchPosition(session);
            }
            return true;
        }
        searchExtendClockStart = clock.instant();
        if (session == null || searchManager == null) {
            return true;
        }
        startSearchExtension(session, request);
        return true;
    }

    /** Widen {@code session} over the slice the request names; the same session keeps its matches and continues. */
    @SuppressWarnings("ReferenceEquality")
    private void startSearchExtension(SearchSession session, BoundaryRequest request) {
        boolean later = request.type() == se.grynna.pager.splunk.BoundaryType.AFTER;
        if (later) {
            laterRangeExpansions++;
        } else {
            earlierRangeExpansions++;
        }
        Instant from = later
                ? (session.windowEnd() != null ? session.windowEnd() : session.getSearchOrigin())
                : request.suggestedBoundary();
        Instant to = later
                ? request.suggestedBoundary()
                : (session.windowStart() != null ? session.windowStart() : session.getSearchOrigin());
        // Capture on the EDT: a logout or new search may replace these before the task runs.
        final SearchManager sm = searchManager;
        final LogDataProvider provider = logProvider;
        runAsync(() -> {
            if (later) {
                provider.setAfterBoundary(request.suggestedBoundary());
            } else {
                provider.setBeforeBoundary(request.suggestedBoundary());
            }
            // No-op if this session was cancelled or replaced meanwhile.
            sm.extendSearch(session, from, to);
            // The extension is running now (not for a stale request): the centre shows the edge it moves to.
            SwingUtilities.invokeLater(() -> {
                if (session.getState() == State.RUNNING && !session.isDiscarded() && session == currentSearchSession()
                        && statusBar.isSearchActive()) {
                    statusBar.setSearchExtending(later, request.suggestedBoundary());
                }
            });
        }).exceptionally(e -> {
            log.error("Failed to extend search range", e);
            return null;
        });
    }

    /** Yes starts a new search around the truncated edge; No remembers the declined edge. */
    private boolean finishSearchRecentre(boolean accepted) {
        BoundaryRequest request = statusBar.searchRecentreRequest();
        statusBar.clearSearchRecentrePrompt();
        SearchSession session = currentSearchSession();
        if (request == null || session == null) {
            return true;
        }
        if (!accepted) {
            searchNavigation.declineExtend(request.type() == se.grynna.pager.splunk.BoundaryType.AFTER
                    ? SearchNavigation.Edge.LATER
                    : SearchNavigation.Edge.EARLIER);
            publishSearchPosition(session);
            return true;
        }
        // From the Matches view the new search stays there (refine semantics).
        leaveBookmarks();
        executeSearch(session.getQuery(), viewMode == ViewMode.MATCHES, request.suggestedBoundary());
        return true;
    }
}
