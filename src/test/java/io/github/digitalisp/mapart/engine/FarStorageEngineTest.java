package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.ConfiguredRestockPlanner;
import io.github.digitalisp.mapart.plan.FarStorage;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.BLACK;
import static io.github.digitalisp.mapart.engine.Sim.LIME;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * Far storage in the engine ({@code build.farStorage}). Two storages in the Sim's
 * relative coordinates: the moss platform's own sorter 37-47 blocks south of its map area and a far one about 125
 * blocks south, like the soul sand platform's walkway and the moss sorter live (2026-10-13, ellen: 07:47 a load wanted
 * 64 purple terracotta, the near chest had 51, and the sweep walked about 250 blocks to the moss sorter for about 12).
 */
class FarStorageEngineTest {
    private static final int LONG = 120_000;
    private static final Pos FAR_CHEST = new Pos(-90, 61, 185);
    private static final Pos NEAR_LIME = new Pos(-90, 61, 104);
    private static final Pos NEAR_BLACK = new Pos(-94, 61, 100);

    private static Sim sim(boolean on, RecordingLogger log, RestockPlanner restock, Map<String, Integer> farItems) {
        Sim sim = new Sim();
        sim.modules.state.put("AntiAFK", false);
        sim.config.build.farStorage = on;
        MapArtConfig.RegionCfg far = new MapArtConfig.RegionCfg();
        far.min = new Pos(-100, 60, 180);
        far.max = new Pos(-80, 62, 190);
        far.name = "far sorter";
        sim.config.site.storage.materialRegions.add(far);
        sim.bot.addContainer(FAR_CHEST, farItems);
        sim.newEngine(new SerpentinePlanner(), restock, log != null ? log : Sim.LOG);
        return sim;
    }

    /** RESTOCK + BUILD rounds whose withdrawals went to {@code far}: one far trip each. */
    private static int farTrips(Sim sim, Pos far) {
        int trips = 0;
        boolean seen = false;
        for (String h : sim.bot.history) {
            if (h.startsWith("withdraw " + far)) seen = true;
            else if (h.startsWith("placeLane") && seen) {
                trips++;
                seen = false;
            }
        }
        return trips + (seen ? 1 : 0);
    }

