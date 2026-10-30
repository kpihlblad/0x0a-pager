package se.grynna.pager.splunk;

/**
 * Type of boundary that was hit (for user prompting).
 */
public enum BoundaryType {
    BEFORE(-1), AFTER(1), NOW(0);

    private final int direction;

    BoundaryType(int direction) {
        this.direction = direction;
    }

    public int direction() {
        return direction;
    }
}
