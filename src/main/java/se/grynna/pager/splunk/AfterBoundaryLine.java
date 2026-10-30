package se.grynna.pager.splunk;

public record AfterBoundaryLine(int line, Boundary boundary, String message) implements LogLine {
    public AfterBoundaryLine(int line, Boundary boundary) {
        this(line, boundary, null);
    }
}
