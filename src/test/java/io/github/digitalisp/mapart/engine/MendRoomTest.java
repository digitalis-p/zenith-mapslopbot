package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory.FakeTool;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * A tool due for mending with a full inventory (live gate C, NUKE: "no XP bottles to mend diamond_pickaxe ... (inventory
 * full)"): the leftover materials go first (materials only, as at RESTOCK), then the bottles are fetched.
 */
class MendRoomTest {
    private static final int LONG = 150_000;
    private static final String PICK = "minecraft:diamond_pickaxe";
    private static final String XP = "minecraft:experience_bottle";
    /** In no job, no material chest, no keep list: an item the bot never moves. */
    private static final String UNKNOWN = "minecraft:nautilus_shell";
    private static final int CELLS = 2 * 128;

    /** A Mending pickaxe at 45 % (mended at the first safe point), the 256 white carpets the tile needs. */
    private static Sim sim(RecordingLogger log) {
        return sim(log, 702);
    }

    private static Sim sim(RecordingLogger log, int uses) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.inv.items.remove(PICK);
        sim.inv.toolStacks.clear();
        FakeTool pick = sim.inv.addTool(PICK, uses, true);
        assertTrue(pick.mending);
        sim.inv.add(WHITE, CELLS);
        sim.config.map.rename = false;
        sim.addJob("j0401", "Mend", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
        return sim;
    }

    /** Fills every empty slot with {@code id}. */
    private static void fill(Sim sim, String id) {
        while (sim.inv.freeSlots() > 0) sim.inv.add(id, 64);
    }

    private static int first(List<String> h, String prefix) {
        for (int i = 0; i < h.size(); i++) if (h.get(i).startsWith(prefix)) return i;
        return -1;
    }

    @Test
    void leftoverMaterialsMakeRoomForTheBottles() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        // a material the tile does not need; a material because a queued job lists it (no chest is scanned yet when the
        // first safe point comes, at INSPECT)
        sim.addJob("j0402", "Later", 1, 1, List.of("moss"), (x, z) -> Sim.single(RED, 1));
        fill(sim, RED);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.mends > 0, 20_000), sim.state() + "\n" + String.join("\n", log.lines));

        List<String> h = sim.bot.history;
        int deposit = first(h, "deposit " + Sim.RETURN);
        int fetch = first(h, "withdraw " + Sim.XP);
        int mend = first(h, "mendTool");
        assertTrue(deposit >= 0 && deposit < fetch && fetch < mend, "leftovers out, bottles in, then the mend: " + h);
        assertEquals(Set.of(RED), sim.bot.containers.get(Sim.RETURN).items.keySet(), "only the unneeded material went");
        assertEquals(CELLS, sim.inv.count(WHITE), "the tile's carpets stay");
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.startsWith("no XP bottles to mend")),
            String.join("\n", log.lines));
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("no room for XP bottles to mend")),
            String.join("\n", log.lines));
    }

    /**
     * The gate's case: NUKE after STORE, the inventory full of the stored tile's own material (picked up), the pickaxe
     * worn below the mend threshold. The tile needs nothing any more: its carpets go, then the bottles come.
     */
    @Test
    void inTheNukeTheStoredTilesMaterialMakesRoom() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, 1500);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), sim.state());
        assertEquals(0, sim.bot.mends);
        sim.inv.tool(PICK).remaining = 702;
        fill(sim, WHITE);
        int historyAt = sim.bot.history.size();
        assertTrue(sim.runUntil(() -> sim.bot.mends > 0, 20_000), sim.state() + "\n" + String.join("\n", log.lines));

        List<String> h = sim.bot.history.subList(historyAt, sim.bot.history.size());
        int deposit = first(h, "deposit " + Sim.RETURN);
        int fetch = first(h, "withdraw " + Sim.XP);
        int mend = first(h, "mendTool");
        assertTrue(deposit >= 0 && deposit < fetch && fetch < mend, h.toString());
        assertEquals(List.of(Phase.NUKE), sim.bot.mendPhases);
        assertEquals(Set.of(WHITE), sim.bot.containers.get(Sim.RETURN).items.keySet());
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.startsWith("no XP bottles to mend")),
            String.join("\n", log.lines));
    }

    @Test
    void itemsThatAreNoMaterialsNeverMakeRoom() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        fill(sim, UNKNOWN);
        int unknown = sim.inv.count(UNKNOWN);
        sim.start();
        assertTrue(sim.runUntil(() -> log.at(Level.WARN).stream().anyMatch(l -> l.startsWith("no XP bottles to mend")),
            20_000), sim.state());

        String warn = log.at(Level.WARN).stream().filter(l -> l.startsWith("no XP bottles to mend")).findFirst()
            .orElseThrow();
        assertTrue(warn.contains("inventory full"), warn);
        assertEquals(unknown, sim.inv.count(UNKNOWN), "never deposited or dropped");
        assertFalse(sim.bot.containers.get(Sim.RETURN).items.containsKey(UNKNOWN));
        assertTrue(sim.bot.droppedItems.isEmpty());
        assertEquals(CELLS, sim.inv.count(WHITE));
    }
}
