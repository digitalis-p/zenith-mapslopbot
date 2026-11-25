package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.EscapeRequest;
import io.github.digitalisp.mapart.engine.SimFakes.RecordingNotifier.Msg;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setback escape ladder, end to end through the real {@link TeleportGuard}
 * and {@link TickThread#guardSink}: after a back-off the engine steps away from the burst cell
 * ({@link io.github.digitalisp.mapart.bot.BotActions#escape}) before the phase carries on, the lanes keep away from that cell for
 * the rest of the tile, and the burst after the ladder is used up pauses with an alert ("stuck"), never a SAFETY STOP
 * (the ladder's full run: {@link SetbackBackoffTest}).
 */
class SetbackEscapeTest {
    private static final int LONG = 60_000;

    /** The adapter's wiring (MapArtModule) on the sim clock; no client loop: the guard's decisions run in the call. */
    private static final class Rig {
        final Sim sim;
        final TeleportGuard guard;
        /** Every small setback the guard passed on (the silent-look monitor's input). */
        final List<Double> setbacks = new ArrayList<>();
        /**
         * The 2b2t model of the incident: the server sets the bot back (0.0 blocks) on every tick a lane task acts with
         * the bot's feet in this cell; null = never.
         */
        Pos badCell;
        int serverSetbacks;

        Rig(Sim sim) {
            this.sim = sim;
            guard = new TeleportGuard(sim.config.safety, List::of, sim.clock::millis,
                TickThread.guardSink(sim.engine, () -> null, Runnable::run, sim.notifier::info, setbacks::add));
        }

        void start() {
            sim.start();
            guard.botTicksStarted();
            for (int i = 0; i <= sim.config.safety.startGraceTicks; i++)
                guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
        }

        /** The module's ClientBotTick: the guard sees the position, the server answers the movement, the engine ticks. */
        void tick() {
            guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
            BotTask t = sim.bot.lastTask;
            if (badCell != null && t != null && !t.isFinished() && t.name().startsWith("lane ")
                && sim.world.playerBlock().equals(badCell)) {
                serverSetbacks++;
                guard.teleport(true, 0, true, 0, true, 0, false);
            }
            sim.tick();
        }

        void run(int n) {
            for (int i = 0; i < n; i++) tick();
        }

        boolean runUntil(BooleanSupplier cond, int max) {
            for (int i = 0; i < max; i++) {
                if (cond.getAsBoolean()) return true;
                tick();
            }
            return cond.getAsBoolean();
        }

        /** Building, with a lane task half done. */
        void untilMidLane() {
            assertTrue(runUntil(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD && sim.bot.lastTask != null
                && sim.bot.lastTask.name().startsWith("lane ") && !sim.bot.lastTask.isFinished(), LONG), sim.state());
        }

        /** A burst of five 0.0-block setbacks where the bot stands, then the tick that applies the back-off. */
        Pos burst() {
            guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
            Pos cell = sim.world.playerBlock();
            for (int i = 0; i < 5; i++) guard.teleport(true, 0, true, 0, true, 0, false);
            sim.tick();
            return cell;
        }
    }

    private static Sim building(String id, int tilesX) {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 8; // lanes take a while: a lane is mid-way when the burst comes
        TileArt art = Sim.stripes(tilesX > 1 ? 12 : 24, 0); // two tiles: within the sim's carpet stock
        sim.addJob(id, "Setbacks", tilesX, 1, List.of("moss"), (x, z) -> art);
        return sim;
    }

    private static Job job(Sim sim) {
        return sim.store.jobs.getFirst();
    }

    private static List<Msg> backoffLines(Sim sim) {
        return sim.notifier.of("detail").stream().filter(m -> m.title().equals("Backing off")).toList();
    }

    private static void assertNeverStopped(Sim sim) {
        assertFalse(sim.config.paused, sim.state());
        assertFalse(sim.engine.safetyLocked());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void afterTheBackoffTheBotStepsAwayFromTheBurstCellAndTheLanesKeepAwayFromIt() {
        Sim sim = building("j0070", 1);
        Rig rig = new Rig(sim);
        rig.start();
        rig.untilMidLane();

        Pos cell = rig.burst();
        assertEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note());
        assertEquals(5, rig.setbacks.size(), "every setback passed on: " + rig.setbacks);
        rig.run(95);
        assertTrue(sim.bot.escapes.isEmpty(), "no escape while the back-off stands still");
        assertTrue(rig.runUntil(() -> !sim.bot.escapes.isEmpty(), 10), sim.state());

        EscapeRequest r = sim.bot.escapes.getFirst();
        assertEquals(1, r.rung());
        assertEquals(cell, r.cell());
        assertEquals(cell.x() + 0.5, r.x());
        assertEquals(cell.y(), r.y());
        assertEquals(cell.z() + 0.5, r.z());
        assertEquals(Set.of(cell), r.avoid());
        assertEquals(Phase.BUILD, sim.phase(), "the escape runs inside the phase");
        assertTrue(sim.engine.status().note().startsWith(BuildEngine.BACKOFF_NOTE), "the presence still shows backing off");
        assertTrue(sim.engine.status().note().contains("escape 1 of 3"), sim.engine.status().note());

        int lanesBefore = sim.bot.laneAvoids.size();
        assertTrue(rig.runUntil(() -> sim.bot.laneAvoids.size() > lanesBefore, 100), sim.state());
        assertTrue(sim.bot.escapeTasks.getFirst().isFinished());
        assertEquals(Set.of(cell), sim.bot.laneAvoids.get(lanesBefore), "the next lane keeps away from the burst cell");
        assertNotEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note());
        assertFalse(sim.engine.status().note().startsWith(BuildEngine.BACKOFF_NOTE), sim.engine.status().note());

        List<Msg> lines = backoffLines(sim);
        assertEquals(1, lines.size(), sim.notifier.toString());
        assertTrue(lines.getFirst().body().startsWith("5 server setbacks in 0.0 s (last 0.0 blocks). Standing still for "
            + "5 s, then stepping away (escape 1 of 3)."), lines.getFirst().body());

        assertTrue(rig.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        for (Set<Pos> avoid : sim.bot.laneAvoids.subList(lanesBefore, sim.bot.laneAvoids.size()))
            assertEquals(Set.of(cell), avoid, "every lane of the tile after the burst");
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed twice: " + e.getKey());
        assertEquals(1, sim.bot.escapes.size());
        assertNeverStopped(sim);
    }

    /** the owner's case: setbacks at one spot recover by themselves before any stop. */
    @Test
    void repeatedSetbacksAtOneSpotRecoverByThemselvesBeforeAnyStop() {
        Sim sim = building("j0071", 1);
        Rig rig = new Rig(sim);
        rig.start();
        rig.untilMidLane();
        String laneAtBadSpot = sim.bot.lastTask.name();
        rig.badCell = sim.world.playerBlock(); // from now on the server sets the bot back while it works here

        assertTrue(rig.runUntil(() -> !sim.bot.escapes.isEmpty(), 200), sim.state());
        assertEquals(5, rig.serverSetbacks, "one burst, then the back-off stood still");
        assertEquals(1, sim.bot.escapes.getFirst().rung());
        assertEquals(rig.badCell, sim.bot.escapes.getFirst().cell());

        assertTrue(rig.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(5, rig.serverSetbacks, "no setbacks after the escape: the lanes keep away from the spot");
        assertEquals(1, sim.bot.escapes.size(), "one rung was enough");
        assertTrue(sim.bot.laneStandsMoved > 0, "the interrupted lane ran again from another stand spot");
        assertTrue(sim.bot.history.stream().filter(h -> h.equals(laneAtBadSpot)).count() >= 2,
            "the interrupted lane ran again: " + laneAtBadSpot);
        assertEquals(1, backoffLines(sim).size(), sim.notifier.toString());
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed twice: " + e.getKey());
        assertNeverStopped(sim);
    }

    @Test
    void aFailedEscapeIsLoggedAndTheEngineCarriesOn() {
        Sim sim = building("j0072", 1);
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.bot.escapeFailure = r -> "no open floor cell within 3 blocks";
        Rig rig = new Rig(sim);
        rig.start();
        rig.untilMidLane();

        Pos cell = rig.burst();
        assertTrue(rig.runUntil(() -> !sim.bot.escapeTasks.isEmpty() && sim.bot.escapeTasks.getFirst().isFinished(), 200),
            sim.state());
        List<String> warns = log.at(Level.WARN);
        assertTrue(warns.contains("escape rung 1 of 3 failed: escape rung 1 from " + cell.key()
            + ": no open floor cell within 3 blocks; carrying on in BUILD"), warns.toString());
        assertTrue(warns.stream().anyMatch(l -> l.startsWith("escape rung 1 of 3 at " + cell.key() + ": stepping away")),
            warns.toString());

        assertTrue(rig.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.contains("(attempt")),
            "a failed escape is no step failure: " + log.at(Level.WARN));
        assertTrue(log.at(Level.ERROR).isEmpty(), log.at(Level.ERROR).toString());
        assertTrue(sim.bot.laneAvoids.getLast().contains(cell), "the lanes still keep away from the cell");
        assertNeverStopped(sim);
    }

    @Test
    void aPauseDuringTheBackoffDropsTheEscapeAndResumeCarriesOn() {
        Sim sim = building("j0073", 1);
        Rig rig = new Rig(sim);
        rig.start();
        rig.untilMidLane();
        rig.burst();
        rig.run(20);
        sim.engine.pause("lunch");
        rig.run(300);
        assertTrue(sim.bot.escapes.isEmpty(), "the pause dropped the escape");
        sim.engine.resume();
        assertTrue(rig.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.escapes.isEmpty(), "no stale escape after the resume");
    }

    @Test
    void aNewerBackoffReplacesARunningEscape() {
        Sim sim = building("j0074", 1);
        sim.bot.escapeTicks = 60; // still stepping away when the next burst comes
        Rig rig = new Rig(sim);
        rig.start();
        rig.untilMidLane();

        Pos cell = rig.burst();
        assertTrue(rig.runUntil(() -> !sim.bot.escapes.isEmpty(), 200), sim.state());
        rig.run(5);
        BotTask first = sim.bot.escapeTasks.getFirst();
        assertFalse(first.isFinished());
        rig.burst();
        assertTrue(first.isFinished(), "the newer back-off cancels the running escape");
        assertEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note());
        rig.run(95);
        assertEquals(1, sim.bot.escapes.size(), "standing still first");
        assertTrue(rig.runUntil(() -> sim.bot.escapes.size() == 2, 10), sim.state());
        EscapeRequest second = sim.bot.escapes.get(1);
        assertEquals(2, second.rung());
        assertTrue(second.avoid().contains(cell), second.avoid().toString());

        assertTrue(rig.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertEquals(2, sim.bot.escapes.size());
        assertNeverStopped(sim);
    }

    @Test
    void burstCellsAreForgottenWhenTheTileEnds() {
        Sim sim = building("j0075", 2);
        Rig rig = new Rig(sim);
        rig.start();
        rig.untilMidLane();
        assertEquals(0, sim.engine.status().tileX());
        Pos cell = rig.burst();
        assertTrue(rig.runUntil(() -> !sim.bot.laneAvoids.isEmpty() && sim.bot.laneAvoids.getLast().contains(cell), 300),
            sim.state());

        assertTrue(rig.runUntil(() -> sim.engine.status().tileX() == 1 && sim.phase() == Phase.BUILD, LONG), sim.state());
        int lanesBefore = sim.bot.laneAvoids.size();
        assertTrue(rig.runUntil(() -> sim.bot.laneAvoids.size() > lanesBefore, LONG), sim.state());
        assertEquals(Set.of(), sim.bot.laneAvoids.get(lanesBefore), "the next tile starts with no burst cells");
        assertTrue(rig.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertNeverStopped(sim);
    }

    @Test
    void stuckPausesWithAnAlertNotASafetyStopAndResumeClearsIt() {
        Sim sim = building("j0076", 1);
        Rig rig = new Rig(sim);
        rig.start();
        rig.untilMidLane();
        var running = sim.bot.lastTask;

        sim.engine.stuck("repeated server setbacks: test");
        assertTrue(sim.config.paused, "persisted in the call");
        assertEquals("stuck: repeated server setbacks: test", sim.config.pauseReason);
        rig.tick();
        assertTrue(running.isFinished(), "the task is cancelled before it ticks again");
        assertFalse(sim.engine.safetyLocked());
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertEquals("Stuck: paused", alerts.getFirst().title());
        assertTrue(alerts.getFirst().body().startsWith("repeated server setbacks: test\nThe bot tried 0 escapes and is paused."),
            alerts.getFirst().body());
        assertTrue(alerts.getFirst().body().endsWith("\nSetbacks (#j0076)"), "the job id last: " + alerts.getFirst().body());
        assertFalse(sim.notifier.has("info", "Paused"), "the alert is the one message");

        sim.engine.stuck("again");
        rig.tick();
        assertEquals(1, sim.notifier.of("alert").size(), "no second alert while paused");
        assertEquals("stuck: repeated server setbacks: test", sim.engine.status().pauseReason());

        int placed = sim.bot.placed;
        rig.run(200);
        assertEquals(placed, sim.bot.placed);
        sim.engine.resume();
        assertFalse(sim.config.paused);
        assertEquals("", sim.config.pauseReason);
        assertTrue(rig.runUntil(() -> job(sim).status == JobStatus.DONE, LONG), sim.state());
        assertFalse(sim.notifier.has("alert", "SAFETY STOP"));
    }
}
