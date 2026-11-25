package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mending in the nuke: a whole map of full blocks (9,879, like ellen) wears a netherite pickaxe
 * (2,031 uses, Unbreaking III: a use every 4 breaks) by about 2,470 uses, so it is mended inside the NUKE, while the
 * blocks it breaks are picked up and fill the inventory. The fake sweep has a lane end every 600 blocks.
 */
class NukeMendTest {
    private static final int LONG = 200_000;
    private static final int BLOCKS = 9_879;
    private static final String PICK = "minecraft:netherite_pickaxe";
    private static final String XP = "minecraft:experience_bottle";
    /** In no job, no material chest, no keep list: the owner's, never moved. */
    private static final String UNKNOWN = "minecraft:nautilus_shell";
    private static final String TOTEM = "minecraft:totem_of_undying";
    /** 50 % of 2,031: the pickaxe is due for mending at or below this. */
    private static final int DUE = 1015;

    /** 9,879 terracotta cells, row by row from the north-west corner; the rest of the tile empty. */
    private static TileArt ellen() {
        TileArt a = new TileArt();
        for (int i = 0; i < BLOCKS; i++) a.set(i % 128, i / 128, BlockSpec.of(Sim.TERRACOTTA));
        return a;
    }

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        for (int i = 0; i < 6; i++) sim.bot.addContainer(new Pos(-90 + 2 * i, 61, 106), Map.of(Sim.TERRACOTTA, 1728));
        sim.bot.containers.get(Sim.XP).items.put(XP, 3000);
        sim.bot.containers.get(Sim.RETURN).slots = 540;
        sim.inv.items.remove("minecraft:diamond_pickaxe");
        sim.inv.toolStacks.clear();
        sim.inv.addTool(PICK, 1929, true); // 95 %: nothing to mend before the nuke
        sim.inv.add(UNKNOWN, 1);
        sim.inv.add(TOTEM, 1);
        sim.bot.breaksPerUse = 4;
        sim.bot.laneBlocks = 600;
        // the lane-end mends as such: with mending during the clear a due mend may wait for WAIT_CLEAR
        // (MendDuringClearTest covers that)
        sim.config.build.mendDuringClear = false;
        return sim;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    private static long xpFetchesIn(Sim sim, Phase phase) {
        return sim.bot.withdrawLog.stream().filter(e -> e.getKey().equals(Sim.XP) && e.getValue() == phase).count();
    }

    /** Never a block broken at the floor, the owner's items still there, only the nuked material out of the inventory. */
    private static void assertOnlyMaterialsLeft(Sim sim) {
        assertEquals(1, sim.inv.count(UNKNOWN), "the owner's item stays");
        assertEquals(1, sim.inv.count(TOTEM), "the totem stays");
        assertTrue(sim.inv.count(PICK) == 1, "the pickaxe stays");
        assertEquals(Set.of(), Set.copyOf(sim.bot.containers.get(Sim.RETURN).items.keySet()).stream()
            .filter(id -> !id.equals(Sim.TERRACOTTA)).collect(java.util.stream.Collectors.toSet()),
            "only the nuked material was returned: " + sim.bot.containers.get(Sim.RETURN).items);
        assertTrue(sim.bot.droppedItems.keySet().stream().allMatch(Sim.TERRACOTTA::equals), sim.bot.droppedItems.toString());
        assertEquals(0, sim.bot.breaksAtFloor);
    }

