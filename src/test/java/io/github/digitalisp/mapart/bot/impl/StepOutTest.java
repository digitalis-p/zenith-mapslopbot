package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StepOut} on the live 2026-10-20 geometry (Abaddon [3,4], soul sand, tile-relative): the art layer at y173,
 * the east edge column x 127, the no-go zone from x 128 east; a pointed dripstone in (127,173,24), the bot's feet at
 * (127.40, 24.096) in its cell, the box against the column's north face.
 */
class StepOutTest {
    static final int Y = 173;
    static final Pos DRIPSTONE = new Pos(127, Y, 24);
    /** Every cell but the dripstone's and the zone's. */
    static final Predicate<Pos> LIVE = c -> !c.equals(DRIPSTONE) && c.x() < 128;

    /** Out to the north, the cell the box already reaches into, straight across the edge, away from the column. */
    @Test
    void liveStepsNorthAwayFromTheColumn() {
        Pos to = StepOut.target(127.40, 24.096, DRIPSTONE, LIVE);
        assertEquals(new Pos(127, Y, 23), to);
        double[] aim = StepOut.aim(127.40, 24.096, to);
        assertEquals(127.40, aim[0], 1e-9);
        assertEquals(23.7, aim[1], 1e-9);
        float yaw = StepOut.yaw(127.40, 24.096, aim[0], aim[1]);
        assertEquals(180, Math.abs(yaw), 1e-3, "north (−z)");
    }

    /** The nearest cell the predicate accepts: the zone and the north cell refused, west next. */
    @Test
    void nearestAcceptedCell() {
        Predicate<Pos> noNorth = LIVE.and(c -> !c.equals(new Pos(127, Y, 23)));
        assertEquals(new Pos(126, Y, 24), StepOut.target(127.40, 24.096, DRIPSTONE, noNorth));
        // a diagonal only when nothing beside is free
        Predicate<Pos> diagonal = c -> c.equals(new Pos(126, Y, 23));
        assertEquals(new Pos(126, Y, 23), StepOut.target(127.40, 24.096, DRIPSTONE, diagonal));
        assertNull(StepOut.target(127.40, 24.096, DRIPSTONE, c -> false));
    }

    /** Equal distance: the cells beside first, north before east, south and west. */
    @Test
    void tiesGoToTheCellsBesideInOrder() {
        assertEquals(new Pos(127, Y, 23), StepOut.target(127.5, 24.5, DRIPSTONE, c -> true));
        assertEquals(new Pos(127, Y, 25), StepOut.target(127.5, 24.5, DRIPSTONE, c -> c.x() < 127 || c.z() > 24));
        assertEquals(new Pos(126, Y, 24), StepOut.target(127.5, 24.5, DRIPSTONE, c -> c.x() < 127));
    }

    /**
     * Wedged: a block the pathfinder cannot walk through rising above the feet (the live dripstone tip, 11/16 high,
     * the bot on soul sand at 172.875; a full-height column). Not: a bottom slab the bot stands on (its own feet cell),
     * a carpet it stands on, anything the pathfinder walks through.
     */
    @Test
    void wedgedOnlyBesideABlockNotOnIt() {
        assertTrue(StepOut.wedged(172.875, Y, 11 / 16.0, false), "the live dripstone tip");
        assertTrue(StepOut.wedged(172.875, Y, 1.0, false), "a dripstone column");
        assertFalse(StepOut.wedged(Y + 0.5, Y, 0.5, false), "on a bottom slab");
        assertFalse(StepOut.wedged(Y + 0.0625, Y, 0.0625, false), "on a carpet");
        assertFalse(StepOut.wedged(172.875, Y, 1.0, true), "walked through");
    }

    /** The aim keeps the whole box in the target cell; distance is to the cell's column. */
    @Test
    void aimAndDistance() {
        double[] a = StepOut.aim(127.05, 24.5, new Pos(126, Y, 24));
        assertEquals(126.7, a[0], 1e-9);
        assertEquals(24.5, a[1], 1e-9);
        assertEquals(0.05, StepOut.distance(127.05, 24.5, new Pos(126, Y, 24)), 1e-9);
        assertEquals(Math.hypot(0.4, 0.096), StepOut.distance(127.40, 24.096, new Pos(126, Y, 23)), 1e-9);
        assertEquals(0, StepOut.distance(127.40, 24.096, DRIPSTONE), 1e-9);
    }
}
