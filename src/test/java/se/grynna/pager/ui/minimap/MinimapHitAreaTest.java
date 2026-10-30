package se.grynna.pager.ui.minimap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The minimap as a component, no window: which pixels take the mouse and what synthetic events do. */
class MinimapHitAreaTest {

    private static final int STRIP = 6;
    private static final int H = 300;
    private static final MinimapModel.Span ROWS = new MinimapModel.Span(0, 3000);

    private Minimap minimap;
    private final List<Long> centred = new ArrayList<>();

    @BeforeEach
    void setUp() {
        minimap = new Minimap();
        minimap.setSize(Minimap.GLOW_REACH + STRIP, H);
        minimap.setNavigator(centred::add);
        minimap.setData(ROWS, new long[0], new long[0], new long[0], OptionalLong.empty(), false, false);
        minimap.setVisibleRange(new MinimapModel.Span(0, 100));
    }

    @Test
    void theStripAndThreePixelsLeftOfItTakeTheMouse() {
        int stripLeft = Minimap.GLOW_REACH;
        assertThat(minimap.contains(stripLeft, 10)).isTrue();
        assertThat(minimap.contains(minimap.getWidth() - 1, H - 1)).isTrue();
        assertThat(minimap.contains(stripLeft - Minimap.HIT_MARGIN, 10)).isTrue();
        assertThat(Minimap.HIT_MARGIN).isEqualTo(3);
    }

    @Test
    void theRestOfTheGutterPassesThrough() {
        int stripLeft = Minimap.GLOW_REACH;
        assertThat(minimap.contains(stripLeft - Minimap.HIT_MARGIN - 1, 10)).isFalse();
        assertThat(minimap.contains(0, 10)).isFalse();
    }

    @Test
    void outsideTheBoundsPassesThrough() {
        assertThat(minimap.contains(Minimap.GLOW_REACH + 1, -1)).isFalse();
        assertThat(minimap.contains(Minimap.GLOW_REACH + 1, H)).isFalse();
        assertThat(minimap.contains(minimap.getWidth(), 10)).isFalse();
    }

    @Test
    void withoutDataEverythingPassesThrough() {
        minimap.clear();
        assertThat(minimap.contains(Minimap.GLOW_REACH + 1, 10)).isFalse();
    }

    @Test
    void withoutANavigatorEverythingPassesThrough() {
        minimap.setNavigator(null);
        assertThat(minimap.contains(Minimap.GLOW_REACH + 1, 10)).isFalse();
    }

    @Test
    void hiddenEverythingPassesThrough() {
        minimap.setVisible(false);
        assertThat(minimap.contains(Minimap.GLOW_REACH + 1, 10)).isFalse();
    }

    @Test
    void aNarrowComponentWithoutAGutterTakesTheMouseAcrossItsWidth() {
        minimap.setSize(STRIP, H);
        assertThat(minimap.contains(0, 10)).isTrue();
        assertThat(minimap.contains(STRIP - 1, 10)).isTrue();
    }

    @Test
    void theMinimapTakesNoKeyboardFocus() {
        assertThat(minimap.isFocusable()).isFalse();
    }

    @Test
    void aPressOutsideTheRangeCentresAndDragsFollow() {
        press(200);
        assertThat(centred).containsExactly(2000L);

        drag(250);
        release();
        drag(10);

        assertThat(centred).as("the drag after the release is ignored").containsExactly(2000L, 2500L);
    }

    @Test
    void bookmarksAttractToo() {
        // 1 px = 10 rows: the bookmark at row 2020 is 2 px from the press at y = 200 (row 2000).
        minimap.setData(ROWS, new long[0], new long[]{2020}, new long[0], OptionalLong.empty(), false, false);
        press(200);
        assertThat(centred).containsExactly(2020L);
    }

    @Test
    void theNearestOfAMatchAndABookmarkWins() {
        minimap.setData(ROWS, new long[]{2025}, new long[]{1990}, new long[0], OptionalLong.empty(), false, false);
        press(200);
        assertThat(centred).containsExactly(1990L);
    }

    @Test
    void aPressInsideTheRangeMovesNothingUntilTheDrag() {
        press(1);
        assertThat(centred).isEmpty();

        drag(31);

        assertThat(centred).hasSize(1);
        assertThat(centred.getFirst()).as("moved by 30 px = 300 rows from the centre of the range").isEqualTo(350L);
    }

    @Test
    void onlyTheLeftButtonStartsAGesture() {
        minimap.dispatchEvent(new MouseEvent(minimap, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON3_DOWN_MASK,
                Minimap.GLOW_REACH + 1, 200, 1, true, MouseEvent.BUTTON3));
        drag(250);

        assertThat(centred).isEmpty();
    }

    @Test
    void theMinimapConsumesTheEventsItHandles() {
        MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 200);
        minimap.dispatchEvent(press);
        assertThat(press.isConsumed()).isTrue();
    }

    @Test
    void theWheelListenerOfTheOwnerReceivesTheWheel() {
        AtomicInteger rotation = new AtomicInteger();
        minimap.addMouseWheelListener(e -> rotation.addAndGet(e.getWheelRotation()));

        minimap.dispatchEvent(new MouseWheelEvent(minimap, MouseEvent.MOUSE_WHEEL, 0, 0, Minimap.GLOW_REACH + 1, 10,
                0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 2));

        assertThat(rotation).hasValue(2);
        assertThat(centred).isEmpty();
    }

    private void press(int y) {
        minimap.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, y));
    }

    private void drag(int y) {
        minimap.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, y));
    }

    private void release() {
        minimap.dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, 0));
    }

    private MouseEvent mouse(int id, int y) {
        int modifiers = id == MouseEvent.MOUSE_RELEASED ? 0 : MouseEvent.BUTTON1_DOWN_MASK;
        return new MouseEvent(minimap, id, 0, modifiers, Minimap.GLOW_REACH + 1, y, 1, false, MouseEvent.BUTTON1);
    }
}
