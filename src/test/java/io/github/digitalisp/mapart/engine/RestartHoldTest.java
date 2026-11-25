package io.github.digitalisp.mapart.engine;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.TERRACOTTA;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * 2b2t's scheduled restart: inside {@code site.restartHoldMinutes} of the
 * announced time the moss carts go off, a NUKE waits and no clear button is pressed, until the bot is back after the
 * restart (or 2 minutes past the time with no restart). A clear already pressed finishes.
 */
class RestartHoldTest {
    private static final int LONG = 60_000;
    /** 2b2t's line as ZenithProxy hands it over (SystemChatEvent.message(): the two lines of one message). */
    private static final String WARNING_5 = "Server restarting in 5 minutes...\n"
        + "You will keep your position on the server if you stay connected, even if you do not have Priority Queue";
    private static final String OFF = "use " + Sim.MOSS_CARTS_OFF;
    private static final String ON = "use " + Sim.MOSS_CARTS_ON;

    // ---- the warning itself ----

    @Test
    void everyVariantInTheLiveLogsParses() {
        // latest.log 2026-10-12 08:09-08:13: 5, 4, 3, 2 minutes, then "1 minute" (singular)
        for (int n = 5; n >= 2; n--)
            assertEquals(OptionalLong.of(n * 60_000L), RestartHold.parse("Server restarting in " + n + " minutes...\n"
                + "You will keep your position on the server if you stay connected, even if you do not have Priority Queue"));
        assertEquals(OptionalLong.of(60_000L), RestartHold.parse("Server restarting in 1 minute..."));
        // earlier warnings of the same form, seconds, and the bare line (taken as due now)
        assertEquals(OptionalLong.of(15 * 60_000L), RestartHold.parse("Server restarting in 15 minutes..."));
        assertEquals(OptionalLong.of(30_000L), RestartHold.parse("Server restarting in 30 seconds..."));
        assertEquals(OptionalLong.of(0), RestartHold.parse("Server Restarting"));
        assertEquals(OptionalLong.of(0), RestartHold.parse("Server restarting..."));
    }

    @Test
    void playerLinesAndOtherTextAreNoWarning() {
        // a player repeating it (seen live 08:10:22), a whisper, the vanilla /say form, unrelated chat
        assertTrue(RestartHold.parse("<Abdik9292> Server restarting in 4 minutes...You will keep your position on the"
            + " server if you stay connected, even if you do not have Priority Queue").isEmpty());
        assertTrue(RestartHold.parse("Abdik9292 whispers: Server restarting in 4 minutes...").isEmpty());
        assertTrue(RestartHold.parse("[Server] Server restarting in 4 minutes...").isEmpty());
        assertTrue(RestartHold.parse("<teiid> does server usually restart?").isEmpty());
        assertTrue(RestartHold.parse("Server restartingly odd").isEmpty());
        assertTrue(RestartHold.parse("").isEmpty());
        assertTrue(RestartHold.parse(null).isEmpty());
    }

    @Test
    void theWindowOpensBeforeTheAnnouncedTimeAndLapsesAfterTheGrace() {
        RestartHold h = new RestartHold();
        long t0 = 1_000_000;
        h.warned(t0, 15 * 60_000L, "Server restarting in 15 minutes...");
        assertFalse(h.holds(t0, 5), "15 min ahead: not yet");
        assertTrue(h.holds(t0 + 10 * 60_000L, 5), "5 min ahead: holds");
        assertFalse(h.holds(t0 + 10 * 60_000L, 0), "0 = off");
        assertFalse(h.expired(t0 + 16 * 60_000L));
        assertTrue(h.expired(t0 + 17 * 60_000L), "2 min past the time: over");
        assertFalse(h.holds(t0 + 17 * 60_000L, 5));
        // a restart seen ends it when bot ticks start again
        h.warned(t0, 60_000L, "Server restarting in 1 minute...");
        assertFalse(h.backAfterRestart(), "no restart seen yet");
        h.connectionLost();
        assertTrue(h.backAfterRestart());
        assertFalse(h.holds(t0, 5));
    }

