package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.digitalisp.mapart.engine.Sim.*;

/**
 * {@code build.nearRecheckAll}. Live 2026-10-29: white carpet
 * put by hand into a platform chest the stock index had as unrelated; the waiting looks read only the chests that
 * "hold or could hold" it, said "now 0 near", and the bot went to far storage and a 264-container sweep. The sorter
 * platform here: a near chest that once held lime (the targeted look's only candidate), an unrelated one, and a far chest.
 */
class NearRecheckAllEngineTest {
    private static final int LONG = 200_000;
    private static final Pos FAR_CHEST = new Pos(-90, 61, 185);
    /** Held lime before; the targeted look reads it. */
    private static final Pos HOLDER = new Pos(-90, 61, 104);
    /** Never held lime; only a look at every near container reads it. */
    private static final Pos UNRELATED = new Pos(-86, 61, 108);

    private static Sim sim(RecordingLogger log, boolean all) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.farStorage = true;
        sim.config.build.nearRecheckAll = all;
        sim.moss.sorterRefills = true;
        MapArtConfig.RegionCfg far = new MapArtConfig.RegionCfg();
        far.min = new Pos(-100, 60, 180);
        far.max = new Pos(-80, 62, 190);
        sim.config.site.storage.materialRegions.add(far);
        sim.bot.addContainer(FAR_CHEST, Map.of(BLACK, 64));
        sim.bot.addContainer(HOLDER, Map.of());
        sim.bot.addContainer(UNRELATED, Map.of(RED, 64));
        sim.store.stock.update(HOLDER, Map.of(LIME, 64), 26, 27, sim.clock.millis() - 600_000);
        sim.store.stock.update(HOLDER, Map.of(), 27, 27, sim.clock.millis() - 500_000);
        // every other material chest read just now, as it is: SELECT decides on the index
        for (Pos p : List.of(new Pos(-100, 61, 100), new Pos(-98, 61, 100), new Pos(-96, 61, 100),
            new Pos(-94, 61, 100), new Pos(-92, 61, 100), UNRELATED, FAR_CHEST))
            sim.store.stock.update(p, sim.bot.containers.get(p).items, 0, 27, sim.clock.millis());
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    /** A white first row (in stock), then {@code limeRows} rows of lime (none anywhere at the start). */
    private static Job job(Sim sim, int limeRows) {
        TileArt art = new TileArt();
        for (int pz = 0; pz <= limeRows; pz++)
            for (int px = 0; px < 128; px++) art.set(px, pz, BlockSpec.of(pz == 0 ? WHITE : LIME));
        return sim.addJob("j0970", "Recheck", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static boolean said(RecordingLogger log, String part) {
        return log.lines.stream().anyMatch(l -> l.contains(part));
    }

    private static long count(RecordingLogger log, String start) {
        return log.at(Level.INFO).stream().filter(l -> l.startsWith(start)).count();
    }

    /** Waits for lime, then the owner fills {@code UNRELATED} and the next look is due. */
    private static Job waitThenRestock(Sim sim, int limeRows, int lime) {
        Job job = job(sim, limeRows);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS, LONG), sim.state());
        sim.bot.containers.get(UNRELATED).items.put(LIME, lime);
        sim.clock.advance(11 * 60_000L);
        return job;
    }

