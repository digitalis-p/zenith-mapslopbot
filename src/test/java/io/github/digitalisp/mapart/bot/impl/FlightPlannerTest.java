package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FlightPlanner} on the {@link FlightSim} platform: the landing pad's choice and the corridor's heights. */
class FlightPlannerTest {
    private static final FlightPlanner.Cfg CFG = FlightPlanner.Cfg.defaults(60);

    private static FlightPlanner.Result plan(FlightSim w, double sx, double sy, double sz, int gx, int gy, int gz) {
        return FlightPlanner.plan(sx, sy, sz, gx, gy, gz, w.terrain(), CFG);
    }

    @Test
    void walkOutLandsOnTheTileShortOfTheWalkway() {
        // from the build (feet on the soul sand, y 172.875) to a chest stand on the walkway one block up (feet 174)
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = plan(w, 40.5, 172.875, 100.5, 40, 174, -3);
        assertNotNull(r.plan(), r.refusal());
        FlightPlanner.Profile p = r.plan().profile();
        assertEquals(173, p.level, "the tile's feet level, one below the goal");
        assertEquals(172.875, p.surface, 1e-9);
        // the pad (R + padFront 4 + its half width) ends on the tile, before the walkway's step at z -1
        assertTrue(p.restZ >= 4.0 && p.restZ <= 7.0, "R at z " + p.restZ);
        for (long k : p.pad) assertTrue((int) k >= 0, "pad cell at z " + (int) k + " off the tile");
        assertEquals(40, r.plan().restCellX());
    }

    @Test
    void walkBackLandsAtTheLaneStart() {
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = plan(w, 40.5, 174, -2.5, 40, 173, 90);
        assertNotNull(r.plan(), r.refusal());
        assertEquals(40, r.plan().restCellX());
        assertEquals(90, r.plan().restCellZ(), "R at the goal itself: the tile is level there");
        assertEquals(173, r.plan().profile().level);
    }

    @Test
    void longHopAlongTheWalkwayLandsOnTheWalkwayOrTheTile() {
        // down the walkway's middle: the pad (1.2 either side) fits on its 4 cells of glass
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = plan(w, 10.5, 174, -2.5, 90, 174, -3);
        assertNotNull(r.plan(), r.refusal());
        FlightPlanner.Profile p = r.plan().profile();
        assertEquals(174, p.level);
        assertTrue(Math.hypot(p.restX - 90.5, p.restZ + 2.5) <= 2, "R " + p.restX + "," + p.restZ);
        for (long k : p.pad) assertTrue((int) k >= -4 && (int) k <= -1, "pad cell at z " + (int) k + " off the walkway");
        // from a chest's stand (beside the sorter's wall) the line grazes the wall: R lies on the tile beside the walkway
        FlightPlanner.Result s = plan(w, 10.5, 174, -3.5, 90, 174, -3);
        assertNotNull(s.plan(), s.refusal());
        FlightPlanner.Profile q = s.plan().profile();
        assertEquals(173, q.level);
        assertTrue(q.restZ > 0 && Math.hypot(q.restX - 90.5, q.restZ + 2.5) <= 24, "R " + q.restX + "," + q.restZ);
        for (long k : q.pad) assertTrue((int) k >= 0, "pad cell at z " + (int) k + " off the tile");
    }

    @Test
    void shortAndLongLegsWalk() {
        FlightSim w = new FlightSim();
        assertTrue(plan(w, 40.5, 172.875, 60.5, 40, 173, 10).refusal().contains("under elytraMinBlocks"));
        FlightPlanner.Result far = FlightPlanner.plan(0.5, 172.875, 0.5, 500, 173, 0, w.terrain(), CFG);
        assertTrue(far.refusal().contains("over 400"), far.refusal());
    }

    @Test
    void padNeverBesideTheSideCanal() {
        // a lane start in the tile's first column: the pad's side reaches the canal at x -1 (water), so R moves back
        // along the line until no pad cell holds fluid; the corridor along the canal is refused for its water
        FlightSim w = new FlightSim();
        FlightPlanner.Result r = plan(w, 0.5, 174, -2.5, 0, 173, 100);
        assertNull(r.plan());
        assertTrue(r.refusal().contains("water") || r.refusal().contains("no landing pad"), r.refusal());
    }

