package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine eats only {@code build.foodItems} (and the foods in keepItems), never golden apples or other valuables:
 * the account is the owner's own. Out of those foods it fetches from the food chest, or alerts as before.
 */
class ConfiguredFoodTest {
    private static final int LONG = 80_000;
    private static final String CARROT = "minecraft:golden_carrot";
    private static final String BREAD = "minecraft:bread";
    private static final String APPLE = "minecraft:golden_apple";
    private static final String GAPPLE = "minecraft:enchanted_golden_apple";

    private static Job longJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4); // several restocks, about 40 hunger points
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0401", "Food", 1, 1, List.of("moss"), (x, z) -> art);
    }

    @Test
    void defaultFoodsAreTheKeepItemsFoods() {
        MapArtConfig c = new MapArtConfig();
        assertEquals(List.of(CARROT, "minecraft:cooked_beef", BREAD), c.build.foodItems);
        for (String f : c.build.foodItems) assertTrue(c.build.keepItems.contains(f), f);
    }

    @Test
    void onlyTheConfiguredFoodIsEatenNeverGoldenApples() {
        Sim sim = new Sim();
        sim.config.build.keepItems.removeIf(id -> id.equals(CARROT) || id.equals(BREAD) || id.equals("minecraft:cooked_beef"));
        sim.config.build.foodItems = List.of(BREAD, APPLE); // a listed golden apple is still never eaten
        sim.inv.remove(CARROT, 32);
        sim.inv.add(CARROT, 10); // carried, but not a configured food
        sim.inv.add(APPLE, 3);
        sim.inv.add(GAPPLE, 1);
        sim.bot.containers.get(Sim.FOOD).items.put(BREAD, 640);
        sim.bot.placementsPerHunger = 200;
        Job job = longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertTrue(sim.bot.eats > 3, "ate: " + sim.bot.eatenIds);
        assertTrue(sim.bot.eatenIds.stream().allMatch(BREAD::equals), "only bread: " + sim.bot.eatenIds);
        assertTrue(sim.bot.containers.get(Sim.FOOD).items.get(BREAD) < 640, "bread fetched from the food chest");
        assertEquals(3, sim.inv.count(APPLE), "golden apples untouched");
        assertEquals(1, sim.inv.count(GAPPLE), "enchanted golden apple untouched");
        assertEquals(10, sim.inv.count(CARROT), "an unconfigured food is not eaten, and kept");
        assertFalse(sim.bot.containers.get(Sim.RETURN).items.containsKey(APPLE));
    }

    @Test
    void outOfConfiguredFoodItFetchesInsteadOfEatingApples() {
        Sim sim = new Sim();
        sim.inv.remove(CARROT, 32);
        sim.inv.add(APPLE, 5);
        sim.inv.add(GAPPLE, 2);
        sim.bot.placementsPerHunger = 200;
        Job job = longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertTrue(sim.bot.count("withdraw " + Sim.FOOD) >= 1, sim.bot.history.toString());
        assertTrue(sim.bot.eats > 3);
        assertTrue(sim.bot.eatenIds.stream().allMatch(CARROT::equals), sim.bot.eatenIds.toString());
        assertEquals(5, sim.inv.count(APPLE));
        assertEquals(2, sim.inv.count(GAPPLE));
    }

    @Test
    void noConfiguredFoodAnywhereAlertsAndNeverEatsApples() {
        Sim sim = new Sim();
        sim.inv.remove(CARROT, 32);
        sim.inv.add(APPLE, 5);
        sim.inv.add(GAPPLE, 2);
        sim.bot.containers.get(Sim.FOOD).items.clear();
        sim.inv.foodLevel = 5; // starving soon
        longJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "No food"), LONG), sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertTrue(sim.bot.eatenIds.isEmpty(), sim.bot.eatenIds.toString());
        assertEquals(5, sim.inv.count(APPLE));
        assertEquals(2, sim.inv.count(GAPPLE));
    }
}
