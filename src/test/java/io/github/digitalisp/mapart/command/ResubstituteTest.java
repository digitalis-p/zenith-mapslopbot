package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.plan.DefaultSubmissionService;
import io.github.digitalisp.mapart.plan.SameColourSwap;
import io.github.digitalisp.mapart.store.FileJobStore;
import io.github.digitalisp.mapart.testutil.MossStock;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mapart resubstitute}: same-colour swaps again on a queued job's tiles with nothing placed, with the real
 * 1.21.4 registry and validation, a moss platform with the default clear lists, the moss sorter's stock plus white
 * candles, and the file store. Tiles with blocks placed and the engine's own tile never change.
 */
class ResubstituteTest {
    private static final String STEM = "minecraft:mushroom_stem";
    private static final String CANDLE = "minecraft:white_candle";
    private static final TileCoord STARTED = new TileCoord(0, 0);
    private static final TileCoord FRESH = new TileCoord(1, 0);
    private static final TileCoord ENGINE = new TileCoord(2, 0);

    @TempDir
    java.nio.file.Path dir;
    MapArtConfig config;
    FileJobStore store;
    Fakes.Engine engine;
    SubmissionPipeline pipeline;
    Job job;

    /** Ten rows of white candles (WOOL, 3), one row of mushroom stem (WOOL, 3), black carpet elsewhere. */
    private static TileArt tile() {
        return Tiles.of((x, z) -> z < 10 ? CANDLE : z == 10 ? STEM : "minecraft:black_carpet");
    }

    @BeforeEach
    void setUp() throws IOException {
        config = new MapArtConfig();
        config.site.platforms.add(MossStock.site().platforms.getFirst());
        config.enabled = true;
        store = new FileJobStore(dir, null, null);
        Map<String, Integer> items = new LinkedHashMap<>(MossStock.items());
        items.put(CANDLE, 5000);
        store.stock().update(new Pos(0, 64, 0), items, 0, 27, 1);
        engine = new Fakes.Engine();
        ZenithBlockCatalog catalog = new ZenithBlockCatalog();
        pipeline = new SubmissionPipeline(new DefaultSubmissionService(), catalog, config, store,
            new SameColourSubmissionTest.NoRender(), (id, b) -> 0xFF000000 | id, () -> engine, NOPLogger.NOP_LOGGER);
        assertEquals(3, catalog.mapColorId(BlockSpec.of(STEM)));
        assertEquals(3, catalog.mapColorId(BlockSpec.of(CANDLE)));

        TreeMap<TileCoord, TileArt> tiles = new TreeMap<>(Map.of(STARTED, tile(), FRESH, tile(), ENGINE, tile()));
        ParsedSubmission art = new ParsedSubmission("ellen", 3, 1, DefaultSubmissionService.DATA_VERSION_1_21_4, tiles,
            List.of());
        var v = new DefaultSubmissionService().validate(art, catalog, config.site);
        assertTrue(v.ok(), v.errors().toString());
        job = store.create(art, v, "ellen", "1", "jared2013");
        Job.TileState started = job.tile(STARTED);
        started.status = TileStatus.ACTIVE;
        started.platformId = "moss";
        started.placed = 500;
        store.save(job);
        // the engine has picked tile 2,0 (still PENDING in the job file while SELECT reads it)
        engine.status = new EngineStatus(true, false, "", Phase.SELECT, job.id, job.name, ENGINE.x(), ENGINE.z(), "moss",
            0, 0, "", 0);
    }

    @Test
    void offOnlyUnstockedBlocksAndEyeblossomsAreLookedAt() {
        SubmissionPipeline.Resubstitution r = pipeline.resubstitute(job.id, false);
        assertTrue(r.ok(), r.problem());
        assertEquals(List.of(FRESH), r.fresh(), "the started tile and the engine's tile are not fresh");
        assertTrue(r.changed().isEmpty());
        assertTrue(SubmissionPipeline.describe(r).startsWith("Nothing to swap on the 1 tile(s)"), SubmissionPipeline.describe(r));
    }

