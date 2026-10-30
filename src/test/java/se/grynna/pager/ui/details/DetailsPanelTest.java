package se.grynna.pager.ui.details;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.ui.DateTimeFormatterManagerTestUtil;
import se.grynna.pager.ui.details.EventDetails.Field;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static se.grynna.pager.ui.details.DetailsFixtures.*;

class DetailsPanelTest {

    private DetailsPanel panel;

    @BeforeAll
    static void initFormatter() {
        DateTimeFormatterManagerTestUtil.initDTM();
    }

    @BeforeEach
    void setUp() {
        panel = new DetailsPanel();
    }

    private int rowHeight() {
        FontMetrics metrics = panel.getFontMetrics(panel.getFont());
        return metrics.getAscent() + metrics.getDescent();
    }

    /** A panel sized to show about {@code rows} visual lines. */
    private void size(int width, int rows) {
        panel.setSize(width, rows * rowHeight() + 12);
    }

    private BufferedImage paint() {
        BufferedImage image = new BufferedImage(Math.max(1, panel.getWidth()), Math.max(1, panel.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            panel.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private void mouse(int id, int x, int y, int button, boolean popup) {
        panel.dispatchEvent(new MouseEvent(panel, id, System.currentTimeMillis(), 0, x, y, 1, popup, button));
    }

    @Test
    void emptyPanelPaintsAHintAndSelectsNothing() {
        size(400, 10);

        paint();

        assertThat(panel.details()).isNull();
        assertThat(panel.selectedField()).isEmpty();
        assertThat(panel.selectedIndex()).isEqualTo(-1);
        assertThat(panel.selectedFieldBounds()).isEqualTo(new Rectangle());
        panel.moveSelection(1);
        panel.pageDown();
        panel.end();
        panel.home();
        assertThat(panel.selectedIndex()).isEqualTo(-1);
    }

    @Test
    void firstFieldIsSelectedWhenDetailsAreSet() {
        size(600, 20);

        panel.setDetails(ofFields("b", "1", "a", "2"), "row 1");

        assertThat(panel.selectedIndex()).isEqualTo(0);
        assertThat(panel.selectedField()).map(Field::name).contains("a");
    }

    @Test
    void newDetailsKeepTheSelectedFieldByName() {
        size(600, 20);
        panel.setDetails(ofFields("a", "1", "b", "2", "c", "3"), "row 1");
        panel.select(1);

        panel.setDetails(ofFields("a0", "0", "a1", "0", "a2", "0", "b", "9"), "row 2");

        assertThat(panel.selectedField()).map(Field::name).contains("b");
        assertThat(panel.selectedIndex()).isEqualTo(3);
    }

    @Test
    void newDetailsWithoutTheSelectedFieldKeepTheIndexClamped() {
        size(600, 20);
        panel.setDetails(manyFields(10), "row 1");
        panel.select(6);

        panel.setDetails(ofFields("a", "1", "b", "2", "c", "3"), "row 2");

        assertThat(panel.selectedIndex()).as("clamped to the last of 3 fields + _raw").isEqualTo(3);

        panel.setDetails(ofFields("a", "1", "b", "2", "c", "3", "d", "4", "e", "5", "f", "6", "g", "7"), "row 3");
        panel.select(4);
        panel.setDetails(ofFields("p", "1", "q", "2", "r", "3", "s", "4", "t", "5", "u", "6"), "row 4");

        assertThat(panel.selectedIndex()).as("same index when the name is gone").isEqualTo(4);
    }

    @Test
    void settingNullDetailsClearsTheSelection() {
        size(600, 20);
        panel.setDetails(manyFields(3), "row 1");

        panel.setDetails(null, null);

        assertThat(panel.details()).isNull();
        assertThat(panel.selectedIndex()).isEqualTo(-1);
        paint();
    }

    @Test
    void moveSelectionClampsAtBothEnds() {
        size(600, 20);
        panel.setDetails(manyFields(5), "row 1");
        int last = panel.details().fields().size() - 1;

        panel.moveSelection(-3);
        assertThat(panel.selectedIndex()).isEqualTo(0);
        panel.moveSelection(2);
        assertThat(panel.selectedIndex()).isEqualTo(2);
        panel.moveSelection(1000);
        assertThat(panel.selectedIndex()).isEqualTo(last);
        panel.moveSelection(1);
        assertThat(panel.selectedIndex()).isEqualTo(last);
        panel.select(-4);
        assertThat(panel.selectedIndex()).isEqualTo(0);
        panel.select(99);
        assertThat(panel.selectedIndex()).isEqualTo(last);
    }

    @Test
    void selectionListenerHearsChangesOnly() {
        size(600, 20);
        List<String> heard = new ArrayList<>();
        panel.setSelectionListener(field -> heard.add(field.name()));

        panel.setDetails(ofFields("a", "1", "b", "2"), "row 1");
        panel.moveSelection(1);
        panel.moveSelection(0);
        panel.moveSelection(-1);

        assertThat(heard).containsExactly("a", "b", "a");
    }

    @Test
    void pageDownPageUpHomeAndEndMoveBySeveralFields() {
        size(600, 12);
        panel.setDetails(manyFields(80), "row 1");
        int last = panel.details().fields().size() - 1;

        panel.pageDown();
        int afterPage = panel.selectedIndex();
        assertThat(afterPage).isGreaterThan(3);
        panel.pageDown();
        assertThat(panel.selectedIndex()).isGreaterThan(afterPage);
        panel.pageUp();
        assertThat(panel.selectedIndex()).isEqualTo(afterPage);

        panel.end();
        assertThat(panel.selectedIndex()).isEqualTo(last);
        panel.pageDown();
        assertThat(panel.selectedIndex()).isEqualTo(last);
        panel.home();
        assertThat(panel.selectedIndex()).isEqualTo(0);
        assertThat(panel.scrollOffset()).isEqualTo(0);
        panel.pageUp();
        assertThat(panel.selectedIndex()).isEqualTo(0);
    }

    @Test
    void selectionStaysVisibleAfterEndAndMoves() {
        size(600, 12);
        panel.setDetails(manyMapFields(80), "row 1");

        panel.end();

        assertThat(panel.selectedIndex()).isEqualTo(79);
        assertThat(panel.scrollOffset()).isGreaterThan(0);
        assertThat(visible(panel.selectedFieldBounds())).isTrue();
        panel.moveSelection(-1);
        assertThat(visible(panel.selectedFieldBounds())).isTrue();
        for (int i = 0; i < 40; i++) {
            panel.moveSelection(-1);
            assertThat(visible(panel.selectedFieldBounds())).as("after %d steps up", i).isTrue();
        }
        panel.pageDown();
        assertThat(visible(panel.selectedFieldBounds())).isTrue();
        panel.home();
        assertThat(visible(panel.selectedFieldBounds())).isTrue();
        assertThat(panel.scrollOffset()).isEqualTo(0);
    }

    @Test
    void endOnAFieldTallerThanThePanelShowsItsTop() {
        size(600, 12);
        panel.setDetails(manyFields(80), "row 1");

        panel.end();

        Rectangle bounds = panel.selectedFieldBounds();
        assertThat(panel.selectedField()).map(Field::name).contains("_raw");
        assertThat(bounds.y).isBetween(0, panel.getHeight() - 1);
        assertThat(bounds.height).isGreaterThan(panel.getHeight());
    }

    private boolean visible(Rectangle bounds) {
        return bounds.y >= 0 && bounds.y + bounds.height <= panel.getHeight() && bounds.height > 0;
    }

    @Test
    void clickSelectsTheFieldUnderTheMouse() {
        size(600, 80);
        panel.setDetails(manyFields(10), "row 1");
        panel.select(7);
        Rectangle seven = panel.selectedFieldBounds();
        panel.select(0);

        mouse(MouseEvent.MOUSE_PRESSED, 200, seven.y + seven.height / 2, MouseEvent.BUTTON1, false);

        assertThat(panel.selectedIndex()).isEqualTo(7);
    }

    @Test
    void clickOutsideTheFieldsKeepsTheSelection() {
        size(600, 80);
        panel.setDetails(manyFields(10), "row 1");
        panel.select(2);

        mouse(MouseEvent.MOUSE_PRESSED, 50, 2, MouseEvent.BUTTON1, false);
        mouse(MouseEvent.MOUSE_PRESSED, 50, panel.getHeight() - 1, MouseEvent.BUTTON1, false);

        assertThat(panel.selectedIndex()).isEqualTo(2);
    }

    @Test
    void rightClickSelectsTheFieldAndCallsTheMenuHandler() {
        size(600, 80);
        panel.setDetails(manyFields(10), "row 1");
        panel.select(5);
        Rectangle five = panel.selectedFieldBounds();
        panel.select(0);
        List<Object[]> calls = new ArrayList<>();
        panel.setFieldMenuHandler((field, point) -> calls.add(new Object[]{field, point}));
        int y = five.y + five.height / 2;

        mouse(MouseEvent.MOUSE_PRESSED, 120, y, MouseEvent.BUTTON3, true);

        assertThat(panel.selectedIndex()).isEqualTo(5);
        assertThat(calls).hasSize(1);
        assertThat(((Field) calls.get(0)[0]).name()).isEqualTo("f005");
        assertThat(calls.get(0)[1]).isEqualTo(new Point(120, y));
    }

    @Test
    void rightClickIsAlsoHandledOnReleaseWhereThePlatformTriggersThere() {
        size(600, 80);
        panel.setDetails(manyFields(4), "row 1");
        panel.select(2);
        Rectangle bounds = panel.selectedFieldBounds();
        panel.select(0);
        List<Field> calls = new ArrayList<>();
        panel.setFieldMenuHandler((field, point) -> calls.add(field));

        mouse(MouseEvent.MOUSE_RELEASED, 10, bounds.y + 1, MouseEvent.BUTTON3, true);
        mouse(MouseEvent.MOUSE_RELEASED, 10, bounds.y + 1, MouseEvent.BUTTON1, false);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).name()).isEqualTo("f002");
    }

    @Test
    void mouseWheelScrollsThreeLinesPerNotch() {
        size(600, 12);
        panel.setDetails(manyFields(80), "row 1");

        panel.dispatchEvent(new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 10, 10,
                0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));
        assertThat(panel.scrollOffset()).isEqualTo(3);
        panel.dispatchEvent(new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, 10, 10,
                0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -5));
        assertThat(panel.scrollOffset()).isEqualTo(0);
    }

    @Test
    void selectedFieldBoundsCoverTheFullWidthAndAllWrappedLines() {
        size(300, 60);
        String longValue = "word ".repeat(80).trim();
        panel.setDetails(ofFields("a", "short", "b", longValue), "row 1");
        panel.select(0);
        Rectangle single = panel.selectedFieldBounds();
        panel.select(1);

        Rectangle wrapped = panel.selectedFieldBounds();

        assertThat(single.width).isEqualTo(300);
        assertThat(single.height).isEqualTo(rowHeight());
        assertThat(wrapped.height).isGreaterThan(2 * rowHeight());
        assertThat(wrapped.y).isGreaterThan(single.y);
    }

    @Test
    void paintsAtAnySizeWithoutExceptions() {
        panel.setDetails(richDetails(), "match 3 of 212");
        for (Dimension size : List.of(new Dimension(0, 0), new Dimension(1, 1), new Dimension(80, 300),
                new Dimension(80, 10), new Dimension(300, 0), new Dimension(0, 300), new Dimension(400, 200),
                new Dimension(1400, 900))) {
            panel.setSize(size);
            panel.select(panel.details().fields().size() - 1);
            paint();
            panel.home();
            paint();
        }
    }

    @Test
    void paintsWhenTheDetailsAreMissingEverything() {
        size(500, 20);
        panel.setDetails(details(event(null, null, null, null)), "");
        paint();
        panel.setDetails(details(event("not json", Map.of("a", ""), "", Level.ERROR)), null);
        paint();
    }

    @Test
    void paintDrawsTheTintAndStripe() {
        size(400, 20);
        panel.setDetails(manyFields(3), "row 1");

        BufferedImage image = paint();

        assertThat(new Color(image.getRGB(1, 5))).isEqualTo(se.grynna.pager.utils.DarculaColors.BLUE_TEXT);
        assertThat(new Color(image.getRGB(image.getWidth() - 1, image.getHeight() - 1)))
                .isEqualTo(se.grynna.pager.utils.DarculaColors.BACKGROUND_DETAILS);
    }

    @Test
    void longUnbrokenValuesWrapByCharacters() {
        assertThat(DetailsPanel.wrap("x".repeat(25), 10)).containsExactly("x".repeat(10), "x".repeat(10), "xxxxx");
    }

    @Test
    void wrapBreaksAtSpacesKeepsNewlinesAndIndentation() {
        assertThat(DetailsPanel.wrap("aaa bbb ccc", 7)).containsExactly("aaa bbb", "ccc");
        assertThat(DetailsPanel.wrap("a\n\n  b", 10)).containsExactly("a", "", "  b");
        assertThat(DetailsPanel.wrap("line1\r\nline2", 20)).containsExactly("line1", "line2");
        assertThat(DetailsPanel.wrap("\tat x", 20)).containsExactly("    at x");
        assertThat(DetailsPanel.wrap("", 5)).containsExactly("");
        assertThat(DetailsPanel.wrap("abc", 0)).containsExactly("a", "b", "c");
    }

    @Test
    void veryLongValuesAndManyFieldsLayOutQuickly() {
        size(600, 30);
        panel.setDetails(manyFields(5000), "row 1");
        paint();
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            panel.moveSelection(1);
            paint();
        }
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms for 200 move+paint").isLessThan(5000);
        panel.setDetails(ofFields("big", "y".repeat(200_000)), "row 2");
        paint();
        assertThat(panel.selectedFieldBounds().height).isGreaterThan(0);
    }

    private static EventDetails richDetails() {
        String raw = """
                {"@timestamp":"2017-03-04T10:11:12.123456789Z","level":"ERROR","a_very_long_key_name_that_goes_on_and_on_and_on_and_beyond_the_cap":"v",\
                "message":"boom\\nsecond line","kubernetes":{"pod_name":"p-1","labels":{"app":"x"}},"tags":["a","b"],\
                "stack_trace":"java.lang.Boom\\n\\tat a.B.c(B.java:1)\\n\\tat d.E.f(E.java:2)"}""";
        Map<String, String> map = Map.of("_bkt", "b~1~X", "host", "h", "index", "i", "multi", "x\ny");
        return EventDetails.of(event(raw, map, "boom\nsecond line", Level.ERROR), name -> name.equals("message"),
                name -> name.equals("level") ? EventDetails.ScopeMark.NARROW : EventDetails.ScopeMark.EXCLUDE);
    }
}
