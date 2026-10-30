package se.grynna.pager.ui.clip;

import se.grynna.pager.export.EventExporter;
import se.grynna.pager.export.ExportEvent;
import se.grynna.pager.export.ExportFormat;
import se.grynna.pager.export.ExportOptions;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The clipboard contents of a copy-as. HTML offers {@code text/html} (the HTML fragment) and a plain-text
 * fallback in the TEXT format. Every other format is plain text only, as {@link EventExporter#exportText}
 * writes it (CSV without its byte order mark).
 */
public final class ExportTransferable implements Transferable {

    private final Map<DataFlavor, String> contents;

    private ExportTransferable(Map<DataFlavor, String> contents) {
        this.contents = contents;
    }

    public static Transferable of(List<ExportEvent> events, ExportFormat format, ExportOptions options) {
        Map<DataFlavor, String> contents = new LinkedHashMap<>();
        if (format == ExportFormat.HTML) {
            contents.put(DataFlavor.allHtmlFlavor, EventExporter.exportText(events, format, options));
            plain(contents, EventExporter.exportText(events, ExportFormat.TEXT, options));
        } else {
            plain(contents, EventExporter.exportText(events, format, options));
        }
        return new ExportTransferable(contents);
    }

    private static void plain(Map<DataFlavor, String> contents, String text) {
        contents.put(DataFlavor.stringFlavor, text);
        contents.put(ClipboardAccess.PLAIN_TEXT, text);
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return contents.keySet().toArray(new DataFlavor[0]);
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return contents.containsKey(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (!contents.containsKey(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        return contents.get(flavor);
    }
}
