package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine eats by itself at safe points and keeps ZenithProxy's AutoEat off while it works. */
class EatingTest {
    private static final int LONG = 80_000;
    private static final String CARROT = "minecraft:golden_carrot";
    /** Phases where an eat task may run: never mid-clear (lever timing), idle or blocked. */
    private static final Set<Phase> NO_EAT = Set.of(Phase.IDLE, Phase.SELECT, Phase.CLEAR, Phase.BLOCKED);

    private static Job longJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4); // 8704 blocks, several restock rounds
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0100", "Long", 1, 1, List.of("moss"), (x, z) -> art);
    }

    @Test
    void eatsBeforeStarvingOnlyBetweenTasksWithAutoEatOff() {
        Sim sim = new Sim();
        sim.bot.placementsPerHunger = 200; // 640-block lane: about 3 hunger points
        Job job = longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        int drained = 8704 / 200;
        assertTrue(sim.bot.eats >= (drained - 6) / 6, "ate enough: " + sim.bot.eats + " for " + drained + " hunger");
        assertTrue(sim.bot.lowestFoodLevel > sim.config.build.eatFoodLevel - 4,
            "hunger never fell far below the eat threshold: " + sim.bot.lowestFoodLevel);
        assertEquals(0, sim.bot.eatTicksBesideOtherTask, "eating only while no walk/lane/container task is running");
        for (Phase p : sim.bot.eatPhases) assertFalse(NO_EAT.contains(p), "ate in " + p);
        assertTrue(sim.bot.eatPhases.contains(Phase.BUILD) || sim.bot.eatPhases.contains(Phase.RESTOCK),
            sim.bot.eatPhases.toString());
        assertEquals(0, sim.bot.ticksWithAutoEatOn, "AutoEat was off whenever an engine task ticked");
        assertEquals(1, sim.output().maps.size());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());

        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), sim.state());
        assertTrue(sim.modules.isEnabled("AutoEat"), "AutoEat back on when idle");
    }

    @Test
    void autoEatRestoredWhenTheEngineStops() {
        Sim sim = new Sim();
        longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.run(5);
        assertFalse(sim.modules.isEnabled("AutoEat"), "off while building");

        sim.engine.onBotTickStopped(); // a player took control or the client disconnected
        assertTrue(sim.modules.isEnabled("AutoEat"), "restored on bot tick stop");

        sim.start();
        sim.run(60);
        assertFalse(sim.modules.isEnabled("AutoEat"), "off again after the bot ticks restart");
        sim.engine.pause("test");
        sim.run(2);
        assertTrue(sim.modules.isEnabled("AutoEat"), "restored while paused");

        sim.engine.resume();
        sim.run(60);
        assertFalse(sim.modules.isEnabled("AutoEat"));
        sim.engine.shutdown();
        assertTrue(sim.modules.isEnabled("AutoEat"), "restored on shutdown");
        assertEquals(0, sim.bot.ticksWithAutoEatOn);
    }

    @Test
    void stockScanWhileIdleRunsWithAutoEatOff() {
        Sim sim = new Sim();
        sim.start();
        sim.run(100);
        assertEquals(Phase.IDLE, sim.phase());
        assertTrue(sim.modules.isEnabled("AutoEat"), "on while idle");
        sim.engine.requestStockScan();
        assertTrue(sim.runUntil(() -> sim.bot.count("scan") >= 5, 2_000), sim.bot.history.toString());
        assertTrue(sim.runUntil(() -> sim.modules.isEnabled("AutoEat"), 2_000), "restored after the scan");
        assertEquals(0, sim.bot.ticksWithAutoEatOn, "the scan walks ran with AutoEat off");
    }

    @Test
    void foodIsRestockedFromTheFoodChestDuringALongBuild() {
        Sim sim = new Sim();
        sim.inv.items.put(CARROT, EngineTuning.FOOD_MIN + 1); // enough for GEAR, not for the whole tile
        sim.bot.placementsPerHunger = 100;
        int chestBefore = sim.bot.containers.get(Sim.FOOD).items.get(CARROT);
        Job job = longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        List<String> h = sim.bot.history;
        int firstLane = h.indexOf(h.stream().filter(s -> s.startsWith("placeLane")).findFirst().orElseThrow());
        assertTrue(h.subList(firstLane, h.size()).stream().anyMatch(s -> s.startsWith("withdraw " + Sim.FOOD)),
            "food withdrawn from the food chest after building started: " + h);
        assertTrue(sim.bot.containers.get(Sim.FOOD).items.getOrDefault(CARROT, 0) < chestBefore);
        assertTrue(sim.bot.eats > EngineTuning.FOOD_MIN + 1, "ate more than the starting food: " + sim.bot.eats);
        assertTrue(sim.bot.lowestFoodLevel > 0, "never starving");
        assertEquals(0, sim.bot.eatTicksBesideOtherTask);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void outOfFoodEverywhereBlocksBeforeStarving() {
        Sim sim = new Sim();
        sim.inv.items.remove(CARROT);
        sim.bot.containers.get(Sim.FOOD).items.clear();
        sim.bot.placementsPerHunger = 200;
        longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.engine.status().note().contains("out of food"), sim.state());
        assertTrue(sim.notifier.has("alert", "No food"), sim.notifier.toString());
        assertTrue(sim.inv.foodLevel > 0 && sim.inv.foodLevel <= EngineTuning.FOOD_CRITICAL, "hunger " + sim.inv.foodLevel);
        assertEquals(0, sim.bot.eats);
        assertTrue(sim.modules.isEnabled("AutoEat"), "AutoEat back on while blocked");
    }

    @Test
    void lowHealthEatsAtTheNextSafePoint() {
        Sim sim = new Sim();
        sim.inv.foodLevel = 18; // above the threshold
        sim.inv.health = 8f;
        longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.eats > 0, LONG), sim.state());
        assertEquals(20, sim.inv.foodLevel);
        assertFalse(NO_EAT.contains(sim.bot.eatPhases.getFirst()), sim.bot.eatPhases.toString());
    }

    @Test
    void eatFoodLevelZeroLeavesEatingToAutoEat() {
        Sim sim = new Sim();
        sim.config.build.eatFoodLevel = 0;
        sim.inv.foodLevel = 4;
        longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.run(200);
        assertTrue(sim.modules.isEnabled("AutoEat"), "AutoEat untouched");
        assertFalse(sim.modules.isEnabled("AntiAFK"));
        assertEquals(0, sim.bot.eats);
    }
}
