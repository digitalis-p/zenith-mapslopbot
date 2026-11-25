package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offhand's item put back by the engine: when the airplace guard wants a
 * repair mid-lane, the lane stops at its next safe point, the repair runs while it is parked, and the same lane carries
 * on; a repair that keeps failing is retried, then given up with one alert.
 */
class OffhandRepairEngineTest {
    private static final int LONG = 80_000;

    private static Sim sim() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 1; // a 640-cell lane takes 640 ticks
        return sim;
    }

    private static Job job(Sim sim) {
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0300", "Offhand", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static boolean laneRunning(Sim sim) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith("lane") && !t.isFinished();
    }

    private static BotTask midLane(Sim sim) {
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.run(30);
        assertTrue(laneRunning(sim), sim.state());
        return sim.bot.lastTask;
    }

    @Test
    void midLaneTheLaneParksTheOffhandIsRepairedAndTheLaneCarriesOn() {
        Sim sim = sim();
        Job job = job(sim);
        BotTask lane = midLane(sim);
        sim.bot.offhandWanted = true;
        assertTrue(sim.runUntil(() -> sim.bot.offhandRepairs > 0, 200), "repaired: " + sim.state());
        assertEquals(1, sim.bot.offhandRepairsWhileParked, "while the lane was parked");
        assertEquals(1, sim.bot.parks);
        assertFalse(lane.isFinished(), "parked, not cancelled");
        assertTrue(sim.runUntil(lane::isFinished, LONG), sim.state());
        assertNull(lane.failure(), "the same lane carried on: " + lane.failure());
        assertEquals(1, sim.bot.resumes);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.offhandRepairs, "once");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void aRepairThatKeepsFailingIsRetriedThenGivenUpWithOneAlert() {
        Sim sim = sim();
        Job job = job(sim);
        midLane(sim);
        sim.bot.offhandWanted = true;
        sim.bot.offhandRepairFailures = 100;
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "the job still finishes: " + sim.state());
        assertEquals(EngineTuning.OFFHAND_MAX_FAILURES, sim.bot.offhandRepairs, "three tries, then no more");
        assertEquals(1, sim.notifier.of("alert").size(), sim.notifier.toString());
        assertTrue(sim.notifier.has("alert", "Offhand repair failed"), sim.notifier.toString());
    }

    @Test
    void nothingWantedNothingParks() {
        Sim sim = sim();
        Job job = job(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.bot.offhandRepairs);
        assertEquals(0, sim.bot.parks);
    }
}
