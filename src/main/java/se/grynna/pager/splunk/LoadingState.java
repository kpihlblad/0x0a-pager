package se.grynna.pager.splunk;

import java.time.Duration;
import java.time.Instant;

/**
 * Represents the loading state machine for LoadingStrategy.
 * Uses sealed interface + records for type-safe, immutable state management.
 * All load-related state is encapsulated here - no separate tracking variables needed.
 *
 * ## State Machine (Mermaid)
 *
 * ```mermaid
 * stateDiagram-v2
 *     [*] --> Uninitialized
 *
 *     Uninitialized --> Normal : first load >0 results
 *     Uninitialized --> BinarySearchBefore : first load 0 results, scrolling up
 *     Uninitialized --> BinarySearchAfter : first load 0 results, scrolling down
 *
 *     Normal --> Normal : load >0 results
 *     Normal --> BinarySearchBefore : scroll up, 0 results
 *     Normal --> BinarySearchAfter : scroll down, 0 results
 *     Normal --> GapVerification : too big exception
 *
 *     BinarySearchBefore --> Normal : found logs (>0 results)
 *     BinarySearchBefore --> BinarySearchBefore : still empty, search not exhausted
 *     BinarySearchBefore --> [boundary] : search exhausted (< 1 second range)
 *     BinarySearchBefore --> GapVerification : too big exception
 *
 *     BinarySearchAfter --> Normal : found logs (>0 results)
 *     BinarySearchAfter --> BinarySearchAfter : still empty, search not exhausted
 *     BinarySearchAfter --> [boundary] : search exhausted (< 1 second range)
 *     BinarySearchAfter --> GapVerification : too big exception
 *
 *     GapVerification --> Normal : gap verified (any result, or gap already covered)
 *     GapVerification --> GapVerification : gap too big (retry with smaller range)
 * ```
 *
 * ## Key behaviors
 * - BinarySearchBefore/After are independent - switching scroll direction starts fresh
 * - Binary search transitions to boundary when search range becomes too small (< 1 second)
 * - GapVerification ensures no logs are missed after a too-big result
 * - GapVerification auto-transitions to Normal if gap is already covered by segment
 * - Normal state uses velocity-based time estimation
 * - consecutiveEmptyLoads tracks escalation for fallback duration calculation
 */
public sealed interface LoadingState {

    // Constants shared across states
    double MAX_TARGET_LOAD_LINES = 2500.0;
    int SUCCESS_STREAK_THRESHOLD = 3;
    double RECOVERY_GROWTH_FACTOR = 1.1;

    /**
     * Get velocity from state if available.
     */
    default double getVelocity() {
        return switch (this) {
            case Normal n -> n.velocity();
            case GapVerification g -> g.velocityFromTooBig();
            default -> -1;
        };
    }

    /**
     * Get target load lines from state.
     */
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
    default int getConsecutiveEmptyLoads() {
        return switch (this) {
            case Normal n -> n.consecutiveEmptyLoads();
            case BinarySearchBefore bs -> bs.consecutiveEmptyLoads();
            case BinarySearchAfter bs -> bs.consecutiveEmptyLoads();
            default -> 0;
        };
    }

    /**
     * Initial state - no segment loaded yet.
     */
    record Uninitialized() implements LoadingState {}

    /**
     * First load in progress - centered on target timestamp.
     * @param targetTimestamp the timestamp user jumped to
     */
    record Initializing(Instant targetTimestamp) implements LoadingState {}

    /**
     * Normal loading state - have velocity data, use rate-based estimation.
     *
     * @param velocity logs per second from recent successful loads
     * @param targetLoadLines current target for how many lines to load
     * @param consecutiveSuccesses count for adaptive recovery of targetLoadLines
     * @param consecutiveEmptyLoads count for progressive fallback duration
     */
    record Normal(double velocity, double targetLoadLines, int consecutiveSuccesses, int consecutiveEmptyLoads)
            implements LoadingState {

        public Normal withVelocity(double newVelocity) {
            return new Normal(newVelocity, targetLoadLines, consecutiveSuccesses, 0);
        }

        public Normal incrementSuccess() {
            return new Normal(velocity, targetLoadLines, consecutiveSuccesses + 1, 0);
        }

        public Normal incrementEmptyLoads() {
            return new Normal(velocity, targetLoadLines, 0, consecutiveEmptyLoads + 1);
        }

        /**
         * Estimate duration to fetch targetLoadLines based on velocity.
         */
        public Duration estimateTimeRange() {
            if (velocity <= 0) {
                return Duration.ofMinutes(5); // fallback
            }
            double millisNeeded = 1000.0 * targetLoadLines / velocity;
            millisNeeded = Math.max(10.0, Math.min(millisNeeded, 31_536_000_000.0)); // 10ms to 1 year
            return Duration.ofMillis(Math.round(millisNeeded));
        }
    }

