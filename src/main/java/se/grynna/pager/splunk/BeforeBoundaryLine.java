package se.grynna.pager.splunk;

public record BeforeBoundaryLine(int line, Boundary boundary, String message) implements LogLine {
    public BeforeBoundaryLine(int line, Boundary boundary) {
        this(line, boundary, null);
    }
}
