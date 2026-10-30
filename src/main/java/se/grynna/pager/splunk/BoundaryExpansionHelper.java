package se.grynna.pager.splunk;

import java.time.*;

/**
 * Helper for calculating suggested boundary expansions.
 * Provides consistent boundary expansion logic for both UP and DOWN directions.
 *
 * Expansion sequence:
 * - 1: 1 day
 * - 2: 1 month
 * - 3: 3 months
 * - 4: 1 year
 * - 5+: to epoch (BEFORE) or now (AFTER)
 */
public final class BoundaryExpansionHelper {

    /** Default timezone for date calculations */
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    private BoundaryExpansionHelper() {
        // Static utility class
    }

    /**
     * Calculate the suggested boundary expansion.
     *
     * @param type              BEFORE (earlier) or AFTER (later) expansion
     * @param originalBoundary  the current boundary to expand from
     * @param expansionCount    number of consecutive expansions (1 = first expansion)
     * @param now               current time instant (for clamping AFTER expansions)
     * @return the suggested new boundary, clamped to valid limits
     */
    public static Instant calculateExpansion(BoundaryType type, Instant originalBoundary, int expansionCount,
            Instant now) {
        if (type == BoundaryType.NOW) {
            throw new IllegalArgumentException("Cannot expand NOW boundary");
        }

        if (expansionCount >= 5) {
            // "Everything": exactly the absolute limit, not a local-date approximation that can leave a sliver.
            if (type == BoundaryType.BEFORE) {
                return SearchBoundaries.MIN_MINIMUM_BOUNDARY;
            }
            if (now != null) {
                return now.isAfter(originalBoundary) ? now : originalBoundary;
            }
        }

        ZonedDateTime zdt = ZonedDateTime.ofInstant(originalBoundary, DEFAULT_ZONE);
        int direction = type.direction(); // -1 for BEFORE, +1 for AFTER

        Period period = switch (expansionCount) {
            case 0 -> throw new IllegalStateException("Expansion count must be 1 or greater");
            case 1 -> Period.ofDays(direction);
            case 2 -> Period.ofMonths(direction);
            case 3 -> Period.ofMonths(3 * direction);
            case 4 -> Period.ofYears(direction);
            default -> {
                // Maximum expansion: to epoch (BEFORE) or now (AFTER)
                if (type == BoundaryType.BEFORE) {
                    ZonedDateTime epochZdt = ZonedDateTime.ofInstant(SearchBoundaries.MIN_MINIMUM_BOUNDARY,
                            DEFAULT_ZONE);
                    yield Period.between(zdt.toLocalDate(), epochZdt.toLocalDate());
                } else {
                    ZonedDateTime nowZdt = ZonedDateTime.ofInstant(now, DEFAULT_ZONE);
                    yield Period.between(zdt.toLocalDate(), nowZdt.toLocalDate());
                }
            }
        };

        ZonedDateTime result = zdt.plus(period);

        // Clamp to valid limits
        if (result.toInstant().isBefore(SearchBoundaries.MIN_MINIMUM_BOUNDARY)) {
            return SearchBoundaries.MIN_MINIMUM_BOUNDARY;
        }
        if (now != null && result.toInstant().isAfter(now)) {
            return now;
        }

        return result.toInstant();
    }
}
