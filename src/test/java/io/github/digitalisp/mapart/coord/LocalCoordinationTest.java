package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.command.Fakes;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LocalCoordination} (today's single bot), {@link SharedStock} and {@link ClaimMenuGate}. */
class LocalCoordinationTest {
    private static final Pos CHEST = new Pos(-100, 61, 100);

    @Test
    void localIsTodaysSingleBot() {
        Fakes.Store store = new Fakes.Store();
        Job j = Fakes.job("j0001", "A", JobStatus.QUEUED, 2, 1, Map.of());
        store.add(j);
        LocalCoordination c = new LocalCoordination(store);
        assertTrue(c.discordIntake());
        assertEquals(List.of("j0001"), c.jobs().stream().map(x -> x.id).toList());
        assertSame(j, c.job("j0001").orElseThrow());
        Unit u = c.unit(j, j.tiles.get(1));
        assertEquals("j0001/1,0", u.key());
        assertEquals("j0001", u.job());
        assertEquals(1, u.attempt());
        assertFalse(u.resumable(), "a local unit never resumes another bot's build");
        assertNull(c.pollAbandon());
        assertSame(store.stock(), c.stock());
        // reports change nothing
        c.progress(u, 1, 2, new TileProgress(1, 2, "BUILD", "moss", false, false, false, false, 0, 0, 0, 1, null, ""));
        c.done(u, new TileProgress(2, 2, "FINISH_TILE", "moss", true, false, false, false, 0, 0, 0, 1, null, ""));
        c.fail(u, "x", false, null);
        c.release(u, "set_aside");
        assertEquals(0, store.saves);
    }

    @Test
    void localClaimsAreHeldAtOnce() {
        LocalCoordination c = new LocalCoordination(new Fakes.Store());
        for (String k : List.of("platform:moss", "chest:1,2,3", Claim.CARTOGRAPHY, Claim.ANVIL)) {
            Claim a = c.claim(k, Claim.Mode.EXCLUSIVE, true);
            Claim b = c.claim(k, Claim.Mode.EXCLUSIVE, true);
            assertTrue(a.held() && b.held(), "no contention with one bot: " + k);
            a.release();
            assertEquals(Claim.State.RELEASED, a.state());
            assertTrue(a.over());
        }
        assertEquals("platform:soul", Claim.platform("soul"));
        assertEquals("chest:1,2,3", Claim.chest(new Pos(1, 2, 3)));
    }

    @Test
    void localStockIsTheStockIndexWithVersions() {
        Fakes.Store store = new Fakes.Store();
        LocalCoordination c = new LocalCoordination(store);
        assertEquals(0, c.stockVersion(CHEST));
        StockEntry e = new StockEntry(CHEST, Map.of("minecraft:white_carpet", 64), 26, 27, 10L);
        Pending<Long> p = c.putStock(e, 0);
        assertTrue(p.done());
        assertEquals(1L, p.value());
        StockIndex.Snapshot s = store.stock().containers.get(CHEST.key());
        assertEquals(Map.of("minecraft:white_carpet", 64), s.items);
        assertEquals(26, s.freeSlots);
        assertEquals(10L, s.scannedAt);
        // compare-and-set: a stale version is refused, the entry stays
        Pending<Long> stale = c.putStock(new StockEntry(CHEST, Map.of(), 27, 27, 11L), 0);
        assertEquals("cas_failed", stale.error());
        assertEquals(10L, store.stock().containers.get(CHEST.key()).scannedAt);
        assertEquals(2L, c.putStock(new StockEntry(CHEST, Map.of(), 27, 27, 12L), 1).value());
        // the same entry through kv
        KvEntry kv = c.kvGet(StockEntry.key(CHEST)).value();
        assertEquals(2L, kv.version());
        assertEquals(12L, StockEntry.fromJson(CHEST, kv.value()).scannedAt());
        // a snapshot loaded from stock.json counts as version 1
        Pos other = new Pos(1, 2, 3);
        store.stock().update(other, Map.of("minecraft:map", 3), 1, 27, 5L);
        assertEquals(1, c.stockVersion(other));
    }

    @Test
    void localKvComparesAndSets() {
        LocalCoordination c = new LocalCoordination(new Fakes.Store());
        assertNull(c.kvGet("lane/1").value().value());
        KvEntry a = c.kvPut("lane/1", new JsonPrimitive("x"), 0).value();
        assertEquals(1, a.version());
        assertEquals("cas_failed", c.kvPut("lane/1", new JsonPrimitive("y"), 0).error(), "0 = must not exist");
        assertEquals("cas_failed", c.kvPut("lane/1", new JsonPrimitive("y"), 5).error());
        assertEquals(2, c.kvPut("lane/1", new JsonPrimitive("y"), 1).value().version());
        assertEquals(3, c.kvPut("lane/1", null, -1).value().version(), "-1 = unconditional; null deletes");
        assertNull(c.kvGet("lane/1").value().value());
    }

    /** A coordination whose stock writes complete when the test says, with the error it says. */
    private static class SlowStock implements Coordination {
        final LocalCoordination local = new LocalCoordination(new Fakes.Store());
        final Deque<Pending.Settable<Long>> writes = new ArrayDeque<>();
        final List<Long> expected = new ArrayList<>();
        long version;

        @Override public boolean discordIntake() { return false; }
        @Override public List<Job> jobs() { return List.of(); }
        @Override public Optional<Job> job(String id) { return Optional.empty(); }
        @Override public @Nullable Unit unit(Job job, Job.TileState tile) { return null; }
        @Override public @Nullable Abandon pollAbandon() { return null; }
        @Override public Claim claim(String key, Claim.Mode mode, boolean wait) { return local.claim(key, mode, wait); }
        @Override public StockIndex stock() { return local.stock(); }
        @Override public long stockVersion(Pos container) { return version; }

        @Override
        public Pending<Long> putStock(StockEntry entry, long expectedVersion) {
            expected.add(expectedVersion);
            Pending.Settable<Long> p = new Pending.Settable<>();
            writes.add(p);
            return p;
        }

        @Override public Pending<KvEntry> kvGet(String key) { return local.kvGet(key); }
        @Override public Pending<KvEntry> kvPut(String key, @Nullable JsonElement value, long v) { return local.kvPut(key, value, v); }
        @Override public void progress(Unit unit, int done, int total, TileProgress data) { }
        @Override public void done(Unit unit, TileProgress result) { }
        @Override public void fail(Unit unit, String reason, boolean retry, @Nullable String detail) { }
        @Override public void release(Unit unit, String reason) { }
    }

    @Test
    void sharedStockRetriesALostCompareAndSetWhileItsReadingIsNewer() {
        SlowStock c = new SlowStock();
        SharedStock s = new SharedStock(c);
        s.record(CHEST, Map.of("minecraft:white_carpet", 64), 26, 27, 100L);
        assertEquals(List.of(0L), c.expected);
        s.tick();
        assertEquals(1, s.inFlight(), "nothing settles until the answer comes");
        c.version = 4; // another bot wrote the entry meanwhile (an older reading of its own)
        c.local.stock().update(CHEST, Map.of(), 27, 27, 50L);
        c.writes.poll().fail("cas_failed");
        s.tick();
        assertEquals(List.of(0L, 4L), c.expected, "tried again on the version now there");
        c.writes.poll().complete(5L);
        s.tick();
        assertEquals(0, s.inFlight());
    }

    @Test
    void sharedStockDropsAnOlderReadingAndOtherErrors() {
        SlowStock c = new SlowStock();
        SharedStock s = new SharedStock(c);
        s.record(CHEST, Map.of(), 27, 27, 100L);
        c.local.stock().update(CHEST, Map.of("minecraft:map", 1), 26, 27, 200L); // a newer reading is in already
        c.writes.poll().fail("cas_failed");
        s.tick();
        assertEquals(1, c.expected.size(), "an older reading is not written over a newer one");
        assertEquals(0, s.inFlight());

        s.record(CHEST, Map.of(), 27, 27, 300L);
        c.writes.poll().fail("unavailable");
        s.tick();
        assertEquals(2, c.expected.size(), "no retry without a link: the next opening writes again");
        assertEquals(0, s.inFlight());

        for (int i = 0; i < SharedStock.MAX_TRIES + 2; i++) {
            if (i == 0) s.record(CHEST, Map.of(), 27, 27, 400L);
            Pending.Settable<Long> w = c.writes.poll();
            if (w == null) break;
            w.fail("cas_failed");
            s.tick();
        }
        assertEquals(2 + SharedStock.MAX_TRIES, c.expected.size(), "at most MAX_TRIES tries");
    }

    @Test
    void claimMenuGateHoldsUntilTheClaimIsHeldAndGivesItBack() {
        List<Claim> asked = new ArrayList<>();
        Claim.State[] start = {Claim.State.WAITING};
        Coordination c = new SlowStock() {
            @Override
            public Claim claim(String key, Claim.Mode mode, boolean wait) {
                MutableClaim m = new MutableClaim(key, start[0]);
                asked.add(m);
                return m;
            }
        };
        ClaimMenuGate gate = new ClaimMenuGate(c);
        assertFalse(gate.mayOpen("chest:1,2,3"));
        assertFalse(gate.mayOpen("chest:1,2,3"));
        assertEquals(1, asked.size(), "one claim, polled");
        ((MutableClaim) asked.getFirst()).state = Claim.State.HELD;
        assertTrue(gate.mayOpen("chest:1,2,3"));
        assertEquals(1, gate.open());
        gate.done("chest:1,2,3");
        assertEquals(Claim.State.RELEASED, asked.getFirst().state());
        assertEquals(0, gate.open());

        // a denied claim is asked for again on the next poll
        start[0] = Claim.State.DENIED;
        assertFalse(gate.mayOpen(Claim.ANVIL));
        start[0] = Claim.State.HELD;
        assertTrue(gate.mayOpen(Claim.ANVIL));
        assertEquals(3, asked.size());
        assertTrue(gate.mayOpen(Claim.CARTOGRAPHY));
        gate.releaseAll();
        assertTrue(asked.stream().allMatch(a -> a.state() == Claim.State.RELEASED || a.state() == Claim.State.DENIED));
        assertEquals(0, gate.open());
    }

    private static final class MutableClaim implements Claim {
        final String key;
        State state;

        MutableClaim(String key, State state) {
            this.key = key;
            this.state = state;
        }

        @Override public String key() { return key; }
        @Override public Mode mode() { return Mode.EXCLUSIVE; }
        @Override public State state() { return state; }
        @Override public @Nullable String reason() { return null; }
        @Override public void release() { state = State.RELEASED; }
    }
}
