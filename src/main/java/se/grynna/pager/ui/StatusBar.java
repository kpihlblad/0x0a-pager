package se.grynna.pager.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import lombok.extern.slf4j.Slf4j;
import raven.swing.spinner.SpinnerProgress;
import se.grynna.pager.splunk.BoundaryType;
import se.grynna.pager.splunk.SearchProgress;
import se.grynna.pager.ui.clip.ClipboardAccess;
import se.grynna.pager.ui.preferences.DateTimeFormatterManager;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static se.grynna.pager.ui.Actions.GOTO;
import static se.grynna.pager.ui.Actions.SEARCH;
import static se.grynna.pager.utils.DarculaColors.FOREGROUND;

/**
 * Combined status bar and input bar component.
 *
 * <h3>Top row (status):</h3>
 * <ul>
 *   <li>Left: General status messages</li>
 *   <li>Right: Current timestamp (first line on page), or the time range of the selection while two or more rows are
 *   selected, or boundary expansion suggestion, timezone, and base directory size</li>
 * </ul>
 *
 * <h3>Bottom row (input):</h3>
 * <ul>
 *   <li>Left: Prompt message and input field</li>
 *   <li>Right: Hamburger menu button</li>
 * </ul>
 * <p>
 * The date field serves dual purposes:
 * <ul>
 *   <li><b>Normal mode:</b> Shows timestamp of the first visible line</li>
 *   <li><b>Selection mode:</b> Shows the time range of the selected lines, with the duration between them, while two or
 *   more rows are selected</li>
 *   <li><b>Boundary expansion mode:</b> Shows suggested new boundary with "?" and orange highlight (clickable with pulsating effect)</li>
 * </ul>
 * <p>
 * Handles input state management and boundary expansion UI with visual indicators.
 */
@Slf4j
public class StatusBar extends JPanel {

    public static final int VPAD = 0;
    public static final int HPAD = 10;
    public static final int preferredIconSize = 16;

    private final Font font;
    private final FlatSVGIcon cancelIcon;
    private final FlatSVGIcon downArrow;
    private final FlatSVGIcon upArrow;
    private final JButton boundaryPromptButton;
    private final JButton boundaryCancelButton;
    private final JTextField boundaryPromptYN;

    // Status bar components (top row)
    private final TimeGroup timeGroup;
    private final JLabel statusNotice; // Right of the centre - transient state notice, see showNotice
    private final Timer noticeTimer;
    private final JLabel statusBaseDirSize;
    private final SpinnerProgress searchSpinner; // Shows when searches are in progress
    private final JLabel loadProgressStatus; // Persistent load state, independent of the prompt and notices

    // Input bar components (bottom row)
    private final JTextField promptField;
    private final JTextField inputField;
    /** Key hints after the input field while the search prompt is open. */
    private final JLabel inputHint;
    private InputState inputState = InputState.INIT;

    /** Colour of a transient notice: INFO in the normal text colour, WARN in amber. */
    public enum Severity {
        INFO, WARN
    }

    /** How long a notice stays before it clears itself. */
    static final int NOTICE_MILLIS = 4000;

    /** Scroll Lock symbol shown while cursor mode is off; the font check falls back to text. */
    static final String SCROLL_LOCK_GLYPH = "\u21F3";

    /** Dim badge in the status row: page mode (scroll lock) is on. Blank in cursor mode, keeping its slot. */
    private final JLabel scrollLockBadge;
    private final String scrollLockGlyph;
    private boolean scrollLockShown;

    private boolean matchesMode;
    private boolean bookmarksMode;
    /** The inline event details are open over the view: the DETAILS tab is active and the row hints their keys. */
    private boolean detailsMode;
    private boolean helpMode;
    private int bookmarkCount;
    /** LOG / MATCHES n / BOOKMARKS n / DETAILS at the left of the status row; also the view indicator. */
    private final ViewTabs viewTabs;
    private final JPanel statusRow;
    /** Pop-and-fade flash of the input bar when it starts needing input; painted by {@link InputFlashLayerUI}. */
    private final AttentionFlash inputFlash;
    private final JLayer<JComponent> inputFlashLayer;
    /** Pop-and-fade flash of the time group's range half when its range grows. */
    private final AttentionFlash centerFlash;
    private final InstantSource nowSource;
    private final JPanel leftPanel;
    private final JPanel rightPanel;
    private final JPanel inputBarLeft;

    // Reference to parent viewer for focus management
    private final LogViewer logViewer;

    // Boundary expansion state
    private BoundaryRequest pendingBoundaryRequest = null;

    /** Roadmap status-bar work: pulsating highlight on the boundary-expansion prompt; off until revisited. */
    private static final boolean PULSATE_ENABLED = false;

    // Pulsating effect for boundary expansion
    private Timer pulsatingTimer;

    // Global State - with precendence
    private boolean updatingBoundary;
    private boolean searchInputActive;
    /** The user typed in the search input since it opened; a log selection no longer replaces its text. */
    private boolean searchInputEdited;
    /** Set while the input's text is changed by the application, so the edit is not counted as typing. */
    private boolean programmaticInputEdit;
    private ClipboardAccess clipboard = ClipboardAccess.system();
    private boolean searchActive;
    private Instant firstVisibleTimestamp;
    /** Time range of the selection while two or more rows are selected; the date field shows it instead. */
    private CursorManager.SelectionSpan selectionSpan;
    /** One-line selected text offered to Narrow and Exclude, or null when the selection is not a scope term. */
    private String selectionTerm;
    private boolean modalLogin;
    private boolean modalIndex;
    private String lastSearchQuery;
    /** 1-based index of the match F3 last landed on, or null if none is selected. */
    private Integer searchMatchNumber;

    private Integer searchHitCount;
    /** Lowercase end-of-matches note, or null when F3 can still move. */
    private String searchEdgeNote;

    private Instant searchWindowStart;
    private Instant searchWindowEnd;
    /** The log's browsing range (provider boundaries), shown in the time group while no search window is. */
    private Instant browsingStart;
    private Instant browsingEnd;
    /** The range the centre showed last, for detecting growth; null after a reset (new search, logout). */
    private Instant shownStart;
    private Instant shownEnd;
    private String shownRangeText;
    /** The edge a running search extension moves towards, or null; {@code searchExtendingLater} is its direction. */
    private Instant searchExtendingEdge;
    private boolean searchExtendingLater;
    private SearchStatusText.Phase textSearchPhase = SearchStatusText.Phase.COMPLETE;
    private boolean searchExtendActive;
    private String searchExtendDirection = "later";
    /** The query of a zero-match search being offered a wider window, or null for the "No more … matches" wording. */
    private String searchExtendNoMatchesFor;
    private BoundaryRequest searchExtendRequest;
    private boolean searchRecentreActive;
    /** The open scope-change question ("3 bookmarks are outside the new scope …"), or null. */
    private String scopeConfirmText;
    private BoundaryRequest searchRecentreRequest;

    /** Gray × — leave search mode. Not the red cancel, which reads as an error. */
    private final JButton searchExitButton;
    private final ActionPill selectionSearchButton;
    private final ActionPill narrowButton;
    private final ActionPill excludeButton;
    /** Bookmarks view only: copy every bookmark as shown, and open the Export dialog on them. */
    private final JButton bookmarksCopyButton;
    private final JButton bookmarksExportButton;
    /** Details only: step to the previous / next event of the view underneath. */
    private final JButton detailsPreviousButton;
    private final JButton detailsNextButton;
    private final ScopeChips scopeChips;
    private final JPanel centerPanel;
    /** Orange square — stop the running Splunk query, keep matches found so far. */
    private final JButton searchStopButton;

    private final SpinnerProgress textSearchSpinner;

    public StatusBar(Font font, LogViewer logViewer) {
        this(font, logViewer, () -> System.nanoTime() / 1_000_000);
    }

    /** As {@link #StatusBar(Font, LogViewer)}, with the clock (milliseconds) every attention flash runs on. */
    StatusBar(Font font, LogViewer logViewer, LongSupplier clockMillis) {
        this(font, logViewer, clockMillis, InstantSource.system());
    }

