package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.SyntheticArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads walked the other way ({@code build.restockLaneFlip}) on the simulated platform: {@code LaneRun} runs the flipped
 * lanes of {@link RestockLoads#cutLanes} like any other, from a walk-in deep in the tile beside the placed lanes, and
 * the whole walk (to the lanes, along them and back) does not grow: two j0015-like tiles, 8,217 → 8,119 blocks
 * (seed 1 -81, seed 2 -17; the DP plans with {@code Costs.DEFAULT}, the sim walks).
 *
 * <p>Each load is planned by the split DP ({@link SplitRestockPlanner}, as AUTO on 9-wide lanes) for 28 empty slots
 * (the live kit with the map and pane at STORE), the bot where the last load ended and every item at the sorter north
 * of the tile, west of its centre (j0015's carpets sit at the walkway's west end); with the switch the DP may walk the
 * load the other way. The lanes of the round are those of the engine: replanned from the world (first lane N→S), cut to
 * the plan. Airplace lanes, even widths. Walking: the sim's lane walks and walks to the lane starts from the sorter,
 * plus the straight line from each load's end back to the sorter (the restock walk, not simulated).
 */
class LoadDirectionLaneSimTest {
    private static final int SLOTS = 28;

    record Run(TileRunner r, int loads, int flipped, long ticks, double walking, double back, int failures) { }

    private static Run build(TileArt art, boolean flip, long seed) {
        TileRunner r = new TileRunner(seed);
        r.build.placeMode = "AIRPLACE";
        r.build.rotatePlayer = false;
        r.build.grimRotate = true;
        r.build.reach = 4.5;
        r.build.laneEvenWidths = true;
        PlacementListener listener = new PlacementListener() {
            @Override public void placed(Placement placement) { }
            @Override public void skipped(Placement placement, String reason) { }
        };
        int loads = 0;
        int flipped = 0;
        long ticks = 0;
        int failures = 0;
        double back = 0;
        double sx = r.geo.minX() + 3.5;
        double sz = r.geo.minZ() - 4.5;
        Pos sorter = new Pos(r.geo.minX() + 3, r.geo.artY(), r.geo.minZ() - 5);
        r.sim.teleport(sx, r.geo.artY(), sz);
        for (int guard = 0; guard < 40; guard++) {
            List<Lane> lanes = new ArrayList<>();
            for (Lane l : r.plan(art)) {
                Lane u = r.unplaced(l);
                if (!u.placements().isEmpty()) lanes.add(u);
            }
            if (lanes.isEmpty()) break;
            loads++;
            Pos bot = new Pos((int) Math.floor(r.sim.x), r.geo.artY(), (int) Math.floor(r.sim.z));
            RestockPlanner.Site site = new RestockPlanner.Site(bot, id -> sorter, RestockPlanner.Costs.DEFAULT, flip);
            RestockPlanner.Plan plan = new SplitRestockPlanner(false).plan(new RestockPlanner.Request(lanes, Map.of(),
                Map.of(), SLOTS, id -> 64, 0, true, site));
            if (plan.flipped()) flipped++;
            // the sorter: north of the tile, west of its centre (j0015's carpets sit at the walkway's west end)
            r.sim.teleport(sx, r.geo.artY(), sz);
            for (Lane lane : RestockLoads.cutLanes(lanes, plan)) {
                TaskStatus s = r.sim.runLane(lane, r.params(), listener, 4000 + 400 * lane.placements().size());
                ticks += r.sim.lastTicks;
                if (s != TaskStatus.SUCCESS) failures++;
            }
            back += Math.hypot(r.sim.x - sx, r.sim.z - sz);
        }
        return new Run(r, loads, flipped, ticks, r.sim.walked + r.sim.baritoneDistance, back, failures);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void flippedLoadsRunLikeAnyOtherAndDoNotWalkMore() {
        List<String> lines = new ArrayList<>();
        double offWalk = 0;
        double onWalk = 0;
        for (long seed = 1; seed <= 2; seed++) {
            TileArt art = SyntheticArt.banded(seed, MapPaneAtStoreModelTest.J0015, 6, 16, 64, 0.30);
            Run off = build(art, false, seed);
            Run on = build(art, true, seed);
            for (Run x : List.of(off, on)) {
                String rep = x.r().report(art);
                assertTrue(x.r().matches(art), "tile not built\n" + rep);
                assertEquals(0, x.failures(), "lane failures\n" + rep);
                assertEquals(0, x.r().sim.pocketTicks, "walled in\n" + rep);
                assertEquals(0, x.r().sim.boxRejects, "into its own box\n" + rep);
                assertEquals(0, x.r().sim.wrongClicks, "wrong click\n" + rep);
                assertEquals(0, x.r().sim.fellOff, "off the platform\n" + rep);
                assertEquals(0, x.r().sim.farRejects, "airplace beyond range\n" + rep);
                assertTrue(x.r().sim.skipReasons.keySet().stream().noneMatch(k -> k.contains("not reachable")), rep);
            }
            assertEquals(0, off.flipped());
            assertTrue(on.flipped() > 0, "some load walks the other way");
            lines.add(String.format(Locale.ROOT, "seed %d: off %d loads, %.0f blocks walked + %.0f back, %d ticks; on %d"
                    + " loads (%d flipped), %.0f blocks + %.0f back, %d ticks", seed, off.loads(), off.walking(),
                off.back(), off.ticks(), on.loads(), on.flipped(), on.walking(), on.back(), on.ticks()));
            offWalk += off.walking() + off.back();
            onWalk += on.walking() + on.back();
        }
        String rep = String.join("\n", lines);
        System.out.println(rep);
        assertTrue(onWalk <= offWalk, rep);
    }
}
