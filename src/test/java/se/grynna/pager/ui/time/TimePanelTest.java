package se.grynna.pager.ui.time;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import se.grynna.pager.splunk.Config;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.GotoHandler;
import se.grynna.pager.ui.time.TimePanel.Focus;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TimePanelTest {

    private static final Instant NOW = Instant.parse("2017-05-03T08:00:00Z");
    private static final Instant AT = Instant.parse("2017-05-01T12:03:14Z");
    private static final Instant FROM = Instant.parse("2017-05-01T11:00:00Z");

    /** Records the listener calls as strings. */
    private static final class Recorder implements TimePanel.Listener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void go(Instant at) {
            calls.add("go " + at);
        }

        @Override
        public void setRange(Instant from, Instant to) {
            calls.add("range " + from + " " + to);
        }

        @Override
        public void close() {
            calls.add("close");
        }
    }

    private final Recorder recorder = new Recorder();

    private TimePanel panel() {
        return new TimePanel(Fontalizer.getFont(), ZoneOffset.UTC, Clock.fixed(NOW, ZoneOffset.UTC),
                ref -> s -> Optional.empty(), recorder);
    }

    /** Headless there is no focus owner, so key events go to the field's key listeners directly. */
    private static void press(DateTimeField f, int code) {
        KeyEvent e = new KeyEvent(f, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
        for (KeyListener l : f.getKeyListeners()) {
            l.keyPressed(e);
        }
    }

    private static void type(DateTimeField f, String chars) {
        for (char c : chars.toCharArray()) {
            KeyEvent e = new KeyEvent(f, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c);
            for (KeyListener l : f.getKeyListeners()) {
                l.keyTyped(e);
            }
        }
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void enterInAtGoesToAt() throws Exception {
        onEdt(() -> {
            TimePanel p = panel();
            p.open(Focus.AT, AT, FROM, null);

            press(p.atField(), KeyEvent.VK_ENTER);

            assertThat(recorder.calls).containsExactly("go " + AT);
            assertThat(p.enterButton().getText()).isEqualTo(TimePanel.GO_TEXT);
        });
    }

    @Test
    void enterInFromWithToNowSetsAnOpenRange() throws Exception {
        onEdt(() -> {
            TimePanel p = panel();
            p.open(Focus.FROM, AT, FROM, null);

            press(p.fromField(), KeyEvent.VK_ENTER);

            assertThat(recorder.calls).containsExactly("range " + FROM + " null");
            assertThat(p.enterButton().getText()).isEqualTo(TimePanel.SET_RANGE_TEXT);
        });
    }

    @Test
    void invalidRangeDisablesApplyAndIgnoresEnter() throws Exception {
        onEdt(() -> {
            TimePanel p = panel();
            p.open(Focus.FROM, AT, FROM, FROM.minusSeconds(60));

            press(p.fromField(), KeyEvent.VK_ENTER);
            press(p.toField(), KeyEvent.VK_ENTER);

            assertThat(recorder.calls).isEmpty();
            assertThat(p.toField().isInvalid()).isTrue();
            assertThat(p.enterButton().isEnabled()).isFalse();
            assertThat(p.reason()).isEqualTo(TimePanel.REASON_ORDER);

            p.toField().setValue(NOW.plusSeconds(60));
            assertThat(p.reason()).isEqualTo(TimePanel.REASON_FUTURE);
            assertThat(p.enterButton().isEnabled()).isFalse();
        });
    }

    @Test
    void chipClickSetsThePresetRange() throws Exception {
        onEdt(() -> {
            TimePanel p = panel();
            p.open(Focus.AT, AT, FROM, null);
            TimePanel.Chip chip = p.chips().stream().filter(c -> c.getText().equals("±5m")).findFirst()
                    .orElseThrow();

            chip.doClick(0);

            assertThat(recorder.calls).containsExactly("range " + AT.minusSeconds(300) + " " + AT.plusSeconds(300));
        });
    }

    @Test
    void freeTextDayWordParsesInEveryField() throws Exception {
        onEdt(() -> {
            Config config = Mockito.mock(Config.class);
            Mockito.when(config.getLocale()).thenReturn(Locale.ENGLISH);
            Mockito.when(config.getZoneId()).thenReturn(ZoneOffset.UTC);
            TimePanel p = new TimePanel(Fontalizer.getFont(), ZoneOffset.UTC, Clock.fixed(NOW, ZoneOffset.UTC),
                    ref -> new GotoHandler(config, at -> {}, () -> {}, ref)::parseTimestamp, recorder);
            p.open(Focus.AT, AT, FROM, null);

            // AT: "yesterday" is relative to the field's own date (2017-05-01)
            type(p.atField(), "yesterday");
            press(p.atField(), KeyEvent.VK_ENTER);
            assertThat(recorder.calls).containsExactly("go " + Instant.parse("2017-04-30T00:00:00Z"));

            // FROM: "today" at midnight of the field's date
            recorder.calls.clear();
            p.open(Focus.FROM, AT, FROM, null);
            type(p.fromField(), "today");
            press(p.fromField(), KeyEvent.VK_ENTER);
            assertThat(recorder.calls).containsExactly("range " + Instant.parse("2017-05-01T00:00:00Z") + " null");
        });
    }

    @Test
    void escCloses() throws Exception {
        onEdt(() -> {
            TimePanel p = panel();
            p.open(Focus.FROM, AT, FROM, null);

            press(p.fromField(), KeyEvent.VK_ESCAPE);

            assertThat(recorder.calls).containsExactly("close");
        });
    }
}
