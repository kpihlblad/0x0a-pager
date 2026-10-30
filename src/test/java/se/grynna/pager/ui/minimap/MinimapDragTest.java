package se.grynna.pager.ui.minimap;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pure part of click and drag: the pixel-to-position mapping and the grab offset. */
class MinimapDragTest {

    private static final int H = 400;
    /** 1000 signed rows, the log numbered around its jump target. */
    private static final MinimapModel.Span ROWS = new MinimapModel.Span(-500, 500);

    // === positionAt ===

    @Test
    void positionAtMapsTopMiddleAndBottom() {
        assertThat(MinimapModel.positionAt(ROWS, H, 0)).isEqualTo(-500);
        assertThat(MinimapModel.positionAt(ROWS, H, H / 2)).isEqualTo(0);
        assertThat(MinimapModel.positionAt(ROWS, H, 3 * H / 4)).isEqualTo(250);
        assertThat(MinimapModel.positionAt(ROWS, H, H - 1)).isEqualTo(498);
    }

    @Test
    void positionAtClampsIntoTheSpan() {
        assertThat(MinimapModel.positionAt(ROWS, H, -50)).isEqualTo(-500);
        assertThat(MinimapModel.positionAt(ROWS, H, H)).as("the bottom edge is the last unit").isEqualTo(499);
        assertThat(MinimapModel.positionAt(ROWS, H, H + 50)).isEqualTo(499);
    }

    @Test
    void positionAtIsTheInverseOfTheRowOfAPosition() {
        for (int y = 0; y < H; y += 7) {
            long position = MinimapModel.positionAt(ROWS, H, y);
            MinimapModel model = MinimapModel.build(ROWS, H, 3, null, null, null, OptionalLong.empty(),
                    new MinimapModel.Span(position, position + 1));
            assertThat(model.visibleFromY()).as("pixel %d", y).isEqualTo(y);
        }
    }

    @Test
    void positionAtOnATimeSpan() {
        Instant from = Instant.parse("2017-03-30T00:00:00Z");
        Instant to = Instant.parse("2017-03-31T00:00:00Z");
        MinimapModel.Span span = MinimapModel.Span.ofInstants(from, to);
        assertThat(MinimapModel.instantOf(MinimapModel.positionAt(span, H, 0))).isEqualTo(from);
        assertThat(MinimapModel.instantOf(MinimapModel.positionAt(span, H, H / 2)))
                .isEqualTo(Instant.parse("2017-03-30T12:00:00Z"));
        assertThat(MinimapModel.instantOf(MinimapModel.positionAt(span, H, H))).isEqualTo(to.minusNanos(1));
    }

    @Test
    void instantOfInvertsNanosAlsoBeforeTheEpoch() {
        Instant instant = Instant.parse("1969-12-31T23:59:59.123456789Z");
        assertThat(MinimapModel.instantOf(MinimapModel.nanos(instant))).isEqualTo(instant);
        Instant later = Instant.parse("2017-03-30T10:00:00.000000001Z");
        assertThat(MinimapModel.instantOf(MinimapModel.nanos(later))).isEqualTo(later);
    }

    @Test
    void positionAtOfAnEmptySpanIsItsStart() {
        assertThat(MinimapModel.positionAt(new MinimapModel.Span(7, 7), H, 100)).isEqualTo(7);
        assertThatThrownBy(() -> MinimapModel.positionAt(ROWS, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // === press and drag ===

    @Test
    void aPressOutsideTheVisibleRangeCentresOnThePointer() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span visible = new MinimapModel.Span(-500, -450);

        OptionalLong target = drag.press(ROWS, H, visible, 3 * H / 4);

        assertThat(target).hasValue(250);
        assertThat(drag.isActive()).isTrue();
    }

    @Test
    void aPressInsideTheVisibleRangeGrabsItAndDoesNotMove() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span visible = new MinimapModel.Span(0, 200);
        MinimapModel.Band pixels = MinimapModel.visiblePixels(ROWS, H, visible);

        OptionalLong target = drag.press(ROWS, H, visible, pixels.top() + 3);

        assertThat(target).isEmpty();
        assertThat(drag.isActive()).isTrue();
    }

    @Test
    void draggingAGrabbedRangeKeepsTheGrabOffset() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span visible = new MinimapModel.Span(0, 200);
        int grabY = MinimapModel.visiblePixels(ROWS, H, visible).top() + 2;
        long grabbed = MinimapModel.positionAt(ROWS, H, grabY);
        long offset = grabbed - 100;
        drag.press(ROWS, H, visible, grabY);

        long moved = drag.drag(ROWS, H, grabY + 40).getAsLong();

        assertThat(moved).isEqualTo(MinimapModel.positionAt(ROWS, H, grabY + 40) - offset);
        assertThat(moved - 100).as("moved by the pointer's distance, not recentred")
                .isEqualTo(MinimapModel.positionAt(ROWS, H, grabY + 40) - grabbed);
    }

