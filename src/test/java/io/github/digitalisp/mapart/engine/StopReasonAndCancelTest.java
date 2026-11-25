package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.command.QueueOps;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live bugs (10x test, 2026-10-11): after the bot died, two more stops (the respawn, then the 54-block respawn teleport)
 * overwrote the pause reason, so the status showed the teleport, not the death; and {@code mapart cancel} on a BLOCKED
 * job logged "clearing the platform" but did nothing until {@code resume}.
 */
class StopReasonAndCancelTest {
    private static final int LONG = 60_000;

    private static long alerts(Sim sim, String title) {
        return sim.notifier.of("alert").stream().filter(m -> m.title().equals(title)).count();
    }

    @Test
    void laterSafetyStopsKeepTheFirstReason() {
        Sim sim = new Sim();
        sim.addJob("j0300", "Stops", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(20, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());

        sim.engine.emergencyStop("the bot died");
        sim.engine.emergencyStop("respawn or dimension change while building");
        sim.engine.emergencyStop("server teleported the bot 54.2 blocks: 1.5, 64.0, 2.5 -> 55.7, 65.1, 2.5");
        String want = "SAFETY STOP: the bot died (+2 later: respawn or dimension change while building; "
            + "server teleported the bot 54.2 blocks: 1.5, 64.0, 2.5 -> 55.7, 65.1, 2.5)";
        assertEquals(want, sim.engine.status().pauseReason());
        assertEquals(want, sim.config.pauseReason, "persisted");
        assertEquals(1, alerts(sim, "SAFETY STOP"), "one alert, for the first stop: " + sim.notifier);
        assertTrue(sim.notifier.of("alert").getFirst().body().startsWith("the bot died"), sim.notifier.toString());
        String status = QueueOps.formatStatus(sim.engine.status(), sim.clock.millis());
        assertTrue(status.startsWith("**State:** paused: SAFETY STOP: the bot died (+2 later: respawn"), status);

        sim.engine.pause("lunch");
        assertTrue(sim.engine.status().pauseReason().startsWith("SAFETY STOP: the bot died (+3 later: respawn"),
            sim.engine.status().pauseReason());
        assertTrue(sim.engine.status().pauseReason().endsWith("; paused: lunch)"), sim.engine.status().pauseReason());

        // a restart keeps the first reason in front
        sim.engine.shutdown();
        sim.newEngine();
        sim.start();
        sim.run(5);
        assertTrue(sim.engine.safetyLocked());
        sim.engine.emergencyStop("server teleported the bot 9.0 blocks");
        assertTrue(sim.engine.status().pauseReason().startsWith("SAFETY STOP: the bot died (+"),
            sim.engine.status().pauseReason());
        assertEquals(1, alerts(sim, "SAFETY STOP"), sim.notifier.toString());

        sim.engine.resume();
        sim.run(2);
        assertFalse(sim.engine.status().paused());
        sim.engine.emergencyStop("stopped from Discord");
        assertEquals("SAFETY STOP: stopped from Discord", sim.engine.status().pauseReason());
        assertEquals(2, alerts(sim, "SAFETY STOP"), "a new stop after resume alerts again");
    }

    @Test
    void safetyReasonListsAtMostThreeLaterStops() {
        assertEquals("SAFETY STOP: a", BuildEngine.safetyReason("SAFETY STOP: a", List.of()));
        assertEquals("SAFETY STOP: a (+1 later: b)", BuildEngine.safetyReason("SAFETY STOP: a", List.of("b")));
        assertEquals("SAFETY STOP: a (+4 later: b; c; d; …)",
            BuildEngine.safetyReason("SAFETY STOP: a", List.of("b", "c", "d", "e")));
        String longOne = "x".repeat(300);
        assertTrue(BuildEngine.safetyReason("SAFETY STOP: a", List.of(longOne)).length() < 160);
    }

    /** BLOCKED in LOCK (no glass panes anywhere): a human is needed, unless the job is cancelled. */
    private static Job blockedJob(Sim sim) {
        sim.bot.containers.get(Sim.PANES).items.clear();
        Job job = sim.addJob("j0301", "Blocked", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("pane"), sim.state());
        return job;
    }

    @Test
    void cancellingABlockedJobClearsThePlatformWithoutResume() {
        Sim sim = new Sim();
        Job job = blockedJob(sim);
        assertTrue(sim.artBlocks(sim.moss) > 0);
        assertTrue(sim.store.cancel(job.id));
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> sim.notifier.has("info", "Cancelled job cleaned up"), LONG), sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, LONG), sim.state());
        assertEquals(0, sim.artBlocks(sim.moss), "platform cleared");
        assertEquals(JobStatus.CANCELLED, job.status);
        assertFalse(sim.notifier.has("info", "Resumed"), "no resume was needed");
        assertEquals(0, sim.machineBroken);
    }

    @Test
    void cancellingABlockedJobWhilePausedClearsAfterResume() {
        Sim sim = new Sim();
        Job job = blockedJob(sim);
        sim.engine.pause("checking the base");
        sim.run(2);
        assertTrue(sim.store.cancel(job.id));
        sim.engine.queueChanged();
        sim.run(400);
        assertEquals(Phase.BLOCKED, sim.phase(), "nothing moves while paused: " + sim.state());
        assertTrue(sim.artBlocks(sim.moss) > 0);
        assertTrue(sim.notifier.messages.stream().anyMatch(m -> m.title().equals("Job cancelled")
            && m.body().contains("after `!map resume`")), sim.notifier.toString());

        sim.engine.resume();
        assertTrue(sim.runUntil(() -> sim.notifier.has("info", "Cancelled job cleaned up"), LONG), sim.state());
        assertEquals(0, sim.artBlocks(sim.moss), "platform cleared after resume");
    }
}
