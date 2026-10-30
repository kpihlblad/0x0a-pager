package se.grynna.pager.splunk;

import static se.grynna.pager.splunk.SearchBoundaries.MIN_MINIMUM_BOUNDARY;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.exceptions.SearchResultToBigException;
import se.grynna.pager.splunk.DirectionLater.BoundaryPrompt;
import se.grynna.pager.splunk.DirectionLater.BoundaryStop;
import se.grynna.pager.ui.BoundaryRequest;
import se.grynna.pager.ui.LogDataProvider;

/**
 * Strategy for deciding when and how much data to load based on scroll position and boundaries.
 * Also handles boundary expansion prompting with debouncing to prevent excessive prompts.
 * Owns and manages the search boundaries.
 */
@Slf4j
public class LoadingStrategy {

    private final SearchBoundaries searchBoundaries;
    /**
     * State machine for loading earlier (scrolling up, direction -1).
     */
    private DirectionEarlier upState = new DirectionEarlier.Init();

    /**
     * State machine for loading later (scrolling down, direction +1).
     */
    private DirectionLater downState = new DirectionLater.Init();

    /**
     * Duration for the initial search when loading a new segment
     */
    public static final Duration INITIAL_SEARCH_DURATION = Duration.ofSeconds(60);

    /**
     * Smallest window worth splitting: Splunk floors a sub-second earliest_time to the whole second,
     * so a window whose effective span is at most this cannot be narrowed further.
     */
    public static final Duration MIN_SPLITTABLE_WINDOW = Duration.ofSeconds(1);

    /**
     * Offset from current time for the noLaterThan boundary to account for indexing delays
     */
    public static final Duration NO_LATER_THAN_OFFSET = Duration.ofMinutes(5);

    // Starts when browsing first requires an unloaded row, not at prefetch time.
    // Guarded by lock.
    private Instant browsingLoadStarted;

    /**
     * Listener that handles boundary expansion prompts and user responses
     */
    private LogDataProvider.BoundaryExpansionListener boundaryExpansionListener;

    /**
     * Clock instance for computing dynamic time boundaries (allows testing with fixed time)
     */
    private final Clock clock;

    /**
     * Lock for synchronization.
     */
    private final Object lock = new Object();

    /**
     * Create a new LoadingStrategy.
     *
     * @param clock Clock for time operations (allows testing with fixed time)
     * @param searchBoundaries the current search boundaries (before/after/now) that
     *                         constrain which log ranges may be loaded
     */
    public LoadingStrategy(Clock clock, SearchBoundaries searchBoundaries) {
        this.clock = clock;
        this.searchBoundaries = searchBoundaries;
    }

    DirectionEarlier getUpState() {
        return upState;
    }

    DirectionLater getDownState() {
        return downState;
    }

    /**
     * Reset the loading strategy state.
     */
    public void reset(Instant target, int targetLine, Instant xbeforeBoundary, Instant xafterBoundary) {
        synchronized (lock) {

            // Reset both direction state machines
            this.upState = new DirectionEarlier.Init();
            this.downState = new DirectionLater.Init();
            browsingLoadStarted = null;

            if (target.equals(searchBoundaries.getNowBoundary())) {
                log.info("Reset DOWN to AtNowBoundary at {}", searchBoundaries.getNowBoundary());
                this.downState = new DirectionLater.AtNowBoundary(
                        searchBoundaries.getNowBoundary(), searchBoundaries.getMaxBoundary(), this.downState);
            }

            log.info(
                    "LoadingStrategy reset: target={}, targetLine={}, beforeBoundary={}, afterBoundary={}",
                    target,
                    targetLine,
                    searchBoundaries.getMinBoundary(),
                    searchBoundaries.getMaxBoundary());
        }
    }

    /**
     * Set the listener for boundary expansion prompts.
     * This allows the strategy to prompt the user when appropriate.
     */
    public void setBoundaryExpansionListener(LogDataProvider.BoundaryExpansionListener listener) {
        this.boundaryExpansionListener = listener;
    }

    public void noteUnloadedRowNeeded() {
        synchronized (lock) {
            if (browsingLoadStarted == null) {
                browsingLoadStarted = clock.instant();
            }
        }
    }

    private boolean autoApproveExpansion() {
        return ExpansionAutoApprove.shortSince(browsingLoadStarted, clock.instant());
    }

