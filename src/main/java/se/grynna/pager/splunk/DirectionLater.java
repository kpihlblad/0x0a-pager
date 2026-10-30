package se.grynna.pager.splunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.grynna.pager.exceptions.SearchResultToBigException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static se.grynna.pager.splunk.DirectionStateBase.fmtV;
import static se.grynna.pager.splunk.LoadingStrategy.INITIAL_SEARCH_DURATION;
import static se.grynna.pager.splunk.LoadingStrategy.MIN_SPLITTABLE_WINDOW;

/**
 * State machine for loading later (DOWN direction, scrolling forward in time).
 * <p>
 * Splunk returns results newest-first, so when searching forward:
 * - Truncation may happen before finding sparse logs near the end
 * - We need proper binary search to handle this
 * <p>
 * ## State Transitions (DOWN direction)
 * <p>
 * Init → Normal (first load with results and velocity)
 * Init → BinarySearchLater (first load returns 0 results)
 * <p>
 * Normal → Normal (successful load, update velocity)
 * Normal → BinarySearchLater (0 results, start seeking sparse region)
 * Normal → AtBoundary (reached NOW hard limit)
 * <p>
 * BinarySearchLater (proper binary search):
 * Uses bsDuration that iteratively halves the search space.
 * On empty result: check boundary, else halve
 * On TooBig result: halve search space
 * → Normal when results found (calculate velocity, segment expanded)
 * → AtBoundary when search space exhausted
 * <p>
 * AtBoundary → BinarySearchLater (boundary expanded, restart search)
 */
