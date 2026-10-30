package se.grynna.pager.ui.minimap;

import java.util.Arrays;
import java.util.OptionalLong;

/**
 * The pointer logic of the minimap, without Swing: maps mouse rows to the axis position the viewport should be
 * centred on. One instance per minimap, one gesture (press, drags, release) at a time.
 * <p>
 * A press inside the visible range grabs it: the offset between the pointer and the range's centre is kept, so
 * the range does not move until the pointer does. A press outside it recentres on the pointer at once (a click)
 * and goes on dragging from the centre, with no offset. While dragging, the range is kept inside the span
 * (clamped at the ends), using the length it had at the press. Every call takes the span as it is now: the
 * loaded segment and the Matches window can grow during a drag.
 * <p>
 * Snap: every target centre (a click or a drag step) moves onto the nearest match position within
 * {@link #SNAP_PX} pixels, so a highlight is easy to hit ({@link Minimap} passes the bookmarks as match
 * positions too). Ties go to the earlier position. Without matches
 * nothing snaps.
 */
public final class MinimapDrag {

    /** How close, in pixels, a target centre must be to a match position to snap onto it. */
    public static final int SNAP_PX = 3;

    private boolean active;
    private long grabOffset;
    private long visibleLength;
    /** Match positions, sorted ascending; may contain duplicates. */
    private long[] matches = new long[0];

    /**
     * The match positions a target centre snaps onto. The array is copied and sorted here; an empty array (the
     * default) turns snapping off.
     *
     * @param positions the match positions, in any order, may contain duplicates; null for none
     */
    public void setMatches(long[] positions) {
        matches = positions == null ? new long[0] : positions.clone();
        Arrays.sort(matches);
    }

    /**
     * Start a gesture at pixel row {@code y}.
     *
     * @param span     the axis the strip covers now
     * @param heightPx strip height in pixels
     * @param visible  the visible range, or null for none
     * @return the position to centre on, or empty when the press grabbed the visible range and nothing moves yet
     */
    public OptionalLong press(MinimapModel.Span span, int heightPx, MinimapModel.Span visible, int y) {
        active = true;
        visibleLength = visible == null ? 0 : visible.length();
        long position = MinimapModel.positionAt(span, heightPx, y);
        if (visible != null) {
            MinimapModel.Band pixels = MinimapModel.visiblePixels(span, heightPx, visible);
            if (y >= pixels.top() && y < pixels.bottom()) {
                grabOffset = position - centreOf(visible);
                return OptionalLong.empty();
            }
        }
        grabOffset = 0;
        return OptionalLong.of(target(span, heightPx, position));
    }

    /** The pointer moved to pixel row {@code y}; empty when no gesture is on. */
    public OptionalLong drag(MinimapModel.Span span, int heightPx, int y) {
        if (!active) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(target(span, heightPx, MinimapModel.positionAt(span, heightPx, y) - grabOffset));
    }

    /** The pointer was released. */
    public void release() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    /** The centre of a range: its start plus half its length. */
    static long centreOf(MinimapModel.Span range) {
        return range.start() + range.length() / 2;
    }

    /**
     * {@code centre} moved so a range of the press-time length stays inside {@code span}. A span shorter than the
     * range gives the span's start-aligned range.
     */
    private long centreFor(MinimapModel.Span span, long centre) {
        long length = Math.max(visibleLength, 1);
        long half = visibleLength / 2;
        long lastStart = Math.max(span.start(), span.endExclusive() - length);
        return Math.clamp(centre - half, span.start(), lastStart) + half;
    }

    /** The centre a press or drag step produces: snapped to a match, then kept inside the span. */
    private long target(MinimapModel.Span span, int heightPx, long centre) {
        return centreFor(span, snapped(span, heightPx, centreFor(span, centre)));
    }

    /**
     * The nearest match inside {@code span} within {@link #SNAP_PX} pixels of {@code centre}, or {@code centre}
     * when there is none. The tolerance is in axis units, {@code SNAP_PX * length / heightPx}, which is the same
     * as the pixel distance drawn.
     */
    private long snapped(MinimapModel.Span span, int heightPx, long centre) {
        if (matches.length == 0 || heightPx < 1) {
            return centre;
        }
        double tolerance = (double) SNAP_PX * span.length() / heightPx;
        int next = lowerBound(centre);
        long best = centre;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i : new int[]{next - 1, next}) {
            if (i < 0 || i >= matches.length || !span.contains(matches[i])) {
                continue;
            }
            double distance = Math.abs((double) matches[i] - centre);
            if (distance <= tolerance && distance < bestDistance) {
                best = matches[i];
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The index of the first match at or after {@code position}, or {@code matches.length}. */
    private int lowerBound(long position) {
        int low = 0;
        int high = matches.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (matches[mid] < position) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
