package se.grynna.pager.export;

/** The formats an event selection can be copied or exported as. */
public enum ExportFormat {

    TEXT("As shown", "time + message, like Ctrl-C", ".txt"), MESSAGE("Message only", "no timestamp", ".txt"), RAW(
            "Raw event", "Splunk _raw", ".log"), JSONL("JSON lines", "all fields, one event per line", ".jsonl"), CSV(
                    "CSV", "time, level, message — opens in Excel",
                    ".csv"), HTML("HTML table", "colours and highlight, for mail/Word", ".html");

    private final String displayName;
    private final String description;
    private final String fileExtension;

    ExportFormat(String displayName, String description, String fileExtension) {
        this.displayName = displayName;
        this.description = description;
        this.fileExtension = fileExtension;
    }

    public String displayName() {
        return displayName;
    }

    /** One line saying what the format holds, shown beside the name. */
    public String description() {
        return description;
    }

    /** The file extension including the leading dot. */
    public String fileExtension() {
        return fileExtension;
    }
}
