package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.EscapeRequest;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.RecordingNotifier.Msg;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setback back-off: a burst of small server setbacks stops the bot for a few seconds, then it steps away (the escape
 * ladder, SetbackEscapeTest); the burst after the ladder is used up pauses it ("stuck"), never a SAFETY STOP.
 */
class SetbackBackoffTest {
    private static final int LONG = 60_000;
    private static final PlatformGeometry MOSS = new PlatformGeometry(-64, -64, 173);
    private static final double CX = 1.5, CZ = 0.5;

    private static final class Rec implements TeleportGuard.Sink {
        final List<String> halts = new ArrayList<>();
        final List<String> stucks = new ArrayList<>();
        final List<String> warns = new ArrayList<>();
        final List<String> backoffs = new ArrayList<>();
        final List<Long> millis = new ArrayList<>();
        final List<Integer> rungs = new ArrayList<>();
        final List<double[]> at = new ArrayList<>();
        final List<Double> setbacks = new ArrayList<>();

        @Override
        public void halt(String reason) {
            halts.add(reason);
        }

        @Override
        public void warn(String title, String body) {
            warns.add(title + ": " + body);
        }

        @Override
        public void backoff(String reason, long ms) {
            backoffs.add(reason);
            millis.add(ms);
        }

        @Override
        public void backoff(String reason, long ms, double x, double y, double z, int rung) {
            backoff(reason, ms);
            rungs.add(rung);
            at.add(new double[]{x, y, z});
        }

        @Override
        public void stuck(String reason) {
            stucks.add(reason);
        }

        @Override
        public void setback(double distance) {
            setbacks.add(distance);
        }
    }

    private static TeleportGuard guard(MapArtConfig.SafetyCfg cfg, Rec rec, AtomicLong now) {
        return new TeleportGuard(cfg, () -> List.of(MOSS), now::get, rec);
    }

    private static void armAtCentre(TeleportGuard g, MapArtConfig.SafetyCfg cfg) {
        g.botTicksStarted();
        for (int i = 0; i < cfg.startGraceTicks + 1; i++) g.tick(CX, 174, CZ, false);
    }

    /** {@code n} setbacks of 0.5 blocks, {@code stepMs} apart (the bot's tick puts it back at the centre each time). */
    private static void setbacks(TeleportGuard g, AtomicLong now, int n, long stepMs) {
        for (int i = 0; i < n; i++) {
            g.teleport(false, CX + 0.5, false, 174, false, CZ, false);
            g.tick(CX, 174, CZ, false);
            now.addAndGet(stepMs);
        }
    }

    // ---------------------------------------------------------------- TeleportGuard