    public void recordTooBigResult(SearchResultToBigException e, LoadDecision decision, boolean b) {
        synchronized (lock) {
            int direction = decision.direction();

            if (direction > 0) {
                // DOWN direction - delegate to downState
                DirectionLater newState =
                        downState.recordTooBigResult(e, decision, searchBoundaries.getEffectiveMaxBoundary());
                log.info("DOWN tooBig {}: {} -> {}", e.getResultCount(), downState, newState);
                this.downState = newState;
            } else {
                // UP direction - delegate to upState
                DirectionEarlier newState = upState.recordTooBigResult(e, decision, searchBoundaries.getMinBoundary());
                log.info("UP tooBig {}: {} -> {}", e.getResultCount(), upState, newState);
                this.upState = newState;
            }
        }
    }

    /**
     * Record the result of a load operation to track velocity.
     * Call this after every load (not just expansions) to track overall log density.
     *
     * @param loadedLogLines logs loaded
     * @param decision       Duration of the time range that was searched
     */
    public boolean recordLoadResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision) {
        synchronized (lock) {
            if (loadedLogLines.logLines().isEmpty()) {
                return recordEmptyResult(lineNo, decision);
            } else {
                return recordNormalResult(lineNo, loadedLogLines, decision);
            }
        }
    }

    /**
     * Internal method to record load results (called within synchronized block).
     * Transitions the appropriate direction state machine based on the result.
     * Triggers boundary expansion prompts when hitting AtBoundary state.
     */
    private boolean recordEmptyResult(int lineNo, LoadDecision decision) {
        int direction = decision.direction();
        Instant nowBoundary = searchBoundaries.getNowBoundary();

        if (direction > 0) {
            // DOWN direction - delegate to downState (uses static BoundaryExpansionHelper internally)
            DirectionLater newState =
                    downState.recordEmptyResult(lineNo, decision, searchBoundaries.getMaxBoundary(), nowBoundary);
            log.info("DOWN empty: {} -> {}", downState, newState);
            this.downState = newState;
            if (newState instanceof BoundaryPrompt prompt) {
                triggerBoundaryPromptAfter(prompt);
            } else {
                searchBoundaries.setMaxBoundary(newState.getMaxBoundary());
            }
        } else {
            // UP direction - delegate to upState
            DirectionEarlier newState =
                    upState.recordEmptyResult(lineNo, decision, searchBoundaries.getMinBoundary(), null);
            log.info("UP empty: {} -> {}", upState, newState);
            this.upState = newState;
            if (newState instanceof DirectionEarlier.BoundaryPrompt prompt) {
                triggerBoundaryPromptBefore(prompt);
            } else {
                searchBoundaries.setMinBoundary(newState.getMinBoundary());
            }
        }

        return true; // empty loads can always update the segment.
    }

    /**
     * Internal method to record load results (called within synchronized block).
     * Transitions the appropriate direction state machine based on the result.
     * Triggers boundary expansion prompts when hitting AtBoundary state.
     */
    private boolean recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision) {
        int direction = decision.direction();

        final boolean shouldStore;
        if (direction > 0) {
            // DOWN direction - delegate to downState
            DirectionLater newState = downState.recordNormalResult(
                    lineNo,
                    loadedLogLines,
                    decision,
                    searchBoundaries.getMaxBoundary(),
                    searchBoundaries.getNowBoundary());
            log.info("DOWN loaded {}: {} -> {}", loadedLogLines.logLines().size(), downState, newState);
            this.downState = newState;
            shouldStore = newState.shouldStore();
            if (newState instanceof BoundaryPrompt prompt) {
                triggerBoundaryPromptAfter(prompt);
            } else {
                searchBoundaries.setMaxBoundary(newState.getMaxBoundary());
            }
        } else {
            // UP direction - delegate to upState
            DirectionEarlier newState =
                    upState.recordNormalResult(lineNo, loadedLogLines, decision, searchBoundaries.getMinBoundary());
            log.info("UP loaded {}: {} -> {}", loadedLogLines.logLines().size(), upState, newState);
            this.upState = newState;
            shouldStore = newState.shouldStore();
            if (newState instanceof DirectionEarlier.BoundaryPrompt prompt) {
                triggerBoundaryPromptBefore(prompt);
            } else {
                searchBoundaries.setMinBoundary(newState.getMinBoundary());
            }
        }

        return shouldStore;
    }

    /**
     * Calculate the log rate (logs per second) from a segment.
     * Returns 0 if rate cannot be determined.
     *
     * @param segment The segment to calculate rate from
     * @return logs per second, or 0 if rate cannot be determined
     */
    public double getSegmentRate(LogSegment segment) {
        if (segment.size() < 2) {
            return 0;
        }

        Duration timeSpan = Duration.between(segment.getStartTime(), segment.getEndTime());
        double seconds = timeSpan.toMillis() / 1000.0;

        if (seconds <= 0) {
            return 0;
        }

        return (segment.size() / seconds);
    }

    /**
     * Thread-local flag indicating whether current evaluation is for an edge request.
     * Used by logElr() to determine log level.
     */
    private final ThreadLocal<Boolean> isEdgeRequest = ThreadLocal.withInitial(() -> false);

    /**
     * Log ELR (evaluate line request) messages at DEBUG level for edge requests,
     * TRACE level for non-edge requests. Errors/warnings/info should use standard log calls.
     */
    private void logElr(String format, Object... args) {
        // if (Boolean.TRUE.equals(isEdgeRequest.get())) {
        log.debug(format, args);
        // } else {
        //    log.trace(format, args);
        // }
    }

    /**
     * Evaluate whether we need to load data for a specific line number.
     * Internal version with custom reason string.
     *
     * @param lineNumber The requested line number
     * @param segment    Current loaded segment
     * @param edge       for logging
     * @return Decision about whether to load and how much
     */
    public LoadDecision evaluateLineRequest(int lineNumber, LogSegment segment, boolean edge) {

        // Set edge flag for this evaluation - affects log level in logElr()
        isEdgeRequest.set(edge);
        try {
            int offset = segment.offset(lineNumber);

            logElr("ELR: Evaluating line={} offset={}", lineNumber, offset);

            if (offset == 0) {
                logElr("ELR: line {} is within loaded segment, no load needed", lineNumber);
                return LoadDecision.noLoad(null, "line " + lineNumber + " within loaded segment");
            }

            String loadReason = (offset < 0
                    ? "getLine " + lineNumber + " before segment"
                    : "getLine " + lineNumber + " after segment");

            // Line is outside segment - determine direction and load
            return offset < 0
                    ? evaluateLoadBeforeSegment(lineNumber, segment, loadReason)
                    : evaluateLoadAfterSegment(lineNumber, segment, loadReason);
        } finally {
            isEdgeRequest.remove();
        }
    }

    /**
     * Evaluate loading data before the segment (scrolling up/earlier).
     * Reads the UP state machine and produces a LoadDecision.
     * State transitions and boundary prompts can happen here or in recordLoadResult/recordTooBigResult.
     */
    private LoadDecision evaluateLoadBeforeSegment(int lineNumber, LogSegment segment, String loadReason) {

        synchronized (lock) {
            Instant beforeBoundary = searchBoundaries.getMinBoundary();
            // The segment can already start on the min boundary (a day-aligned jump,
            // or an earlier load whose window was clamped to that instant). There is
            // then no [boundary, segmentStart) to query — asking for the line above
            // the segment must prompt, not throw a zero-length load.
            if (!(upState instanceof DirectionEarlier.BoundaryPrompt)
                    && !(upState instanceof DirectionEarlier.BoundaryStop)
                    && !DirectionEarlier.hasEarlierWindow(segment.getStartTime(), beforeBoundary)) {
                DirectionEarlier.BoundaryPrompt prompt = DirectionEarlier.promptAtMinBoundary(beforeBoundary);
                log.info("UP segment already at min boundary {}: {} -> {}", beforeBoundary, upState, prompt);
                this.upState = prompt;
                triggerBoundaryPromptBefore(prompt);
            }
            return upState.evaluateLoad(segment, searchBoundaries.getMinBoundary(), lineNumber, loadReason);
        }
    }

    /**
     * Evaluate loading data after the segment (scrolling down/later).
     * Reads the DOWN state machine and produces a LoadDecision.
     * State transitions and boundary prompts can happen here or in recordLoadResult/recordTooBigResult.
     */
    // Identity comparison is intentional: checkBoundary returns `this` when
    // NOW has not advanced, so a different instance means the state actually
    // transitioned.
    @SuppressWarnings("ReferenceEquality")
    private LoadDecision evaluateLoadAfterSegment(int lineNumber, LogSegment segment, String loadReason) {
        synchronized (lock) {
            Instant nowBoundary = searchBoundaries.getNowBoundary();

            DirectionLater newState = downState.checkBoundary(nowBoundary, lineNumber);
            if (newState != downState) {
                log.info("DOWN now advanced: {} -> {}", downState, newState);
                if (newState instanceof BoundaryPrompt prompt) {
                    triggerBoundaryPromptAfter(prompt);
                }
                this.downState = newState;
            }

            DirectionLater exhausted = DirectionLater.whenNoLaterWindow(
                    downState, segment.getEndTime(), searchBoundaries.getMaxBoundary(), nowBoundary);
            if (exhausted != null) {
                log.info(
                        "DOWN segment already at max boundary {}: {} -> {}",
                        segment.getEndTime(),
                        downState,
                        exhausted);
                this.downState = exhausted;
                if (exhausted instanceof BoundaryPrompt prompt) {
                    triggerBoundaryPromptAfter(prompt);
                }
            }

            return downState.evaluateLoad(
                    segment, searchBoundaries.getMaxBoundary(), nowBoundary, lineNumber, loadReason);
        }
    }

    // set states to BoundaryStop
    public void cancel() {
        synchronized (lock) {
            upState = new DirectionEarlier.BoundaryStop(searchBoundaries.getMinBoundary());
            downState = new DirectionLater.BoundaryStop(searchBoundaries.getMaxBoundary());
        }
    }

    ///  ///  ///  ///  ///  ///  ///  ///  ///
    ///  /// Boundary Expansion Logic  ///  ///
    ///  ///  ///  ///  ///  ///  ///  ///  ///

    public void unfreezeBeforeBoundary() {
        synchronized (lock) {
            if (upState instanceof DirectionEarlier.BoundaryStop stop) {
                var newUpState = stop.unfreezeBoundary();
                log.info("UP unfreeze BEFORE boundary: {} -> {}", upState, newUpState);
                this.upState = newUpState;
                if (newUpState instanceof DirectionEarlier.BoundaryPrompt prompt) {
                    triggerBoundaryPromptBefore(prompt);
                }
            } else {
                log.warn("UP unfreeze BEFORE but state is not BoundaryStop: {}", upState);
            }
        }
    }

    public void unfreezeAfterBoundary() {
        synchronized (lock) {
            if (downState instanceof BoundaryStop stop) {
                var newDownState = stop.unfreezeBoundary(searchBoundaries.getNowBoundary());
                log.info("DOWN unfreeze AFTER boundary: {} -> {}", downState, newDownState);
                this.downState = newDownState;
                if (newDownState instanceof BoundaryPrompt prompt) {
                    triggerBoundaryPromptAfter(prompt);
                }
            } else {
                log.warn("DOWN unfreeze AFTER but state is not BoundaryStop: {}", downState);
            }
        }
    }

    /**
     * Trigger boundary expansion prompt when transitioning to AtBoundary state.
     * Called from recordLoadResult/recordTooBigResult when search exhausted a direction.
     * Uses the pre-calculated suggestedBoundary from the prompt state.
     */
    private void triggerBoundaryPromptBefore(DirectionEarlier.BoundaryPrompt boundaryPrompt) {
        if (boundaryExpansionListener == null) {
            log.debug("UP boundary: no listener");
            return;
        }

        if (!searchBoundaries.getMinBoundary().isAfter(MIN_MINIMUM_BOUNDARY)) {
            log.debug("UP boundary: at epoch, cannot expand");
            return;
        }

        var suggestedBoundary = boundaryPrompt.suggestedBoundary();
        if (autoApproveExpansion()) {
            log.info("UP boundary: auto-expanding to {} during short browsing load", suggestedBoundary);
            expansionReply(BoundaryType.BEFORE, true, suggestedBoundary);
            return;
        }
        browsingLoadStarted = clock.instant();
        log.info("UP boundary: prompting expansion to {}", suggestedBoundary);

        boundaryExpansionListener.requestBoundaryExpansion(
                new BoundaryRequest(BoundaryType.BEFORE, suggestedBoundary, this::expansionReply));
    }

    /**
     * Trigger boundary expansion prompt for AFTER direction.
     * Uses the pre-calculated suggestedBoundary from the prompt state.
     */
    private void triggerBoundaryPromptAfter(BoundaryPrompt boundaryPrompt) {
        if (boundaryExpansionListener == null) {
            log.debug("DOWN boundary: no listener");
            return;
        }

        var nowBoundary = searchBoundaries.getNowBoundary();
        if (!searchBoundaries.getMaxBoundary().isBefore(nowBoundary)) {
            log.debug("DOWN boundary: at NOW, cannot expand");
            return;
        }

        var suggestedBoundary = boundaryPrompt.suggestedBoundary();
        if (autoApproveExpansion()) {
            log.info("DOWN boundary: auto-expanding to {} during short browsing load", suggestedBoundary);
            expansionReply(BoundaryType.AFTER, true, suggestedBoundary);
            return;
        }
        browsingLoadStarted = clock.instant();
        log.info("DOWN boundary: prompting expansion to {}", suggestedBoundary);

        boundaryExpansionListener.requestBoundaryExpansion(
                new BoundaryRequest(BoundaryType.AFTER, suggestedBoundary, this::expansionReply));
    }

    /**
     * Called by UI when user accepts boundary expansion.
     * Updates internal state and clears prompt lock.
     *
     * @param type        the boundary type being expanded
     * @param accepted    whether the expansion was accepted
     * @param newBoundary the new boundary to use if accepted
     */
    public void expansionReply(BoundaryType type, boolean accepted, Instant newBoundary) {
        synchronized (lock) {
            switch (type) {
                case BEFORE -> {
                    if (upState instanceof DirectionEarlier.BoundaryPrompt state) {
                        var newState = state.expansionReply(accepted, newBoundary);
                        log.info("UP expansion {}: {} -> {}", accepted ? "accepted" : "rejected", upState, newState);
                        this.upState = newState;
                        if (accepted) {
                            searchBoundaries.setMinBoundary(newBoundary);
                        }
                    } else {
                        log.debug("UP expansion reply: no prompt state");
                    }
                }
                case AFTER -> {
                    if (downState instanceof BoundaryPrompt state) {
                        var newState = state.expansionReply(accepted, newBoundary);
                        log.info(
                                "DOWN expansion {}: {} -> {}", accepted ? "accepted" : "rejected", downState, newState);
                        this.downState = newState;
                        if (accepted) {
                            searchBoundaries.setMaxBoundary(newBoundary);
                        }
                    } else {
                        log.debug("DOWN expansion reply: no prompt state");
                    }
                }
                case NOW -> throw new IllegalArgumentException("Cannot expand NOW boundary");
            }
        }
    }

    /**
     * Calculate the initial search time range for a new segment centered on the target timestamp.
     * The range is centered on the target and clamped to the configured boundaries.
     *
     * @param target The target timestamp to center the search around
     * @return A LoadDecision containing the clamped start and end times for the initial search
     */
    public LoadDecision calculateInitialSearchRange(Instant target) {
        synchronized (lock) {
            // Load a time range centered on the target
            var halfDuration = INITIAL_SEARCH_DURATION.dividedBy(2);

            var end = target.plus(halfDuration);

            // Clamp end to max boundary - prefer extending up.
            var effectiveMax = searchBoundaries.getEffectiveMaxBoundary();
            var clampedEnd = clampToMaxBoundary(end, effectiveMax);
            boolean clampedToEnd = (clampedEnd.compareTo(end) != 0);

            var start = clampedEnd.minus(INITIAL_SEARCH_DURATION);
            var clampedStart = clampToMin(start, searchBoundaries.getMinBoundary(), "initial start");
            boolean clampedToStart = (clampedStart.compareTo(start) != 0);

            String reason = "initial segment load"
                    + (clampedToStart ? " (clamped start from " + start + ")" : "")
                    + (clampedToEnd ? " (clamped end from " + end + ")" : "");

            var decision = LoadDecision.load(upState, -1, clampedStart, clampedEnd, reason);

            log.debug("calculateInitialSearchRange: target {} : {}", target, decision);
            return decision;
        }
    }

    /**
     * Clamp a timestamp to not go before a lower boundary.
     */
    private Instant clampToMin(Instant time, Instant minBoundary, String descr) {
        if (minBoundary == null) return time;
        if (time.isBefore(minBoundary)) {
            log.debug("Clamping {} {} to min boundary {}", descr, time, minBoundary);
            return minBoundary;
        }
        return time;
    }

    /**
     * Clamp a timestamp to not go after a higher boundary.
     */
    private Instant clampToMaxBoundary(Instant time, Instant max) {

        if (time.isAfter(max)) {
            return max;
        }
        return time;
    }

    public boolean isLoadingUp() {
        synchronized (lock) {
            return upState.isLoading();
        }
    }

    public boolean isLoadingDown() {
        synchronized (lock) {
            return downState.isLoading();
        }
    }
}
