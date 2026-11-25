package io.github.digitalisp.mapart.command;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.engine.EngineControl;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.store.EngineCheckpoint;
import io.github.digitalisp.mapart.store.JobStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** In-memory fakes for command and discord tests. */
public final class Fakes {
    private Fakes() { }

    public static Job job(String id, String name, JobStatus status, int tilesX, int tilesZ, Map<String, Integer> materials) {
        Job j = new Job();
        j.id = id;
        j.name = name;
        j.status = status;
        j.tilesX = tilesX;
        j.tilesZ = tilesZ;
        j.submitterName = "jared2013";
        j.submitterId = "1";
        j.materials = new LinkedHashMap<>(materials);
        j.platformIds = new ArrayList<>(List.of("moss"));
        for (int z = 0; z < tilesZ; z++) for (int x = 0; x < tilesX; x++) {
            Job.TileState t = new Job.TileState();
            t.x = x;
            t.z = z;
            j.tiles.add(t);
        }
        return j;
    }

    public static final class Store implements JobStore {
        public final List<Job> jobs = new ArrayList<>();
        public final StockIndex stock = new StockIndex();
        public int saves;
        public boolean failCreate;
        private int nextId = 1;

        public Store add(Job j) {
            jobs.add(j);
            return this;
        }

        @Override
        public synchronized Job create(ParsedSubmission s, ValidationResult v, String displayName, String submitterId,
                                       String submitterName) throws IOException {
            if (failCreate) throw new IOException("disk full");
            Job j = new Job();
            j.id = String.format("j%04d", nextId++);
            j.name = displayName;
            j.submitterId = submitterId;
            j.submitterName = submitterName;
            j.tilesX = s.tilesX();
            j.tilesZ = s.tilesZ();
            j.materials = new LinkedHashMap<>(v.materials());
            j.platformIds = new ArrayList<>(v.platformIds());
            for (TileCoord c : s.tiles().keySet()) {
                Job.TileState t = new Job.TileState();
                t.x = c.x();
                t.z = c.z();
                t.total = s.tiles().get(c).nonEmptyCount();
                j.tiles.add(t);
            }
            jobs.add(j);
            return j;
        }

        @Override
        public synchronized List<Job> list() {
            return new ArrayList<>(jobs);
        }

        @Override
        public synchronized Optional<Job> get(String id) {
            return jobs.stream().filter(j -> j.id.equals(id)).findFirst();
        }

        @Override
        public synchronized void save(Job job) {
            saves++;
        }

        @Override
        public TileArt loadTile(String jobId, TileCoord coord) throws IOException {
            throw new IOException("not stored");
        }

        @Override
        public synchronized boolean cancel(String id) {
            Optional<Job> j = get(id);
            if (j.isEmpty() || j.get().status.terminal()) return false;
            j.get().status = JobStatus.CANCELLED;
            return true;
        }

        @Override
        public synchronized boolean move(String id, int index) {
            Optional<Job> o = get(id);
            if (o.isEmpty() || o.get().status.terminal() || index < 0) return false;
            Job j = o.get();
            jobs.remove(j);
            int seen = 0;
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).status.terminal()) continue;
                if (seen++ == index) {
                    jobs.add(i, j);
                    return true;
                }
            }
            jobs.add(j);
            return true;
        }

        @Override
        public synchronized Optional<Job> next() {
            for (JobStatus s : List.of(JobStatus.ACTIVE, JobStatus.QUEUED, JobStatus.WAITING_MATERIALS))
                for (Job j : jobs) if (j.status == s) return Optional.of(j);
            return Optional.empty();
        }

        @Override
        public EngineCheckpoint loadCheckpoint() {
            return new EngineCheckpoint();
        }

        @Override
        public void saveCheckpoint(EngineCheckpoint checkpoint) { }

        @Override
        public StockIndex stock() {
            return stock;
        }

        @Override
        public void saveStock() { }
    }

    public static final class Engine implements EngineControl {
        public EngineStatus status = new EngineStatus(true, false, "", Phase.IDLE, null, null, 0, 0, null, 0, 0, "", 0);
        public final List<String> calls = new ArrayList<>();

        @Override
        public void pause(String reason) {
            calls.add("pause:" + reason);
        }

        @Override
        public void emergencyStop(String reason) {
            calls.add("stop:" + reason);
        }

        @Override
        public void resume() {
            calls.add("resume");
        }

        @Override
        public EngineStatus status() {
            return status;
        }

        @Override
        public void requestStockScan() {
            calls.add("scan");
        }

        @Override
        public void queueChanged() {
            calls.add("queueChanged");
        }
    }

    /** Catalog stub: everything known, map colour 1. */
    public static final class Catalog implements BlockCatalog {
        @Override public boolean isKnownBlock(String blockId) { return true; }
        @Override public boolean isKnownItem(String itemId) { return true; }
        @Override public Optional<String> placementItem(BlockSpec spec) { return Optional.of(spec.id()); }
        @Override public int mapColorId(BlockSpec spec) { return 1; }
        @Override public boolean placeableOnTopFace(BlockSpec spec) { return true; }
        @Override public boolean matches(BlockSpec target, BlockSpec actual) {
            return target == null || target.isAir() ? actual.isAir() : target.equals(actual);
        }
        @Override public boolean isGravityBlock(String blockId) { return false; }
        @Override public int maxStackSize(String itemId) { return 64; }
    }

    /** A tilesX x tilesZ submission whose tiles each hold {@code pixelsPerTile} white carpets. */
    public static ParsedSubmission parsed(String baseName, int tilesX, int tilesZ, int pixelsPerTile) {
        TreeMap<TileCoord, TileArt> tiles = new TreeMap<>();
        for (int z = 0; z < tilesZ; z++) for (int x = 0; x < tilesX; x++) {
            TileArt t = new TileArt();
            for (int i = 0; i < pixelsPerTile; i++) t.set(i % TileArt.SIZE, i / TileArt.SIZE, BlockSpec.of("white_carpet"));
            tiles.put(new TileCoord(x, z), t);
        }
        return new ParsedSubmission(baseName, tilesX, tilesZ, 4189, tiles, List.of());
    }

    public static Map<String, Integer> materials(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }
}