    /**
     * Live 2026-11-14: five minutes into the muncher ride the anti-cheat set the bot back 75 blocks to the start rail
     * and the server then put it beside its cart; the SAFETY STOP froze the bot while the cart went on. In a ride
     * phase a far teleport is a warning and counts as no setback; out of one it stops as before.
     */
    @Test
    void aFarTeleportDuringTheRideWarnsAndNeverStops() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        g.rideMode(true);
        g.teleport(false, CX + 75, false, 174, false, CZ, false);
        g.teleport(false, CX, false, 174, false, CZ, false);
        assertTrue(rec.halts.isEmpty(), rec.halts.toString());
        assertTrue(rec.backoffs.isEmpty() && rec.setbacks.isEmpty(), "no setback is counted in a ride");
        assertEquals(2, rec.warns.size(), rec.warns.toString());
        assertTrue(rec.warns.getFirst().startsWith("Teleport during the ride"), rec.warns.getFirst());
        g.rideMode(false);
        g.teleport(false, CX + 75, false, 174, false, CZ, false);
        assertEquals(1, rec.halts.size(), "out of the ride phases the same teleport is a SAFETY STOP");
    }

    @Test
    void burstOfSetbacksBacksOff() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        setbacks(g, now, 4, 100);
        assertTrue(rec.backoffs.isEmpty(), "4 setbacks are no burst");
        setbacks(g, now, 1, 100);
        assertEquals(1, rec.backoffs.size(), rec.backoffs.toString());
        assertEquals(5_000L, rec.millis.getFirst());
        assertEquals("5 server setbacks in 0.4 s (last 0.5 blocks)", rec.backoffs.getFirst());
        assertEquals(List.of(1), rec.rungs, "the first rung of the escape ladder");
        // where the 5th setback put the bot, not where it stood before it
        assertArrayEquals(new double[]{CX + 0.5, 174, CZ}, rec.at.getFirst());
        assertEquals(5, rec.setbacks.size(), "every small setback is passed on");
        assertTrue(rec.halts.isEmpty());
        assertTrue(rec.stucks.isEmpty());
    }

    @Test
    void slowSetbacksNeverBackOffButStillAlert() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        setbacks(g, now, 40, 600); // at most 4 in any 2 s window
        assertTrue(rec.backoffs.isEmpty(), rec.backoffs.toString());
        assertTrue(rec.halts.isEmpty());
        assertEquals(1, rec.warns.size(), "the 10-in-300 s alert is unchanged");
        assertTrue(rec.warns.getFirst().startsWith("Frequent setbacks"), rec.warns.getFirst());
    }

    @Test
    void setbacksWhileStandingStillStartNoNewBurst() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        setbacks(g, now, 5, 100);              // back-off from 1_000_400 to 1_005_400
        setbacks(g, now, 40, 100);             // in-flight replies while standing still
        assertEquals(1, rec.backoffs.size(), rec.backoffs.toString());
        assertEquals(45, rec.setbacks.size(), "setbacks while standing still are still passed on");
        now.set(1_005_400);
        setbacks(g, now, 5, 100);
        assertEquals(2, rec.backoffs.size(), "a burst after the back-off backs off again");
        assertEquals(List.of(1, 2), rec.rungs);
        assertEquals(50, rec.setbacks.size());
        assertTrue(rec.halts.isEmpty());
    }

    @Test
    void rungsOneToThreeThenTheFourthBurstIsStuckNeverAHalt() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        for (int k = 0; k < 3; k++) {
            setbacks(g, now, 5, 100);
            now.addAndGet(5_000);              // the back-off (and the escape) is over
        }
        assertEquals(3, rec.backoffs.size(), rec.backoffs.toString());
        assertEquals(List.of(1, 2, 3), rec.rungs);
        for (double[] p : rec.at) assertArrayEquals(new double[]{CX + 0.5, 174, CZ}, p);
        assertTrue(rec.halts.isEmpty());
        assertTrue(rec.stucks.isEmpty());

        setbacks(g, now, 5, 100);
        assertEquals(3, rec.backoffs.size(), "the 4th burst pauses instead of backing off");
        assertEquals(List.of("repeated server setbacks: 3 back-offs and escapes in 2 min did not help (last at "
            + CoordFormat.point(CX + 0.5, 174, CZ) + ")"), rec.stucks);
        assertTrue(rec.halts.isEmpty(), "never a SAFETY STOP: " + rec.halts);

        // the ladder starts over after the pause
        now.addAndGet(5_000);
        setbacks(g, now, 5, 100);
        assertEquals(4, rec.backoffs.size());
        assertEquals(List.of(1, 2, 3, 1), rec.rungs);
        assertEquals(1, rec.stucks.size());

        // big teleports, respawns and deaths still halt (SAFETY STOP) at once, whatever the ladder
        g.teleport(false, CX + 20, false, 174, false, CZ, false);
        g.respawn(false);
        g.death();
        assertEquals(3, rec.halts.size(), rec.halts.toString());
        assertTrue(rec.halts.getFirst().startsWith("server teleported the bot 20.0 blocks"), rec.halts.getFirst());
        assertEquals(1, rec.stucks.size());
    }

    @Test
    void theRungCountsBackoffsWithinTwoMinutesOnly() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        setbacks(g, now, 5, 100);
        now.addAndGet(70_000);
        setbacks(g, now, 5, 100);
        now.addAndGet(70_000);                 // the first back-off is out of the window now
        setbacks(g, now, 5, 100);
        assertEquals(List.of(1, 2, 2), rec.rungs);
        assertTrue(rec.stucks.isEmpty());
    }

    @Test
    void backoffsSpreadOverMoreThanTwoMinutesNeverHalt() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        for (int k = 0; k < 10; k++) {
            setbacks(g, now, 5, 100);
            now.addAndGet(61_000);
        }
        assertEquals(10, rec.backoffs.size());
        assertTrue(rec.halts.isEmpty(), rec.halts.toString());
        assertTrue(rec.stucks.isEmpty(), rec.stucks.toString());
    }

    @Test
    void zeroDisables() {
        for (String field : List.of("burstCount", "burstWindow", "backoffSeconds", "guard")) {
            var cfg = new MapArtConfig.SafetyCfg();
            switch (field) {
                case "burstCount" -> cfg.setbackBurstCount = 0;
                case "burstWindow" -> cfg.setbackBurstWindowMillis = 0;
                case "backoffSeconds" -> cfg.setbackBackoffSeconds = 0;
                default -> cfg.teleportGuard = false;
            }
            var rec = new Rec();
            var now = new AtomicLong(1_000_000);
            var g = guard(cfg, rec, now);
            armAtCentre(g, cfg);
            for (int k = 0; k < 6; k++) {
                setbacks(g, now, 10, 50);
                now.addAndGet(6_000);
            }
            assertTrue(rec.backoffs.isEmpty(), field + ": " + rec.backoffs);
            assertTrue(rec.halts.isEmpty(), field + ": " + rec.halts);
            assertTrue(rec.stucks.isEmpty(), field + ": " + rec.stucks);
        }

        // no escalation: back-offs (and escapes) go on, never a pause or a halt
        var cfg = new MapArtConfig.SafetyCfg();
        cfg.setbackBackoffMaxPerWindow = 0;
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        for (int k = 0; k < 6; k++) {
            setbacks(g, now, 5, 100);
            now.addAndGet(5_000);
        }
        assertEquals(6, rec.backoffs.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), rec.rungs);
        assertTrue(rec.halts.isEmpty());
        assertTrue(rec.stucks.isEmpty());
    }

    @Test
    void ignoredDuringGraceInQueueAndWhilePlayerControls() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var now = new AtomicLong(1_000_000);
        var g = guard(cfg, rec, now);

        g.botTicksStarted();                    // login position sync: grace period
        for (int i = 0; i < 10; i++) {
            g.teleport(false, CX + 0.5, false, 174, false, CZ, false);
            g.tick(CX, 174, CZ, false);
        }
        assertTrue(rec.backoffs.isEmpty(), "grace");

        armAtCentre(g, cfg);
        for (int i = 0; i < 10; i++) g.teleport(false, CX + 0.5 * (i % 2), false, 174, false, CZ, true);
        assertTrue(rec.backoffs.isEmpty(), "2b2t queue");

        armAtCentre(g, cfg);
        g.botTicksStopped();                    // a player took control
        for (int i = 0; i < 10; i++) g.teleport(false, CX + 0.5 * (i % 2), false, 174, false, CZ, false);
        assertTrue(rec.backoffs.isEmpty(), "player in control");

        armAtCentre(g, cfg);
        setbacks(g, now, 4, 100);
        armAtCentre(g, cfg);                    // reconnect: a half burst does not carry over
        setbacks(g, now, 1, 100);
        assertTrue(rec.backoffs.isEmpty(), rec.backoffs.toString());
        assertTrue(rec.halts.isEmpty());
        assertEquals(5, rec.setbacks.size(), "only the armed setbacks are passed on: " + rec.setbacks);
    }

    // ---------------------------------------------------------------- engine

    private static List<Msg> backoffLines(Sim sim) {
        return sim.notifier.of("detail").stream().filter(m -> m.title().equals("Backing off")).toList();
    }

    @Test
    void backoffCancelsTheTaskStandsStillThenFinishes() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 8; // lanes take a while: a lane is mid-way when the burst comes
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0050", "Pushed", 1, 1, List.of("moss"), (x, z) -> art);
        AtomicInteger hooks = new AtomicInteger();
        sim.engine.setHaltHook(hooks::incrementAndGet);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD
            && sim.bot.lastTask != null && !sim.bot.lastTask.isFinished(), LONG), sim.state());
        BotTask running = sim.bot.lastTask;

        sim.engine.backoff("5 server setbacks in 0.4 s (last 0.5 blocks)", 5_000);
        assertEquals(1, hooks.get(), "Baritone is stopped in the call");
        int placed = sim.bot.placed;
        int tasks = sim.bot.history.size();
        Map<String, Integer> inventory = sim.inv.contents();
        double x = sim.world.x, y = sim.world.y, z = sim.world.z;
        int eats = sim.bot.eats;

        sim.tick();
        assertTrue(running.isFinished(), "the running task is cancelled before it ticks again");
        assertEquals(placed, sim.bot.placed, "nothing placed after the call");
        assertEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note());
        assertEquals(Phase.BUILD, sim.phase());
        assertFalse(sim.engine.status().paused(), "not a pause");
        assertFalse(sim.config.paused);
        assertFalse(sim.engine.safetyLocked());
        assertFalse(sim.modules.isEnabled("AntiAFK"), "modules stay off: nothing else moves the bot");

        sim.run(98); // 99 ticks = 4.95 s of the 5 s
        assertEquals(placed, sim.bot.placed, "no placements while backing off");
        assertEquals(tasks, sim.bot.history.size(), "no walks, clicks or inventory actions while backing off");
        assertEquals(inventory, sim.inv.contents());
        assertEquals(eats, sim.bot.eats);
        assertEquals(x, sim.world.x);
        assertEquals(y, sim.world.y);
        assertEquals(z, sim.world.z);
        assertEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note());
        List<Msg> lines = backoffLines(sim);
        assertEquals(1, lines.size(), sim.notifier.toString());
        assertTrue(lines.getFirst().body().startsWith("5 server setbacks in 0.4 s (last 0.5 blocks). Standing still "
            + "for 5 s"), lines.getFirst().body());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());

        sim.tick(); // 5 s are over: the phase carries on at once
        assertTrue(sim.bot.history.size() > tasks, "a fresh step after the back-off");
        assertNotEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed twice: " + e.getKey());
        assertFalse(sim.notifier.has("alert", "SAFETY STOP"));
        assertEquals(1, hooks.get());
    }

    @Test
    void oneDiscordLinePerMinuteAtMost() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0052", "Lines", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(24, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.backoff("first", 5_000);
        sim.run(120);
        sim.engine.backoff("second", 5_000);
        sim.run(120);
        assertEquals(1, backoffLines(sim).size(), sim.notifier.toString());
        sim.run(1_200); // a minute later
        sim.engine.backoff("third", 5_000);
        sim.tick();
        List<Msg> lines = backoffLines(sim);
        assertEquals(2, lines.size(), sim.notifier.toString());
        assertTrue(lines.get(1).body().startsWith("third"), lines.get(1).body());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void repeatedBurstsEscapeOnEachRungThenPauseStuckNotSafetyStopAndResumeFinishes() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 8; // still building when the bursts come
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0051", "Pushed", 1, 1, List.of("moss"), (x, z) -> art);
        AtomicInteger hooks = new AtomicInteger();
        sim.engine.setHaltHook(hooks::incrementAndGet);
        // the adapter's wiring (MapArtModule): no client loop, so the guard's decisions run in the call
        TeleportGuard guard = new TeleportGuard(sim.config.safety, List::of, sim.clock::millis,
            TickThread.guardSink(sim.engine, () -> null, Runnable::run, sim.notifier::info));
        Runnable tick = () -> {
            guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
            sim.tick();
        };
        Runnable burst = () -> {
            for (int i = 0; i < 5; i++) {
                guard.teleport(true, 0.5, true, 0, true, 0, false); // 0.5 blocks from the last known position
                tick.run();
            }
        };
        sim.start();
        guard.botTicksStarted();
        for (int i = 0; i <= sim.config.safety.startGraceTicks; i++) guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
        for (int i = 0; i < LONG && !(sim.bot.placed > 300 && sim.phase() == Phase.BUILD); i++) tick.run();
        assertEquals(Phase.BUILD, sim.phase(), sim.state());

        for (int k = 0; k < 3; k++) {
            burst.run();
            assertEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note(), "back-off " + (k + 1));
            for (int i = 0; i < 130; i++) tick.run(); // 6.5 s: standing still, the escape, then the bot works again
            assertEquals(k + 1, sim.bot.escapes.size(), "an escape after back-off " + (k + 1));
            assertTrue(sim.bot.escapeTasks.getLast().isFinished());
            assertFalse(sim.engine.safetyLocked());
            assertFalse(sim.config.paused);
        }
        assertEquals(List.of(1, 2, 3), sim.bot.escapes.stream().map(EscapeRequest::rung).toList());
        assertEquals(3, hooks.get());
        assertEquals(1, backoffLines(sim).size(), "one line per minute: " + sim.notifier);

        burst.run();
        assertTrue(sim.config.paused, "pause persisted");
        assertTrue(sim.engine.status().paused());
        assertFalse(sim.engine.safetyLocked(), "stuck is not a SAFETY STOP");
        assertEquals(4, hooks.get(), "Baritone stopped at once");
        assertEquals(3, sim.bot.escapes.size(), "no escape after the ladder is used up");
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertEquals("Stuck: paused", alerts.getFirst().title());
        assertTrue(alerts.getFirst().body().startsWith("repeated server setbacks: 3 back-offs and escapes in 2 min did "
            + "not help (last at "), alerts.getFirst().body());
        assertTrue(alerts.getFirst().body().contains("The bot tried 3 escapes and is paused."), alerts.getFirst().body());
        assertTrue(alerts.getFirst().body().endsWith("`!map resume`.\nPushed (#j0051)"), alerts.getFirst().body());
        assertFalse(sim.notifier.has("alert", "SAFETY STOP"));
        assertTrue(sim.engine.status().pauseReason().startsWith("stuck: repeated server setbacks"),
            sim.engine.status().pauseReason());
        assertEquals(sim.engine.status().pauseReason(), sim.config.pauseReason);

        int placed = sim.bot.placed;
        int tasks = sim.bot.history.size();
        for (int i = 0; i < 400; i++) tick.run();
        assertEquals(placed, sim.bot.placed, "nothing placed while paused");
        assertEquals(tasks, sim.bot.history.size(), "no tasks while paused");

        sim.engine.resume();
        assertFalse(sim.config.paused);
        assertEquals("", sim.config.pauseReason);
        for (int i = 0; i < LONG && job.status != JobStatus.DONE; i++) tick.run();
        assertEquals(JobStatus.DONE, job.status, sim.state());
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue(), "placed twice: " + e.getKey());
        assertEquals(1, sim.notifier.of("alert").size(), sim.notifier.toString());
    }
}
