package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory.FakeTool;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.BLACK;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * The bot runs on the owner's MAIN account: only materials leave the inventory (live, 2026-10-11: at each RESTOCK it tried to
 * drop the totem of undying from the offhand as a "leftover"). Totems, gear, shulker boxes, unknown items and maps the
 * bot did not make stay where they are; the restock load is planned around the slots they hold.
 */
class PersonalItemsTest {
    private static final int LONG = 200_000;
    private static final String TOTEM = "minecraft:totem_of_undying";
    private static final String SWORD = "minecraft:diamond_sword";
    private static final String SHULKER = "minecraft:shulker_box";
    /** In no job, no material chest, no keep list: an item the bot knows nothing about. */
    private static final String UNKNOWN = "minecraft:nautilus_shell";

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static void assertDone(Sim sim, Job job, RecordingLogger log) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state() + tail(log));
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), "not idle after done: " + sim.state());
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 40), l.size()));
    }

    /** the owner's things (totem in the offhand) and leftover carpets: sorter items this job does not use. */
    private static void personalInventory(Sim sim) {
        sim.inv.offhand.put(TOTEM, 1);
        sim.inv.add(SWORD, 1);
        sim.inv.add(SHULKER, 1);
        sim.inv.add(UNKNOWN, 3);
        sim.inv.add(RED, 100);
        sim.inv.add(BLACK, 64);
    }

    private static void assertPersonalUntouched(Sim sim) {
        assertEquals(Map.of(TOTEM, 1), sim.inv.offhand, "offhand untouched");
        assertEquals(1, sim.inv.count(SWORD), "sword kept");
        assertEquals(1, sim.inv.count(SHULKER), "shulker box kept");
        assertEquals(3, sim.inv.count(UNKNOWN), "unknown item kept");
        assertTrue(sim.inv.hasTools(), "tools kept");
        assertTrue(sim.inv.foodCount() > 0, "food kept");
    }

    private static long count(RecordingLogger log, String prefix) {
        return log.at(Level.INFO).stream().filter(l -> l.startsWith(prefix)).count();
    }

    @Test
    void onlyTheCarpetsAreDroppedAtTheDropPoint() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.site.storage.returnChests.clear(); // the moss site: leftovers go into the water channel
        MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
        drop.standAt = new Pos(-65, 64, -66);
        drop.target = new Pos(-65, 60, -62);
        sim.config.site.storage.returnDrops.add(drop);
        personalInventory(sim);
        Job job = sim.addJob("j0201", "Mine", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 24));
        sim.start();
        assertDone(sim, job, log);

        assertEquals(Map.of(RED, 100, BLACK, 64), sim.bot.droppedItems, "only the carpets were dropped");
        assertEquals(0, sim.inv.count(RED));
        assertEquals(0, sim.inv.count(BLACK));
        assertPersonalUntouched(sim);
        for (String l : log.at(Level.INFO)) {
            if (!l.startsWith("dropping") && !l.startsWith("dropped")) continue;
            for (String id : List.of(TOTEM, SWORD, SHULKER, UNKNOWN)) assertFalse(l.contains(id), l);
        }
        // several restocks, one "keeping" line in the session
        assertTrue(count(log, "restock:") >= 2, tail(log));
        assertEquals(1, count(log, "keeping "), tail(log));
        assertTrue(log.at(Level.INFO).contains("keeping 4 non-material items: minecraft:totem_of_undying (offhand), "
            + "minecraft:diamond_sword, minecraft:nautilus_shell ×3, minecraft:shulker_box"), tail(log));
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.startsWith("could not get rid of")), tail(log));
    }

    @Test
    void onlyTheCarpetsGoIntoTheReturnChest() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        personalInventory(sim);
        sim.inv.add(TOTEM, 2); // spare totems in the main inventory stay too
        Job job = sim.addJob("j0202", "Mine", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 24));
        sim.start();
        assertDone(sim, job, log);

        assertEquals(Map.of(RED, 100, BLACK, 64), sim.bot.containers.get(Sim.RETURN).items, "only the carpets went back");
        assertTrue(sim.bot.droppedItems.isEmpty());
        assertPersonalUntouched(sim);
        assertEquals(2, sim.inv.count(TOTEM), "main-inventory totems kept");
        assertTrue(log.at(Level.INFO).contains("keeping 5 non-material items: minecraft:totem_of_undying (offhand), "
            + "minecraft:diamond_sword, minecraft:nautilus_shell ×3, minecraft:shulker_box, minecraft:totem_of_undying ×2"),
            tail(log));
    }

    @Test
    void theLoadIsPlannedAroundTheSlotsUntouchableItemsHold() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.bot.wholeStackWithdrawals = true; // shift-clicks, like the live bot
        sim.inv.offhand.put(TOTEM, 1);       // outside the 36 slots
        sim.inv.add(UNKNOWN, 20 * 64);       // 20 slots
        sim.inv.add(SWORD, 1);               // 1 slot
        // 36 - pickaxe - carrots - 21 = 13 empty; reservedSlots 5 minus the 2 kept stacks keeps 3 of them: 10 for carpets
        Job job = sim.addJob("j0203", "Around", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 24));
        int[] maxWhite = {0};
        int[] maxUsed = {0};
        sim.start();
        assertTrue(sim.runUntil(() -> {
            maxWhite[0] = Math.max(maxWhite[0], sim.inv.slotsUsed().getOrDefault(WHITE, 0));
            maxUsed[0] = Math.max(maxUsed[0], sim.inv.slotsUsed().values().stream().mapToInt(Integer::intValue).sum());
            return job.status == JobStatus.DONE;
        }, LONG), sim.state() + tail(log));

        assertEquals(10, maxWhite[0], "each load fills exactly the slots left around them" + tail(log));
        assertTrue(maxUsed[0] <= 36, maxUsed[0] + " slots used");
        assertEquals(20 * 64, sim.inv.count(UNKNOWN), "never deposited to make room");
        assertEquals(1, sim.inv.count(SWORD));
        assertEquals(Map.of(TOTEM, 1), sim.inv.offhand);
        assertTrue(log.at(Level.INFO).stream().noneMatch(l -> l.startsWith("withdraw from")), "no short withdrawal" + tail(log));
        // 3072 cells at 640 per trip: 5 trips (a 6th only if a round ends short)
        long trips = count(log, "restock:");
        assertTrue(trips >= 5 && trips <= 6, trips + " restocks" + tail(log));
        assertTrue(sim.notifier.of("alert").isEmpty(), "a lane still fits: no alert " + sim.notifier);
    }

    @Test
    void theChestplateAFlightSwappedOutIsTheKitsSlotNotAnUntouchableItem() {
        // live 2026-10-25: the elytra worn at the RESTOCK planning (the gear swap of a flight, the chestplate goes back
        // on after the landing) made "1 slot hold items the bot won't touch: minecraft:diamond_chestplate"
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.elytraLegs = true;
        sim.bot.wholeStackWithdrawals = true;
        sim.inv.offhand.put(TOTEM, 1);
        sim.inv.wear(new SimFakes.FakeInventory.FakeArmor(ElytraKit.ELYTRA, 400, true));
        sim.inv.carry(new SimFakes.FakeInventory.FakeArmor("minecraft:diamond_chestplate", 500, true));
        sim.inv.add(UNKNOWN, 29 * 64); // with the chestplate 30 slots: crowded as in the test below
        Job job = sim.addJob("j0206", "Swapped", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 13));
        sim.start();
        assertDone(sim, job, log);

        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert").stream()
            .filter(m -> m.title().equals("Few free slots")).toList();
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().contains("29 slots hold items the bot won't touch: nautilus shell ×1856."),
            alerts.getFirst().body());
        assertFalse(alerts.getFirst().body().contains("chestplate"), alerts.getFirst().body());
        assertEquals(1, sim.inv.count("minecraft:diamond_chestplate"), "never deposited or dropped");
    }

    @Test
    void theFlightsKitIsNotAnUntouchableItemWithTheFlightsSwitchedOff() {
        // live 2026-10-25 08:50: elytra off after a player's flight left the elytra worn: "2 slots hold items the bot
        // won't touch: minecraft:diamond_chestplate, minecraft:firework_rocket ×26"
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.elytraLegs = false;
        sim.bot.wholeStackWithdrawals = true;
        sim.inv.offhand.put(TOTEM, 1);
        sim.inv.wear(new SimFakes.FakeInventory.FakeArmor(ElytraKit.ELYTRA, 400, true));
        sim.inv.carry(new SimFakes.FakeInventory.FakeArmor("minecraft:diamond_chestplate", 500, true));
        sim.inv.add(ElytraKit.ROCKET, 26);
        sim.inv.add(UNKNOWN, 28 * 64); // with the chestplate and the rockets 30 slots: crowded as below
        Job job = sim.addJob("j0208", "Off", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 13));
        sim.start();
        assertDone(sim, job, log);

        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert").stream()
            .filter(m -> m.title().equals("Few free slots")).toList();
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().contains("28 slots hold items the bot won't touch: nautilus shell ×1792."),
            alerts.getFirst().body());
        assertEquals(1, sim.inv.count("minecraft:diamond_chestplate"), "never deposited or dropped");
        assertEquals(26, sim.inv.count(ElytraKit.ROCKET), "never deposited or dropped");
    }

    @Test
    void aCarriedChestplateWithTheChestplateSlotNotAnElytraStillCounts() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.elytraLegs = true;
        sim.bot.wholeStackWithdrawals = true;
        sim.inv.offhand.put(TOTEM, 1);
        sim.inv.carry(new SimFakes.FakeInventory.FakeArmor("minecraft:diamond_chestplate", 500, true)); // a spare, no elytra worn
        sim.inv.add(UNKNOWN, 29 * 64);
        Job job = sim.addJob("j0207", "Spare", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 13));
        sim.start();
        assertDone(sim, job, log);

        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert").stream()
            .filter(m -> m.title().equals("Few free slots")).toList();
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().contains("30 slots hold items the bot won't touch: diamond chestplate,"
            + " nautilus shell ×1856."), alerts.getFirst().body());
    }

    @Test
    void tooFewSlotsLeftAlertsOnceAndTheBotCarriesOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.bot.wholeStackWithdrawals = true;
        sim.inv.offhand.put(TOTEM, 1);
        sim.inv.add(UNKNOWN, 30 * 64); // 30 slots: 4 empty, 3 of them reserved, 1 for carpets; a lane needs 2
        Job job = sim.addJob("j0204", "Crowded", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 13));
        sim.start();
        assertDone(sim, job, log);

        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), "one alert for many restocks: " + sim.notifier);
        assertEquals("Few free slots", alerts.getFirst().title());
        assertTrue(alerts.getFirst().body().contains("30 slots hold items the bot won't touch: nautilus shell ×1920."),
            alerts.getFirst().body());
        assertTrue(count(log, "restock:") >= 13 * 128 / 64, "carried on with one slot per trip" + tail(log));
        assertEquals(13 * 128, sim.bot.placed, "every cell placed");
        assertEquals(30 * 64, sim.inv.count(UNKNOWN), "never discarded");
        assertFalse(sim.bot.containers.get(Sim.RETURN).items.containsKey(UNKNOWN));
        assertFalse(sim.bot.droppedItems.containsKey(UNKNOWN));
        assertEquals(Map.of(TOTEM, 1), sim.inv.offhand);
    }

    @Test
    void aLockedMapTheBotDidNotMakeIsNeverRenamedOrStored() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        // the owner's own locked map (e.g. a map art he carries)
        sim.inv.filledMaps.add(8);
        sim.maps.locked.add(8);
        sim.maps.colors.put(8, new byte[128 * 128]);
        sim.inv.xpPoints = 7 * 30; // level 30: RENAME goes straight to the anvil
        Job job = sim.addJob("j0205", "Mine", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RENAME, LONG), sim.state());
        // reconnect between LOCK and STORE: map ids are new, the bot's map is not held, the owner's map is last in slot order
        sim.engine.onDisconnect();
        sim.maps.held = null;
        sim.inv.filledMaps.remove(Integer.valueOf(8));
        sim.inv.filledMaps.add(8);
        assertDone(sim, job, log);

        assertEquals(1, sim.output().maps.size(), sim.output().maps.toString());
        FakeBot.MapItem stored = sim.output().maps.getFirst();
        assertNotEquals(8, stored.id(), "stored the bot's map");
        assertEquals("Mine", stored.name());
        assertTrue(sim.inv.filledMaps.contains(8), "the owner's map kept");
        assertFalse(sim.inv.mapNames.containsKey(8), "the owner's map not renamed");
    }

    @Test
    void aWornToolThatIsNotAToolChestToolStays() {
        Sim sim = new Sim();
        // the owner's iron pickaxe, worn to the safety floor; the bot's diamond pickaxe (a tool-chest tool) is the spare
        FakeTool iron = sim.inv.addTool("minecraft:iron_pickaxe", 5, false);
        Job job = sim.addJob("j0206", "Mine", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.bot.toolsReturned, "never put into the tool chest");
        assertEquals(0, sim.bot.exchanges, "no trip to the tool chest for it");
        assertTrue(sim.inv.stacksOf("minecraft:iron_pickaxe").contains(iron), "kept");
    }

    @Test
    void withoutASpareTheToolChestToolIsTakenAndPhisWornToolStays() {
        Sim sim = new Sim();
        sim.inv.remove("minecraft:diamond_pickaxe", 1);
        FakeTool iron = sim.inv.addTool("minecraft:iron_pickaxe", 5, false);
        Map<String, Integer> chestBefore = new TreeMap<>(sim.bot.containers.get(Sim.TOOLS).items);
        Job job = sim.addJob("j0207", "Mine", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(chestBefore.get("minecraft:diamond_pickaxe") - 1,
            sim.bot.containers.get(Sim.TOOLS).items.get("minecraft:diamond_pickaxe"), "a diamond pickaxe taken");
        assertEquals(0, sim.bot.toolsReturned, "the worn iron pickaxe is the owner's: kept");
        assertTrue(sim.inv.stacksOf("minecraft:iron_pickaxe").contains(iron));
        assertTrue(sim.bot.containers.get(Sim.TOOLS).wornTools.isEmpty());
    }
}
