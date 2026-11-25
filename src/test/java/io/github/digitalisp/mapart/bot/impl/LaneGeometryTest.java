package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneGeometryTest {
    private static final double EPS = 1e-9;

    @Test
    void northToSouthLane() {
        // lane at x = -62 from z = -64 to z = 63 (walking south, +z)
        LaneGeometry g = new LaneGeometry(-62, -64, -62, 63);
        assertTrue(g.alongZ());
        assertEquals(1, g.dir());
        assertEquals(127, g.length(), EPS);
        assertEquals(0, g.cellS(-62, -64), EPS);
        assertEquals(127, g.cellS(-62, 63), EPS);
        assertEquals(10.25, g.s(-61.5, -64 + 10.75), EPS);
        assertEquals(2, g.cellLateral(-60, 0), EPS);
        assertEquals(-2, g.cellLateral(-64, 0), EPS);
        assertTrue(g.inCorridor(-62, 5));
        assertFalse(g.inCorridor(-61, 5));
        assertArrayEquals(new double[]{0, 1}, g.forward(), EPS);
    }

    @Test
    void southToNorthLane() {
        LaneGeometry g = new LaneGeometry(-57, 63, -57, -64);
        assertEquals(-1, g.dir());
        assertEquals(0, g.cellS(-57, 63), EPS);
        assertEquals(1, g.cellS(-57, 62), EPS, "one block north is one step along the lane");
        assertEquals(127, g.length(), EPS);
        assertArrayEquals(new double[]{0, -1}, g.forward(), EPS);
    }

    @Test
    void eastWestLane() {
        LaneGeometry g = new LaneGeometry(10, 5, 0, 5);
        assertFalse(g.alongZ());
        assertEquals(-1, g.dir());
        assertEquals(10, g.length(), EPS);
        assertEquals(4, g.cellS(6, 5), EPS);
        assertEquals(1, g.cellLateral(6, 6), EPS);
        assertArrayEquals(new double[]{-1, 0}, g.forward(), EPS);
    }

    @Test
    void steeringPointsBackToTheLine() {
        LaneGeometry g = new LaneGeometry(0, 0, 0, 10);
        double[] left = g.steer(1.0, 3, 2.0);   // 0.5 east of the line x = 0.5
        assertTrue(left[0] < 0, "steer west");
        assertEquals(0, left[1], EPS);
        double[] far = g.steer(5.0, 3, 2.0);
        assertEquals(-1, far[0], EPS, "clamped");
    }

    @Test
    void singleCellLaneIsAllowedAndDiagonalIsNot() {
        LaneGeometry g = new LaneGeometry(3, 3, 3, 3);
        assertEquals(0, g.length(), EPS);
        assertThrows(IllegalArgumentException.class, () -> new LaneGeometry(0, 0, 5, 5));
    }

    @Test
    void airPlaceHalfWidthForTheUsualRanges() {
        assertEquals(4, LaneGeometry.maxAirPlaceHalfWidth(5.0), "9-wide lanes at the printer's 5.0");
        assertEquals(3, LaneGeometry.maxAirPlaceHalfWidth(4.5));
        assertEquals(0, LaneGeometry.maxAirPlaceHalfWidth(1.0), "not even the lane line's own cells");
        assertEquals(0, LaneGeometry.maxAirPlaceHalfWidth(0.0));
        assertEquals(0, LaneGeometry.maxAirPlaceHalfWidth(-10.0), "a negative range is no range, not a wide lane");
        assertEquals(0, LaneGeometry.maxAirPlaceHalfWidth(Double.NaN));
        int last = 0;
        for (int i = 0; i <= 200; i++) {
            int w = LaneGeometry.maxAirPlaceHalfWidth(i / 20.0);
            assertTrue(w >= 0, "never negative: range " + i / 20.0);
            assertTrue(w >= last, "grows with the range: " + i / 20.0);
            last = w;
        }
    }

    /** Eye (1.62 above the floor) to the centre (0.5 above the floor) of the cell {@code column} off the line. */
    private static double eyeToCell(int column, double along) {
        double h = Aim.EYE_HEIGHT - 0.5;
        return Math.sqrt(column * column + along * along + h * h);
    }

    /** True if column {@code column} stays within {@code range} of the eye for along-lane offsets in [-1, 1]. */
    private static boolean inRangeOverWindow(int column, double range) {
        for (int k = -20; k <= 20; k++) {
            if (eyeToCell(column, k / 20.0) > range + 1e-9) return false;
        }
        return true;
    }

    /** The definition, by brute force: the widest column within range − margin over ±1 block along the lane. */
    @Test
    void airPlaceHalfWidthMatchesItsDefinition() {
        assertTrue(inRangeOverWindow(4, 5.0 - LaneGeometry.AIR_WIDTH_MARGIN), "outer column at 5.0");
        assertTrue(eyeToCell(5, 0) > 5.0, "column 5 is out of range even beside the bot with the full 5.0");
        for (int i = 40; i <= 160; i++) {
            double range = i / 20.0;
            int brute = 0;
            while (inRangeOverWindow(brute + 1, range - LaneGeometry.AIR_WIDTH_MARGIN)) brute++;
            assertEquals(brute, LaneGeometry.maxAirPlaceHalfWidth(range), "range " + range);
        }
    }

    @Test
    void airWindowIsTheAlongLaneReachOfAColumn() {
        double h = PlaceMode.EYE_ABOVE_CELL_CENTRE;
        assertEquals(Math.sqrt(4.95 * 4.95 - h * h - 16), LaneGeometry.airWindow(4, 4.95), EPS);
        assertEquals(LaneGeometry.airWindow(4, 4.95), LaneGeometry.airWindow(-4, 4.95), EPS, "either side");
        assertEquals(2.2, LaneGeometry.airWindow(4, 5.0 - LaneGeometry.AIR_WIDTH_MARGIN), 0.01,
            "outer column of a 9-wide lane: about ±2.2 blocks while walking");
        assertEquals(5.0, eyeToCell(4, LaneGeometry.airWindow(4, 5.0)), EPS, "the window ends at the range");
        assertTrue(Double.isNaN(LaneGeometry.airWindow(5, 5.0)), "column 5 never in range at 5.0");
        assertTrue(Double.isNaN(LaneGeometry.airWindow(0, 1.0)), "the cell below the eye is out of range");
    }
}
