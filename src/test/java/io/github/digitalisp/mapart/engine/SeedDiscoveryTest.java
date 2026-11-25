package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** After a restart STOCK_CHECK takes the last discovery from the stock index (build.seedDiscoveryFromStock). */
class SeedDiscoveryTest {
    private static final int LONG = 60_000;

    private static boolean discovered(RecordingLogger log) {
        return log.lines.stream().anyMatch(l -> l.contains("material containers in"));
    }

    private static boolean seeded(RecordingLogger log) {
        return log.lines.stream().anyMatch(l -> l.contains("taken from the stock index"));
    }

    private Sim firstRunDone() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0001", "One", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertNotNull(sim.store.stock().discovered, "the discovery was stored with the stock index");
        return sim;
    }

    private RecordingLogger secondRun(Sim sim, boolean seed) {
        sim.config.build.seedDiscoveryFromStock = seed;
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log); // a restart: nothing discovered yet
        Job job = sim.addJob("j0002", "Two", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        return log;
    }

    @Test
    void restartTakesTheStoredDiscovery() {
        RecordingLogger log = secondRun(firstRunDone(), true);
        assertTrue(seeded(log), log.lines.toString());
        assertFalse(discovered(log), "no discovery walk after the restart");
    }

    @Test
    void killSwitchDiscoversAgain() {
        RecordingLogger log = secondRun(firstRunDone(), false);
        assertFalse(seeded(log));
        assertTrue(discovered(log));
    }
}