    @Test
    void stockPutIntoAnUnrelatedNearChestIsFoundByTheNextWaitingLook() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        long before = sim.clock.millis();
        Job job = waitThenRestock(sim, 6, 1472);
        long scans = sim.bot.count("scan " + UNRELATED);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertTrue(sim.bot.count("scan " + UNRELATED) > scans, "the waiting look opened the unrelated chest");
        assertTrue(sim.bot.count("withdraw " + UNRELATED) > 0, log.lines.toString());
        assertEquals(0, sim.bot.count("withdraw " + FAR_CHEST), "no far trip");
        assertTrue(said(log, "looking again at every near container"), log.lines.toString());
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("near storage re-scanned (every near"
            + " container) before skipping") && l.endsWith(LIME + " now 1472 near")), log.lines.toString());
        assertTrue(sim.clock.millis() - before < 30 * 60_000L, "found at the first look after the restock");
    }

    @Test
    void stockBackInAChestThatHeldItIsFoundByTheTargetedLookWithoutASweep() {
        // live 2026-11-15 (j0009, pass 6): resin block short by the index, 4,296 in its own chests; the look "before
        // skipping" swept all 502 near containers of the platform (7:16) where reading the item's chests was enough
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Job job = job(sim, 6);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS, LONG), sim.state());
        sim.bot.containers.get(HOLDER).items.put(LIME, 1472);
        sim.clock.advance(11 * 60_000L);
        long sweeps = count(log, "near storage re-scanned");
        long unrelated = sim.bot.count("scan " + UNRELATED);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("near storage re-checked (1 of the near"
            + " containers that hold or could hold them) before skipping") && l.endsWith(LIME + " now 1472 near")),
            log.lines.toString());
        assertEquals(sweeps, count(log, "near storage re-scanned"), "no sweep of every near container: " + log.lines);
        assertEquals(unrelated, sim.bot.count("scan " + UNRELATED), "the unrelated chest stays closed");
        assertTrue(sim.bot.count("withdraw " + HOLDER) > 0, log.lines.toString());
        assertEquals(0, sim.bot.count("withdraw " + FAR_CHEST), "no far trip");
    }

    @Test
    void withoutTheSwitchTheTargetedLookMissesIt() {
        // the live behaviour: the look reads the former holder only and says "now 0 near"
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false);
        waitThenRestock(sim, 6, 1472);
        assertTrue(sim.runUntil(() -> count(log, "near storage re-checked (1 of the near containers") >= 2, LONG),
            log.lines.toString());
        assertTrue(said(log, "before skipping #j0970 Recheck [0,0]: " + LIME + " now 0 near"), log.lines.toString());
    }

    @Test
    void selectSweepsNothingWhenNothingIsShort() {
        // the owner's rule (2026-10-13): no near sweep per map start; the switch only acts on a shortage
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Job job = sim.addJob("j0971", "Stocked", 2, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals(job.id, sim.engine.status().jobId());
        assertEquals(0, count(log, "near storage re-scanned"), log.lines.toString());
        assertEquals(0, count(log, "near storage re-checked"), log.lines.toString());
        assertEquals(0, sim.bot.count("scan " + UNRELATED), "no look at an unrelated chest");
    }

    @Test
    void selectSweepsEveryNearContainerOnlyWhenTheTargetedLookLeavesTheInOrderMapShort() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        sim.moss.sorterRefills = false; // else the former holder's sorter counts as covering it at SELECT
        // the in-order map needs lime; only the unrelated chest has it, which the targeted look never opens
        sim.bot.containers.get(UNRELATED).items.put(LIME, 1728);
        sim.addJob("j0972", "Order", 2, 1, List.of("moss"), (x, z) -> Sim.single(x == 0 ? LIME : WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals(0, sim.engine.status().tileX(), "the in-order map kept: the sweep found its lime");
        assertEquals(1, count(log, "near storage re-checked"), "the targeted look first: " + log.lines);
        assertEquals(1, count(log, "near storage re-scanned"), log.lines.toString());
    }

    @Test
    void lookAtEveryNearContainerAtMostOncePerMinute() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Job job = job(sim, 6);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS, LONG), sim.state());
        long sweeps = count(log, "near storage re-scanned");
        // a look due again within the minute: the sweep just made stands for it
        sim.engine.requestStockScan();
        sim.run(400);
        assertEquals(sweeps, count(log, "near storage re-scanned"), log.lines.toString());
    }

    @Test
    void afterAPartialWithdrawTheRestStaysCounted() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Job job = waitThenRestock(sim, 6, 1472);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        var s = sim.store.stock.containers.get(UNRELATED.key());
        assertNotNull(s);
        assertEquals(1472 - 768, s.items.getOrDefault(LIME, 0), "the load took 768; the rest is still in the index");
        assertEquals(704, sim.bot.containers.get(UNRELATED).items.getOrDefault(LIME, 0));
    }

    @Test
    void noSorterWordingForStockNothingPutIntoTheSorter() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        Job job = job(sim, 6);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS, LONG), sim.state());
        assertFalse(said(log, "the sorter is still returning them"), log.lines.toString());
        assertTrue(said(log, "768 lime carpet short in the near chests, and none of it went into this platform's"
            + " sorter: it needs restocking"), log.lines.toString());
    }

    @Test
    void theSorterWordingStaysForWhatThisTilesNukeBroke() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true);
        // lime carpet on the art layer outside the map's rows: the nuke breaks it, and its drops go into the sorter
        sim.world.set(new Pos(sim.moss.minX + 5, sim.moss.artY, sim.moss.minZ + 120), BlockSpec.of(LIME));
        Job job = job(sim, 6);
        sim.start();
        assertTrue(sim.runUntil(() -> said(log, "not back in the near chests yet") || job.status == JobStatus.DONE
            || said(log, "needs restocking"), LONG), sim.state() + log.lines);
        assertTrue(said(log, "lime carpet not back in the near chests yet (the sorter is still returning them)"),
            log.lines.toString());
    }
}