    /** As above, with the clock the range text reads {@code now} from. */
    StatusBar(Font font, LogViewer logViewer, LongSupplier clockMillis, InstantSource nowSource) {
        this.font = font;
        this.logViewer = logViewer;
        this.nowSource = nowSource;

        this.cancelIcon = new FlatSVGIcon("cancel.svg", preferredIconSize, preferredIconSize);
        FlatSVGIcon exitSearchIcon = new FlatSVGIcon("exit-search.svg", preferredIconSize, preferredIconSize);
        FlatSVGIcon stopIcon = new FlatSVGIcon("stop.svg", preferredIconSize, preferredIconSize);

        this.upArrow = new FlatSVGIcon("uparrow.svg", 8, 8);
        this.downArrow = new FlatSVGIcon("downarrow.svg", 8, 8);

        // Create hamburger menu button
        MenuManager menuManager = new MenuManager(logViewer);
        JButton hamburgerButton = menuManager.getHamburgerButton();

        setLayout(new BorderLayout());
        setBackground(DarculaColors.MIDNIGHT_BLUE);
        setOpaque(true);

        // === Top row: Status bar ===
        statusRow = new JPanel();
        statusRow.setLayout(new BoxLayout(statusRow, BoxLayout.X_AXIS));
        statusRow.setBackground(DarculaColors.MIDNIGHT_BLUE);
        statusRow.setOpaque(true);

        // Search spinner - small indeterminate progress indicator
        searchSpinner = new SpinnerProgress();
        searchSpinner.setPreferredSize(new Dimension(preferredIconSize, preferredIconSize));
        searchSpinner.setMinimumSize(new Dimension(preferredIconSize, preferredIconSize));
        searchSpinner.setMaximumSize(new Dimension(preferredIconSize, preferredIconSize));
        searchSpinner.setBorderPainted(false);
        searchSpinner.setStringPainted(false);
        searchSpinner.setOpaque(false);
        searchSpinner.setVisible(false);

        loadProgressStatus = createLabel("", SwingConstants.LEFT);
        loadProgressStatus.setVisible(false);

        timeGroup = new TimeGroup(font);

        // Left panel: view tabs + time group + load progress
        viewTabs = new ViewTabs(font, clockMillis);
        viewTabs.setOnSelect(this::selectTab);

        leftPanel = new JPanel();
        leftPanel.setBackground(DarculaColors.MIDNIGHT_BLUE);
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.X_AXIS));
        leftPanel.setOpaque(true);
        leftPanel.add(viewTabs);
        leftPanel.add(Box.createHorizontalStrut(HPAD));
        leftPanel.add(timeGroup);
        leftPanel.add(Box.createHorizontalStrut(5));
        leftPanel.add(searchSpinner);
        leftPanel.add(loadProgressStatus);

        // Base directory size indicator
        statusBaseDirSize = createLabel("󰋊 0 MB", SwingConstants.RIGHT);

        statusNotice = createLabel("", SwingConstants.RIGHT);
        statusNotice.setVisible(false);
        noticeTimer = new Timer(NOTICE_MILLIS, e -> clearNotice());
        noticeTimer.setRepeats(false);

        // Shown while cursor mode is off (page mode, where the arrows scroll); a click switches cursor mode on.
        scrollLockGlyph = font.canDisplay(SCROLL_LOCK_GLYPH.codePointAt(0)) ? SCROLL_LOCK_GLYPH : "SCRL";
        scrollLockBadge = createLabel("", SwingConstants.CENTER);
        scrollLockBadge.setForeground(DarculaColors.COMMENT);
        // Only the glyph's width, no side padding: the size display's own padding is the gap. The slot keeps that
        // width when the badge is blank, so the size display beside it does not move.
        scrollLockBadge.setBorder(BorderFactory.createEmptyBorder(VPAD, 0, VPAD, 0));
        Dimension slot = new Dimension(scrollLockBadge.getFontMetrics(font).stringWidth(scrollLockGlyph),
                scrollLockBadge.getPreferredSize().height);
        scrollLockBadge.setPreferredSize(slot);
        scrollLockBadge.setMinimumSize(slot);
        scrollLockBadge.setMaximumSize(slot);
        scrollLockBadge.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (scrollLockShown) {
                    logViewer.toggleCursorMode();
                }
            }
        });

        // Right end of the bar: the scope chips sit right-aligned before the scroll-lock slot and the size.
        JPanel indicators = new JPanel();
        indicators.setLayout(new BoxLayout(indicators, BoxLayout.X_AXIS));
        indicators.setOpaque(false);
        indicators.add(scrollLockBadge);
        indicators.add(statusBaseDirSize);

        rightPanel = new JPanel(new BorderLayout(HPAD, 0));
        rightPanel.setForeground(FOREGROUND);
        rightPanel.add(indicators, BorderLayout.EAST);
        scopeChips = new ScopeChips(font, logViewer::removeScopeTerm);
        rightPanel.add(scopeChips, BorderLayout.CENTER);
        rightPanel.setOpaque(true);
        rightPanel.setBackground(DarculaColors.MIDNIGHT_BLUE);

        centerPanel = new JPanel(new BorderLayout(HPAD, 0));
        centerPanel.setOpaque(true);
        centerPanel.setBackground(DarculaColors.MIDNIGHT_BLUE);
        centerPanel.add(statusNotice, BorderLayout.EAST);

        statusRow.add(leftPanel);
        statusRow.add(centerPanel);
        statusRow.add(rightPanel);

        // === Bottom row: Input bar ===
        JPanel inputRow = new JPanel(new BorderLayout(0, 0));
        inputRow.setBackground(DarculaColors.BACKGROUND);
        inputRow.setOpaque(true);

        // Prompt field - shows state message
        promptField = new JTextField();
        promptField.setFont(font);
        promptField.setEditable(false);
        promptField.setBackground(DarculaColors.BACKGROUND);
        promptField.setForeground(FOREGROUND);
        promptField.setBorder(BorderFactory.createEmptyBorder(VPAD, 5, VPAD, 2));
        promptField.setFocusable(false);

        // Prompt button (OK / Cancel) - only visible during boundary expansion
        boundaryPromptButton = createButton(null);
        boundaryPromptButton.setFont(font);
        boundaryPromptButton.setBackground(DarculaColors.BACKGROUND);
        boundaryPromptButton.addActionListener(e -> logViewer.actionAcceptBoundaryExpansion(true));

        boundaryCancelButton = createButton(null);
        boundaryCancelButton.setFont(font);
        boundaryCancelButton.setBackground(DarculaColors.BACKGROUND);
        boundaryCancelButton.setBorder(BorderFactory.createEmptyBorder(VPAD, 0, VPAD, 0));
        boundaryCancelButton.setIcon(cancelIcon);
        boundaryCancelButton.addActionListener(e -> logViewer.actionAcceptBoundaryExpansion(false));
        boundaryPromptYN = new JTextField("(" + InputState.yesNo(KeyMap.preferred()) + ")");
        boundaryPromptYN.setFont(font);
        boundaryPromptYN.setBackground(DarculaColors.BACKGROUND);
        boundaryPromptYN.setForeground(FOREGROUND);
        boundaryPromptYN.setBorder(BorderFactory.createEmptyBorder(VPAD, 5, VPAD, 5));

        setBoundaryButtonsVisible(false);

        // Leave search mode. Gray ×, not the red cancel — that one means "something failed".
        searchExitButton = createButton(null);
        styleChromeButton(searchExitButton, font, true);
        searchExitButton.setIcon(exitSearchIcon);
        searchExitButton.setToolTipText("Exit search (Esc)");
        searchExitButton.setVisible(false);
        searchExitButton.addActionListener(e -> logViewer.cancelSearch());

        selectionSearchButton = new ActionPill("Search", DarculaColors.SEARCH_ACCENT, font);
        selectionSearchButton.setToolTipText(RowContextMenu.searchSelectionText(null));
        selectionSearchButton.setVisible(false);
        selectionSearchButton.addActionListener(e -> logViewer.searchForSelection());

        // Fold the selected text, or the active search when there is no selection, into the scope.
        narrowButton = new ActionPill(RowContextMenu.NARROW_LABEL, DarculaColors.NARROW_ACCENT, font);
        narrowButton.setToolTipText(MenuManager.narrowText(KeyMap.preferred()));
        narrowButton.setVisible(false);
        narrowButton.addActionListener(e -> {
            if (detailsMode) {
                logViewer.detailsNarrowField();
            } else if (selectionTerm != null) {
                logViewer.narrowScopeToSelection();
            } else {
                logViewer.narrowScopeToSearch();
            }
        });

        excludeButton = new ActionPill(RowContextMenu.EXCLUDE_LABEL, DarculaColors.EXCLUDE_ACCENT, font);
        excludeButton.setToolTipText(MenuManager.excludeText(KeyMap.preferred()));
        excludeButton.setVisible(false);
        excludeButton.addActionListener(e -> {
            if (detailsMode) {
                logViewer.detailsExcludeField();
            } else if (selectionTerm != null) {
                logViewer.excludeSelectionFromScope();
            } else {
                logViewer.excludeSearchFromScope();
            }
        });
        alignActionPillHeights();

        bookmarksCopyButton = createButton("Copy all");
        styleChromeButton(bookmarksCopyButton, font, false);
        bookmarksCopyButton.setToolTipText("Copy all bookmarks as shown");
        bookmarksCopyButton.setVisible(false);
        bookmarksCopyButton.addActionListener(e -> logViewer.copyBookmarks());

        bookmarksExportButton = createButton("Export\u2026");
        styleChromeButton(bookmarksExportButton, font, false);
        bookmarksExportButton.setToolTipText("Export the bookmarks to a file");
        bookmarksExportButton.setVisible(false);
        bookmarksExportButton.addActionListener(e -> logViewer.showExportDialog(ExportSources.Source.BOOKMARKS));

        detailsPreviousButton = createButton("\u25C0 Prev");
        styleChromeButton(detailsPreviousButton, font, false);
        detailsPreviousButton.setToolTipText(detailsStepText(KeyMap.preferred(), false));
        detailsPreviousButton.setVisible(false);
        detailsPreviousButton.addActionListener(e -> logViewer.detailsStep(false));

        detailsNextButton = createButton("Next \u25B6");
        styleChromeButton(detailsNextButton, font, false);
        detailsNextButton.setToolTipText(detailsStepText(KeyMap.preferred(), true));
        detailsNextButton.setVisible(false);
        detailsNextButton.addActionListener(e -> logViewer.detailsStep(true));

        // Halt the query. Matches already found stay navigable.
        searchStopButton = createButton(null);
        styleChromeButton(searchStopButton, font, true);
        searchStopButton.setIcon(stopIcon);
        searchStopButton.setToolTipText("Stop search");
        searchStopButton.setVisible(false);
        searchStopButton.addActionListener(e -> logViewer.stopTextSearch());

        textSearchSpinner = new SpinnerProgress() {
            @Override
            public Point getToolTipLocation(MouseEvent event) {
                return InWindowToolTip.locationFor(this, event);
            }
        };
        textSearchSpinner.setPreferredSize(new Dimension(preferredIconSize, preferredIconSize));
        textSearchSpinner.setMinimumSize(new Dimension(preferredIconSize, preferredIconSize));
        textSearchSpinner.setMaximumSize(new Dimension(preferredIconSize, preferredIconSize));
        textSearchSpinner.setBorderPainted(false);
        textSearchSpinner.setStringPainted(false);
        textSearchSpinner.setOpaque(false);
        textSearchSpinner.setVisible(false);
        textSearchSpinner.setFocusable(false);
        textSearchSpinner.setToolTipText("Search running");

        // Input field - optionally editable based on state
        inputField = new JTextField("");
        inputField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                inputEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                inputEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attribute changes are not edits.
            }
        });
        inputField.setFont(font);
        inputField.setBackground(DarculaColors.BACKGROUND);
        inputField.setForeground(FOREGROUND);
        inputField.setBorder(BorderFactory.createEmptyBorder(VPAD, 5, VPAD, 5));
        inputField.setCaretColor(FOREGROUND);
        inputField.setPreferredSize(new Dimension(400, inputField.getPreferredSize().height));
        inputField.setMinimumSize(new Dimension(66, inputField.getMinimumSize().height));

        // Setup keyboard handling for the search input (Enter to submit, Escape/Ctrl-G to cancel)
        setupInputFieldKeyListener();
        setupInputFieldClipboard();

        // Left side: prompt + input field
        inputBarLeft = new JPanel();
        inputBarLeft.setLayout(new BoxLayout(inputBarLeft, BoxLayout.X_AXIS));

        inputBarLeft.setBackground(DarculaColors.BACKGROUND);
        inputBarLeft.add(textSearchSpinner);
        inputBarLeft.add(Box.createHorizontalStrut(4));
        inputBarLeft.add(promptField);
        inputBarLeft.add(boundaryPromptButton);
        inputBarLeft.add(boundaryCancelButton);
        inputBarLeft.add(boundaryPromptYN);
        inputBarLeft.add(searchStopButton);
        inputBarLeft.add(searchExitButton);
        inputBarLeft.add(selectionSearchButton);
        inputBarLeft.add(narrowButton);
        inputBarLeft.add(excludeButton);
        inputBarLeft.add(bookmarksCopyButton);
        inputBarLeft.add(bookmarksExportButton);
        inputBarLeft.add(detailsPreviousButton);
        inputBarLeft.add(detailsNextButton);

        inputBarLeft.add(inputField);

        inputHint = new JLabel();
        inputHint.setFont(font.deriveFont(Math.max(10f, font.getSize2D() - 2f)));
        inputHint.setForeground(DarculaColors.COMMENT);
        inputHint.setBorder(BorderFactory.createEmptyBorder(VPAD, 8, VPAD, 5));
        inputHint.setVisible(false);
        inputBarLeft.add(inputHint);
        inputBarLeft.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applySearchChrome();
            }
        });

        // Search button and goto button
        // Actions are looked up at click time because InputHandler.setup() runs after StatusBar construction
        JButton searchButton = new JButton();
        FlatSVGIcon searchIcon = new FlatSVGIcon("search.svg", preferredIconSize, preferredIconSize);
        searchButton.setIcon(searchIcon);
        searchButton.setFocusable(false);
        searchButton.setBackground(DarculaColors.BACKGROUND);
        searchButton.setForeground(FOREGROUND);
        searchButton.addActionListener(e -> {
            Action action = logViewer.getActionMap().get(SEARCH);
            if (action != null) {
                action.actionPerformed(e);
            }
        });

        JButton gotoButton = new JButton();
        gotoButton.setFont(font);
        FlatSVGIcon gotoIcon = new FlatSVGIcon("goto.svg", preferredIconSize, preferredIconSize);
        gotoButton.setIcon(gotoIcon);
        gotoButton.setFocusable(false);
        gotoButton.setBackground(DarculaColors.BACKGROUND);
        gotoButton.setForeground(FOREGROUND);
        gotoButton.addActionListener(e -> {
            Action action = logViewer.getActionMap().get(GOTO);
            if (action != null) {
                action.actionPerformed(e);
            }
        });

        // Buttons bar (WEST in input row)
        // Stacking buttons to the left of the input field
        // Goto, Search, and Hamburger.

        JPanel buttonsBar = new JPanel();
        buttonsBar.setLayout(new BoxLayout(buttonsBar, BoxLayout.X_AXIS));
        buttonsBar.setBackground(DarculaColors.BACKGROUND);
        buttonsBar.add(gotoButton);
        buttonsBar.add(searchButton);
        buttonsBar.add(hamburgerButton);

        // Hamburger button styling
        hamburgerButton.setFocusable(false);
        hamburgerButton.setBackground(DarculaColors.BACKGROUND);
        hamburgerButton.setForeground(FOREGROUND);

        inputRow.add(inputBarLeft, BorderLayout.CENTER);
        inputRow.add(buttonsBar, BorderLayout.EAST);

        // Both rows go in a layer that paints the input bar's attention flash over them.
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.add(statusRow);
        rows.add(inputRow);
        inputFlashLayer = new JLayer<JComponent>(rows);
        inputFlash = new AttentionFlash(inputFlashLayer::repaint, clockMillis);
        centerFlash = new AttentionFlash(inputFlashLayer::repaint, clockMillis);
        inputFlashLayer.setUI(
                new InputFlashLayerUI(inputFlash, inputRow, this::answerZone, centerFlash, timeGroup));
        add(inputFlashLayer, BorderLayout.CENTER);

        // Initialize with welcome message
        setState(InputState.INIT);
    }

    /** The components the user answers in: the input field when editable, else the Y/N prompt's visible parts. */
    private List<Component> answerZone() {
        List<Component> zone = new ArrayList<>();
        if (inputState.editable) {
            zone.add(inputField);
        } else if (boundaryPromptButton.isVisible()) {
            for (Component part : List.of(boundaryPromptButton, boundaryCancelButton, boundaryPromptYN)) {
                if (part.isVisible()) {
                    zone.add(part);
                }
            }
        }
        return zone;
    }

    /** The input bar's attention flash, for tests. */
    AttentionFlash inputFlash() {
        return inputFlash;
    }

    /** The blue-bar centre's attention flash, for tests. */
    AttentionFlash centerFlash() {
        return centerFlash;
    }

    private void inputEdited() {
        if (!programmaticInputEdit) {
            searchInputEdited = true;
        }
    }

    /** Replaces the input's text without counting as the user typing in it. */
    private void setInputText(String text) {
        programmaticInputEdit = true;
        try {
            inputField.setText(text);
        } finally {
            programmaticInputEdit = false;
        }
    }

    private void setBoundaryButtonsVisible(boolean promptVisible) {
        boundaryCancelButton.setVisible(promptVisible);
        boundaryCancelButton.setFocusable(promptVisible);
        boundaryCancelButton.setEnabled(promptVisible);
        boundaryPromptButton.setFocusable(promptVisible);
        boundaryPromptButton.setVisible(promptVisible);
        boundaryPromptButton.setEnabled(promptVisible);
        boundaryPromptYN.setVisible(promptVisible);
    }

    private static JButton createButton(String text) {
        return new JButton(text) {
            @Override
            public Point getToolTipLocation(MouseEvent event) {
                return InWindowToolTip.locationFor(this, event);
            }
        };
    }

    private JLabel createLabel(String text, int alignment) {
        JLabel label = new JLabel(text, alignment) {
            @Override
            public Point getToolTipLocation(MouseEvent event) {
                return InWindowToolTip.locationFor(this, event);
            }
        };
        label.setForeground(FOREGROUND);
        label.setFont(font);
        label.setBorder(BorderFactory.createEmptyBorder(VPAD, HPAD, VPAD, HPAD));
        label.setOpaque(true);
        label.setBackground(DarculaColors.MIDNIGHT_BLUE);
        return label;
    }

    /**
     * Setup key listener for input field to handle Enter submission.
     * Cancel (Escape/Ctrl-G) is handled globally by InputHandler's cancel action
     * which fires via WHEN_IN_FOCUSED_WINDOW regardless of which component has focus.
     */
    private void setupInputFieldKeyListener() {
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_ENTER) {
                    return;
                }

                if (searchInputActive) {
                    // In search input mode, Enter submits the search. Shift-Enter
                    // searches and opens the Matches view.
                    String term = inputField.getText();
                    boolean openMatches = e.isShiftDown();
                    log.debug("Search submitted: {} (openMatches={})", term, openMatches);
                    logViewer.executeSearch(term, openMatches);
                    e.consume();
                }
            }
        });
    }

    /**
     * The input field's own clipboard keys. They win over the log's Copy (the field has focus, so
     * the window-wide bindings never see the keystroke) and paste as one line. Set up with the field,
     * so Ctrl-C/X/V/A, Ctrl-Insert, Shift-Insert and Shift-Delete work the same on every look and feel.
     */
    private void setupInputFieldClipboard() {
        ActionMap am = inputField.getActionMap();
        am.put(DefaultEditorKit.copyAction, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyFromInputField();
            }
        });
        am.put(DefaultEditorKit.cutAction, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (copyFromInputField() && inputField.isEditable()) {
                    inputField.replaceSelection("");
                }
            }
        });
        am.put(DefaultEditorKit.pasteAction, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pasteIntoInputField();
            }
        });
        am.put(DefaultEditorKit.selectAllAction, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                inputField.selectAll();
            }
        });

        InputMap im = inputField.getInputMap(JComponent.WHEN_FOCUSED);
        int ctrl = InputEvent.CTRL_DOWN_MASK;
        int shift = InputEvent.SHIFT_DOWN_MASK;
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, ctrl), DefaultEditorKit.copyAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, ctrl), DefaultEditorKit.copyAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, ctrl), DefaultEditorKit.cutAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, shift), DefaultEditorKit.cutAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, ctrl), DefaultEditorKit.pasteAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, shift), DefaultEditorKit.pasteAction);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, ctrl), DefaultEditorKit.selectAllAction);
    }

    /** Copies the field's selection; false when nothing is selected. */
    private boolean copyFromInputField() {
        String selected = inputField.getSelectedText();
        if (selected == null || selected.isEmpty()) {
            return false;
        }
        clipboard.setText(selected);
        return true;
    }

    private void pasteIntoInputField() {
        if (!inputField.isEditable()) {
            return;
        }
        clipboard.text().map(PromptText::singleLine).filter(text -> !text.isEmpty())
                .ifPresent(inputField::replaceSelection);
    }

    /** Where the input field's Copy and Paste go; the system clipboard unless a test replaces it. */
    void setClipboard(ClipboardAccess clipboard) {
        this.clipboard = clipboard;
    }

    /**
     * Update the base directory size display.
     */
    public void setBaseDirSize(String sizeText) {
        statusBaseDirSize.setText("󰋊 " + sizeText);
    }

    /**
     * Start a single blip effect for visual feedback.
     * Brief attention-getting flash without being distracting.
     */
    // Pulsating boundary-prompt highlight, disabled behind the PULSATE_ENABLED flag (EP23);
    // the call site in showBoundaryExpansionPrompt stays commented out until revisited.
    // TODO(roadmap): re-enable or remove the pulsating blip together with the flag.
    @SuppressWarnings("UnusedMethod")
    private void startPulsating() {
        if (!PULSATE_ENABLED) return;

        if (pulsatingTimer != null) {
            pulsatingTimer.stop();
        }

        // Single blip: bright -> dim -> bright -> stop
        final Color orangeDim = DarculaColors.ORANGE_DIM;
        final Color orangeBright = DarculaColors.ORANGE;

        final int[] blipCount = {0}; // 0=bright, 1=dim, 2=bright, 3=stop

        pulsatingTimer = new Timer(500, e -> {
            blipCount[0]++;

            if (blipCount[0] >= 3) {
                // End of blip - restore to bright and stop
                boundaryPromptButton.setBackground(orangeBright);
                ((Timer) e.getSource()).stop();
                pulsatingTimer = null;
            } else {
                // Alternate between bright and dim
                Color color = (blipCount[0] % 2 == 1) ? orangeDim : orangeBright;
                boundaryPromptButton.setBackground(color);
            }
        });
        pulsatingTimer.start();
    }

    /**
     * Stop pulsating effect
     */
    private void stopPulsating() {
        if (pulsatingTimer != null) {
            pulsatingTimer.stop();
            pulsatingTimer = null;
        }
    }

    /**
     * Show boundary expansion prompt with highlighted date field and input bar message.
     * Highlights the date field in orange with a pulsating effect and shows
     * a suggestion prompt in the input bar.
     */
    public void showBoundaryExpansionPrompt(BoundaryRequest request) {
        log.info("Showing boundary expansion prompt: {}", request);

        this.pendingBoundaryRequest = request;
        Instant edge = request.type() == BoundaryType.BEFORE ? browsingStart : clampToNow(browsingEnd);
        setBoundaryPromptText(stepText(edge, request.suggestedBoundary()), request.suggestedBoundary());

        // Start subtle pulsating effect on the date field
        // startPulsating();

        this.setUpdatingBoundary(true);
    }

    /**
     * Cancel boundary expansion and restore normal display.
     *
     */
    public void disableBoundaryExpansionPrompt() {
        if (pendingBoundaryRequest == null) {
            return;
        }

        log.info("Disabling boundary expansion UI");
        setUpdatingBoundary(false);

        // Stop pulsating effect
        stopPulsating();

        // Clear pending state FIRST so setCurrentTimestamp will work
        pendingBoundaryRequest = null;

        // Trigger layout update
        revalidate();
        repaint();
    }

    private String formatTime(Instant instant) {
        return DateTimeFormatterManager.formatTimestamp(instant);
    }

    /** The Y/N button: the step from {@code edge} (delta first) when known, else the suggested endpoint. */
    private void setBoundaryPromptText(String text, Instant suggested) {
        boundaryPromptButton.setText(text + "?");
        boundaryPromptButton.setToolTipText(formatTime(suggested));
    }

    private Instant clampToNow(Instant t) {
        Instant now = nowSource.instant();
        return t == null || !t.isAfter(now) ? t : now;
    }

    private String stepText(Instant edge, Instant suggested) {
        Instant now = nowSource.instant();
        if (edge == null) {
            return DateTimeFormatterManager.formatRangeEndpoint(suggested, firstVisibleTimestamp, now);
        }
        return DateTimeFormatterManager.formatRangeStep(edge, suggested, firstVisibleTimestamp, now);
    }

    /** Enter or exit search input mode (user is typing a search term). */
    public void setSearchInputActive(boolean active) {
        if (active && !this.searchInputActive) {
            searchInputEdited = false;
        }
        this.searchInputActive = active;
        updateState();
    }

    /**
     * Pre-fill the search input with {@code term} and select all of it, so the next keystroke
     * replaces the term instead of appending to it. Once the user has typed in the input since it
     * opened, the typed text stays (only selected). With no term, whatever the field holds stays and
     * is selected. Used by the search keys, which always open the prompt this way.
     */
    public void prefillSearchInput(String term) {
        if (term != null && !term.isBlank() && !searchInputEdited) {
            setInputText(term);
        }
        inputField.selectAll();
        inputField.requestFocusInWindow();
    }

    /**
     * A one-line log selection while the input is open and the user has not typed in it: the input
     * shows the selection, all selected. Keyboard focus is left where it is.
     */
    public void offerSelectionToSearchInput(String selected) {
        if (!searchInputActive || searchInputEdited || selected == null || selected.isBlank()) {
            return;
        }
        setInputText(selected);
        inputField.selectAll();
    }

    /**
     * Enter or exit search active mode (results are available, F3/Shift-F3 navigates).
     * The query text and hit count are shown in the prompt, not editable.
     */
    public void setSearchActive(boolean active, String queryText) {
        this.searchActive = active;
        this.searchInputActive = false; // input mode always ends when active starts
        if (active) {
            this.lastSearchQuery = queryText;
        }
        // A new or cancelled search is not sitting on a previous match.
        this.searchMatchNumber = null;
        this.searchEdgeNote = null;
        this.searchHitCount = null;
        this.searchProvisional = false;
        this.searchWindowStart = null;
        this.searchWindowEnd = null;
        this.searchExtendingEdge = null;
        resetShownRange();
        this.searchExtendActive = false;
        this.searchExtendRequest = null;
        this.searchExtendNoMatchesFor = null;
        this.searchRecentreActive = false;
        this.searchRecentreRequest = null;
        setTextSearchPhase(active ? SearchStatusText.Phase.RUNNING : SearchStatusText.Phase.COMPLETE);
        updateState();
        renderSearchCenter();
    }

    /** True if user is currently typing a search term */
    public boolean isSearchInputActive() {
        return searchInputActive;
    }

    /** True if a search has been submitted and results are navigable */
    public boolean isSearchActive() {
        return searchActive;
    }

    /** Update the hit count display in both the prompt bar and the blue status bar center */
    public void updateSearchHitCount(int hitCount, boolean completed, Instant windowStart, Instant windowEnd) {
        Integer previousCount = this.searchHitCount;
        SearchStatusText.Phase previousPhase = this.textSearchPhase;
        this.searchHitCount = hitCount;
        this.searchWindowStart = windowStart;
        this.searchWindowEnd = windowEnd;
        if (textSearchPhase != SearchStatusText.Phase.STOPPED && textSearchPhase != SearchStatusText.Phase.FAILED) {
            textSearchPhase = completed ? SearchStatusText.Phase.COMPLETE : SearchStatusText.Phase.RUNNING;
        }
        endExtensionUnlessRunning();
        applySearchChrome();
        renderSearchStatus();
        hintMatches(previousCount, previousPhase);
    }

    /**
     * Flash the Matches tab when the search first has matches (the count goes from none to some) or when a
     * running search completes with matches. Streaming increases in between do not flash, and nothing flashes
     * while the Matches view is the active tab.
     */
    private void hintMatches(Integer previousCount, SearchStatusText.Phase previousPhase) {
        if (!searchActive || searchHitCount == null || searchHitCount <= 0 || (matchesMode && !bookmarksMode)) {
            return;
        }
        boolean firstMatch = previousCount == null || previousCount == 0;
        boolean completed = previousPhase == SearchStatusText.Phase.RUNNING
                && textSearchPhase == SearchStatusText.Phase.COMPLETE;
        if (firstMatch || completed) {
            viewTabs.flash(ViewTabs.Tab.MATCHES);
        }
    }

    /** Flash the Bookmarks tab: bookmarks were added while the Bookmarks view is not the one shown. */
    public void flashBookmarks() {
        if (!bookmarksMode) {
            viewTabs.flash(ViewTabs.Tab.BOOKMARKS);
        }
    }

    /**
     * Running shows a spinner and a stop square. Complete hides both.
     * Stopped keeps the matches and says so, without pretending the window was finished.
     */
    public void setTextSearchPhase(SearchStatusText.Phase phase) {
        SearchStatusText.Phase previousPhase = this.textSearchPhase;
        this.textSearchPhase = phase == null ? SearchStatusText.Phase.COMPLETE : phase;
        endExtensionUnlessRunning();
        applySearchChrome();
        renderSearchStatus();
        hintMatches(searchHitCount, previousPhase);
    }

    /**
     * Ask to widen the text-search window. {@code directionWord} is "later" or "earlier".
     * Y / N / Esc answer it; the gray exit button is hidden so it is not a second ×.
     */
    public void showSearchExtendPrompt(BoundaryRequest request, String directionWord) {
        showSearchExtendPrompt(request, directionWord, null);
    }

    /**
     * Same question for a search that found nothing: "No matches for "query" · search".
     * {@code noMatchesFor} is the query, or null for the walked-off-the-end wording.
     */
    public void showSearchExtendPrompt(BoundaryRequest request, String directionWord, String noMatchesFor) {
        this.searchExtendNoMatchesFor = noMatchesFor;
        this.searchExtendRequest = request;
        this.searchExtendDirection = directionWord == null ? "later" : directionWord;
        this.searchExtendActive = true;
        this.searchRecentreActive = false;
        this.searchRecentreRequest = null;
        Instant edge = "earlier".equals(searchExtendDirection) ? searchWindowStart : clampToNow(searchWindowEnd);
        setBoundaryPromptText(stepText(edge, request.suggestedBoundary()), request.suggestedBoundary());
        updateState();
    }

    /**
     * Ask {@code question} (a yes/no question about a scope change, e.g. bookmarks that would be
     * dropped). Y / N / Esc answer it through the same path as the boundary prompts.
     */
    public void showScopeConfirmPrompt(String question) {
        this.scopeConfirmText = question;
        boundaryPromptButton.setText("Yes");
        updateState();
    }

    public boolean hasScopeConfirmPrompt() {
        return scopeConfirmText != null;
    }

    public void clearScopeConfirmPrompt() {
        if (scopeConfirmText == null) {
            return;
        }
        scopeConfirmText = null;
        updateState();
    }

    public boolean hasSearchExtendPrompt() {
        return searchExtendActive && searchExtendRequest != null;
    }

    /** True while the open extend prompt is the zero-match wording. */
    boolean hasSearchExtendNoMatchesPrompt() {
        return hasSearchExtendPrompt() && searchExtendNoMatchesFor != null;
    }

    BoundaryRequest searchExtendRequest() {
        return searchExtendRequest;
    }

    public void clearSearchExtendPrompt() {
        if (!searchExtendActive && searchExtendRequest == null) {
            return;
        }
        searchExtendActive = false;
        searchExtendRequest = null;
        searchExtendNoMatchesFor = null;
        updateState();
    }

    public void showSearchRecentrePrompt(BoundaryRequest request) {
        this.searchRecentreRequest = request;
        this.searchRecentreActive = true;
        this.searchExtendActive = false;
        this.searchExtendRequest = null;
        this.searchExtendNoMatchesFor = null;
        Instant suggested = request.suggestedBoundary();
        setBoundaryPromptText(
                DateTimeFormatterManager.formatRangeEndpoint(suggested, firstVisibleTimestamp, nowSource.instant()),
                suggested);
        updateState();
    }

    public boolean hasSearchRecentrePrompt() {
        return searchRecentreActive && searchRecentreRequest != null;
    }

    BoundaryRequest searchRecentreRequest() {
        return searchRecentreRequest;
    }

    public void clearSearchRecentrePrompt() {
        if (!searchRecentreActive && searchRecentreRequest == null) {
            return;
        }
        searchRecentreActive = false;
        searchRecentreRequest = null;
        updateState();
    }

    /**
     * Where F3 last landed. {@code matchNumber} is 1-based, or null when no match
     * is selected. {@code edgeNote} is the lowercase end note, or null when the
     * next F3 / Shift-F3 can still move. Stored and rendered with the search
     * summary so a streaming hit-count update does not wipe it, and so it is not
     * forced over a boundary or modal prompt.
     */
    public void setSearchPosition(Integer matchNumber, int hitCount, String edgeNote) {
        this.searchMatchNumber = matchNumber;
        this.searchHitCount = hitCount;
        this.searchEdgeNote = edgeNote;
        renderSearchStatus();
    }

    /** Forget the selected match. The hit-count summary stays. */
    public void clearSearchPosition() {
        if (searchMatchNumber == null && searchEdgeNote == null) {
            return;
        }
        searchMatchNumber = null;
        searchEdgeNote = null;
        renderSearchStatus();
    }

    /**
     * Small FlatLaf round-rect buttons for the search chrome: a thin border and hover
     * fill, no focus stop, and no minimum size so they stay within the status row.
     */
    private static void styleChromeButton(JButton button, Font font, boolean iconOnly) {
        button.setFont(font);
        button.setBackground(DarculaColors.BACKGROUND);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        button.putClientProperty(FlatClientProperties.MINIMUM_WIDTH, 0);
        button.putClientProperty(FlatClientProperties.MINIMUM_HEIGHT, 0);
        button.setMargin(iconOnly ? new Insets(0, 4, 0, 4) : new Insets(0, 6, 0, 6));
        button.setFocusable(false);
    }

    /** Give the three text actions the same height as the icon chrome buttons beside them. */
    private void alignActionPillHeights() {
        int height = searchExitButton.getPreferredSize().height;
        selectionSearchButton.alignHeight(height);
        narrowButton.alignHeight(height);
        excludeButton.alignHeight(height);
    }

    /**
     * Show a transient state notice (e.g. "No matches", "Esc again to end search") in the
     * status row. One notice at a time: a new one replaces the previous one. It clears
     * itself after {@link #NOTICE_MILLIS}. Failures are toasts, not notices. EDT only.
     */
    public void showNotice(String text, Severity severity) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("showNotice must be called on the Event Dispatch Thread");
        }
        statusNotice.setText(text == null ? "" : text);
        statusNotice.setForeground(severity == Severity.WARN ? DarculaColors.ORANGE : FOREGROUND);
        statusNotice.setVisible(!statusNotice.getText().isEmpty());
        noticeTimer.restart();
        centerPanel.revalidate();
        centerPanel.repaint();
    }

    /** Remove the current notice, if any. EDT only. */
    void clearNotice() {
        noticeTimer.stop();
        if (!statusNotice.isVisible() && statusNotice.getText().isEmpty()) {
            return;
        }
        statusNotice.setText("");
        statusNotice.setVisible(false);
        centerPanel.revalidate();
        centerPanel.repaint();
    }

    String noticeText() {
        return statusNotice.getText();
    }

    Color noticeColor() {
        return statusNotice.getForeground();
    }

    boolean isNoticeTimerRunning() {
        return noticeTimer.isRunning();
    }

    /** Clear the blue bar search info (called when search is cancelled) */
    public void clearSearchInfo() {
        searchMatchNumber = null;
        searchEdgeNote = null;
        searchHitCount = null;
        searchProvisional = false;
        searchWindowStart = null;
        searchWindowEnd = null;
        searchExtendingEdge = null;
        resetShownRange();
        textSearchPhase = SearchStatusText.Phase.COMPLETE;
        searchExtendActive = false;
        searchExtendRequest = null;
        searchExtendNoMatchesFor = null;
        searchRecentreActive = false;
        searchRecentreRequest = null;
        applySearchChrome();
        renderSearchCenter();
    }

    private void renderSearchStatus() {
        // Only touch the prompt while search is what the user is looking at.
        // Forcing SEARCH_ACTIVE here would wipe a boundary or modal prompt.
        if (searchActive && inputState == InputState.SEARCH_ACTIVE && lastSearchQuery != null) {
            setState(InputState.SEARCH_ACTIVE, searchPromptDetail());
        } else if (searchActive && inputState == InputState.MATCHES && lastSearchQuery != null) {
            setState(InputState.MATCHES, searchPromptDetail());
        }
        renderSearchCenter();
    }

    private String searchPromptDetail() {
        if (!searchActive) {
            return "no search yet";
        }
        String query = lastSearchQuery != null ? lastSearchQuery : "";
        if (searchHitCount == null) {
            if (textSearchPhase == SearchStatusText.Phase.RUNNING) {
                return "\"%s\" — searching…".formatted(query);
            }
            return "\"%s\"".formatted(query);
        }
        String summary = SearchStatusText.summary(searchMatchNumber, searchHitCount, textSearchPhase, searchEdgeNote,
                isSearchTruncated(), isSearchTooManyWithinSecond(), searchProvisional);
        return summary.isEmpty() ? "\"%s\"".formatted(query) : "\"%s\" — %s".formatted(query, summary);
    }

    private void renderSearchCenter() {
        refreshMatchCount();
        renderCenter();
    }

    /**
     * The time group's range, never the match count (that is the Matches tab's). Without a search it is
     * the log's browsing range; with one it is the search window, labelled SEARCHING while the query runs and
     * SEARCHED after. A running extension shows the edge it moves towards, an end
     * note keeps its orange form, and growth of the shown range glares.
     */
    private void renderCenter() {
        Instant now = nowSource.instant();
        boolean running = textSearchPhase == SearchStatusText.Phase.RUNNING;
        boolean searchWindow = searchActive && searchWindowStart != null && searchWindowEnd != null;
        Instant start = null;
        Instant end = null;
        if (searchWindow) {
            start = searchWindowStart;
            end = searchWindowEnd;
        } else if (browsingStart != null && browsingEnd != null) {
            start = browsingStart;
            end = browsingEnd.isAfter(now) ? now : browsingEnd;
        }
        String edgeNote = searchActive ? searchEdgeNote : null;
        if (start == null || end == null || end.isBefore(start)) {
            if (edgeNote != null) {
                setCenter(SearchStatusText.center(edgeNote, null), DarculaColors.ORANGE, null);
            } else if (searchActive && searchHitCount != null && searchHitCount == 0 && !running) {
                setCenter("No matches", FOREGROUND, null);
            } else {
                setCenter("", FOREGROUND, null);
            }
            return;
        }

        Instant anchor = firstVisibleTimestamp;
        String range = DateTimeFormatterManager.formatRange(start, end, anchor, now);
        // Candidates from the full text to the barest; the first that fits the label is shown.
        String text = range;
        String shorter = range;
        if (searchActive) {
            if (searchExtendingEdge != null && running) {
                text = (searchExtendingLater ? "\u2192 " : "\u2190 ")
                        + DateTimeFormatterManager.formatRangeEndpoint(searchExtendingEdge, anchor, now);
                shorter = text;
            } else if (searchHitCount != null && searchHitCount == 0
                    && textSearchPhase == SearchStatusText.Phase.COMPLETE) {
                text = "No matches \u00b7 " + range;
                shorter = "No matches";
            }
        }
        String tooltip = DateTimeFormatterManager.formatRangeFull(start, end);
        if (edgeNote != null) {
            setCenter(SearchStatusText.center(edgeNote, text), DarculaColors.ORANGE, tooltip,
                    SearchStatusText.center(edgeNote, shorter), SearchStatusText.center(edgeNote, null));
        } else {
            setCenter(text, FOREGROUND, tooltip, shorter);
        }

        // A search that has no window yet shows the browsing range as a stand-in; it is not the baseline.
        if (searchWindow || !searchActive) {
            boolean grew = shownStart != null && shownEnd != null && !range.equals(shownRangeText)
                    && !start.isAfter(shownStart) && !end.isBefore(shownEnd)
                    && (start.isBefore(shownStart) || end.isAfter(shownEnd));
            shownStart = start;
            shownEnd = end;
            shownRangeText = range;
            if (grew) {
                centerFlash.start();
            }
        }
    }

    /** Supplies the full value and width fallbacks; the time group chooses what fits its range half. */
    private void setCenter(String text, Color color, String tooltip, String... fallbacks) {
        String word = searchActive && textSearchPhase == SearchStatusText.Phase.RUNNING
                ? "SEARCHING"
                : searchActive && searchWindowStart != null && searchWindowEnd != null ? "SEARCHED" : "RANGE";
        timeGroup.setRange(word, text, color, tooltip, fallbacks);
    }

    /** Forget what the centre showed, so the next range is a first one, not a growth (new search, logout). */
    private void resetShownRange() {
        shownStart = null;
        shownEnd = null;
        shownRangeText = null;
    }

    /** A user-set range replaces the old range rather than growing it. EDT only. */
    public void resetRangeBaseline() {
        resetShownRange();
        centerFlash.stop();
    }

    private void endExtensionUnlessRunning() {
        if (textSearchPhase != SearchStatusText.Phase.RUNNING) {
            searchExtendingEdge = null;
        }
    }

    /**
     * The log's browsing range, from the provider's before and after boundaries; null for either clears it
     * (logged out, welcome screen). Shown in the centre while no search window is. A range that grows glares.
     * EDT only.
     */
    public void setBrowsingRange(Instant start, Instant end) {
        Instant newStart = start == null || end == null ? null : start;
        Instant newEnd = start == null || end == null ? null : end;
        if (Objects.equals(newStart, browsingStart) && Objects.equals(newEnd, browsingEnd)) {
            return;
        }
        browsingStart = newStart;
        browsingEnd = newEnd;
        if (newStart == null && !searchActive) {
            resetShownRange();
        }
        renderCenter();
    }

    /**
     * A search extension is running towards {@code edge}: the centre reads {@code searching ← edge} (earlier)
     * or {@code searching → edge} (later) until the search stops running. Null clears. EDT only.
     */
    public void setSearchExtending(boolean later, Instant edge) {
        this.searchExtendingLater = later;
        this.searchExtendingEdge = edge;
        renderCenter();
    }

    /** The shown count includes provisional matches (instant refinement); it renders with a leading "~". */
    private boolean searchProvisional;
    private boolean searchTruncatedEarlier;
    private boolean searchTruncatedLater;
    private boolean searchTooManyMatchesWithinSecond;

    /** Whether the shown count still includes provisional matches (it then reads {@code ~212}). */
    public void setSearchProvisional(boolean provisional) {
        if (this.searchProvisional == provisional) {
            return;
        }
        this.searchProvisional = provisional;
        renderSearchStatus();
    }

    /** Which sides stopped at the match limit; the counts gain a "+". */
    public void setSearchTruncation(boolean truncatedEarlier, boolean truncatedLater,
            boolean tooManyMatchesWithinSecond) {
        this.searchTruncatedEarlier = truncatedEarlier;
        this.searchTruncatedLater = truncatedLater;
        this.searchTooManyMatchesWithinSecond = tooManyMatchesWithinSecond;
        renderSearchStatus();
    }

    private boolean isSearchTruncated() {
        return searchTruncatedEarlier || searchTruncatedLater || searchTooManyMatchesWithinSecond;
    }

    private boolean isSearchTooManyWithinSecond() {
        return searchTooManyMatchesWithinSecond;
    }

    /** The Matches tab shows the hit count while a search is active and has one; nothing otherwise. */
    private void refreshMatchCount() {
        viewTabs.setMatchCount(searchActive && searchHitCount != null ? searchCountText() : null);
    }

    /** A tab was clicked: take the same road as its key. */
    private void selectTab(ViewTabs.Tab tab) {
        switch (tab) {
            case LOG -> logViewer.showLog();
            case MATCHES -> {
                if (!logViewer.isMatchesView()) {
                    logViewer.toggleMatches();
                }
            }
            case BOOKMARKS -> logViewer.showBookmarks();
            case DETAILS -> logViewer.openCursorDetails();
        }
    }

    ViewTabs viewTabs() {
        return viewTabs;
    }

    private String searchCountText() {
        return SearchStatusText.count(searchHitCount, isSearchTruncated(), searchProvisional);
    }

    private void setUpdatingBoundary(boolean updatingBoundary) {

        // enable or disable boundary expansion mode
        if (this.updatingBoundary == updatingBoundary) {
            return;
        }

        this.updatingBoundary = updatingBoundary;
        updateState();
    }

    public void updateState() {
        refreshScrollLock();

        if (modalLogin) {
            setState(InputState.MODAL_LOGIN);
            return;
        }

        if (modalIndex) {
            setState(InputState.MODAL_INDEX);
            return;
        }

        if (scopeConfirmText != null) {
            setState(InputState.SCOPE_CONFIRM, scopeConfirmText);
            return;
        }

        if (updatingBoundary) {
            setState(InputState.BOUNDARY_EXPANSION);
            return;
        }

        if (searchExtendActive) {
            if (searchExtendNoMatchesFor != null) {
                setState(InputState.SEARCH_EXTEND_NONE, abbreviate(searchExtendNoMatchesFor));
            } else {
                setState(InputState.SEARCH_EXTEND, searchExtendDirection);
            }
            return;
        }

        if (searchRecentreActive) {
            setState(InputState.SEARCH_RECENTRE);
            return;
        }

        if (searchInputActive) {
            setState(InputState.SEARCH_INPUT);
            return;
        }

        if (helpMode) {
            setState(logViewer.isCursorMode() ? InputState.HELP_CURSOR : InputState.HELP);
            return;
        }

        if (detailsMode) {
            setState(InputState.DETAILS);
            return;
        }

        if (bookmarksMode) {
            setState(InputState.BOOKMARKS, bookmarksSummary());
            return;
        }

        if (matchesMode) {
            setState(InputState.MATCHES, searchPromptDetail());
            return;
        }

        if (searchActive) {
            setState(InputState.SEARCH_ACTIVE, searchPromptDetail());
            return;
        }

        setState(logViewer.getState());
    }

    private String bookmarksSummary() {
        return bookmarkCount + (bookmarkCount == 1 ? " bookmark" : " bookmarks");
    }

    /**
     * Enter or exit the Bookmarks view chrome: teal status-row background and the {@code BOOKMARKS}
     * tab; the prompt shows how many bookmarks there are. Takes precedence over the Matches chrome
     * while the Bookmarks view is open on top of it.
     */
    public void setBookmarksMode(boolean bookmarksMode) {
        if (this.bookmarksMode == bookmarksMode) {
            return;
        }
        this.bookmarksMode = bookmarksMode;
        applyMatchesChrome();
        applySearchChrome();
        updateState();
    }

    /**
     * The inline event details open or close over the current view: the DETAILS tab turns active (blue, whatever
     * view is underneath), the prompt hints the details keys and the {@code Prev} / {@code Next} buttons show.
     */
    public void setDetailsMode(boolean detailsMode) {
        if (this.detailsMode == detailsMode) {
            return;
        }
        this.detailsMode = detailsMode;
        applyMatchesChrome();
        updateState();
    }

    /** The welcome/help page is shown over a logged-in session; the prompt hints the scope and back keys. */
    public void setHelpMode(boolean helpMode) {
        if (this.helpMode == helpMode) {
            return;
        }
        this.helpMode = helpMode;
        applySearchChrome();
        updateState();
    }

    /** The details Narrow or Exclude tooltip: the action, with the key that does it in details. */
    static String detailsFieldText(String action, UiAction key, KeyMap keyMap) {
        return action + " (" + keyMap.label(key) + ")";
    }

    static String detailsStepText(KeyMap keyMap, boolean next) {
        return next
                ? "Next event (" + keyMap.label(UiAction.DETAILS_NEXT) + ")"
                : "Previous event (" + keyMap.label(UiAction.DETAILS_PREVIOUS) + ")";
    }

    /** The number of bookmarks the Bookmarks view's summary shows. */
    public void setBookmarkCount(int count) {
        if (bookmarkCount == count) {
            return;
        }
        bookmarkCount = count;
        viewTabs.setBookmarkCount(count);
        applySearchChrome();
        if (bookmarksMode) {
            updateState();
        }
    }

    /** The scroll-lock badge follows cursor mode: shown in page mode, blank while the cursor is on. */
    private void refreshScrollLock() {
        scrollLockShown = !logViewer.isCursorMode();
        scrollLockBadge.setText(scrollLockShown ? scrollLockGlyph : "");
        scrollLockBadge.setToolTipText(scrollLockShown ? scrollLockTooltip(KeyMap.preferred()) : null);
        scrollLockBadge.setCursor(java.awt.Cursor.getPredefinedCursor(
                scrollLockShown ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
    }

    static String scrollLockTooltip(KeyMap keyMap) {
        return "Scroll lock: arrows scroll the page (" + keyMap.label(UiAction.TOGGLE_CURSOR) + " toggles)";
    }

    /** Whether the scroll-lock badge is shown (page mode). EDT only. */
    boolean isScrollLockShown() {
        return scrollLockShown;
    }

    /**
     * Enter or exit the Matches view chrome: amber status-row background and the
     * {@code MATCHES} tab. The prompt itself switches via {@link #updateState()}.
     */
    public void setMatchesMode(boolean matchesMode) {
        if (this.matchesMode == matchesMode) {
            return;
        }
        this.matchesMode = matchesMode;
        applyMatchesChrome();
        updateState();
    }

    /** Switches the status row (and its labels) between the normal and Matches colours. */
    private void applyMatchesChrome() {
        Color bg = detailsMode
                ? DarculaColors.MIDNIGHT_BLUE
                : bookmarksMode
                        ? DarculaColors.BOOKMARKS_BAR
                        : matchesMode ? DarculaColors.MATCHES_BAR : DarculaColors.MIDNIGHT_BLUE;
        statusRow.setBackground(bg);
        leftPanel.setBackground(bg);
        rightPanel.setBackground(bg);
        statusNotice.setBackground(bg);
        loadProgressStatus.setBackground(bg);
        centerPanel.setBackground(bg);
        statusBaseDirSize.setBackground(bg);
        viewTabs.setActive(detailsMode
                ? ViewTabs.Tab.DETAILS
                : bookmarksMode ? ViewTabs.Tab.BOOKMARKS : matchesMode ? ViewTabs.Tab.MATCHES : ViewTabs.Tab.LOG);
    }

    /** Show the scope's narrowing and excluded text terms as chips. EDT only. */
    public void setScopeTerms(List<String> narrow, List<String> exclude) {
        scopeChips.setTerms(narrow, exclude);
    }

    /** Whether both selection buttons fit while leaving the input field its minimum width. */
    private boolean selectionButtonsFit() {
        if (inputBarLeft == null || inputBarLeft.getWidth() <= 0) {
            return true;
        }
        int required = 0;
        for (Component component : inputBarLeft.getComponents()) {
            if (component == inputField) {
                required += component.getMinimumSize().width;
            } else if (component.isVisible() || component == selectionSearchButton || component == narrowButton
                    || component == excludeButton) {
                required += component.getPreferredSize().width;
            }
        }
        return required <= inputBarLeft.getWidth();
    }

    /**
     * Spinner and stop square mean "the query is running".
     * The gray × means "leave search mode" and is hidden while an extend question is up,
     * so that question has only one dismiss control.
     */
    private void applySearchChrome() {
        if (searchExitButton == null) {
            return;
        }
        boolean running = textSearchPhase == SearchStatusText.Phase.RUNNING;
        boolean inSearch = searchActive && (inputState == InputState.SEARCH_ACTIVE || inputState == InputState.MATCHES);
        boolean promptBlocking = searchExtendActive || searchRecentreActive || scopeConfirmText != null;
        searchExitButton.setVisible(inSearch && !promptBlocking);
        KeyMap keyMap = KeyMap.preferred();
        boolean selectionView = switch (inputState) {
            case SCROLL, CURSOR, SELECTING, SEARCH_ACTIVE, MATCHES, BOOKMARKS -> true;
            default -> false;
        };
        boolean selectionActions = selectionTerm != null && selectionView && !promptBlocking && !detailsMode
                && (inSearch || selectionButtonsFit());
        selectionSearchButton
                .setToolTipText(RowContextMenu.searchSelectionText(selectionActions ? selectionTerm : null));
        selectionSearchButton.setVisible(selectionActions);
        boolean detailsActions = detailsMode && !promptBlocking;
        if (detailsMode) {
            narrowButton.setToolTipText(
                    detailsFieldText("Narrow scope to the field's value", UiAction.DETAILS_NARROW_FIELD, keyMap));
            excludeButton.setToolTipText(
                    detailsFieldText("Exclude the field's value from scope", UiAction.DETAILS_EXCLUDE_FIELD, keyMap));
        } else {
            narrowButton.setToolTipText(selectionActions
                    ? RowContextMenu.narrowSelectionText(selectionTerm)
                    : MenuManager.narrowText(keyMap));
            excludeButton.setToolTipText(selectionActions
                    ? RowContextMenu.excludeSelectionText(selectionTerm)
                    : MenuManager.excludeText(keyMap));
        }
        narrowButton.setVisible(detailsActions || selectionActions || (inSearch && !promptBlocking));
        excludeButton.setVisible(detailsActions || selectionActions || (inSearch && !promptBlocking));
        searchStopButton.setVisible(running && inSearch && !promptBlocking);
        textSearchSpinner.setVisible(running);
        textSearchSpinner.setIndeterminate(running);
        boolean anyBookmarks = bookmarkCount > 0;
        bookmarksCopyButton.setVisible(bookmarksMode && !detailsMode);
        bookmarksCopyButton.setEnabled(anyBookmarks);
        bookmarksExportButton.setVisible(bookmarksMode && !detailsMode);
        bookmarksExportButton.setEnabled(anyBookmarks);
        detailsPreviousButton.setToolTipText(detailsStepText(keyMap, false));
        detailsNextButton.setToolTipText(detailsStepText(keyMap, true));
        detailsPreviousButton.setVisible(detailsMode && !promptBlocking);
        detailsNextButton.setVisible(detailsMode && !promptBlocking);
    }

    /**
     * Change input state and update UI accordingly
     */
    private static String abbreviate(String query) {
        return query.length() <= 40 ? query : query.substring(0, 39) + "…";
    }

    private void setState(InputState state, String... args) {

        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("setState must be called on the Event Dispatch Thread");
        }

        log.info("Input state changed to {}", state.name());

        setBoundaryButtonsVisible(state == InputState.BOUNDARY_EXPANSION || state == InputState.SEARCH_EXTEND
                || state == InputState.SEARCH_EXTEND_NONE || state == InputState.SEARCH_RECENTRE
                || state == InputState.SCOPE_CONFIRM);

        boolean entering = this.inputState != state;
        if (entering) {
            setInputText("");
        }

        this.inputState = state;
        if (entering && state.needsInput()) {
            inputFlash.start();
        }

        // Format the prompt text: key tokens resolve against the key map, then the %s args fill in.
        String formattedPrompt = state.text(args);
        promptField.setText(formattedPrompt);

        // Calculate preferred width based on the actual displayed text
        int promptWidth = promptField.getFontMetrics(promptField.getFont()).stringWidth(formattedPrompt) + 20; // Extra padding for comfortable spacing
        Dimension d = new Dimension(promptWidth, promptField.getPreferredSize().height);
        promptField.setPreferredSize(d);
        promptField.setMinimumSize(d);
        promptField.setMaximumSize(d);

        inputField.setEditable(state.editable);
        inputField.setFocusable(state.editable);

        String hint = state.hint(KeyMap.preferred());
        inputHint.setText(hint);
        inputHint.setVisible(!hint.isEmpty());
        applySearchChrome();

        // Trigger layout update
        Container parent = promptField.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
        revalidate();
        repaint();

        // Handle focus transfer for editable states only
        // For non-editable states, the caller is responsible for focus management
        // (e.g., login overlay will request its own focus)
        if (state.editable) {
            SwingUtilities.invokeLater(inputField::requestFocus);
        }
    }

    InputState inputState() {
        return inputState;
    }

    JTextField inputField() {
        return inputField;
    }

    String inputHintText() {
        return inputHint.isVisible() ? inputHint.getText() : "";
    }

    String searchPromptText() {
        return promptField.getText();
    }

    String searchCenterText() {
        return timeGroup.rangeText();
    }

    String boundaryPromptText() {
        return boundaryPromptButton.getText();
    }

    String boundaryPromptTooltip() {
        return boundaryPromptButton.getToolTipText();
    }

    TimeGroup centerLabel() {
        return timeGroup;
    }

    String centerTooltip() {
        return timeGroup.rangeTooltip();
    }

    boolean isCenterShown() {
        return timeGroup.hasRange();
    }

    String centerWord() {
        return timeGroup.rangeWord();
    }

    String dateWord() {
        return timeGroup.dateWord();
    }

    /** Clicks on the time group's halves; the listener runs on the EDT without transferring focus. */
    public void setTimeGroupListener(Consumer<TimeGroup.Half> listener) {
        timeGroup.setOnClick(listener);
    }

    public void setFirstVisibleTimestamp(Instant firstVisibleTimestamp) {
        this.firstVisibleTimestamp = firstVisibleTimestamp;
        renderDateField();
        // The range text is relative to the first visible line's date.
        renderCenter();
    }

    /** The time range of the selection, shown in the date field while two or more rows are selected; null to clear. */
    public void setSelectionSpan(CursorManager.SelectionSpan span) {
        this.selectionSpan = span;
        renderDateField();
    }

    /** The selected one-line term offered by Narrow and Exclude, or null to offer the active search instead. */
    public void setSelectionTerm(String term) {
        if (Objects.equals(selectionTerm, term)) {
            return;
        }
        selectionTerm = term;
        applySearchChrome();
    }

    private void renderDateField() {
        String text;
        if (selectionSpan != null) {
            text = DateTimeFormatterManager.formatSelectionSpan(selectionSpan.first(), selectionSpan.last());
        } else {
            text = firstVisibleTimestamp == null ? "" : formatTime(firstVisibleTimestamp);
        }
        timeGroup.setDate(selectionSpan == null ? "AT" : "SPAN", text);
    }

    String dateFieldText() {
        return timeGroup.dateText();
    }

    public Optional<Instant> getFirstVisibleTimestamp() {
        return Optional.ofNullable(firstVisibleTimestamp);
    }

    /** Update the load spinner and persistent waiting message without touching the prompt. EDT only. */
    public void updateSearchProgress(SearchProgress progress) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("updateSearchProgress must be called on the Event Dispatch Thread");
        }

        boolean loading = progress != null && !progress.isDone();
        String status = loading && progress.isWaitingForSplunk() ? progress.statusText() : "";
        loadProgressStatus.setText(status);
        loadProgressStatus.setVisible(!status.isEmpty());
        searchSpinner.setVisible(loading);
        revalidate();

        if (!loading) {
            searchSpinner.setIndeterminate(false);
            searchSpinner.setIcon(null);
            searchSpinner.repaint();
            return;
        }

        if (progress.direction() > 0) {
            searchSpinner.setIcon(downArrow);
        } else if (progress.direction() < 0) {
            searchSpinner.setIcon(upArrow);
        } else {
            searchSpinner.setIcon(null);
        }

        if (progress.indeterminate()) {
            searchSpinner.setIndeterminate(true);
        } else {
            searchSpinner.setIndeterminate(false);
            searchSpinner.setMinimum(0);
            searchSpinner.setMaximum(100);
            int value = Math.round(progress.doneProgress() * 100);
            searchSpinner.setValue(value);
        }

        searchSpinner.repaint();
    }

    JLabel loadProgressLabel() {
        return loadProgressStatus;
    }

    SpinnerProgress loadSpinner() {
        return searchSpinner;
    }

    public void setModalLogin(boolean modalLogin) {

        if (this.modalLogin == modalLogin) {
            return;
        }

        this.modalLogin = modalLogin;
        updateState();
    }

    public boolean isModalLogin() {
        return modalLogin;
    }

    public void setModalIndex(boolean modalIndex) {
        if (this.modalIndex == modalIndex) {
            return;
        }
        this.modalIndex = modalIndex;
        updateState();
    }

    public boolean isModalIndex() {
        return modalIndex;
    }
}
