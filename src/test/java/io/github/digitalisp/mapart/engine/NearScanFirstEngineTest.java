package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.digitalisp.mapart.engine.Sim.*;

/**
 * Near-first decisions against deliberately wrong, recent snapshots, with a near and a far sorter: a targeted look
 * at the near chests that hold or could hold the short items, and before a far trip or a hold (not at SELECT) a sweep
 * of every near container at most once per {@code nearScanFullMaxAgeMinutes}.
 */
class NearScanFirstEngineTest {
    private static final Pos FAR = new Pos(-90, 61, 185);
    private static final Pos ARRIVAL = new Pos(-94, 61, 100);
    /** A lime sorter chest of the near storage the bot emptied two minutes ago, which the sorter fills again. */
    private static final Pos SORTER = new Pos(-88, 61, 100);
    private static final List<Pos> NEAR = List.of(new Pos(-100, 61, 100), new Pos(-98, 61, 100),
        new Pos(-96, 61, 100), ARRIVAL, new Pos(-92, 61, 100));
    private static final int LONG = 120_000;

    private static Sim sim(RecordingLogger log, Map<String, Integer> far) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.stockMaxAgeMinutes = 0;
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = new Pos(-100, 60, 180);
        r.max = new Pos(-80, 62, 190);
        sim.config.site.storage.materialRegions.add(r);
        sim.bot.addContainer(FAR, far);
        // Prime every material chest, including far: ordinary STOCK_CHECK has nothing to scan.
        for (Pos p : NEAR) snapshot(sim, p);
        snapshot(sim, FAR);
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static void snapshot(Sim sim, Pos p) {
        sim.store.stock.update(p, sim.bot.containers.get(p).items, 0, 27, sim.clock.millis());
    }

    private static void arrive(Sim sim) {
        // The holder index says this unrelated, non-empty chest holds black, and was just read.
        // Only a full near sweep, independent of item targeting and the usual one-minute floor, finds lime here.
        sim.bot.containers.get(ARRIVAL).items.clear();
        sim.bot.containers.get(ARRIVAL).items.put(LIME, 1728);
    }

