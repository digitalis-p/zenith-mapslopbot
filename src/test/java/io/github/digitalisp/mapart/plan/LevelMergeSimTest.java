package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both levels of a colour-suppression layer in one sweep ({@link LevelMerge}, {@code build.suppressionMergeLevels};
 * docs/COLOR_SUPPRESSION.md): each layer of the real export
 * {@code full1214-suppress_crubtech.nbt} built in {@link TileRunner} with the live colour-suppression lane settings and
 * restock rounds of 770 cells (the live median), each round re-planned from the world and its last lane cut as the split
 * planner cuts it. The level-0 pass alone, the merged sweep, and the merged sweep with the lane placer as it was (one
 * level) are printed; the merged sweep must build both levels with no pocket, no stand on a raised block, no fall.
 */
class LevelMergeSimTest {
    static final int LOAD = 770;

    static TileArt level(SuppressionArt a, int layer, int level) {
        TileArt t = new TileArt();
        for (int col = 0; col < TileArt.SIZE; col++)
            for (int row = 0; row < TileArt.SIZE; row++) {
                BlockSpec b = a.get(layer, level, col, row);
                if (b != null) t.set(col, row, b);
            }
        return t;
    }

    static TileRunner runner(boolean raisedRun) {
        TileRunner r = new TileRunner(7);
        r.build.placeMode = "AIRPLACE";
        r.build.rotatePlayer = true;
        r.build.grimRotate = true;
        r.build.reach = 4.5;
        r.build.airPlaceRange = 5.0;
        r.build.pacedLaneWalk = true;
        r.build.laneStepBack = true;
        r.build.placeClicksPerTick = 3;
        r.build.placeClickWindowTicks = 7;
        r.sim.platformBlock = BlockSpec.of("minecraft:glass");
        r.sim.confirmDelay = 2;
        r.sim.countLag = 2;
        r.sim.echoes = true;
        r.sim.paperWindowTicks = 6;
        r.sim.paperWindowMax = 9;
        r.sim.stackSize = 64;
        r.sim.raisedRun = raisedRun;
        // the chests: north-west of the tile, off it (a restock trip is not timed)
        r.sorter = new double[] {r.geo.minX() - 3.5, r.geo.minZ() - 3.5};
        return r;
    }

    static String line(String what, TileRunner r, int left) {
        return String.format(Locale.ROOT, "  %-26s %7d ticks, %3d lanes, %2d rounds, %5d left, walk overhead %.2f, "
                + "stand spot lanes %d, last resorts %d, pocket ticks %d, on a raised top %d / %d spots, box rejects %d,"
                + " fell %d, failures %d", what, r.ticks, r.lanesRun, r.loads, left, r.walkOverhead(), r.spotLanes(),
            r.sim.log.stream().filter(l -> l.contains("last resort")).count(), r.sim.pocketTicks, r.sim.raisedStands,
            r.sim.raisedSpots, r.sim.boxRejects, r.sim.fellOff, r.failures);
    }

    @Test
    void eachLayerOfTheExportInOneSweep() throws Exception {
        SuppressionArt a = CsPrintModelTest.full();
        StringBuilder out = new StringBuilder("both levels in one sweep, full1214, loads of " + LOAD + " cells:\n");
        String only = System.getenv("RAISED_LAYER");
        List<String> failures = new java.util.ArrayList<>();
        for (int layer : new int[] {SuppressionArt.TOP, SuppressionArt.BOTTOM}) {
            String name = layer == SuppressionArt.TOP ? "top" : "bottom";
            if (only != null && !only.equals(name)) continue;
            TileArt l0 = level(a, layer, 0);
            TileArt l1 = level(a, layer, 1);
            TileRunner low = runner(false);
            int lowLeft = low.buildLevels(l0, new TileArt(), LOAD, 200);
            TileRunner merged = runner(true);
            int left = merged.buildLevels(l0, l1, LOAD, 200);
            TileRunner before = runner(false);
            int beforeLeft = before.buildLevels(l0, l1, LOAD, 200);
            out.append(name).append(" layer: ").append(l0.nonEmptyCount()).append(" cells on the glass, ")
                .append(l1.nonEmptyCount()).append(" raised\n")
                .append(line("level 0 alone", low, lowLeft)).append('\n')
                .append(line("both levels", merged, left)).append('\n')
                .append(line("both, one-level placer", before, beforeLeft)).append('\n');
            if (System.getenv("RAISED_LOG") != null) {
                System.out.println("POCKETS " + merged.sim.pockets + "\nFIRST POCKET TRAIL\n" + merged.sim.firstPocketTrail);
                System.out.println("STUCK\n" + String.join("\n----\n", merged.sim.stuckViews));
                System.out.println("LOG\n  " + String.join("\n  ", merged.sim.log));
            }
            String what = name + ": " + merged.sim.pockets + " " + merged.levelMismatches(l0, l1).subList(0,
                Math.min(20, merged.levelMismatches(l0, l1).size()));
            if (left > l1.nonEmptyCount() / 100) failures.add("cells left: " + left + " " + what);
            if (merged.sim.pocketTicks > 0) failures.add("pocket ticks " + merged.sim.pocketTicks + " " + what);
            if (merged.sim.raisedStands + merged.sim.raisedSpots > 0) failures.add("on a raised block " + what);
            if (merged.sim.fellOff + merged.sim.boxRejects + merged.failures > 0) failures.add("fell, box rejects or failures " + what);
            // the walk of the merged sweep is the level-0 sweep's: the raised cells add clicks, not walking
            if (merged.walkOverhead() > low.walkOverhead() * 1.15 + 0.05)
                failures.add("walk " + merged.walkOverhead() + " against " + low.walkOverhead());
        }
        System.out.println(out);
        assertEquals(List.of(), failures);
    }

