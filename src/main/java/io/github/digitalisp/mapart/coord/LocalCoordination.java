package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.store.JobStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bot behind the {@link Coordination} seam: jobs from the local queue (queue.json through the
 * {@link JobStore}), the Discord intake on, every claim granted at once, the stock in the store's {@link StockIndex}
 * (stock.json), reports dropped (the job file is the record). It logs nothing.
 *
 * <p>Every tile of the local queue is a unit ({@code <job>/<x>,<z>}, fence 0, attempt 1, nothing handed over), never
 * abandoned. kv keys other than {@code stock/...} live in memory for the run.
 */
public final class LocalCoordination implements Coordination {
    private final JobStore store;
    /** Versions of the {@code stock/} entries written in this run (a snapshot loaded from stock.json counts as 1). */
    private final Map<String, Long> stockVersions = new ConcurrentHashMap<>();
    /** Other kv keys, in memory. */
    private final Map<String, KvEntry> kv = new ConcurrentHashMap<>();

    public LocalCoordination(JobStore store) {
        this.store = store;
    }

    // ---- intake ----

    @Override
    public boolean discordIntake() {
        return true;
    }

    @Override
    public List<Job> jobs() {
        return store.list();
    }

    @Override
    public Optional<Job> job(String id) {
        return store.get(id);
    }

    @Override
    public Unit unit(Job job, Job.TileState tile) {
        return new Unit(Unit.tileKey(job.id, tile.x, tile.z), job.id, 0, 1, null, null);
    }

    @Override
    public @Nullable Abandon pollAbandon() {
        return null;
    }

    // ---- claims ----

    @Override
    public Claim claim(String key, Claim.Mode mode, boolean wait) {
        return new Granted(key, mode);
    }

    /** Held from the start; released by the holder. */
    private static final class Granted implements Claim {
        private final String key;
        private final Mode mode;
        private volatile State state = State.HELD;

        Granted(String key, Mode mode) {
            this.key = key;
            this.mode = mode;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Mode mode() {
            return mode;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public @Nullable String reason() {
            return null;
        }

        @Override
        public void release() {
            state = State.RELEASED;
        }
    }

    // ---- stock ----

    @Override
    public StockIndex stock() {
        return store.stock();
    }

    @Override
    public long stockVersion(Pos container) {
        Long v = stockVersions.get(container.key());
        if (v != null) return v;
        StockIndex st = store.stock();
        synchronized (st) {
            return st.containers.containsKey(container.key()) ? 1 : 0;
        }
    }

    /** {@link StockIndex#update} (the store saves it on its writer). */
    @Override
    public Pending<Long> putStock(StockEntry e, long expectedVersion) {
        String k = e.container().key();
        synchronized (stockVersions) {
            long cur = stockVersion(e.container());
            if (expectedVersion >= 0 && expectedVersion != cur) return Pending.failed("cas_failed");
            store.stock().update(e.container(), e.items(), e.freeSlots(), e.totalSlots(), e.scannedAt());
            stockVersions.put(k, cur + 1);
            return Pending.of(cur + 1);
        }
    }

    @Override
    public Pending<KvEntry> kvGet(String key) {
        Pos c = StockEntry.containerOf(key);
        if (c != null) {
            StockIndex st = store.stock();
            StockIndex.Snapshot s;
            synchronized (st) {
                s = st.containers.get(c.key());
            }
            JsonElement v = s == null ? null
                : new StockEntry(c, s.items, s.freeSlots, s.totalSlots, s.scannedAt).toJson();
            return Pending.of(new KvEntry(key, v, stockVersion(c)));
        }
        KvEntry e = kv.get(key);
        return Pending.of(e != null ? e : new KvEntry(key, null, 0));
    }

    @Override
    public Pending<KvEntry> kvPut(String key, @Nullable JsonElement value, long expectedVersion) {
        Pos c = StockEntry.containerOf(key);
        if (c != null) {
            if (value == null) {
                synchronized (stockVersions) {
                    long cur = stockVersion(c);
                    if (expectedVersion >= 0 && expectedVersion != cur) return Pending.failed("cas_failed");
                    StockIndex st = store.stock();
                    synchronized (st) {
                        st.containers.remove(c.key());
                    }
                    stockVersions.put(c.key(), cur + 1);
                    return Pending.of(new KvEntry(key, null, cur + 1));
                }
            }
            StockEntry e = StockEntry.fromJson(c, value);
            if (e == null) return Pending.failed("bad_message");
            return Pending.map(putStock(e, expectedVersion), v -> new KvEntry(key, value, v));
        }
        synchronized (kv) {
            KvEntry cur = kv.get(key);
            long ver = cur == null ? 0 : cur.version();
            boolean exists = cur != null && cur.value() != null;
            if (expectedVersion == 0 && exists) return Pending.failed("cas_failed");
            if (expectedVersion > 0 && expectedVersion != ver) return Pending.failed("cas_failed");
            KvEntry next = new KvEntry(key, value == null ? null : value.deepCopy(), ver + 1);
            kv.put(key, next);
            return Pending.of(next);
        }
    }

    // ---- reports: the job file is the record ----

    @Override
    public void progress(Unit unit, int done, int total, TileProgress data) { }

    @Override
    public void done(Unit unit, TileProgress result) { }

    @Override
    public void fail(Unit unit, String reason, boolean retry, @Nullable String detail) { }

    @Override
    public void release(Unit unit, String reason) { }
}