    @Test
    void dryRunShowsTheSwapAndChangesNothing() throws IOException {
        config.map.substituteStocked = true;
        Map<String, Integer> materials = new LinkedHashMap<>(job.materials);
        SubmissionPipeline.Resubstitution r = pipeline.resubstitute(job.id, false);
        assertTrue(r.ok(), r.problem());
        assertFalse(r.applied());
        assertEquals(List.of(FRESH), List.copyOf(r.changed().keySet()));
        assertEquals(new SubmissionPipeline.TileChange(3, 2, 0, 0), r.changed().get(FRESH));
        String text = SubmissionPipeline.describe(r);
        assertTrue(text.startsWith("Would swap (identical colors): mushroom_stem ×128 → white_candle\n"), text);
        assertTrue(text.contains("1 of 1 tile(s) with nothing placed would change (distinct items, LOOK cells): 1,0: 3→2"), text);
        assertTrue(text.contains("`mapart resubstitute " + job.id + " apply`"), text);
        assertEquals(BlockSpec.of(STEM), store.loadTile(job.id, FRESH).get(5, 10), "dry run: the file is unchanged");
        assertEquals(materials, job.materials);
        assertTrue(engine.calls.isEmpty());
    }

    @Test
    void applyNeedsTheBuilderPausedThenChangesOnlyTheFreshTile() throws IOException {
        config.map.substituteStocked = true;
        SubmissionPipeline.Resubstitution refused = pipeline.resubstitute(job.id, true);
        assertFalse(refused.ok());
        assertTrue(refused.problem().startsWith("Pause the builder first"), refused.problem());
        assertEquals(BlockSpec.of(STEM), store.loadTile(job.id, FRESH).get(5, 10));

        config.paused = true;
        SubmissionPipeline.Resubstitution r = pipeline.resubstitute(job.id, true);
        assertTrue(r.ok(), r.problem());
        assertTrue(r.applied());
        assertTrue(SubmissionPipeline.describe(r).startsWith("Re-substituted (identical colors): mushroom_stem ×128 → "
            + "white_candle\n"), SubmissionPipeline.describe(r));
        assertEquals(List.of("queueChanged"), engine.calls);

        FileJobStore reopened = new FileJobStore(dir, null, null);
        Job back = reopened.get(job.id).orElseThrow();
        assertEquals(BlockSpec.of(CANDLE), reopened.loadTile(job.id, FRESH).get(5, 10), "the fresh tile is re-planned");
        assertEquals(BlockSpec.of(STEM), reopened.loadTile(job.id, STARTED).get(5, 10), "blocks placed: never touched");
        assertEquals(BlockSpec.of(STEM), reopened.loadTile(job.id, ENGINE).get(5, 10), "the engine's tile: never touched");
        assertTrue(Tiles.equal(tile(), reopened.loadTile(job.id, STARTED)));
        assertEquals(2 * 128, back.materials.get(STEM), "the whole job's materials follow the tiles");
        assertEquals(3 * 1280 + 128, back.materials.get(CANDLE));
        assertEquals(1, back.substitutions.size());
        Job.Substitution s = back.substitutions.getFirst();
        assertEquals(STEM, BlockSpec.parse(s.from).id());
        assertEquals(CANDLE, BlockSpec.parse(s.to).id());
        assertEquals(128, s.count);
        assertEquals(3, s.mapColor);
        assertEquals(SameColourSwap.FEWER_ITEMS, s.reason);
        assertEquals(500, back.tile(STARTED).placed);
        assertEquals(TileStatus.PENDING, back.tile(FRESH).status);

        // nothing left to do: a second run changes nothing
        SubmissionPipeline.Resubstitution again = pipeline.resubstitute(job.id, true);
        assertTrue(again.ok(), again.problem());
        assertTrue(again.changed().isEmpty());
        assertEquals(1, reopened.get(job.id).orElseThrow().substitutions.size());
    }

    @Test
    void refusesUnknownFinishedAndFullyStartedJobs() {
        assertFalse(pipeline.resubstitute("j0099", false).ok());
        for (Job.TileState t : job.tiles) {
            t.status = TileStatus.STORED;
            t.placed = t.total;
        }
        store.save(job);
        SubmissionPipeline.Resubstitution r = pipeline.resubstitute(job.id, false);
        assertFalse(r.ok());
        assertTrue(r.problem().contains("no tile with nothing placed"), r.problem());
        job.status = io.github.digitalisp.mapart.model.JobStatus.DONE;
        store.save(job);
        assertTrue(pipeline.resubstitute(job.id, false).problem().contains("is DONE"));
    }
}
