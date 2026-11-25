package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * A far load fills the item there ({@code build.farLoadFill}). Live j0015
 * (2026-10-23, soul sand): gray-heavy tiles, the near sorter chests returning a few hundred gray carpet while a load is
 * built; once they ran dry every load took those and topped up from the moss sorter, 1.5 far trips a map. Here: a tile
 * of white only, the near chest starting with 1,000 and the sorter putting back 1,000 while each load is built (Sim
 * coordinates, the far sorter about 125 blocks south as in {@link FarStorageEngineTest}).
 */
class FarLoadFillEngineTest {
    private static final int LONG = 400_000;
    private static final Pos FAR_CHEST = new Pos(-90, 61, 185);
    private static final Pos NEAR_WHITE = new Pos(-100, 61, 100);
    private static final Pos NEAR_WHITE_2 = new Pos(-98, 61, 100);
    private static final int REFILL = 1000;
    /** Live j0015 [4,0]: about 2.4 min from one restock to the next. */
    private static final long BUILD_MS = 120_000L;

    private record Run(int farTrips, int loads, int farWhite, Sim sim, RecordingLogger log) { }

    private static Run build(boolean fill, Supplier<RestockPlanner> planner) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.farStorage = true;
        sim.config.build.farLoadFill = fill;
        MapArtConfig.RegionCfg far = new MapArtConfig.RegionCfg();
        far.min = new Pos(-100, 60, 180);
        far.max = new Pos(-80, 62, 190);
        far.name = "far sorter";
        sim.config.site.storage.materialRegions.add(far);
        sim.bot.addContainer(FAR_CHEST, Map.of(WHITE, 20_000));
        sim.bot.containers.get(NEAR_WHITE).items.put(WHITE, 1000);
        sim.bot.containers.get(NEAR_WHITE_2).items.remove(WHITE);
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SerpentinePlanner(), planner.get(), log);
        Job job = sim.addJob(fill ? "j0981" : "j0980", "Fill", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 80));
        sim.start();
        Phase[] last = {Phase.IDLE};
        int[] loads = {0};
        assertTrue(sim.runUntil(() -> {
            Phase now = sim.phase();
            // the sorter returns its part while each load is built
            if (now == Phase.BUILD && last[0] != Phase.BUILD) {
                loads[0]++;
                sim.bot.containers.get(NEAR_WHITE).items.merge(WHITE, REFILL, Integer::sum);
                sim.clock.advance(BUILD_MS); // a load's BUILD as live (the Sim places much faster)
            }
            last[0] = now;
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, sim.output().maps.size(), "verified and stored");
        int trips = 0;
        boolean seen = false;
        for (String h : sim.bot.history) {
            if (h.startsWith("withdraw " + FAR_CHEST)) seen = true;
            else if (h.startsWith("placeLane") && seen) {
                trips++;
                seen = false;
            }
        }
        if (seen) trips++;
        int farWhite = 20_000 - sim.bot.containers.get(FAR_CHEST).items.getOrDefault(WHITE, 0);
        return new Run(trips, loads[0], farWhite, sim, log);
    }

    @Test
    void aFarLoadTakesAllItServesThereAndTheNextLoadsStayNear() {
        for (Supplier<RestockPlanner> planner : List.<Supplier<RestockPlanner>>of(GreedyRestockPlanner::new,
            () -> new ConfiguredRestockPlanner(() -> "AUTO"))) {
            Run before = build(false, planner);
            Run now = build(true, planner);
            String name = planner.get().getClass().getSimpleName();
            System.out.println("[far load fill] " + name + ": far trips " + before.farTrips() + " -> " + now.farTrips()
                + " (loads " + before.loads() + " -> " + now.loads() + ", white from far " + before.farWhite() + " -> "
                + now.farWhite() + ")");
            assertTrue(now.farTrips() < before.farTrips(), name + ": " + before + " " + now);
            assertTrue(now.loads() <= before.loads(), name + ": no extra loads");
            assertTrue(now.log().at(Level.INFO).stream().anyMatch(l -> l.contains("(build.farLoadFill)")),
                now.log().lines.toString());
            assertTrue(before.log().at(Level.INFO).stream().noneMatch(l -> l.contains("(build.farLoadFill)")));
        }
    }

    /** Nothing changes for a load that the near chests cover: the fill only sizes a far trip the load makes anyway. */
    @Test
    void noFarTripWhenTheNearChestsCoverTheTile() {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.farStorage = true;
        sim.config.build.farLoadFill = true;
        MapArtConfig.RegionCfg far = new MapArtConfig.RegionCfg();
        far.min = new Pos(-100, 60, 180);
        far.max = new Pos(-80, 62, 190);
        sim.config.site.storage.materialRegions.add(far);
        sim.bot.addContainer(FAR_CHEST, Map.of(WHITE, 20_000));
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), Sim.LOG);
        Job job = sim.addJob("j0982", "Near", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 20));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(20_000, sim.bot.containers.get(FAR_CHEST).items.get(WHITE), "far storage untouched");
    }
}
