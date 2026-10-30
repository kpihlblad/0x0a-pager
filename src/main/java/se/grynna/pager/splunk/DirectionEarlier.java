package se.grynna.pager.splunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.grynna.pager.exceptions.SearchResultToBigException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static se.grynna.pager.splunk.DirectionStateBase.fmtV;
import static se.grynna.pager.splunk.LoadingStrategy.INITIAL_SEARCH_DURATION;

/**
 * State machine for loading earlier (UP direction, scrolling backwards in time).
 * <p>
 * Splunk reads from earliest to latest. When searching backward:
 * - Logs nearest the segment edge are found first
 * - If nothing is found, we need to search the whole boundary range -> SeekEarlier
 * - After a SeekEarlier TooBig, we need to verify no logs were missed -> GapVerification
 * - If the GapVerification works out, we then resume with the indicated velocity from the previous read.
 * <p>
 * ## State Transitions (UP direction)
 * <p>
 * Init → Normal (first load with results and velocity)
 * Init → SeekEarlier (first load returns 0 results)
 * <p>
 * Normal → Normal (successful load, update velocity)
 * Normal → SeekEarlier (0 results, start seeking sparse region)
 * Normal → GapVerification (TooBigException only)
 * Normal → AtBoundary (reached Epoch hard limit)
 * <p>
 * SeekEarlier (searches whole range to boundary):
 * → Normal when results found (calculate velocity, segment expanded)
 * → GapVerification when TooBig (always, since Splunk order is stochastic)
 * → AtBoundary when 0 results (exhausted, prompt user)
 * <p>
 * GapVerification (only entered via TooBig):
 * → Normal when 0 or more (non-trunkated) results, gap confirmed, use probableVelocity.
 * → GapVerification when TooBig (new gap to latest log to verify)
 * <p>
 * AtBoundary → SeekEarlier (boundary expanded, restart search)
 * AtBoundary → Exhausted (user indicates no more data)
 */