    /**
     * Binary search state when scrolling UP (earlier/before).
     * Searches from segment start toward minBoundary.
     *
     * @param segmentEdge the start of the segment we're expanding from
     * @param searchBoundary the minBoundary we're searching toward
     * @param lastProbeStart the start of the last probe that returned 0 results
     * @param iterationCount how many binary search iterations we've done
     * @param consecutiveEmptyLoads for fallback duration escalation
     */
    record BinarySearchBefore(Instant segmentEdge, Instant searchBoundary, Instant lastProbeStart, int iterationCount,
            int consecutiveEmptyLoads) implements LoadingState {

        /**
         * Calculate the next probe start using binary search.
         * Probes halfway between searchBoundary and lastProbeStart.
         */
        public Instant nextProbeStart() {
            long boundaryMs = searchBoundary.toEpochMilli();
            long lastMs = lastProbeStart.toEpochMilli();
            long midMs = boundaryMs + (lastMs - boundaryMs) / 2;
            return Instant.ofEpochMilli(midMs);
        }

        public BinarySearchBefore withProbeStart(Instant newProbeStart) {
            return new BinarySearchBefore(segmentEdge, searchBoundary, newProbeStart, iterationCount + 1,
                    consecutiveEmptyLoads + 1);
        }

        /**
         * Check if search range is too small to continue (< 1 second).
         */
        public boolean isExhausted() {
            return Duration.between(searchBoundary, lastProbeStart).toMillis() < 1000;
        }
    }

    /**
     * Binary search state when scrolling DOWN (later/after).
     * Searches from segment end toward maxBoundary/noLaterThan.
     *
     * @param segmentEdge the end of the segment we're expanding from
     * @param searchBoundary the maxBoundary we're searching toward
     * @param lastProbeEnd the end of the last probe that returned 0 results
     * @param iterationCount how many binary search iterations we've done
     * @param consecutiveEmptyLoads for fallback duration escalation
     */
    record BinarySearchAfter(Instant segmentEdge, Instant searchBoundary, Instant lastProbeEnd, int iterationCount,
            int consecutiveEmptyLoads) implements LoadingState {

        /**
         * Calculate the next probe end using binary search.
         * Probes halfway between lastProbeEnd and searchBoundary.
         */
        public Instant nextProbeEnd() {
            long lastMs = lastProbeEnd.toEpochMilli();
            long boundaryMs = searchBoundary.toEpochMilli();
            long midMs = lastMs + (boundaryMs - lastMs) / 2;
            return Instant.ofEpochMilli(midMs);
        }

        public BinarySearchAfter withProbeEnd(Instant newProbeEnd) {
            return new BinarySearchAfter(segmentEdge, searchBoundary, newProbeEnd, iterationCount + 1,
                    consecutiveEmptyLoads + 1);
        }

        /**
         * Check if search range is too small to continue (< 1 second).
         */
        public boolean isExhausted() {
            return Duration.between(lastProbeEnd, searchBoundary).toMillis() < 1000;
        }
    }

    /**
     * Gap verification state - after a too-big result, verify no logs in the gap.
     *
     * @param direction -1 for prepending, +1 for appending
     * @param gapEnd the end of the gap (first log timestamp from too-big result)
     * @param velocityFromTooBig velocity calculated from the too-big result
     * @param targetLoadLines current target, reduced after too-big result
     * @param retryCount number of times we've retried gap verification
     */
    record GapVerification(int direction, Instant gapEnd, double velocityFromTooBig, double targetLoadLines,
            int retryCount) implements LoadingState {

        /**
         * Estimate duration for velocity-based load after gap verification.
         */
        public Duration estimateTimeRange() {
            if (velocityFromTooBig <= 0) {
                return Duration.ofMinutes(1);
            }
            double millisNeeded = 1000.0 * targetLoadLines / velocityFromTooBig;
            millisNeeded = Math.max(10.0, Math.min(millisNeeded, 31_536_000_000.0));
            return Duration.ofMillis(Math.round(millisNeeded));
        }
    }
}
