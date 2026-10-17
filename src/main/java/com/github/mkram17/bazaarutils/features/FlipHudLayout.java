package com.github.mkram17.bazaarutils.features;

/**
 * Geometry of the flip list panel, shared by the drawing code ({@link FlipHud}) and click hit-testing, so a click always
 * lands on the row that was drawn there. Pure arithmetic in the panel's scaled coordinates; no Minecraft classes.
 */
public final class FlipHudLayout {
    public static final int WIDTH = 250, TITLE_H = 22, ROW_H = 36;

    private FlipHudLayout() { }

    /** Top of row slot {@code i} (0 = first visible row), in scaled coordinates: the separator line sits here. */
    public static int rowTop(int panelY, int i) {
        return panelY + TITLE_H + 1 + i * ROW_H;
    }

    /** Where row slot {@code i}'s first line of text is drawn. */
    public static int rowTextY(int panelY, int i) {
        return rowTop(panelY, i) + 2;
    }

    /**
     * Index (into the full, scrolled list) of the row under a screen-pixel point, or -1 for the title bar, the footer, past
     * the panel edges or past the end of the list. {@code panelX/panelY} are the panel's scaled origin, {@code scale} the
     * panel scale, {@code scroll} the first visible index.
     */
    public static int rowAt(double mouseX, double mouseY, int panelX, int panelY, float scale, int scroll, int visible, int count) {
        double x = mouseX / scale, y = mouseY / scale;
        if (x < panelX || x >= panelX + WIDTH) return -1;
        int first = rowTop(panelY, 0);
        if (y < first) return -1;
        int slot = (int) Math.floor((y - first) / ROW_H);
        if (slot >= visible) return -1;
        int index = scroll + slot;
        return index < count ? index : -1;
    }

    /** True if a point lies inside a rectangle (screen pixels), e.g. an open container's panel. */
    public static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }
}
