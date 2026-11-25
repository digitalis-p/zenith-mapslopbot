package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The re-sync around a setback spot ({@link ResyncRun}) against a fake world and server: the server answers each
 * off-hand click with the clicked block and the one above it, as vanilla's {@code handleUseItemOn} does; a click only
 * counts when the rotation sent on the tick before (and held on its own tick) is the look at that block.
 */
class ResyncRunTest {
    private static final Pos SPOT = new Pos(-60, 173, -4);
    private static final String AIR = "minecraft:air";

    /** The cache (what the bot sees) and the server (the truth); a click copies the server's two blocks over. */
    private static final class FakeServer implements ResyncRun.Port {
        final Map<Pos, String> cache = new HashMap<>();
        final Map<Pos, String> server = new HashMap<>();
        final List<Pos> clicks = new ArrayList<>();
        final List<Long> clickTicks = new ArrayList<>();
        /** Look per tick: {yaw, pitch}; a tick without one held still. */
        final Map<Long, float[]> looks = new HashMap<>();
        final Set<Pos> updated = new HashSet<>();
        final Set<Pos> hidden = new HashSet<>();
        final Map<Pos, String> skips = new HashMap<>();
        @Nullable String offhand;
        boolean menu;
        boolean executes = true;
        int local;
        int still;
        long tick;

        FakeServer() {
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    Pos floor = new Pos(SPOT.x() + dx, SPOT.y() - 1, SPOT.z() + dz);
                    put(floor, "minecraft:moss_block");
                    put(floor.above(), "minecraft:gray_carpet");
                }
        }

        void put(Pos p, String b) {
            cache.put(p, b);
            server.put(p, b);
        }

        /** The fake rotation that looks at {@code c}. */
        static float[] rotationOf(Pos c) {
            return new float[]{c.x(), c.z()};
        }

        @Override public void resyncLocal() { local++; }
        @Override public void holdStill() { still++; }
        @Override public void look(float yaw, float pitch) { looks.put(tick, new float[]{yaw, pitch}); }
        @Override public boolean containerOpen() { return menu; }
        @Override public @Nullable String offhandProblem() { return offhand; }
        @Override public boolean air(Pos p) { return AIR.equals(block(p)); }
        @Override public @Nullable String skip(Pos c) { return skips.get(c); }
        @Override public float @Nullable [] aim(Pos c) { return hidden.contains(c) ? null : rotationOf(c); }
        @Override public boolean lookExecuted() { return executes && looks.containsKey(tick - 1) && looks.containsKey(tick); }
        @Override public String block(Pos p) { return cache.getOrDefault(p, AIR); }
        @Override public long mark() { return 0; }
        @Override public boolean updatedSince(Pos p, long mark) { return updated.contains(p); }

