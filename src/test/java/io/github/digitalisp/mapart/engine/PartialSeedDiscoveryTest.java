package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A material region added between two runs (a sorter's upper rows): the restart takes the stored discovery of the
 * unchanged region and discovers only the new one, whose chest is the only one scanned (build.seedDiscoveryPartial).
 */
class PartialSeedDiscoveryTest {
    private static final int LONG = 60_000;
    private static final Pos UPPER_CHEST = new Pos(-100, 63, 100);

    private Sim firstRunDone() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0001", "One", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertNotNull(sim.store.stock().discovered, "the discovery was stored with the stock index");
        return sim;
    }

    /** The upper row: a region of its own above the first one, one chest in it. */
    private static void addUpperRow(Sim sim) {
        MapArtConfig.RegionCfg upper = new MapArtConfig.RegionCfg();
        upper.min = new Pos(-100, 63, 100);
        upper.max = new Pos(-80, 63, 100);
        upper.name = "upper row";
        sim.config.site.storage.materialRegions.add(upper);
        sim.bot.addContainer(UPPER_CHEST, Map.of(Sim.WHITE, 1728));
    }

    private RecordingLogger restartAndRun(Sim sim, String id) {
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log); // a restart: nothing discovered yet
        Job job = sim.addJob(id, id, 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        return log;
    }

    private static boolean has(RecordingLogger log, String text) {
        return log.lines.stream().anyMatch(l -> l.contains(text));
    }

    @Test
    void anAddedRegionIsDiscoveredAloneAndOnlyItsChestScanned() {
        Sim sim = firstRunDone();
        addUpperRow(sim);
        RecordingLogger log = restartAndRun(sim, "j0002");
        assertTrue(has(log, "5 material containers taken from the stock index (the last discovery, regions unchanged); 1"
            + " new or changed material regions to discover: \"upper row\""), log.lines.toString());
        assertTrue(has(log, "found 1 material containers in 1 new or changed regions (0 positions not loaded); 6 in all"),
            log.lines.toString());
        assertTrue(has(log, "scanning 1 material containers (1 never scanned)"), log.lines.toString());
        assertTrue(sim.store.stock().discovered.contains(UPPER_CHEST.key()), "stored with the next discovery");
        assertTrue(sim.store.stock().containers.containsKey(UPPER_CHEST.key()), "its snapshot is in the index");

        // the next restart takes all six as they are
        RecordingLogger again = restartAndRun(sim, "j0003");
        assertTrue(has(again, "6 material containers taken from the stock index (the last discovery; no walk"),
            again.lines.toString());
        assertFalse(has(again, "material containers in"), "no discovery on the restart after");
    }

    @Test
    void killSwitchDiscoversEveryRegion() {
        Sim sim = firstRunDone();
        addUpperRow(sim);
        sim.config.build.seedDiscoveryPartial = false;
        RecordingLogger log = restartAndRun(sim, "j0002");
        assertFalse(has(log, "taken from the stock index"), log.lines.toString());
        assertTrue(has(log, "found 6 material containers in 2 regions"), log.lines.toString());
    }

    @Test
    void aRequestedScanStillDiscoversEverything() {
        Sim sim = firstRunDone();
        addUpperRow(sim);
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.engine.requestStockScan();
        sim.start();
        assertTrue(sim.runUntil(() -> has(log, "material containers in"), LONG), sim.state());
        assertTrue(has(log, "found 6 material containers in 2 regions"), log.lines.toString());
    }
}
