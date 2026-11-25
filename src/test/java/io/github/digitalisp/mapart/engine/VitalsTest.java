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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hunger and health are checked every tick, not only between tasks. Live bug (10x test, 2026-10-11): with
 * {@code effect give MapArtBot minecraft:hunger 30 100} the hunger bar fell 20 → 0 in 1.4 s and health 20 → 0 in
 * 7.5 s while lane 7 kept running (a 128-long lane runs 1,500-2,600 ticks); the bot starved to death with its Mending
 * tools. Now the lane stops at its next safe point, the bot eats standing still, and the same lane carries on.
 */
class VitalsTest {
    private static final int LONG = 80_000;
    private static final String CARROT = "minecraft:golden_carrot";

    /** Like the test server: long lanes, starving costs a health point every 8 ticks at hunger 0. */
    private static Sim sim() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 1; // a 640-cell lane takes 640 ticks
        sim.bot.starveTicks = 8;
        return sim;
    }

    private static Job longJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0200", "Vitals", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static boolean laneRunning(Sim sim) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith("lane") && !t.isFinished();
    }

    /** Starts the job and runs until a lane is well under way; returns that lane. */
    private static BotTask midLane(Sim sim) {
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.run(30);
        assertTrue(laneRunning(sim), sim.state());
        return sim.bot.lastTask;
    }

    private static int foodChest(Sim sim) {
        return sim.bot.containers.get(Sim.FOOD).items.getOrDefault(CARROT, 0);
    }

    @Test
    void severeHungerMidLaneStopsTheLaneEatsAndCarriesOn() {
        Sim sim = sim();
        sim.inv.add(CARROT, 160);
        Job job = longJob(sim);
        BotTask lane = midLane(sim);
        // "effect give MapArtBot minecraft:hunger 30 100": the bar is empty in 40 ticks
        sim.bot.ticksPerHungerPoint = 2;
        sim.bot.hungerUntil = sim.ticks + 600;

        assertTrue(sim.runUntil(() -> sim.bot.parks > 0, 60), "the lane stopped for food: " + sim.state());
        assertFalse(lane.isFinished(), "parked, not cancelled");
        assertTrue(sim.runUntil(() -> sim.bot.eatsWhileParked > 0, 60), sim.state());
        assertTrue(sim.runUntil(() -> lane.isFinished(), LONG), sim.state());
        assertNull(lane.failure(), "the same lane carried on and finished: " + lane.failure());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertFalse(sim.bot.died, "never dead");
        assertEquals(20f, sim.bot.lowestHealth, "never starving: health never dropped");
        assertTrue(sim.bot.lowestFoodLevel > 0, "hunger never reached 0: " + sim.bot.lowestFoodLevel);
        assertTrue(sim.bot.parks > 1, "stopped every time the bar ran low: " + sim.bot.parks);
        assertEquals(sim.bot.parks, sim.bot.resumes, "every stopped task carried on");
        assertEquals(0, sim.bot.eatTicksBesideOtherTask, "never ate while another task was running");
        assertEquals(0, sim.bot.ticksWithAutoEatOn);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void outOfFoodMidLaneFetchesFromTheFoodChestAndCarriesOn() {
        Sim sim = sim();
        Job job = longJob(sim);
        BotTask lane = midLane(sim);
        sim.inv.items.remove(CARROT);
        int chestBefore = foodChest(sim);
        int historyBefore = sim.bot.history.size();
        sim.bot.ticksPerHungerPoint = 2;
        sim.bot.hungerUntil = sim.ticks + 200;

        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, 200), sim.state());
        List<String> since = sim.bot.history.subList(historyBefore, sim.bot.history.size());
        assertTrue(since.stream().anyMatch(h -> h.startsWith("withdraw " + Sim.FOOD)), "fetched food: " + since);
        assertTrue(sim.bot.parks > 0, "the lane waited for the fetch");
        assertFalse(lane.isFinished(), "the lane was not dropped for the fetch");
        assertTrue(foodChest(sim) < chestBefore);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertNull(lane.failure(), lane.failure());
        assertFalse(sim.bot.died);
        assertEquals(20f, sim.bot.lowestHealth);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void noFoodAnywhereBlocksBeforeDeath() {
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.inv.items.remove(CARROT);
        sim.bot.containers.get(Sim.FOOD).items.clear();
        sim.bot.ticksPerHungerPoint = 2; // and it does not stop

        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 400), sim.state());
        assertFalse(sim.bot.died);
        assertEquals(20f, sim.bot.lowestHealth, "BLOCKED before starving began");
        assertTrue(sim.inv.foodLevel <= EngineTuning.FOOD_CRITICAL, "hunger " + sim.inv.foodLevel);
        assertTrue(sim.engine.status().note().contains("out of food"), sim.state());
        assertTrue(sim.notifier.has("alert", "No food"), sim.notifier.toString());
        assertTrue(sim.notifier.has("alert", "Map art blocked"), sim.notifier.toString());
        assertTrue(sim.bot.parks > 0, "the lane was stopped, not run to its end");
        assertTrue(sim.modules.isEnabled("AutoEat"), "ZenithProxy's modules are back while BLOCKED");
    }

    @Test
    void lowHealthWithoutFoodToRegenerateBlocks() {
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.inv.items.remove(CARROT);
        sim.bot.containers.get(Sim.FOOD).items.clear();
        sim.inv.foodLevel = 12; // too low for natural regeneration
        sim.inv.health = 7f;

        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 200), sim.state());
        assertTrue(sim.engine.status().note().contains("no food to regenerate"), sim.state());
        assertTrue(sim.notifier.has("alert", "Low health"), sim.notifier.toString());
        assertTrue(sim.notifier.has("alert", "No food"), sim.notifier.toString());
        assertFalse(sim.bot.died);
    }

    @Test
    void lowHealthStopsAlertsAndCarriesOnOnceHealthIsBack() {
        Sim sim = sim();
        Job job = longJob(sim);
        BotTask lane = midLane(sim);
        sim.inv.health = 7f; // a fall or a mob; the hunger bar is full

        assertTrue(sim.runUntil(() -> sim.bot.parks > 0, 20), sim.state());
        assertTrue(sim.notifier.has("alert", "Low health"), sim.notifier.toString());
        int placed = sim.bot.placed;
        long tasks = sim.bot.history.size();
        sim.run(300);
        assertEquals(placed, sim.bot.placed, "stands still while health is low");
        assertEquals(tasks, sim.bot.history.size(), "no new task while health is low");
        assertEquals(0, sim.bot.eats, "the bar is full: it waits for regeneration");
        assertTrue(sim.engine.status().note().contains("low health"), sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase());

        sim.bot.regenTicks = 10; // natural regeneration at a full bar
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertNull(lane.failure(), "the same lane carried on: " + lane.failure());
        assertEquals(sim.bot.parks, sim.bot.resumes);
        assertEquals(1, sim.notifier.of("alert").size(), sim.notifier.toString());
    }

    @Test
    void lowHealthWithHungerEatsToRegenerate() {
        Sim sim = sim();
        Job job = longJob(sim);
        midLane(sim);
        sim.bot.regenTicks = 10;
        sim.inv.foodLevel = 15;
        sim.inv.health = 6f;

        assertTrue(sim.runUntil(() -> sim.bot.eatsWhileParked > 0, 40), sim.state());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(sim.bot.died);
        assertEquals(sim.bot.parks, sim.bot.resumes);
    }

    @Test
    void healthStillDroppingWhileStandingStillBlocksWithLeavingOff() {
        Sim sim = sim();
        sim.config.safety.leaveWhenHurt = false;
        longJob(sim);
        midLane(sim);
        sim.inv.health = 8f;
        assertTrue(sim.runUntil(() -> sim.bot.parks > 0, 20), sim.state());
        sim.run(20);
        assertNotEquals(Phase.BLOCKED, sim.phase());
        sim.inv.health = 5f; // a mob keeps hitting the standing bot
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 40), sim.state());
        assertTrue(sim.engine.status().note().contains("still dropping"), sim.state());
        assertTrue(sim.bot.escapes.isEmpty(), "the old behavior: no leave");
    }

    @Test
    void healthStillDroppingWhileStandingStillLeavesTheCellBeforeItBlocks() {
        // live 2026-10-12: health 9.3 → 7.9 → 5.1 while the bot stood still in a health hold, then BLOCKED, then dead
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.inv.health = 8f;
        assertTrue(sim.runUntil(() -> sim.bot.parks > 0, 20), sim.state());
        sim.run(20);
        Pos stood = sim.world.playerBlock();
        sim.inv.health = 6.5f; // still dropping where it stands
        assertTrue(sim.runUntil(() -> sim.bot.escapes.size() == 1, 40), sim.state());
        assertEquals(stood, sim.bot.escapes.getFirst().cell(), "it leaves the cell it was hurt in");
        assertTrue(sim.bot.escapes.getFirst().avoid().contains(stood), "and does not come back to it");
        assertTrue(sim.runUntil(() -> sim.bot.escapeTasks.getFirst().isFinished(), 40), sim.state());
        assertNotEquals(stood, sim.world.playerBlock(), "it moved");
        sim.run(40);
        assertNotEquals(Phase.BLOCKED, sim.phase(), "health stopped dropping where it went: it holds there");

        sim.inv.health = 5.5f; // dropping again: leaves once more
        assertTrue(sim.runUntil(() -> sim.bot.escapes.size() == 2, 40), sim.state());
        assertTrue(sim.runUntil(() -> sim.bot.escapeTasks.get(1).isFinished(), 40), sim.state());
        sim.inv.health = 4f; // and still: a human must look
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 40), sim.state());
        assertTrue(sim.engine.status().note().contains("still dropping"), sim.state());
        assertEquals(2, sim.bot.escapes.size(), "at most two leaves per hold");
    }

    @Test
    void statusNoteSaysEatingWhileTheBotEats() {
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.inv.foodLevel = 5;
        assertTrue(sim.runUntil(() -> sim.bot.lastTask.name().startsWith("eat") && !sim.bot.lastTask.isFinished(), 40),
            sim.state());
        assertTrue(sim.engine.status().note().startsWith(BuildEngine.EATING_NOTE), sim.state());
        assertEquals(Phase.BUILD, sim.phase(), "the phase stays; the note says what happens");
        assertTrue(sim.runUntil(() -> sim.bot.resumes > 0, 100), sim.state());
        sim.tick();
        assertFalse(sim.engine.status().note().startsWith(BuildEngine.EATING_NOTE), "cleared: " + sim.state());
    }

    @Test
    void eatingBetweenTasksStillWaitsForCalmPhases() {
        // ordinary hunger (above the critical level) is not worth stopping a lane for
        Sim sim = sim();
        longJob(sim);
        BotTask lane = midLane(sim);
        sim.inv.foodLevel = 10; // below eatFoodLevel 14, above FOOD_CRITICAL 6
        sim.run(40);
        assertEquals(0, sim.bot.parks, "the lane runs on");
        assertSame(lane, sim.bot.lastTask);
        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, LONG), sim.state());
        assertTrue(lane.isFinished(), "ate after the lane");
    }

    // ---- starving while working (gate B, t1b and B2): the working engine's own fetch; the plugin never disconnects ----

    /** Food fetches tried at the food chest so far (an injected failure counts too). */
    private static long foodFetches(Sim sim) {
        return sim.bot.history.stream().filter(h -> h.startsWith("withdraw " + Sim.FOOD)).count();
    }

    @Test
    void starvingWithFoodCarriedEats() {
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.inv.add(CARROT, 16);
        sim.inv.foodLevel = 0;
        sim.inv.health = 6f;
        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, 100), sim.state());
        sim.run(200);
        assertFalse(sim.engine.status().paused(), sim.state());
        assertFalse(sim.bot.died);
        assertEquals(0, foodFetches(sim), "food carried: no fetch");
    }

    @Test
    void starvingMidLaneFetchesAndAlertsStarving() {
        Sim sim = sim();
        sim.bot.starveTicks = 0; // the fetch is instant here: only the alert matters
        longJob(sim);
        midLane(sim);
        sim.inv.items.remove(CARROT);
        sim.inv.foodLevel = 0;
        sim.inv.health = 6.5f;
        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, 200), sim.state());
        assertTrue(sim.notifier.has("alert", "Starving"), sim.notifier.toString());
        assertTrue(sim.notifier.toString().contains("Starving: fetching food from the food chest (health 6.5)"),
            sim.notifier.toString());
        assertFalse(sim.engine.status().paused(), "no SAFETY STOP: " + sim.state());
        assertEquals(1, foodFetches(sim));
        assertTrue(sim.inv.foodCount() > 0, "food carried again");
    }

    @Test
    void starvingWithNoFoodAnywhereBlocksAndNeverDisconnects() {
        // gate B2: carrots cleared, the food chest gives none, a hunger effect. BLOCKED, the "No food" alert, and
        // starvation goes on: disconnecting for health is ZenithProxy's AutoDisconnect, not the plugin's
        Sim sim = sim();
        longJob(sim);
        midLane(sim);
        sim.inv.items.remove(CARROT);
        sim.bot.failNext.put("withdraw", 1000); // gate B: "food withdraw failed"
        sim.bot.ticksPerHungerPoint = 2;

        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 400), sim.state());
        assertTrue(sim.notifier.has("alert", "No food"), sim.notifier.toString());
        assertTrue(sim.runUntil(() -> sim.inv.health <= 2f, 3000), sim.state());
        assertFalse(sim.engine.status().paused(), "no SAFETY STOP, no disconnect: " + sim.state());
        assertEquals(Phase.BLOCKED, sim.phase());
        // the working engine's round at hunger 6, then one rescue round when starvation began: no loop
        assertEquals(2, foodFetches(sim), sim.bot.history.toString());
        sim.run(1000);
        assertEquals(2, foodFetches(sim), "no more fetches in this episode and connection");
    }
}