        @Override
        public boolean click(Pos c) {
            float[] want = rotationOf(c);
            float[] before = looks.get(tick - 1), now = looks.get(tick);
            if (before == null || now == null || before[0] != want[0] || before[1] != want[1] || now[0] != want[0]
                || now[1] != want[1]) return false;
            clicks.add(c);
            clickTicks.add(tick);
            for (Pos p : List.of(c, c.above())) {
                cache.put(p, server.getOrDefault(p, AIR));
                updated.add(p);
            }
            return true;
        }
    }

    private static TaskStatus run(ResyncRun r, FakeServer s, int maxTicks) {
        TaskStatus st = TaskStatus.RUNNING;
        for (int i = 0; i < maxTicks && st == TaskStatus.RUNNING; i++) {
            s.tick++;
            st = r.tick(s);
        }
        return st;
    }

    @Test
    void looksThenClicksEachSurfaceAroundTheSpotNearestFirstAndFindsTheGhost() {
        FakeServer s = new FakeServer();
        Pos ghost = new Pos(SPOT.x() + 1, SPOT.y(), SPOT.z()); // the cache shows a full block, the server a carpet
        s.cache.put(ghost, "minecraft:cyan_terracotta");
        ResyncRun r = new ResyncRun(SPOT);

        TaskStatus st = run(r, s, 300);

        assertEquals(TaskStatus.SUCCESS, st);
        assertEquals(1, s.local, "the local resync first");
        assertEquals(25, s.clicks.size(), r.summary());
        assertEquals(SPOT, s.clicks.getFirst(), "the carpet the bot stands on first");
        for (Pos c : s.clicks) {
            assertEquals(SPOT.y(), c.y(), "the walking surface (carpets): " + c);
            assertTrue(Math.abs(c.x() - SPOT.x()) <= 2 && Math.abs(c.z() - SPOT.z()) <= 2, c.toString());
        }
        for (int i = 1; i < s.clickTicks.size(); i++)
            assertEquals(s.clickTicks.get(i - 1) + 2, s.clickTicks.get(i), "a look tick, then the click tick");
        for (int i = 0; i < s.clicks.size(); i++) {
            long t = s.clickTicks.get(i);
            assertArrayEquals(FakeServer.rotationOf(s.clicks.get(i)), s.looks.get(t - 1), "looked at it the tick before");
            assertArrayEquals(FakeServer.rotationOf(s.clicks.get(i)), s.looks.get(t), "and held the look on the click");
        }
        assertEquals(1 + 2 * 25 + ResyncRun.SETTLE_TICKS + 1, s.tick, "local resync, look + click each, the settle");
        assertEquals("minecraft:gray_carpet", s.cache.get(ghost), "the server's answer replaced the ghost");
        assertEquals("resync at " + SPOT.key() + ": re-requested 25 blocks and the cells above them (25 answered); "
            + "changed: " + ghost.key() + " minecraft:cyan_terracotta -> minecraft:gray_carpet", r.summary());
    }

    @Test
    void theSurfaceIsTheFirstBlockFromTheFeetLevelDown() {
        FakeServer s = new FakeServer();
        Pos hole = new Pos(SPOT.x() + 2, SPOT.y(), SPOT.z() + 2);
        s.put(hole, AIR);                                                     // empty art cell: the floor below
        Pos pit = new Pos(SPOT.x() - 2, SPOT.y(), SPOT.z() - 2);
        for (int d = 0; d <= ResyncRun.DEPTH; d++) s.put(pit.offset(0, -d, 0), AIR); // a drop hole
        ResyncRun r = new ResyncRun(SPOT);
        assertEquals(TaskStatus.SUCCESS, run(r, s, 300));
        assertTrue(s.clicks.contains(hole.below()), "the floor under an empty cell: " + s.clicks);
        assertFalse(s.clicks.stream().anyMatch(c -> c.x() == pit.x() && c.z() == pit.z()), "nothing in the pit");
        assertEquals(24, s.clicks.size());
        assertTrue(r.summary().contains("1 skipped (first: " + pit.key() + " (no block within 2 below))"), r.summary());
    }

    @Test
    void nothingChangedWhenTheCacheMatches() {
        FakeServer s = new FakeServer();
        ResyncRun r = new ResyncRun(SPOT);
        assertEquals(TaskStatus.SUCCESS, run(r, s, 300));
        assertTrue(r.summary().endsWith("(25 answered); nothing changed: the cached blocks matched the server's"),
            r.summary());
    }

    @Test
    void noClickWithAnOffhandThatCouldActOnABlock() {
        FakeServer s = new FakeServer();
        s.offhand = "the off-hand holds minecraft:flint_and_steel";
        ResyncRun r = new ResyncRun(SPOT);
        assertEquals(TaskStatus.SUCCESS, run(r, s, 300));
        assertTrue(s.clicks.isEmpty(), s.clicks.toString());
        assertTrue(s.looks.isEmpty(), "no look either");
        assertEquals(1, s.local, "the local resync still runs");
        assertEquals("resync at " + SPOT.key() + ": no block re-requested (the off-hand holds minecraft:flint_and_steel);"
            + " movement state and block predictions resynced only", r.summary());
    }

    @Test
    void skippedHiddenAndUnexecutedLooksAreNotClicked() {
        FakeServer s = new FakeServer();
        Pos chest = SPOT.offset(2, 0, 2);
        s.skips.put(chest, "a block entity: minecraft:chest");
        Pos hidden = SPOT.offset(-2, 0, 2);
        s.hidden.add(hidden);
        s.executes = false; // another module owns the input: the first look is never executed
        ResyncRun r = new ResyncRun(SPOT);
        run(r, s, 2 + ResyncRun.LOOK_WAIT_TICKS + 1);
        assertTrue(s.clicks.isEmpty(), "no click before the look was executed");
        s.executes = true;
        assertEquals(TaskStatus.SUCCESS, run(r, s, 300));
        assertEquals(22, s.clicks.size(), r.summary());
        assertFalse(s.clicks.contains(chest) || s.clicks.contains(hidden) || s.clicks.contains(SPOT), s.clicks.toString());
        assertTrue(r.summary().contains("3 skipped (first: " + SPOT.key() + " (the look was not executed))"), r.summary());
    }

    @Test
    void failsWithAMenuOpen() {
        FakeServer s = new FakeServer();
        s.menu = true;
        ResyncRun r = new ResyncRun(SPOT);
        assertEquals(TaskStatus.FAILED, run(r, s, 10));
        assertEquals("a container menu is open", r.failure());
        assertTrue(s.clicks.isEmpty());
    }
}
