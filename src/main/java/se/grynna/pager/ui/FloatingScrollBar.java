package se.grynna.pager.ui;

import lombok.extern.slf4j.Slf4j;
import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

@Slf4j
public class FloatingScrollBar {

    private static final Color THUMB_COLOR = DarculaColors.GREY_DARK_TRANSLUCENT;

    // private final LogViewPort viewPort;
    private final CursorManager cursorManager;

    private Rectangle renderArea;
    private int columns;
    private int charWidth;

    private int scrollbarHeight = 8;
    private int minThumbWidth = 20;

    private int maxLineLength = 0; // max width of the content in characters
    private int contentWidth; // max width of the content in pixels

    private boolean isDragging = false;
    private int dragStartX;
    private int thumbStartPosition;
    private int scrollPosition;

    public FloatingScrollBar(CursorManager cursorManager) {
        this.cursorManager = cursorManager;
    }

    public void setFontMetrics(FontMetrics fontMetrics) {
        // Use 'M' for consistent monospace width measurement
        this.charWidth = fontMetrics.charWidth('M');
        this.scrollbarHeight = (int) (fontMetrics.getHeight() * .55);
        this.minThumbWidth = 10 * charWidth;
    }

    public void setRenderArea(Rectangle renderArea) {
        this.renderArea = renderArea;
        this.columns = renderArea.width / charWidth;
    }

    public void setMaxLineLength(int maxLineLength) {
        log.trace("Setting max line length to {}", maxLineLength);
        this.maxLineLength = maxLineLength;
        this.contentWidth = maxLineLength * charWidth;
    }

    public void installListener(JComponent component) {

        // Add mouse listeners for scrollbar interaction
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (isScrollbarVisible() && isPointInScrollArea(e.getY())) {
                    int thumbX = getThumbPosition();
                    int thumbWidth = getThumbWidth();
                    if (e.getX() >= thumbX && e.getX() <= thumbX + thumbWidth) {
                        isDragging = true;
                        dragStartX = e.getX();
                        thumbStartPosition = 0;
                        // Update the horizontal offset in the log view port
                        updateHorizontalOffset();
                        e.consume();
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (isDragging) {
                    e.consume();
                }
                isDragging = false;
            }
        });

        component.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (isDragging) {
                    int deltaX = e.getX() - dragStartX;
                    int maxScroll = contentWidth - renderArea.width;

                    float scrollRange = renderArea.width - getThumbWidth();
                    scrollPosition = thumbStartPosition + (int) (deltaX * maxScroll / scrollRange);
                    scrollPosition = Math.max(0, Math.min(scrollPosition, maxScroll));

                    // Update the horizontal offset in the log view port
                    updateHorizontalOffset();
                    log.debug("Dragging scrollbar at {}", dragStartX);
                    e.consume();
                }
            }
        });
    }

    private void updateHorizontalOffset() {
        var scrollPositionInColumns = (int) ((float) scrollPosition / contentWidth * maxLineLength);
        cursorManager.setHorizontalOffset(scrollPositionInColumns);
    }

    boolean isScrollbarVisible() {
        return maxLineLength > columns;
    }

    /** Pixels the horizontal thumb takes at the bottom of the view; 0 while it is not shown. */
    int occupiedHeight() {
        return isScrollbarVisible() ? scrollbarHeight : 0;
    }

    private boolean isPointInScrollArea(int y) {
        return y >= renderArea.y + renderArea.height - scrollbarHeight;
    }

    private int getThumbWidth() {
        if (!isScrollbarVisible()) return 0;
        float visibleRatio = (float) renderArea.width / contentWidth;
        int thumbWidth = (int) (renderArea.width * visibleRatio);
        return Math.max(thumbWidth, minThumbWidth);
    }

    private int getThumbPosition() {
        if (!isScrollbarVisible()) return 0;
        int maxScroll = contentWidth - renderArea.width;
        float scrollRange = renderArea.width - getThumbWidth();
        return (int) ((scrollPosition / (float) maxScroll) * scrollRange);
    }

    protected void paintScrollBar(Graphics2D g2d) {

        // calculate scroll position

        scrollPosition = Math.min(scrollPosition, Math.max(0, contentWidth - renderArea.width));

        if (isScrollbarVisible()) {

            // Draw scrollbar thumb
            g2d.setColor(THUMB_COLOR);
            int thumbWidth = getThumbWidth();
            int thumbX = getThumbPosition();
            g2d.fillRoundRect(thumbX, renderArea.height - scrollbarHeight, thumbWidth, scrollbarHeight,
                    scrollbarHeight / 2, scrollbarHeight / 2);
        }
    }
}
