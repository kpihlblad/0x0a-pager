package se.grynna.pager.splunk;

public record NowBoundaryLine(int line, Boundary boundary) implements LogLine {

    // Require non-null boundary
    public NowBoundaryLine {
        if (boundary == null) {
            throw new NullPointerException("boundary must be non-null");
        }
    }
}
