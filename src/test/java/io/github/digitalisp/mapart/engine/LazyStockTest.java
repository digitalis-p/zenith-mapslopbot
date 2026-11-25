package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.MossStock;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Lazy stock staleness ({@code build.stockMaxAgeMinutes} = 0, the default): snapshots are trusted whatever their age;
 * the chests the bot opens refresh themselves, and only a real shortage looks again (STOCK_CHECK's shortfall, a
 * withdrawal that comes up short, a waiting job's re-check). A stale "full" snapshot of a chest that is really empty
 * must never stall a job.
 */
class LazyStockTest {
    private static final int LONG = 60_000;
    private static final long HOUR = 3_600_000L;
    private static final Pos WHITE_A = new Pos(-98, 61, 100); // the nearer white chest: restock tries it first
    private static final Pos WHITE_B = new Pos(-100, 61, 100);

    private static void assertDone(Sim sim, Job job) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state());
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), "not idle after done: " + sim.state());
    }

    private static RecordingLogger logged(Sim sim) {
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return log;
    }

    // ---- a day of jobs -------------------------------------------------------------------------

    /** 44 materials, 7 chests each (308) plus 2 empty ones: the moss sorter's 310 containers, synthetic coordinates. */
    private static List<String> sorterStorage(Sim sim) {
        List<String> mats = new ArrayList<>(MossStock.items().keySet());
        sim.config.site.storage.materialRegions.clear();
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = new Pos(-300, 61, 140);
        r.max = new Pos(-239, 65, 140);
        sim.config.site.storage.materialRegions.add(r);
        int i = 0;
        for (int y = 61; y <= 65; y++) {
            for (int x = -300; x <= -239; x++, i++) {
                int m = i / 7;
                sim.bot.addContainer(new Pos(x, y, 140), m < mats.size() ? Map.of(mats.get(m), 1728) : Map.of());
            }
        }
        return mats;
    }

    /** A tile whose first two rows cycle through {@code ids}. */
    private static TileArt cycle(List<String> ids) {
        TileArt a = new TileArt();
        for (int pz = 0; pz < 2; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(ids.get((px + pz) % ids.size())));
        return a;
    }

    /** Six jobs two hours apart, 12 of the 44 materials each; scans per job. */
    private static List<Long> dayOfJobs(int stockMaxAgeMinutes) {
        Sim sim = new Sim();
        sim.config.build.stockMaxAgeMinutes = stockMaxAgeMinutes;
        List<String> mats = sorterStorage(sim);
        sim.start();
        List<Long> scans = new ArrayList<>();
        for (int j = 0; j < 6; j++) {
            List<String> use = new ArrayList<>();
            for (int k = 0; k < 12; k++) use.add(mats.get((j * 7 + k) % mats.size()));
            long before = sim.bot.count("scan ");
            Job job = sim.addJob("j07" + j, "Day " + j, 1, 1, List.of("moss"), (x, z) -> cycle(use));
            sim.engine.queueChanged();
            assertDone(sim, job);
            scans.add(sim.bot.count("scan ") - before);
            sim.clock.advance(2 * HOUR);
        }
        return scans;
    }

    @Test
    void aDayOfJobsScansTheSorterOnceAndThenTrustsTheIndex() {
        List<Long> ceiling60 = dayOfJobs(60); // before: the old default
        List<Long> lazy = dayOfJobs(0);       // now
        System.out.println("[lazy stock] scans per job, 6 jobs 2 h apart, 12 of 44 materials each, 310 chests:"
            + " stockMaxAgeMinutes=60 " + ceiling60 + " (" + ceiling60.stream().mapToLong(Long::longValue).sum()
            + "), 0 " + lazy + " (" + lazy.stream().mapToLong(Long::longValue).sum() + ")");
        assertEquals(310L, lazy.getFirst(), "the first job scans every never-scanned container once");
        for (int j = 1; j < lazy.size(); j++) assertEquals(0L, lazy.get(j), "job " + j + ": no rescans, the index suffices");
        assertEquals(310L, ceiling60.getFirst());
        for (int j = 1; j < ceiling60.size(); j++) {
            assertEquals(84L, ceiling60.get(j), "job " + j + " with the old 60 min ceiling: 12 materials x 7 stale chests");
        }
    }

    // ---- a stale "full" snapshot of an emptied chest ----------------------------------------------

    /** After a first job, the index believes {@code WHITE_A} holds 3456 white (more than any other chest; the nearest). */
    private static Sim afterFirstJob(Map<String, Integer> realA, Map<String, Integer> realB, RecordingLogger[] log) {
        Sim sim = new Sim();
        sim.config.build.stockMaxAgeMinutes = 0;
        log[0] = logged(sim);
        Job first = sim.addJob("j0801", "First", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.RED, 1));
        sim.start();
        assertDone(sim, first);
        sim.store.stock.update(WHITE_A, Map.of(WHITE, 3456), 0, 54, sim.clock.millis() - 5 * HOUR);
        sim.bot.containers.get(WHITE_A).items.clear();
        sim.bot.containers.get(WHITE_A).items.putAll(realA);
        sim.bot.containers.get(WHITE_B).items.clear();
        sim.bot.containers.get(WHITE_B).items.putAll(realB);
        sim.clock.advance(3 * HOUR);
        return sim;
    }

    @Test
    void aWithdrawalFromAStaleFullChestReLooksAndReplansTheLoad() {
        RecordingLogger[] log = new RecordingLogger[1];
        Sim sim = afterFirstJob(Map.of(), Map.of(WHITE, 1728), log);
        Job job = sim.addJob("j0802", "Lots of white", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 12)); // 1536
        sim.engine.queueChanged();
        assertDone(sim, job);
        List<String> info = log[0].at(org.slf4j.event.Level.INFO);
        assertTrue(info.stream().anyMatch(l -> l.contains("withdrawals came up short of [" + WHITE + "]")), info.toString());
        assertTrue(sim.bot.count("scan " + WHITE_B) >= 2, "the other white chest was looked at again: " + sim.bot.history);
        assertTrue(info.stream().noneMatch(l -> l.startsWith("INFO build round") && l.contains(" 0 placed")),
            "no build round without materials before the re-plan: " + info);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertEquals(1728 - 1536, sim.bot.containers.get(WHITE_B).items.getOrDefault(WHITE, 0), "the load came from B");
    }

    @Test
    void whiteGoneEverywhereWaitsForMaterialsInsteadOfBlocking() {
        RecordingLogger[] log = new RecordingLogger[1];
        Sim sim = afterFirstJob(Map.of(), Map.of(), log);
        Job job = sim.addJob("j0803", "No white", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 12));
        sim.engine.queueChanged();
        List<Phase> seen = new ArrayList<>();
        assertTrue(sim.runUntil(() -> {
            seen.add(sim.phase());
            return job.status == JobStatus.WAITING_MATERIALS && sim.phase() == Phase.IDLE;
        }, LONG), sim.state());
        assertFalse(seen.contains(Phase.BLOCKED), "never BLOCKED: " + sim.state());
        assertTrue(sim.notifier.of("alert").stream().anyMatch(m -> m.title().equals("Out of white carpet")
            && m.body().contains("Nothing to build")), sim.notifier.toString());
        // the sorter refills the chest later: the waiting job's re-check finds it
        sim.config.build.waitingRecheckMinutes = 1;
        sim.bot.containers.get(WHITE_B).items.put(WHITE, 1728);
        assertDone(sim, job);
    }

    // ---- waiting jobs without an age ceiling ------------------------------------------------------

    @Test
    void aWaitingJobLooksAgainAtItsChestsEveryRecheckWithoutAnAgeCeiling() {
        Sim sim = new Sim();
        sim.config.build.nearRecheckAll = false; // the targeted look itself (on: every near container)
        sim.config.build.stockMaxAgeMinutes = 0;
        sim.config.build.waitingRecheckMinutes = 1;
        Pos empty = new Pos(-90, 61, 104); // the sorter's (so far empty) lime chest
        sim.bot.addContainer(empty, Map.of());
        Job job = sim.addJob("j0804", "Lime", 1, 1, List.of("moss"), (x, z) -> Sim.single(LIME, 1));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.WAITING_MATERIALS, LONG), sim.state());
        long whiteScans = sim.bot.count("scan " + WHITE_A);
        sim.bot.containers.get(empty).items.put(LIME, 200);
        assertDone(sim, job);
        assertEquals(whiteScans, sim.bot.count("scan " + WHITE_A), "only the chests that could hold lime were looked at");
        assertNotEquals(0, sim.bot.count("scan " + empty));
    }
}
