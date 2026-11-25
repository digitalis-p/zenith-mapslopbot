package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's keep-alive while the bot waits (live 2026-10-11: 2b2t dropped the bot
 * standing in the FILL_MAP cover wait after about 14 minutes, twice). Idle for {@code build.keepAliveSeconds}: a head
 * turn through ZenithProxy's input path (FakeBot models its Bot and the movement packets) and an arm swing, never beside
 * a task, a back-off, an escape, a SAFETY STOP, a busy bot layer, or while a player controls the account. ZenithProxy's
 * AntiAFK is off, as on the owner's proxy.
 */
class KeepAliveTest {
    private static final int LONG = 80_000;
    private static final int MINUTE = 1_200;
    private static final int GLASS = 64 + 27;

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        if (log != null) sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    /** A one-tile job whose art is covered from above by 80 pixels of wool: FILL_MAP waits at the centre. */
    private static Sim covered(RecordingLogger log) {
        Sim sim = sim(log);
        sim.addJob("j0070", "Covered", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        for (int dz = 0; dz < 4; dz++)
            for (int dx = 0; dx < 20; dx++)
                sim.world.set(new Pos(sim.moss.minX + 10 + dx, GLASS + 1, sim.moss.minZ + dz), BlockSpec.of("minecraft:red_wool"));
        return sim;
    }

    private static void reachCoverWait(Sim sim) {
        sim.start();
        assertTrue(sim.runUntil(() -> {
            String n = sim.engine.status().note();
            return sim.phase() == Phase.FILL_MAP && n != null && n.startsWith(BuildEngine.COVER_NOTE)
                && sim.bot.createdMaps == 1 && sim.maps.held != null;
        }, LONG), sim.state());
    }

    private static List<String> keepAliveLines(RecordingLogger log, Level level) {
        return log.at(level).stream().filter(l -> l.startsWith("keep-alive")).toList();
    }

    private static long count(List<String> wire, String what) {
        return wire.stream().filter(l -> l.contains(what)).count();
    }

    @Test
    void aFiveMinuteCoverWaitSwingsAndTurnsAboutEveryMinuteWithOneMovementPacketPerTick() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = covered(log);
        reachCoverWait(sim);
        int before = sim.bot.keepAlives.size();
        int actions = sim.bot.history.size();
        double x = sim.world.x;
        double z = sim.world.z;
        float yaw = sim.bot.yaw;
        float pitch = sim.bot.pitch;
        int wireBefore = sim.bot.wire.size();

        sim.run(5 * MINUTE + 40); // five minutes and two seconds

        List<FakeBot.KeepAliveStart> ka = sim.bot.keepAlives.subList(before, sim.bot.keepAlives.size());
        assertEquals(5, ka.size(), ka.toString());
        assertEquals(5, sim.bot.keepAlivesDone);
        assertEquals(5, sim.bot.swings);
        for (int i = 0; i < ka.size(); i++) {
            FakeBot.KeepAliveStart k = ka.get(i);
            assertEquals(i % 2 == 0 ? 15f : -15f, k.turn(), "right and left in turn: " + ka);
            assertEquals(Phase.FILL_MAP, k.phase());
            assertFalse(k.besideTask(), "never beside a task");
            if (i > 0) {
                long gap = k.tick() - ka.get(i - 1).tick();
                assertTrue(gap >= MINUTE && gap <= MINUTE + 3, "about every 60 s: " + gap + " ticks");
            }
        }
        // the turn rides on ZenithProxy's own movement packet; the swing is the next tick; never a second flying packet
        List<String> wire = sim.bot.wire.subList(wireBefore, sim.bot.wire.size());
        assertEquals(5, count(wire, "rot "), wire.toString());
        assertEquals(5, count(wire, "swing"), wire.toString());
        for (int i = 0; i + 1 < wire.size(); i += 2) {
            assertTrue(wire.get(i).contains("rot "), wire.toString());
            assertTrue(wire.get(i + 1).endsWith("swing"), wire.toString());
            long rotTick = Long.parseLong(wire.get(i).substring(0, wire.get(i).indexOf(':')));
            long swingTick = Long.parseLong(wire.get(i + 1).substring(0, wire.get(i + 1).indexOf(':')));
            assertEquals(rotTick + 1, swingTick, wire.toString());
        }
        assertEquals(1, sim.bot.maxFlyingPerTick, "one movement packet per tick at most");
        assertEquals(0, sim.bot.extraFlying, "no movement packet of the plugin's own");
        assertEquals(yaw + 15f, sim.bot.yaw, 1e-3, "an odd count ends turned once; the next turns back");
        assertEquals(pitch, sim.bot.pitch, "pitch unchanged");

        // it stands where it was, holding the map, and started nothing else
        assertEquals(actions, sim.bot.history.size(), sim.bot.history.subList(actions, sim.bot.history.size()).toString());
        assertEquals(x, sim.world.x, 1e-9);
        assertEquals(z, sim.world.z, 1e-9);
        assertEquals(sim.inv.filledMaps.getFirst(), sim.maps.held);
        assertTrue(sim.engine.status().note().startsWith(BuildEngine.COVER_NOTE), sim.state());

        // INFO once, DEBUG for the rest, nothing on Discord
        List<String> info = keepAliveLines(log, Level.INFO);
        assertEquals(List.of("keep-alive: idle 60 s in FILL_MAP (Waiting: platform above in use (80 pixels covered));"
            + " swinging every 60 s"), info);
        assertEquals(4, keepAliveLines(log, Level.DEBUG).size(), log.lines.toString());
        assertFalse(sim.notifier.toString().toLowerCase(Locale.ROOT).contains("keep"), sim.notifier.toString());
    }