    /** Art blocks placed on moss that are not {@code item}. */
    private static int placedOtherThan(Sim sim, String item) {
        int n = 0;
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) {
                BlockSpec b = sim.world.block(new Pos(sim.moss.minX + px, sim.moss.artY, sim.moss.minZ + pz));
                if (!b.isAir() && !b.id().equals(item)) n++;
            }
        return n;
    }

    private record Run(int farTrips, Sim sim) { }

    private static Run build(Sim sim, TileArt art) {
        Job job = sim.addJob("j0950", "Far", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.output().maps.size(), "verified and stored");
        return new Run(farTrips(sim, FAR_CHEST), sim);
    }

    /** White/red/terracotta stripes over {@code rows} rows, lime in the first {@code lime} cells of row 0. */
    private static TileArt fewLime(int rows, int lime) {
        TileArt a = new TileArt();
        String[] cols = {WHITE, RED, Sim.TERRACOTTA};
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(pz == 0 && px < lime ? LIME : cols[px % 3]));
        return a;
    }

    /** {@link #fewLime} over 40 rows with black in the last 8 columns (the last two lanes): 320 black cells. */
    private static TileArt limeThenBlack(int lime) {
        TileArt a = fewLime(40, lime);
        for (int pz = 0; pz < 40; pz++) for (int px = 120; px < 128; px++) a.set(px, pz, BlockSpec.of(BLACK));
        return a;
    }

    @Test
    void theFarSorterIsFarAndTheOwnOneNear() {
        Sim sim = new Sim();
        PlatformGeometry geo = new PlatformGeometry(sim.moss.minX, sim.moss.minZ, sim.moss.artY);
        assertFalse(FarStorage.far(geo, sim.config.build.farStorageBlocks, new Pos(-100, 61, 110)));
        assertTrue(FarStorage.far(geo, sim.config.build.farStorageBlocks, FAR_CHEST));
    }

    /**
     * Live 07:47: the near chest holds 51 of the 60 lime the first lanes need; the far sorter has the rest, and all
     * the black, which only the last lanes need. Before: a far trip for 9 lime, then one for the black. Now the 9 lime
     * cells wait and go with the black (the greedy planner), or the batch of the load that goes for the black takes
     * them (the AUTO planner, whose first load needed only the near 51).
     */
    @Test
    void aFewFarCellsGoWithALoadThatGoesThereAnyway() {
        for (RestockPlanner planner : List.<RestockPlanner>of(new GreedyRestockPlanner(),
            new ConfiguredRestockPlanner(() -> "AUTO"))) {
            String name = planner.getClass().getSimpleName();
            Sim off = sim(false, null, planner, Map.of(LIME, 1000, BLACK, 3000));
            off.bot.addContainer(NEAR_LIME, Map.of(LIME, 51));
            off.bot.addContainer(NEAR_BLACK, Map.of());
            Run before = build(off, limeThenBlack(60));
            RecordingLogger log = new RecordingLogger();
            Sim on = sim(true, log, planner, Map.of(LIME, 1000, BLACK, 3000));
            on.bot.addContainer(NEAR_LIME, Map.of(LIME, 51));
            on.bot.addContainer(NEAR_BLACK, Map.of());
            Run now = build(on, limeThenBlack(60));
            System.out.println("[far storage] " + name + ", 9 lime + 320 black far: far trips " + before.farTrips()
                + " -> " + now.farTrips());
            assertEquals(2, before.farTrips(), name + ": before, a trip for the 9 lime and one for the black");
            assertEquals(1, now.farTrips(), name + ": the lime goes with the black");
            // the 9 lime wait (left out), or the load's batch takes them along with the black: one far trip either way
            assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("restock: the load would go to far storage"
                + " for only ") && l.contains("lime carpet), fewer than 64: leaving out"))
                || log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("restock: far storage batch: {" + BLACK)),
                log.lines.toString());
            assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("restock: far trip: {")
                && l.contains(BLACK) && l.contains(LIME) && l.endsWith("(125 blocks from the platform)")), log.lines.toString());
        }
    }

    /**
     * 30 lime cells in the tile's first row, all in the far sorter, and no later load goes there: the load that needs
     * them takes them now. Deferring them would only move the same trip to the tile's end and walk back over their
     * lanes (restock model: +50 % for 16 such cells).
     */
    @Test
    void aFewFarCellsWithNoLaterFarTripGoNow() {
        TileArt art = fewLime(20, 30);
        Run before = build(sim(false, null, new GreedyRestockPlanner(), Map.of(LIME, 1728)), art);
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(true, log, new GreedyRestockPlanner(), Map.of(LIME, 1728));
        Job job = sim.addJob("j0951", "Few", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        int[] otherAtFarTrip = {-1};
        assertTrue(sim.runUntil(() -> {
            if (otherAtFarTrip[0] < 0 && sim.bot.count("withdraw " + FAR_CHEST) > 0)
                otherAtFarTrip[0] = placedOtherThan(sim, LIME);
            return job.status == JobStatus.DONE;
        }, LONG), sim.state());
        assertEquals(1, before.farTrips());
        assertEquals(1, farTrips(sim, FAR_CHEST), "one far trip");
        assertEquals(0, otherAtFarTrip[0], "with the first load, not at the end");
        assertTrue(log.at(Level.INFO).stream().noneMatch(l -> l.contains("leaving out")), log.lines.toString());
    }

    @Test
    void theStackRoundUpNeverComesFromTheFarSorter() {
        // 100 lime cells, the near chest holds 100: a top-up asks for 128; before, the other 28 came from far
        TileArt art = fewLime(4, 100);
        Sim off = sim(false, null, new GreedyRestockPlanner(), Map.of(LIME, 1728));
        off.bot.addContainer(NEAR_LIME, Map.of(LIME, 100));
        Sim on = sim(true, null, new GreedyRestockPlanner(), Map.of(LIME, 1728));
        on.bot.addContainer(NEAR_LIME, Map.of(LIME, 100));
        assertEquals(1, build(off, art).farTrips(), "before: a far trip for the round-up");
        assertEquals(0, build(on, art).farTrips(), "the round-up stays near");
    }

    @Test
    void aLoadWithManyFarCellsStillGoes() {
        // 512 lime cells only in the far sorter: the load that needs them goes, nothing waits
        TileArt art = fewLime(4, 0);
        for (int pz = 0; pz < 4; pz++) for (int px = 0; px < 128; px++) art.set(px, pz, BlockSpec.of(LIME));
        RecordingLogger log = new RecordingLogger();
        Run now = build(sim(true, log, new GreedyRestockPlanner(), Map.of(LIME, 1728)), art);
        assertEquals(1, now.farTrips());
        assertTrue(log.at(Level.INFO).stream().noneMatch(l -> l.contains("leaving out")), log.lines.toString());
    }

    // ------------------------------------------------------------------------------------------------- batching

    /** White/red/terracotta stripes over {@code rows} rows, every 4th column lime: a quarter of the cells, every lane. */
    private static TileArt limeEveryLane(int rows) {
        TileArt a = new TileArt();
        String[] cols = {WHITE, RED, Sim.TERRACOTTA};
        for (int pz = 0; pz < rows; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(px % 4 == 3 ? LIME : cols[px % 3]));
        return a;
    }

    private static Run batched(boolean batching, RestockPlanner planner, RecordingLogger log) {
        Sim sim = sim(true, log, planner, Map.of(LIME, 3456));
        sim.config.build.farStorageBatching = batching;
        return build(sim, limeEveryLane(40));
    }

    /**
     * 40 rows, 5,120 cells, 1,280 of them lime in every lane, lime only in the far sorter: each load needs some. Before,
     * every load went there; batched, the load that goes takes the lime of the lanes ahead.
     */
    @Test
    void aFarItemInEveryLaneComesInFewerFarTrips() {
        for (RestockPlanner planner : List.<RestockPlanner>of(new GreedyRestockPlanner(),
            new ConfiguredRestockPlanner(() -> "AUTO"))) {
            String name = planner.getClass().getSimpleName();
            RecordingLogger offLog = new RecordingLogger();
            Run off = batched(false, planner, offLog);
            RecordingLogger log = new RecordingLogger();
            Run on = batched(true, planner, log);
            System.out.println("[far storage] " + name + ", lime far in every lane: far trips " + off.farTrips() + " -> "
                + on.farTrips() + ", restocks " + off.sim().store.entries("RESTOCK") + " -> "
                + on.sim().store.entries("RESTOCK"));
            assertTrue(off.farTrips() >= 3, name + ": before, every load went far: " + off.farTrips());
            assertTrue(on.farTrips() < off.farTrips(), name + ": " + on.farTrips() + " vs " + off.farTrips());
            assertTrue(log.at(Level.INFO).stream().anyMatch(l -> l.startsWith("restock: far storage batch: {" + LIME + "=")
                && l.endsWith("later loads need no far trip for them")), log.lines.toString());
            assertTrue(offLog.at(Level.INFO).stream().noneMatch(l -> l.startsWith("restock: far storage batch")),
                "the kill switch: " + offLog.lines);
        }
    }

    @Test
    void batchedFarItemsAreNotReturnedToMakeRoom() {
        RecordingLogger log = new RecordingLogger();
        batched(true, new GreedyRestockPlanner(), log);
        assertTrue(log.at(Level.INFO).stream().noneMatch(l -> (l.startsWith("crowded load:") || l.startsWith("making room:"))
            && l.contains(LIME)), log.lines.toString());
    }
}
