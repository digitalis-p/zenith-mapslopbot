package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyStopTest {
    private static final int LONG = 60_000;
    // moss platform from the site survey; centre (0, 0)
    private static final PlatformGeometry MOSS = new PlatformGeometry(-64, -64, 173);
    private static final double CX = 1.5, CZ = 0.5;

    private static final class Rec implements TeleportGuard.Sink {
        final List<String> halts = new ArrayList<>();
        final List<String> warns = new ArrayList<>();

        @Override
        public void halt(String reason) {
            halts.add(reason);
        }

        @Override
        public void warn(String title, String body) {
            warns.add(title + ": " + body);
        }

        @Override
        public void backoff(String reason, long millis) {
            // setback back-off: SetbackBackoffTest
        }
    }

    private static TeleportGuard guard(MapArtConfig.SafetyCfg cfg, Rec rec, AtomicLong now) {
        return new TeleportGuard(cfg, () -> List.of(MOSS), now::get, rec);
    }

    /** Starts bot ticks at the platform centre and runs past the grace period. */
    private static void armAtCentre(TeleportGuard g, MapArtConfig.SafetyCfg cfg) {
        g.botTicksStarted();
        for (int i = 0; i < cfg.startGraceTicks + 1; i++) g.tick(CX, 174, CZ, false);
    }

    @Test
    void bigTeleportHalts() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var g = guard(cfg, rec, new AtomicLong());
        armAtCentre(g, cfg);
        g.teleport(false, CX + 20, false, 174, false, CZ, false);
        assertEquals(1, rec.halts.size(), rec.halts.toString());
        assertTrue(rec.halts.getFirst().contains("20.0 blocks"), rec.halts.getFirst());
    }

    @Test
    void armsOnFirstTickWhenStartEventWasMissed() {
        // builder switched on mid-session: ClientBotTick.Starting fired before the module was enabled
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var g = guard(cfg, rec, new AtomicLong());
        for (int i = 0; i < cfg.startGraceTicks + 1; i++) g.tick(CX, 174, CZ, false);
        g.teleport(false, CX + 20, false, 174, false, CZ, false);
        assertEquals(1, rec.halts.size(), rec.halts.toString());
    }

    @Test
    void relativeTeleportResolvedAgainstLastPosition() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var g = guard(cfg, rec, new AtomicLong());
        armAtCentre(g, cfg);
        g.teleport(true, 0.5, true, 0, true, -0.5, false);
        assertTrue(rec.halts.isEmpty(), "small relative setback must not halt");
        g.teleport(true, 0, true, 50, true, 0, false);
        assertEquals(1, rec.halts.size(), "relative +50 Y must halt");
    }

    @Test
    void smallSetbacksOnlyAlertWhenFrequent() {
        var cfg = new MapArtConfig.SafetyCfg();
        cfg.setbackAlertCount = 3;
        var rec = new Rec();
        var now = new AtomicLong(1_000);
        var g = guard(cfg, rec, now);
        armAtCentre(g, cfg);
        for (int i = 0; i < 2; i++) {
            g.teleport(false, CX + 1, false, 174, false, CZ, false);
            g.tick(CX, 174, CZ, false);
            now.addAndGet(1_000);
        }
        assertTrue(rec.warns.isEmpty());
        g.teleport(false, CX + 1, false, 174, false, CZ, false);
        assertEquals(1, rec.warns.size(), rec.warns.toString());
        g.teleport(false, CX + 1.5, false, 174, false, CZ, false);
        assertEquals(1, rec.warns.size(), "one alert per window");
        assertTrue(rec.halts.isEmpty());
    }

    @Test
    void loginSyncIgnoredButFarFromBaseHalts() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var g = guard(cfg, rec, new AtomicLong());
        g.botTicksStarted();
        g.teleport(false, 1000, false, 70, false, 1000, false);   // login sync to somewhere else entirely
        assertTrue(rec.halts.isEmpty(), "sync during grace is not a teleport");
        for (int i = 0; i < cfg.startGraceTicks; i++) g.tick(1000, 70, 1000, false);
        assertEquals(1, rec.halts.size(), rec.halts.toString());
        assertTrue(rec.halts.getFirst().contains("from the nearest platform"));
    }

    @Test
    void loginAtBaseDoesNotHalt() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var g = guard(cfg, rec, new AtomicLong());
        g.botTicksStarted();
        g.teleport(false, -60.5, false, 174, false, -67.5, false);
        for (int i = 0; i < cfg.startGraceTicks + 50; i++) g.tick(-60.5, 174, -67.5, false);
        assertTrue(rec.halts.isEmpty(), rec.halts.toString());
    }

    @Test
    void ignoredWhilePlayerControlsOrInQueueOrDisabled() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var g = guard(cfg, rec, new AtomicLong());
        armAtCentre(g, cfg);
        g.botTicksStopped();                                       // a player took control
        g.teleport(false, CX + 500, false, 174, false, CZ, false);
        g.respawn(false);
        g.death();
        assertTrue(rec.halts.isEmpty(), "player movement is not judged");

        armAtCentre(g, cfg);
        g.teleport(false, CX + 500, false, 174, false, CZ, true);  // 2b2t queue
        assertTrue(rec.halts.isEmpty(), "queue teleports are not judged");

        cfg.teleportGuard = false;
        armAtCentre(g, cfg);
        g.teleport(false, CX + 500, false, 174, false, CZ, false);
        assertTrue(rec.halts.isEmpty(), "disabled guard never halts");
    }

    @Test
    void respawnAndDeathHalt() {
        var cfg = new MapArtConfig.SafetyCfg();
        var rec = new Rec();
        var g = guard(cfg, rec, new AtomicLong());
        armAtCentre(g, cfg);
        g.respawn(false);
        g.death();
        assertEquals(2, rec.halts.size(), rec.halts.toString());
    }

    @Test
    void emergencyStopHaltsBuildPersistsAndNeedsResume() {
        Sim sim = new Sim();
        TileArt art = Sim.stripes(24, 0);
        Job job = sim.addJob("j0042", "Safety", 1, 1, List.of("moss"), (x, z) -> art);
        AtomicInteger hooks = new AtomicInteger();
        sim.engine.setHaltHook(hooks::incrementAndGet);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD, LONG), sim.state());
        var running = sim.bot.lastTask;

        sim.engine.emergencyStop("server teleported the bot 40.0 blocks");
        assertEquals(1, hooks.get(), "halt hook (Baritone stop) runs immediately");
        assertTrue(running.isFinished(), "current task cancelled immediately, not on the next tick");
        assertTrue(sim.config.paused, "pause persisted in config");
        assertTrue(sim.engine.safetyLocked());
        assertTrue(sim.engine.status().pauseReason().startsWith("SAFETY STOP"));
        var alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().title().contains("SAFETY STOP"));

        sim.engine.emergencyStop("again");
        assertEquals(1, sim.notifier.of("alert").size(), "alert once per lock");

        int placed = sim.bot.placed;
        int tasks = sim.bot.history.size();
        sim.run(1_000);
        assertEquals(placed, sim.bot.placed, "nothing placed while locked");
        assertEquals(tasks, sim.bot.history.size(), "no tasks while locked");
        assertTrue(sim.modules.isEnabled("AntiAFK"), "modules restored while locked");

        sim.engine.resume();
        assertFalse(sim.engine.safetyLocked());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        for (var e : sim.bot.placeCounts.entrySet()) assertEquals(1, e.getValue());
    }

    @Test
    void safetyStopReasonSurvivesRestartAndIsSaved() {
        Sim sim = new Sim();
        Job job = sim.addJob("j0043", "Saved", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(24, 0));
        AtomicInteger saves = new AtomicInteger();
        sim.engine.setConfigSaver(saves::incrementAndGet);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.emergencyStop("server teleported the bot 20.0 blocks");
        assertTrue(saves.get() >= 1, "config saved right away");
        assertTrue(sim.config.pauseReason.startsWith("SAFETY STOP: server teleported"), sim.config.pauseReason);

        sim.engine.shutdown();
        sim.newEngine(); // restart: same config object, as loaded from mapart.json
        sim.start();
        sim.run(200);
        assertTrue(sim.engine.status().paused());
        assertTrue(sim.engine.status().pauseReason().startsWith("SAFETY STOP: server teleported"), sim.state());
        assertTrue(sim.engine.safetyLocked());
        int placed = sim.bot.placed;
        sim.run(500);
        assertEquals(placed, sim.bot.placed, "nothing placed after the restart");

        sim.engine.resume();
        assertEquals("", sim.config.pauseReason);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void manualPauseIsSavedWithItsReason() {
        Sim sim = new Sim();
        AtomicInteger saves = new AtomicInteger();
        sim.engine.setConfigSaver(saves::incrementAndGet);
        sim.engine.pause("lunch");
        assertEquals(1, saves.get());
        assertEquals("lunch", sim.config.pauseReason);
        sim.engine.resume();
        assertEquals(2, saves.get());
        assertFalse(sim.config.paused);
    }
}