    private static Job limeJob(Sim sim, int rows) {
        return sim.addJob("j0960", "Near arrival", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, rows));
    }

    /** Full near sweeps (every near container). */
    private static long rescans(RecordingLogger log) {
        return log.at(Level.INFO).stream().filter(l -> l.startsWith("near storage re-scanned")).count();
    }

    /** Targeted near looks. */
    private static long looks(RecordingLogger log) {
        return log.at(Level.INFO).stream().filter(l -> l.startsWith("near storage re-checked")).count();
    }

    /** The SORTER chest held lime, the bot emptied it, then the sorter drops lime into it: no snapshot says so. */
    private static void sorterArrival(Sim sim) {
        sim.bot.addContainer(SORTER, Map.of());
        sim.store.stock.update(SORTER, Map.of(LIME, 64), 26, 27, sim.clock.millis() - 180_000);
        sim.store.stock.update(SORTER, Map.of(), 27, 27, sim.clock.millis() - 120_000);
        sim.bot.containers.get(SORTER).items.put(LIME, 1728);
    }

    /** An empty chest that never held lime: not part of a targeted look. */
    private static final Pos SPARE = new Pos(-86, 61, 108);

    @Test
    void theInOrderTileIsKeptWhenTheTargetedLookFindsASorterArrival() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(BLACK, 1728));
        sorterArrival(sim);
        Job job = sim.addJob("j0961", "In order", 2, 1, List.of("moss"),
            (x, z) -> Sim.single(x == 0 ? LIME : WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals(0, sim.engine.status().tileX(), log.lines.toString());
        assertEquals(job.id, sim.engine.status().jobId());
        assertEquals(1, sim.bot.count("scan " + SORTER), "the emptied lime chest could hold lime again");
        for (Pos p : NEAR) assertEquals(0, sim.bot.count("scan " + p), "unrelated near chest left alone: " + p);
        assertEquals(0, sim.bot.count("scan " + FAR), "SELECT never scans far storage");
        assertEquals(0, sim.bot.count("scan " + Sim.RETURN), "role chest excluded");
        assertEquals(0, rescans(log), "no full sweep at SELECT");
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.equals("near storage re-checked (1 of the near"
            + " containers that hold or could hold them) before skipping #j0961 In order [0,0]: " + LIME
            + " now 1728 near")), log.lines.toString());
    }

    @Test
    void anEmptyChestThatNeverHeldTheItemIsNotPartOfTheTargetedLook() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(BLACK, 1728));
        sim.bot.addContainer(SPARE, Map.of());
        sim.store.stock.update(SPARE, Map.of(), 27, 27, sim.clock.millis() - 120_000);
        sorterArrival(sim);
        sim.addJob("j0966", "Spare", 2, 1, List.of("moss"), (x, z) -> Sim.single(x == 0 ? LIME : WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals(0, sim.engine.status().tileX());
        assertEquals(1, sim.bot.count("scan " + SORTER));
        assertEquals(0, sim.bot.count("scan " + SPARE), "far from every lime chest, never held lime");
    }

    @Test
    void selectNeverSweepsEveryNearContainer() {
        // live 2026-10-13: a full sweep of the 118 soul sand containers at a map boundary, then another at RESTOCK
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(BLACK, 1728));
        sim.config.build.nearRecheckAll = false; // the targeted look itself (on: every near container, NearRecheckAllEngineTest)
        arrive(sim);
        sim.addJob("j0965", "Boundary", 2, 1, List.of("moss"), (x, z) -> Sim.single(x == 0 ? LIME : WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals(1, sim.engine.status().tileX(), "lime only in an unrelated chest: the stocked map first");
        for (Pos p : NEAR) assertEquals(0, sim.bot.count("scan " + p), "not scanned at SELECT: " + p);
        assertEquals(0, rescans(log));
        assertEquals(1, looks(log));
    }

    @Test
    void aJobIsNotHeldWhenTheNearSweepFindsItsMissingItem() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(BLACK, 1728));
        arrive(sim);
        Job job = limeJob(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals(JobStatus.ACTIVE, job.status);
        assertEquals(0, sim.bot.count("scan " + FAR));
        assertEquals(1, rescans(log));
    }

    @Test
    void aFarTripIsAvoidedWhenTheNearSweepFindsTheLoad() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 1728));
        arrive(sim);
        Job job = limeJob(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.bot.count("withdraw " + FAR));
        assertEquals(0, sim.bot.count("scan " + FAR));
        assertTrue(sim.bot.count("withdraw " + ARRIVAL) > 0);
        for (Pos p : NEAR) assertEquals(1, sim.bot.count("scan " + p), "full near sweep: " + p);
        assertEquals(1, looks(log), "the targeted look first");
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.equals("near storage re-scanned (every near container)"
            + " before a far trip: " + LIME + " now 1728 near")), log.lines.toString());
        assertTrue(log.at(Level.INFO).stream().noneMatch(l -> l.startsWith("restock: far trip:")), log.lines.toString());
    }

    @Test
    void aSorterArrivalAvoidsTheFarTripWithoutAFullSweep() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 1728));
        sorterArrival(sim);
        Job job = limeJob(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.bot.count("withdraw " + FAR));
        assertTrue(sim.bot.count("withdraw " + SORTER) > 0);
        for (Pos p : NEAR) assertEquals(0, sim.bot.count("scan " + p), "unrelated near chest left alone: " + p);
        assertEquals(0, rescans(log), "found by the targeted look");
    }

    @Test
    void aFarRoundUpAlsoReReadsNearBeforeWithdrawing() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 1728));
        sim.config.build.farStorage = false;
        sim.store.stock.update(ARRIVAL, Map.of(LIME, 100), 0, 27, sim.clock.millis());
        arrive(sim);
        var art = Sim.single(LIME, 1);
        for (int x = 100; x < 128; x++) art.set(x, 0, null);
        Job job = sim.addJob("j0963", "Round-up", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, sim.bot.count("withdraw " + FAR));
        assertEquals(1, rescans(log));
    }

    @Test
    void aRecentSweepIsReusedAcrossRestockRounds() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 10_000));
        Job job = limeJob(sim, 40);
        long start = sim.clock.millis();
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.clock.millis() - start < 10 * 60_000L, "still within the age ceiling");
        assertTrue(sim.store.entries("RESTOCK") >= 3, "multiple loads");
        assertEquals(1, rescans(log));
        for (Pos p : NEAR) assertEquals(1, sim.bot.count("scan " + p));
        assertEquals(0, sim.bot.count("scan " + FAR));
        assertTrue(sim.bot.count("withdraw " + FAR) > 1, "far still used when near really lacks the item");
    }

    /** A sweep, then a restart (a new engine on the same store) while the job still needs far loads. */
    private static Sim restartAfterTheSweep(RecordingLogger log, boolean persist) {
        Sim sim = sim(log, Map.of(LIME, 10_000));
        sim.config.build.nearScanPersist = persist;
        Job job = limeJob(sim, 40);
        sim.start();
        assertTrue(sim.runUntil(() -> rescans(log) == 1 && sim.bot.count("placeLane") > 0, LONG), sim.state());
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.store.entries("RESTOCK") >= 3, "multiple loads");
        return sim;
    }

    @Test
    void aRestartKeepsTheRecentSweep() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = restartAfterTheSweep(log, true);
        assertEquals(1, rescans(log), log.lines.toString());
        for (Pos p : NEAR) assertEquals(1, sim.bot.count("scan " + p), "swept once: " + p);
        assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("near storage times restored from the stock"
            + " index: last full sweep 0 min ago")), log.lines.toString());
        assertNotNull(sim.store.stock.nearSweptAt(io.github.digitalisp.mapart.stock.StockIndex.platformKey(-64, -64, 64)));
    }

    @Test
    void withoutPersistenceARestartSweepsAgain() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = restartAfterTheSweep(log, false);
        assertEquals(2, rescans(log), log.lines.toString());
        assertNull(sim.store.stock.nearSweptAt);
    }

    @Test
    void aSweepTimeAheadOfTheClockIsDropped() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 1728));
        arrive(sim);
        // a clock change: the stored sweep lies an hour ahead, which must not keep the sweep from coming due
        sim.store.stock.nearSwept(io.github.digitalisp.mapart.stock.StockIndex.platformKey(-64, -64, 64),
            sim.clock.millis() + 60 * 60_000L);
        Job job = limeJob(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, rescans(log), log.lines.toString());
        assertEquals(0, sim.bot.count("withdraw " + FAR));
    }

    @Test
    void theInitialFullStockCheckAlsoCountsAsARecentNearSweep() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 1728));
        sim.store.stock.containers.clear();
        Job job = limeJob(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(0, rescans(log), "STOCK_CHECK just scanned every near chest");
        for (Pos p : NEAR) assertEquals(1, sim.bot.count("scan " + p));
    }

    @Test
    void aFailedNearSweepIsNotRepeatedEveryRound() {
        // live 2026-10-13: a sweep with 59 failed scans was not counted, and the next decision swept all 118 again
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 10_000));
        sim.bot.unreachable.add(ARRIVAL);
        Job job = limeJob(sim, 40);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.store.entries("RESTOCK") >= 3, "multiple loads");
        assertEquals(1, rescans(log), "a tried sweep counts, failed scans included");
        assertEquals(1, sim.bot.count("scan " + NEAR.getFirst()));
        assertTrue(sim.bot.count("scan " + ARRIVAL) <= 2, "the twice-failed skip rule still applies");
    }

    @Test
    void aRecentSweepOnAnotherPlatformDoesNotSuppressTheWorkingPlatformsSweep() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 1728));
        Pos soulNear = new Pos(300, 61, 100);
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = soulNear;
        r.max = soulNear;
        sim.config.site.storage.materialRegions.add(r);
        sim.bot.addContainer(soulNear, Map.of(BLACK, 1728));
        snapshot(sim, soulNear);
        Job first = limeJob(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE && sim.phase() == Phase.IDLE, LONG), sim.state());
        long trips = sim.bot.count("withdraw " + FAR);
        sim.bot.containers.get(soulNear).items.clear();
        sim.bot.containers.get(soulNear).items.put(LIME, 1728);
        Job second = sim.addJob("j0964", "Soul near", 1, 1, List.of("soul"), (x, z) -> Sim.single(LIME, 1));
        sim.engine.queueChanged();
        assertTrue(sim.runUntil(() -> second.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(2, rescans(log), "each platform has its own completion timestamp");
        assertEquals(1, sim.bot.count("scan " + soulNear));
        assertEquals(trips, sim.bot.count("withdraw " + FAR), "soul uses its own arrival");
        assertEquals(1, sim.bot.count("scan " + ARRIVAL), "soul never rescans the moss sorter");
    }

    @Test
    void anExpiredFullSweepIsRepeated() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 10_000));
        Job job = limeJob(sim, 40);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.count("placeLane") > 0, LONG), sim.state());
        sim.clock.advance(60 * 60_000L);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(rescans(log) >= 2, log.lines.toString());
        assertTrue(sim.bot.count("scan " + ARRIVAL) >= 2);
    }

    @Test
    void onlyTheTargetedLookIsRepeatedWithinTheFullSweepsAge() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 10_000));
        Job job = limeJob(sim, 40);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.count("placeLane") > 0, LONG), sim.state());
        sim.clock.advance(10 * 60_000L);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, rescans(log), log.lines.toString());
        assertTrue(looks(log) >= 2, log.lines.toString());
        assertEquals(1, sim.bot.count("scan " + ARRIVAL));
    }

    @Test
    void zeroAgeScansAgainAtEveryRestockRound() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 10_000));
        sim.config.build.nearScanMaxAgeMinutes = 0;
        sim.config.build.nearScanFullMaxAgeMinutes = 0;
        Job job = limeJob(sim, 40);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(rescans(log) >= 3, log.lines.toString());
        assertEquals(0, sim.bot.count("scan " + FAR));
    }

    @Test
    void theKillSwitchKeepsTheOldSelectBehaviour() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(BLACK, 1728));
        sim.config.build.nearScanFirst = false;
        arrive(sim);
        sim.addJob("j0962", "Old select", 2, 1, List.of("moss"),
            (x, z) -> Sim.single(x == 0 ? LIME : WHITE, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        assertEquals(1, sim.engine.status().tileX());
        assertEquals(0, sim.bot.count("scan "));
        assertEquals(0, rescans(log));
    }

    @Test
    void theKillSwitchKeepsTheOldFarWithdrawal() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, Map.of(LIME, 1728));
        sim.config.build.nearScanFirst = false;
        arrive(sim);
        Job job = limeJob(sim, 1);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.count("withdraw " + FAR) > 0);
        assertEquals(0, sim.bot.count("scan "));
        assertEquals(0, rescans(log));
    }

    @Test
    void theNearRuleAlsoAppliesWithFarBatchingAndFarRulesDisabled() {
        for (boolean farRules : List.of(false, true)) {
            RecordingLogger log = new RecordingLogger();
            Sim sim = sim(log, Map.of(LIME, 1728));
            sim.config.build.farStorage = farRules;
            sim.config.build.farStorageBatching = false;
            arrive(sim);
            Job job = limeJob(sim, 1);
            sim.start();
            assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
            assertEquals(0, sim.bot.count("withdraw " + FAR));
            assertEquals(1, rescans(log));
        }
    }
}