    @Test
    void zeroSecondsTurnsItOff() {
        Sim sim = covered(null);
        sim.config.build.keepAliveSeconds = 0;
        reachCoverWait(sim);
        sim.run(5 * MINUTE);
        assertTrue(sim.bot.keepAlives.isEmpty(), sim.bot.keepAlives.toString());
        assertEquals(0, sim.bot.swings);
        assertEquals(0, count(sim.bot.wire, "rot "));
    }

    @Test
    void neverBesideALaneOrAContainerVisitAndTheJobStillFinishes() {
        Sim sim = sim(null);
        sim.config.build.keepAliveSeconds = 1; // due after 20 idle ticks: every gap between tasks is tried
        sim.bot.placementsPerTick = 8;         // lanes of hundreds of ticks
        sim.bot.containerTicks = 60;           // container visits of 3 s
        sim.addJob("j0001", "Stripes", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(4, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.store.jobs.getFirst().status == JobStatus.DONE, 200_000), sim.state());
        sim.run(MINUTE); // and idle afterwards (empty queue)

        assertFalse(sim.bot.keepAlives.isEmpty(), "the waits (WAIT_CLEAR, IDLE) had keep-alives");
        for (FakeBot.KeepAliveStart k : sim.bot.keepAlives)
            assertFalse(k.besideTask(), "a keep-alive beside a task: " + k + " of " + sim.bot.keepAlives);
        assertTrue(sim.bot.keepAlives.stream().anyMatch(k -> k.phase() == Phase.IDLE), sim.bot.keepAlives.toString());
        assertEquals(sim.bot.keepAlivesDone, sim.bot.swings);
        assertEquals(1, sim.bot.maxFlyingPerTick);
        assertEquals(1, sim.output().maps.size(), "the map was stored");
    }

    @Test
    void neverDuringABackOffOrTheEscapeAfterIt() {
        Sim sim = covered(null);
        sim.bot.escapeTicks = 200;
        reachCoverWait(sim);
        sim.run(50 * 20); // idle 50 s already
        int before = sim.bot.keepAlives.size();
        long from = sim.ticks;
        sim.engine.backoff("test burst", 90_000L, sim.world.playerBlock(), 1);
        assertTrue(sim.runUntil(() -> !sim.bot.escapeTasks.isEmpty() && sim.bot.escapeTasks.getFirst().isFinished(),
            3 * MINUTE), sim.state());
        long escaped = sim.ticks;
        assertTrue(escaped - from >= 90 * 20 + 200, "back-off then escape: " + (escaped - from) + " ticks");
        assertEquals(before, sim.bot.keepAlives.size(), "none during the back-off or the escape: " + sim.bot.keepAlives);

        // the wait goes on: the next keep-alive a minute after the escape
        assertTrue(sim.runUntil(() -> sim.bot.keepAlives.size() > before, 2 * MINUTE), sim.state());
        long next = sim.bot.keepAlives.get(before).tick();
        assertTrue(next - escaped >= MINUTE - 1 && next - escaped <= MINUTE + 3, (next - escaped) + " ticks");
        assertEquals(Phase.FILL_MAP, sim.bot.keepAlives.get(before).phase());
    }

