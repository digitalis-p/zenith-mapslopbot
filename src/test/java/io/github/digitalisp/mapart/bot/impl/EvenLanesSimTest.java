package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.SerpentineBuildPlanner;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.LaneSim;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Even lanes ({@code build.laneEvenWidths}, {@link SerpentineBuildPlanner}) on the simulated platform with the live
 * click budget ({@link LaneSim}: sub-tick dispatch in a 310 ms window, 3 per tick, Paper's 9 per 300 ms, acks and slot
 * counts 4 ticks late, the paced walk at walking speed, no Soul Speed). Each lane of a full carpet tile, as the planner
 * plans it with the switch off and on, runs from its start: the narrow last lane (2 columns) is bound by the walk, not
 * by the clicks, so sharing its columns among the other lanes saves most of its time.
 */
class EvenLanesSimTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, -64, 64);
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    private static LaneSim sim(long seed) {
        Map<Pos, BlockSpec> world = new HashMap<>();
        LaneSim sim = new LaneSim(new LaneSim.Blocks() {
            @Override
            public BlockSpec get(Pos p) {
                return world.getOrDefault(p, BlockSpec.AIR);
            }

            @Override
            public void set(Pos p, BlockSpec b) {
                world.put(p, b);
            }
        }, new LaneSim.Items() {
            @Override
            public int count(String item) {
                return 100_000;
            }

            @Override
            public boolean take(String item) {
                return true;
            }
        }, GEO.minX(), GEO.minZ(), GEO.artY(), new AdaptivePacer(1, 1, 4), seed);
        sim.confirmDelay = 4;
        sim.countLag = 4;
        sim.echoes = true;
        sim.paperWindowTicks = 6;
        sim.paperWindowMax = 9;
        sim.millisecondArrivals = true;
        Random random = new Random(seed);
        sim.clickJitterMillis = seq -> random.nextInt(11);
        return sim;
    }

    /** Ticks of one lane run from the lane's start, every cell placed. */
    private static long laneTicks(Lane lane, long seed) {
        LaneSim sim = sim(seed);
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg(); // AIRPLACE, silent looks, the live paced walk
        b.pacedLaneWalk = true;
        sim.silent = PlaceMode.silentRotation(b);
        PlacePacing pacing = PlacePacing.of(b).withClicksPerTick(3, 7, 9).withSubTickDispatch(true, 310);
        LaneRun.Params params = LaneRun.Params.of(b, sim.tileArea()).withPacing(pacing);
        sim.teleport(lane.from().x() + 0.5, GEO.artY(), lane.from().z() + 0.5);
        TaskStatus s = sim.runLane(lane, params, NONE, 40_000);
        assertEquals(TaskStatus.SUCCESS, s, String.join("\n", sim.log));
        assertEquals(lane.placements().size(), sim.accepted, "lane " + lane.index());
        assertEquals(0, sim.wrongClicks);
        return sim.lastTicks;
    }

    @Test
    void evenLanesSaveTheNarrowLastLanesWalk() {
        TileArt art = Tiles.filled(WHITE);
        FakeCatalog catalog = new FakeCatalog();
        List<Lane> off = new SerpentineBuildPlanner(() -> false).plan(art, GEO, catalog, 4, p -> false);
        List<Lane> on = new SerpentineBuildPlanner(() -> true).plan(art, GEO, catalog, 4, p -> false);
        assertEquals(15, off.size());
        assertEquals(15, on.size());
        long seeds = 3;
        long[] offTicks = new long[15];
        long[] onTicks = new long[15];
        // lanes of the same width and direction run alike: one of each kind, averaged over seeds
        Map<String, Long> memo = new HashMap<>();
        for (int k = 0; k < 15; k++) {
            offTicks[k] = kindTicks(off.get(k), seeds, memo);
            onTicks[k] = kindTicks(on.get(k), seeds, memo);
        }
        long offSum = 0, onSum = 0;
        for (int k = 0; k < 15; k++) {
            offSum += offTicks[k];
            onSum += onTicks[k];
        }
        List<String> rows = new ArrayList<>();
        for (int k = 0; k < 15; k++) {
            rows.add(k + ":" + width(off.get(k)) + "w " + offTicks[k] + "t / " + width(on.get(k)) + "w " + onTicks[k] + "t");
        }
        System.out.printf("EVEN-LANES-SIM full carpet tile, live budget, walking speed: off %d ticks (%.1f s), on %d ticks"
            + " (%.1f s), saved %.1f s; last lane %d -> %d ticks; %s%n", offSum, offSum / 20.0, onSum, onSum / 20.0,
            (offSum - onSum) / 20.0, offTicks[14], onTicks[14], rows);
        // the 2-column lane walks 128 rows at walking speed: walk-bound, about 3x the 9 s its clicks need
        assertTrue(offTicks[14] > 0.9 * 128 / LaneSim.WALK, "narrow lane walk-bound: " + offTicks[14]);
        assertTrue(onSum < offSum - 300, "even lanes save over 15 s of lane time: " + onSum + " vs " + offSum);
    }

    private static long kindTicks(Lane lane, long seeds, Map<String, Long> memo) {
        boolean south = lane.to().z() > lane.from().z();
        String key = width(lane) + (south ? "S" : "N");
        Long known = memo.get(key);
        if (known != null) return known;
        long sum = 0;
        for (long seed = 1; seed <= seeds; seed++) sum += laneTicks(lane, seed);
        memo.put(key, sum / seeds);
        return sum / seeds;
    }

    private static int width(Lane lane) {
        return lane.placements().size() / TileArt.SIZE;
    }
}
