package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lane placer's walking target. Live bug: a cell beside the corridor (lateral ±1) whose row was the last one left
 * pulled the walk only onto its own row, where it touches the player's safe box (PlaceLaneTask.placeableNow), so it
 * was never placed and the lane gave up after 100 ticks with "not reachable from the lane" (BUILD↔RESTOCK loop).
 */
class LaneWalkTest {
    private static final double EPS = 1e-9;
    private static final double STOP = 0.05; // PlaceLaneTask stops walking within this of the target
    private static final int Y = 64;

    private static LaneWalk.Cell cell(LaneGeometry g, int x, int z) {
        return new LaneWalk.Cell(g.cellS(x, z), g.cellLateral(x, z), g.inCorridor(x, z));
    }

    /**
     * PlaceLaneTask's check for a non-corridor cell (x, z), with the player on the lane line x = 2.5 at {@code s} of an
     * along-Z lane that starts at z = {@code z0}.
     */
    private static boolean clear(LaneGeometry g, int z0, int x, int z, double s) {
        double pz = z0 + 0.5 + g.dir() * s;
        assertEquals(s, g.s(2.5, pz), EPS);
        return !Aim.cellOverlaps(x, Y, z, Aim.playerBox(2.5, Y, pz, LaneWalk.SAFE_INFLATE));
    }

    @Test
    void besideCellOnTheStartRowIsPassedAndPlacedFromBehind() {
        // the live retry lane: only the cell beside the corridor on the tile's edge row is left; the planner clamps the
        // lane start onto that row (from z=0, to z=1), lane line x=2, cell (1, 0)
        LaneGeometry g = new LaneGeometry(2, 0, 2, 1);
        LaneWalk.Cell c = cell(g, 1, 0);
        assertEquals(0, c.s(), EPS);
        assertTrue(c.beside());
        assertFalse(clear(g, 0, 1, 0, 0), "standing on the cell's row, it touches the safe box");
        double endCap = g.length() + 0.95; // the row past `to` is walkable
        double t = LaneWalk.forwardTarget(List.of(c), 0, endCap);
        assertEquals(LaneWalk.SIDE_CLEARANCE + LaneWalk.MARGIN, t, EPS);
        assertTrue(clear(g, 0, 1, 0, t - STOP), "where the walk stops the cell can be placed");
        assertFalse(LaneWalk.blockedAtEnd(List.of(c), t - STOP));
    }

    @Test
    void besideCellOnTheEndRowAtTheTileEdgeIsPlacedOnTheWayBack() {
        // the live long lane: walking north to the tile edge (to = z 0), nothing walkable beyond, so endCap = length
        LaneGeometry g = new LaneGeometry(2, 127, 2, 0);
        LaneWalk.Cell c = cell(g, 1, 0);
        double endCap = g.length();
        assertEquals(endCap, c.s(), EPS);
        assertEquals(endCap, LaneWalk.forwardTarget(List.of(c), 100, endCap), EPS, "capped at the lane end");
        assertFalse(clear(g, 127, 1, 0, endCap));
        assertTrue(LaneWalk.blockedAtEnd(List.of(c), endCap), "reverse instead of giving up");
        double back = LaneWalk.reverseTarget(List.of(c), endCap);
        assertEquals(c.s() - LaneWalk.SIDE_CLEARANCE - LaneWalk.MARGIN, back, EPS);
        assertTrue(clear(g, 127, 1, 0, back + STOP), "placeable where the walk back stops");
        assertEquals(back, LaneWalk.reverseTarget(List.of(c), back + STOP), EPS, "and it stays there");
    }

    @Test
    void walkingBackStopsOnTheLaneStartCell() {
        // one-row lane from z=126 to the clamped end z=127, cell (1, 127): back to s=-0.2 is just enough
        LaneGeometry g = new LaneGeometry(2, 126, 2, 127);
        LaneWalk.Cell c = cell(g, 1, 127);
        assertEquals(1, c.s(), EPS);
        assertTrue(LaneWalk.blockedAtEnd(List.of(c), g.length()));
        double back = LaneWalk.reverseTarget(List.of(c), g.length());
        assertEquals(LaneWalk.MIN_BACK_S, back, EPS, "never past the start cell");
        assertTrue(clear(g, 126, 1, 127, back + STOP));
    }

    @Test
    void corridorCellsKeepTheirRule() {
        LaneGeometry g = new LaneGeometry(2, 0, 2, 10);
        LaneWalk.Cell c = cell(g, 2, 5);
        assertTrue(c.corridor());
        assertFalse(c.beside());
        assertEquals(5 + 0.5 + LaneWalk.EDGE + 0.15, LaneWalk.forwardTarget(List.of(c), 0, 20), EPS);
        assertTrue(LaneWalk.blockedAtEnd(List.of(c), 5.5));
        assertFalse(LaneWalk.blockedAtEnd(List.of(c), 6.0));
        assertEquals(5 - 0.5 - LaneWalk.EDGE - 0.1, LaneWalk.reverseTarget(List.of(c), 5.5), EPS);
    }

