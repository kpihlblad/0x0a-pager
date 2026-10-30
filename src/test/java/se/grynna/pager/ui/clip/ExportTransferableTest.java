package se.grynna.pager.ui.clip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.export.EventExporter;
import se.grynna.pager.export.ExportEvent;
import se.grynna.pager.export.ExportFormat;
import se.grynna.pager.export.ExportOptions;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportTransferableTest {

    private static final Instant T0 = Instant.parse("2023-01-01T12:00:00Z");
    private static final ExportOptions OPTIONS = new ExportOptions("needle", ZoneOffset.UTC);

    private List<ExportEvent> events;

    @BeforeEach
    void formatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
        events = List.of(event(0, "find the needle <now>"), event(1, "second"));
    }

    private static ExportEvent event(long seconds, String message) {
        Instant time = T0.plusSeconds(seconds);
        var line = new LoadedLogLine(MsgType.JSON, null, 0, "cd" + seconds, "bkt", time, "raw", time, time, message,
                false, Level.ERROR);
        line.setEvent(new TreeMap<>(Map.of("_cd", "cd" + seconds, "_bkt", "bkt")));
        return ExportEvent.of(line);
    }

    @Test
    void htmlOffersTheHtmlFragmentAndThePlainTextFallbackOfTheTextFormat() throws Exception {
        Transferable contents = ExportTransferable.of(events, ExportFormat.HTML, OPTIONS);

        assertThat(contents.isDataFlavorSupported(DataFlavor.allHtmlFlavor)).isTrue();
        assertThat(contents.getTransferData(DataFlavor.allHtmlFlavor))
                .isEqualTo(EventExporter.exportText(events, ExportFormat.HTML, OPTIONS));
        assertThat(contents.isDataFlavorSupported(ClipboardAccess.PLAIN_TEXT)).isTrue();
        assertThat(contents.getTransferData(ClipboardAccess.PLAIN_TEXT))
                .isEqualTo(EventExporter.exportText(events, ExportFormat.TEXT, OPTIONS));
        assertThat(contents.getTransferData(DataFlavor.stringFlavor))
                .isEqualTo(EventExporter.exportText(events, ExportFormat.TEXT, OPTIONS));
    }

    @Test
    void htmlHighlightsTheSearchTermAndTheTextFallbackDoesNot() throws Exception {
        Transferable contents = ExportTransferable.of(events, ExportFormat.HTML, OPTIONS);

        assertThat((String) contents.getTransferData(DataFlavor.allHtmlFlavor)).contains("<mark>needle</mark>");
        assertThat((String) contents.getTransferData(ClipboardAccess.PLAIN_TEXT)).doesNotContain("<mark>");
    }

    @Test
    void theClipboardReadsTheHtmlPayloadAsItsPlainText() {
        var clipboard = new InMemoryClipboard();
        clipboard.setContents(ExportTransferable.of(events, ExportFormat.HTML, OPTIONS));

        assertThat(clipboard.text()).contains(EventExporter.exportText(events, ExportFormat.TEXT, OPTIONS));
    }

    @Test
    void csvIsPlainTextWithoutAByteOrderMark() throws Exception {
        Transferable contents = ExportTransferable.of(events, ExportFormat.CSV, OPTIONS);

        assertThat(contents.isDataFlavorSupported(DataFlavor.allHtmlFlavor)).isFalse();
        String text = (String) contents.getTransferData(ClipboardAccess.PLAIN_TEXT);
        assertThat(text).startsWith("time,level,message\r\n").doesNotStartWith("\uFEFF");
        assertThat(text).isEqualTo(EventExporter.exportText(events, ExportFormat.CSV, OPTIONS));
    }

    @Test
    void textFormatsOfferOnlyPlainText() {
        for (ExportFormat format : List.of(ExportFormat.TEXT, ExportFormat.MESSAGE, ExportFormat.RAW,
                ExportFormat.JSONL, ExportFormat.CSV)) {
            Transferable contents = ExportTransferable.of(events, format, OPTIONS);

            assertThat(contents.getTransferDataFlavors()).as(format.name())
                    .containsExactlyInAnyOrder(DataFlavor.stringFlavor, ClipboardAccess.PLAIN_TEXT);
        }
    }

    @Test
    void anUnofferedFlavourIsRejected() {
        Transferable contents = ExportTransferable.of(events, ExportFormat.CSV, OPTIONS);

        assertThatThrownBy(() -> contents.getTransferData(DataFlavor.allHtmlFlavor))
                .isInstanceOf(UnsupportedFlavorException.class);
    }
}
