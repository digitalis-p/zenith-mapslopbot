package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starving rescue: food while the engine does not work. Gate B2 (2026-10-11): the old
 * {@code safety.starvationDisconnect} disconnected the starving bot at health 6; on reconnect it came back at health 6,
 * hunger 0, no food, paused by the SAFETY STOP and BLOCKED, and disconnected again on its first tick: it could never
 * recover by itself. Now the plugin never disconnects for health (that is ZenithProxy's AutoDisconnect); a starving
 * bot fetches food from the food chest even while paused, safety-locked or BLOCKED, and AutoEat (or the engine) eats it.
 */
class StarvingRescueTest {
    private static final String CARROT = "minecraft:golden_carrot";

    /** Like the test server's 1x: 1 health point every 80 ticks at hunger 0 (Hard), regeneration at 18 or more. */
    private static Sim sim() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 1;
        sim.bot.starveTicks = 80;
        sim.bot.regenTicks = 10;
        return sim;
    }

    private static Job longJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0300", "Rescue", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static boolean laneRunning(Sim sim) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith("lane") && !t.isFinished();
    }

    private static void midLane(Sim sim) {
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, 80_000), sim.state());
    }

    private static Map<String, Integer> foodChest(Sim sim) {
        return sim.bot.containers.get(Sim.FOOD).items;
    }

    private static long fetchesSince(Sim sim, int from) {
        return tasksSince(sim, from).stream().filter(h -> h.startsWith("withdraw " + Sim.FOOD)).count();
    }

    private static List<String> tasksSince(Sim sim, int from) {
        return List.copyOf(sim.bot.history.subList(from, sim.bot.history.size()));
    }

    private static void disconnect(Sim sim) {
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
    }

    /**
     * Gate B2's stuck bot: out of food mid-build (BLOCKED), then the old build's starvation SAFETY STOP and its
     * disconnect; offline at health 6, hunger 0, no food carried, the food chest empty.
     */
    private static void stuckBot(Sim sim) {
        longJob(sim);
        midLane(sim);
        sim.inv.items.remove(CARROT);
        foodChest(sim).clear();
        sim.bot.ticksPerHungerPoint = 2;
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 400), sim.state());
        sim.bot.ticksPerHungerPoint = 0;
        sim.engine.emergencyStop("starving: health 6/20, hunger 0/20, no food in the inventory; the food chests gave none."
            + " Disconnecting: starvation damage goes on while BLOCKED or paused");
        disconnect(sim);
        sim.inv.foodLevel = 0;
        sim.inv.health = 6f;
        sim.bot.lowestHealth = 6f;
    }

    private static void assertStillStopped(Sim sim) {
        assertTrue(sim.engine.status().paused(), sim.state());
        assertTrue(sim.engine.safetyLocked(), "the safety lock stays: " + sim.state());
        assertTrue(sim.engine.status().pauseReason().startsWith("SAFETY STOP: starving"), sim.state());
        assertEquals(Phase.BLOCKED, sim.phase(), "BLOCKED stays: " + sim.state());
    }

    @Test
    void connectStarvingWithFoodInTheChestFetchesAndAutoEatEats() {
        Sim sim = sim();
        stuckBot(sim);
        sim.newEngine(); // ZenithProxy restarted with the fixed plugin: the SAFETY STOP and BLOCKED come back from disk
        foodChest(sim).put(CARROT, 640); // restocked
        sim.bot.autoEatEats = true; // the owner's ZenithProxy has AutoEat on
        int from = sim.bot.history.size();
        int autoEatTicksBefore = sim.bot.ticksWithAutoEatOn;
        int alertsBefore = sim.notifier.of("alert").size();

        sim.start(); // zenith-cmd.sh connect
        assertTrue(sim.runUntil(() -> sim.engine.status().note().startsWith("starving: fetching food"), 100), sim.state());
        assertTrue(sim.runUntil(() -> sim.bot.autoEats > 0, 100), sim.state());
        sim.run(2000);

        assertFalse(sim.bot.died, "survived");
        assertTrue(sim.bot.lowestHealth >= 5f, "at most one starvation hit before the food came: " + sim.bot.lowestHealth);
        assertTrue(sim.inv.health >= 14f, "regenerating: " + sim.inv.health);
        assertTrue(sim.inv.foodLevel > 0);
        assertTrue(sim.inv.foodCount() > 0, "food left over");
        assertEquals(List.of("withdraw " + Sim.FOOD), tasksSince(sim, from).stream()
            .map(h -> h.substring(0, ("withdraw " + Sim.FOOD).length())).toList(), "only the food fetch: " + tasksSince(sim, from));
        assertEquals(autoEatTicksBefore, sim.bot.ticksWithAutoEatOn, "AutoEat off during the fetch");
        assertEquals(0, sim.bot.autoEatsBesideTask, "AutoEat ate only between the engine's tasks");
        assertEquals(0, sim.bot.eats, "AutoEat is on: the engine does not eat");
        assertStillStopped(sim);
        assertTrue(sim.modules.isEnabled("AutoEat"), "modules back after the fetch");
        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert");
        assertTrue(alerts.subList(alertsBefore, alerts.size()).stream().anyMatch(m -> m.title().equals("Starving")
                && m.body().contains("Starving: fetching food from the food chest (health 6)")
                && m.body().contains("The SAFETY STOP stays")), sim.notifier.toString());
        assertTrue(alerts.subList(alertsBefore, alerts.size()).stream().noneMatch(m -> m.title().equals("SAFETY STOP")),
            "no new safety stop, no disconnect: " + sim.notifier);
    }

    @Test
    void withAutoEatOffTheEngineEatsWhileStillStopped() {
        Sim sim = sim();
        stuckBot(sim);
        foodChest(sim).put(CARROT, 640);
        sim.modules.state.put("AutoEat", false); // the owner turned AutoEat off in ZenithProxy
        int from = sim.bot.history.size();

        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, 200), sim.state());
        sim.run(2000);

        assertFalse(sim.bot.died);
        assertTrue(sim.inv.foodLevel >= EngineTuning.REGEN_FOOD, "ate until it can regenerate: " + sim.inv.foodLevel);
        assertTrue(sim.inv.health >= 14f, "regenerating: " + sim.inv.health);
        assertEquals(1, fetchesSince(sim, from));
        assertTrue(tasksSince(sim, from).stream().allMatch(h -> h.startsWith("withdraw " + Sim.FOOD) || h.equals("eat")),
            "only the fetch and eating: " + tasksSince(sim, from));
        assertTrue(sim.bot.eatPhases.contains(Phase.BLOCKED), "ate while BLOCKED: " + sim.bot.eatPhases);
        assertStillStopped(sim);
    }

    @Test
    void connectStarvingWithNoFoodAnywhereBlocksOnceWithoutALoopAndARestockedChestRescuesOnReconnect() {
        Sim sim = sim();
        sim.bot.autoEatEats = true;
        stuckBot(sim);
        sim.bot.starveTicks = 0; // control flow only: the bot stays alive here
        int from = sim.bot.history.size();
        int alertsBefore = sim.notifier.of("alert").size();

        sim.start();
        assertTrue(sim.runUntil(() -> fetchesSince(sim, from) == 1, 100), sim.state());
        sim.run(1000);
        assertEquals(1, fetchesSince(sim, from), "one look at the chest per episode and connection: "
            + tasksSince(sim, from));
        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert");
        List<SimFakes.RecordingNotifier.Msg> since = alerts.subList(alertsBefore, alerts.size());
        assertEquals(1, since.stream().filter(m -> m.title().equals("No food")).count(), sim.notifier.toString());
        assertTrue(since.stream().noneMatch(m -> m.title().equals("SAFETY STOP")), "no new safety stop: " + sim.notifier);
        assertStillStopped(sim);

        // AutoDisconnect (ZenithProxy) or the owner disconnects; the chest is restocked; the next connection fetches again
        disconnect(sim);
        foodChest(sim).put(CARROT, 640);
        int from2 = sim.bot.history.size();
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.autoEats > 0, 200), sim.state());
        assertEquals(1, fetchesSince(sim, from2));
        assertTrue(sim.inv.foodLevel > 0);
        assertStillStopped(sim);
    }

    @Test
    void pausedMidBuildWithNoFoodGoesBlockedAndAlerts() {
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.engine.pause("jared2013");
        disconnect(sim);
        sim.inv.items.remove(CARROT);
        foodChest(sim).clear();
        sim.inv.foodLevel = 0;
        sim.inv.health = 6f;
        sim.bot.starveTicks = 0;
        int from = sim.bot.history.size();

        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 200), sim.state());
        assertEquals(1, fetchesSince(sim, from));
        assertTrue(sim.engine.status().note().contains("out of food"), sim.state());
        assertTrue(sim.notifier.has("alert", "No food"), sim.notifier.toString());
        assertTrue(sim.notifier.has("alert", "Map art blocked"), sim.notifier.toString());
        assertTrue(sim.engine.status().paused(), "the pause stays: " + sim.state());
        assertFalse(sim.engine.safetyLocked());
        sim.run(500);
        assertEquals(1, fetchesSince(sim, from), "no loop");
    }

    @Test
    void aStopInThisConnectionHoldsTheRescueUntilTheNextConnection() {
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.engine.emergencyStop("manual stop by jared2013 (Discord)");
        sim.inv.items.remove(CARROT);
        sim.inv.foodLevel = 0;
        sim.inv.health = 10f;
        sim.bot.starveTicks = 0;
        int from = sim.bot.history.size();

        sim.run(300);
        assertEquals(List.of(), tasksSince(sim, from), "the kill switch holds: the bot does not move");
        assertTrue(sim.notifier.toString().contains("holds the food fetch"), sim.notifier.toString());

        disconnect(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> fetchesSince(sim, from) == 1, 100), "a new connection fetches: " + sim.state());
        assertTrue(sim.engine.safetyLocked(), "the stop itself stays");
    }

    @Test
    void idleStarvingFetchesAndEats() {
        Sim sim = sim();
        sim.inv.items.remove(CARROT);
        sim.inv.foodLevel = 0;
        sim.inv.health = 10f;
        sim.bot.starveTicks = 0;

        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, 200), sim.state());
        assertEquals(1, fetchesSince(sim, 0), sim.bot.history.toString());
        assertEquals(Phase.IDLE, sim.phase(), sim.state());
        assertFalse(sim.engine.status().paused());
        assertTrue(sim.notifier.has("alert", "Starving"), sim.notifier.toString());
    }

    @Test
    void aFoodChestThatDoesNotLoadCountsAsOutOfReach() {
        Sim sim = sim();
        stuckBot(sim);
        foodChest(sim).put(CARROT, 640);
        sim.world.loaded = p -> !p.equals(Sim.FOOD); // the bot is far from it
        sim.bot.starveTicks = 0;
        int from = sim.bot.history.size();
        int alertsBefore = sim.notifier.of("alert").size();

        sim.start();
        sim.run(BuildEngine.RESCUE_LOAD_WAIT_TICKS + EngineTuning.START_DELAY_TICKS + 20);
        assertEquals(0, fetchesSince(sim, from), "no walk to an unloaded chest");
        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert");
        assertTrue(alerts.subList(alertsBefore, alerts.size()).stream()
            .anyMatch(m -> m.title().equals("No food") && m.body().contains("not loaded")), sim.notifier.toString());
        assertStillStopped(sim);
    }
}
