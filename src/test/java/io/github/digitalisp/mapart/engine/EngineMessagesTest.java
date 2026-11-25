package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.ContainerSnapshot;
import io.github.digitalisp.mapart.bot.TransferResult;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate B wording: "the XP chests are empty" while the XP withdraw had failed at a full chest (F4), and
 * "build round: 3 lanes run, 0 without items" while a lane had skipped all its cells for lack of items (F6).
 * Synthetic coordinates.
 */
class EngineMessagesTest {
    private static final int LONG = 60_000;
    private static final String PICK = "minecraft:diamond_pickaxe";
    private static final String XP = "minecraft:experience_bottle";

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 40), l.size()));
    }

    // ---- no build progress with the items in hand: the bot cannot get on (live 2026-10-19 13:09) ----

    @Test
    void noProgressWithTheItemsInHandSaysTheBotCannotGetOnNotMaterials() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.config.build.reservedSlots = 34; // 2 slots for materials: one load covers a lane, the tile needs many loads
        sim.newEngine(new io.github.digitalisp.mapart.plan.SerpentineBuildPlanner(), new GreedyRestockPlanner(), log);
        sim.bot.lanesStandStill = true; // every lane ends by its watchdog: nothing placed, nothing skipped
        sim.addJob("j0312", "Wedged", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 13));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state() + tail(log));
        String note = sim.engine.status().note();
        assertTrue(note.startsWith("no build progress in "), note);
        assertTrue(note.endsWith("restock rounds; the lanes ran with their items and placed nothing: the bot cannot get"
            + " on (see the lane lines)"), note);
        assertFalse(note.contains("materials cannot be obtained"), note);
    }

    // ---- F4: the real cause when no XP came ----

    @Test
    void renameBlockedByAFailedXpWithdrawSaysSoNotEmpty() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        // every XP withdraw fails (live: "input not held still" at a full chest); the chest block is still there
        sim.bot.containers.remove(Sim.XP);
        sim.addJob("j0310", "Xp", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state() + tail(log));

        String note = sim.engine.status().note();
        assertTrue(note.startsWith("no XP for renaming: withdraw from XP chest " + Sim.XP.key() + " failed ("), note);
        assertTrue(note.contains("no container at"), note);
        assertFalse(note.contains("empty"), note);
        assertEquals(Phase.RENAME.name(), sim.store.checkpoint.blockedFrom);
    }

    @Test
    void renameBlockedByAnEmptyXpChestSaysEmpty() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.bot.containers.get(Sim.XP).items.clear();
        sim.addJob("j0311", "Xp", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state() + tail(log));
        assertEquals("no XP for renaming: the XP chest is empty", sim.engine.status().note());
    }

    @Test
    void mendingWarningGivesTheFailedWithdrawNotEmptyChests() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.inv.items.remove(PICK);
        sim.inv.toolStacks.clear();
        sim.inv.addTool(PICK, 702, true); // 45 %: mended at the first safe point
        sim.config.map.rename = false;
        sim.addJob("j0312", "Mend", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.bot.containers.remove(Sim.XP); // every XP withdraw fails; the chest block is still there
        sim.start();
        assertTrue(sim.runUntil(() -> log.at(Level.WARN).stream().anyMatch(l -> l.startsWith("no XP bottles to mend")),
            LONG), sim.state() + tail(log));

        String warn = log.at(Level.WARN).stream().filter(l -> l.startsWith("no XP bottles to mend")).findFirst()
            .orElseThrow();
        assertTrue(warn.contains("withdraw from XP chest " + Sim.XP.key() + " failed ("), warn);
        assertFalse(warn.contains("empty"), warn);
    }

    @Test
    void xpFetchLogTellsEmptyFromFailedFromFull() {
        Pos a = new Pos(1, 2, 3);
        Pos b = new Pos(4, 5, 6);
        BuildEngine.XpFetchLog x = new BuildEngine.XpFetchLog();
        assertEquals("no XP chest is configured (site.storage.xpChests)", x.cause(List.of()));
        x.none(a, new TransferResult(Map.of(), new ContainerSnapshot(a, "chest", Map.of(), 27, 27), "container empty"));
        assertEquals("the XP chest is empty", x.cause(List.of(a)));
        x.none(b, new TransferResult(Map.of(), new ContainerSnapshot(b, "chest", Map.of(XP, 640), 17, 27),
            "minecraft:experience_bottle short by 32 (inventory full)"));
        assertEquals("XP chest " + a.key() + " is empty; XP chest " + b.key() + " gave no bottles"
            + " (minecraft:experience_bottle short by 32 (inventory full)) although it holds 640", x.cause(List.of(a, b)));
        x.clear();
        x.failed(a, "withdraw " + a + ": input not held still");
        assertEquals("withdraw from XP chest " + a.key() + " failed (withdraw " + a + ": input not held still)",
            x.cause(List.of(a)));
    }

    // ---- F6: lanes short of items are counted ----

    @Test
    void buildRoundCountsALaneThatRanShortOfItems() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.placeMode = "LOOK"; // the counts below are 5-wide lanes
        Job job = sim.addJob("j0313", "Short", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        AtomicBoolean taken = new AtomicBoolean();
        assertTrue(sim.runUntil(() -> {
            // the first lane starts with 3 white carpets: it places 3 and skips the other 7 cells for lack of items
            if (sim.bot.count("placeLane") == 1 && taken.compareAndSet(false, true)) {
                sim.inv.remove(Sim.WHITE, sim.inv.count(Sim.WHITE) - 3);
            }
            return log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("build round:"));
        }, LONG), sim.state() + tail(log));

        String round = log.at(Level.INFO).stream().filter(l -> l.startsWith("build round:")).findFirst().orElseThrow();
        assertTrue(round.startsWith("build round: 1 lanes run (1 short of items: 7 cells skipped for a missing item), "),
            round);
        assertTrue(round.contains(" 3 placed,"), round);
        // the other lanes had no white carpet at all: not run, counted apart
        assertTrue(round.contains(", 25 not run for lack of items, "), round);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
    }

    @Test
    void buildRoundWithEveryItemCountsNoShortLane() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0314", "Full", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        List<String> rounds = log.at(Level.INFO).stream().filter(l -> l.startsWith("build round:")).toList();
        assertFalse(rounds.isEmpty());
        for (String r : rounds) assertFalse(r.contains("short of items"), r);
    }
}
