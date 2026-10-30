package se.grynna.pager.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportNoticesTest {

    @Test
    void copyNoticeReadsNaturallyForEveryFormat() {
        assertThat(ExportNotices.copied(2, ExportFormat.TEXT)).isEqualTo("Copied 2 events as shown");
        assertThat(ExportNotices.copied(2, ExportFormat.MESSAGE)).isEqualTo("Copied 2 events (message only)");
        assertThat(ExportNotices.copied(2, ExportFormat.RAW)).isEqualTo("Copied 2 events as raw events");
        assertThat(ExportNotices.copied(1, ExportFormat.CSV)).isEqualTo("Copied 1 event as CSV");
        assertThat(ExportNotices.copied(2, ExportFormat.HTML)).isEqualTo("Copied 2 events as HTML table");
    }
}
