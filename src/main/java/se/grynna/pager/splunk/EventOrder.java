package se.grynna.pager.splunk;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared event ordering rules used by both loaded rows and search hits.
 */
public final class EventOrder {

    private EventOrder() {}

    static Comparator<LoadedLogLine> loadedLogLineComparator() {
        return Comparator.comparing(LoadedLogLine::getSplunkTime).thenComparing(LoadedLogLine::getDisplayTime)
                .thenComparing(line -> parseCbForComparison(line.getCb()))
                .thenComparing(LoadedLogLine::getBkt, Comparator.nullsFirst(String::compareTo))
                .thenComparing(LoadedLogLine::getCb, Comparator.nullsFirst(String::compareTo))
                .thenComparing(LoadedLogLine::isContinuation);
    }

    public static Comparator<SearchHit> searchHitComparator() {
        return Comparator.comparing(SearchHit::splunkTime).thenComparing(SearchHit::displayTime)
                .thenComparing(hit -> parseCbForComparison(hit.cd()))
                .thenComparing(SearchHit::bkt, Comparator.nullsFirst(String::compareTo))
                .thenComparing(SearchHit::cd, Comparator.nullsFirst(String::compareTo));
    }

    static boolean sameIdentity(LoadedLogLine line, SearchHit hit) {
        return line != null && hit != null && hit.hasIdentity() && hit.bkt().equals(line.getBkt())
                && hit.cd().equals(line.getCb());
    }

    /**
     * Parse the cb field (e.g., {@code 226:1222552}) into a comparable value.
     * All consecutive digits are interpreted as decimal parts and compared
     * lexicographically as BigIntegers.
     */
    static CbComparable parseCbForComparison(String cb) {
        if (cb == null || cb.isEmpty()) {
            return new CbComparable(List.of());
        }

        List<BigInteger> numbers = new ArrayList<>();
        StringBuilder currentNumber = new StringBuilder();

        for (int i = 0; i < cb.length(); i++) {
            char c = cb.charAt(i);
            if (Character.isDigit(c)) {
                currentNumber.append(c);
            } else if (!currentNumber.isEmpty()) {
                numbers.add(new BigInteger(currentNumber.toString()));
                currentNumber.setLength(0);
            }
        }

        if (!currentNumber.isEmpty()) {
            numbers.add(new BigInteger(currentNumber.toString()));
        }

        return new CbComparable(numbers);
    }

    static final class CbComparable implements Comparable<CbComparable> {
        private final List<BigInteger> numbers;

        CbComparable(List<BigInteger> numbers) {
            this.numbers = numbers;
        }

        @Override
        public int compareTo(CbComparable other) {
            int minSize = Math.min(this.numbers.size(), other.numbers.size());
            for (int i = 0; i < minSize; i++) {
                int cmp = this.numbers.get(i).compareTo(other.numbers.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(this.numbers.size(), other.numbers.size());
        }
    }
}
