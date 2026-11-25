package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A queued colour-suppression job that has not started any pass takes the platform's current
 * {@code topLayerFirst} order when it is first selected ({@link PassOrder}); a job with a pass started keeps its own.
 */
class PassOrderTest {
    private static final int LONG = 60_000;

    private static List<Integer> layers(Job j) {
        List<Integer> out = new ArrayList<>();
        for (Job.SuppressionPass p : j.suppression) out.add(p.layer);
        return out;
    }

    /** The art of every stored pass, as "layer/level/noobline: blocks". */
    private static List<String> stored(Sim sim, Job j) throws IOException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < j.tiles.size(); i++) {
            Job.SuppressionPass p = j.suppression.get(i);
            out.add(p.layer + "/" + p.level + "/" + p.noobline + ": " + sim.store.loadTile(j.id, new TileCoord(i, 0)).blockCounts());
        }
        return out;
    }

    /** The same for the art as built in the order {@code topFirst}. */
    private static List<String> expected(SuppressionArt a, boolean topFirst) {
        List<String> out = new ArrayList<>();
        boolean before = a.topFirst();
        for (SuppressionArt.Pass p : a.topFirst(topFirst).passes())
            out.add(p.layer() + "/" + p.level() + "/" + p.noobline() + ": " + p.art().blockCounts());
        a.topFirst(before);
        return out;
    }

    @Test
    void thePermutationPutsTheWantedLayerFirstAndKeepsEachLayersOrder() {
        List<Job.SuppressionPass> bottomFirst = SuppressionEngineTest.passList(SuppressionEngineTest.art());
        assertEquals(8, bottomFirst.size());
        assertArrayEquals(new int[]{4, 5, 6, 7, 0, 1, 2, 3}, PassOrder.permutation(bottomFirst, true));
        assertNull(PassOrder.permutation(bottomFirst, false), "the order it has");
        List<Job.SuppressionPass> topFirst = SuppressionEngineTest.passList(SuppressionEngineTest.art().topFirst(true));
        assertNull(PassOrder.permutation(topFirst, true));
        assertArrayEquals(new int[]{4, 5, 6, 7, 0, 1, 2, 3}, PassOrder.permutation(topFirst, false));
        // a map of one layer has one order
        assertNull(PassOrder.permutation(topFirst.subList(0, 4), false));
        assertNull(PassOrder.permutation(List.of(), true));
    }

    @Test
    void aQueuedJobTakesThePlatformsOrderWhenItIsFirstSelected() throws IOException {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = SuppressionEngineTest.csPlatform(sim);
        assertTrue(cs.topLayerFirst, "the default");
        SuppressionArt a = SuppressionEngineTest.art();
        Job job = SuppressionEngineTest.addJob(sim, a); // uploaded while the platform built the bottom layer first
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1), layers(job));
        List<Integer> totals = new ArrayList<>();
        sim.start();
        int[] firstBuilt = {-1};
        assertTrue(sim.runUntil(() -> {
            if (firstBuilt[0] < 0 && sim.phase() == Phase.BUILD) {
                firstBuilt[0] = job.suppression.get(sim.engine.status().tileX()).layer;
                assertEquals(0, sim.engine.status().tileX(), "its first pass");
            }
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        assertEquals(SuppressionArt.TOP, firstBuilt[0], "the top layer is printed first");
        assertEquals(List.of(1, 1, 1, 1, 0, 0, 0, 0), layers(job));
        assertEquals(expected(a, true), stored(sim, job), "every stored pass is the pass its entry names");
        assertEquals(8, sim.store.tileSaves.size(), "written once: " + sim.store.tileSaves);
        for (Job.TileState t : job.tiles) totals.add(t.total);
        List<Integer> want = new ArrayList<>();
        for (SuppressionArt.Pass p : a.topFirst(true).passes()) want.add(p.art().nonEmptyCount());
        assertEquals(want, totals, "the block counts follow the passes");
        // the whole map stands as designed
        for (int layer = 0; layer < 2; layer++)
            for (int level = 0; level < 2; level++)
                for (int row = -1; row < 128; row++)
                    for (int col = 0; col < 128; col++) {
                        BlockSpec w = a.get(layer, level, col, row);
                        BlockSpec have = sim.world.block(new Pos(cs.minX + col, cs.glassY.get(layer) + 1 + level, cs.minZ + row));
                        if (w == null) assertTrue(have.isAir(), "air at " + layer + "/" + level + " " + col + "," + row);
                        else assertEquals(w, have, layer + "/" + level + " " + col + "," + row);
                    }
    }

    @Test
    void theOtherWayRoundAndAJobAlreadyInThePlatformsOrderIsLeftAlone() throws IOException {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = SuppressionEngineTest.csPlatform(sim);
        cs.topLayerFirst = false;
        SuppressionArt a = SuppressionEngineTest.art().topFirst(true);
        Job job = SuppressionEngineTest.addJob(sim, a); // uploaded top layer first
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(0).status != TileStatus.PENDING, LONG), sim.state());
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1), layers(job), "bottom layer first, as the platform says now");
        assertEquals(expected(a, false), stored(sim, job));

        Sim same = new Sim();
        SuppressionEngineTest.csPlatform(same);
        Job kept = SuppressionEngineTest.addJob(same, SuppressionEngineTest.art().topFirst(true));
        same.start();
        assertTrue(same.runUntil(() -> kept.tiles.get(0).status != TileStatus.PENDING, LONG), same.state());
        assertTrue(same.store.tileSaves.isEmpty(), "nothing rewritten");
        assertEquals(List.of(1, 1, 1, 1, 0, 0, 0, 0), layers(kept));
    }

    @Test
    void aJobWithAPassStartedKeepsItsOrder() throws IOException {
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg cs = SuppressionEngineTest.csPlatform(sim);
        cs.topLayerFirst = false;
        SuppressionArt a = SuppressionEngineTest.art();
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.start();
        assertTrue(sim.runUntil(() -> job.tiles.get(0).status == TileStatus.STORED, LONG), sim.state());
        // the setting changes and the plugin restarts with the job's first pass done
        cs.topLayerFirst = true;
        sim.newEngine();
        sim.start();
        assertTrue(sim.runUntil(() -> {
            assertTrue(sim.phase() != Phase.BLOCKED, sim.state());
            return job.status == JobStatus.READY_FOR_RIDE;
        }, LONG), sim.state());
        assertTrue(sim.store.tileSaves.isEmpty(), "no pass was moved: " + sim.store.tileSaves);
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1), layers(job), "the order it was started with");
        assertEquals(expected(a, false), stored(sim, job));
        assertFalse(PassOrder.unstarted(job));
    }

    /** A restart between the tiles' rewrite and the job's save: the journal finishes the change at the next selection. */
    @Test
    void anOrderChangeCutShortIsFinishedFromItsJournal() throws IOException {
        Sim sim = new Sim();
        SuppressionEngineTest.csPlatform(sim);
        sim.store.blobs = new HashMap<>();
        SuppressionArt a = SuppressionEngineTest.art();
        Job job = SuppressionEngineTest.addJob(sim, a);
        sim.store.failTileSave = job.id + ":" + new TileCoord(3, 0);
        assertThrows(IOException.class, () -> PassOrder.write(sim.store, job, true));
        assertEquals(3, sim.store.tileSaves.size(), "three tiles in the new order, five in the old");
        assertNotNull(sim.store.blobs.get(PassOrder.blobName(job.id)), "the journal is there");
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1), layers(job), "the job itself is untouched");

        // the next selection (after a restart; the setting may even have changed back): the journal wins
        sim.store.failTileSave = null;
        PassOrder.Change c = PassOrder.write(sim.store, job, false);
        assertNotNull(c);
        PassOrder.apply(job, c);
        assertEquals(List.of(1, 1, 1, 1, 0, 0, 0, 0), layers(job));
        assertEquals(expected(a, true), stored(sim, job));
        TileArt first = sim.store.loadTile(job.id, new TileCoord(0, 0));
        assertEquals(first.nonEmptyCount(), job.tiles.get(0).total);

        // in the engine: the same journal is finished at SELECT and removed, and the map is printed from it
        Sim again = new Sim();
        MapArtConfig.PlatformCfg cs = SuppressionEngineTest.csPlatform(again);
        again.store.blobs = new HashMap<>();
        Job j2 = SuppressionEngineTest.addJob(again, a);
        again.store.failTileSave = j2.id + ":" + new TileCoord(5, 0);
        assertThrows(IOException.class, () -> PassOrder.write(again.store, j2, true));
        again.store.failTileSave = null;
        again.start();
        assertTrue(again.runUntil(() -> {
            assertTrue(again.phase() != Phase.BLOCKED, again.state());
            return j2.status == JobStatus.READY_FOR_RIDE;
        }, LONG), again.state());
        assertEquals(List.of(1, 1, 1, 1, 0, 0, 0, 0), layers(j2));
        assertTrue(again.store.blobs.isEmpty(), "the journal is gone once the job is saved");
        assertTrue(cs.topLayerFirst);
    }

    /** A rewrite that fails is never printed from: the job fails with an alert instead of a map in mixed order. */
    @Test
    void aRewriteThatFailsFailsTheJob() {
        Sim sim = new Sim();
        SuppressionEngineTest.csPlatform(sim);
        Job job = SuppressionEngineTest.addJob(sim, SuppressionEngineTest.art());
        sim.store.failTileSave = job.id + ":" + new TileCoord(3, 0);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.FAILED, LONG), sim.state());
        assertTrue(job.statusDetail.startsWith("pass order not changed"), job.statusDetail);
        assertTrue(sim.notifier.has("alert", "Job failed"), sim.notifier.toString());
        assertEquals(0, sim.bot.placed, "nothing was printed");
    }
}