    @Test
    void theWindowDefaultsToFiveMinutesInAnOlderConfig() {
        MapArtConfig old = new Gson().fromJson("{ \"site\": { \"anvil\": {\"x\": 1, \"y\": 2, \"z\": 3} } }", MapArtConfig.class);
        assertEquals(5, old.site.restartHoldMinutes);
    }

    // ---- the engine ----

    /** A Sim whose engine logs into {@code log}; the moss tile is nuked block by block (2 ticks a block). */
    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.bot.breakTicksPerBlock = 2;
        return sim;
    }

    /** Clear button presses that went out (the handler runs at the click), and whether the carts were on at each. */
    private static List<Boolean> countClearPresses(Sim sim) {
        List<Boolean> presses = new ArrayList<>();
        Runnable press = sim.bot.useHandlers.get(Sim.MOSS_BUTTON);
        sim.bot.useHandlers.put(Sim.MOSS_BUTTON, () -> {
            presses.add(sim.cartsOn());
            press.run();
        });
        return presses;
    }

    /** 2b2t restarts: bot ticks stop, the connection drops, {@code downMs} later the bot is back in game. */
    private static void restart(Sim sim, long downMs) {
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.clock.advance(downMs);
    }

    private static long infos(RecordingLogger log, String prefix) {
        return log.at(Level.INFO).stream().filter(l -> l.startsWith(prefix)).count();
    }

    private static void assertDone(Sim sim, Job job) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), "not idle after done: " + sim.state());
    }

    @Test
    void warningDuringNukeSwitchesTheCartsOffAndTheNukeWaitsForTheRestart() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        List<Boolean> presses = countClearPresses(sim);
        Job job = sim.addJob("j0301", "Nuke", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE && sim.cartsOn() && sim.artBlocks(sim.moss) < 492, LONG),
            "breaking with the carts on: " + sim.state());
        int parks = sim.bot.parks;
        sim.engine.onServerMessage(WARNING_5);

        assertTrue(sim.runUntil(() -> !sim.cartsOn(), 200), "carts off: " + sim.state());
        assertTrue(sim.bot.parks > parks, "the break stopped at a safe point");
        int left = sim.artBlocks(sim.moss);
        assertTrue(left > 0, "the NUKE was cut short");
        sim.run(400);
        assertEquals(Phase.NUKE, sim.phase());
        assertEquals(left, sim.artBlocks(sim.moss), "nothing broken while it waits");
        assertFalse(sim.cartsOn(), "carts stay off");
        assertTrue(sim.engine.status().note().startsWith(BuildEngine.RESTART_NOTE), sim.engine.status().note());
        assertTrue(presses.isEmpty(), "no clear pressed");
        assertEquals(1, infos(log, "server restart in about 5 min (\"Server restarting in 5 minutes...\"): the NUKE"
            + " stops at its next safe point"), log.at(Level.INFO).toString());
        assertEquals(1, sim.notifier.of("detail").stream().filter(m -> m.title().equals("Server restart")).count(),
            sim.notifier.toString());
        // the later warnings (4, 3, 2, 1 minutes) add nothing
        sim.engine.onServerMessage("Server restarting in 4 minutes...");
        sim.run(20);
        assertEquals(1, infos(log, "server restart in about"));

        long onBefore = sim.bot.count(ON);
        restart(sim, 4 * 60_000L);
        assertDone(sim, job);
        assertEquals(1, infos(log, "server restart over: back in game"), log.at(Level.INFO).toString());
        assertTrue(sim.bot.count(ON) > onBefore, "carts on again for the rest of the NUKE");
        assertEquals(0, sim.artBlocks(sim.moss));
        assertEquals(List.of(true), presses, "one clear, with the carts on");
        assertFalse(sim.cartsOn(), "carts off at the end");
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void warningDuringBuildSwitchesNothingAndNoNukeStartsInsideTheWindow() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        List<Boolean> presses = countClearPresses(sim);
        Job job = sim.addJob("j0302", "Build", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.placed > 100, LONG), sim.state());
        long offs = sim.bot.count(OFF);
        long ons = sim.bot.count(ON);
        long breaks = sim.bot.count("breakBlocks 256");
        long warned = sim.clock.millis();
        sim.engine.onServerMessage(WARNING_5);
        int placed = sim.bot.placed;

        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), "the map is made and stored: " + sim.state());
        assertTrue(sim.bot.placed > placed, "the build went on");
        assertTrue(sim.clock.millis() < warned + 5 * 60_000L, "NUKE reached inside the window");
        sim.run(400);
        assertEquals(Phase.NUKE, sim.phase(), "the NUKE waits");
        assertEquals(offs, sim.bot.count(OFF), "nothing to switch off");
        assertEquals(ons, sim.bot.count(ON), "the carts not switched on");
        assertEquals(breaks, sim.bot.count("breakBlocks 256"), "nothing broken");
        assertEquals(512, sim.artBlocks(sim.moss));
        assertTrue(presses.isEmpty());

        // no restart comes: 2 minutes past the announced time the NUKE carries on
        sim.clock.advance(7 * 60_000L);
        assertDone(sim, job);
        assertEquals(1, infos(log, "server restart hold over: no restart within 2 min"), log.at(Level.INFO).toString());
        assertEquals(List.of(true), presses);
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void aPlayerRepeatingTheWarningIsIgnored() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        List<Boolean> presses = countClearPresses(sim);
        Job job = sim.addJob("j0303", "Player", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE && sim.cartsOn() && sim.artBlocks(sim.moss) < 492, LONG),
            sim.state());
        long offs = sim.bot.count(OFF);
        // as seen live at 08:10:22, and the same text whispered
        sim.engine.onServerMessage("<Abdik9292> Server restarting in 4 minutes...You will keep your position on the server"
            + " if you stay connected, even if you do not have Priority Queue");
        sim.engine.onServerMessage("Abdik9292 whispers: Server restarting in 1 minute...");
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.NUKE, 2_000), "the NUKE goes on: " + sim.state());
        assertEquals(offs, sim.bot.count(OFF), "the carts stay on through the NUKE");
        assertDone(sim, job);
        assertEquals(0, infos(log, "server restart"), log.at(Level.INFO).toString());
        assertFalse(sim.notifier.has("detail", "Server restart"), sim.notifier.toString());
        assertEquals(List.of(true), presses);
    }

    @Test
    void zeroMinutesSwitchesTheFeatureOff() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.site.restartHoldMinutes = 0;
        List<Boolean> presses = countClearPresses(sim);
        Job job = sim.addJob("j0304", "Off", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(2, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE && sim.cartsOn() && sim.artBlocks(sim.moss) < 492, LONG),
            sim.state());
        long offs = sim.bot.count(OFF);
        sim.engine.onServerMessage(WARNING_5);
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.NUKE, 2_000), "the NUKE goes on: " + sim.state());
        assertEquals(offs, sim.bot.count(OFF), "the carts stay on through the NUKE");
        assertDone(sim, job);
        assertEquals(0, infos(log, "server restart"), log.at(Level.INFO).toString());
        assertFalse(sim.notifier.has("detail", "Server restart"), sim.notifier.toString());
        assertEquals(List.of(true), presses);
    }

    @Test
    void warningJustBeforeTheClearPressHoldsItUntilAfterTheRestart() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.moss.collectionRunAfterClearSeconds = 0;
        sim.bot.useWalkTicks.put(Sim.MOSS_BUTTON, 40); // the walk to the button: the warning lands during it
        List<Boolean> presses = countClearPresses(sim);
        Job job = sim.addJob("j0305", "Clear", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.CLEAR, LONG), sim.state());
        assertTrue(presses.isEmpty() && sim.cartsOn(), "walking to the button with the carts on");
        sim.engine.onServerMessage(WARNING_5);

        assertTrue(sim.runUntil(() -> !sim.cartsOn(), 400), "carts off: " + sim.state());
        sim.run(400);
        assertEquals(Phase.CLEAR, sim.phase(), "the clear waits");
        assertTrue(presses.isEmpty(), "nothing pressed in the window");
        assertEquals(256, sim.artBlocks(sim.moss));
        assertTrue(sim.engine.status().note().startsWith(BuildEngine.RESTART_NOTE), sim.engine.status().note());
        assertEquals(1, infos(log, "server restart in about 5 min (\"Server restarting in 5 minutes...\"): the clear"
            + " waits with the carts off, nothing pressed"), log.at(Level.INFO).toString());

        restart(sim, 5 * 60_000L);
        assertDone(sim, job);
        assertEquals(List.of(true), presses, "pressed once after the restart, the carts on again first");
        assertEquals(0, sim.artBlocks(sim.moss));
        assertFalse(sim.notifier.has("info", "Clear timed out"), sim.notifier.toString());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertFalse(sim.cartsOn());
    }

    @Test
    void soulSandNukeGoesOnAndItsClearWaitsForTheRestart() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        List<Integer> presses = new ArrayList<>();
        Runnable press = sim.bot.useHandlers.get(Sim.SOUL_BUTTON);
        sim.bot.useHandlers.put(Sim.SOUL_BUTTON, () -> {
            presses.add(1);
            press.run();
        });
        Job job = sim.addJob("j0307", "Soul", 1, 1, List.of("soul"), (x, z) -> Sim.stripes(2, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE && sim.artBlocks(sim.soul) < 492, LONG), sim.state());
        sim.engine.onServerMessage(WARNING_5);
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.CLEAR, 2_000), "the NUKE goes on (no carts): " + sim.state());
        assertEquals(256, sim.artBlocks(sim.soul), "the terracotta broken, the carpets left for the clear");
        sim.run(400);
        assertEquals(Phase.CLEAR, sim.phase(), "the clear waits");
        assertTrue(presses.isEmpty(), "the soul sand clear not pressed in the window");
        assertEquals(0, sim.bot.count(OFF) + sim.bot.count(ON), "no carts to switch");

        restart(sim, 5 * 60_000L);
        assertDone(sim, job);
        assertEquals(1, presses.size(), "pressed once after the restart");
        assertEquals(0, sim.artBlocks(sim.soul));
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void warningDuringWaitClearLetsTheClearFinishThenTheCartsGoOff() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.moss.collectionRunAfterClearSeconds = 30;
        List<Boolean> presses = countClearPresses(sim);
        Job first = sim.addJob("j0306", "Wait", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.WAIT_CLEAR, LONG), sim.state());
        assertTrue(sim.artBlocks(sim.moss) > 0 && sim.cartsOn(), "the machines are under way");
        sim.engine.onServerMessage(WARNING_5);

        long[] clearedAt = {-1};
        assertTrue(sim.runUntil(() -> {
            if (clearedAt[0] < 0 && sim.artBlocks(sim.moss) == 0) clearedAt[0] = sim.clock.millis();
            return sim.phase() != Phase.WAIT_CLEAR;
        }, 2_000), sim.state());
        assertTrue(clearedAt[0] > 0, "the clear finished");
        assertTrue(sim.runUntil(() -> !sim.cartsOn(), 400), "carts off after the clear: " + sim.state());
        assertTrue(sim.clock.millis() - clearedAt[0] < 10_000, "no 30 s run-on in the window: "
            + (sim.clock.millis() - clearedAt[0]) + " ms");
        assertEquals(1, infos(log, "item collection run-on on moss cut short: server restart due"),
            log.at(Level.INFO).toString());
        assertEquals(1, infos(log, "platform moss clear "), "the clear read once: " + log.at(Level.INFO));
        assertEquals(1, infos(log, "server restart in about 5 min (\"Server restarting in 5 minutes...\"): the clear"
            + " under way finishes, then the carts go off"), log.at(Level.INFO).toString());
        assertDone(sim, first);
        assertEquals(List.of(true), presses, "one press: the clear itself");
        assertFalse(sim.cartsOn());
        assertEquals(1, sim.output().maps.size());
    }
}