    @Test
    void draggingAfterAPressOutsideFollowsThePointerFromTheCentre() {
        MinimapDrag drag = new MinimapDrag();
        drag.press(ROWS, H, new MinimapModel.Span(-500, -450), H / 2);

        assertThat(drag.drag(ROWS, H, H / 2 + 40)).hasValue(MinimapModel.positionAt(ROWS, H, H / 2 + 40));
    }

    @Test
    void theRangeIsClampedInsideTheSpanAtBothEnds() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span visible = new MinimapModel.Span(0, 100);
        drag.press(ROWS, H, visible, 0);

        assertThat(drag.drag(ROWS, H, -30)).as("range start at the span start").hasValue(-500 + 50);
        assertThat(drag.drag(ROWS, H, H + 30)).as("range end at the span end").hasValue(500 - 50);
        assertThat(drag.drag(ROWS, H, H / 2)).hasValue(0);
    }

    @Test
    void aClickAtTheEndsKeepsTheWholePageInside() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span visible = new MinimapModel.Span(0, 100);

        assertThat(drag.press(ROWS, H, visible, 0)).hasValue(-450);
        drag.release();
        assertThat(drag.press(ROWS, H, visible, H - 1)).hasValue(450);
    }

    @Test
    void aSpanShorterThanTheRangeGivesTheStartAlignedRange() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span span = new MinimapModel.Span(-6, 6);
        MinimapModel.Span visible = new MinimapModel.Span(-6, 44);

        drag.press(span, H, visible, H / 2);

        assertThat(drag.drag(span, H, H / 2)).hasValue(-6 + 25);
    }

    @Test
    void withoutAVisibleRangeAPressIsAClickOnThePosition() {
        MinimapDrag drag = new MinimapDrag();

        assertThat(drag.press(ROWS, H, null, H / 2)).hasValue(0);
        assertThat(drag.press(ROWS, H, null, H)).as("clamped to the last unit").hasValue(499);
    }

    @Test
    void eachEventUsesTheSpanAsItIsNow() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span visible = new MinimapModel.Span(-500, -450);
        drag.press(ROWS, H, visible, H / 2);
        MinimapModel.Span grown = new MinimapModel.Span(-500, 1500);

        assertThat(drag.drag(grown, H, H / 2)).hasValue(500);
    }

    @Test
    void dragWithoutAGestureAndAfterReleaseDoesNothing() {
        MinimapDrag drag = new MinimapDrag();
        assertThat(drag.drag(ROWS, H, 10)).isEmpty();

        drag.press(ROWS, H, new MinimapModel.Span(-500, -450), 100);
        drag.release();

        assertThat(drag.isActive()).isFalse();
        assertThat(drag.drag(ROWS, H, 10)).isEmpty();
    }

    // === snap to matches ===

    /** At H = 400 over 1000 rows, a pixel is 2.5 rows; the snap tolerance is 3 px = 7.5 rows. */
    @Test
    void aClickSnapsToAMatchTwoPixelsAway() {
        MinimapDrag drag = new MinimapDrag();
        drag.setMatches(new long[]{5});

        assertThat(drag.press(ROWS, H, null, H / 2)).as("the click is at row 0, the match at 5").hasValue(5);
    }

    @Test
    void aClickDoesNotSnapToAMatchFourPixelsAway() {
        MinimapDrag drag = new MinimapDrag();
        drag.setMatches(new long[]{10});

        assertThat(drag.press(ROWS, H, null, H / 2)).hasValue(0);
    }

    @Test
    void aClickSnapsToTheNearestOfTwoMatches() {
        MinimapDrag drag = new MinimapDrag();
        drag.setMatches(new long[]{6, -4});

        assertThat(drag.press(ROWS, H, null, H / 2)).hasValue(-4);
    }

    @Test
    void aTieSnapsToTheEarlierMatch() {
        MinimapDrag drag = new MinimapDrag();
        drag.setMatches(new long[]{5, -5});

        assertThat(drag.press(ROWS, H, null, H / 2)).hasValue(-5);
    }

    @Test
    void duplicatesAndUnsortedMatchesAreFine() {
        MinimapDrag drag = new MinimapDrag();
        drag.setMatches(new long[]{400, 3, 3, -200, 2});

        assertThat(drag.press(ROWS, H, null, H / 2)).hasValue(2);
        assertThat(drag.drag(ROWS, H, 3 * H / 4)).as("row 250 is out of reach of 400 and -200").hasValue(250);
    }

    @Test
    void aSignedRowSpanSnapsLikeAPositiveOne() {
        MinimapDrag drag = new MinimapDrag();
        drag.setMatches(new long[]{-498, -300});

        assertThat(drag.press(ROWS, H, null, 0)).as("row -500 is 2 rows from -498").hasValue(-498);
    }

    @Test
    void aNanosSpanSnapsWithTheSameTolerance() {
        MinimapModel.Span day = MinimapModel.Span.ofInstants(Instant.parse("2017-03-30T00:00:00Z"),
                Instant.parse("2017-03-31T00:00:00Z"));
        long noon = MinimapModel.nanos(Instant.parse("2017-03-30T12:00:00Z"));
        long twoPixels = 2L * day.length() / H;
        long fourPixels = 4L * day.length() / H;
        MinimapDrag drag = new MinimapDrag();

        drag.setMatches(new long[]{noon + twoPixels});
        assertThat(drag.press(day, H, null, H / 2)).hasValue(noon + twoPixels);

        drag.setMatches(new long[]{noon + fourPixels});
        assertThat(drag.press(day, H, null, H / 2)).hasValue(noon);
    }

    @Test
    void aDragStepWithAGrabOffsetSnapsTheRangeCentre() {
        MinimapDrag drag = new MinimapDrag();
        MinimapModel.Span visible = new MinimapModel.Span(0, 200);
        int grabY = MinimapModel.visiblePixels(ROWS, H, visible).top() + 2;
        long offset = MinimapModel.positionAt(ROWS, H, grabY) - 100;
        drag.press(ROWS, H, visible, grabY);
        long unsnapped = MinimapModel.positionAt(ROWS, H, grabY + 40) - offset;
        drag.setMatches(new long[]{unsnapped + 2});

        assertThat(drag.drag(ROWS, H, grabY + 40)).hasValue(unsnapped + 2);
    }

    @Test
    void withoutMatchesNothingSnaps() {
        MinimapDrag drag = new MinimapDrag();
        drag.setMatches(null);
        assertThat(drag.press(ROWS, H, null, H / 2)).hasValue(0);

        drag.setMatches(new long[0]);
        assertThat(drag.press(ROWS, H, null, H / 2)).hasValue(0);
    }

    @Test
    void aTimeSpanIsClampedThroughTheSameRules() {
        MinimapModel.Span span = MinimapModel.Span.ofInstants(Instant.parse("2017-03-30T00:00:00Z"),
                Instant.parse("2017-03-31T00:00:00Z"));
        MinimapModel.Span visible = MinimapModel.Span.ofInstants(Instant.parse("2017-03-30T00:00:00Z"),
                Instant.parse("2017-03-30T02:00:00Z"));
        MinimapDrag drag = new MinimapDrag();

        long centre = drag.press(span, H, visible, H - 1).getAsLong();

        assertThat(MinimapModel.instantOf(centre)).isEqualTo(Instant.parse("2017-03-30T23:00:00Z"));
    }
}
