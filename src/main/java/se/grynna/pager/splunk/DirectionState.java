package se.grynna.pager.splunk;

import java.time.Duration;
import java.time.Instant;

/**
 * State machine for a single loading direction (UP = earlier/before, DOWN = later/after).
 * Each direction has independent state, so switching scroll direction doesn't lose progress.
 *
 * ## Splunk Reading Order & Strategy
 *
 * Splunk reads from earliest to latest. This affects our search strategies:
 *
 * - **SeekEarlier (UP direction)**: When searching backward, Splunk finds logs nearest
 *   to the segment edge first. We can search the whole boundary range with maxEvents
 *   and use GapVerification on truncation (order is somewhat stochastic).
 *
 * - **BinarySearchLater (DOWN direction)**: When searching forward, Splunk may hit
 *   truncation before finding sparse logs near the end. We need proper binary search.
 *
 * ## State Transitions
 *
 * Init → Normal (first load with results and velocity)
 * Init → SeekEarlier (first load returns 0 results, for UP direction)
 * Init → BinarySearchLater (first load returns 0 results, for DOWN direction)
 *
 * Normal → Normal (successful load, update velocity)
 * Normal → SeekEarlier/BinarySearchLater (0 results, start seeking sparse region)
 * Normal → GapVerification (TooBigException only)
 * Normal → AtBoundary (reached Epoch/NOW hard limit)
 *
 * SeekEarlier (for UP direction - searches whole range to boundary):
 *   → Normal when results found (calculate velocity, segment expanded)
 *   → GapVerification when TooBig (always, since Splunk order is stochastic)
 *   → AtBoundary when 0 results (exhausted, prompt user)
 *
 * BinarySearchLater (for DOWN direction - proper binary search):
 *   Uses low/high bounds and iteratively halves the search space.
 *   On empty result: move low bound up (search later half)
 *   On TooBig result: move high bound down (search earlier half)
 *   → Normal when results found (calculate velocity, segment expanded)
 *   → AtBoundary when search space exhausted (low >= high)
 *
 * GapVerification (only entered via TooBig):
 *   → Normal when 0 results (gap confirmed empty, use probableVelocity)
 *   → Normal when results found (segment expanded, calculate new velocity)
 *   → GapVerification when TooBig (new gap to verify)
 *
 * AtBoundary → SeekEarlier/BinarySearchLater (boundary expanded, restart search)
 *
 * ## Key Rules
 * - GapVerification is ONLY entered after TooBig (incomplete) reads
 * - Normal reads with results: segment edge moves, go directly to Normal
 * - SeekEarlier (UP): searches whole boundary, uses maxEvents, always GapVerification on TooBig
 * - BinarySearchLater (DOWN): proper binary search to handle Splunk's earliest→latest reading order
 * - Segment edge is updated by successful (complete) reads, not by TooBig reads
 */