public sealed interface DirectionLater extends DirectionStateBase
        permits DirectionLater.Init, DirectionLater.Normal, DirectionLater.BinarySearchLater,
        DirectionLater.BoundaryPrompt, DirectionLater.AtNowBoundary, DirectionLater.BoundaryStop {

    Logger log = LoggerFactory.getLogger(DirectionLater.class);

    int MAX_TARGET_LOAD_LINES = 2500;

    /**
     * Maximum events to request per query during sparse seeking.
     */
    int MAX_EVENTS_LIMIT = 3000;

    /**
     * Minimum search range for binary search - 1 minute.
     * We don't search smaller ranges unless forced by TooBig results.
     */
    Duration BINARY_SEARCH_MIN_RANGE = Duration.ofMinutes(1);

    /**
     * Check if nowBoundary has advanced and return a potentially new state.
     * Called BEFORE evaluateLoad to allow state transition when NOW ticks forward.
     * <p>
     * Only meaningful for AtNowBoundary state - other states return themselves unchanged.
     *
     * @param nowBoundary the current "now" boundary
     * @param lineNo      the line number requesting the load
     * @return this state, or a new state if NOW has advanced
     */
    default DirectionLater checkBoundary(Instant nowBoundary, int lineNo) {
        return this; // Default: no change for most states
    }

    /**
     * Get target load lines.
     */
    @Override
    default double getTargetLoadLines() {
        return switch (this) {
            case Normal n -> n.targetLoadLines();
            default -> MAX_TARGET_LOAD_LINES;
        };
    }

    /**
     * Get consecutive empty loads for fallback duration escalation.
     */
    @Override
    default int getConsecutiveEmptyLoads() {
        return switch (this) {
            case Normal n -> n.consecutiveEmptyLoads();
            default -> 0;
        };
    }

    /**
     * Get the current max boundary for this state.
     * Returns null if no boundary has been set (e.g., Init state).
     */
    Instant getMaxBoundary();

    @Override
    boolean shouldStore();

    /**
     * Evaluate what load decision to make based on current state.
     * Called when we need to load data after the segment (scrolling down/later).
     *
     * @param segment       the current log segment
     * @param maxBoundary   the user-defined maximum time boundary
     * @param nowBoundary   the current "now" time (can't search beyond this)
     * @param lineNo        the line number that triggered the load request
     * @param reason        human-readable reason for the load
     * @return LoadDecision describing what to load
     */
    LoadDecision evaluateLoad(LogSegment segment, Instant maxBoundary, Instant nowBoundary, int lineNo, String reason);

    /**
     * Transition for empty result (0 logs returned).
     *
     * @param lineNo               the line number that triggered the load request
     * @param decision             the load decision that resulted in empty
     * @param maxBoundary          the current max boundary
     * @param nowBoundary          the current "now" boundary (can't expand beyond midnight after this)
     * @return the next state
     */
    DirectionLater recordEmptyResult(int lineNo, LoadDecision decision, Instant maxBoundary, Instant nowBoundary);

    /**
     * Transition for normal result (logs found, not truncated).
     *
     * @param lineNo         the line number that triggered the load request
     * @param loadedLogLines the loaded log data
     * @param decision       the load decision
     * @param maxBoundary    the current max boundary
     * @param nowBoundary    the current "now" boundary
     * @return the next state
     */
    DirectionLater recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
            Instant maxBoundary, Instant nowBoundary);

    /**
     * Transition for too-big result (truncated by Splunk).
     *
     * @param e           the exception with truncation details
     * @param decision    the load decision
     * @param maxBoundary the current effective max boundary
     * @return the next state
     */
    DirectionLater recordTooBigResult(SearchResultToBigException e, LoadDecision decision, Instant maxBoundary);

    boolean isLoading();

    // --- States ---

    /**
     * Initial state - no load attempted yet.
     */
    record Init(Instant maxBoundary) implements DirectionLater {

        public Init() {
            this(null);
        }

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public Instant getMaxBoundary() {
            return maxBoundary;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant maxBoundary, Instant nowBoundary, int lineNo,
                String reason) {
            Instant start = segment.getEndTime();
            Instant end = clampToMax(start.plus(INITIAL_SEARCH_DURATION), maxBoundary, nowBoundary);
            log.debug("DOWN Init: initial range={},", INITIAL_SEARCH_DURATION);
            return loadLater(this, start, end, reason + " " + this, nowBoundary);
        }

        @Override
        public DirectionLater recordEmptyResult(int lineNo, LoadDecision decision, Instant maxBoundary,
                Instant nowBoundary) {
            // Calculate the resume state for binary search
            Instant queryEnd = decision.end();
            Duration remainingStep = Duration.between(queryEnd, effectiveMax(maxBoundary, nowBoundary));
            var resumeState = new BinarySearchLater(remainingStep, 0, maxBoundary);

            // Check if at boundary (NOW or MAX)
            DirectionLater newState = evaluateBoundaryState(queryEnd, maxBoundary, nowBoundary, 0, resumeState);

            if (newState != null) {
                log.debug("DOWN Init: Empty -> {}", newState);
                return newState;
            }

            // Not at boundary - continue with binary search
            log.debug("DOWN Init: Empty -> {}", resumeState);
            return resumeState;
        }

        @Override
        public DirectionLater recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant maxBoundary, Instant nowBoundary) {
            // Create the Normal state we would transition to
            Instant dataEnd = loadedLogLines.end();
            var resumeState = new Normal(loadedLogLines.averageVelocity(), getTargetLoadLines(), 0,
                    autoExpandMaxBoundary(dataEnd, loadedLogLines.averageVelocity(), maxBoundary, nowBoundary));

            // Check if data ended at boundary (next load would fail with zero-length range)
            DirectionLater boundaryTransition = evaluateBoundaryState(dataEnd, maxBoundary, nowBoundary, 0,
                    resumeState);

            if (boundaryTransition != null) {
                log.debug("DOWN Init: Normal {} -> {}", loadedLogLines.logLines().size(), boundaryTransition);
                return boundaryTransition;
            }

            log.debug("DOWN Init: Normal {} -> {}", loadedLogLines.logLines().size(), resumeState);
            return resumeState;
        }

        @Override
        public DirectionLater recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant maxBoundary) {
            // TooBig: segment NOT updated, so next evaluateLoad retries from decision.start()
            var next = nextAfterTooBig(decision, 0, maxBoundary);
            log.debug("DOWN Init: TooBig {} -> {}", e.getResultCount(), next);
            return next;
        }

        @Override
        public String toString() {
            return "Init";
        }
    }

    /**
     * Normal loading - have velocity, use rate-based time estimation.
     */
    record Normal(double velocity, double targetLoadLines, int consecutiveEmptyLoads, Instant maxBoundary)
            implements DirectionLater {

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public Instant getMaxBoundary() {
            return maxBoundary;
        }

        public Normal withVelocity(double newVelocity, Instant maxBoundary) {
            return new Normal(newVelocity, targetLoadLines, 0, maxBoundary);
        }

        /**
         * Estimate duration to fetch targetLoadLines based on velocity.
         */
        public Duration estimateTimeRange() {
            if (velocity <= 0) {
                return Duration.ofMinutes(5);
            }
            double millisNeeded = 1000.0 * targetLoadLines / velocity;
            millisNeeded = Math.max(10.0, Math.min(millisNeeded, 31_536_000_000.0));
            return Duration.ofMillis(Math.round(millisNeeded));
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant maxBoundary, Instant nowBoundary, int lineNo,
                String reason) {
            Instant start = segment.getEndTime();
            Duration timeToLoad = estimateTimeRange();
            Instant end = clampToMax(start.plus(timeToLoad), maxBoundary, nowBoundary);
            log.debug("DOWN Normal: velocity={}, range={}", fmtV(velocity), timeToLoad);
            return loadLater(this, start, end, reason + " " + this, nowBoundary);
        }

        @Override
        public DirectionLater recordEmptyResult(int lineNo, LoadDecision decision, Instant maxBoundary,
                Instant nowBoundary) {
            // Calculate the resume state for binary search
            Instant queryEnd = decision.end();
            Duration remainingStep = Duration.between(queryEnd, effectiveMax(maxBoundary, nowBoundary));
            var resumeState = new BinarySearchLater(remainingStep, 0, maxBoundary);

            // Check if at boundary (NOW or MAX)
            DirectionLater boundaryTransition = evaluateBoundaryState(queryEnd, maxBoundary, nowBoundary, 0,
                    resumeState);

            if (boundaryTransition != null) {
                log.debug("DOWN Normal: Empty -> {}", boundaryTransition);
                return boundaryTransition;
            }

            // Not at boundary - continue with binary search
            log.debug("DOWN Normal: Empty -> {}", resumeState);
            return resumeState;
        }

        @Override
        public DirectionLater recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant maxBoundary, Instant nowBoundary) {
            // Create the Normal state we would transition to
            Instant dataEnd = loadedLogLines.end();
            var resumeState = withVelocity(loadedLogLines.averageVelocity(),
                    autoExpandMaxBoundary(dataEnd, loadedLogLines.averageVelocity(), maxBoundary, nowBoundary));

            // Check if data ended at boundary (next load would fail with zero-length range)
            DirectionLater boundaryTransition = evaluateBoundaryState(dataEnd, maxBoundary, nowBoundary, 0,
                    resumeState);

            if (boundaryTransition != null) {
                log.debug("DOWN Normal: Normal {} -> {}", loadedLogLines.logLines().size(), boundaryTransition);
                return boundaryTransition;
            }

            log.debug("DOWN Normal: Normal {} -> {}", loadedLogLines.logLines().size(), resumeState);
            return resumeState;
        }

        @Override
        public DirectionLater recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant effectiveMaxBoundary) {
            // TooBig: segment NOT updated, so next evaluateLoad retries from same position
            var next = nextAfterTooBig(decision, 0, effectiveMaxBoundary);
            log.debug("DOWN Normal: TooBig {} -> {}", e.getResultCount(), next);
            return next;
        }

        @Override
        public String toString() {
            return "Normal(v=" + fmtV(velocity) + ")";
        }
    }

    /**
     * Binary search state for DOWN direction (searching forward in time).
     * <p>
     * Implements additive halving binary search to find the maximal forward time duration
     * for loading events from a newest-first source (Splunk) while keeping event count
     * within a safe limit. The algorithm avoids re-searching discarded time ranges.
     * <p>
     * <b>Key insight:</b> The segment's endTime is updated BEFORE recordEmptyResult/recordNormalResult
     * is called, so segment.getEndTime() always reflects the confirmed search position.
     * For TooBig results, segment is NOT updated, so we naturally retry from the same position.
     * <p>
     * <b>Algorithm:</b>
     * <ul>
     *   <li>Use segment.getEndTime() as the confirmed position (updated by caller)</li>
     *   <li>Maintain {@code stepSize} = current probe size</li>
     *   <li>Each iteration: probe range [segment.end, segment.end + stepSize]</li>
     *   <li>On "normal" result (events > 0, ≤ safe_max): Exit to Normal state</li>
     *   <li>On "empty" result (0 events):
     *       <ul>
     *         <li>Segment was already advanced to probed end</li>
     *         <li>If at NOW boundary: transition to AtNowBoundary state</li>
     *         <li>If at MAX boundary: transition to BoundaryPrompt state</li>
     *         <li>Otherwise: halve stepSize for finer probing from new segment.end</li>
     *       </ul>
     *   </li>
     *   <li>On "too large" result (events > safe_max):
     *       <ul>
     *         <li>Segment NOT updated, so we retry from same position</li>
     *         <li>Halve stepSize and retry</li>
     *       </ul>
     *   </li>
     *   <li>When stepSize < threshold (1 minute): exhausted, transition to boundary state</li>
     *   <li>When the halved TooBig probe would drop below one second: probe to the end of the current
     *       second, which is loaded whole</li>
     * </ul>
     */
    record BinarySearchLater(Duration stepSize, // Current step size for probing extensions
            int consecutiveExpansions, // Number of consecutive boundary expansions
            Instant maxBoundary // User-defined max boundary
    ) implements DirectionLater {

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public Instant getMaxBoundary() {
            return maxBoundary;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant maxBoundary, Instant nowBoundary, int lineNo,
                String reason) {

            var ss = stepSize;
            if (ss.isZero()) {
                Instant queryEnd = segment.getEndTime();
                ss = Duration.between(queryEnd, effectiveMax(maxBoundary, nowBoundary));
            }

            Instant start = segment.getEndTime();
            Instant requestedEnd = start.plus(ss);
            Instant end = clampToMax(requestedEnd, maxBoundary, nowBoundary);

            Duration effectiveRange = Duration.between(start, end);
            if (!effectiveRange.equals(ss)) {
                log.debug("DOWN BinarySearch: probe from {} step {} -> {} (clamped)", start, ss, effectiveRange);
            } else {
                log.debug("DOWN BinarySearch: probe from {} step {}", start, ss);
            }

            return loadLaterLimited(this, start, end, reason + " " + this, nowBoundary, MAX_EVENTS_LIMIT);
        }

        @Override
        public DirectionLater recordEmptyResult(int lineNo, LoadDecision decision, Instant maxBoundary,
                Instant nowBoundary) {
            // Calculate the halved step for next iteration
            Instant queryEnd = decision.end();
            Duration nextStep = stepSize.dividedBy(2);
            if (nextStep.compareTo(BINARY_SEARCH_MIN_RANGE) < 0) {
                nextStep = BINARY_SEARCH_MIN_RANGE;
            }
            boolean atMinStep = stepSize.compareTo(BINARY_SEARCH_MIN_RANGE) <= 0;

            // Create the resume state for boundary transitions
            var resumeState = new BinarySearchLater(atMinStep ? BINARY_SEARCH_MIN_RANGE : nextStep,
                    consecutiveExpansions, maxBoundary);

            // Check if at boundary (NOW or MAX)
            DirectionLater boundaryTransition = evaluateBoundaryState(queryEnd, maxBoundary, nowBoundary,
                    consecutiveExpansions, resumeState);

            if (boundaryTransition != null) {
                log.debug("DOWN BinarySearch: Empty -> {}", boundaryTransition);
                return boundaryTransition;
            }

            // Not at boundary - continue with halved step (or min step if already at min)
            if (atMinStep) {
                log.debug("DOWN BinarySearch: Empty, continue min step -> {}", resumeState);
            } else {
                log.debug("DOWN BinarySearch: Empty -> {} (halve)", resumeState);
            }
            return resumeState;
        }

        @Override
        public DirectionLater recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant maxBoundary, Instant nowBoundary) {
            // Create the Normal state we would transition to
            Instant dataEnd = loadedLogLines.end();
            var resumeState = new Normal(loadedLogLines.rangedVelocity(), getTargetLoadLines(), 0,
                    autoExpandMaxBoundary(dataEnd, loadedLogLines.rangedVelocity(), maxBoundary, nowBoundary));

            // Check if data ended at boundary (next load would fail with zero-length range)
            DirectionLater boundaryTransition = evaluateBoundaryState(dataEnd, maxBoundary, nowBoundary,
                    consecutiveExpansions, resumeState);

            if (boundaryTransition != null) {
                log.debug("DOWN BinarySearch: Normal {} -> {}", loadedLogLines.logLines().size(), boundaryTransition);
                return boundaryTransition;
            }

            log.debug("DOWN BinarySearch: Normal {} -> {}", loadedLogLines.logLines().size(), resumeState);
            return resumeState;
        }

        @Override
        public DirectionLater recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant effectiveMaxBoundary) {
            // Halve the actual probe: stepSize may have been clamped to MAX or NOW.
            var next = nextAfterTooBig(decision, consecutiveExpansions, maxBoundary);
            log.debug("DOWN BinarySearch: TooBig {} -> {}", e.getResultCount(), next);
            return next;
        }

        @Override
        public String toString() {
            return "BinarySearch(step=" + stepSize + ")";
        }
    }

    /**
     * At boundary - search exhausted to boundary, waiting for expansion.
     * Includes a pre-calculated suggested boundary for the expansion prompt.
     */
    record BoundaryPrompt(Instant originalBoundary, int consecutiveExpansions, Instant suggestedBoundary)
            implements DirectionLater {

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public Instant getMaxBoundary() {
            // BoundaryPrompt doesn't change the boundary - return the original
            return originalBoundary;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant maxBoundary, Instant nowBoundary, int lineNo,
                String reason) {
            return LoadDecision.boundary(this, BoundaryType.AFTER, this.toString());
        }

        @Override
        public double getTargetLoadLines() {
            return MAX_TARGET_LOAD_LINES;
        }

        @Override
        public DirectionLater recordEmptyResult(int lineNo, LoadDecision decision, Instant maxBoundary,
                Instant nowBoundary) {
            log.debug("DOWN BoundaryPrompt: still at boundary, awaiting expansion");
            return this;
        }

        @Override
        public DirectionLater recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant maxBoundary, Instant nowBoundary) {
            throw new IllegalStateException("recordNormalResult: DOWN direction cannot be in AtBoundary state");
        }

        @Override
        public DirectionLater recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant effectiveMaxBoundary) {
            throw new IllegalStateException("recordTooBigResult: DOWN direction cannot be in AtBoundary state");
        }

        public DirectionLater expansionReply(boolean accepted, Instant newBoundary) {
            if (accepted) {
                log.debug("DOWN BoundaryPrompt: expansion accepted to {}", newBoundary);
                // Resume with new expanded range
                return new BinarySearchLater(Duration.between(originalBoundary, newBoundary), // Step is the new range
                        consecutiveExpansions, newBoundary); // New max boundary

            } else {
                log.debug("DOWN BoundaryPrompt: expansion rejected, stopping");
                return new DirectionLater.BoundaryStop(originalBoundary);
            }
        }

        @Override
        public String toString() {
            return "BoundaryPrompt(exp=" + consecutiveExpansions + ", suggest=" + suggestedBoundary + ")";
        }
    }

    /**
     * Waiting at the NOW boundary for time to advance.
     * <p>
     * This state is entered when binary search reaches the current time (nowBoundary).
     * Unlike BoundaryPrompt (which waits for user input), this state automatically
     * resumes when nowBoundary advances by at least 1 minute.
     * <p>
     * Exit criteria:
     * <ul>
     *   <li>If nowBoundary has advanced: return to resumeState to continue searching</li>
     *   <li>If lineNo is higher (user scrolled down): prompt for MAX boundary expansion</li>
     * </ul>
     */
    record AtNowBoundary(Instant lastKnownNow, // The nowBoundary when we entered this state
            Instant maxBoundary, // User-defined max boundary
            DirectionLater resumeState // State to resume when NOW advances
    ) implements DirectionLater {

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public Instant getMaxBoundary() {
            return maxBoundary;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public DirectionLater checkBoundary(Instant nowBoundary, int lineNo) {
            // Check if NOW has advanced by at least 1 minute
            if (nowBoundary != null && nowBoundary.isAfter(lastKnownNow)) {
                log.debug("DOWN AtNowBoundary: NOW advanced {} -> {}, resuming", lastKnownNow, nowBoundary);
                return resumeState;
            }
            // NOW hasn't advanced - stay in this state
            return this;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant maxBoundary, Instant nowBoundary, int lineNo,
                String reason) {
            // If we get here, checkNowAdvanced didn't transition us, so NOW hasn't advanced
            // Return noLoad to indicate we're waiting
            log.debug("DOWN AtNowBoundary: evaluateLoad called but NOW={} hasn't advanced from lastKnownNow={}",
                    nowBoundary, lastKnownNow);
            return LoadDecision.noLoad(BoundaryType.NOW, this.toString());
        }

        @Override
        public DirectionLater recordEmptyResult(int lineNo, LoadDecision decision, Instant maxBoundary,
                Instant nowBoundary) {
            // Shouldn't be called since evaluateLoad returns noLoad
            throw new IllegalStateException("recordEmptyResult: should not be called in AtNowBoundary state");
        }

        @Override
        public DirectionLater recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant maxBoundary, Instant nowBoundary) {
            throw new IllegalStateException("recordNormalResult: should not be called in AtNowBoundary state");
        }

        @Override
        public DirectionLater recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant effectiveMaxBoundary) {
            throw new IllegalStateException("recordTooBigResult: should not be called in AtNowBoundary state");
        }

        @Override
        public String toString() {
            return "AtNowBoundary(lastNow=" + lastKnownNow + ")";
        }
    }

    record BoundaryStop(Instant maxBoundary) implements DirectionLater {

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public Instant getMaxBoundary() {
            return maxBoundary;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant maxBoundary, Instant nowBoundary, int lineNo,
                String reason) {
            return LoadDecision.noLoad(BoundaryType.AFTER, this.toString());
        }

        @Override
        public DirectionLater recordEmptyResult(int lineNo, LoadDecision decision, Instant maxBoundary,
                Instant nowBoundary) {
            throw new IllegalStateException("recordEmptyResult: DOWN direction cannot be in Exhausted state");
        }

        @Override
        public DirectionLater recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant maxBoundary, Instant nowBoundary) {
            throw new IllegalStateException("recordNormalResult: DOWN direction cannot be in Exhausted state");
        }

        @Override
        public DirectionLater recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant effectiveMaxBoundary) {
            throw new IllegalStateException("recordTooBigResult: DOWN direction cannot be in Exhausted state");
        }

        public DirectionLater unfreezeBoundary(Instant nowBoundary) {
            log.debug("DOWN BoundaryStop: unfreezing " + this);
            var resumeState = new BinarySearchLater(Duration.between(maxBoundary, nowBoundary), 0, maxBoundary);

            return evaluateBoundaryState(maxBoundary, maxBoundary, nowBoundary, 0, resumeState);
        }

        @Override
        public String toString() {
            return "BoundaryStop";
        }
    }

    /**
     * Next state after a truncated DOWN load: halve the probe down to one second. Below that, probe to
     * the end of the current second; such a window cannot be narrowed and SplunkClient reads it whole.
     */
    private static DirectionLater nextAfterTooBig(LoadDecision decision, int consecutiveExpansions,
            Instant maxBoundary) {
        Duration halved = decision.getDuration().dividedBy(2);
        if (halved.compareTo(MIN_SPLITTABLE_WINDOW) < 0) {
            Instant start = decision.start();
            halved = Duration.between(start, start.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        }
        return new BinarySearchLater(halved, consecutiveExpansions, maxBoundary);
    }

    // --- Utility methods ---

    /**
     * The segment already ends at the effective max, so there is no later interval
     * to query. Returns the boundary state to enter, or null when a load is still possible.
     */
    static DirectionLater whenNoLaterWindow(DirectionLater current, Instant segmentEnd, Instant maxBoundary,
            Instant nowBoundary) {
        if (current instanceof BoundaryPrompt || current instanceof BoundaryStop || current instanceof AtNowBoundary) {
            return null;
        }
        Instant effective = effectiveMax(maxBoundary, nowBoundary);
        if (segmentEnd == null || effective == null || segmentEnd.isBefore(effective)) {
            return null;
        }
        return evaluateBoundaryState(segmentEnd, maxBoundary, nowBoundary, 0, current);
    }

    private static LoadDecision loadLater(DirectionLater state, Instant start, Instant end, String reason,
            Instant nowBoundary) {
        if (start == null || end == null || !start.isBefore(end)) {
            log.debug("DOWN {}: no later window [{} .. {})", state, start, end);
            return LoadDecision.boundary(state, boundaryReached(end, nowBoundary), reason + " (at boundary)");
        }
        return LoadDecision.load(state, 1, start, end, reason);
    }

    private static LoadDecision loadLaterLimited(DirectionLater state, Instant start, Instant end, String reason,
            Instant nowBoundary, int maxEvents) {
        if (start == null || end == null || !start.isBefore(end)) {
            log.debug("DOWN {}: no later window [{} .. {})", state, start, end);
            return LoadDecision.boundary(state, boundaryReached(end, nowBoundary), reason + " (at boundary)");
        }
        return LoadDecision.loadWithMaxEvents(state, 1, start, end, reason, maxEvents);
    }

    private static BoundaryType boundaryReached(Instant end, Instant nowBoundary) {
        return nowBoundary != null && end != null && !end.isBefore(nowBoundary) ? BoundaryType.NOW : BoundaryType.AFTER;
    }

    /**
     * Calculate the effective maximum boundary (earlier of maxBoundary and nowBoundary).
     */
    private static Instant effectiveMax(Instant maxBoundary, Instant nowBoundary) {
        if (maxBoundary == null) return nowBoundary;
        if (nowBoundary == null) return maxBoundary;
        return maxBoundary.isBefore(nowBoundary) ? maxBoundary : nowBoundary;
    }

    /**
     * Clamp a timestamp to not go after the effective maximum boundary.
     * Logs which boundary (max or now) caused the clamp.
     */
    private static Instant clampToMax(Instant time, Instant maxBoundary, Instant nowBoundary) {
        Instant effectiveMax = effectiveMax(maxBoundary, nowBoundary);

        if (effectiveMax == null) return time;

        if (time.isAfter(effectiveMax)) {
            if (nowBoundary != null && effectiveMax.equals(nowBoundary)) {
                log.debug("clampToMax: clamped to NOW boundary {}", nowBoundary);
            } else {
                log.debug("clampToMax: clamped to MAX boundary {}", maxBoundary);
            }
            return effectiveMax;
        }
        return time;
    }

    /**
     * Auto-expand the max boundary based on velocity, but only if:
     * 1. We have positive velocity (logs are flowing)
     * 2. The estimated new boundary is later than current
     * 3. The new boundary doesn't exceed nowBoundary's midnight
     *
     * Does NOT auto-expand when at boundary with no velocity - that requires user prompt.
     */
    private static Instant autoExpandMaxBoundary(Instant end, double velocity, Instant maxBoundary,
            Instant nowBoundary) {
        // Calculate the maximum allowed boundary (midnight after nowBoundary)
        Instant maxAllowed = nowBoundary != null
                ? nowBoundary.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
                : Instant.MAX;

        if (velocity > 0) {
            // 10.000 lines (*millis per line) / lines per second = estimated millis to get 10.000 lines
            // max of 1 day increments
            long estimatedMillis = (long) Math.min((10_000_000.0 / velocity), 86_400_000.0);

            var estimatedNewBoundary = end.plusMillis(estimatedMillis).truncatedTo(ChronoUnit.DAYS).plus(1,
                    ChronoUnit.DAYS);

            // Clamp to not exceed nowBoundary's midnight
            if (estimatedNewBoundary.isAfter(maxAllowed)) {
                estimatedNewBoundary = maxAllowed;
            }

            if (estimatedNewBoundary.isAfter(maxBoundary)) {
                log.debug("autoExpandMaxBoundary: expanding from {} to {} based on velocity {}", maxBoundary,
                        estimatedNewBoundary, fmtV(velocity));
                return estimatedNewBoundary;
            }
        }
        // No auto-expand when velocity=0 or at boundary - user should be prompted
        return maxBoundary;
    }

    /**
     * Check if we've reached a boundary and return the appropriate transition state.
     * <p>
     * This is the unified boundary handling logic for all states and result types:
     * <ul>
     *   <li>If at NOW boundary → return AtNowBoundary with the provided resumeState</li>
     *   <li>If at MAX boundary → return BoundaryPrompt to ask user for expansion</li>
     *   <li>If not at boundary → return null (caller should continue with normal logic)</li>
     * </ul>
     * <p>
     * Used by both recordEmptyResult (probedEnd = decision.end()) and
     * recordNormalResult (probedEnd = loadedLogLines.end()).
     *
     * @param probedEnd         the end time that was reached (decision.end or loadedLogLines.end)
     * @param maxBoundary       user-defined max boundary
     * @param nowBoundary       current "now" time
     * @param consecutiveExpansions number of consecutive expansions so far
     * @param resumeState       the state to resume to after boundary is cleared
     * @return the next state if at boundary, or null if not at boundary
     */
    private static DirectionLater evaluateBoundaryState(Instant probedEnd, Instant maxBoundary, Instant nowBoundary,
            int consecutiveExpansions, DirectionLater resumeState) {

        Instant effectiveMax = effectiveMax(maxBoundary, nowBoundary);

        // Not at boundary - return null to signal caller should continue normal logic
        if (probedEnd.isBefore(effectiveMax)) {
            return null;
        }

        // At effective boundary - determine if NOW or MAX
        boolean atNow = nowBoundary != null && effectiveMax.equals(nowBoundary);

        if (atNow) {
            // At NOW boundary - wait for time to advance
            return new AtNowBoundary(nowBoundary, maxBoundary, resumeState);
        }

        // At MAX boundary - prompt user for expansion
        Instant maxAllowed = nowBoundary != null
                ? nowBoundary.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
                : Instant.MAX;

        // If MAX is already at NOW's midnight, we can't expand further - treat as at NOW
        if (!maxBoundary.isBefore(maxAllowed)) {
            log.debug("DOWN: MAX at NOW's midnight, treating as NOW boundary");
            return new AtNowBoundary(nowBoundary, maxBoundary, resumeState);
        }

        // Calculate suggested expansion using static helper
        Instant suggestedMaxBoundary = BoundaryExpansionHelper.calculateExpansion(BoundaryType.AFTER, maxBoundary,
                consecutiveExpansions + 1, nowBoundary);

        // Clamp to not exceed NOW's midnight
        if (suggestedMaxBoundary.isAfter(maxAllowed)) {
            suggestedMaxBoundary = maxAllowed;
        }

        return new BoundaryPrompt(maxBoundary, consecutiveExpansions + 1, suggestedMaxBoundary);
    }
}
