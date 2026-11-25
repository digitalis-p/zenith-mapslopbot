package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrowPlannerTest {
    /** Block sets keyed "x,y,z". */
    private record World(Set<String> solid, Set<String> water) implements ThrowPlanner.Cells {
        World() {
            this(new HashSet<>(), new HashSet<>());
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return solid.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean water(int x, int y, int z) {
            return water.contains(x + "," + y + "," + z);
        }
    }

    /**
     * Shape of the moss drop point: the bot stands on a walkway (floor y=173), a 1-high railing at z=1 (y=174),
     * then a 1-wide open column at x=0 running south, with a water channel at its bottom (y=169) and a water
     * source at y=170, four blocks south of the bot.
     */
    private static World walkwayWithRailing() {
        World w = new World();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 0; z++) w.solid.add(x + ",173," + z);
            w.solid.add(x + ",173,1");
            w.solid.add(x + ",174,1");
            for (int z = 2; z <= 12; z++) {
                if (x == 0) w.water.add("0,169," + z);
                else for (int y = 169; y <= 172; y++) w.solid.add(x + "," + y + "," + z); // channel walls
            }
        }
        w.water.add("0,170,4");
        return w;
    }

    @Test
    void findsAFlatThrowOverTheRailing() {
        World w = walkwayWithRailing();
        Pos water = new Pos(0, 170, 4);
        ThrowPlanner.Aim aim = ThrowPlanner.plan(0.5, 174 + 1.62, 0.5, water, w);
        assertNotNull(aim);
        assertEquals(0f, aim.yaw(), 0.01f, "facing +z");
        assertTrue(aim.miss() <= 1.0, aim.toString());
        assertTrue(aim.pitch() < 30f, "looking straight at the water would hit the railing: " + aim);
        double straight = ThrowPlanner.landingMiss(0.5, 175.62, 0.5, 0f, 51f, 0, water, w);
        assertTrue(Double.isNaN(straight), "a steep throw hits the railing");
    }

    @Test
    void offCentreStandingStillHitsTheOneWideColumn() {
        World w = walkwayWithRailing();
        ThrowPlanner.Aim aim = ThrowPlanner.plan(0.8, 175.62, 0.3, new Pos(0, 170, 4), w);
        assertNotNull(aim);
        assertTrue(aim.yaw() > 0f && aim.yaw() < 10f, "turned slightly west: " + aim);
        for (double jitter : new double[]{-0.1, 0, 0.1})
            assertTrue(ThrowPlanner.landingMiss(0.8, 175.62, 0.3, aim.yaw(), aim.pitch(), jitter, new Pos(0, 170, 4), w) < 2.5);
    }

    @Test
    void yawPointsAtTheTarget() {
        World w = new World();
        for (int x = -5; x <= -1; x++) w.water.add(x + ",72,0");
        ThrowPlanner.Aim west = ThrowPlanner.plan(0.5, 75.62, 0.5, new Pos(-3, 72, 0), w);
        assertNotNull(west);
        assertEquals(90f, west.yaw(), 0.01f);
        World n = new World();
        for (int z = -5; z <= -1; z++) n.water.add("0,72," + z);
        ThrowPlanner.Aim north = ThrowPlanner.plan(0.5, 75.62, 0.5, new Pos(0, 72, -3), n);
        assertNotNull(north);
        assertEquals(180f, Math.abs(north.yaw()), 0.01f);
    }

    @Test
    void noAimWhenAWallBlocksEveryPath() {
        World w = new World();
        for (int x = -3; x <= 3; x++) for (int y = 60; y <= 90; y++) w.solid.add(x + "," + y + ",2");
        for (int z = 3; z <= 6; z++) w.water.add("0,72," + z);
        assertNull(ThrowPlanner.plan(0.5, 75.62, 0.5, new Pos(0, 72, 4), w));
    }

    @Test
    void targetOutOfThrowingRangeIsRejected() {
        World w = new World();
        w.water.add("0,72,30");
        assertNull(ThrowPlanner.plan(0.5, 75.62, 0.5, new Pos(0, 72, 30), w));
    }
}