    /**
     * What the merge costs a load: the materials of each 9-wide lane at level 0 alone and with its raised cells (a load
     * holds one lane and the start of the next in 31 slots, each material a slot of its own). Printed for the page; the
     * merge adds no material a layer does not have.
     */
    @Test
    void theMaterialsALaneCarries() throws Exception {
        SuppressionArt a = CsPrintModelTest.full();
        SerpentineBuildPlanner planner = new SerpentineBuildPlanner();
        StringBuilder out = new StringBuilder("materials a lane carries (level 0 alone -> both levels):\n");
        TileRunner r = runner(true);
        for (int layer : new int[] {SuppressionArt.TOP, SuppressionArt.BOTTOM}) {
            TileArt l0 = level(a, layer, 0);
            TileArt l1 = level(a, layer, 1);
            var cat = new io.github.digitalisp.mapart.testutil.FakeCatalog();
            List<Lane> alone = planner.plan(l0, r.geo, cat, 4, p -> false);
            List<Lane> both = LevelMerge.lanes(planner, l0, l1, r.geo, cat, 4, p -> false, p -> false);
            java.util.Set<String> layerItems = new java.util.TreeSet<>(), lowItems = new java.util.TreeSet<>();
            int sumA = 0, sumB = 0, maxA = 0, maxB = 0;
            for (Lane l : alone) {
                java.util.Set<String> s = new java.util.HashSet<>();
                for (var p : l.placements()) s.add(p.itemId());
                lowItems.addAll(s);
                sumA += s.size();
                maxA = Math.max(maxA, s.size());
            }
            for (Lane l : both) {
                java.util.Set<String> s = new java.util.HashSet<>();
                for (var p : l.placements()) s.add(p.itemId());
                layerItems.addAll(s);
                sumB += s.size();
                maxB = Math.max(maxB, s.size());
            }
            java.util.Set<String> only1 = new java.util.TreeSet<>(layerItems);
            only1.removeAll(lowItems);
            out.append(String.format(Locale.ROOT, "  %s layer: %.1f -> %.1f materials a lane (most %d -> %d); %d materials"
                    + " in the layer, %d of them only raised%n", layer == SuppressionArt.TOP ? "top" : "bottom",
                (double) sumA / alone.size(), (double) sumB / both.size(), maxA, maxB, layerItems.size(), only1.size()));
        }
        System.out.println(out);
    }

    /** The merged plan is the level-0 plan when no raised cell is left, and holds every raised cell otherwise. */
    @Test
    void theMergedPlanHoldsBothLevels() throws Exception {
        SuppressionArt a = CsPrintModelTest.full();
        TileRunner r = runner(true);
        for (int layer : new int[] {SuppressionArt.TOP, SuppressionArt.BOTTOM}) {
            TileArt l0 = level(a, layer, 0);
            TileArt l1 = level(a, layer, 1);
            SerpentineBuildPlanner planner = new SerpentineBuildPlanner();
            List<Lane> alone = planner.plan(l0, r.geo, new io.github.digitalisp.mapart.testutil.FakeCatalog(), 4, p -> false);
            List<Lane> none = LevelMerge.lanes(planner, l0, l1, r.geo, new io.github.digitalisp.mapart.testutil.FakeCatalog(), 4, p -> false, p -> true);
            assertEquals(alone, none);
            List<Lane> both = LevelMerge.lanes(planner, l0, l1, r.geo, new io.github.digitalisp.mapart.testutil.FakeCatalog(), 4, p -> false, p -> false);
            assertEquals(l1.nonEmptyCount(), LevelMerge.raisedCells(both));
            int cells = 0;
            for (Lane l : both) cells += l.placements().size();
            assertEquals(l0.nonEmptyCount() + l1.nonEmptyCount(), cells);
        }
    }
}
