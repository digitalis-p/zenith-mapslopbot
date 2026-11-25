package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory.FakeTool;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "The tools should never break, always use XP to mend them": mend at safe points, swap at the floor, else stop. */
class ToolWearTest {
    private static final int LONG = 150_000;
    private static final String PICK = "minecraft:diamond_pickaxe";
    private static final String XP = "minecraft:experience_bottle";
    /** Phases where the engine never mends (as for eating, plus WAIT_CLEAR: soul sand must stay at the centre). */
    private static final Set<Phase> NO_MEND = Set.of(Phase.IDLE, Phase.SELECT, Phase.CLEAR, Phase.WAIT_CLEAR, Phase.BLOCKED);

    /** Adds {@code chests} more terracotta chests (1728 each) to the material region. */
    private static void terracottaStock(Sim sim, int chests) {
        for (int i = 0; i < chests; i++) sim.bot.addContainer(new Pos(-90 + 2 * i, 61, 106), Map.of(Sim.TERRACOTTA, 1728));
    }

    /** Replaces the starting pickaxe with one at {@code uses} uses left. */
    private static FakeTool pickaxe(Sim sim, int uses, boolean mending) {
        sim.inv.items.remove(PICK);
        sim.inv.toolStacks.clear();
        return sim.inv.addTool(PICK, uses, mending);
    }

    private static int xpInChest(Sim sim) {
        return sim.bot.containers.get(Sim.XP).items.getOrDefault(XP, 0);
    }