    @Test
    void cellsTwoColumnsOutNeverBlock() {
        LaneGeometry g = new LaneGeometry(2, 0, 2, 10);
        LaneWalk.Cell c = cell(g, 4, 10);
        assertFalse(c.beside());
        assertTrue(clear(g, 0, 4, 10, 10), "never touches the safe box of a player on the line");
        assertEquals(10, LaneWalk.forwardTarget(List.of(c), 0, 10.95), EPS);
        assertFalse(LaneWalk.blockedAtEnd(List.of(c), 10));
        assertEquals(10, LaneWalk.reverseTarget(List.of(c), 10), EPS, "nothing to walk back for");
    }

    private static LaneWalk.Cell at(double s, int column) {
        return new LaneWalk.Cell(s, column, column == 0, true);
    }

    @Test
    void aFullBlockHidesTheNextColumnOutInItsRowAndTheTwoRowsBehind() {
        // live: mushroom stem beside the line (column -1) went up before the stem one further out (column -2)
        LaneWalk.Cell beside = at(10, -1);
        assertTrue(LaneWalk.shadows(beside, at(10, -2), 1), "same row, further out");
        assertTrue(LaneWalk.shadows(beside, at(9, -2), 1), "one row behind");
        assertTrue(LaneWalk.shadows(beside, at(8, -2), 1), "two rows behind");
        assertFalse(LaneWalk.shadows(beside, at(7, -2), 1), "three rows behind: the ray passes above it");
        assertFalse(LaneWalk.shadows(beside, at(11, -2), 1), "ahead: seen from beside or ahead of it");
        assertFalse(LaneWalk.shadows(beside, at(10, 2), 1), "other side of the line");
        assertFalse(LaneWalk.shadows(beside, at(10, 0), 1), "nearer the line");
        // walking back (the lane end), behind means the other way
        assertTrue(LaneWalk.shadows(beside, at(11, -2), -1));
        assertFalse(LaneWalk.shadows(beside, at(9, -2), -1));
    }

    @Test
    void theCorridorHidesTheColumnsBesideItAndItsOwnColumnBehind() {
        // a ray from the eye (1.62 up) to column ±1 leaves the corridor at 0.81 < 1; to column ±2 at 1.2 > 1
        LaneWalk.Cell corridor = at(20, 0);
        assertTrue(LaneWalk.shadows(corridor, at(20, -1), 1));
        assertTrue(LaneWalk.shadows(corridor, at(19, 1), 1));
        assertFalse(LaneWalk.shadows(corridor, at(20, 2), 1), "two columns out is seen over the corridor");
        assertTrue(LaneWalk.shadows(corridor, at(19, 0), 1), "the corridor cell behind it");
        assertTrue(LaneWalk.shadows(corridor, at(18, 0), 1));
        assertFalse(LaneWalk.shadows(corridor, at(21, 0), 1), "the corridor ahead is placed after it");
        assertFalse(LaneWalk.shadows(corridor, corridor, 1));
        // wider lanes: column 1 hides 2, and column 2 hides 3 and 4
        assertTrue(LaneWalk.shadows(at(5, 2), at(5, 3), 1));
        assertTrue(LaneWalk.shadows(at(5, 2), at(5, 4), 1));
        assertFalse(LaneWalk.shadows(at(5, 1), at(5, 4), 1), "1.62·(1 − 1.5/4) ≈ 1.01 clears a block");
    }

    @Test
    void holdKeepsAWaitingCellWithinReachBehind() {
        double reachH = LaneWalk.horizontalReach(4.23, Aim.EYE_HEIGHT);
        assertEquals(Math.sqrt(4.23 * 4.23 - Aim.EYE_HEIGHT * Aim.EYE_HEIGHT), reachH, EPS);
        double corridor = LaneWalk.holdLimit(at(10, 0), reachH, 1);
        double outer = LaneWalk.holdLimit(at(10, 2), reachH, 1);
        assertEquals(10 + reachH - LaneWalk.HOLD_MARGIN, corridor, EPS);
        assertTrue(outer < corridor, "a cell further out leaves reach sooner");
        assertTrue(corridor - 10 > LaneWalk.SIDE_CLEARANCE + LaneWalk.MARGIN,
            "the walk can still pass the beside cells the corridor block waits for");
        assertEquals(10 - (reachH - LaneWalk.HOLD_MARGIN), LaneWalk.holdLimit(at(10, 0), reachH, -1), EPS);
    }

    @Test
    void farthestRequirementWinsAndNothingLeftMeansStay() {
        LaneGeometry g = new LaneGeometry(2, 0, 2, 10);
        List<LaneWalk.Cell> todo = List.of(cell(g, 2, 3), cell(g, 1, 4), cell(g, 4, 5));
        assertEquals(4 + LaneWalk.SIDE_CLEARANCE + LaneWalk.MARGIN, LaneWalk.forwardTarget(todo, 0, 10.95), EPS);
        assertEquals(7.5, LaneWalk.forwardTarget(List.of(), 7.5, 10.95), EPS);
        assertEquals(7.5, LaneWalk.reverseTarget(List.of(), 7.5), EPS);
    }
}
