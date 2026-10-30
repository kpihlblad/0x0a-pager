package se.grynna.pager.ui.time;

import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.SearchBoundaries;
import se.grynna.pager.ui.Fontalizer;
import se.grynna.pager.ui.time.DateTimeField.Symbol;
import se.grynna.pager.ui.time.DateTimeSegments.Segment;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DateTimeFieldTest {

    private static final Instant T = Instant.parse("2017-05-01T12:03:14Z");
    private static final Instant NOW = Instant.parse("2017-05-03T08:00:00Z");

    private static DateTimeField field(java.util.function.Function<String, Optional<Instant>> parser) {
        DateTimeField f = new DateTimeField(Fontalizer.getFont(), ZoneOffset.UTC, parser);
        f.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        f.setSize(f.getPreferredSize());
        f.setValue(T);
        return f;
    }

    /** Headless there is no focus owner, so key events go to the field's key listeners directly. */
    private static KeyEvent key(DateTimeField f, KeyEvent e) {
        for (KeyListener l : f.getKeyListeners()) {
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                l.keyPressed(e);
            } else {
                l.keyTyped(e);
            }
        }
        return e;
    }

    private static KeyEvent press(DateTimeField f, int code) {
        return key(f, new KeyEvent(f, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED));
    }

    private static void type(DateTimeField f, String chars) {
        for (char c : chars.toCharArray()) {
            key(f, new KeyEvent(f, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c));
        }
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void arrowKeyStepsTheFocusedSegmentAndNotifiesOnce() throws Exception {
        onEdt(() -> {
            DateTimeField f = field(s -> Optional.empty());
            AtomicInteger changes = new AtomicInteger();
            f.addChangeListener(e -> changes.incrementAndGet());
            f.setFocusedSegment(Segment.MINUTE);

            press(f, KeyEvent.VK_UP);

            assertThat(f.getValue()).isEqualTo(Instant.parse("2017-05-01T12:04:14Z"));
            assertThat(changes).hasValue(1);
        });
    }

    @Test
    void wheelUpStepsTheSegmentUnderThePointerOncePerNotch() throws Exception {
        onEdt(() -> {
            DateTimeField f = field(s -> Optional.empty());
            int x = f.segmentCenterX(Segment.DAY);
            int y = f.textCenterY();

            for (int i = 0; i < 4; i++) {
                f.dispatchEvent(new MouseWheelEvent(f, MouseEvent.MOUSE_WHEEL, 0, 0, x, y, x, y, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1, -0.5));
            }

            assertThat(f.getValue()).isEqualTo(Instant.parse("2017-05-03T12:03:14Z"));
        });
    }

    @Test
    void aLetterEntersFreeTextAndEnterParsesAndCommits() throws Exception {
        onEdt(() -> {
            AtomicReference<String> parsed = new AtomicReference<>();
            DateTimeField f = field(s -> {
                parsed.set(s);
                return s.equals("yesterday") ? Optional.of(NOW.minusSeconds(86_400)) : Optional.empty();
            });
            AtomicInteger commits = new AtomicInteger();
            f.setOnCommit(commits::incrementAndGet);

            type(f, "yesterday");
            assertThat(f.isFreeText()).isTrue();
            press(f, KeyEvent.VK_ENTER);

            assertThat(parsed).hasValue("yesterday");
            assertThat(f.isFreeText()).isFalse();
            assertThat(f.getValue()).isEqualTo(NOW.minusSeconds(86_400));
            assertThat(commits).hasValue(1);
        });
    }

    @Test
    void unparsableFreeTextStaysAndEscRevertsConsumingTheKey() throws Exception {
        onEdt(() -> {
            DateTimeField f = field(s -> Optional.empty());

            type(f, "+bogus");
            press(f, KeyEvent.VK_ENTER);
            assertThat(f.isFreeText()).isTrue();
            assertThat(f.getFreeText()).isEqualTo("+bogus");

            KeyEvent esc = press(f, KeyEvent.VK_ESCAPE);
            assertThat(esc.isConsumed()).isTrue();
            assertThat(f.isFreeText()).isFalse();
            assertThat(f.getValue()).isEqualTo(T);

            KeyEvent esc2 = press(f, KeyEvent.VK_ESCAPE);
            assertThat(esc2.isConsumed()).isFalse();
        });
    }

    @Test
    void backspaceMovesTheFocusedSegmentLeftWhenNothingIsTyped() throws Exception {
        onEdt(() -> {
            DateTimeField f = field(s -> Optional.empty());
            f.setFocusedSegment(Segment.MINUTE);

            press(f, KeyEvent.VK_BACK_SPACE);
            assertThat(f.getFocusedSegment()).isEqualTo(Segment.HOUR);
            assertThat(f.getValue()).isEqualTo(T);

            press(f, KeyEvent.VK_BACK_SPACE);
            assertThat(f.getFocusedSegment()).isEqualTo(Segment.DAY);
        });
    }

    @Test
    void symbolicNowBecomesConcreteOnAStep() throws Exception {
        onEdt(() -> {
            DateTimeField f = field(s -> Optional.empty());
            f.setSymbol(Symbol.NOW);
            assertThat(f.getValue()).isNull();
            assertThat(f.resolve(NOW)).isEqualTo(NOW);
            f.setFocusedSegment(Segment.HOUR);

            press(f, KeyEvent.VK_UP);

            assertThat(f.getSymbol()).isEqualTo(Symbol.NONE);
            assertThat(f.getValue()).isEqualTo(NOW.plusSeconds(3600));
        });
    }

    @Test
    void theStartSymbolIsNamedByTheMinimumYearAndTheLegacyWord() {
        assertThat(Symbol.START.word()).isEqualTo("1971")
                .isEqualTo(SearchBoundaries.MIN_BOUNDARY_YEAR);
        assertThat(Symbol.START.namedBy("1971")).isTrue();
        assertThat(Symbol.START.namedBy("START")).isTrue();
        assertThat(Symbol.START.namedBy("now")).isFalse();
        assertThat(Symbol.NOW.namedBy("1971")).isFalse();
    }

    @Test
    void freeTextMinimumYearOrStartNamesTheStartSymbol() throws Exception {
        onEdt(() -> {
            DateTimeField f = field(s -> Optional.empty());
            f.setAllowedSymbol(Symbol.START);

            // Digits do not enter free text from segment mode, so seed it with a letter and erase it.
            type(f, "x");
            press(f, KeyEvent.VK_BACK_SPACE);
            type(f, "1971");
            press(f, KeyEvent.VK_ENTER);

            assertThat(f.getSymbol()).isEqualTo(Symbol.START);
            assertThat(f.resolve(NOW)).isEqualTo(SearchBoundaries.MIN_MINIMUM_BOUNDARY);

            f.setValue(T);
            type(f, "start");
            press(f, KeyEvent.VK_ENTER);

            assertThat(f.getSymbol()).isEqualTo(Symbol.START);
        });
    }
}