public sealed interface DirectionState permits DirectionState.Init, DirectionState.Normal, DirectionState.SeekEarlier,
        DirectionState.BinarySearchLater, DirectionState.GapVerification, DirectionState.AtBoundary {

    double MAX_TARGET_LOAD_LINES = 2500.0;

    /**
     * Minimum search range for binary search - 1 second.
     */
    long BINARY_SEARCH_MIN_RANGE_MS = 1;

    /**
     * Get velocity if available, 0 otherwise.
     */
    default double getVelocity() {
        return switch (this) {
            case Normal n -> n.velocity();
            case GapVerification g -> g.probableVelocity();
            default -> 0;
        };
    }

    /**
     * Get target load lines.
     */
    default double getTargetLoadLines() {
        return switch (this) {
            case Normal n -> n.targetLoadLines();
            case GapVerification g -> g.targetLoadLines();
            default -> MAX_TARGET_LOAD_LINES;
        };
    }

    boolean shouldStore();

    // --- States ---

    /**
     * Initial state - no load attempted in this direction yet.
     */
    record Init() implements DirectionState {
        @Override
        public boolean shouldStore() {
            return true;
        }
    }

    /**
     * Normal loading - have velocity, use rate-based time estimation.
     *
     * @param velocity logs per second from recent loads
     * @param targetLoadLines target number of lines per load
     * @param consecutiveEmptyLoads for fallback escalation when 0 results
     */
    record Normal(double velocity, double targetLoadLines, int consecutiveEmptyLoads) implements DirectionState {

        public Normal withVelocity(double newVelocity) {
            return new Normal(newVelocity, targetLoadLines, 0);
        }

        public Normal incrementEmptyLoads() {
            return new Normal(velocity, targetLoadLines, consecutiveEmptyLoads + 1);
        }

        /**
         * Estimate duration to fetch targetLoadLines based on velocity.
         */
        public Duration estimateTimeRange() {
            if (velocity <= 0) {
                // No velocity means we should transition to seeking state, not estimate
                return Duration.ofMinutes(5);
            }
            double millisNeeded = 1000.0 * targetLoadLines / velocity;
            millisNeeded = Math.max(10.0, Math.min(millisNeeded, 31_536_000_000.0)); // 10ms to 1 year
            return Duration.ofMillis(Math.round(millisNeeded));
        }

        @Override
        public boolean shouldStore() {
            return true;
        }
    }

    /**
     * Seek earlier state - searching backwards for logs in a sparse region (UP direction).
     *
     * Since Splunk reads earliest→latest, when searching backward the logs nearest to
     * the segment edge are found first. This allows us to search the whole boundary
     * range at once with maxEvents limit. On truncation, we go to GapVerification
     * since the exact order is somewhat stochastic.
     *
     * @param searchBoundary the minimum boundary we're searching toward
     * @param consecutiveEmptyLoads for fallback duration if transitioning to Normal
     */
    record SeekEarlier(Instant searchBoundary, int consecutiveEmptyLoads) implements DirectionState {

        /**
         * Create initial seek earlier state from segment edge to boundary.
         */
        public static SeekEarlier start(Instant boundary, int consecutiveEmpty) {
            return new SeekEarlier(boundary, consecutiveEmpty);
        }

        /**
         * Increment empty loads counter after unsuccessful search.
         */
        public SeekEarlier incrementEmptyLoads() {
            return new SeekEarlier(searchBoundary, consecutiveEmptyLoads + 1);
        }

        @Override
        public boolean shouldStore() {
            return true;
        }
    }

    /**
     * Binary search state for DOWN direction (searching forward in time).
     *
     * Splunk returns results newest-first, so when searching forward:
     * 0. When entering state, bsDuration is set to the maxBoundary-segmentEnd
     *
     * 1. Search whole range [segmentEnd - segmentEnd+bsDuration]
     * 2. If empty:
     * 2a if segmentEnd+bsDuration >= maxBoundary → AtBoundary
     * 2b else → bsDuration = bsDuration / 2 (segment.end is increased by reportResults)
     * 3  If tooBig → bsDuration = bsDuration / 2
     * 4. If results (not truncated) → go to Normal.
     *
     */
    record BinarySearchLater(Duration bsDuration) implements DirectionState {

        /**
         * Create initial binary search from segment edge to searchSpaceEnd.
         */
        public static BinarySearchLater start(Instant start, Instant maxBoundary) {
            if (start.isAfter(maxBoundary)) {
                throw new IllegalArgumentException("start must be before maxBoundary " + start + " > " + maxBoundary);
            }
            if (start.equals(maxBoundary)) {
                throw new IllegalArgumentException("start must be before maxBoundary " + start + " == " + maxBoundary);
            }
            return new BinarySearchLater(Duration.between(start, maxBoundary));
        }

        /**
         * Calculate the midpoint between segment end and high bound.
         */
        public BinarySearchLater halved() {
            if (bsDuration.toMillis() <= BINARY_SEARCH_MIN_RANGE_MS) {
                throw new IllegalStateException("bsDuration: " + bsDuration);
            }
            return new BinarySearchLater(bsDuration.dividedBy(2));
        }

        @Override
        public boolean shouldStore() {
            return true;
        }
    }

    /**
     * Gap verification - after TooBigException, verify no logs were missed in the gap.
     *
     * @param probableLatestLog the timestamp of first known log from too-big result
     * @param probableVelocity velocity calculated from the truncated result
     * @param targetLoadLines reduced target after too-big
     * @param retryCount number of gap verification retries
     */
    record GapVerification(Instant probableLatestLog, double probableVelocity, double targetLoadLines, int retryCount)
            implements DirectionState {

        public Duration estimateTimeRange() {
            if (probableVelocity <= 0) {
                return Duration.ofMinutes(1);
            }
            double millisNeeded = 1000.0 * targetLoadLines / probableVelocity;
            millisNeeded = Math.max(10.0, Math.min(millisNeeded, 31_536_000_000.0));
            return Duration.ofMillis(Math.round(millisNeeded));
        }

        public GapVerification incrementRetry() {
            return new GapVerification(probableLatestLog, probableVelocity, targetLoadLines, retryCount + 1);
        }

        @Override
        public boolean shouldStore() {
            return true;
        }
    }

    /**
     * At boundary - search exhausted to boundary, waiting for expansion.
     * Stores the previous state so we can resume properly after expansion.
     *
     * @param previousState the state we were in before hitting boundary
     *                      Used to restore mode after boundary expansion.
     */
    record AtBoundary(Instant boundary, DirectionState previousState) implements DirectionState {

        @Override
        public boolean shouldStore() {
            return true;
        }

        @Override
        public double getVelocity() {
            return previousState.getVelocity();
        }

        @Override
        public double getTargetLoadLines() {
            return previousState.getTargetLoadLines();
        }

        /**
         * Create the state to transition to after boundary is expanded.
         * If was in SeekEarlier/BinarySearchLater, continue search to new boundary.
         * If was in Normal, stay in Normal.
         *
         * @param newBoundary the new expanded boundary
         * @return new state to continue loading
         */
        public DirectionState resumeAfterExpansion(Instant newBoundary) {
            return switch (previousState) {
                case SeekEarlier se -> {
                    // Continue seeking earlier with new boundary
                    yield new SeekEarlier(newBoundary, se.consecutiveEmptyLoads());
                }
                case BinarySearchLater bs -> {
                    // Continue binary search with new boundary
                    // segmentEnd stays the same, searchSpaceEnd is the new expanded boundary
                    yield BinarySearchLater.start(boundary, newBoundary);
                }
                case Normal n -> n; // Stay in Normal, will use velocity-based loading
                default -> throw new IllegalStateException("Cannot resume from AtBoundary when previous state is "
                        + previousState.getClass().getSimpleName());
            };
        }
    }
}