    @Test
    void theXpTakenAtStoreMendsAtLaneEndsWhileDropsFillTheInventory() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.bot.pickupEvery = 3; // a third of the drops: 3,293 items, more than the free slots hold
        Job job = sim.addJob("j0501", "Ellen", 1, 1, List.of("moss"), (x, z) -> ellen());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + "\n" + tail(log));

        System.out.println("nuke mend: paused at " + sim.bot.pausedAtUses + " uses, " + sim.bot.mendBottles
            + " bottles thrown, mends in " + sim.bot.mendPhases + ", lowest " + sim.bot.lowestToolUses + ", ends at "
            + sim.inv.tool(PICK) + ", pickups " + sim.bot.pickedUp + ", refused " + sim.bot.pickupsRefusedIn
            + ", XP withdrawals " + sim.bot.withdrawLog.stream().filter(e -> e.getKey().equals(Sim.XP)).toList());
        log.at(Level.INFO).stream().filter(l -> l.contains("XP for the nuke") || l.startsWith("breaking paused")
            || l.startsWith("mending ")).forEach(l -> System.out.println("  " + l));
        assertEquals(BLOCKS, sim.bot.brokenWithTool.getOrDefault(Sim.TERRACOTTA, 0), sim.bot.brokenWithTool.toString());
        assertTrue(sim.bot.pickupsRefusedIn.getOrDefault(Phase.NUKE, 0) > 0, "the drops filled the inventory in the NUKE");
        assertEquals(0, sim.bot.toolStops, "never stopped at the floor: " + sim.bot.pausedAtUses);
        assertTrue(sim.bot.pausedAtUses.size() >= 2, "two planned mends at lane ends: " + sim.bot.pausedAtUses);
        for (int u : sim.bot.pausedAtUses) {
            assertTrue(u <= DUE && u > DUE - 160, "paused at the first lane end once due (600 blocks = 150 uses): " + u);
        }
        assertTrue(sim.bot.mendPhases.stream().filter(p -> p == Phase.NUKE).count() >= 2, sim.bot.mendPhases.toString());
        assertTrue(sim.bot.lowestToolUses > DUE - 160, "lowest " + sim.bot.lowestToolUses);
        assertEquals(0, xpFetchesIn(sim, Phase.NUKE), "the bottles came from STORE, no walk in the NUKE: "
            + sim.bot.withdrawLog);
        assertTrue(xpFetchesIn(sim, Phase.STORE) >= 1, sim.bot.withdrawLog.toString());
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("XP for the nuke: 9879 blocks")), tail(log));
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("breaking paused")), tail(log));
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.contains("stay in the inventory")), tail(log));
        assertTrue(sim.inv.tool(PICK).remaining > DUE, "left the nuke above the mend threshold: " + sim.inv.tool(PICK));
        assertOnlyMaterialsLeft(sim);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    /**
     * No XP carried (the XP chest was empty at STORE): at each lane-end pause the inventory is full of drops, so the
     * picked-up material goes to the return chest first, then the bottles come; twice in one NUKE (a return chest that
     * took something is tried again).
     */
    @Test
    void withoutCarriedXpEachLaneEndFetchMakesRoomFirst() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.map.rename = false; // the anvil would want the XP the chest does not have
        sim.bot.pickupEvery = 1;
        int stocked = sim.bot.containers.get(Sim.XP).items.remove(XP);
        Job job = sim.addJob("j0502", "Ellen", 1, 1, List.of("moss"), (x, z) -> ellen());
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), sim.state() + "\n" + tail(log));
        assertEquals(0, sim.inv.count(XP));
        assertTrue(log.at(Level.WARN).stream().anyMatch(l -> l.contains("XP bottles for the nuke carried")), tail(log));
        sim.bot.containers.get(Sim.XP).items.put(XP, stocked); // a human refills the XP chest
        int historyAt = sim.bot.history.size();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + "\n" + tail(log));

        assertEquals(0, sim.bot.toolStops, "never stopped at the floor: " + sim.bot.pausedAtUses);
        assertTrue(sim.bot.pausedAtUses.size() >= 2, sim.bot.pausedAtUses.toString());
        assertTrue(xpFetchesIn(sim, Phase.NUKE) >= 2, "fetched at the lane ends: " + sim.bot.withdrawLog);
        List<String> h = sim.bot.history.subList(historyAt, sim.bot.history.size());
        int fetches = 0;
        for (int i = 0; i < h.size(); i++) {
            if (!h.get(i).startsWith("withdraw " + Sim.XP)) continue;
            fetches++;
            boolean roomFirst = false;
            for (int j = i - 1; j >= 0 && !h.get(j).startsWith("breakBlocks"); j--) {
                if (h.get(j).startsWith("deposit " + Sim.RETURN)) roomFirst = true;
            }
            assertTrue(roomFirst, "fetch " + fetches + ": the drops went to the return chest first: " + h);
        }
        System.out.println("no XP carried: paused at " + sim.bot.pausedAtUses + ", " + fetches + " fetches, returned "
            + sim.bot.containers.get(Sim.RETURN).items + ", lowest " + sim.bot.lowestToolUses);
        log.at(Level.INFO).stream().filter(l -> l.startsWith("no room for") || l.startsWith("depositing")
            || l.startsWith("fetching ")).forEach(l -> System.out.println("  " + l));
        assertTrue(fetches >= 2, h.toString());
        assertTrue(sim.bot.mendPhases.stream().filter(p -> p == Phase.NUKE).count() >= 2, sim.bot.mendPhases.toString());
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.startsWith("no XP bottles to mend")), tail(log));
        assertTrue(sim.bot.containers.get(Sim.RETURN).items.getOrDefault(Sim.TERRACOTTA, 0) > 64 * 30,
            "whole inventories of drops went back: " + sim.bot.containers.get(Sim.RETURN).items);
        assertOnlyMaterialsLeft(sim);
    }

    /** No bottles carried into the NUKE: the XP chest empty at STORE, refilled once the NUKE runs. */
    private static Sim noCarriedXp(RecordingLogger log, String jobId) {
        Sim sim = sim(log);
        sim.config.map.rename = false;
        int stocked = sim.bot.containers.get(Sim.XP).items.remove(XP);
        sim.addJob(jobId, "Ellen", 1, 1, List.of("moss"), (x, z) -> ellen());
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), sim.state() + "\n" + tail(log));
        sim.bot.containers.get(Sim.XP).items.put(XP, stocked);
        return sim;
    }

    /** Index of the first XP withdrawal in the NUKE within {@code h}, and whether a deposit came before it. */
    private static int firstXpFetch(List<String> h) {
        for (int i = 0; i < h.size(); i++) if (h.get(i).startsWith("withdraw " + Sim.XP)) return i;
        return -1;
    }

    /**
     * Local 10x run: the lane end had one free slot, more than the 51 bottles needed, but the walk to the XP chest went
     * over the drops and filled it ("inventory full"). In the NUKE a fetch keeps {@code NUKE_PICKUP_SLOTS} more free:
     * here 2 free slots at the pause, 2 filled on the way: the drops go first.
     */
    @Test
    void aFetchInTheNukeKeepsRoomForTheDropsPickedUpOnTheWay() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = noCarriedXp(log, "j0504");
        sim.bot.onPause = () -> {
            while (sim.inv.freeSlots() > 2) sim.inv.add(Sim.TERRACOTTA, 64);
        };
        sim.bot.pickupsBeforeWithdraw.add(2);
        int historyAt = sim.bot.history.size();
        assertTrue(sim.runUntil(() -> sim.bot.mends > 0, LONG), sim.state() + "\n" + tail(log));

        List<String> h = sim.bot.history.subList(historyAt, sim.bot.history.size());
        int fetch = firstXpFetch(h);
        assertTrue(fetch > 0 && h.subList(0, fetch).stream().anyMatch(l -> l.startsWith("deposit " + Sim.RETURN)),
            "the drops went before the first fetch: " + h);
        assertTrue(log.at(Level.INFO).stream().noneMatch(l -> l.contains("filled up on the way")), tail(log));
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("no room for XP bottles to mend")), tail(log));
        assertEquals(0, sim.bot.toolStops);
        assertOnlyMaterialsLeft(sim);
    }

    /** More drops on the way than any margin: the fetch arrives full, makes room and tries once more. */
    @Test
    void aFetchThatArrivesFullMakesRoomAndTriesOnceMore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = noCarriedXp(log, "j0505");
        sim.bot.pickupsBeforeWithdraw.add(36);
        int historyAt = sim.bot.history.size();
        assertTrue(sim.runUntil(() -> sim.bot.mends > 0, LONG), sim.state() + "\n" + tail(log));

        List<String> h = sim.bot.history.subList(historyAt, sim.bot.history.size());
        int first = firstXpFetch(h);
        assertTrue(first >= 0, h.toString());
        List<String> rest = h.subList(first + 1, h.size());
        int deposit = -1;
        for (int i = 0; i < rest.size(); i++) if (rest.get(i).startsWith("deposit " + Sim.RETURN)) { deposit = i; break; }
        int second = firstXpFetch(rest);
        assertTrue(deposit >= 0 && second > deposit, "full at the chest, room, then the fetch again: " + h);
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.contains("filled up on the way")), tail(log));
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.startsWith("no XP bottles to mend")), tail(log));
        assertEquals(0, sim.bot.toolStops);
        assertOnlyMaterialsLeft(sim);
    }

    /**
     * Local 10x run: a foreign art (INSPECT found it before any chest was scanned) of gray terracotta, a block no job lists
     * and no chest holds. Its drops are materials all the same (the bot broke them itself): they make room for the
     * bottles; the owner's items still never do.
     */
    @Test
    void theDropsOfAForeignNukeMakeRoomToo() {
        String gray = "minecraft:gray_terracotta";
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.map.rename = false;
        sim.bot.pickupEvery = 1;
        sim.bot.walkDrop = gray;
        for (int i = 0; i < BLOCKS; i++) sim.world.set(sim.moss.geometry().cell(i % 128, i / 128), BlockSpec.of(gray));
        sim.addJob("j0506", "Fresh", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), sim.state() + "\n" + tail(log));
        int historyAt = sim.bot.history.size();
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.NUKE, LONG), sim.state() + "\n" + tail(log));

        assertEquals(BLOCKS, sim.bot.brokenWithTool.getOrDefault(gray, 0), sim.bot.brokenWithTool.toString());
        assertEquals(0, sim.bot.toolStops, "never stopped at the floor: " + sim.bot.pausedAtUses);
        assertTrue(sim.bot.pausedAtUses.size() >= 2, sim.bot.pausedAtUses.toString());
        List<String> h = sim.bot.history.subList(historyAt, sim.bot.history.size());
        int fetch = firstXpFetch(h);
        assertTrue(fetch > 0 && h.subList(0, fetch).stream().anyMatch(l -> l.startsWith("deposit " + Sim.RETURN)),
            "the foreign drops went before the fetch: " + h);
        assertTrue(sim.bot.containers.get(Sim.RETURN).items.getOrDefault(gray, 0) > 64 * 20,
            sim.bot.containers.get(Sim.RETURN).items.toString());
        assertTrue(sim.bot.mendPhases.stream().filter(p -> p == Phase.NUKE).count() >= 2, sim.bot.mendPhases.toString());
        assertTrue(log.at(Level.WARN).stream().noneMatch(l -> l.startsWith("no XP bottles to mend")), tail(log));
        assertEquals(1, sim.inv.count(UNKNOWN), "the owner's item stays");
        assertEquals(1, sim.inv.count(TOTEM), "the totem stays");
        assertEquals(0, sim.bot.breaksAtFloor);
    }

    @Test
    void withNukeMendOffItMendsAtTheFloorAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.nukeMend = false;
        sim.bot.pickupEvery = 3;
        Job job = sim.addJob("j0503", "Ellen", 1, 1, List.of("moss"), (x, z) -> ellen());
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + "\n" + tail(log));

        assertEquals(0, sim.bot.laneEndsAsked, "no pause question without nukeMend");
        assertTrue(sim.bot.toolStops >= 1, "stopped at the floor");
        assertEquals(sim.config.build.toolSafetyFloor, sim.bot.lowestToolUses);
        assertTrue(xpFetchesIn(sim, Phase.NUKE) >= 1, sim.bot.withdrawLog.toString());
        assertFalse(log.at(Level.INFO).stream().anyMatch(l -> l.contains("XP for the nuke")), tail(log));
        assertOnlyMaterialsLeft(sim);
    }
}
