package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.ContainerVisit;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;
import io.github.digitalisp.mapart.testutil.ContainerSites;
import io.github.digitalisp.mapart.testutil.RecordingLogger;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A requested full scan of a moss-like sorter (62 one-wide columns of double chests, 5 rows, a walkway in front;
 * synthetic coordinates) through the engine, the production {@code Approach}, {@code ContainerRun} and
 * {@code SweepPlanner} on the simulated block world. Before: nearest-first from the region centre, where discovery
 * leaves the bot (the live 2026-10-11 scan: every walk reversed direction, 14 blocks per container), and the old menu
 * timing. Now: one sweep, every container opened from the first stand spot that sees it, look-only scans.
 */
class ScanSweepSimTest {
    private static final int X0 = -400;
    private static final int X1 = X0 + 61;
    private static final int FLOOR = 70;
    private static final int Z_FRONT = 40;
    private static final int CONTAINERS = 62 * 5;

    record Run(long ticks, double walked, int walks, int approaches, int failures, int scans, List<ContainerVisit> plan) { }

    private static Sim sorter(boolean sweep, boolean lookOnly) {
        return sorter(sweep, lookOnly, (x, y) -> Map.of(Sim.WHITE, 64));
    }

    private static Sim sorter(boolean sweep, boolean lookOnly,
                              java.util.function.BiFunction<Integer, Integer, Map<String, Integer>> items) {
        Sim sim = new Sim();
        sim.config.site.storage.materialRegions.clear();
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = new Pos(X0, FLOOR + 1, Z_FRONT - 1);
        r.max = new Pos(X1, FLOOR + 5, Z_FRONT);
        sim.config.site.storage.materialRegions.add(r);
        ContainerSim cs = sim.bot.simulateContainerAccess(17);
        cs.routeRadius = 96; // Baritone paths along the whole walkway (the zig-zag spans 60 blocks)
        ContainerSites.sorterWall(cs, X0, X1, FLOOR, Z_FRONT);
        for (int x = X0; x <= X1; x++) {
            for (int y = FLOOR + 1; y <= FLOOR + 5; y++) {
                // the stock-index half (smaller z) as a chest in the engine's world, for discovery
                sim.bot.addContainer(ContainerSites.sorterChest(x, y, Z_FRONT).getFirst(), items.apply(x, y));
            }
        }
        // the bot starts on the walkway in the middle of the sorter: where the old discovery always walked first (the
        // region centre), the reference point of the nearest-first order; the worst start for the sweep
        sim.world.moveTo(new Pos((X0 + X1) / 2, FLOOR + 1, Z_FRONT + 3));
        sim.bot.containerStart = new double[]{(X0 + X1) / 2 + 0.5, FLOOR + 1, Z_FRONT + 3.5};
        sim.bot.planSweeps = sweep;
        sim.bot.legacyMenuTiming = !lookOnly;
        return sim;
    }

    private static Run fullScan(boolean sweep, boolean lookOnly) {
        Sim sim = sorter(sweep, lookOnly);
        RecordingLogger log = new RecordingLogger();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.start();
        sim.run(60);
        sim.engine.requestStockScan();
        long t0 = sim.ticks;
        assertTrue(sim.runUntil(() -> sim.store.stock.containers.size() == CONTAINERS, 400_000), () -> sim.state()
            + "\nstock " + sim.store.stock.containers.size() + "\n" + String.join("\n",
            log.lines.subList(Math.max(0, log.lines.size() - 40), log.lines.size()))
            + "\nhistory tail " + sim.bot.history.subList(Math.max(0, sim.bot.history.size() - 10), sim.bot.history.size())
            + "\napproach failures " + sim.bot.approachFailures + "\n" + sim.bot.containerSim.log);
        long ticks = sim.ticks - t0;
        ContainerSim cs = sim.bot.containerSim;
        List<ContainerVisit> plan = sim.bot.plans.isEmpty() ? List.of() : sim.bot.plans.getLast();
        return new Run(ticks, cs.walked, cs.baritoneWalks, sim.bot.approaches, sim.bot.approachFailures.size(),
            (int) sim.bot.count("scan "), plan);
    }

    @Test
    void aFullScanSweepsTheSorterOnceInsteadOfZigZagging() {
        Run before = fullScan(false, false);
        Run sweptOnly = fullScan(true, false);
        Run now = fullScan(true, true);
        System.out.printf("[scan sweep] 310 containers: before %d ticks (%.0f s at 1x), %.0f blocks walked in %d walks;"
                + " sweep with the old menu timing %d ticks (%.0f s); now %d ticks (%.0f s), %.0f blocks in %d walks,"
                + " %d stand spots%n",
            before.ticks(), before.ticks() / 20.0, before.walked(), before.walks(), sweptOnly.ticks(),
            sweptOnly.ticks() / 20.0, now.ticks(), now.ticks() / 20.0, now.walked(), now.walks(), stops(now.plan()));
        assertTrue(now.ticks() < sweptOnly.ticks(), "look-only scans are quicker");

        for (Run r : List.of(before, now)) {
            assertEquals(CONTAINERS, r.scans(), "every container scanned once");
            assertEquals(CONTAINERS, r.approaches());
            assertEquals(0, r.failures(), "no approach failed");
        }
        assertEquals(CONTAINERS, now.plan().size());
        Set<Pos> once = new HashSet<>();
        for (ContainerVisit v : now.plan()) assertTrue(once.add(v.container()), "planned once: " + v);
        assertTrue(now.plan().stream().allMatch(v -> v.standAt() != null), "every container has a stand spot");
        assertTrue(stops(now.plan()) <= 15, "a few stand spots: " + stops(now.plan()));
        assertTrue(now.walks() <= stops(now.plan()) + 1, "one walk per stand spot: " + now.walks());
        assertTrue(now.walked() <= 31 + 62 + 10, "the nearer end, then one pass: " + now.walked());
        assertTrue(before.walked() > 10 * now.walked(), "before zig-zagged: " + before.walked());
        assertTrue(now.ticks() * 3 < before.ticks(), "less than a third of the time: " + now.ticks() + " vs " + before.ticks());
        assertTrue(monotone(now.plan()), "the stand spots go one way after reaching the end");
    }

