package com.github.mkram17.bazaarutils.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A click on the flip list must open the row that was drawn under the cursor, never its neighbour or the title bar. */
class FlipHudLayoutTest {
    private static final int X = 10, Y = 20; // panel origin (scaled), title bar is Y..Y+22, rows start at Y+23

    private static int at(double mx, double my, float scale, int scroll, int visible, int count) {
        return FlipHudLayout.rowAt(mx, my, X, Y, scale, scroll, visible, count);
    }

    @Test
    void eachRowBandMapsToItsRow() {
        assertEquals(-1, at(50, 30, 1, 0, 3, 5), "title bar");
        assertEquals(-1, at(50, 42, 1, 0, 3, 5), "the separator under the title");
        assertEquals(0, at(50, 43, 1, 0, 3, 5), "first pixel of row 1");
        assertEquals(0, at(50, 78, 1, 0, 3, 5), "last pixel of row 1");
        assertEquals(1, at(50, 79, 1, 0, 3, 5), "first pixel of row 2");
        assertEquals(2, at(50, 150, 1, 0, 3, 5), "last visible row");
        assertEquals(-1, at(50, 151, 1, 0, 3, 5), "below the visible rows (footer)");
    }

    @Test
    void clicksOutsideThePanelWidthMissEveryRow() {
        assertEquals(-1, at(9, 50, 1, 0, 3, 5));
        assertEquals(0, at(10, 50, 1, 0, 3, 5));
        assertEquals(0, at(259, 50, 1, 0, 3, 5));
        assertEquals(-1, at(260, 50, 1, 0, 3, 5));
    }

    @Test
    void scrollingShiftsWhichRowIsUnderTheCursor() {
        assertEquals(2, at(50, 43, 1, 2, 3, 5), "scrolled down 2: the top slot shows row 3");
        assertEquals(4, at(50, 115, 1, 2, 3, 5));
        assertEquals(-1, at(50, 80, 1, 2, 3, 3), "slot 2 would be index 3, past the end of a 3-row list");
    }

    @Test
    void panelScaleIsAppliedToTheClick() {
        assertEquals(0, at(50 * 0.75, 43 * 0.75, 0.75f, 0, 3, 5));
        assertEquals(-1, at(50 * 0.75, 42.5 * 0.75, 0.75f, 0, 3, 5));
        assertEquals(1, at(50 * 1.5, 80 * 1.5, 1.5f, 0, 3, 5));
    }

    /** The drawing code places row i's text at rowTextY; a click on that text must resolve to row i. */
    @Test
    void wherearowIsDrawnIsWhereItIsClicked() {
        for (int i = 0; i < 7; i++) {
            int textY = FlipHudLayout.rowTextY(Y, i);
            assertEquals(i, at(X + 6, textY + 4, 1, 0, 7, 7), "row " + i);
            assertEquals(i, at(X + 6, textY + 26, 1, 0, 7, 7), "row " + i + "'s third line");
        }
    }

    @Test
    void containerPanelBoundsAreHalfOpen() {
        assertTrue(FlipHudLayout.inside(100, 50, 100, 50, 176, 166));
        assertTrue(FlipHudLayout.inside(275, 215, 100, 50, 176, 166));
        assertFalse(FlipHudLayout.inside(276, 100, 100, 50, 176, 166));
        assertFalse(FlipHudLayout.inside(99, 100, 100, 50, 176, 166));
        assertFalse(FlipHudLayout.inside(150, 216, 100, 50, 176, 166));
    }
}
