package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.LIME;

/**
 * A started tile waiting for materials goes as soon as there is something worth building. Live 2026-10-13, ellen [3,4],
 * waiting for cyan terracotta: the owner took control, put cyan into the bot's
 * inventory ("theres cyan in inventory now make it go") and handed back at 05:21; the tile went on only at the next
 * 10-minute look (05:28), and 768 cyan in the chests had been "not worth a trip until a load's worth" since 05:16.
 *
 * <ul>
 *   <li>Carried items never wait ({@code build.partialBuildCarried}): the cells the inventory covers are built at once,
 *       with no trip to the chests.</li>
 *   <li>Bot ticks starting again (a player handing control back) or a change in the inventory's count of a short item
 *       look again at once ({@code build.stockWaitWakeOnChange}).</li>
 *   <li>A stack in the chests is worth a trip ({@code build.partialTripMinCells}, 64; 0 = a full load or all of an
 *       item, as before).</li>
 * </ul>
 */
class StockWaitGoTest {
    private static final int LONG = 80_000;
    /** Sim ticks per minute of clock (50 ms per tick). */
    private static final int MINUTE = 1_200;
    /** The sorter's lime chest in the material region, empty unless a test fills it. */
    private static final Pos LIME_CHEST = new Pos(-90, 61, 104);
    private static final Set<Phase> MAP_PHASES = EnumSet.of(Phase.FILL_MAP, Phase.CHECK_MAP, Phase.LOCK, Phase.RENAME,
        Phase.STORE);

    /** A sim whose timed look is far away (30 min): anything sooner is a wake. */
    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.waitingRecheckMinutes = 30;
        sim.bot.addContainer(LIME_CHEST, Map.of());
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static boolean waiting(Sim sim) {
        String n = sim.engine.status().note();
        return sim.phase() == Phase.STOCK_CHECK && n != null && n.startsWith(BuildEngine.MATERIALS_NOTE);
    }

