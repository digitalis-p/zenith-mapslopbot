package io.github.digitalisp.mapart.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileJobStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private final List<String> logs = new CopyOnWriteArrayList<>();

    private FileJobStore store() {
        return new FileJobStore(dir, null, CLOCK, logs::add);
    }

    private static TileArt art(int seed) {
        return Tiles.of((x, z) -> (x + z + seed) % 5 == 0 ? null
            : (x + seed) % 3 == 0 ? "minecraft:oak_log[axis=y]" : "minecraft:" + List.of("red", "blue").get((z + seed) & 1) + "_carpet");
    }

    private static ParsedSubmission sub(int tilesX, int tilesZ) {
        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        for (int z = 0; z < tilesZ; z++)
            for (int x = 0; x < tilesX; x++) tiles.put(new TileCoord(x, z), art(x + 7 * z));
        return new ParsedSubmission("base_name", tilesX, tilesZ, 4189, tiles, List.of());
    }

    private static ValidationResult validation() {
        return new ValidationResult(List.of(), List.of(), Map.of("minecraft:red_carpet", 5, "minecraft:oak_log", 2), List.of("moss"));
    }

    private Job create(FileJobStore s) throws IOException {
        return s.create(sub(1, 1), validation(), "art", "123", "jared2013");
    }

    private List<Path> files(String glob) throws IOException {
        try (Stream<Path> w = Files.walk(dir)) {
            return w.filter(p -> p.getFileName().toString().matches(glob)).toList();
        }
    }

    @Test
    void createPersistsJobAndTilesAndSurvivesARestart() throws IOException {
        FileJobStore s = store();
        ParsedSubmission p = sub(3, 2);
        Job j = s.create(p, validation(), "  My\nArt §c ", "123", "jared2013");
        assertEquals("j0001", j.id);
        assertEquals("My Art c", j.name);
        assertEquals(1_760_000_000_000L, j.createdAt);
        assertEquals(JobStatus.QUEUED, j.status);
        assertEquals(List.of("moss"), j.platformIds);
        assertEquals(6, j.tiles.size());
        List<TileCoord> order = j.tiles.stream().map(Job.TileState::coord).toList();
        assertEquals(List.of(new TileCoord(0, 0), new TileCoord(1, 0), new TileCoord(2, 0),
            new TileCoord(0, 1), new TileCoord(1, 1), new TileCoord(2, 1)), order, "row-major build order");
        assertEquals(p.tiles().get(new TileCoord(2, 1)).nonEmptyCount(), j.tile(new TileCoord(2, 1)).total);
        assertTrue(Files.exists(dir.resolve("jobs/j0001/job.json")));
        assertTrue(Files.exists(dir.resolve("jobs/j0001/tile_2_1.json")));
        assertTrue(Files.exists(dir.resolve("queue.json")));

        j.status = JobStatus.ACTIVE;
        j.tiles.getFirst().status = TileStatus.STORED;
        j.tiles.getFirst().mapName = "art 0,0";
        s.save(j);

        FileJobStore again = store();
        Job k = again.get("j0001").orElseThrow();
        assertNotSame(j, k);
        assertEquals(JobStatus.ACTIVE, k.status);
        assertEquals("My Art c", k.name);
        assertEquals("123", k.submitterId);
        assertEquals("jared2013", k.submitterName);
        assertEquals(Map.of("minecraft:red_carpet", 5, "minecraft:oak_log", 2), k.materials);
        assertEquals(TileStatus.STORED, k.tiles.getFirst().status);
        assertEquals("art 0,0", k.tiles.getFirst().mapName);
        for (TileCoord c : p.tiles().keySet())
            assertTrue(Tiles.equal(p.tiles().get(c), again.loadTile("j0001", c)), "tile " + c);
        assertSame(k, again.get("j0001").orElseThrow(), "live shared instance");
        assertEquals(List.of(), logs);
    }

    @Test
    void blankDisplayNameFallsBackToTheBaseName() throws IOException {
        Job j = store().create(sub(1, 1), null, " \t", "terminal", "terminal");
        assertEquals("base_name", j.name);
        assertEquals(Map.of(), j.materials);
    }

    @Test
    void idsAreMonotonicAcrossRestartsAndDeletions() throws IOException {
        FileJobStore s = store();
        for (int i = 0; i < 3; i++) create(s);
        assertEquals("j0004", create(store()).id);

        deleteTree(dir.resolve("jobs/j0004"));
        deleteTree(dir.resolve("jobs/j0003"));
        assertEquals("j0005", create(store()).id, "counter persisted in queue.json");

        Files.delete(dir.resolve("queue.json"));
        FileJobStore rebuilt = store();
        assertEquals(List.of("j0001", "j0002", "j0005"), rebuilt.list().stream().map(j -> j.id).toList());
        assertEquals("j0006", create(rebuilt).id, "counter recovered from job directories");
    }

    @Test
    void writesAreAtomicAndLeaveNoTempFiles() throws IOException {
        FileJobStore s = store();
        Job j = create(s);
        for (int i = 0; i < 20; i++) {
            j.statusDetail = "step " + i;
            s.save(j);
        }
        assertEquals(List.of(), files(".*\\.tmp"));
        String json = Files.readString(dir.resolve("jobs/j0001/job.json"));
        assertTrue(json.contains("step 19"), json);

        Files.writeString(dir.resolve("queue.json.123.tmp"), "half written");
        Files.writeString(dir.resolve("jobs/j0001/job.json.456.tmp"), "half written");
        assertEquals("step 19", store().get("j0001").orElseThrow().statusDetail);
        assertEquals(List.of(), files(".*\\.tmp"), "stale temp files are cleaned on load");
    }

    @Test
    void corruptQueueIsMovedAsideAndRebuiltFromJobDirectories() throws IOException {
        FileJobStore s = store();
        create(s);
        create(s);
        Files.writeString(dir.resolve("queue.json"), "{\"nextSeq\": 3, \"jobs\": [\"j0001\",");
        FileJobStore again = store();
        assertEquals(List.of("j0001", "j0002"), again.list().stream().map(j -> j.id).toList());
        assertTrue(Files.exists(dir.resolve("queue.json.corrupt")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("corrupt") && l.contains("queue.json")), logs.toString());
        assertEquals("j0003", create(again).id);
        assertTrue(Files.readString(dir.resolve("queue.json")).contains("j0003"));
    }

    @Test
    void corruptJobIsSkippedAndKeptAside() throws IOException {
        FileJobStore s = store();
        create(s);
        create(s);
        create(s);
        Files.writeString(dir.resolve("jobs/j0002/job.json"), "not json at all {");
        FileJobStore again = store();
        assertEquals(List.of("j0001", "j0003"), again.list().stream().map(j -> j.id).toList());
        assertTrue(Files.exists(dir.resolve("jobs/j0002/job.json.corrupt")));
        assertEquals("j0004", create(again).id, "the corrupt job's id is never reused");

        Files.writeString(dir.resolve("jobs/j0003/job.json"), "");
        assertEquals(List.of("j0001", "j0004"), store().list().stream().map(j -> j.id).toList());
    }

    @Test
    void corruptCheckpointAndStockFallBackToDefaults() throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("checkpoint.json"), "[1,2");
        Files.writeString(dir.resolve("stock.json"), "{\"containers\": 5}");
        FileJobStore s = store();
        assertEquals("IDLE", s.loadCheckpoint().phase);
        assertTrue(s.stock().containers.isEmpty());
        assertTrue(Files.exists(dir.resolve("checkpoint.json.corrupt")));
        assertTrue(Files.exists(dir.resolve("stock.json.corrupt")));
        assertEquals(2, logs.size(), logs.toString());

        Files.writeString(dir.resolve("checkpoint.json"), "garbage");
        store().loadCheckpoint();
        assertTrue(files("checkpoint\\.json\\.\\d+\\.corrupt").size() == 1, "second corrupt copy gets a timestamped name");
    }

    @Test
    void concurrentCreatesGetUniqueIds() throws Exception {
        FileJobStore s = store();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++)
            futures.add(pool.submit(() -> {
                start.await();
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    ids.add(create(s).id);
                    s.list();
                    s.next();
                }
                return ids;
            }));
        start.countDown();
        List<String> all = new ArrayList<>();
        for (Future<List<String>> f : futures) all.addAll(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(40, all.size());
        assertEquals(40, all.stream().distinct().count());
        Collections.sort(all);
        assertEquals("j0001", all.getFirst());
        assertEquals("j0040", all.getLast());
        assertEquals(40, store().list().size());
    }

    @Test
    void moveReordersOnlyOpenJobs() throws IOException {
        FileJobStore s = store();
        for (int i = 0; i < 4; i++) create(s);
        Job done = s.get("j0002").orElseThrow();
        done.status = JobStatus.DONE;
        s.save(done);
        assertTrue(s.move("j0004", 0));
        assertEquals(List.of("j0004", "j0002", "j0001", "j0003"), s.list().stream().map(j -> j.id).toList());
        assertTrue(s.move("j0004", 99));
        assertEquals(List.of("j0001", "j0002", "j0003", "j0004"), s.list().stream().map(j -> j.id).toList());
        assertTrue(s.move("j0003", 0));
        assertFalse(s.move("j0002", 0), "terminal");
        assertFalse(s.move("j0099", 0), "unknown");
        assertEquals(List.of("j0003", "j0002", "j0001", "j0004"), store().list().stream().map(j -> j.id).toList(),
            "order persisted");
    }

    @Test
    void cancelAndNextFollowQueueOrder() throws IOException {
        FileJobStore s = store();
        for (int i = 0; i < 4; i++) create(s);
        assertEquals("j0001", s.next().orElseThrow().id);
        Job j3 = s.get("j0003").orElseThrow();
        j3.status = JobStatus.ACTIVE;
        s.save(j3);
        assertEquals("j0003", s.next().orElseThrow().id, "ACTIVE wins");
        j3.status = JobStatus.DONE;
        s.save(j3);
        Job j1 = s.get("j0001").orElseThrow();
        j1.status = JobStatus.WAITING_MATERIALS;
        s.save(j1);
        assertEquals("j0002", s.next().orElseThrow().id, "QUEUED before WAITING_MATERIALS");
        assertTrue(s.cancel("j0002"));
        assertFalse(s.cancel("j0002"));
        assertFalse(s.cancel("nope"));
        assertTrue(s.cancel("j0004"));
        assertEquals("j0001", s.next().orElseThrow().id);
        assertTrue(s.cancel("j0001"));
        assertTrue(s.next().isEmpty());
        assertEquals(JobStatus.CANCELLED, store().get("j0004").orElseThrow().status);
    }

    @Test
    void checkpointRoundTripReturnsCopies() {
        FileJobStore s = store();
        EngineCheckpoint idle = s.loadCheckpoint();
        assertNull(idle.jobId);
        EngineCheckpoint cp = new EngineCheckpoint();
        cp.jobId = "j0001";
        cp.tileX = 2;
        cp.tileZ = 1;
        cp.platformId = "moss";
        cp.phase = "BUILD";
        cp.phaseSince = 42;
        cp.clearAttempts = 1;
        cp.stored = true;
        cp.note = "n";
        s.saveCheckpoint(cp);
        cp.phase = "MUTATED";
        EngineCheckpoint a = s.loadCheckpoint();
        assertEquals("BUILD", a.phase);
        a.phase = "ALSO_MUTATED";
        assertEquals("BUILD", s.loadCheckpoint().phase);
        EngineCheckpoint b = store().loadCheckpoint();
        assertEquals("j0001", b.jobId);
        assertEquals(2, b.tileX);
        assertEquals(1, b.tileZ);
        assertEquals("moss", b.platformId);
        assertEquals(42, b.phaseSince);
        assertTrue(b.stored);
    }

    @Test
    void stockIsSharedAndPersisted() {
        FileJobStore s = store();
        StockIndex st = s.stock();
        assertSame(st, s.stock());
        st.update(new Pos(1, 64, -3), Map.of("minecraft:red_carpet", 640), 10, 27, 99);
        s.saveStock();
        StockIndex loaded = store().stock();
        assertEquals(640, loaded.total("minecraft:red_carpet"));
        assertEquals(List.of(new Pos(1, 64, -3)), loaded.containersWith("minecraft:red_carpet"));
        assertEquals(99, loaded.oldestScan());
    }

    @Test
    void nearLookTimesArePersisted() {
        FileJobStore s = store();
        StockIndex st = s.stock();
        String key = StockIndex.platformKey(-64, -64, 64);
        assertNull(st.nearSweptAt(key));
        assertEquals(Map.of(), st.nearLookedAt(key));
        st.nearSwept(key, 1_000L);
        st.nearLooked(key, List.of("minecraft:lime_carpet", "minecraft:glow_lichen"), 2_000L);
        s.saveStock();
        StockIndex loaded = store().stock();
        assertEquals(1_000L, loaded.nearSweptAt(key));
        assertEquals(Map.of("minecraft:glow_lichen", 2_000L, "minecraft:lime_carpet", 2_000L), loaded.nearLookedAt(key));
        assertNull(loaded.nearSweptAt(StockIndex.platformKey(64, -64, 64)), "per platform");
    }

    @Test
    void missingTilesAndBadIdsThrow() throws IOException {
        FileJobStore s = store();
        create(s);
        assertThrows(IOException.class, () -> s.loadTile("j0001", new TileCoord(5, 5)));
        assertThrows(IOException.class, () -> s.loadTile("../etc", new TileCoord(0, 0)));
        Files.writeString(dir.resolve("jobs/j0001/tile_0_0.json"), "{\"version\":1,\"palette\":[],\"cells\":\"AAAA\"}");
        assertThrows(IOException.class, () -> s.loadTile("j0001", new TileCoord(0, 0)));
        Job bad = new Job();
        bad.id = "../x";
        assertThrows(IllegalArgumentException.class, () -> s.save(bad));
    }

    @Test
    void tileCodecRoundTrip() throws IOException {
        TileArt t = new TileArt();
        t.set(0, 0, BlockSpec.parse("minecraft:oak_log[axis=y]"));
        t.set(127, 127, BlockSpec.parse("minecraft:glow_lichen[down=true,east=false]"));
        t.set(5, 9, BlockSpec.of("minecraft:white_carpet"));
        TileCodec.Stored stored = TileCodec.encode(t);
        assertEquals(List.of("minecraft:oak_log[axis=y]", "minecraft:white_carpet",
            "minecraft:glow_lichen[down=true,east=false]"), stored.palette);
        TileArt back = TileCodec.decode(stored);
        assertTrue(Tiles.equal(t, back));
        assertEquals(3, back.nonEmptyCount());
        assertEquals(Tiles.filled("minecraft:stone").blockCounts(),
            TileCodec.decode(TileCodec.encode(Tiles.filled("minecraft:stone"))).blockCounts());
    }

    private static void deleteTree(Path p) throws IOException {
        try (Stream<Path> w = Files.walk(p)) {
            for (Path f : w.sorted(Collections.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
