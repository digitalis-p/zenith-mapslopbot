package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.impl.MapStore;
import io.github.digitalisp.mapart.bot.impl.ZenithMapView;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.BLACK;

/**
 * 2b2t, 2026-10-11: FILL_MAP held map 990 and the server sent 122 map packets for it, but ZenithProxy's MapDataCache was
 * full (the owner's framed maps; it skips new ids past {@code debug maxCachedMaps}) and kept none. CHECK_MAP failed 6 times
 * with "no map data received for map 990" and went BLOCKED; a reconnect brought the data back under the new id 0.
 * Now the plugin keeps its own copy of the map packets, CHECK_MAP recovers with one fresh map before it asks for a
 * reconnect, and the bot's maps are found again by name or pixels after their ids change.
 */
class MapDataRecoveryTest {
    private static final int LONG = 80_000;

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static void assertDone(Sim sim, Job job, RecordingLogger log) {
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), "job not done: " + sim.state() + tail(log));
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE, 2_000), "not idle after done: " + sim.state());
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 40), l.size()));
    }

    private static long lines(RecordingLogger log, Level level, String part) {
        return log.at(level).stream().filter(l -> l.contains(part)).count();
    }

    /**
     * The fake server sends every map it draws into {@code own} like vanilla: the whole picture in the first packet to
     * the holder, then only the rectangle of changed pixels (nothing changed: a packet without pixels). Returns the
     * count of partial patches sent, live.
     */
    private static AtomicInteger serverSends(Sim sim, MapStore own) {
        AtomicInteger patches = new AtomicInteger();
        Map<Integer, byte[]> sent = new HashMap<>();
        sim.maps.onDraw = (id, px) -> {
            boolean locked = sim.maps.locked.contains(id);
            byte[] before = sent.put(id, px.clone());
            if (before == null) {
                own.apply(id, locked, new MapStore.Patch(0, 0, 128, 128, px.clone()));
                return;
            }
            int minX = 128, minZ = 128, maxX = -1, maxZ = -1;
            for (int i = 0; i < px.length; i++) {
                if (px[i] == before[i]) continue;
                minX = Math.min(minX, i % 128);
                maxX = Math.max(maxX, i % 128);
                minZ = Math.min(minZ, i / 128);
                maxZ = Math.max(maxZ, i / 128);
            }
            if (maxX < 0) {
                own.apply(id, locked, null);
                return;
            }
            int cols = maxX - minX + 1, rows = maxZ - minZ + 1;
            byte[] patch = new byte[cols * rows];
            for (int j = 0; j < rows; j++)
                for (int i = 0; i < cols; i++) patch[i + j * cols] = px[minX + i + (minZ + j) * 128];
            own.apply(id, locked, new MapStore.Patch(minX, minZ, cols, rows, patch));
            patches.incrementAndGet();
        };
        return patches;
    }

    /**
     * A reconnect: bot ticks stop, the connection's state is dropped, and every carried map comes back under a new id
     * ({@code newIds}: old → new) with its picture, name and locked flag, in the order given; nothing is held.
     */
    static void reconnect(Sim sim, Map<Integer, Integer> newIds, List<Integer> order) {
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        Map<Integer, byte[]> pictures = new HashMap<>(sim.maps.colors);
        Map<Integer, String> names = new HashMap<>(sim.inv.mapNames);
        List<Integer> locked = new ArrayList<>(sim.maps.locked);
        sim.maps.colors.clear();
        sim.inv.mapNames.clear();
        sim.maps.locked.clear();
        sim.inv.filledMaps.clear();
        for (int old : order) {
            int id = newIds.get(old);
            sim.inv.filledMaps.add(id);
            if (locked.contains(old)) sim.maps.locked.add(id);
            if (names.containsKey(old)) sim.inv.mapNames.put(id, names.get(old));
            sim.maps.colors.put(id, pictures.get(old));
        }
        sim.maps.held = null;
    }

    @Test
    void theOwnCaptureHasTheMapWhileZenithProxysCacheIsFull() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        MapStore own = new MapStore(MapStore.CAPACITY, () -> sim.inv.filledMaps);
        AtomicInteger patches = serverSends(sim, own);
        // ZenithProxy's cache is full: it has nothing for any map
        sim.bot.mapView = new ZenithMapView(own, id -> null, () -> sim.maps.held);
        Job job = sim.addJob("j0301", "Captured", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        // one wrong block on the first map: CHECK_MAP sees it, VERIFY repairs it, the held map's patch fixes the pixel
        Pos victim = sim.moss.geometry().cell(20, 1);
        sim.bot.beforeCreateMap = () -> sim.world.set(victim, BlockSpec.of(BLACK));
        sim.start();
        assertDone(sim, job, log);

        assertEquals(1, sim.bot.createdMaps, "the map was checked from the own capture: no fresh map");
        assertTrue(patches.get() >= 1, "a partial patch reached the store");
        assertEquals(1, lines(log, Level.WARN, "mismatching pixels"), "the wrong pixel was seen" + tail(log));
        assertEquals(1, lines(log, Level.INFO, "matches the art"), tail(log));
        assertEquals(0, lines(log, Level.WARN, "no map data"), tail(log));
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertEquals(1, sim.output().maps.size());
        assertTrue(sim.output().maps.getFirst().locked());
        assertTrue(own.locked(sim.output().maps.getFirst().id()).orElseThrow(), "the locked copy's packet said locked");
    }

    @Test
    void withoutTheOwnCaptureTheSameFullCacheEndsInAReconnectRequest() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        MapStore own = new MapStore(MapStore.CAPACITY, () -> sim.inv.filledMaps); // never fed: the module was off
        sim.bot.mapView = new ZenithMapView(own, id -> null, () -> sim.maps.held);
        Job job = sim.addJob("j0302", "Uncaptured", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state() + tail(log));
        assertEquals(JobStatus.ACTIVE, job.status);
        assertEquals(2, sim.bot.createdMaps, "the fill and one fresh map");
        assertTrue(sim.engine.status().note().contains("Reconnect the bot"), sim.engine.status().note());
    }

    @Test
    void noDataForTheHeldMapMakesOneFreshMap() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.maps.blind = id -> id == 100; // the first map's packets never reach the bot
        Job job = sim.addJob("j0303", "Fresh", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertDone(sim, job, log);

        assertEquals(2, sim.bot.createdMaps, "the fill and one fresh map");
        assertEquals(EngineTuning.MISSING_DATA_HOLDS, sim.bot.heldMaps, "held twice, then the fresh map");
        assertEquals(0, lines(log, Level.WARN, "(attempt"), "no back-off retries for missing data" + tail(log));
        assertEquals(1, lines(log, Level.WARN, "making a fresh map"), tail(log));
        assertTrue(sim.notifier.has("info", "No map data"), sim.notifier.toString());
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
        assertEquals(1, sim.output().maps.size());
        assertNotEquals(100, sim.output().maps.getFirst().id());
        assertEquals("Fresh", sim.output().maps.getFirst().name());
        assertFalse(sim.maps.locked.contains(100), "the map without data is never locked");
        // after STORE the stale map goes into the scrap map chest (none configured: the map chest), never dropped
        assertFalse(sim.inv.filledMaps.contains(100), "not left in the inventory: " + sim.inv.filledMaps);
        assertTrue(sim.bot.containers.get(Sim.MAPS).maps.stream().anyMatch(m -> m.id() == 100 && !m.locked()),
            sim.bot.containers.get(Sim.MAPS).maps.toString());
        assertTrue(sim.bot.droppedItems.isEmpty(), sim.bot.droppedItems.toString());
    }

    @Test
    void noDataEvenForAFreshMapBlocksAskingForAReconnectAndAReconnectFixesIt() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.maps.blind = id -> true; // this connection never delivers map data
        Job job = sim.addJob("j0304", "Blind", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state() + tail(log));

        assertEquals(2, sim.bot.createdMaps, "one fresh map, no more");
        assertEquals(0, lines(log, Level.WARN, "(attempt"), "BLOCKED without six back-off retries" + tail(log));
        String note = sim.engine.status().note();
        assertTrue(note.startsWith("no map data received for map 101 (a fresh map"), note);
        assertTrue(note.contains("Reconnect the bot (ZenithProxy `disconnect`, then `connect`)"), note);
        List<SimFakes.RecordingNotifier.Msg> alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.notifier.toString());
        assertTrue(alerts.getFirst().body().contains("Reconnect the bot"), alerts.getFirst().body());

        // the owner reconnects: both maps come back under new ids with all their data, then `!map resume`
        sim.maps.blind = id -> false;
        reconnect(sim, Map.of(100, 7, 101, 0), List.of(100, 101));
        sim.engine.onBotTickStarting();
        sim.engine.resume();
        assertDone(sim, job, log);

        assertEquals(2, sim.bot.createdMaps, "no map made after the reconnect");
        assertEquals(1, sim.output().maps.size());
        assertEquals("Blind", sim.output().maps.getFirst().name());
        // the other unlocked map shows the tile's art (found by its pixels): a stale map of the tile, into the scrap
        // map chest after STORE (none configured: the map chest), unlocked, never dropped
        assertTrue(sim.inv.filledMaps.isEmpty(), "no stale map left: " + sim.inv.filledMaps);
        List<FakeBot.MapItem> scrapped = sim.bot.containers.get(Sim.MAPS).maps;
        assertEquals(1, scrapped.size(), scrapped.toString());
        assertFalse(scrapped.getFirst().locked());
    }

    @Test
    void afterAReconnectTheUnlockedMapIsFoundByItsPixelsNotByItsSlot() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        // the owner's own unlocked map of another place, carried by the account
        byte[] elsewhere = new byte[128 * 128];
        Arrays.fill(elsewhere, (byte) (11 << 2 | 1));
        sim.inv.filledMaps.add(8);
        sim.maps.colors.put(8, elsewhere);
        Job job = sim.addJob("j0305", "Again", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.LOCK, LONG), sim.state() + tail(log));
        assertEquals(List.of(8, 100), sim.inv.filledMaps);

        // reconnect before the lock: the bot's map 100 is now 0, and the old id 100 is the owner's map, last in slot order
        reconnect(sim, Map.of(100, 0, 8, 100), List.of(100, 8));
        sim.engine.onBotTickStarting();
        assertDone(sim, job, log);

        assertEquals(1, sim.bot.createdMaps, "the bot's map was found again: no new one");
        assertEquals(1, lines(log, Level.INFO, "map 0 found again by its pixels"), tail(log));
        assertEquals(1, sim.output().maps.size());
        assertEquals("Again", sim.output().maps.getFirst().name());
        assertTrue(sim.inv.filledMaps.contains(100), "the owner's map kept");
        assertFalse(sim.maps.locked.contains(100), "the owner's map not locked");
        assertFalse(sim.inv.mapNames.containsKey(100), "the owner's map not renamed");
        assertArrayEquals(elsewhere, sim.maps.colors.get(100), "the owner's map never held at the center");
    }

    @Test
    void afterAReconnectTheOldIdOfTheLockedMapBelongsToAnotherMap() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.inv.xpPoints = 7 * 30; // RENAME goes straight to the anvil
        Job job = sim.addJob("j0306", "Renamed", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.STORE, LONG), sim.state() + tail(log));
        int lockedId = sim.inv.filledMaps.getLast();
        assertEquals("Renamed", sim.inv.mapNames.get(lockedId));

        // reconnect: the bot's locked map is now 3; the old id is the owner's own locked map, unnamed, other pixels
        reconnect(sim, Map.of(lockedId, 3), List.of(lockedId));
        byte[] phis = new byte[128 * 128];
        Arrays.fill(phis, (byte) (29 << 2 | 1));
        sim.inv.filledMaps.add(lockedId);
        sim.maps.locked.add(lockedId);
        sim.maps.colors.put(lockedId, phis);
        sim.engine.onBotTickStarting();
        assertDone(sim, job, log);

        assertEquals(1, sim.output().maps.size());
        assertEquals(3, sim.output().maps.getFirst().id(), "the bot's map, found again by its name");
        assertEquals("Renamed", sim.output().maps.getFirst().name());
        assertEquals(1, lines(log, Level.INFO, "locked map 3 found again by its name"), tail(log));
        assertTrue(sim.inv.filledMaps.contains(lockedId), "the owner's map under the old id kept");
    }
}
