package io.github.digitalisp.mapart.engine;

import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.coord.Abandon;
import io.github.digitalisp.mapart.coord.Claim;
import io.github.digitalisp.mapart.coord.Coordination;
import io.github.digitalisp.mapart.coord.KvEntry;
import io.github.digitalisp.mapart.coord.LocalCoordination;
import io.github.digitalisp.mapart.coord.Pending;
import io.github.digitalisp.mapart.coord.StockEntry;
import io.github.digitalisp.mapart.coord.TileProgress;
import io.github.digitalisp.mapart.coord.TileUnitSpec;
import io.github.digitalisp.mapart.coord.Unit;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.store.JobStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A scripted coordination: units granted by the test ({@link #grant}), taken back by
 * {@link #revoke} / {@link #loseLease} (queued for {@link #pollAbandon}),
 * every claim and report recorded. Jobs and the stock come from the store, like {@link LocalCoordination}.
 */
final class FakeCoordination implements Coordination {
    final JobStore store;
    private final LocalCoordination local;
    /** "job|job/x,z" → the unit this bot holds. */
    final Map<String, Unit> granted = new LinkedHashMap<>();
    final Deque<Abandon> abandons = new ArrayDeque<>();
    /** Every claim asked for, in order. */
    final List<FakeClaim> claims = new ArrayList<>();
    /** State new platform claims start in (WAITING: another bot holds the platform). */
    Claim.State platformStart = Claim.State.HELD;
    /** Every report, in order: "progress tile:0,0 123/1024 BUILD", "done tile:0,0", "release tile:0,0 set_aside", "fail ...". */
    final List<String> reports = new ArrayList<>();
    final List<TileProgress> progress = new ArrayList<>();
    /** The clock's millis at each progress report. */
    final List<Long> progressAt = new ArrayList<>();
    java.util.function.LongSupplier now = () -> 0;
    @Nullable TileProgress doneWith;
    boolean intake;
    private long fences = 100;

    FakeCoordination(JobStore store) {
        this.store = store;
        this.local = new LocalCoordination(store);
    }

    /** Grants the tile ({@code attempt}, previous holders' {@code handed} progress) with a fresh fence. */
    Unit grant(Job job, int x, int z, int attempt, @Nullable TileProgress handed, @Nullable TileUnitSpec spec) {
        Unit u = new Unit(Unit.tileKey(job.id, x, z), job.id, ++fences, attempt, spec, handed);
        granted.put(job.id + "|" + u.key(), u);
        return u;
    }

    Unit grant(Job job, int x, int z) {
        return grant(job, x, z, 1, null, null);
    }

    /** The unit is taken back. */
    void revoke(Unit u) {
        granted.remove(u.job() + "|" + u.key());
        abandons.add(new Abandon(u, Abandon.Reason.REVOKED, "test"));
    }

    /** The lease ran out. */
    void loseLease(Unit u) {
        granted.remove(u.job() + "|" + u.key());
        abandons.add(new Abandon(u, Abandon.Reason.LEASE_LOST, ""));
    }

    @Nullable TileProgress lastProgress() {
        return progress.isEmpty() ? null : progress.getLast();
    }

    List<FakeClaim> claims(String prefix) {
        return claims.stream().filter(c -> c.key.startsWith(prefix)).toList();
    }

    List<FakeClaim> held() {
        return claims.stream().filter(c -> c.state == Claim.State.HELD || c.state == Claim.State.WAITING).toList();
    }

    // ---- Coordination ----

    @Override public boolean discordIntake() { return intake; }
    @Override public List<Job> jobs() { return store.list(); }
    @Override public Optional<Job> job(String id) { return store.get(id); }

    @Override
    public @Nullable Unit unit(Job job, Job.TileState tile) {
        return granted.get(job.id + "|" + Unit.tileKey(job.id, tile.x, tile.z));
    }

    @Override public @Nullable Abandon pollAbandon() { return abandons.poll(); }

    @Override
    public Claim claim(String key, Claim.Mode mode, boolean wait) {
        FakeClaim c = new FakeClaim(key, mode, key.startsWith("platform:") ? platformStart : Claim.State.HELD);
        claims.add(c);
        return c;
    }

    @Override public StockIndex stock() { return local.stock(); }
    @Override public long stockVersion(Pos container) { return local.stockVersion(container); }
    @Override public Pending<Long> putStock(StockEntry entry, long expectedVersion) { return local.putStock(entry, expectedVersion); }
    @Override public Pending<KvEntry> kvGet(String key) { return local.kvGet(key); }

    @Override
    public Pending<KvEntry> kvPut(String key, @Nullable JsonElement value, long expectedVersion) {
        return local.kvPut(key, value, expectedVersion);
    }

    @Override
    public void progress(Unit unit, int done, int total, TileProgress data) {
        reports.add("progress " + unit.key() + " " + done + "/" + total + " " + data.phase());
        progressAt.add(now.getAsLong());
        progress.add(data);
    }

    @Override
    public void done(Unit unit, TileProgress result) {
        reports.add("done " + unit.key());
        doneWith = result;
        granted.remove(unit.job() + "|" + unit.key());
    }

    @Override
    public void fail(Unit unit, String reason, boolean retry, @Nullable String detail) {
        reports.add("fail " + unit.key() + " " + reason);
    }

    @Override
    public void release(Unit unit, String reason) {
        reports.add("release " + unit.key() + " " + reason);
        granted.remove(unit.job() + "|" + unit.key()); // back to the coordinator, to be granted again
    }

    static final class FakeClaim implements Claim {
        final String key;
        final Mode mode;
        volatile State state;
        @Nullable String reason;

        FakeClaim(String key, Mode mode, State state) {
            this.key = key;
            this.mode = mode;
            this.state = state;
        }

        @Override public String key() { return key; }
        @Override public Mode mode() { return mode; }
        @Override public State state() { return state; }
        @Override public @Nullable String reason() { return reason; }

        @Override
        public void release() {
            state = State.RELEASED;
        }

        @Override
        public String toString() {
            return key + "=" + state;
        }
    }
}
