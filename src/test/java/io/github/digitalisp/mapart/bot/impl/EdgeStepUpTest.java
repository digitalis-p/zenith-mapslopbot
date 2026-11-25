package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hole guard's jumps over the blocks in the box's way. Live 2026-10-29 15:39-15:41 (the walk to
 * the map station after a tile): Baritone's ascend, a 1-block step up beside a drop of more than 3 blocks. The bot stood
 * still (0.00 blocks a tick) with the forward key; the guard took the jump away every time ("the keys (1, 0) would take
 * the feet into …: a drop of more than 3 blocks; no jump"), Baritone's ascend timed out, the walk stalled 4 times and
 * the "No progress" alert said the cause was not known. The flight was played at the start's feet level through the
 * step's block and on into the drop beyond it.
 *
 * <p>Geometry (local cells): a stone floor y172 (feet level 173) for x 0..4, a step block at (4, 173, 2), nothing at all
 * for x 5 and beyond (a drop of more than 3 at both feet levels). The bot stands at (3.50, 2.70), facing east.
 */
class EdgeStepUpTest {
    static final int LEVEL = 173;
    static final double SOUL_SPEED = NukeGuard.accel(EdgeApproach.JUMP_TOP_SPEED);
    static final double EAST = -90;
    static final BlockSpec STONE = BlockSpec.of("minecraft:stone");

    static EdgeTrenchTest.World stepBesideDrop() {
        EdgeTrenchTest.World w = new EdgeTrenchTest.World(-2, 12, -2, 8);
        for (int x = -2; x <= 4; x++) for (int z = -2; z <= 8; z++) w.blocks.put(new Pos(x, 172, z), STONE);
        w.blocks.put(new Pos(4, LEVEL, 2), STONE);
        return w;
    }

    static Function<Pos, String> holes(EdgeTrenchTest.World w) {
        return c -> EdgeDrop.holeAt(c, w);
    }

    @Test
    void theGeometryIsTheLiveOne() {
        EdgeTrenchTest.World w = stepBesideDrop();
        assertEquals("a drop of more than 3 blocks", EdgeDrop.holeAt(new Pos(5, LEVEL, 2), w));
        assertEquals("a drop of more than 3 blocks", EdgeDrop.holeAt(new Pos(5, LEVEL + 1, 2), w), "beyond the step too");
        assertNull(EdgeDrop.holeAt(new Pos(4, LEVEL, 2), w), "the step's block");
        assertNull(EdgeDrop.holeAt(new Pos(4, LEVEL + 1, 2), w), "on the step");
        assertNull(EdgeDrop.holeAt(new Pos(4, LEVEL, 3), w), "beside the step: the floor below");
    }

    @Test
    void aStillAscendBesideTheDropWasRefusedAndIsNowAllowed() {
        EdgeTrenchTest.World w = stepBesideDrop();
        // before the fix (no blocks in the flight): the jump is taken away, the live line
        EdgeApproach.Guard old = EdgeApproach.guard(3.5, 2.7, LEVEL, 0, 0, SOUL_SPEED, EAST, 1, 0, true, false, holes(w));
        assertNotNull(old);
        assertTrue(old.noJump());
        assertTrue(old.why().startsWith("5,173,2: a drop of more than 3 blocks"), old.why());
        // with the step's block: the box stops at its face until a block up, lands on it and stays on it
        assertNull(EdgeApproach.guard(3.5, 2.7, LEVEL, 0, 0, SOUL_SPEED, EAST, 1, 0, true, false, holes(w),
            EdgeApproach.solid(w)), "still, forward and jump: the ascend is kept");
        assertNull(EdgeApproach.jumpWhy(3.5, 2.7, LEVEL, 0, 0, SOUL_SPEED, new double[]{1, 0}, false, holes(w),
            EdgeApproach.solid(w)));
        // the first live refusal came at 0.19 blocks a tick toward the step: the face stops that too
        assertNull(EdgeApproach.guard(3.5, 2.7, LEVEL, 0.19, 0, SOUL_SPEED, EAST, 1, 0, true, false, holes(w),
            EdgeApproach.solid(w)));
        // a sprinting ascend from still, and one from any point of the cell's east half
        assertNull(EdgeApproach.guard(3.5, 2.7, LEVEL, 0, 0, SOUL_SPEED, EAST, 1, 0, true, true, holes(w),
            EdgeApproach.solid(w)));
        for (double x = 3.5; x <= 3.7; x += 0.05) {
            for (double z = 2.3; z <= 2.7; z += 0.1) {
                assertNull(EdgeApproach.guard(x, z, LEVEL, 0, 0, SOUL_SPEED, EAST, 1, 0, true, false, holes(w),
                    EdgeApproach.solid(w)), x + ", " + z);
            }
        }
    }

    @Test
    void theStepsBlockStopsTheBoxUntilItIsABlockUp() {
        // the flight's x a tick from still: at the face (feet 3.70) for the first ticks, then over the step, landing on
        // it short of its east edge (the drop): the feet point never leaves the step's cell
        EdgeTrenchTest.World w = stepBesideDrop();
        double[] reached = {0};
        Function<Pos, String> spy = c -> {
            reached[0] = Math.max(reached[0], c.x());
            return EdgeDrop.holeAt(c, w);
        };
        assertNull(EdgeApproach.jumpWhy(3.5, 2.5, LEVEL, 0, 0, SOUL_SPEED, new double[]{1, 0}, false, spy,
            EdgeApproach.solid(w)));
        assertTrue(reached[0] <= 4, "no cell beyond the step looked at: " + reached[0]);
    }

    /**
     * The 1-wide glass stairs of the 5.49 drill (et.rcon, tile-relative x 138): a block at y n and n+1 in each column z,
     * one up a cell south, drops of more than 3 on both sides (x 137 and 139). et2 logged "no jump" there from
     * (138.40, 1.17) at 0.12 blocks a tick ("… into 139,175,3") and from (138.47, 2.63) ("… into 138,176,6": the hole
     * under the stairs ahead).
     */
    @Test
    void theGlassStairsAscendsAreKept() {
        EdgeTrenchTest.World w = new EdgeTrenchTest.World(130, 146, -4, 12);
        w.blocks.put(new Pos(138, 173, 0), BlockSpec.of("minecraft:glass"));
        for (int z = 1; z <= 6; z++) {
            w.blocks.put(new Pos(138, 172 + z, z), BlockSpec.of("minecraft:glass"));
            w.blocks.put(new Pos(138, 173 + z, z), BlockSpec.of("minecraft:glass"));
        }
        double south = 0;
        assertEquals("a drop of more than 3 blocks", EdgeDrop.holeAt(new Pos(139, 175, 3), w));
        // from (138.5, 0.5), feet 174, onto z 1 (feet 175); from (138.4, 1.17) at 0.12 south, feet 175, onto z 2
        // (straight south the flight without blocks passed here too; et2's refusals came at Baritone's yaw a little off
        // south, which the step's block now stops as well)
        for (double yaw = -8; yaw <= 8; yaw += 4) {
            assertNull(EdgeApproach.guard(138.40, 1.17, 175, 0, 0.12, SOUL_SPEED, yaw, 1, 0, true, false, holes(w),
                EdgeApproach.solid(w)), "yaw " + yaw);
        }
        assertNull(EdgeApproach.guard(138.5, 0.5, 174, 0, 0, SOUL_SPEED, south, 1, 0, true, false, holes(w),
            EdgeApproach.solid(w)));
        assertNull(EdgeApproach.guard(138.40, 1.17, 175, 0, 0.12, SOUL_SPEED, south, 1, 0, true, false, holes(w),
            EdgeApproach.solid(w)));
        assertNull(EdgeApproach.guard(138.47, 2.63, 176, 0, 0.16, SOUL_SPEED, south, 1, 0, true, false, holes(w),
            EdgeApproach.solid(w)));
    }

    @Test
    void aJumpThatCarriesTheBoxOverTheHoleIsStillRefused() {
        EdgeTrenchTest.World w = stepBesideDrop();
        // a sprint jump at speed from 1.5 blocks back clears the step and flies on into the drop beyond it
        EdgeApproach.Guard g = EdgeApproach.guard(2.2, 2.5, LEVEL, 0.35, 0, SOUL_SPEED, EAST, 1, 0, true, true, holes(w),
            EdgeApproach.solid(w));
        assertNotNull(g);
        assertTrue(g.noJump(), g.why());
        assertTrue(g.why().startsWith("5,173,2: a drop of more than 3 blocks"), g.why());
        // a parkour jump over a 1-wide gap: the arc is over the hole's column
        EdgeTrenchTest.World gap = new EdgeTrenchTest.World(-2, 12, -2, 8);
        for (int x = -2; x <= 8; x++) {
            if (x == 4) continue;
            for (int z = -2; z <= 8; z++) gap.blocks.put(new Pos(x, 172, z), STONE);
        }
        EdgeApproach.Guard p = EdgeApproach.guard(3.5, 2.5, LEVEL, 0.28, 0, SOUL_SPEED, EAST, 1, 0, true, true,
            holes(gap), EdgeApproach.solid(gap));
        assertNotNull(p);
        assertTrue(p.keys(), "the keys are replaced: no jump that tick (applyToBaritone)");
        assertTrue(p.why().startsWith("4,173,2: a drop of more than 3 blocks"), p.why());
        assertEquals("4,173,2: a drop of more than 3 blocks", EdgeApproach.jumpWhy(3.5, 2.5, LEVEL, 0.28, 0, SOUL_SPEED,
            new double[]{1, 0}, true, holes(gap), EdgeApproach.solid(gap)), "the jump's own flight");
        // the live trench fall of 2026-10-28 (EdgeTrenchTest): from still on the rim toward the trench, with its blocks
        EdgeTrenchTest.World t = EdgeTrenchTest.trench();
        EdgeApproach.Guard r = EdgeApproach.guard(127.509, 4.496, EdgeTrenchTest.LEVEL, 0, 0, SOUL_SPEED, EAST, 1, 0,
            true, false, c -> EdgeDrop.holeAt(c, t), EdgeApproach.solid(t));
        assertNotNull(r);
        assertTrue(r.noJump());
        assertTrue(r.why().startsWith("128,173,4: water or lava 3 below"), r.why());
        // and the trench test's distances are the same with the blocks: 1.5 in refused, 2.2 in kept
        double[] east = {1, 0};
        assertNotNull(EdgeApproach.jumpWhy(126.3, 4.496, EdgeTrenchTest.LEVEL, 0, 0, 0.205, east, false,
            c -> EdgeDrop.holeAt(c, t), EdgeApproach.solid(t)));
        assertNull(EdgeApproach.jumpWhy(125.3, 4.496, EdgeTrenchTest.LEVEL, 0, 0, 0.205, east, false,
            c -> EdgeDrop.holeAt(c, t), EdgeApproach.solid(t)));
    }

    @Test
    void onCarpetsTheBoxIsNotHeldByItsOwnFloor() {
        // the art layer: carpets in the feet cells. A box on a carpet must not count the carpet as a wall (the flight
        // would stop at once and every jump would pass): toward a hole it is still refused
        EdgeTrenchTest.World w = stepBesideDrop();
        w.blocks.remove(new Pos(4, LEVEL, 2));
        for (int x = -2; x <= 4; x++) for (int z = -2; z <= 8; z++) {
            w.blocks.put(new Pos(x, LEVEL, z), BlockSpec.of("minecraft:white_carpet"));
        }
        EdgeApproach.Guard g = EdgeApproach.guard(4.4, 2.5, LEVEL, 0, 0, SOUL_SPEED, EAST, 1, 0, true, false, holes(w),
            EdgeApproach.solid(w));
        assertNotNull(g);
        assertTrue(g.noJump(), g.why());
    }

    @Test
    void aRefusalThatGoesOnFor5SecondsWhileStillHandsTheWalkBack() {
        EdgeApproach.RefusalWatch watch = new EdgeApproach.RefusalWatch();
        String why = "5,173,2: a drop of more than 3 blocks; no jump";
        long t = 1000;
        for (int i = 0; i < EdgeApproach.RefusalWatch.TICKS; i++) {
            assertNull(watch.tick(t + i, i % 3 == 0 ? why : null, 3.5, 2.7), "tick " + i);
        }
        assertEquals(why, watch.tick(t + EdgeApproach.RefusalWatch.TICKS, null, 3.5, 2.7), "5 s: handed back");
        assertTrue(EdgeApproach.RefusalWatch.TICKS < Walker.STALL_TICKS, "before the walk's own stall");
        // then the watch starts again
        assertNull(watch.tick(t + EdgeApproach.RefusalWatch.TICKS + 1, why, 3.5, 2.7));

        // a bot that moves on is not held: the run starts again where it is
        EdgeApproach.RefusalWatch moving = new EdgeApproach.RefusalWatch();
        for (int i = 0; i <= EdgeApproach.RefusalWatch.TICKS; i++) {
            assertNull(moving.tick(t + i, why, 3.5 + i * 0.02, 2.7), "moving, tick " + i);
        }
        // a gap of more than 2 s without a refusal ends the run (a single refusal on stairs, say)
        EdgeApproach.RefusalWatch gaps = new EdgeApproach.RefusalWatch();
        for (int i = 0; i <= 3 * EdgeApproach.RefusalWatch.TICKS; i++) {
            boolean refused = i % (EdgeApproach.RefusalWatch.GAP + 5) == 0;
            assertNull(gaps.tick(t + i, refused ? why : null, 3.5, 2.7), "gaps, tick " + i);
        }
        // a new walk resets it
        EdgeApproach.RefusalWatch reset = new EdgeApproach.RefusalWatch();
        for (int i = 0; i < EdgeApproach.RefusalWatch.TICKS - 1; i++) reset.tick(t + i, why, 3.5, 2.7);
        reset.reset();
        assertNull(reset.tick(t + EdgeApproach.RefusalWatch.TICKS, why, 3.5, 2.7));
    }

    @Test
    void theHandBackPlansAroundTheJumpOnceThenFails() {
        Pos feet = new Pos(3, LEVEL, 2);
        List<Pos> route = List.of(feet, new Pos(4, LEVEL + 1, 2), new Pos(5, LEVEL + 1, 2));
        Pos up = EdgeApproach.RefusalWatch.jumpEnd(route, feet);
        assertEquals(new Pos(4, LEVEL + 1, 2), up);
        assertNull(EdgeApproach.RefusalWatch.jumpEnd(List.of(feet, new Pos(4, LEVEL, 2)), feet), "no step up ahead");
        assertTrue(EdgeApproach.RefusalWatch.replan(false, up, 0, Walker.MAX_CORNER_DETOURS, feet, false), "first");
        assertFalse(EdgeApproach.RefusalWatch.replan(true, up, 1, Walker.MAX_CORNER_DETOURS, feet, false),
            "second: the walk fails, the caller's fallback runs");
        assertFalse(EdgeApproach.RefusalWatch.replan(false, null, 0, Walker.MAX_CORNER_DETOURS, feet, false), "no end");
        assertFalse(EdgeApproach.RefusalWatch.replan(false, up, 0, Walker.MAX_CORNER_DETOURS, feet, true), "the goal");
        assertFalse(EdgeApproach.RefusalWatch.replan(false, up, Walker.MAX_CORNER_DETOURS, Walker.MAX_CORNER_DETOURS,
            feet, false), "no detour left");
        assertEquals("on the way to the map station", Walker.plainWay("MAP_STATION"));
        assertEquals("on the way to a chest", Walker.plainWay("CONTAINER, restock"));
        assertEquals("on a walk", Walker.plainWay("WALK"));
    }
}
