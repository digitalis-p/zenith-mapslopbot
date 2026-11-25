package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;

/**
 * Stand cells only on floors ZenithProxy's pathfinder walks onto ({@code build.standSpotWalkable},
 * {@link StandSpots#walkableFloor}). Live 2026-10-23, soul sand: two walkway cells lie over decorated pots (collision
 * top 1, so {@link StandSpots#feetY} stands on them; {@code MovementHelper.canWalkOnBlockState} says no). The chests
 * beside them were approached from there 3.3 times a map, each a "No path found" and about 6 s before the next spot.
 * Tile-relative made-up geometry: a row of chests at z −5 beside a one-wide walkway at z −4.
 */
class StandSpotWalkableTest {
    private static final double REACH = 4.3;
    private static final int FLOOR = 63;
    /** A dry pot (a waterlogged one is "maybe": the pathfinder decides water by position). */
    private static final BlockSpec POT = BlockSpec.parse("minecraft:decorated_pot[waterlogged=false]");
    /** The two walkway cells over pots. */
    private static final List<Pos> POT_CELLS = List.of(new Pos(6, FLOOR + 1, -4), new Pos(7, FLOOR + 1, -4));
    /** The chest whose nearest stand cell from the west is over a pot. */
    private static final Pos CHEST = new Pos(10, FLOOR + 1, -5);

    private static ContainerSim walkway(boolean walkable) {
        ContainerSim s = new ContainerSim(23);
        s.fill(-4, FLOOR, -4, 20, FLOOR, -4, STONE);
        for (Pos p : POT_CELLS) s.set(p.below(), POT);
        for (int x = 0; x <= 16; x++) {
            s.set(x, FLOOR, -5, STONE);
            s.set(x, FLOOR + 1, -5, chest("south", "single"));
            s.set(x, FLOOR + 2, -5, chest("south", "single"));
        }
        // Baritone finds no path onto a pot
        s.unpathable.addAll(POT_CELLS);
        if (walkable) s.walksOn = BlockStates::pathfinderWalksOn;
        s.targets = List.of(CHEST);
        s.teleport(0.5, FLOOR + 1, -3.5);
        return s;
    }

    @Test
    void thePathfinderWalksOnFullBlocksChestsAndSlabsButNotOnPotsOrHoppers() {
        assertFalse(BlockStates.pathfinderWalksOn(POT));
        assertFalse(BlockStates.pathfinderWalksOn(BlockSpec.of("minecraft:hopper")));
        assertTrue(BlockStates.pathfinderWalksOn(BlockSpec.parse("minecraft:decorated_pot[waterlogged=true]")));
        for (String id : List.of("minecraft:stone", "minecraft:soul_sand", "minecraft:glass", "minecraft:gray_stained_glass",
            "minecraft:chest", "minecraft:barrel", "minecraft:oak_slab", "minecraft:moss_block"))
            assertTrue(BlockStates.pathfinderWalksOn(BlockSpec.of(id)), id);
        // both have a collision top of 1: the stand-spot search stood on them before the check
        assertEquals(1.0, BlockStates.collisionTop(POT), 1e-9);
        assertEquals(1.0, BlockStates.collisionTop(BlockSpec.of("minecraft:hopper")), 1e-9);
    }

    @Test
    void theSearchLeavesOutCellsOverPotsAndSaysWhy() {
        ContainerSim before = walkway(false);
        StandSpots.Search old = StandSpots.search(before, before, before.targets, REACH, 0.5, FLOOR + 1, -3.5, null,
            Set.of(), 4);
        assertTrue(POT_CELLS.contains(old.spots().getFirst().cell()), "before: the nearest cell, over a pot: " + old.spots());

        ContainerSim s = walkway(true);
        StandSpots.Search r = StandSpots.search(s, s, s.targets, REACH, 0.5, FLOOR + 1, -3.5, null, Set.of(), 4);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        for (StandSpots.Spot sp : r.spots()) assertFalse(POT_CELLS.contains(sp.cell()), "over a pot: " + sp);
        assertEquals(new Pos(8, FLOOR + 1, -4), r.spots().getFirst().cell(), "the next cell east");
        assertEquals(2, r.excluded().getOrDefault(StandSpots.UNWALKABLE_FLOOR, 0), r.excluded().toString());
    }

    @Test
    void theApproachWalksOnceInsteadOfFailingOnThePots() {
        ContainerSim before = walkway(false);
        Approach<ContainerSim.Shot> a0 = new Approach<>("chest", before.targets, REACH, null, before);
        assertEquals(TaskStatus.SUCCESS, before.run(a0, 1200), a0.failure() + " " + before.log);
        assertEquals(CHEST, before.opened);

        ContainerSim s = walkway(true);
        Approach<ContainerSim.Shot> a = new Approach<>("chest", s.targets, REACH, null, s);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 1200), a.failure() + " " + s.log);
        assertEquals(CHEST, s.opened);
        System.out.println("[stand spot walkable] walks " + a0.walks() + " -> " + a.walks() + ", failed path searches "
            + before.failedPaths + " -> " + s.failedPaths);
        assertTrue(a0.walks() > 1 && before.failedPaths > 0, "before: a walk to a pot cell failed first: " + before.log);
        assertEquals(1, a.walks());
        assertEquals(0, s.failedPaths);
        assertFalse(POT_CELLS.contains(a.standingCell()));
    }

    @Test
    void theBotAlreadyStandingOnAPotStaysThere() {
        ContainerSim s = walkway(true);
        Pos on = POT_CELLS.get(1);
        s.teleport(on.x() + 0.5, FLOOR + 1, on.z() + 0.5);
        StandSpots.Search r = StandSpots.search(s, s, s.targets, REACH, on.x() + 0.5, FLOOR + 1, on.z() + 0.5, null,
            Set.of(), 4);
        assertEquals(on, r.spots().getFirst().cell(), "no walk needed: " + r.describe(REACH));
    }

    @Test
    void aSweepPlansNoStopOverAPot() {
        for (boolean walkable : List.of(false, true)) {
            ContainerSim s = walkway(walkable);
            List<SweepPlanner.Target> ts = new ArrayList<>();
            for (int x = 0; x <= 16; x++)
                for (int y = FLOOR + 1; y <= FLOOR + 2; y++) {
                    Pos c = new Pos(x, y, -5);
                    ts.add(new SweepPlanner.Target(c, List.of(c), sight(s, List.of(c)), Set.of()));
                }
            SweepPlanner.Plan p = SweepPlanner.plan(s, ts, REACH, 0.5, FLOOR + 1, -3.5);
            assertTrue(p.unplanned().isEmpty(), p.unplanned().toString());
            boolean onPot = p.stops().stream().anyMatch(st -> POT_CELLS.contains(st.cell()));
            if (walkable) assertFalse(onPot, p.stops().toString());
        }
    }

    private static StandSpots.Sight sight(ContainerSim cs, List<Pos> blocks) {
        return new StandSpots.Sight() {
            @Override
            public boolean visibleFrom(double ex, double ey, double ez, double reach) {
                cs.targets = blocks;
                return cs.visibleFrom(ex, ey, ez, reach);
            }

            @Override
            public String hiddenFrom(double ex, double ey, double ez, double reach) {
                cs.targets = blocks;
                return cs.hiddenFrom(ex, ey, ez, reach);
            }
        };
    }
}
