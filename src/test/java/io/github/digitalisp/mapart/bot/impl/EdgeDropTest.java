package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EdgeDrop} on the live 2026-10-13 geometry in base-relative coordinates (testserver/examples/
 * site-config.soulsand.json: the tile's west edge column x -320, soul sand floor y172, art layer y173): west of the edge
 * a one-block gap at x -321 over an item water channel (water y170-172, its bottom y169), further west the parked clear
 * machine's no-go zone (x -328..-321, y173..178).
 */
class EdgeDropTest {
    static final int E = -320;

    /** The platform edge: soul sand floor x E..E+20 at y172, the channel at x E-1, solid ground west of it at y172. */
    static final class World implements EdgeDrop.Cells {
        final Map<Pos, BlockSpec> blocks = new HashMap<>();
        final Set<Pos> water = new HashSet<>();
        final Set<Pos> unloaded = new HashSet<>();
        final Set<Pos> noGo = new HashSet<>();

        World() {
            for (int x = E - 8; x <= E + 20; x++)
                for (int z = -10; z <= 10; z++) {
                    if (x == E - 1) {
                        for (int y = 170; y <= 172; y++) water.add(new Pos(x, y, z));
                        blocks.put(new Pos(x, 169, z), BlockSpec.of("minecraft:stone"));
                    } else {
                        blocks.put(new Pos(x, 172, z), BlockSpec.of("minecraft:soul_sand"));
                    }
                }
        }

        @Override public boolean loaded(Pos p) { return !unloaded.contains(p); }
        @Override public BlockSpec block(Pos p) { return blocks.getOrDefault(p, BlockSpec.AIR); }
        @Override public boolean fluid(Pos p) { return water.contains(p); }

        @Override
        public double collisionTop(BlockSpec b) {
            if (b == null || b.isAir()) return 0;
            if (b.id().endsWith("soul_sand")) return 0.875;
            if (b.id().endsWith("_carpet")) return 1.0 / 16;
            return 1.0;
        }

        @Override
        public boolean noGo(Pos feet) {
            return noGo.contains(feet) || noGo.contains(feet.above());
        }

        void zone(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) noGo.add(new Pos(x, y, z));
        }
    }

    @Test
    void theLastResortSpotOnTheEdgeColumnBordersTheWaterChannel() {
        World w = new World();
        assertEquals("-321,173,2: water or lava 1 below", EdgeDrop.beside(new Pos(E, 173, 2), w),
            "the west neighbor, a side one: side neighbors come first");
        // with the clear machine's zone over the gap, the zone is named
        w.zone(E - 8, 173, -10, E - 1, 178, 10);
        assertEquals("-321,173,2: no-go zone", EdgeDrop.beside(new Pos(E, 173, 2), w));
    }

    @Test
    void oneCellInFromTheEdgeIsNoHazard() {
        World w = new World();
        assertNull(EdgeDrop.beside(new Pos(E + 1, 173, 2), w), "x E+1: all eight neighbors are floor");
        assertNull(EdgeDrop.beside(new Pos(E + 10, 173, 0), w));
    }

    @Test
    void aStepDownIsNoHazardTwoBlocksAre() {
        World w = new World();
        Pos feet = new Pos(E + 10, 173, 0);
        // one block lower east of it: a normal step down
        for (int z = -1; z <= 1; z++) {
            w.blocks.remove(new Pos(E + 11, 172, z));
            w.blocks.put(new Pos(E + 11, 171, z), BlockSpec.of("minecraft:stone"));
        }
        assertNull(EdgeDrop.beside(feet, w));
        // two blocks lower: a hazard
        for (int z = -1; z <= 1; z++) {
            w.blocks.remove(new Pos(E + 11, 171, z));
            w.blocks.put(new Pos(E + 11, 170, z), BlockSpec.of("minecraft:stone"));
        }
        assertEquals("-309,173,0: a drop of 2 blocks", EdgeDrop.beside(feet, w));
        // nothing within three blocks: a drop of more
        for (int z = -1; z <= 1; z++) w.blocks.remove(new Pos(E + 11, 170, z));
        assertEquals("-309,173,0: a drop of more than 3 blocks", EdgeDrop.beside(feet, w));
    }

    @Test
    void wallsAndCarpetsAreNoHazardUnloadedIs() {
        World w = new World();
        Pos feet = new Pos(E, 173, 2);
        // a full block wall over the gap at feet height, and one at head height: the bot cannot move in
        for (int z = 1; z <= 3; z++) w.blocks.put(new Pos(E - 1, 173, z), BlockSpec.of("minecraft:stone"));
        assertNull(EdgeDrop.beside(feet, w));
        for (int z = 1; z <= 3; z++) {
            w.blocks.remove(new Pos(E - 1, 173, z));
            w.blocks.put(new Pos(E - 1, 174, z), BlockSpec.of("minecraft:stone"));
        }
        assertNull(EdgeDrop.beside(feet, w));
        // a carpet on the art layer next to the bot: something to stand on
        World w2 = new World();
        w2.blocks.put(new Pos(E + 3, 173, 1), BlockSpec.of("minecraft:white_carpet"));
        assertNull(EdgeDrop.beside(new Pos(E + 2, 173, 1), w2));
        w2.unloaded.add(new Pos(E + 12, 173, 0));
        assertTrue(EdgeDrop.beside(new Pos(E + 11, 173, 0), w2).endsWith("not loaded"));
    }

    @Test
    void diagonalNeighboursCount() {
        World w = new World();
        // the gap only at z 5 (the rest of the column is floor): the cell diagonally next to it borders it
        for (int z = -10; z <= 10; z++) {
            if (z == 5) continue;
            for (int y = 170; y <= 172; y++) w.water.remove(new Pos(E - 1, y, z));
            w.blocks.put(new Pos(E - 1, 172, z), BlockSpec.of("minecraft:soul_sand"));
        }
        assertEquals("-321,173,5: water or lava 1 below", EdgeDrop.beside(new Pos(E, 173, 4), w));
        assertNull(EdgeDrop.beside(new Pos(E, 173, 2), w));
    }

    // ---- edge descents (EdgeDrop.descent): live 2026-10-17, Galileo [7,5], lane 0

    /** Full blocks of the art on the column one in from the edge (x E+1, z 0..8), as the green terracotta was. */
    private static World withWallOneIn() {
        World w = new World();
        for (int z = 0; z <= 8; z++) w.blocks.put(new Pos(E + 1, 173, z), BlockSpec.of("minecraft:green_terracotta"));
        return w;
    }

    private static Pos p(int x, int y, int z) {
        return new Pos(x, y, z);
    }

    @Test
    void theLiveStepDownWestOntoTheEdgeColumnIsRefused() {
        World w = withWallOneIn();
        // on top of the terracotta at x E+1 (feet y174), one step west down onto the edge column: the gap is straight ahead
        List<Pos> path = List.of(p(E + 1, 174, 4), p(E, 173, 4), p(E, 173, 5));
        EdgeDrop.Descent d = EdgeDrop.descent(path, w, c -> true);
        assertNotNull(d);
        assertEquals(p(E + 1, 174, 4), d.from());
        assertEquals(p(E, 173, 4), d.to());
        assertEquals(p(E - 1, 173, 4), d.toward());
        assertEquals("water or lava 1 below", d.hazard());
        // live, the gap lies in the west station's zone: the zone is named first
        w.zone(E - 8, 173, -10, E - 1, 178, 10);
        assertEquals("no-go zone", EdgeDrop.descent(path, w, c -> true).hazard());
    }

    @Test
    void aStepDownAlongTheEdgeOrAwayFromItIsFine() {
        World w = withWallOneIn();
        // along the edge column: down from a block on the column itself, south
        w.blocks.put(new Pos(E, 173, 0), BlockSpec.of("minecraft:green_terracotta"));
        assertNull(EdgeDrop.descent(List.of(p(E, 174, 0), p(E, 173, 1), p(E, 173, 2)), w, c -> true));
        // east, away from the gap
        assertNull(EdgeDrop.descent(List.of(p(E + 1, 174, 4), p(E + 2, 173, 4)), w, c -> true));
        // west one cell further in: the bot lands on the edge column, beside the gap (the edge sneak's case)
        for (int z = 0; z <= 8; z++) w.blocks.put(new Pos(E + 2, 173, z), BlockSpec.of("minecraft:green_terracotta"));
        assertNull(EdgeDrop.descent(List.of(p(E + 3, 174, 4), p(E + 2, 174, 4), p(E + 1, 174, 4)), w, c -> true),
            "level on top of the blocks");
        w.blocks.remove(new Pos(E + 1, 173, 4));
        assertNull(EdgeDrop.descent(List.of(p(E + 2, 174, 4), p(E + 1, 173, 4)), w, c -> true));
        // level and up: no descent
        assertNull(EdgeDrop.descent(List.of(p(E, 173, 9), p(E + 1, 174, 8)), withWallOneIn(), c -> true));
        assertNull(EdgeDrop.descent(List.of(p(E + 1, 174, 4)), w, c -> true));
    }

    @Test
    void aDiagonalStepDownCountsItsSides() {
        World w = withWallOneIn();
        // south-west down onto the edge column: straight ahead (E-1, z+2) and the side west (E-1, z+1) are the gap
        EdgeDrop.Descent d = EdgeDrop.descent(List.of(p(E + 1, 174, 3), p(E, 173, 4)), w, c -> true);
        assertNotNull(d);
        assertEquals(p(E - 1, 173, 5), d.toward(), "straight ahead first");
        // the gap only at z 4: the side cell alone
        World one = withWallOneIn();
        for (int z = -10; z <= 10; z++) {
            if (z == 4) continue;
            for (int y = 170; y <= 172; y++) one.water.remove(new Pos(E - 1, y, z));
            one.blocks.put(new Pos(E - 1, 172, z), BlockSpec.of("minecraft:soul_sand"));
        }
        assertEquals(p(E - 1, 173, 4), EdgeDrop.descent(List.of(p(E + 1, 174, 3), p(E, 173, 4)), one, c -> true).toward());
    }

    @Test
    void offWhereItDoesNotApplyAndNeverForUnloadedCells() {
        World w = withWallOneIn();
        List<Pos> path = List.of(p(E + 1, 174, 4), p(E, 173, 4));
        assertNull(EdgeDrop.descent(path, w, c -> false), "build.edgeDescents off, or a stair box");
        assertNull(EdgeDrop.descent(path, w, c -> !c.equals(p(E, 173, 4))), "only the move's end counts");
        w.unloaded.add(p(E - 1, 173, 4));
        assertNull(EdgeDrop.descent(path, w, c -> true), "the pathfinder plans in loaded cells only");
    }

    /** Live 2026-11-23: the chase's path stepped off the walkway and fell 112 blocks into water. */
    @Test
    void aPlannedFallOfMoreThanThreeBlocksIsFound() {
        List<Pos> path = List.of(new Pos(-76, 174, 4), new Pos(-77, 174, 5), new Pos(-78, 62, 8), new Pos(-77, 63, 8));
        EdgeDrop.Fall f = EdgeDrop.longFall(path, EdgeDrop.MAX_PLANNED_FALL);
        assertNotNull(f);
        assertEquals(new Pos(-77, 174, 5), f.from());
        assertEquals(new Pos(-78, 62, 8), f.to());
        assertEquals(112, f.blocks());
    }

    /** The pathfinder's own falls onto a floor (3 blocks), a climb down a ladder and a climb up are no long fall. */
    @Test
    void shortFallsLaddersAndClimbsAreNoLongFall() {
        assertNull(EdgeDrop.longFall(List.of(new Pos(0, 176, 0), new Pos(1, 173, 0)), EdgeDrop.MAX_PLANNED_FALL));
        assertNotNull(EdgeDrop.longFall(List.of(new Pos(0, 176, 0), new Pos(1, 172, 0)), EdgeDrop.MAX_PLANNED_FALL));
        assertNull(EdgeDrop.longFall(List.of(new Pos(0, 176, 0), new Pos(0, 175, 0), new Pos(0, 174, 0),
            new Pos(0, 173, 0), new Pos(0, 172, 0), new Pos(0, 171, 0)), EdgeDrop.MAX_PLANNED_FALL));
        assertNull(EdgeDrop.longFall(List.of(new Pos(0, 62, 0), new Pos(0, 176, 0)), EdgeDrop.MAX_PLANNED_FALL));
        assertNull(EdgeDrop.longFall(List.of(new Pos(0, 62, 0)), EdgeDrop.MAX_PLANNED_FALL));
    }
}
