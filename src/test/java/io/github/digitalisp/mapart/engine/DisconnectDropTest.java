package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a disconnect drops ({@code build.recoverDisconnectDrops}): the
 * server drops a menu's cursor stack when the player leaves (vanilla {@code AbstractContainerMenu.removed}), and the
 * cartography table and the anvil what lies in their input slots. After the reconnect the engine's first decision picks
 * a valuable one up (rockets of a top-up cut short, the map of a lock, the elytra), with one log line and no
 * coordinates above debug; a material is not worth the walk; one the bot is too far from is logged as lost; one back in
 * the inventory (its click had not reached the server) is a debug line; a cursor stack found nowhere is said to be most
 * likely still in the container.
 */
class DisconnectDropTest {
    private static final String ROCKET = "minecraft:firework_rocket";

    private static Sim midLane(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.bot.placementsPerTick = 1;
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        sim.addJob("j0400", "Drops", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> {
            BotTask t = sim.bot.lastTask;
            return t != null && t.name().startsWith("lane") && !t.isFinished() && sim.bot.placed > 50;
        }, 80_000), sim.state());
        return sim;
    }

    /** The adapter's order on a disconnect (the server drops the cursor) and the reconnect a moment later. */
    private static int disconnectAndBack(Sim sim) {
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.bot.disconnect();
        sim.run(3);
        int from = sim.bot.history.size();
        sim.engine.onBotTickStarting();
        return from;
    }

    private static List<String> infoAbove(RecordingLogger log, String prefix) {
        return log.lines.stream().filter(l -> !l.startsWith("DEBUG ") && !l.startsWith("TRACE ") && l.contains(prefix))
            .toList();
    }

    @Test
    void aValuableStackOnTheCursorIsPickedUpFirstAfterTheReconnect() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        int before = sim.inv.count(ROCKET);
        // a rocket top-up cut short: the chest's full stack on the cursor when the connection ends
        sim.bot.cursorItem = ROCKET;
        sim.bot.cursorCount = 64;
        int from = disconnectAndBack(sim);
        assertEquals(1, sim.bot.ground.size(), "the server dropped the cursor's stack");
        assertTrue(sim.runUntil(() -> sim.bot.ground.isEmpty() && sim.bot.history.size() > from + 1, 2_000), sim.state());
        assertEquals(before + 64, sim.inv.count(ROCKET), "the rockets are back in the inventory");
        // before anything else: the first task of the new connection
        assertTrue(sim.bot.history.get(from).startsWith("recoverDrops"), sim.bot.history.subList(from, sim.bot.history.size()).toString());
        // the lanes go on afterwards
        assertTrue(sim.runUntil(() -> sim.bot.lastTask != null && sim.bot.lastTask.name().startsWith("lane"), 2_000), sim.state());
        // one line, no coordinates above debug
        List<String> lines = infoAbove(log, "Disconnect drop");
        assertEquals(List.of("INFO Disconnect drop: picked up 64 firework_rocket (on the cursor)"), lines);
        assertFalse(lines.getFirst().matches(".*-?\\d+, ?-?\\d+, ?-?\\d+.*"), lines.toString());
        assertEquals(1, sim.bot.count("recoverDrops"), "once per reconnect");
    }

    /**
     * Local drill 2026-10-25 (10x): the disconnect came 5 ms after the click, which had not reached the server; the
     * stack never left the inventory. Nothing to pick up, nothing lost: a debug line, no walk, the lanes go on.
     */
    @Test
    void aStackWhoseClickHadNotReachedTheServerIsBackNotLost() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        int before = sim.inv.count(ROCKET);
        sim.bot.cursorItem = ROCKET;
        sim.bot.cursorCount = 64;
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.bot.disconnect(false);
        sim.run(3);
        int from = sim.bot.history.size();
        sim.engine.onBotTickStarting();
        assertTrue(sim.bot.ground.isEmpty(), "nothing dropped");
        assertTrue(sim.runUntil(() -> sim.bot.lastTask != null && sim.bot.lastTask.name().startsWith("lane")
            && sim.bot.history.size() > from + 1, 2_000), sim.state());
        assertEquals(before + 64, sim.inv.count(ROCKET));
        assertTrue(infoAbove(log, "Disconnect drop").isEmpty(), log.lines.toString());
        assertTrue(log.at(Level.DEBUG).stream().anyMatch(l -> l.contains("64 firework_rocket back in the inventory")),
            log.at(Level.DEBUG).toString());
    }

    /**
     * A top-up's stack on the cursor when the connection ended, but neither on the ground nor in the inventory after the
     * reconnect: the click that took it from the chest had not reached the server. Said so, once, not called lost.
     */
    @Test
    void aCursorStackFoundNowhereIsMostLikelyStillInTheChest() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        sim.bot.cursorItem = ROCKET;
        sim.bot.cursorCount = 64;
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.bot.disconnect();
        sim.bot.ground.clear(); // the server never had it on the cursor: still in the chest
        sim.run(3);
        sim.engine.onBotTickStarting();
        assertTrue(sim.runUntil(() -> !infoAbove(log, "Disconnect drop").isEmpty(), 2_000), sim.state());
        assertEquals(List.of("INFO Disconnect drop: 64 firework_rocket not found (on the cursor when the connection"
            + " ended; none on the ground within 6 blocks, nor back in the inventory): most likely still in the container"
            + " it was being taken from"), infoAbove(log, "Disconnect drop"));
        sim.run(200);
        assertEquals(1, infoAbove(log, "Disconnect drop").size(), "logged once");
    }

    /**
     * Local drill 2026-10-25 (10x): the top-up's 64 rockets, thrown off the walkway beside the rocket chest, lay 3 blocks
     * below in the trench with no way there; the line said "most likely still in the container". Seen on the ground and
     * out of reach is lost, with where it lies.
     */
    @Test
    void aCursorStackSeenOutOfReachIsLostNotStillInTheChest() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        sim.bot.cursorItem = ROCKET;
        sim.bot.cursorCount = 64;
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.bot.disconnect();
        var e = sim.bot.ground.getFirst();
        sim.bot.ground.set(0, new io.github.digitalisp.mapart.bot.WorldView.ItemInFlight(e.entityId(), e.item(), e.count(), e.x(),
            e.y() - 3, e.z()));
        sim.run(3);
        sim.engine.onBotTickStarting();
        assertTrue(sim.runUntil(() -> !infoAbove(log, "Disconnect drop").isEmpty(), 2_000), sim.state());
        assertEquals(List.of("INFO Disconnect drop: 64 firework_rocket lost (on the cursor when the connection ended; seen"
            + " on the ground 3 blocks below where it dropped, no way to it)"), infoAbove(log, "Disconnect drop"));
    }

    @Test
    void aMaterialOnTheCursorIsNotWorthTheWalk() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        // a carpet top-up cut short: 64 white carpet dropped, not looked for
        sim.bot.cursorItem = Sim.WHITE;
        sim.bot.cursorCount = 64;
        int from = disconnectAndBack(sim);
        assertTrue(sim.runUntil(() -> sim.bot.history.size() > from, 2_000), sim.state());
        assertEquals(0, sim.bot.count("recoverDrops"), sim.bot.history.subList(from, sim.bot.history.size()).toString());
        assertEquals(1, sim.bot.ground.size());
        assertTrue(infoAbove(log, "Disconnect drop").isEmpty(), log.lines.toString());
        assertTrue(log.at(Level.DEBUG).stream().anyMatch(l -> l.contains("a material, not worth the walk")));
    }

    @Test
    void tooFarFromWhereItDroppedIsLoggedAsLostOnce() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        sim.bot.cursorItem = "minecraft:elytra";
        sim.bot.cursorCount = 1;
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        sim.bot.disconnect();
        // the server put the bot elsewhere (a death, a teleport): 20 blocks off
        sim.world.moveTo(new Pos((int) Math.floor(sim.world.x) + 20, (int) sim.world.y, (int) Math.floor(sim.world.z)));
        sim.run(3);
        sim.engine.onBotTickStarting();
        assertTrue(sim.runUntil(() -> !infoAbove(log, "Disconnect drop").isEmpty(), 2_000), sim.state());
        assertEquals(List.of("INFO Disconnect drop: 1 elytra lost (on the cursor when the connection ended; the bot is 20"
            + " blocks from where it dropped)"), infoAbove(log, "Disconnect drop"));
        assertEquals(1, sim.bot.ground.size(), "not walked to");
        sim.run(200);
        assertEquals(1, infoAbove(log, "Disconnect drop").size(), "logged once");
    }

    @Test
    void offTheKeyNothingIsLookedFor() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        sim.config.build.recoverDisconnectDrops = false;
        sim.bot.cursorItem = ROCKET;
        sim.bot.cursorCount = 64;
        int from = disconnectAndBack(sim);
        assertTrue(sim.runUntil(() -> sim.bot.history.size() > from, 2_000), sim.state());
        assertEquals(0, sim.bot.count("recoverDrops"));
        assertEquals(1, sim.bot.ground.size());
    }

    @Test
    void aReconnectWithNothingDroppedStartsNoPickup() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = midLane(log);
        int from = disconnectAndBack(sim);
        assertTrue(sim.runUntil(() -> sim.bot.history.size() > from, 2_000), sim.state());
        assertEquals(0, sim.bot.count("recoverDrops"));
        assertTrue(infoAbove(log, "Disconnect drop").isEmpty());
    }
}