public sealed interface DirectionEarlier extends DirectionStateBase
        permits DirectionEarlier.Init, DirectionEarlier.Normal, DirectionEarlier.SeekEarlier,
        DirectionEarlier.GapVerification, DirectionEarlier.BoundaryPrompt, DirectionEarlier.BoundaryStop {

    Logger log = LoggerFactory.getLogger(DirectionEarlier.class);

    /**
     * True when [beforeBoundary, segmentStart) is a real interval.
     * Equal instants are not a load window — the segment already starts on the boundary.
     */
    static boolean hasEarlierWindow(Instant segmentStart, Instant beforeBoundary) {
        return segmentStart != null && beforeBoundary != null && beforeBoundary.isBefore(segmentStart);
    }

    /** Ask to widen a boundary the segment has already reached. */
    static BoundaryPrompt promptAtMinBoundary(Instant beforeBoundary) {
        return BoundaryPrompt.promptForBoundary(beforeBoundary, 1, 1);
    }

    // Backward reads have good throughput but high round-trip latency on Splunk.
    int MAX_TARGET_LOAD_LINES = 5000;

    /**
     * Maximum events to request per query during sparse seeking.
     */
    int MAX_EVENTS_LIMIT = 3000;

    /**
     * Get target load lines.
     */
    @Override
    default double getTargetLoadLines() {
        return switch (this) {
            case Normal n -> n.targetLoadLines();
            case GapVerification g -> g.targetLoadLines();
            default -> MAX_TARGET_LOAD_LINES;
        };
    }

    /**
     * Get consecutive empty loads for fallback duration escalation.
     */
    @Override
    default int getConsecutiveEmptyLoads() {
        return switch (this) {
            case SeekEarlier se -> se.consecutiveEmptyLoads();
            default -> 0;
        };
    }

    /**
     * Get the current min boundary for this state.
     * Returns null if no boundary has been set (e.g., Init state).
     */
    Instant getMinBoundary();

    @Override
    boolean shouldStore();

    /**
     * Evaluate what load decision to make based on current state.
     * Called when we need to load data before the segment (scrolling up/earlier).
     *
     * @param segment        the current log segment
     * @param beforeBoundary the minimum time boundary
     * @param lineNo         the line number that triggered the load
     * @param reason         human-readable reason for the load
     * @return LoadDecision describing what to load
     */
    LoadDecision evaluateLoad(LogSegment segment, Instant beforeBoundary, int lineNo, String reason);

    /**
     * Transition for empty result (0 logs returned).
     *
     * @param lineNo          the line number that triggered the load request
     * @param decision        the load decision that resulted in empty
     * @param beforeBoundary  the current BEFORE boundary
     * @param expansionHelper helper for calculating suggested boundary expansions (may be null if not needed)
     * @return the next state
     */
    DirectionEarlier recordEmptyResult(int lineNo, LoadDecision decision, Instant beforeBoundary,
            BoundaryExpansionHelper expansionHelper);

    /**
     * Transition for normal result (logs found, not truncated).
     *
     * @param lineNo         the line number that triggered the load request
     * @param loadedLogLines the loaded log data
     * @param decision       the load decision
     * @param beforeBoundary the current BEFORE boundary
     * @return the next state
     */
    DirectionEarlier recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
            Instant beforeBoundary);

    /**
     * Transition for too-big result (truncated by Splunk).
     *
     * @param e              the exception with truncation details
     * @param decision       the load decision
     * @param beforeBoundary the current BEFORE boundary
     * @return the next state
     */
    DirectionEarlier recordTooBigResult(SearchResultToBigException e, LoadDecision decision, Instant beforeBoundary);

    boolean isLoading();

    // --- States ---

    /**
     * Initial state - no load attempted yet.
     */
    record Init(Instant minBoundary) implements DirectionEarlier {

        public Init() {
            this(null);
        }

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public Instant getMinBoundary() {
            return minBoundary;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant beforeBoundary, int lineNo, String reason) {
            Instant end = segment.getStartTime();
            Instant start = clampToMin(end.minus(INITIAL_SEARCH_DURATION), beforeBoundary);
            log.debug("UP Init: initial range={}", INITIAL_SEARCH_DURATION);
            return loadEarlier(this, start, end, reason + " (init)");
        }

        @Override
        public DirectionEarlier recordEmptyResult(int lineNo, LoadDecision decision, Instant beforeBoundary,
                BoundaryExpansionHelper expansionHelper) {

            DirectionEarlier next;
            if (!decision.start().isAfter(beforeBoundary)) {
                next = BoundaryPrompt.promptForBoundary(beforeBoundary, 1, 1);
            } else {
                next = new SeekEarlier(beforeBoundary, 0, 0, beforeBoundary);
            }
            log.debug("UP Init: Empty -> {}", next);
            return next;
        }

        @Override
        public DirectionEarlier recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant beforeBoundary) {
            var next = new Normal(loadedLogLines.averageVelocity(), getTargetLoadLines(),
                    autoExpandMinBoundary(loadedLogLines.start(), loadedLogLines.averageVelocity(), beforeBoundary));
            log.debug("UP Init: Normal {} -> {}", loadedLogLines.logLines().size(), next);
            return next;
        }

        @Override
        public DirectionEarlier recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant beforeBoundary) {
            var velocity = calculateVelocityFromTooBig(e, decision);
            var next = new Normal(velocity, getTargetLoadLines(), beforeBoundary);
            log.debug("UP Init: TooBig {} -> {}", e.getResultCount(), next);
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
    record Normal(double velocity, double targetLoadLines, Instant minBoundary) implements DirectionEarlier {

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public Instant getMinBoundary() {
            return minBoundary;
        }

        public Normal withVelocity(double newVelocity, Instant minBoundary) {
            return new Normal(newVelocity, targetLoadLines, minBoundary);
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
        public LoadDecision evaluateLoad(LogSegment segment, Instant beforeBoundary, int lineNo, String reason) {
            Instant end = segment.getStartTime();
            Duration timeToLoad = estimateTimeRange();
            Instant start = clampToMin(windowStart(end, timeToLoad), beforeBoundary);
            log.debug("UP Normal: velocity={}, range={}", fmtV(velocity), timeToLoad);
            return loadEarlier(this, start, end, reason + " (normal)");
        }

        @Override
        public DirectionEarlier recordEmptyResult(int lineNo, LoadDecision decision, Instant beforeBoundary,
                BoundaryExpansionHelper expansionHelper) {
            DirectionEarlier next;
            if (!decision.start().isAfter(beforeBoundary)) {
                next = BoundaryPrompt.promptForBoundary(beforeBoundary, 1, 1);
            } else {
                next = new SeekEarlier(beforeBoundary, 0, 0, beforeBoundary);
            }
            log.debug("UP Init: Normal -> {}", next);
            return next;
        }

        @Override
        public DirectionEarlier recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant beforeBoundary) {
            var next = withVelocity(loadedLogLines.averageVelocity(),
                    autoExpandMinBoundary(loadedLogLines.start(), loadedLogLines.averageVelocity(), beforeBoundary));
            log.debug("UP Normal: Normal {} -> {}", loadedLogLines.logLines().size(), next);
            return next;
        }

        @Override
        public DirectionEarlier recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant beforeBoundary) {
            var velocityFromTooBig = calculateVelocityFromTooBig(e, decision);
            var next = new Normal(velocityFromTooBig, getTargetLoadLines(), beforeBoundary);
            log.debug("UP Normal: TooBig {} -> {}", e.getResultCount(), next);
            return next;
        }

        @Override
        public String toString() {
            return "Normal(v=" + fmtV(velocity) + ")";
        }
    }

    /**
     * Seek earlier state - searching backwards for logs in a sparse region.
     * <p>
     * Since Splunk reads earliest→latest, logs nearest segment edge are found first.
     * We search the whole boundary range with maxEvents limit.
     * On truncation, we go to GapVerification since order is stochastic.
     */
    record SeekEarlier(Instant searchBoundary, int consecutiveEmptyLoads, int consecutiveBoundaryExpansions,
            Instant minBoundary) implements DirectionEarlier {

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public Instant getMinBoundary() {
            return minBoundary;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant beforeBoundary, int lineNo, String reason) {
            Instant end = segment.getStartTime();
            Instant start = clampToMin(searchBoundary, beforeBoundary);
            return loadEarlierLimited(this, start, end, reason + " (seek earlier)", MAX_EVENTS_LIMIT);
        }

        @Override
        public DirectionEarlier recordEmptyResult(int lineNo, LoadDecision decision, Instant beforeBoundary,
                BoundaryExpansionHelper expansionHelper) {
            DirectionEarlier next;
            if (!decision.start().isAfter(beforeBoundary)) {
                next = BoundaryPrompt.promptForBoundary(beforeBoundary, consecutiveEmptyLoads + 1,
                        consecutiveBoundaryExpansions + 1);
            } else {
                next = new SeekEarlier(beforeBoundary, consecutiveEmptyLoads + 1, consecutiveBoundaryExpansions + 1,
                        beforeBoundary);
            }
            log.debug("UP SeekEarlier: Normal -> {}", next);
            return next;
        }

        @Override
        public DirectionEarlier recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant beforeBoundary) {
            var next = new Normal(loadedLogLines.rangedVelocity(), getTargetLoadLines(), DirectionEarlier
                    .autoExpandMinBoundary(loadedLogLines.start(), loadedLogLines.rangedVelocity(), beforeBoundary));

            log.debug("UP SeekEarlier: Normal {} -> {}", loadedLogLines.logLines().size(), next);
            return next;
        }

        @Override
        public DirectionEarlier recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant beforeBoundary) {
            double velocityFromTooBig = calculateVelocityFromTooBig(e, decision);
            var next = new GapVerification(e.getEnd(), velocityFromTooBig, getTargetLoadLines(), 1, beforeBoundary);
            log.debug("UP SeekEarlier: TooBig {} -> {}", e.getResultCount(), next);
            return next;
        }

        @Override
        public String toString() {
            return "SeekEarlier(empty=" + consecutiveEmptyLoads + ", exp=" + consecutiveBoundaryExpansions + ")";
        }
    }

    /**
     * Gap verification - after TooBigException, verify no logs were missed.
     * The probe runs from the second after the probable latest log up to the segment start;
     * the latest log's own second is loaded by the following Normal load.
     */
    record GapVerification(Instant probableLatestLog, double probableVelocity, double targetLoadLines, int retryCount,
            Instant minBoundary) implements DirectionEarlier {

        @Override
        public boolean isLoading() {
            return true;
        }

        @Override
        public Instant getMinBoundary() {
            return minBoundary;
        }

        public Duration estimateTimeRange() {
            if (probableVelocity <= 0) {
                return Duration.ofMinutes(1);
            }
            double millisNeeded = 1000.0 * targetLoadLines / probableVelocity;
            millisNeeded = Math.max(10.0, Math.min(millisNeeded, 31_536_000_000.0));
            return Duration.ofMillis(Math.round(millisNeeded));
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant beforeBoundary, int lineNo, String reason) {
            Instant end = segment.getStartTime();
            // The latest second of a truncated result is where the density is; the next Normal
            // load ends at the new segment start and covers that second, so the probe skips it.
            Instant gapTarget = probableLatestLog == null
                    ? null
                    : probableLatestLog.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            // A TooBig without readable timestamps leaves no gap to verify.
            Duration gapDuration = gapTarget == null ? null : Duration.between(gapTarget, end);

            if (gapDuration == null || !gapDuration.isPositive()) {
                // The latest log's second reaches the segment start: nothing to verify.
                if (gapDuration != null) {
                    log.debug("UP GapVerification: no gap after {} (segmentStart={})", probableLatestLog, end);
                }
                Duration timeToLoad = estimateTimeRange();
                Instant start = clampToMin(windowStart(end, timeToLoad), beforeBoundary);
                return loadEarlier(this, start, end, reason + " (no gap to verify, velocity-based)");
            }

            Instant start = clampToMin(gapTarget, beforeBoundary);
            log.debug("UP GapVerification: verifying gap={}", gapDuration);
            return loadEarlier(this, start, end, reason + " (gap verification)");
        }

        @Override
        public DirectionEarlier recordEmptyResult(int lineNo, LoadDecision decision, Instant beforeBoundary,
                BoundaryExpansionHelper expansionHelper) {
            var next = new Normal(probableVelocity, targetLoadLines, beforeBoundary);
            log.debug("UP GapVerification: Empty -> {}", next);
            return next;
        }

        @Override
        public DirectionEarlier recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant beforeBoundary) {
            var next = new Normal(probableVelocity, getTargetLoadLines(), beforeBoundary);
            log.debug("UP GapVerification: Normal {} -> {}", loadedLogLines.logLines().size(), next);
            return next;
        }

        @Override
        public DirectionEarlier recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant beforeBoundary) {
            double velocityFromTooBig = calculateVelocityFromTooBig(e, decision);
            var next = new GapVerification(e.getEnd(), velocityFromTooBig, getTargetLoadLines(), retryCount + 1,
                    beforeBoundary);
            log.debug("UP GapVerification: TooBig {} -> {}", e.getResultCount(), next);
            return next;
        }

        @Override
        public String toString() {
            return "GapVerification(v=" + fmtV(probableVelocity) + ", retry=" + retryCount + ")";
        }
    }

    record BoundaryStop(Instant minBoundary) implements DirectionEarlier {

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public Instant getMinBoundary() {
            return minBoundary;
        }

        @Override
        public int getConsecutiveEmptyLoads() {
            return 0;
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant beforeBoundary, int lineNo, String reason) {
            return LoadDecision.noLoad(BoundaryType.BEFORE, "search exhausted " + this.toString());
        }

        @Override
        public double getTargetLoadLines() {
            return 0;
        }

        @Override
        public DirectionEarlier recordEmptyResult(int lineNo, LoadDecision decision, Instant beforeBoundary,
                BoundaryExpansionHelper expansionHelper) {
            throw new IllegalStateException("recordEmptyResult: UP direction cannot be in Exhausted state");
        }

        @Override
        public DirectionEarlier recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant beforeBoundary) {
            throw new IllegalStateException("recordNormalResult: UP direction cannot be in Exhausted state");
        }

        @Override
        public DirectionEarlier recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant beforeBoundary) {
            throw new IllegalStateException("recordTooBigResult: UP direction cannot be in Exhausted state");
        }

        public DirectionEarlier unfreezeBoundary() {
            var result = BoundaryPrompt.promptForBoundary(minBoundary, 1, 1);
            log.debug("UP BoundaryStop: unfreezing, resuming {}", result);
            return result;
        }

        @Override
        public String toString() {
            return "BoundaryStop";
        }
    }

    /**
     * At boundary - search exhausted to boundary, waiting for expansion.
     * Includes a pre-calculated suggested boundary for the expansion prompt.
     */
    record BoundaryPrompt(Instant originalBoundary, int consecutiveExpansions, DirectionEarlier resumeState,
            Instant suggestedBoundary) implements DirectionEarlier {

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public Instant getMinBoundary() {
            // BoundaryPrompt doesn't change the boundary - return the original
            return originalBoundary;
        }

        @Override
        public int getConsecutiveEmptyLoads() {
            return resumeState.getConsecutiveEmptyLoads();
        }

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public LoadDecision evaluateLoad(LogSegment segment, Instant beforeBoundary, int lineNo, String reason) {
            return LoadDecision.boundary(this, BoundaryType.BEFORE, this.toString());
        }

        @Override
        public double getTargetLoadLines() {
            return resumeState.getTargetLoadLines();
        }

        @Override
        public DirectionEarlier recordEmptyResult(int lineNo, LoadDecision decision, Instant beforeBoundary,
                BoundaryExpansionHelper expansionHelper) {
            log.debug("UP BoundaryPrompt: still at boundary, awaiting expansion");
            return this;
        }

        @Override
        public DirectionEarlier recordNormalResult(int lineNo, LoadedLogLines loadedLogLines, LoadDecision decision,
                Instant beforeBoundary) {
            throw new IllegalStateException("recordNormalResult: UP direction cannot be in AtBoundary state");
        }

        @Override
        public DirectionEarlier recordTooBigResult(SearchResultToBigException e, LoadDecision decision,
                Instant beforeBoundary) {
            throw new IllegalStateException("recordTooBigResult: UP direction cannot be in AtBoundary state");
        }

        public DirectionEarlier expansionReply(boolean accepted, Instant newBoundary) {
            if (accepted) {
                log.debug("UP BoundaryPrompt: expansion accepted to {}", newBoundary);
                // Update the resume state with the new boundary
                return switch (resumeState) {
                    case SeekEarlier se -> new SeekEarlier(newBoundary, se.consecutiveEmptyLoads(),
                            se.consecutiveBoundaryExpansions() + 1, newBoundary);
                    case Normal n -> new Normal(n.velocity(), n.targetLoadLines(), newBoundary);
                    default -> resumeState; // shouldn't happen
                };
            } else {
                log.debug("UP BoundaryPrompt: expansion rejected, stopping");
                return new BoundaryStop(originalBoundary);
            }
        }

        private static BoundaryPrompt promptForBoundary(Instant beforeBoundary, int consecutiveEmptyLoads,
                int consecutiveExpansions) {
            // check if the decision reached the boundary
            // Calculate suggested expansion for the prompt
            Instant suggestedMinBoundary = BoundaryExpansionHelper.calculateExpansion(BoundaryType.BEFORE,
                    beforeBoundary, consecutiveExpansions, null);

            var resumeState = new SeekEarlier(suggestedMinBoundary, consecutiveEmptyLoads, consecutiveExpansions,
                    suggestedMinBoundary);

            return new BoundaryPrompt(beforeBoundary, consecutiveExpansions, resumeState, suggestedMinBoundary);
        }

        @Override
        public String toString() {
            return "BoundaryPrompt(exp=" + consecutiveExpansions + ", suggest=" + suggestedBoundary + ")";
        }
    }

    // --- Utility methods ---

    double TOO_BIG_FUDGE_FACTOR = 2.0;

    /**
     * A clamped earlier-window can collapse to a point when the segment already
     * starts on the min boundary. That is "ask to extend", not a load.
     */
    private static LoadDecision loadEarlier(DirectionEarlier state, Instant start, Instant end, String reason) {
        if (start == null || end == null || !start.isBefore(end)) {
            log.debug("UP {}: no earlier window [{} .. {}), at boundary", state, start, end);
            return LoadDecision.boundary(state, BoundaryType.BEFORE, reason + " (at boundary)");
        }
        return LoadDecision.load(state, -1, start, end, reason);
    }

    private static LoadDecision loadEarlierLimited(DirectionEarlier state, Instant start, Instant end, String reason,
            int maxEvents) {
        if (start == null || end == null || !start.isBefore(end)) {
            log.debug("UP {}: no earlier window [{} .. {}), at boundary", state, start, end);
            return LoadDecision.boundary(state, BoundaryType.BEFORE, reason + " (at boundary)");
        }
        return LoadDecision.loadWithMaxEvents(state, -1, start, end, reason, maxEvents);
    }

    /**
     * Start of an earlier window of {@code duration} ending at {@code end}. Below one second the
     * window is the whole second before {@code end}: Splunk cannot narrow it further, and
     * SplunkClient reads such a window completely.
     */
    private static Instant windowStart(Instant end, Duration duration) {
        if (duration.compareTo(LoadingStrategy.MIN_SPLITTABLE_WINDOW) >= 0) {
            return end.minus(duration);
        }
        Instant second = end.truncatedTo(ChronoUnit.SECONDS);
        return second.equals(end) ? end.minusSeconds(1) : second;
    }

    /**
     * Clamp a timestamp to not go before a minimum boundary.
     */
    private static Instant clampToMin(Instant time, Instant minBoundary) {
        if (minBoundary == null) return time;
        if (time.isBefore(minBoundary)) {
            return minBoundary;
        }
        return time;
    }

    /**
     * Calculate velocity from a TooBigException.
     * <p>
     * Uses the span of the returned events when it is known and non-zero. Otherwise
     * (no readable timestamps, or all events share one second-granular {@code _time})
     * falls back to the queried window, which held at least the returned events.
     */
    private static double calculateVelocityFromTooBig(SearchResultToBigException e, LoadDecision decision) {
        long spanMillis = positiveMillis(e.getStart(), e.getEnd());
        if (spanMillis <= 0 && decision != null) {
            log.debug("recordTooBigResult: no usable log time span, estimating velocity from queried window");
            spanMillis = positiveMillis(decision.start(), decision.end());
        }
        if (spanMillis <= 0) {
            log.warn("recordTooBigResult: no usable time span from TooBigException, cannot calculate velocity");
            return 0;
        }
        return e.getResultCount() / (spanMillis / 1000.0) * TOO_BIG_FUDGE_FACTOR;
    }

    /** Milliseconds from start to end, or 0 when either is unknown or the span is not positive. */
    private static long positiveMillis(Instant start, Instant end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    /**
     * Auto-expand the min boundary based on velocity, but only if:
     * 1. We have positive velocity (logs are flowing)
     * 2. The estimated new boundary is earlier than current
     * <p>
     * Does NOT auto-expand when at boundary with no velocity - that requires user prompt.
     */
    private static Instant autoExpandMinBoundary(Instant start, double velocity, Instant minBoundary) {
        if (velocity > 0) {
            // 10.000 lines (*millis per line) / lines per second = estimated millis to get 10.000 lines
            // max 1 day
            long estimatedMillis = (long) Math.min((10_000_000.0 / velocity), 86_400_000.0);

            var estimatedNewBoundary = clampToMin(start.minusMillis(estimatedMillis).truncatedTo(ChronoUnit.DAYS),
                    SearchBoundaries.MIN_MINIMUM_BOUNDARY);
            if (estimatedNewBoundary.isBefore(minBoundary)) {
                log.debug("autoExpandMinBoundary: expanding from {} to {} based on velocity {}", minBoundary,
                        estimatedNewBoundary, fmtV(velocity));
                return estimatedNewBoundary;
            }
        }
        // No auto-expand when velocity=0 or at boundary - user should be prompted
        return minBoundary;
    }
}
