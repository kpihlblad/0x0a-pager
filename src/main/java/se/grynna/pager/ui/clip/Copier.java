package se.grynna.pager.ui.clip;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import se.grynna.pager.utils.HexDump;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("serial")
@Slf4j
public class Copier {

    public static final boolean DEBUG = false;

    /** Copies the lines to the system clipboard. */
    public static void copy(List<String> selection) {
        ClipboardAccess.system().setContents(transferable(selection));
    }

    /** The clipboard contents for the lines: rich text (HTML) plus plain text, one line per entry. */
    public static Transferable transferable(List<String> selection) {
        return new MyTransferable(selection);
    }

    static class MyTransferable implements Transferable {
        private static final ArrayList<DataFlavor> MyFlavors = new ArrayList<>();

        static {
            try {
                // ,
                for (String m : new String[]{"text/html", "text/plain"}) {
                    MyFlavors.add(new DataFlavor(m + ";class=java.lang.String"));
                    MyFlavors.add(new DataFlavor(m + ";class=java.io.Reader"));
                    MyFlavors.add(new DataFlavor(m + ";class=java.io.InputStream;charset=utf-8"));
                }
            } catch (ClassNotFoundException e) {
                log.error("Error copying to clipboard", e);
            }
        }

        private final List<String> pairs;
        private ByteArrayInputStream bos;
        private BufferedReader reader;

        MyTransferable(List<String> pairs) {
            this.pairs = pairs;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return MyFlavors.toArray(new DataFlavor[0]);
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            log.debug("Check flavor: {}", flavor);
            return MyFlavors.contains(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            StringBuilder content;

            if (flavor.getMimeType().contains("text/plain")) {
                if (DEBUG) {
                    content = new StringBuilder("DEBUG");
                } else {
                    content = str(pairs);
                }
            } else if (flavor.getMimeType().contains("text/html")) {
                if (DEBUG) {
                    content = str(pairs);
                    for (int i = 0; i < pairs.size(); i++) {
                        log.debug("-------- {}: {}", i, pairs.get(i));
                        log.debug(HexDump.hexDump(pairs.get(i)));
                    }

                    log.debug("text/html: {}", content);
                    // log a nice line + hex
                    log.debug("copy:\n {}", HexDump.hexDump(content.toString()));

                } else {
                    // Use htmlInline for inline HTML when pasting a single row
                    if (pairs.size() == 1) {
                        content = htmlInline(pairs.getFirst());
                    } else {
                        content = html(pairs);
                    }
                }
            } else {
                throw new UnsupportedFlavorException(flavor);
            }
            log.debug("copy: {}", flavor);

            if (String.class.equals(flavor.getRepresentationClass())) {
                return content.toString();
            } else if (Reader.class.equals(flavor.getRepresentationClass())) {
                if (reader == null) {
                    reader = new BufferedReader(new InputStreamReader(
                            new ByteArrayInputStream(content.toString().getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8));
                }
                return reader;
            } else if (InputStream.class.equals(flavor.getRepresentationClass())) {
                if (bos == null) {
                    bos = new ByteArrayInputStream(content.toString().getBytes(StandardCharsets.UTF_8));
                }
                return bos;
            }

            throw new UnsupportedFlavorException(flavor);
        }

        private static StringBuilder html(List<String> selection) {

            // NOTE TO SELF - Teams html pasting is broken and can't handle <code> or <pre> properly, and messes up
            // <tables>
            // DO NOT USE
            // This version is based on VS Codes clipboard output, and it's probably as good as it gets.

            StringBuilder html = new StringBuilder();

            String startHtml = """
                    <div style="font-family: Consolas, 'Courier New', monospace;font-weight: normal;font-size: 14px;line-height: 19px;white-space: pre;">""";

            String endHtml = """
                    </div>""";

            html.append(startHtml);

            for (String s : selection) {
                if (StringUtils.isEmpty(s)) {
                    html.append("<br>");
                } else {
                    html.append("<div>").append(StringEscapeUtils.escapeHtml4(StringUtils.stripEnd(s, null)))
                            .append("</div>");
                }
            }
            html.append(endHtml);

            log.info("html-content:{}", html);

            return html;
        }

        // Add this method for inline HTML pasting
        private static StringBuilder htmlInline(String s) {
            StringBuilder html = new StringBuilder();
            html.append(
                    """
                            <span style="font-family: Consolas, 'Courier New', monospace;font-weight: normal;font-size: 14px;line-height: 19px;white-space: pre;">""");

            html.append(StringEscapeUtils.escapeHtml4(s));
            html.append("</span>");
            log.info("html-inline-content:{}", html);
            return html;
        }

        private static StringBuilder str(List<String> selection) {

            StringBuilder str = new StringBuilder();
            for (String s : selection) {
                str.append(s);
                str.append("\n");
            }
            return str;
        }
    }
}