    @Test
    void pausedWhileBotTicksRunButNeverWhileAPlayerControlsTheAccountOrAfterASafetyStop() {
        Sim sim = covered(null);
        reachCoverWait(sim);
        sim.engine.pause("jared2013 checks something");
        int before = sim.bot.keepAlives.size();
        sim.run(2 * MINUTE + 20);
        assertTrue(sim.engine.status().paused(), sim.state());
        assertTrue(sim.bot.keepAlives.size() - before >= 1, "paused, bot ticks running: " + sim.bot.keepAlives);
        assertTrue(sim.bot.keepAlives.subList(before, sim.bot.keepAlives.size()).stream().noneMatch(
            FakeBot.KeepAliveStart::besideTask));

        // a player takes control: no bot ticks, nothing at all for five minutes
        sim.engine.onBotTickStopped();
        int duringControl = sim.bot.keepAlives.size();
        int swings = sim.bot.swings;
        sim.clock.advance(5 * 60_000L);
        assertEquals(duringControl, sim.bot.keepAlives.size());
        assertEquals(swings, sim.bot.swings);
        // the player leaves: the idle time starts afresh
        sim.engine.onBotTickStarting();
        sim.run(MINUTE - 20);
        assertEquals(duringControl, sim.bot.keepAlives.size(), "not before a full minute after the player left");
        sim.run(40);
        assertEquals(duringControl + 1, sim.bot.keepAlives.size());

        // a SAFETY STOP: nothing moves
        sim.engine.emergencyStop("test stop");
        int stopped = sim.bot.keepAlives.size();
        sim.run(3 * MINUTE);
        assertEquals(stopped, sim.bot.keepAlives.size(), sim.bot.keepAlives.toString());
    }

    @Test
    void idleWithAnEmptyQueueUnlessZenithProxysAntiAfkIsOn() {
        Sim sim = sim(null);
        sim.start();
        sim.run(3 * MINUTE + 40);
        assertEquals(Phase.IDLE, sim.phase());
        assertEquals(3, sim.bot.keepAlives.size(), sim.bot.keepAlives.toString());
        assertTrue(sim.bot.keepAlives.stream().allMatch(k -> k.phase() == Phase.IDLE));

        Sim on = sim(null);
        on.modules.state.put("AntiAFK", true); // the engine gave the modules back in IDLE: AntiAFK keeps it active
        on.start();
        on.run(3 * MINUTE);
        assertTrue(on.bot.keepAlives.isEmpty(), on.bot.keepAlives.toString());
    }

    /**
     * A cancelled double break left its delayed block on the server (production: PendingDelayed via
     * ZenithBotActions.isBusy): no keep-alive turn or swing until the server broke it, then the keep-alive comes back.
     */
    @Test
    void noKeepAliveWhileADelayedBlockIsPending() {
        Sim sim = covered(null);
        reachCoverWait(sim);
        int before = sim.bot.keepAlives.size();
        int swings = sim.bot.swings;
        Pos d = new Pos(500, 200, 500); // off the platforms: the engine does not look at it
        sim.world.set(d, BlockSpec.of("minecraft:white_concrete"));
        sim.bot.delayedBlock = d;
        sim.run(3 * MINUTE);
        assertEquals(before, sim.bot.keepAlives.size(), "no keep-alive while the server holds the delayed block");
        assertEquals(swings, sim.bot.swings, "no swing either");

        sim.world.set(d, BlockSpec.AIR); // the server broke it
        sim.run(MINUTE + 20);
        assertTrue(sim.bot.keepAlives.size() > before, "the keep-alive comes back once it showed air");
    }

    @Test
    void aBusyBotLayerOrAnotherModuleOnTheInputHoldsItBackThenItIsTriedAgain() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = covered(log);
        reachCoverWait(sim);
        int before = sim.bot.keepAlives.size();
        sim.bot.busy = true; // a menu open, Baritone walking, a delayed block on the server
        sim.run(2 * MINUTE);
        assertEquals(before, sim.bot.keepAlives.size());

        sim.bot.busy = false;
        sim.bot.inputOwnedElsewhere = true; // AutoEat eating, say
        sim.run(3);
        assertEquals(before + 1, sim.bot.keepAlives.size());
        assertEquals(0, sim.bot.swings, "no swing without the turn");
        assertTrue(keepAliveLines(log, Level.DEBUG).stream().anyMatch(l -> l.contains("another module owns the input")
            && l.contains("trying again in 5 s")), log.lines.toString());
        sim.bot.inputOwnedElsewhere = false;
        sim.run(5 * 20 + 3);
        assertEquals(before + 2, sim.bot.keepAlives.size(), "tried again 5 s later");
        assertEquals(1, sim.bot.swings);
        assertEquals(15f, sim.bot.keepAlives.get(before + 1).turn(), "the same turn as the one that failed");
    }
}