    @Test
    void corridorNeverCrossesWater() {
        // across the east canal to a floor beyond it: never over the water; a part of the leg flies (R on the tile
        // short of the canal, its pad, its run-out (8 past R: no slide into the canal) and corridor dry) and the rest
        // walks
        FlightSim w = new FlightSim();
        for (int x = 129; x <= 200; x++) for (int z = 50; z <= 70; z++) w.block(x, 172, z);
        FlightPlanner.Result r = plan(w, 50.5, 172.875, 60.5, 180, 173, 60);
        assertNotNull(r.plan(), r.refusal());
        FlightPlanner.Profile p = r.plan().profile();
        for (long k : p.tops.keySet()) {
            // the apron (the look-ahead's room for a burn's spin or a bail-out) may reach it, water never come down on
            if (p.apron.contains(k)) {
                if ((int) (k >> 32) == 128) assertTrue(p.water((int) (k >> 32), (int) k), "the canal in the apron counts as water");
                continue;
            }
            assertTrue((int) (k >> 32) < 128, "column at x " + (int) (k >> 32) + " over the canal");
        }
        assertTrue(r.plan().flightBlocks() >= 45 && r.plan().flightBlocks() < r.plan().legBlocks(), "a part flies");
        // no floor short of the canal far enough for a flight: refused for the water
        FlightPlanner.Result none = plan(w, 100.5, 172.875, 60.5, 180, 173, 60);
        assertNull(none.plan());
        assertTrue(none.refusal().contains("crosses water at 128,170,"), none.refusal());
    }

    @Test
    void pathHeightClearsTheHighestBlockOrRefuses() {
        FlightSim w = new FlightSim();
        // a pillar 4 high on the line: the cruise must clear its top (177) by the clearance
        for (int y = 173; y <= 176; y++) w.block(40, y, 50);
        FlightPlanner.Result r = plan(w, 40.5, 172.875, 100.5, 40, 173, 10);
        assertNotNull(r.plan(), r.refusal());
        assertEquals(178.0, r.plan().cruiseY(), 1e-9);
        // one 9 high: over the start's floor + elytraCruise 8 (180.875): the leg walks
        for (int y = 177; y <= 181; y++) w.block(40, y, 50);
        FlightPlanner.Result blocked = plan(w, 40.5, 172.875, 100.5, 40, 173, 10);
        assertNull(blocked.plan());
        assertTrue(blocked.refusal().startsWith("the flight path is blocked: a block at 40,181,50"), blocked.refusal());
    }

    @Test
    void endRodsAreRoofsAndTheBandStaysUnderThem() {
        FlightSim w = new FlightSim().lights();
        FlightPlanner.Result r = plan(w, 23.5, 172.875, 120.5, 23, 174, -3);
        assertNotNull(r.plan(), r.refusal());
        FlightPlanner.Profile p = r.plan().profile();
        // the rod at 23,178,106 is a roof over its column, the tile under it the floor
        assertEquals(178.0, p.roof(23, 106), 1e-9);
        assertEquals(172.875, p.top(23, 106), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, p.roof(23, 110));
        assertTrue(r.plan().cruiseY() <= 178 - ElytraPhysics.BOX_HEIGHT - FlightPlanner.ROOF_MARGIN,
            "band " + r.plan().cruiseY() + " under the rods");
        // a roof 2 above the floor over the line (a slab ceiling at y 175): no room for the box, the leg walks
        FlightSim low = new FlightSim();
        for (int z = 40; z <= 60; z++) low.block(40, 175, z);
        FlightPlanner.Result none = plan(low, 40.5, 172.875, 100.5, 40, 173, 10);
        assertNull(none.plan());
        assertTrue(none.refusal().startsWith("no room under the blocks over the flight path: a block at 40,175,"), none.refusal());
    }

