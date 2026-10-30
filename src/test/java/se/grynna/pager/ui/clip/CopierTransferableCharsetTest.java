package se.grynna.pager.ui.clip;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transferable advertises {@code charset=utf-8} flavors, so its Reader flavor must decode
 * with UTF-8 rather than the platform default charset.
 */
class CopierTransferableCharsetTest {

    private static final String NON_ASCII = "åäö €";

    private static final String COPIED_TEXT = NON_ASCII + "\n";

    @Test
    void readerFlavorYieldsUtf8Text() throws Exception {
        Transferable transferable = transferable();
        try (Reader reader = (Reader) transferable.getTransferData(new DataFlavor("text/plain;class=java.io.Reader"))) {
            assertThat(readAll(reader)).isEqualTo(COPIED_TEXT);
        }
    }

    @Test
    void inputStreamFlavorYieldsUtf8Bytes() throws Exception {
        Transferable transferable = transferable();
        try (InputStream in = (InputStream) transferable
                .getTransferData(new DataFlavor("text/plain;class=java.io.InputStream;charset=utf-8"))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(COPIED_TEXT);
        }
    }

    @Test
    void stringFlavorYieldsOriginalText() throws Exception {
        Transferable transferable = transferable();
        assertThat((String) transferable.getTransferData(new DataFlavor("text/plain;class=java.lang.String")))
                .isEqualTo(COPIED_TEXT);
    }

    private static String readAll(Reader reader) throws Exception {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[16];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            text.append(buffer, 0, read);
        }
        return text.toString();
    }

    private static Transferable transferable() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "clipboard requires a display");
        try {
            return new Copier.MyTransferable(List.of(NON_ASCII));
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assumptions.abort("clipboard unavailable: " + e);
            return null;
        }
    }
}
