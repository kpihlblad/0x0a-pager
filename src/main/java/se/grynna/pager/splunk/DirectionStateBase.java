package se.grynna.pager.splunk;

/**
 * Common marker interface for direction state machines.
 * Both DirectionEarlier and DirectionLater implement this interface
 * to allow polymorphic handling where the specific direction doesn't matter.
 */
public interface DirectionStateBase {

    /**
     * Get target load lines.
     */
    double getTargetLoadLines();

    /**
     * Get consecutive empty loads for fallback duration escalation.
     */
    int getConsecutiveEmptyLoads();

    /**
     * Whether results from this state should be stored in the segment.
     */
    boolean shouldStore();

    /**
     * Format a velocity value for logging (avoids E-notation).
     * Shows 6 decimal places for small values, 2 for larger ones.
     */
    static String fmtV(double velocity) {
        if (velocity == 0) {
            return "0";
        }
        if (Math.abs(velocity) < 0.01) {
            return String.format("%.6f", velocity);
        }
        return String.format("%.2f", velocity);
    }

    /**
     * Get simple state name for logging (just class name without package/record details).
     */
    default String stateName() {
        return getClass().getSimpleName();
    }
}