    /** Lime blocks placed on moss for the {@link PartialBuildTest#mixed} art of {@code rows} rows. */
    private static int limePlaced(Sim sim, int rows) {
        int n = 0;
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) {
                BlockSpec b = sim.world.block(new Pos(sim.moss.minX + px, sim.moss.artY, sim.moss.minZ + pz));
                if (!b.isAir() && b.id().equals(LIME)) n++;
            }
        return n;
    }

    /** Waits until the mixed tile (10 rows, 320 lime, no lime at the base) waits on moss for its lime. */
    private static Job startAndWait(Sim sim, String id) {
        TileArt art = PartialBuildTest.mixed(10);
        Job job = sim.addJob(id, "Go", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        assertEquals("waiting for materials: 320 lime carpet", sim.engine.status().note());
        return job;
    }

    private static boolean logged(RecordingLogger log, String part) {
        return log.lines.stream().anyMatch(l -> l.contains(part));
    }

    private static long loggedCount(RecordingLogger log, String part) {
        return log.lines.stream().filter(l -> l.contains(part)).count();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void limeHandedIntoTheInventoryIsPlacedAtOnceWithNoTrip() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = startAndWait(sim, "j0960");
        int historyAt = sim.bot.history.size();
        long givenAt = sim.clock.millis();
        sim.bot.inv.items.merge(LIME, 100, Integer::sum); // someone hands the bot 100 lime (no chest holds any)
        assertTrue(sim.runUntil(() -> limePlaced(sim, 10) == 100, 2 * MINUTE), sim.state());
        long took = sim.clock.millis() - givenAt;
        List<String> between = sim.bot.history.subList(historyAt, sim.bot.history.size());
        assertTrue(between.stream().noneMatch(h -> h.startsWith("withdraw ") || h.startsWith("scan ")),
            "no trip and no look at the chests before the carried lime went up: " + between);
        assertTrue(took < 60_000L, "placed within a minute of the hand-over, not at the 30-min look: " + took + " ms");
        assertTrue(logged(log, "the inventory changed (lime carpet 0 -> 100): looking again at once"), log.lines.toString());
        assertTrue(logged(log, "building the 100 cells the inventory covers (100 lime carpet) at once, no trip"),
            log.lines.toString());
        // then the wait for the rest, as before
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        assertEquals("waiting for materials: 220 lime carpet", sim.engine.status().note());
        assertEquals(JobStatus.WAITING_MATERIALS, job.status);
        assertTrue(sim.output().maps.isEmpty(), "no map while lime is missing");
        assertEquals(0, sim.bot.inv.items.getOrDefault(LIME, 0), "every carried lime placed");
        assertTrue(sim.bot.placeCounts.values().stream().allMatch(n -> n == 1), "no cell placed twice");
    }

    @Test
    void aSlotUpdateStillOnItsWayAfterTheRoundStartsNoRoundOfItsOwn() {
        // local 10x run: right after all 200 carried lime were placed the inventory still showed 1, which started a
        // round of its own (no lane run); STOCK_CHECK now waits a second after a carried round before it decides
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        startAndWait(sim, "j0967");
        int[] lime = {0};
        sim.bot.placeOverride = p -> {
            if (p.itemId().equals(LIME) && ++lime[0] == 100) {
                // the last carried lime: its slot update comes 6 ticks late, the inventory shows 1 meanwhile
                sim.bot.inv.items.merge(LIME, 1, Integer::sum);
                sim.bot.schedule(6, () -> sim.bot.inv.items.remove(LIME));
            }
            return null;
        };
        sim.bot.inv.items.merge(LIME, 100, Integer::sum);
        assertTrue(sim.runUntil(() -> lime[0] >= 100 && waiting(sim), LONG), sim.state());
        assertEquals(100, limePlaced(sim, 10));
        assertEquals(1, loggedCount(log, "cells the inventory covers"), "one carried round: " + log.lines);
        assertEquals("waiting for materials: 220 lime carpet", sim.engine.status().note());
    }

    @Test
    void handingControlBackWithLimeInTheInventoryBuildsItAtOnce() {
        // the live case: a player takes control, puts the missing item into the inventory and hands back
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        startAndWait(sim, "j0961");
        sim.engine.onBotTickStopped(); // the player logs in
        sim.bot.inv.items.merge(LIME, 320, Integer::sum); // all the lime the tile needs
        sim.clock.advance(3 * 60_000L);
        sim.engine.onBotTickStarting(); // the player leaves
        long back = sim.clock.millis();
        int gears = sim.store.entries("GEAR");
        long[] gearAt = {-1};
        assertTrue(sim.runUntil(() -> {
            if (gearAt[0] < 0 && sim.store.entries("GEAR") > gears) gearAt[0] = sim.clock.millis();
            return sim.output().maps.size() == 1;
        }, LONG), sim.state());
        assertTrue(gearAt[0] >= back && gearAt[0] - back < 60_000L, "on its way within a minute of the hand-back: "
            + (gearAt[0] - back) + " ms");
        assertTrue(logged(log, "bot ticks started again (a player handed control back, or a reconnect): looking again at"
            + " once"), log.lines.toString());
        assertTrue(sim.bot.history.stream().noneMatch(h -> h.startsWith("withdraw " + LIME_CHEST)),
            "the lime came from the inventory: " + sim.bot.history);
    }

    @Test
    void handingControlBackAfterRestockingTheChestsLooksAgainAtOnce() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = startAndWait(sim, "j0962");
        int gears = sim.store.entries("GEAR");
        sim.engine.onBotTickStopped();
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 1728); // the player fills the lime chest meanwhile
        sim.clock.advance(2 * 60_000L);
        sim.engine.onBotTickStarting();
        long back = sim.clock.millis();
        assertTrue(sim.runUntil(() -> sim.store.entries("GEAR") > gears, 2 * MINUTE), "no look within 2 min of the"
            + " hand-back: " + sim.state());
        assertTrue(sim.clock.millis() - back < 60_000L, (sim.clock.millis() - back) + " ms");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void withTheWakeSwitchOffOnlyTheTimedLookFindsTheLime() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.stockWaitWakeOnChange = false;
        sim.config.build.waitingRecheckMinutes = 10;
        startAndWait(sim, "j0963");
        int gears = sim.store.entries("GEAR");
        sim.engine.onBotTickStopped();
        sim.bot.inv.items.merge(LIME, 100, Integer::sum);
        sim.clock.advance(60_000L);
        sim.engine.onBotTickStarting();
        sim.run(5 * MINUTE);
        assertTrue(waiting(sim), sim.state());
        assertEquals(gears, sim.store.entries("GEAR"), "nothing before the timed look: " + sim.store.phaseLog);
        assertEquals(0, limePlaced(sim, 10));
        assertTrue(!logged(log, "looking again at once"), log.lines.toString());
        // the timed look (10 min after the wait began) builds the carried lime
        assertTrue(sim.runUntil(() -> limePlaced(sim, 10) == 100, 8 * MINUTE), sim.state());
    }

    @Test
    void withCarriedItemsOffTheyCountTowardsATripAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.partialBuildCarried = false;
        sim.config.build.partialTripMinCells = 0; // the rule before 2026-10-13 in full
        startAndWait(sim, "j0964");
        int gears = sim.store.entries("GEAR");
        sim.bot.inv.items.merge(LIME, 100, Integer::sum); // 100 of 320 carried: below a full load
        sim.run(5 * MINUTE);
        assertTrue(waiting(sim), sim.state());
        assertEquals(gears, sim.store.entries("GEAR"), "not worth a trip: " + sim.store.phaseLog);
        assertEquals(0, limePlaced(sim, 10));
        assertTrue(logged(log, "the inventory changed (lime carpet 0 -> 100): looking again at once"), log.lines.toString());
        assertTrue(logged(log, "100 blocks in stock: not worth a trip until a load's worth or all of an item is there"),
            log.lines.toString());
    }

    @Test
    void aStackInTheChestsIsWorthATripAtTheNextLook() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.waitingRecheckMinutes = 2;
        startAndWait(sim, "j0965");
        int gears = sim.store.entries("GEAR");
        sim.bot.containers.get(LIME_CHEST).items.put(LIME, 100); // 100 of the 320 lime: more than a stack
        assertTrue(sim.runUntil(() -> limePlaced(sim, 10) == 100, 4 * MINUTE), sim.state());
        assertEquals(gears + 1, sim.store.entries("GEAR"), "one trip: " + sim.store.phaseLog);
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state());
        assertEquals("waiting for materials: 220 lime carpet", sim.engine.status().note());
        assertTrue(logged(log, "building the 100 of 320 cells the stock covers"), log.lines.toString());
    }

    @Test
    void carriedCellsTheBotCannotPlaceAreTriedOnceThenWait() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = startAndWait(sim, "j0966");
        sim.bot.unplaceable = p -> p.itemId().equals(LIME) ? "not reachable" : null; // no lime cell can be placed
        sim.bot.inv.items.merge(LIME, 100, Integer::sum);
        sim.run(10 * MINUTE);
        assertTrue(waiting(sim), sim.state());
        assertEquals(0, limePlaced(sim, 10));
        assertEquals(1, loggedCount(log, "cells the inventory covers"), "one carried round, not a loop: " + log.lines);
        assertEquals(1, loggedCount(log, "placed none of them; they wait with the rest"), log.lines.toString());
        assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
        assertEquals(JobStatus.WAITING_MATERIALS, job.status);
        // more lime handed over: the inventory changed, so it is tried again (once)
        sim.bot.unplaceable = null;
        sim.bot.inv.items.merge(LIME, 20, Integer::sum);
        assertTrue(sim.runUntil(() -> limePlaced(sim, 10) == 120, 2 * MINUTE), sim.state());
        assertTrue(sim.store.phaseLog.stream().noneMatch(p -> MAP_PHASES.contains(Phase.valueOf(p))),
            "no map while lime is missing");
    }
}
