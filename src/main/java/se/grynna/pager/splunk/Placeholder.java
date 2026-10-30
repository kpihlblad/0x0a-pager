package se.grynna.pager.splunk;

public record Placeholder(int line, String message) implements LogLine {

    public static Placeholder from(int line, String message) {
        return new Placeholder(line, message);
    }
}
