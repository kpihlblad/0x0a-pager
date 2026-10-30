package se.grynna.pager.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adaptive threshold for "unusually long" gaps between the events of a result set.
 * <p>
 * {@code threshold = clamp(8 × median gap, 1 s, 1 h)}. The median is taken over the
 * <em>non-zero</em> gaps between consecutive times: equal neighbours (continuation rows,
 * identical timestamps) say nothing about spacing and would otherwise drag the median to
 * zero. With fewer than 3 times, or no non-zero gap at all, there is no threshold.
 */
public final class GapThreshold {

    static final Duration MIN = Duration.ofSeconds(1);
    static final Duration MAX = Duration.ofHours(1);
    static final int MULTIPLIER = 8;
    static final int MIN_TIMES = 3;

    private GapThreshold() {}

    /**
     * @param times event times in ascending order (may contain equal neighbours)
     * @return the adaptive threshold, or empty when there are too few times to judge
     */
    public static Optional<Duration> adaptive(List<Instant> times) {
        if (times == null || times.size() < MIN_TIMES) {
            return Optional.empty();
        }
        List<Duration> gaps = new ArrayList<>(times.size() - 1);
        for (int i = 1; i < times.size(); i++) {
            Duration gap = Duration.between(times.get(i - 1), times.get(i));
            if (!gap.isZero()) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return Optional.empty();
        }
        gaps.sort(null);
        int n = gaps.size();
        Duration median = n % 2 == 1
                ? gaps.get(n / 2)
                : gaps.get(n / 2 - 1).plus(gaps.get(n / 2)).dividedBy(2);
        Duration threshold = median.multipliedBy(MULTIPLIER);
        if (threshold.compareTo(MIN) < 0) {
            threshold = MIN;
        } else if (threshold.compareTo(MAX) > 0) {
            threshold = MAX;
        }
        return Optional.of(threshold);
    }

    /**
     * Keeps the threshold stable while a search streams in: it is recomputed only when the
     * count has grown by at least 10 % since the last computation, or when the search
     * finishes (first call always computes).
     */
    public static final class Tracker {

        private boolean computed;
        private boolean lastFinished;
        private int lastCount;
        private Optional<Duration> cached = Optional.empty();

        /**
         * @param times    current event times in ascending order
         * @param finished whether the producing search has completed
         * @return the (possibly cached) threshold
         */
        public Optional<Duration> update(List<Instant> times, boolean finished) {
            int count = times.size();
            boolean finishedNow = finished && !lastFinished;
            boolean grewEnough = (long) count * 10 >= (long) lastCount * 11;
            if (!computed || finishedNow || grewEnough) {
                cached = adaptive(times);
                computed = true;
                lastCount = count;
            }
            lastFinished = finished;
            return cached;
        }
    }
}