    @Test
    void landingCellAvoidsTallBlocksAndBorderTops() {
        FlightSim w = new FlightSim();
        // a candle-like tall block (a full block in the test) in the feet cell at the goal: R moves back past it
        w.block(40, 173, 20);
        FlightPlanner.Result r = plan(w, 40.5, 172.875, 100.5, 40, 173, 20);
        assertNotNull(r.plan(), r.refusal());
        FlightPlanner.Profile p0 = r.plan().profile();
        assertFalse(p0.pad(40, 20), "the block's column is no pad cell");
        assertTrue(Math.hypot(p0.restX - 40.5, p0.restZ - 20.5) <= 4, "R " + p0.restX + "," + p0.restZ + " next to it");
        // the south wall's top (z 128, feet 175) is a border top: never a landing, the leg walks
        FlightSim w2 = new FlightSim();
        FlightPlanner.Result top = FlightPlanner.plan(40.5, 172.875, 40.5, 40, 175, 128, new FlightPlanner.Terrain() {
            final FlightPlanner.Terrain t = w2.terrain();
            @Override public boolean loaded(int x, int z) { return t.loaded(x, z); }
            @Override public double top(int x, int y, int z) { return t.top(x, y, z); }
            @Override public boolean fluid(int x, int y, int z) { return t.fluid(x, y, z); }
            @Override public boolean forbidden(int x, int y, int z) { return false; }
            @Override public boolean noLanding(int x, int y, int z) { return new BorderTop(0, 0, 127, 127, 175).contains(x, y, z); }
        }, CFG);
        if (top.plan() != null) {
            FlightPlanner.Profile p = top.plan().profile();
            assertTrue(p.restZ < 127.5 - 4, "R " + p.restZ + " on the tile, short of the wall");
            assertEquals(173, p.level);
        }
    }

    /**
     * Water high over the flight (a clear machine's channel: live 2026-10-24, "crosses water at ...,182,..." on a
     * LANE_START leg) is a roof the band stays under, not water under the flight; water on the floor still refuses.
     */
    @Test
    void waterHighOverTheFlightIsARoofNotARefusal() {
        FlightSim w = new FlightSim();
        for (int z = 20; z <= 80; z++) for (int x = 38; x <= 43; x++) w.water.add(x + ",182," + z);
        FlightPlanner.Result r = plan(w, 40.5, 174, -2.5, 40, 173, 97);
        assertNotNull(r.plan(), r.refusal());
        FlightPlanner.Profile p = r.plan().profile();
        assertEquals(182.0, p.roof(40, 50), 1e-9);
        assertTrue(p.bandHigh <= 182 - ElytraPhysics.BOX_HEIGHT - FlightPlanner.ROOF_MARGIN + 1e-9, "band " + p.bandHigh);
        // water just over the floor (a puddle two cells up would be a roof too; one on the floor is under the flight)
        FlightSim wet = new FlightSim();
        for (int z = 20; z <= 80; z++) for (int x = 38; x <= 43; x++) wet.water.add(x + ",173," + z);
        FlightPlanner.Result r2 = plan(wet, 40.5, 174, -2.5, 40, 173, 97);
        assertTrue(r2.plan() == null || r2.plan().profile().restZ < 20, "never over the water on the floor");
    }

    /** A leg with no pad says how every candidate failed, besides the nearest's problem. */
    @Test
    void aRefusalForNoPadCountsTheCandidatesProblems() {
        FlightSim w = new FlightSim();
        for (int x = 0; x <= 127; x++) for (int z = 0; z <= 127; z++) if ((x + z) % 3 == 0) w.tall.add(x + ",173," + z);
        FlightPlanner.Result r = plan(w, 40.5, 172.875, 120.5, 40, 173, 10);
        assertNull(r.plan());
        assertTrue(r.refusal().startsWith("no landing pad near the goal ("), r.refusal());
        assertTrue(r.refusal().contains("; candidates: "), r.refusal());
    }

    @Test
    void noGoCellsAndUnloadedChunksRefuse() {
        FlightSim w = new FlightSim();
        w.forbidden.add("40,180,50");
        FlightPlanner.Result r = plan(w, 40.5, 172.875, 100.5, 40, 173, 10);
        assertNull(r.plan());
        assertTrue(r.refusal().contains("no-go cell at 40,180,50"), r.refusal());
        FlightSim u = new FlightSim();
        u.unloaded.add("40,60");
        FlightPlanner.Result r2 = plan(u, 40.5, 172.875, 100.5, 40, 173, 10);
        assertNull(r2.plan());
        assertTrue(r2.refusal().contains("not loaded at 40,60"), r2.refusal());
    }

    @Test
    void noHeadRoomAtTheStartRefuses() {
        FlightSim w = new FlightSim();
        w.block(40, 174, 100);
        FlightPlanner.Result r = plan(w, 40.5, 172.875, 100.5, 40, 173, 10);
        assertEquals("no head room to take off", r.refusal());
    }
}
