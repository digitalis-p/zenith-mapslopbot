package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setbacks onto the bot's own recent path are anti-cheat setbacks, not SAFETY STOPs; repeats at one spot re-sync it.
 * The incident test replays the bot track and the server's
 * teleports of the live 2b2t stop of 2026-10-11 23:36 (from the replay, base-relative: the moss tile's minX/minZ are -64).
 */
class RecentPathSetbackTest {
    private static final int LONG = 60_000;
    private static final PlatformGeometry MOSS = new PlatformGeometry(-64, -64, 173);
    /** The walked column: replay x = minX + 4.5. */
    private static final double X = -64 + 4.5;
    private static final double CARPET = 173.0625;

    private static final class Rec implements TeleportGuard.Sink {
        final List<String> halts = new ArrayList<>();
        final List<String> warns = new ArrayList<>();
        final List<Double> setbacks = new ArrayList<>();
        final List<String> resyncs = new ArrayList<>();
        final List<double[]> resyncAt = new ArrayList<>();

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
        }

        @Override
        public void setback(double distance) {
            setbacks.add(distance);
        }

        @Override
        public void resync(String reason, double x, double y, double z) {
            resyncs.add(reason);
            resyncAt.add(new double[]{x, y, z});
        }
    }

    private static final class Rig {
        final MapArtConfig.SafetyCfg cfg = new MapArtConfig.SafetyCfg();
        final Rec rec = new Rec();
        final AtomicLong now = new AtomicLong(1_000_000);
        final TeleportGuard g = new TeleportGuard(cfg, () -> List.of(MOSS), now::get, rec);
        double x, y, z;

        /** Bot ticks start at (x, y, z) and run past the grace period, one tick per 50 ms. */
        Rig arm(double ax, double ay, double az) {
            g.botTicksStarted();
            for (int i = 0; i <= cfg.startGraceTicks; i++) tick(ax, ay, az);
            return this;
        }

        void tick(double px, double py, double pz) {
            x = px;
            y = py;
            z = pz;
            g.tick(px, py, pz, false);
            now.addAndGet(50);
        }

        /** Walks along z to {@code toZ} at 0.3 blocks per tick, the height from {@code height}. */
        void walkZ(double toZ, java.util.function.DoubleUnaryOperator height) {
            double step = toZ < z ? -0.3 : 0.3;
            while (Math.abs(toZ - z) > 0.3) tick(x, height.applyAsDouble(z + step), z + step);
            tick(x, height.applyAsDouble(toZ), toZ);
        }

        void walkX(double toX) {
            double step = toX < x ? -0.3 : 0.3;
            while (Math.abs(toX - x) > 0.3) tick(x + step, y, z);
            tick(toX, y, z);
        }

        void stand(long millis) {
            for (long t = 0; t < millis; t += 50) tick(x, y, z);
        }

        /** The server's absolute teleport; the bot is there from its next tick on. */
        void teleport(double tx, double ty, double tz) {
            g.teleport(false, tx, false, ty, false, tz, false);
            tick(tx, ty, tz);
        }
    }

    /** The moss tile's z for a replay z (base-relative row). */
    private static double z(double replayZ) {
        return -64 + replayZ;
    }

    /** Heights of the walked column: carpets, then a cyan terracotta row, then pressure plates (replay). */
    private static double height(double z) {
        double rz = z + 64;
        return rz > 46.5 ? CARPET : rz > 34.5 ? 174.0 : 173.0;
    }

    @Test
    void liveIncidentSetbacksCarryOnAndTheThirdAtOneSpotResyncs() {
        Rig r = new Rig().arm(X, 173.0, z(92.6));
        r.walkZ(z(33.5), RecentPathSetbackTest::height);                    // the walk north to the drop point
        r.teleport(X, CARPET, z(60.836));                                     // 23:36:33, 27.3 blocks back
        assertTrue(r.rec.halts.isEmpty(), "no SAFETY STOP: " + r.rec.halts);
        assertEquals(1, r.rec.warns.size(), r.rec.warns.toString());
        assertTrue(r.rec.warns.getFirst().startsWith("Setback onto the bot's path: the server moved the bot 27.3 blocks"
            + " back to " + CoordFormat.point(X, CARPET, z(60.836)) + ", where it stood "), r.rec.warns.getFirst());
        assertTrue(r.rec.warns.getFirst().contains("not a SAFETY STOP"), r.rec.warns.getFirst());
        assertEquals(27.3, r.rec.setbacks.getFirst(), 0.05, "passed on as a setback");

        r.stand(700);
        r.teleport(X, CARPET, z(60.478));                                     // 0.75 s later: re-sent, one repeat
        r.stand(15_000);
        r.teleport(X, CARPET, z(60.478));                                     // 23:36:49: the second at the spot
        assertTrue(r.rec.resyncs.isEmpty(), r.rec.resyncs.toString());
        r.stand(15_000);
        r.walkZ(z(53.58), RecentPathSetbackTest::height);                     // resumed: walking again
        r.teleport(X, CARPET, z(60.478));                                     // 23:37:05, 6.9 blocks: the third
        assertEquals(1, r.rec.resyncs.size(), r.rec.resyncs.toString());
        assertTrue(r.rec.resyncs.getFirst().startsWith("3 server setbacks to " + CoordFormat.point(X, CARPET, z(60.478))
            + " within 3"), r.rec.resyncs.getFirst());
        assertArrayEquals(new double[]{X, CARPET, z(60.478)}, r.rec.resyncAt.getFirst());

        r.walkZ(z(42.03), RecentPathSetbackTest::height);                     // up onto the terracotta row
        r.teleport(X, CARPET, z(52.078));                                     // 23:37:09, 10.1 blocks back
        assertTrue(r.rec.halts.isEmpty(), "no SAFETY STOP: " + r.rec.halts);
        assertEquals(2, r.rec.warns.size(), "the 27.3 and the 10.1 (the 6.9 is small anyway): " + r.rec.warns);
        assertEquals(1, r.rec.resyncs.size(), "another spot");
        assertEquals(5, r.rec.setbacks.size(), r.rec.setbacks.toString());
    }

    @Test
    void realTeleportsStillHalt() {
        // further than setbackRecentMaxDistance (32), although on the path, with the long-rollback rule off
        Rig r = new Rig();
        r.cfg.setbackRecentSpeed = 0;
        r.arm(X, 173.0, z(100));
        r.walkZ(z(60), d -> 173.0);
        r.teleport(X, 173.0, z(100));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());
        assertTrue(r.rec.halts.getFirst().startsWith("server teleported the bot 40.0 blocks"), r.rec.halts.getFirst());

        // never visited: 20 blocks sideways
        r = new Rig().arm(X, 173.0, z(80));
        r.walkZ(z(70), d -> 173.0);
        r.teleport(X + 20, 173.0, z(70));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());

        // visited, but left more than setbackRecentSeconds (60) ago
        r = new Rig().arm(X, 173.0, z(80));
        r.walkZ(z(65), d -> 173.0);
        r.stand(61_000);
        r.teleport(X, 173.0, z(80));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());

        // left 57.5 s ago (the 2.5 s walk, then 55 s standing): still on the recent path
        r = new Rig().arm(X, 173.0, z(80));
        r.walkZ(z(65), d -> 173.0);
        r.stand(55_000);
        r.teleport(X, 173.0, z(80));
        assertTrue(r.rec.halts.isEmpty(), r.rec.halts.toString());

        // the kill switch: every teleport over teleportStopDistance halts, as before
        r = new Rig();
        r.cfg.setbackRecentPath = false;
        r.arm(X, 173.0, z(80));
        r.walkZ(z(65), d -> 173.0);
        r.teleport(X, 173.0, z(80));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());
        assertTrue(r.rec.warns.isEmpty(), r.rec.warns.toString());
    }

    /** Sprints along x at {@code perTick} blocks a tick (about 10 blocks a second at 0.5) to {@code toX}. */
    private static void sprintX(Rig r, double toX, double perTick) {
        double step = toX < r.x ? -perTick : perTick;
        while (Math.abs(toX - r.x) > perTick) r.tick(r.x + step, r.y, r.z);
        r.tick(toX, r.y, r.z);
    }

    /**
     * Live 2026-10-20 20:08-20:09 (2b2t, ABD [3,5], tile-relative): the server put the bot back onto the chest stand
     * after "container did not open"; it ate there (the server did not consume the food: lag), then sprinted 118.7
     * blocks to its lane start in about 12 s; the server rolled it back onto the chest stand: a SAFETY STOP that waited
     * 21 minutes. 17:39 the same with 66.0 blocks. Now both are setbacks onto the bot's path.
     */
    @Test
    void longRollbacksOntoTheWalkJustDoneAreSetbacks() {
        Rig r = new Rig().arm(X, 174.0, z(60));
        r.stand(7_000);                                                      // eating at the chest stand
        sprintX(r, X + 118.7, 0.5);                                         // 11.9 s
        r.teleport(X, 174.0, z(60));
        assertTrue(r.rec.halts.isEmpty(), "no SAFETY STOP: " + r.rec.halts);
        assertEquals(1, r.rec.warns.size(), r.rec.warns.toString());
        assertTrue(r.rec.warns.getFirst().startsWith("Setback onto the bot's path: the server moved the bot 118.7 blocks"
            + " back to " + CoordFormat.point(X, 174.0, z(60)) + ", where it stood 11."), r.rec.warns.getFirst());
        assertEquals(118.7, r.rec.setbacks.getFirst(), 0.05, "passed on as a setback");

        // 17:39: 66 blocks back to a point the bot sprinted past about 15 s before (then it stood at the lane start)
        r = new Rig().arm(X, 173.0, z(60));
        sprintX(r, X + 66, 0.5);
        sprintX(r, X + 110, 0.5);
        r.stand(6_000);
        r.teleport(X + 44, 173.0, z(60));
        assertTrue(r.rec.halts.isEmpty(), "no SAFETY STOP: " + r.rec.halts);
        assertEquals(1, r.rec.warns.size(), r.rec.warns.toString());
    }

    @Test
    void longTeleportsTheWalkCannotExplainStillHalt() {
        // there a second ago, 100 blocks away now: no walk (a pearl, a /tp), however much it lies on the track
        Rig r = new Rig().arm(X, 173.0, z(60));
        r.stand(5_000);
        sprintX(r, X + 100, 5.0);                                           // 100 blocks a second
        r.teleport(X, 173.0, z(60));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());
        assertTrue(r.rec.halts.getFirst().startsWith("server teleported the bot 100.0 blocks"), r.rec.halts.getFirst());

        // further than setbackRecentFarMaxDistance (160), walked at sprint speed within the minute
        r = new Rig().arm(X, 173.0, z(60));
        sprintX(r, X + 170, 0.5);
        r.teleport(X, 173.0, z(60));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());

        // never visited, 66 blocks
        r = new Rig().arm(X, 173.0, z(60));
        sprintX(r, X + 66, 0.5);
        r.stand(10_000);
        r.teleport(X + 30, 173.0, z(60) + 30);
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());

        // the switch off: the 66-block rollback halts, as before
        r = new Rig();
        r.cfg.setbackRecentSpeed = 0;
        r.arm(X, 173.0, z(60));
        sprintX(r, X + 66, 0.5);
        r.teleport(X, 173.0, z(60));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());
        assertTrue(r.rec.warns.isEmpty(), r.rec.warns.toString());
    }

    @Test
    void theTrackStartsOverAfterARespawnOrAReconnect() {
        // a respawn (here one the guard does not judge: in the queue) may change the dimension
        Rig r = new Rig().arm(X, 173.0, z(80));
        r.walkZ(z(65), d -> 173.0);
        r.g.respawn(true);
        r.teleport(X, 173.0, z(80));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());

        // a reconnect: bot ticks start again
        r = new Rig().arm(X, 173.0, z(80));
        r.walkZ(z(65), d -> 173.0);
        r.arm(X, 173.0, z(65));
        r.teleport(X, 173.0, z(80));
        assertEquals(1, r.rec.halts.size(), r.rec.halts.toString());
    }

    @Test
    void resyncCountsRepeatsAtOneSpotOnly() {
        Rig r = new Rig().arm(X, 173.0, z(80));
        // re-sent teleports within 2 s count once (also a burst: a back-off, SetbackBackoffTest)
        for (int i = 0; i < 6; i++) {
            r.teleport(X + 0.5, 173.0, z(80));
            r.stand(250);
        }
        assertTrue(r.rec.resyncs.isEmpty(), r.rec.resyncs.toString());
        // three spots 3 blocks apart are three spots
        r.stand(3_000);
        for (int i = 1; i <= 3; i++) {
            r.walkX(X + 3 * i + 0.4);
            r.teleport(X + 3 * i, 173.0, z(80));
            r.stand(3_000);
        }
        assertTrue(r.rec.resyncs.isEmpty(), r.rec.resyncs.toString());
        // the same spot (within 1.5 blocks) 3 times, 5 s apart: one resync; then the count starts over
        for (int i = 0; i < 6; i++) {
            r.teleport(X + 9 + 0.3 * (i % 2), 173.0, z(80));
            r.stand(5_000);
        }
        assertEquals(2, r.rec.resyncs.size(), r.rec.resyncs.toString());
        assertTrue(r.rec.resyncs.getFirst().startsWith("3 server setbacks to "), r.rec.resyncs.getFirst());
        // spread over more than 2 minutes: never 3 within the window
        r.stand(130_000);
        for (int i = 0; i < 3; i++) {
            r.teleport(X + 3, 173.0, z(80));
            r.stand(70_000);
        }
        assertEquals(2, r.rec.resyncs.size(), r.rec.resyncs.toString());
        assertTrue(r.rec.halts.isEmpty(), r.rec.halts.toString());

        // 0 switches it off
        r = new Rig();
        r.cfg.setbackResyncRepeats = 0;
        r.arm(X, 173.0, z(80));
        for (int i = 0; i < 6; i++) {
            r.teleport(X + 0.5, 173.0, z(80));
            r.stand(5_000);
        }
        assertTrue(r.rec.resyncs.isEmpty(), r.rec.resyncs.toString());
    }

    // ---------------------------------------------------------------- engine

    @Test
    void resyncCancelsTheTaskRunsAtTheSpotThenTheJobFinishes() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 8; // a lane is mid-way when the resync comes
        Job job = sim.addJob("j0060", "Lagged", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(24, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.placed > 300 && sim.phase() == Phase.BUILD
            && sim.bot.lastTask != null && !sim.bot.lastTask.isFinished(), LONG), sim.state());
        BotTask running = sim.bot.lastTask;
        Pos spot = new Pos(-60, 173, -4);

        sim.engine.resync("3 server setbacks to the spot within 32 s", spot);
        sim.tick();
        assertTrue(running.isFinished(), "the running task is cancelled before it ticks again");
        assertEquals(List.of(spot), sim.bot.resyncs);
        assertEquals(Phase.BUILD, sim.phase(), "inside the phase");
        assertFalse(sim.config.paused, "not a pause");
        assertFalse(sim.engine.safetyLocked());
        assertTrue(sim.notifier.has("detail", "Resync"), sim.notifier.toString());

        sim.engine.resync("again while it runs", spot);
        sim.tick();
        assertEquals(1, sim.bot.resyncs.size(), "one at a time");

        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        assertTrue(sim.bot.resyncTasks.getFirst().isFinished());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void noResyncWhilePaused() {
        Sim sim = new Sim();
        sim.addJob("j0061", "Paused", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(24, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.pause("jared2013");
        sim.tick();
        sim.engine.resync("3 server setbacks", new Pos(-60, 173, -4));
        sim.run(40);
        assertTrue(sim.bot.resyncs.isEmpty(), "never while paused");
    }

    @Test
    void guardToEngineThroughTheAdaptersSink() {
        Sim sim = new Sim();
        sim.addJob("j0062", "Wired", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(24, 0));
        TeleportGuard guard = new TeleportGuard(sim.config.safety, List::of, sim.clock::millis,
            TickThread.guardSink(sim.engine, () -> null, Runnable::run, sim.notifier::info));
        sim.start();
        guard.botTicksStarted();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        for (int i = 0; i <= sim.config.safety.startGraceTicks; i++) {
            guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
            sim.tick();
        }
        double sx = sim.world.x + 0.5, sy = sim.world.y, sz = sim.world.z;
        for (int k = 0; k < 3; k++) {
            guard.teleport(false, sx, false, sy, false, sz, false);
            for (int i = 0; i < 60; i++) { // 3 s
                guard.tick(sim.world.x, sim.world.y, sim.world.z, false);
                sim.tick();
            }
        }
        assertEquals(1, sim.bot.resyncs.size(), sim.state());
        assertEquals(new Pos((int) Math.floor(sx), (int) Math.floor(sy + 0.1251), (int) Math.floor(sz)),
            sim.bot.resyncs.getFirst(), "the spot's feet cell");
        assertFalse(sim.config.paused);
        assertFalse(sim.engine.safetyLocked());
    }
}
