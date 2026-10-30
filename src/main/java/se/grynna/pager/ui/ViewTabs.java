package se.grynna.pager.ui;

import se.grynna.pager.utils.DarculaColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The view tabs at the left of the status row: {@code LOG │ MATCHES 212 │ BOOKMARKS 3 │ DETAILS}. A painted strip,
 * no buttons: each tab is its name in a bold, smaller monospace and an optional dim count. The active tab (the
 * view shown, or DETAILS while the inline event details are open over it) is filled in its colour; the
 * others are dim text on the row's own background (the component is transparent), brighter under the mouse.
 * Clicks go to the {@linkplain #setOnSelect selection callback}; the strip never takes keyboard focus, so keys stay
 * in the log. Tooltips name the action and its key, taken from the {@link KeyMap} when they are asked for.
 * {@link #flash(Tab)} draws attention to a tab for half a second ({@link AttentionFlash}): its fill pops in the
 * tab's accent colour with a glare around it, then fades. EDT only.
 */
final class ViewTabs extends JComponent {

    /** The tabs, left to right. */
    enum Tab {
        LOG, MATCHES, BOOKMARKS, DETAILS
    }

    /** Horizontal padding inside a tab, either side of its text. */
    private static final int PAD_X = 9;
    /** Width of the separator between two tabs. */
    private static final int SEPARATOR = 1;
    /** Alpha of the dim count next to the name, and of the Matches tab while there is no search. */
    private static final int COUNT_ALPHA = 170;
    private static final int NO_SEARCH_ALPHA = 130;
    private static final int SEPARATOR_ALPHA = 110;
    private static final int SEPARATOR_INSET = 3;
    /** A flash fills the tab with its accent up to this alpha, and glows this far around it. */
    private static final float FLASH_FILL_ALPHA = 0.85f;
    static final int FLASH_REACH = 6;
    private static final float FLASH_GLOW_PEAK = 0.55f;
    private static final int MAX_CACHED_GLOWS = 16;

    private record GlowKey(int width, int height, int rgb) {}

    private final Font nameFont;
    private final Font countFont;
    private final int rowHeight;

    private Tab active = Tab.LOG;
    private Tab hovered;
    private String matchCount;
    private int bookmarkCount;
    private Consumer<Tab> onSelect = tab -> {};
    private final LongSupplier clockMillis;
    private final Map<Tab, AttentionFlash> flashes = new EnumMap<>(Tab.class);
    private final Map<GlowKey, BufferedImage> glows = new HashMap<>();

    /** @param font the status row font; the tab text is derived from it, and the height follows it */
    ViewTabs(Font font) {
        this(font, () -> System.nanoTime() / 1_000_000);
    }

    /** As {@link #ViewTabs(Font)}, with the clock the flashes run on (milliseconds), for tests. */
    ViewTabs(Font font, LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
        float size = Math.max(9f, font.getSize() - 2f);
        this.nameFont = font.deriveFont(Font.BOLD, size);
        this.countFont = font.deriveFont(Font.PLAIN, size);
        this.rowHeight = getFontMetrics(font).getHeight();
        setOpaque(false);
        setFocusable(false);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        ToolTipManager.sharedInstance().registerComponent(this);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Tab tab = tabAt(e.getX());
                if (tab != null && SwingUtilities.isLeftMouseButton(e)) {
                    onSelect.accept(tab);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hover(tabAt(e.getX()));
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover(tabAt(e.getX()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover(null);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Make {@code tab} the active one: the view shown, or DETAILS while the event details are open over it. */
    void setActive(Tab tab) {
        if (tab == null || tab == active) {
            return;
        }
        active = tab;
        repaint();
    }

    /** The hit count shown after {@code MATCHES} (as the search summary renders it), or null with no search. */
    void setMatchCount(String text) {
        if (Objects.equals(matchCount, text)) {
            return;
        }
        matchCount = text;
        changed();
    }

    /** The bookmark count shown after {@code BOOKMARKS}; hidden at 0. */
    void setBookmarkCount(int count) {
        int shown = Math.max(0, count);
        if (bookmarkCount == shown) {
            return;
        }
        bookmarkCount = shown;
        changed();
    }

    /** Where a click on a tab goes. Called on the EDT. */
    void setOnSelect(Consumer<Tab> onSelect) {
        this.onSelect = onSelect == null ? tab -> {} : onSelect;
    }

    Tab active() {
        return active;
    }

    /** Draw attention to {@code tab} (pop and fade, 0.5 s); a flash already running pops again. */
    void flash(Tab tab) {
        if (tab == null) {
            return;
        }
        flashes.computeIfAbsent(tab, each -> new AttentionFlash(() -> repaintFlash(each), clockMillis)).start();
        repaintFlash(tab);
    }

    /** Whether {@code tab}'s flash is running. */
    boolean isFlashing(Tab tab) {
        AttentionFlash flash = flashes.get(tab);
        return flash != null && flash.isRunning();
    }

    private float flashIntensity(Tab tab) {
        AttentionFlash flash = flashes.get(tab);
        return flash == null ? 0f : flash.intensity();
    }

    private void repaintFlash(Tab tab) {
        Rectangle bounds = tabBounds(tab);
        repaint(bounds.x - FLASH_REACH, bounds.y, bounds.width + 2 * FLASH_REACH, bounds.height);
    }

    /** The tab at {@code x} in this component's coordinates, or null on a separator or outside the strip. */
    Tab tabAt(int x) {
        for (Tab tab : Tab.values()) {
            Rectangle bounds = tabBounds(tab);
            if (x >= bounds.x && x < bounds.x + bounds.width) {
                return tab;
            }
        }
        return null;
    }

    /** The tab's rectangle in this component's coordinates, full height. */
    Rectangle tabBounds(Tab tab) {
        int x = 0;
        for (Tab each : Tab.values()) {
            int width = tabWidth(each);
            if (each == tab) {
                return new Rectangle(x, 0, width, getHeight());
            }
            x += width + SEPARATOR;
        }
        throw new IllegalArgumentException("no tab " + tab);
    }

    /** The text of a tab as painted: its name and, when it has one, its count ({@code MATCHES 212}). */
    String labelOf(Tab tab) {
        String count = countOf(tab);
        return count == null ? nameOf(tab) : nameOf(tab) + " " + count;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        Tab tab = tabAt(event.getX());
        if (tab == null) {
            return null;
        }
        return tab == Tab.DETAILS && active == Tab.DETAILS
                ? "Close details (" + KeyMap.preferred().label(UiAction.CANCEL) + ")"
                : tooltipOf(tab, KeyMap.preferred());
    }

    static String tooltipOf(Tab tab, KeyMap keys) {
        return switch (tab) {
            case LOG -> "Back to the log (" + keys.label(UiAction.CANCEL) + ")";
            case MATCHES -> "Show the matches (" + keys.label(UiAction.TOGGLE_MATCHES) + ")";
            case BOOKMARKS -> "Show the bookmarks (" + keys.label(UiAction.BOOKMARKS_VIEW) + ")";
            case DETAILS -> "Details for the cursor line (" + keys.label(UiAction.OPEN_DETAILS) + ")";
        };
    }

    @Override
    public Dimension getPreferredSize() {
        int width = 0;
        for (Tab tab : Tab.values()) {
            width += tabWidth(tab) + SEPARATOR;
        }
        return new Dimension(width - SEPARATOR, rowHeight);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    /** As tall as the row it sits in, so the active tab is a full-height block. */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(getPreferredSize().width, Short.MAX_VALUE);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            Tab[] tabs = Tab.values();
            for (int i = 0; i < tabs.length; i++) {
                Rectangle bounds = tabBounds(tabs[i]);
                paintTab(g, tabs[i], bounds);
                if (i < tabs.length - 1 && tabs[i] != active && tabs[i + 1] != active) {
                    paintSeparator(g, bounds.x + bounds.width);
                }
            }
            for (Tab tab : tabs) {
                paintFlashGlow(g, tab, tabBounds(tab));
            }
        } finally {
            g.dispose();
        }
    }

    private void paintTab(Graphics2D g, Tab tab, Rectangle bounds) {
        boolean isActive = tab == active;
        float flash = flashIntensity(tab);
        if (isActive) {
            g.setColor(accentOf(tab));
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        } else if (flash > 0f) {
            g.setColor(withAlpha(accentOf(tab), Math.round(255 * FLASH_FILL_ALPHA * flash)));
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
        Color text = textColor(tab, isActive);
        if (!isActive && flash > 0f) {
            text = blend(text, DarculaColors.BACKGROUND, flash);
        }
        int x = bounds.x + PAD_X;
        FontMetrics names = g.getFontMetrics(nameFont);
        int baseline = bounds.y + (bounds.height - names.getHeight()) / 2 + names.getAscent();
        g.setFont(nameFont);
        g.setColor(text);
        g.drawString(nameOf(tab), x, baseline);

        String count = countOf(tab);
        if (count != null) {
            x += names.stringWidth(nameOf(tab) + " ");
            g.setFont(countFont);
            g.setColor(withAlpha(text, isActive ? COUNT_ALPHA : text.getAlpha()));
            g.drawString(count, x, baseline);
        }
    }

    /** The glare around a flashing tab, over its neighbours; clipped to the strip, which is as tall as the row. */
    private void paintFlashGlow(Graphics2D g, Tab tab, Rectangle bounds) {
        float flash = flashIntensity(tab);
        if (flash <= 0f) {
            return;
        }
        Color accent = accentOf(tab);
        BufferedImage glow = glowOf(bounds.width, bounds.height, accent);
        Composite before = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, flash)));
        g.drawImage(glow, bounds.x - FLASH_REACH, bounds.y - FLASH_REACH, null);
        g.setComposite(before);
    }

    private BufferedImage glowOf(int width, int height, Color accent) {
        if (glows.size() >= MAX_CACHED_GLOWS) {
            glows.clear();
        }
        return glows.computeIfAbsent(new GlowKey(width, height, accent.getRGB()),
                key -> Glow.render(width, height, FLASH_REACH, accent, FLASH_GLOW_PEAK));
    }

    private static Color blend(Color from, Color to, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * u),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * u),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * u),
                Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * u));
    }

    private void paintSeparator(Graphics2D g, int x) {
        g.setColor(withAlpha(DarculaColors.COMMENT, SEPARATOR_ALPHA));
        g.fillRect(x, SEPARATOR_INSET, SEPARATOR, Math.max(0, getHeight() - 2 * SEPARATOR_INSET));
    }

    private Color textColor(Tab tab, boolean isActive) {
        if (isActive) {
            // The blue log fill is too dark for dark text.
            return tab == Tab.LOG || tab == Tab.DETAILS ? DarculaColors.FOREGROUND : DarculaColors.BACKGROUND;
        }
        if (tab == hovered) {
            return DarculaColors.FOREGROUND;
        }
        if (tab == Tab.MATCHES && matchCount == null) {
            return withAlpha(DarculaColors.COMMENT, NO_SEARCH_ALPHA);
        }
        return DarculaColors.COMMENT;
    }

    private static Color accentOf(Tab tab) {
        return switch (tab) {
            case MATCHES -> DarculaColors.MATCHES_ACCENT;
            case BOOKMARKS -> DarculaColors.BOOKMARK;
            case LOG, DETAILS -> DarculaColors.BLUE;
        };
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static String nameOf(Tab tab) {
        return tab.name();
    }

    private String countOf(Tab tab) {
        return switch (tab) {
            case MATCHES -> matchCount;
            case BOOKMARKS -> bookmarkCount > 0 ? Integer.toString(bookmarkCount) : null;
            case LOG, DETAILS -> null;
        };
    }

    private int tabWidth(Tab tab) {
        int width = getFontMetrics(nameFont).stringWidth(labelOf(tab));
        return width + 2 * PAD_X;
    }

    private void hover(Tab tab) {
        if (hovered != tab) {
            hovered = tab;
            repaint();
        }
    }

    private void changed() {
        revalidate();
        repaint();
    }
}
