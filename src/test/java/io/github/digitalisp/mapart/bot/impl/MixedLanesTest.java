package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mixed carpet / full-block tiles on a simulated platform ({@link io.github.digitalisp.mapart.testutil.LaneSim}: collision,
 * jumping, falling, line of sight against outline shapes, the noobline, a Baritone stand-in), built by the
 * production planner and {@link LaneRun}, with repair passes re-planned from the world like VERIFY.
 *
 * <p>Live bug (10x test, 2026-10-11, 28×18 art of 2-wide columns: leaves, dripstone, mushroom stem and terracotta
 * among plates, roots, lichen, flowers, petals, resin and a carpet frame): full blocks placed beside and on the
 * walking line hid the cells beyond them from the line, and the repair passes walked lanes walled by them. 32 cells
 * were never placed ("not reachable from the lane") and the tile went BLOCKED after 4 repair passes. The simulator
 * reproduces that with the old ordering (27 cells missing after 4 passes).
 */
class MixedLanesTest {
    /** Built, nothing given up as unreachable, never walled in, never into its own box, bounded walking. */
    private static void assertBuilt(TileRunner r, TileArt art, boolean built, double maxOverhead) {
        String rep = r.report(art);
        assertTrue(built, "tile not built\n" + rep);
        assertEquals(0, r.failures, "lane failures\n" + rep);
        assertTrue(r.sim.skipReasons.keySet().stream().noneMatch(k -> k.contains("not reachable")),
            "a cell was given up as not reachable\n" + rep);
        assertEquals(0, r.sim.pocketTicks, "the bot stood on the floor walled in on all four sides\n" + rep);
        assertEquals(0, r.sim.boxRejects, "placement into the bot's own box\n" + rep);
        assertEquals(0, r.sim.wrongClicks, "click without a target or with the wrong item\n" + rep);
        assertEquals(0, r.sim.fellOff, "walked off the platform\n" + rep);
        assertTrue(r.walkOverhead() <= maxOverhead, "walking overhead " + r.walkOverhead() + "\n" + rep);
    }

    private static TileRunner run(TileArt art, double missRate, long seed, int repairPasses, double maxOverhead) {
        TileRunner r = new TileRunner(seed);
        r.sim.missRate = missRate;
        boolean built = r.build(art, repairPasses);
        assertBuilt(r, art, built, maxOverhead);
        return r;
    }

    @Test
    void liveStripesAreBuiltInOnePassWithoutStandSpots() {
        TileArt art = TileRunner.liveStripes();
        TileRunner r = run(art, 0, 1, 0, 1.2);
        assertEquals(1, r.passes, "done in the BUILD pass\n" + r.report(art));
        assertEquals(0, r.spotLanes(), "the walk placed everything\n" + r.report(art));
        assertEquals(0, r.sim.jumps, "no climbing needed\n" + r.report(art));
    }

    @Test
    void liveStripesWithDroppedPlacementsNeedOneRepairPass() {
        TileArt art = TileRunner.liveStripes();
        TileRunner r = run(art, 0.05, 2, 4, 1.5);
        assertTrue(r.passes <= 2, "repair passes: " + r.passes + "\n" + r.report(art));
    }

    @Test
    void checkerboardOfFullBlocksAndCarpet() {
        // rows 0-39 against the noobline: every carpet ends up walled in on four sides
        TileArt art = TileRunner.checkerboard(20, 0, 60, 40);
        TileRunner r = run(art, 0, 3, 0, 1.3);
        assertEquals(1, r.passes, r.report(art));
        assertEquals(0, r.spotLanes(), r.report(art));
        run(art, 0.05, 4, 4, 2.0);
    }

    @Test
    void fullBlockOnlyTile() {
        // the whole tile: the noobline north, the tile edges east, west and south (no floor beyond)
        TileArt art = TileRunner.fill("minecraft:white_terracotta", 0, 0, 128, 128);
        TileRunner r = run(art, 0, 5, 0, 1.1);
        assertEquals(1, r.passes, r.report(art));
        // a lane that ends at the edge may leave an end cell to a stand spot right next to it; how many lanes do
        // depends on the simulation's random aim and Baritone offsets (seeds 1-10: 2-6 lanes, before and after the
        // pacer rework of the confirmation classifier)
        assertTrue(r.spotLanes() <= 6, "stand spots in " + r.spotLanes() + " lanes\n" + r.report(art));
    }

    @Test
    void fullBlocksWithDroppedPlacements() {
        TileArt art = TileRunner.fill("minecraft:white_terracotta", 0, 0, 40, 128);
        run(art, 0.05, 6, 4, 2.0);
    }

    @Test
    void ringOfFullBlocksAroundCarpet() {
        TileArt art = TileRunner.ring(10, 0, 70, 50, 20, 10, 60, 40, 2);
        TileRunner r = run(art, 0, 7, 0, 1.3);
        assertEquals(1, r.passes, r.report(art));
        assertEquals(0, r.spotLanes(), r.report(art));
    }

    @Test
    void randomNoise() {
        TileArt art = TileRunner.noise(8, 0, 0, 60, 60, 0.4);
        TileRunner r = run(art, 0, 8, 0, 1.3);
        assertEquals(1, r.passes, r.report(art));
        run(TileRunner.noise(9, 0, 0, 60, 60, 0.4), 0.05, 9, 4, 1.8);
        run(TileRunner.noise(10, 64, 64, 128, 128, 0.7), 0, 10, 1, 1.5);
    }

    @Test
    void carpetOnlyTileWalksAsBefore() {
        TileArt art = TileRunner.fill("minecraft:white_carpet", 0, 0, 128, 128);
        TileRunner r = run(art, 0, 11, 0, 1.12);
        assertEquals(0, r.spotLanes(), "no stand spots on a carpet tile\n" + r.report(art));
        assertEquals(0, r.sim.jumps, r.report(art));
        assertTrue(r.ticks <= 1.6 * art.nonEmptyCount(), "ticks " + r.ticks + "\n" + r.report(art));
    }

    @Test
    void repairPassPlacesHolesWalledInByFullBlocks() {
        // VERIFY after a BUILD that placed every full block of a checkerboard and none of its carpets: every missing
        // cell is a hole with a full block on each side, and every walking line is walled
        TileArt art = TileRunner.checkerboard(30, 20, 60, 50);
        TileRunner r = new TileRunner(12);
        for (int x = 30; x < 60; x++) {
            for (int z = 20; z < 50; z++) {
                BlockSpec b = art.get(x, z);
                if (b.id().endsWith("terracotta")) r.world.put(r.geo.cell(x, z), b);
            }
        }
        boolean built = r.build(art, 1);
        assertBuilt(r, art, built, 99);
        assertEquals(1, r.passes, "one repair pass\n" + r.report(art));
        assertTrue(r.sim.baritoneWalks <= 450 / 2, "stand spots per hole\n" + r.report(art));
    }

    @Test
    void cellsLeftByARoundWithoutTheirMaterialArePlacedAmongFullBlocks() {
        // a material ran out in the first round (skipped as missing); the next round re-plans lanes whose walking
        // lines are walled by the full blocks placed meanwhile
        TileArt art = TileRunner.noise(13, 0, 0, 60, 60, 0.4);
        TileRunner r = new TileRunner(13);
        r.missingItems.add("minecraft:white_terracotta");
        r.build(art, 0);
        assertTrue(r.sim.skipReasons.keySet().stream().anyMatch(k -> k.startsWith("missing item")), r.report(art));
        r.missingItems.clear();
        r.sim.skipReasons.clear();
        boolean built = r.build(art, 2);
        assertBuilt(r, art, built, 99);
    }
}
