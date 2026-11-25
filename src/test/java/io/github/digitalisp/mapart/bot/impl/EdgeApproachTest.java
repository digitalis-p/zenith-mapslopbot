package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EdgeApproach}'s pure rules on the live 2026-10-13 approach in base-relative coordinates: the last-resort stand
 * spot (-320, 173, 2) on the soul sand tile's west edge column, the gap over the water channel at x -321, the walk
 * coming from (-310, 173, 51) north-west at 0.49 blocks a tick.
 */
class EdgeApproachTest {
    private static final String WATER = "-321,173,2: water or lava 1 below";

    @Test
    void theFinalApproachToAnEdgeCellSneaks() {
        assertTrue(EdgeApproach.sneak(WATER, 0.6, false, false), "in the spot's cell, 0.6 from its center");
        assertTrue(EdgeApproach.sneak(WATER, 2.4, false, false), "one cell before it, beside the gap too");
        assertFalse(EdgeApproach.sneak(WATER, 2.6, false, false), "further out, not moving toward it: full speed");
        assertTrue(EdgeApproach.sneak(WATER, 40, false, true), "mid-path, moving toward the gap: sneak");
        assertFalse(EdgeApproach.sneak(null, 0.6, false, true), "no hazard beside the feet: no sneak");
        assertFalse(EdgeApproach.sneak(WATER, 0.6, true, true), "a planned step down: the sneak stop would refuse it");
    }

    @Test
    void towardTheGapButNotAlongTheEdge() {
        // the edge column x -320 with the gap west of it: hazards west, north-west and south-west
        List<int[]> dirs = List.of(new int[]{-1, 0}, new int[]{-1, -1}, new int[]{-1, 1});
        assertTrue(EdgeApproach.toward(dirs, -0.33, 0.01), "the local run: west at 0.33 a tick");
        assertTrue(EdgeApproach.toward(dirs, -0.36, -0.33), "the live arrival: north-west");
        assertFalse(EdgeApproach.toward(dirs, 0.0, -0.37), "north along the edge column");
        assertFalse(EdgeApproach.toward(dirs, 0.2, -0.2), "away from the gap");
        assertFalse(EdgeApproach.toward(dirs, -0.01, -0.3), "a drift of 0.01: not toward");
        // a corner: only the diagonal neighbour north-west is a hazard
        List<int[]> corner = List.of(new int[]{-1, -1});
        assertTrue(EdgeApproach.toward(corner, -0.2, -0.2));
        assertFalse(EdgeApproach.toward(corner, 0.0, -0.3), "north past the corner on its east side");
    }

    @Test
    void aStepDownInTheNextTwoMovesIsADescent() {
        Pos a = new Pos(-318, 173, 4);
        Pos b = new Pos(-319, 173, 3);
        Pos c = new Pos(-320, 173, 2);
        assertFalse(EdgeApproach.descentAhead(List.of(a, b, c)));
        assertTrue(EdgeApproach.descentAhead(List.of(new Pos(-318, 174, 4), b, c)), "off a block now");
        assertTrue(EdgeApproach.descentAhead(List.of(a, new Pos(-319, 174, 3), c)), "down in the next move");
        assertFalse(EdgeApproach.descentAhead(List.of(a, new Pos(-319, 174, 3), new Pos(-320, 174, 2),
            new Pos(-321, 173, 1))), "a descent three moves on is not yet");
        assertFalse(EdgeApproach.descentAhead(List.of(c)));
    }

    @Test
    void stillMeansOnTheGroundBelowZenithsCutOff() {
        assertTrue(EdgeApproach.still(true, 0, 0));
        assertTrue(EdgeApproach.still(true, 0.002, 0.001));
        assertFalse(EdgeApproach.still(true, 0.36, -0.33), "the live arrival: 0.49 blocks a tick");
        assertFalse(EdgeApproach.still(false, 0, 0), "in the air");
    }
}
