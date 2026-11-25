package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hole guard ({@code build.edgeHoles}) on the two live 2026-10-28 incidents (soul sand, tile-relative, blocks from
 * the replay's chunk data):
 * <ul>
 * <li><b>The trench fall</b> (11:35:24, [3,4] NUKE leftovers): the soul sand floor (y172, feet level 173) ends at x 127;
 * the item trench x 128 is water at y170 over glass y169, and in the bot's row z 4 a dead coral wall fan (dry) at y172
 * and a ladder (facing west, a plate on the trench's east wall) at y171. The bot stood still at feet point (127.51,
 * 4.50); the walk's first tick jumped east (the replay: 0.205, then 0.127, 0.108, 0.090 a tick in the air), the box came
 * down at x 128.33, wholly over the trench, and fell to y170.</li>
 * <li><b>The walk-off BLOCKED</b> (11:37-11:38): a 1-wide walkway beside the tile (floor y173, feet level 174) runs south
 * from row z -1 through cell (138, 0), with drops of more than 3 at (137, 0) and (139, 0). The path's diagonal from
 * (139, -1) to (138, 0) passed the corner of (139, 0); the second corner fix made (138, 0) look far, and no later path
 * could get past it.</li>
 * </ul>
 */
class EdgeTrenchTest {
    static final int LEVEL = 173;
    static final int ROW = 4;
    static final double WALK = 0.098;
    static final double SOUL_SPEED = NukeGuard.accel(EdgeApproach.JUMP_TOP_SPEED);
    /** Yaw facing east (+x): forward is (1, 0). */
    static final double EAST = -90;
    /** Yaw facing north (-z). */
    static final double NORTH = 180;

    static final BlockSpec SOUL_SAND = BlockSpec.of("minecraft:soul_sand");
    static final BlockSpec GLASS = BlockSpec.of("minecraft:white_stained_glass");
    static final BlockSpec LADDER = BlockSpec.parse("minecraft:ladder[facing=west,waterlogged=false]");
    static final BlockSpec FAN = BlockSpec.parse("minecraft:dead_brain_coral_wall_fan[facing=west,waterlogged=false]");

    /** Blocks by position over the real block shapes ({@link BlockStates}), as the live {@link EdgeApproach#cells}. */
    static class World implements EdgeDrop.Cells, WorldView {
        final Map<Pos, BlockSpec> blocks = new HashMap<>();
        final Set<Pos> water = new HashSet<>();
        final int minX;
        final int maxX;
        final int minZ;
        final int maxZ;

        World(int minX, int maxX, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        @Override public boolean loaded(Pos p) { return p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ; }
        @Override public BlockSpec block(Pos p) { return blocks.getOrDefault(p, BlockSpec.AIR); }
        @Override public boolean fluid(Pos p) { return water.contains(p); }
        @Override public double collisionTop(BlockSpec b) { return b == null || b.isAir() ? 0 : BlockStates.collisionTop(b); }
        @Override public double floorTop(BlockSpec b) { return b == null || b.isAir() ? 0 : BlockStates.floorTop(b); }
        @Override public boolean noGo(Pos feet) { return false; }
        @Override public boolean isLoaded(Pos pos) { return loaded(pos); }
        @Override public boolean hasFluid(Pos pos) { return fluid(pos); }
        @Override public int viewDistance() { return 10; }
        @Override public double playerX() { return 0; }
        @Override public double playerY() { return 0; }
        @Override public double playerZ() { return 0; }
        @Override public Pos playerBlock() { return new Pos(0, 0, 0); }
    }

    /** The east edge and the trench, x 118..129, z 0..9. */
    static World trench() {
        World w = new World(118, 129, 0, 9);
        for (int z = 0; z <= 9; z++) {
            for (int x = 118; x <= 127; x++) w.blocks.put(new Pos(x, 172, z), SOUL_SAND);
            w.blocks.put(new Pos(127, 170, z), GLASS);
            w.blocks.put(new Pos(128, 169, z), GLASS);
            for (int y = 169; y <= 172; y++) w.blocks.put(new Pos(129, y, z), GLASS);
            w.blocks.put(new Pos(129, 173, z), BlockSpec.of("minecraft:obsidian"));
            for (int y = 170; y <= 172; y++) w.water.add(new Pos(128, y, z));
        }
        // the bot's row: dry above the water, a ladder 2 below the rim
        w.water.remove(new Pos(128, 172, ROW));
        w.water.remove(new Pos(128, 171, ROW));
        w.blocks.put(new Pos(128, 172, ROW), FAN);
        w.blocks.put(new Pos(128, 171, ROW), LADDER);
        return w;
    }

    @Test
    void aLadderOnTheTrenchsFarWallIsNoFloor() {
        assertEquals(1.0, BlockStates.collisionTop(LADDER), 1e-9, "a ladder has a collision box to the top");
        assertEquals(0.0, BlockStates.floorTop(LADDER), 1e-9, "but only a plate on its wall: nothing under the center");
        assertEquals(1.0, BlockStates.floorTop(SOUL_SAND), 0.2);
        assertEquals(0.0625, BlockStates.floorTop(BlockSpec.of("minecraft:white_carpet")), 1e-9);
        assertTrue(BlockStates.floorTop(BlockSpec.parse("minecraft:oak_fence[east=false,north=false,south=false,"
            + "waterlogged=false,west=false]")) > 1, "a fence post holds the center");

        World w = trench();
        Pos cell = new Pos(128, LEVEL, ROW);
        assertEquals("water or lava 3 below", EdgeDrop.holeAt(cell, w), "the trench cell of the bot's row is a hole");
        assertEquals("water or lava 1 below", EdgeDrop.holeAt(new Pos(128, LEVEL, ROW - 1), w));
        assertNull(EdgeDrop.holeAt(new Pos(127, LEVEL, ROW), w), "the edge column is floor");

        // live (b36c6d5): the ladder counted as a floor 2 below, a 1-block step down: no hole, no drop
        EdgeDrop.Cells old = new World(118, 129, 0, 9) {
            { blocks.putAll(w.blocks); water.addAll(w.water); }
            @Override public double floorTop(BlockSpec b) { return collisionTop(b); }
        };
        assertNull(EdgeDrop.holeAt(cell, old));
        assertNull(EdgeDrop.at(cell, old));
        assertEquals("water or lava 3 below", EdgeDrop.at(cell, w));
    }

    @Test
    void theJumpEastFromTheEdgeColumnIsTakenAway() {
        World w = trench();
        double fx = 127.509;
        double fz = 4.496;
        // the ground model alone lets the first tick's keys through (still, the walk's speed not known yet)...
        assertNull(EdgeApproach.guard(fx, fz, LEVEL, 0, 0, WALK, EAST, 1, 0, c -> EdgeDrop.holeAt(c, w)),
            "walking east from still stops 0.22 later, short of the rim's margin");
        // ...and the jump of that tick flies the feet point into the trench's cell
        assertEquals("128,173,4: water or lava 3 below",
            EdgeApproach.jumpWhy(fx, fz, LEVEL, 0, 0, WALK, new double[]{1, 0}, false, c -> EdgeDrop.holeAt(c, w)));
        EdgeApproach.Guard g = EdgeApproach.guard(fx, fz, LEVEL, 0, 0, SOUL_SPEED, EAST, 1, 0, true, false,
            c -> EdgeDrop.holeAt(c, w));
        assertNotNull(g);
        assertTrue(g.noJump(), "the jump is taken away");
        assertTrue(g.keys());
        assertTrue(g.why().endsWith("; no jump"), g.why());
        // the turned keys (live: 45 degrees off the trench's water cell) jump no better: every key within 50 degrees of
        // east still goes east
        for (int s = -1; s <= 1; s += 2) {
            double[] k = Aim.keysToWorld(EAST, 1, s);
            double l = Math.hypot(k[0], k[1]);
            assertNotNull(EdgeApproach.jumpWhy(fx, fz, LEVEL, 0, 0, WALK, new double[]{k[0] / l, k[1] / l}, false,
                c -> EdgeDrop.holeAt(c, w)), "forward and strafe " + s);
        }
    }

    @Test
    void theFlightModelGoesAsFarAsTheReplaysTrack() {
        // the replay: x 127.509 still, then 127.714, 127.841, 127.949, 128.039 (top of the jump) and 128.26 four ticks on;
        // the guard's model with the first tick's 0.205 must reach the trench cell no later than the bot did
        World w = trench();
        double[] east = {1, 0};
        assertNotNull(EdgeApproach.jumpWhy(127.509, 4.496, LEVEL, 0, 0, 0.205, east, false, c -> EdgeDrop.holeAt(c, w)));
        // the model holds the keys all the way (Baritone does) and lands at the feet level: 2.35 blocks with 0.205, more
        // than the bot flew with the guard's keys; from 1.5 blocks in that is still refused (on the safe side)...
        assertNotNull(EdgeApproach.jumpWhy(126.3, 4.496, LEVEL, 0, 0, 0.205, east, false, c -> EdgeDrop.holeAt(c, w)));
        // ...from 2.2 blocks in it lands short of the rim's margin: kept
        assertNull(EdgeApproach.jumpWhy(125.3, 4.496, LEVEL, 0, 0, 0.205, east, false, c -> EdgeDrop.holeAt(c, w)));
        assertNull(EdgeApproach.guard(125.3, 4.496, LEVEL, 0, 0, 0.205, EAST, 1, 0, true, false,
            c -> EdgeDrop.holeAt(c, w)));
    }

    @Test
    void aJumpAlongTheEdgeOrAwayFromItIsKept() {
        World w = trench();
        assertNull(EdgeApproach.guard(127.5, 8.5, LEVEL, 0, 0, SOUL_SPEED, NORTH, 1, 0, true, false,
            c -> EdgeDrop.holeAt(c, w)), "north along the edge column");
        assertNull(EdgeApproach.guard(127.5, 4.5, LEVEL, 0, 0, SOUL_SPEED, 90, 1, 0, true, false,
            c -> EdgeDrop.holeAt(c, w)), "west, away from the trench");
        // a sprint jump north along the edge, already at speed: kept
        assertNull(EdgeApproach.guard(127.5, 8.5, LEVEL, 0, -0.3, SOUL_SPEED, NORTH, 1, 0, true, true,
            c -> EdgeDrop.holeAt(c, w)));
        // no jump wanted: as before
        EdgeApproach.Guard plain = EdgeApproach.guard(127.5, 4.5, LEVEL, 0, 0, SOUL_SPEED, 90, 1, 0, false, false,
            c -> EdgeDrop.holeAt(c, w));
        assertNull(plain);
    }

    @Test
    void aSprintFromStillOnSoulSandIsGuardedFromTheFirstTick() {
        // local drill 2026-10-28: the slab rows of the trench (a top slab at y171, water on it at y172)
        World w = trench();
        w.water.remove(new Pos(128, 171, 3));
        w.blocks.put(new Pos(128, 171, 3), BlockSpec.parse("minecraft:smooth_stone_slab[type=top,waterlogged=false]"));
        assertEquals("water or lava 1 below", EdgeDrop.holeAt(new Pos(128, LEVEL, 3), w));
        java.util.function.Function<Pos, String> hole = c -> EdgeDrop.holeAt(c, w);

        // still on the edge column, facing east, the walk's first tick sprinted on Soul Speed: 0.29 that tick (0.222 x 1.3)
        double first = EdgeApproach.groundAccel(0, 0, true, true);
        assertEquals(0.289, first, 0.002);
        assertNotNull(NukeGuard.slide(127.5, 3.5, LEVEL, 0, 0, first, new double[]{1, 0}, hole, true),
            "that tick and its slide take the feet point into the trench's cell");
        // 8cd1732 played the first tick at walking's 0.098 and let it through; the feet point went 0.18 into the cell
        assertNull(EdgeApproach.guard(127.5, 3.5, LEVEL, 0, 0, WALK, EAST, 1, 0, false, true, hole));
        for (int row : new int[]{3, ROW}) {
            EdgeApproach.Guard g = EdgeApproach.guard(127.5, row + 0.5, LEVEL, 0, 0, first, EAST, 1, 0, false, true, hole);
            assertNotNull(g, "row " + row);
            assertTrue(g.keys());
            if (g.forward() != 0 || g.strafe() != 0) {
                double[] k = Aim.keysToWorld(EAST, g.forward(), g.strafe());
                assertNull(NukeGuard.slide(127.5, row + 0.5, LEVEL, 0, 0, first, unit(k), hole, true), "row " + row);
            }
        }
        // the second tick (drill trace): Baritone's yaw already south-west (45), the bot still facing east, moving 0.157
        // east at 127.79: the move may still be made facing east, so the keys are held until the turn is made
        double a2 = EdgeApproach.groundAccel(0.29, 0.157, true, true);
        EdgeApproach.Guard atBody = EdgeApproach.guard(127.79, 3.5, LEVEL, 0.157, 0, a2, EAST, 1, 0, false, true, hole);
        assertNotNull(atBody, "facing east, the keys go into the trench's cell");
        EdgeApproach.Guard atTarget = EdgeApproach.guard(127.79, 3.5, LEVEL, 0.157, 0, a2, 45, 1, 0, false, true, hole);
        EdgeApproach.Guard t = EdgeApproach.whileTurning(atTarget, atBody);
        assertNotNull(t);
        assertTrue(t.keys());
        assertEquals(0, t.forward());
        assertEquals(0, t.strafe());
        assertTrue(t.why().endsWith("; turning, held"), t.why());
        assertNull(EdgeApproach.whileTurning(null, null));
        EdgeApproach.Guard sneakOnly = new EdgeApproach.Guard(1, 0, false, true, "in it");
        assertSame(sneakOnly, EdgeApproach.whileTurning(sneakOnly, null), "a sneak alone is kept as it is");

        // the floor's least push: walking 0.098 off Soul Speed's floors, x1.3 sprinting; a known faster walk wins
        assertEquals(0.098, EdgeApproach.groundAccel(0, 0, false, false), 1e-9);
        assertEquals(0.1274, EdgeApproach.groundAccel(0, 0, false, true), 1e-9);
        assertEquals(NukeGuard.accel(EdgeApproach.JUMP_TOP_SPEED), EdgeApproach.groundAccel(0, 0, true, false), 1e-9);
        assertEquals(NukeGuard.accel(0.6), EdgeApproach.groundAccel(0.6, 0, true, false), 1e-9);
    }

    // ---- the walk-off: corner fixes never wall off the only way

    static final int WALK_LEVEL = 174;

    /** The walkway: row z -1 from x 134 to 142, and the cell (138, 0) south of it; open around, down to y168 at least. */
    static World walkway() {
        World w = new World(130, 146, -6, 6);
        for (int x = 134; x <= 142; x++) w.blocks.put(new Pos(x, 173, -1), BlockSpec.of("minecraft:stone"));
        for (int z = 0; z <= 6; z++) w.blocks.put(new Pos(138, 173, z), BlockSpec.of("minecraft:stone"));
        return w;
    }

    @Test
    void aSecondHoleCornerIsWalkedUnderTheGuardNotPlannedAround() {
        World w = walkway();
        assertEquals("a drop of more than 3 blocks", EdgeDrop.holeAt(new Pos(139, WALK_LEVEL, 0), w));
        assertEquals("a drop of more than 3 blocks", EdgeDrop.holeAt(new Pos(137, WALK_LEVEL, 0), w));
        EdgeApproach e = new EdgeApproach(() -> true, p -> false, p -> true, NoGoZones.NONE, w);
        List<Pos> first = List.of(new Pos(137, WALK_LEVEL, -1), new Pos(138, WALK_LEVEL, 0), new Pos(138, WALK_LEVEL, 1));
        NoGoZones.Violation v1 = e.corner(first);
        assertNotNull(v1);
        assertTrue(e.ownsCorner(v1));
        NoGoZones.CornerFix f1 = e.cornerFix(v1, Set.of(), false, c -> false);
        assertNotNull(f1.via(), "first: through the move's free side cell (138, -1)");
        assertEquals(new GoalNearXZ(138, WALK_LEVEL, -1, 0, 1), f1.via());
        assertNull(f1.avoid());

        // live: the bot came to (139, -1) and the next path cut the other corner, to the walkway's only cell
        List<Pos> second = List.of(new Pos(139, WALK_LEVEL, -1), new Pos(138, WALK_LEVEL, 0), new Pos(138, WALK_LEVEL, 1));
        NoGoZones.Violation v2 = e.corner(second);
        assertNotNull(v2);
        NoGoZones.CornerFix f2 = e.cornerFix(v2, Set.of(), true, c -> false);
        assertSame(EdgeApproach.WAIVE, f2, "no cell made to look far: (138, 0) is the only way on");
        assertNull(f2.avoid());
        assertNull(f2.via());
        assertNull(e.corner(second), "the rest of this walk takes the move; the per-tick guard keeps the feet out");
        assertNull(e.corner(first));

        // the guard on that move: from (139.2, -0.4) the keys south-west are turned or held, never into (139, 0)
        EdgeApproach.Guard g = EdgeApproach.guard(139.2, -0.4, WALK_LEVEL, 0, 0, WALK, 45, 1, 0,
            c -> EdgeDrop.holeAt(c, w));
        if (g != null && g.keys()) {
            double[] k = Aim.keysToWorld(45, g.forward(), g.strafe());
            assertNull(NukeGuard.slide(139.2, -0.4, WALK_LEVEL, 0, 0, WALK, unit(k), c -> EdgeDrop.holeAt(c, w), true));
        }

        e.reset(); // a new walk plans around corners again
        assertNotNull(e.corner(second));
        assertFalse(e.cornerFix(e.corner(first), Set.of(), false, c -> false).via() == null);
    }

    private static double[] unit(double[] w) {
        double l = Math.hypot(w[0], w[1]);
        return l < 1e-9 ? new double[]{0, 0} : new double[]{w[0] / l, w[1] / l};
    }
}
