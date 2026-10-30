package se.grynna.pager.ui.minimap;

/**
 * What the {@link Minimap} asks of the viewer when it is clicked or dragged. Called on the EDT, once per mouse
 * event that moves the viewport.
 */
@FunctionalInterface
public interface MinimapNavigator {

    /**
     * Scroll so the viewport is centred on {@code axisPosition}: a row for the log view, epoch nanoseconds (see
     * {@link MinimapModel#nanos}) for the Matches view. The position is already clamped into the minimap's span.
     * Scrolling only: it must not move the cursor or the selection.
     */
    void centerOn(long axisPosition);
}