    // ---- restock -------------------------------------------------------------------------------

    private static final List<String> MATERIALS = List.of("minecraft:white_carpet", "minecraft:orange_carpet",
        "minecraft:magenta_carpet", "minecraft:light_blue_carpet", "minecraft:yellow_carpet", "minecraft:lime_carpet",
        "minecraft:pink_carpet", "minecraft:gray_carpet", "minecraft:light_gray_carpet", "minecraft:cyan_carpet",
        "minecraft:purple_carpet", "minecraft:blue_carpet");

    /** Walks within the sorter (the first, from off the site, left out), blocks walked so, withdrawals, ticks. */
    record Restock(int walks, double walked, long withdrawals, long ticks, int stops) { }

    /**
     * The first RESTOCK of a job using 12 materials, each kept in every 12th column of the sorter (stock already
     * scanned): the chests of one load spread along the sorter.
     */
    private static Restock restock(boolean sweep) {
        Sim sim = sorter(sweep, sweep, (x, y) -> Map.of(MATERIALS.get(Math.floorMod(x - X0, 12)), 1728));
        sim.bot.containerStart = null; // the bot comes from elsewhere (GEAR's chests)
        for (int x = X0; x <= X1; x++) {
            for (int y = FLOOR + 1; y <= FLOOR + 5; y++) {
                sim.store.stock.update(ContainerSites.sorterChest(x, y, Z_FRONT).getFirst(),
                    Map.of(MATERIALS.get(Math.floorMod(x - X0, 12)), 1728), 0, 27, sim.clock.millis());
            }
        }
        io.github.digitalisp.mapart.model.TileArt art = new io.github.digitalisp.mapart.model.TileArt();
        for (int px = 0; px < 128; px++) {
            art.set(px, 0, io.github.digitalisp.mapart.model.BlockSpec.of(MATERIALS.get(px % MATERIALS.size())));
        }
        io.github.digitalisp.mapart.model.Job job = sim.addJob("j0901", "Twelve", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RESTOCK, 60_000), sim.state());
        ContainerSim cs = sim.bot.containerSim;
        int w0 = cs.walkLengths.size();
        long d0 = sim.bot.count("withdraw ");
        long t0 = sim.ticks;
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, 60_000), sim.state());
        List<Double> inside = cs.walkLengths.subList(Math.min(w0 + 1, cs.walkLengths.size()), cs.walkLengths.size());
        int plans = sim.bot.plans.size();
        int stops = plans == 0 ? 0 : stops(sim.bot.plans.getLast());
        assertTrue(sim.runUntil(() -> job.status == io.github.digitalisp.mapart.model.JobStatus.DONE, 200_000), sim.state());
        return new Restock(inside.size(), inside.stream().mapToDouble(Double::doubleValue).sum(),
            sim.bot.count("withdraw ") - d0, sim.ticks - t0, stops);
    }

    @Test
    void aRestockFetchesFromItsChestsInOneSweep() {
        Restock before = restock(false);
        Restock now = restock(true);
        System.out.printf("[restock sweep] %d chests: before %d walks on the sorter (%.0f blocks), %d ticks; now %d"
                + " walks (%.0f blocks) from %d stand spots, %d ticks%n", now.withdrawals(), before.walks(),
            before.walked(), before.ticks(), now.walks(), now.walked(), now.stops(), now.ticks());
        assertEquals(before.withdrawals(), now.withdrawals(), "the same load");
        assertTrue(now.withdrawals() >= 6, "a load from chests along the sorter: " + now.withdrawals());
        assertTrue(now.walks() <= now.stops(), "one walk per stand spot: " + now);
        assertTrue(now.walks() < before.walks(), "fewer walks: " + now + " vs " + before);
        assertTrue(now.ticks() < before.ticks(), "quicker: " + now + " vs " + before);
    }

    /** Stand cells walked to (consecutive visits from one cell count once). */
    static int stops(List<ContainerVisit> plan) {
        int n = 0;
        Pos last = null;
        for (ContainerVisit v : plan) {
            if (v.standAt() != null && !v.standAt().equals(last)) n++;
            last = v.standAt();
        }
        return n;
    }

    /** The stand cells' x never turns back. */
    private static boolean monotone(List<ContainerVisit> plan) {
        List<Integer> xs = new ArrayList<>();
        for (ContainerVisit v : plan) {
            if (v.standAt() != null && (xs.isEmpty() || xs.getLast() != v.standAt().x())) xs.add(v.standAt().x());
        }
        int dir = 0;
        for (int i = 1; i < xs.size(); i++) {
            int d = Integer.signum(xs.get(i) - xs.get(i - 1));
            if (dir == 0) dir = d;
            else if (d != 0 && d != dir) return false;
        }
        return true;
    }
}