    @Test
    void longNukeNeverDrivesAToolBelowTheFloor() {
        Sim sim = new Sim();
        terracottaStock(sim, 6);
        sim.bot.containers.get(Sim.XP).items.put(XP, 3000);
        TileArt art = Sim.stripes(0, 80); // 10240 terracotta: ~6.6 pickaxes' worth of breaks
        Job job = sim.addJob("j0200", "Nuke", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        int floor = sim.config.build.toolSafetyFloor;
        assertEquals(10240, sim.bot.brokenWithTool.getOrDefault(Sim.TERRACOTTA, 0), "every terracotta block nuked with the pickaxe");
        assertTrue(sim.bot.toolStops >= 5, "the nuke stopped at the floor several times: " + sim.bot.toolStops);
        assertEquals(floor, sim.bot.lowestToolUses, "used down to the floor, never below");
        assertEquals(0, sim.bot.breaksAtFloor);
        assertTrue(sim.inv.tool(PICK).remaining > floor);
        assertTrue(sim.bot.mends >= 5 && sim.bot.mendBottles > 500, sim.bot.mends + " mends, " + sim.bot.mendBottles + " bottles");
        for (Phase p : sim.bot.mendPhases) assertFalse(NO_MEND.contains(p), "mended in " + p);
        assertTrue(sim.bot.mendPhases.contains(Phase.NUKE), sim.bot.mendPhases.toString());
        assertEquals(0, sim.bot.mendTicksBesideOtherTask, "mending only while no walk/lane/break task runs");
        assertTrue(sim.bot.count("withdraw " + Sim.XP) >= 2, "XP fetched from the XP chest: " + sim.bot.history);
        assertEquals(0, sim.bot.exchanges, "a Mending pickaxe is mended, not swapped");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void longVerifyRepairNeverDrivesAToolBelowTheFloor() {
        Sim sim = new Sim();
        sim.bot.containers.get(Sim.XP).items.put(XP, 2000);
        pickaxe(sim, 800, true); // 51 %: not mended before the repair starts
        TileArt art = Sim.single(Sim.WHITE, 16); // 2048 carpets
        // the first 1200 white carpets come out as terracotta (interference): VERIFY breaks them with the pickaxe
        int[] wrong = {0};
        sim.bot.placeOverride = p -> wrong[0]++ < 1200 ? new Placement(p.pos(), BlockSpec.of(Sim.TERRACOTTA), p.itemId()) : null;
        sim.config.build.maxRepairPasses = 6;
        Job job = sim.addJob("j0201", "Repair", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        int floor = sim.config.build.toolSafetyFloor;
        assertEquals(1200, sim.bot.brokenWithTool.getOrDefault(Sim.TERRACOTTA, 0), sim.bot.brokenWithTool.toString());
        assertTrue(sim.bot.toolStops >= 1, "VERIFY stopped at the floor: " + sim.bot.toolStops);
        assertTrue(sim.bot.lowestToolUses >= floor, "lowest " + sim.bot.lowestToolUses);
        assertEquals(0, sim.bot.breaksAtFloor);
        assertTrue(sim.bot.mendPhases.contains(Phase.VERIFY), sim.bot.mendPhases.toString());
        for (Phase p : sim.bot.mendPhases) assertFalse(NO_MEND.contains(p), "mended in " + p);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void statusNoteSaysMendingWhileATaskMends() {
        Sim sim = new Sim();
        pickaxe(sim, 702, true); // 45 %
        TileArt art = Sim.single(Sim.WHITE, 4);
        sim.addJob("j0209", "MendNote", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.lastTask != null && sim.bot.lastTask.name().startsWith("mendTool")
            && !sim.bot.lastTask.isFinished(), 20_000), sim.state());
        assertTrue(sim.engine.status().note().startsWith(BuildEngine.MENDING_NOTE), sim.state());
        assertTrue(sim.runUntil(() -> sim.bot.lastTask.isFinished(), 20), sim.state());
        sim.tick();
        assertFalse(sim.engine.status().note().startsWith(BuildEngine.MENDING_NOTE), "cleared after the mend: "
            + sim.state());
    }

    @Test
    void mendsAtTheThresholdAtASafePointWithXpFromTheXpChest() {
        Sim sim = new Sim();
        FakeTool pick = pickaxe(sim, 702, true); // 45 %
        int chestBefore = xpInChest(sim);
        TileArt art = Sim.single(Sim.WHITE, 4);
        Job job = sim.addJob("j0202", "Mend", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.mends > 0, 20_000), sim.state());

        List<String> h = sim.bot.history;
        int fetch = h.indexOf(h.stream().filter(s -> s.startsWith("withdraw " + Sim.XP)).findFirst().orElseThrow());
        int mend = h.indexOf(h.stream().filter(s -> s.startsWith("mendTool")).findFirst().orElseThrow());
        assertTrue(fetch < mend, "bottles fetched from the XP chest first: " + h);
        assertTrue(xpInChest(sim) < chestBefore);
        assertFalse(NO_MEND.contains(sim.bot.mendPhases.getFirst()), sim.bot.mendPhases.toString());
        assertTrue(h.stream().noneMatch(s -> s.startsWith("placeLane")) || h.indexOf(h.stream()
            .filter(s -> s.startsWith("placeLane")).findFirst().orElseThrow()) > mend, "mended before building: " + h);

        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(pick.remaining >= 1483, "mended to 95 %: " + pick);
        assertEquals(0, sim.bot.mendTicksBesideOtherTask);
        for (Phase p : sim.bot.mendPhases) assertFalse(NO_MEND.contains(p), "mended in " + p);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void aboveTheThresholdNothingIsMended() {
        Sim sim = new Sim();
        pickaxe(sim, 860, true); // 55 %
        TileArt art = Sim.single(Sim.WHITE, 4);
        Job job = sim.addJob("j0203", "NoMend", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.bot.mends);
        assertEquals(0, sim.bot.count("mendTool"));
    }

    @Test
    void noXpAndNoSpareToolBlocksWithoutAnotherBreak() {
        Sim sim = new Sim();
        sim.bot.containers.get(Sim.XP).items.clear();
        sim.bot.containers.get(Sim.TOOLS).items.clear();
        sim.config.map.rename = false; // no XP for the anvil either: reach the nuke
        pickaxe(sim, 300, true);
        TileArt art = Sim.stripes(0, 4); // 512 terracotta to nuke
        sim.addJob("j0204", "Stuck", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());

        String note = sim.engine.status().note();
        assertTrue(note.contains("diamond_pickaxe at 20/1561"), note);
        assertTrue(note.contains("no XP bottles"), note);
        assertTrue(note.contains("refusing to break more blocks"), note);
        assertEquals(20, sim.inv.tool(PICK).remaining);
        assertEquals(20, sim.bot.lowestToolUses);
        assertEquals(0, sim.bot.breaksAtFloor);
        assertTrue(sim.notifier.has("alert", "Tool at the safety floor"), sim.notifier.toString());
        assertTrue(sim.notifier.has("alert", "Map art blocked"), sim.notifier.toString());

        int broken = sim.bot.broken;
        long breakTasks = sim.bot.count("breakBlocks");
        sim.run(3_000);
        assertEquals(Phase.BLOCKED, sim.phase());
        assertEquals(broken, sim.bot.broken, "no block broken after the stop");
        assertEquals(breakTasks, sim.bot.count("breakBlocks"), "no new break task");
    }

    @Test
    void resumeAfterRestockingXpFinishesTheJob() {
        Sim sim = new Sim();
        sim.bot.containers.get(Sim.XP).items.clear();
        sim.bot.containers.get(Sim.TOOLS).items.clear();
        sim.config.map.rename = false; // no XP for the anvil either: reach the nuke
        pickaxe(sim, 300, true);
        TileArt art = Sim.stripes(0, 4);
        Job job = sim.addJob("j0205", "Restocked", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());

        sim.bot.containers.get(Sim.XP).items.put(XP, 500); // a human refills the XP chest
        sim.engine.resume();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.mends > 0);
        assertTrue(sim.bot.lowestToolUses >= 20);
        assertEquals(0, sim.bot.breaksAtFloor);
    }

    @Test
    void aToolWithoutMendingIsSwappedAtTheFloor() {
        Sim sim = new Sim();
        sim.inv.freshToolsMending = false; // the tool chest's pickaxes have no Mending either
        FakeTool worn = pickaxe(sim, 100, false);
        TileArt art = Sim.stripes(0, 4); // 512 terracotta to nuke
        Job job = sim.addJob("j0206", "Plain", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(0, sim.bot.count("mendTool"), "a tool without Mending is never mended");
        assertEquals(1, sim.bot.toolsReturned, "the worn pickaxe went back");
        List<FakeTool> back = sim.bot.containers.get(Sim.TOOLS).wornTools;
        assertEquals(1, back.size());
        assertTrue(back.getFirst() == worn && worn.remaining == 20, "returned at the floor: " + worn);
        assertEquals(3, sim.bot.containers.get(Sim.TOOLS).items.get(PICK), "one fresh pickaxe taken");
        assertTrue(sim.inv.tool(PICK).remaining > 20);
        assertEquals(20, sim.bot.lowestToolUses);
        assertEquals(0, sim.bot.breaksAtFloor);
        assertEquals(1, sim.notifier.of("alert").stream().filter(m -> m.title().equals("No Mending pickaxe")).count(),
            sim.notifier.toString());
        assertEquals(1, sim.notifier.of("alert").size(), sim.notifier.toString());
    }

    @Test
    void carpetsAreBrokenByHandNotWithATool() {
        Sim sim = new Sim();
        FakeTool pick = pickaxe(sim, 1000, true);
        TileArt art = Sim.single(Sim.WHITE, 8);
        // every 4th white carpet comes out red: VERIFY breaks the wrong carpets
        int[] n = {0};
        sim.bot.placeOverride = p -> n[0]++ % 4 == 0 ? new Placement(p.pos(), BlockSpec.of(Sim.RED), p.itemId()) : null;
        sim.config.build.maxRepairPasses = 6;
        Job job = sim.addJob("j0207", "Carpets", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertTrue(sim.bot.brokenByHand.getOrDefault(Sim.RED, 0) >= 200, sim.bot.brokenByHand.toString());
        assertTrue(sim.bot.brokenWithTool.isEmpty(), "no carpet broken with a tool: " + sim.bot.brokenWithTool);
        assertEquals(1000, pick.remaining, "the pickaxe lost no use");
        assertEquals(0, sim.bot.mends);
    }

    @Test
    void bootsAreStillMendedAtRenameBesideTheTool() {
        Sim sim = new Sim();
        FakeTool pick = pickaxe(sim, 600, true); // 38 %
        TileArt art = Sim.stripes(6, 0);
        Job job = sim.addJob("j0208", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals("minecraft:netherite_boots", sim.inv.boots, "boots equipped");
        assertTrue(sim.inv.bootsDurability >= 0.95, "boots mended at RENAME: " + sim.inv.bootsDurability);
        assertTrue(sim.bot.count("throwXp") > 0, "RENAME's own XP throws (boots, level)");
        assertTrue(pick.remaining >= 1483, "pickaxe mended: " + pick);
        assertEquals("Soul", sim.output().maps.getFirst().name());
        for (Phase p : sim.bot.mendPhases) assertFalse(NO_MEND.contains(p), "mended in " + p);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    /** Worn boots and no XP anywhere: taken off before they break, and not worn again without bottles (live 2026-10-14). */
    @Test
    void wornOutBootsWithNoXpAreTakenOffNotBroken() {
        Sim sim = new Sim();
        sim.inv.boots = "minecraft:netherite_boots";
        sim.inv.bootsDurability = 0.05;
        for (Pos xp : sim.config.site.storage.xpChests) sim.bot.containers.get(xp).items.clear();
        TileArt art = Sim.stripes(6, 0);
        sim.addJob("j0210", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.inv.boots == null, LONG), "boots taken off: " + sim.state());
        assertEquals(1, sim.inv.count("minecraft:netherite_boots"), "kept in the inventory");
        sim.runUntil(() -> false, 2_000);
        // the alert once the XP chests gave nothing (the durability guard tries them first)
        assertTrue(sim.notifier.toString().contains("Boots taken off"), sim.notifier.toString());
        assertEquals(1, sim.notifier.toString().split("Boots taken off", -1).length - 1, "one alert: " + sim.notifier);
        assertEquals(null, sim.inv.boots, "not worn again without XP to mend them");
        assertFalse(sim.bot.armorBroke);
    }

    /** Worn boots with no bottles carried fetch XP from the XP chest and are mended there and then. */
    @Test
    void wornBootsWithoutBottlesFetchXp() {
        Sim sim = new Sim();
        sim.inv.boots = "minecraft:netherite_boots";
        sim.inv.bootsDurability = 0.2;
        TileArt art = Sim.stripes(6, 0);
        Job job = sim.addJob("j0211", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.inv.bootsDurability >= 0.95 || job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals("minecraft:netherite_boots", sim.inv.boots);
        assertTrue(sim.inv.bootsDurability >= 0.95, "mended: " + sim.inv.bootsDurability);
    }

    @Test
    void xpTakenByTheBootsOnlySlowsTheMend() {
        Sim sim = new Sim();
        sim.bot.bootsTakeEveryOtherBottle = true;
        sim.inv.boots = "minecraft:netherite_boots";
        sim.inv.bootsDurability = 0.3;
        FakeTool pick = pickaxe(sim, 300, true);
        TileArt art = Sim.single(Sim.WHITE, 4);
        Job job = sim.addJob("j0209", "Shared", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(pick.remaining >= 1483, "pickaxe still mended to 95 %: " + pick);
        assertTrue(sim.inv.bootsDurability > 0.3, "the boots took some XP");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }
}
