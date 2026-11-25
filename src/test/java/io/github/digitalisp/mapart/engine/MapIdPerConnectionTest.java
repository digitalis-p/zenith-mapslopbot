package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.engine.SimFakes.RecordingNotifier.Msg;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2b2t, 2026-10-29: after each restart the "Map done" ids were low (#913, #766, #855, #857) and tiles 11,1 and 2,2 were
 * both "map #766", each in its own connection. 2b2t renumbers every map per connection, so each id was right: the
 * {@code map_id} component of the item checked, locked and stored, in the connection it was stored in. These tests pin
 * that the check, the lock and the "stored in" log line follow the carried item through a renumbering. The post names
 * no map id since 2026-11-03 (the owner: only this connection's, so of no use).
 */
class MapIdPerConnectionTest {
    private static final int LONG = 80_000;

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static List<Msg> mapDone(Sim sim) {
        return sim.notifier.messages.stream().filter(m -> m.title().startsWith("Map done")).toList();
    }

    /** A reconnect that gives the tile's unlocked map id {@code mapAs} and the next new map id {@code nextAs}. */
    private static void renumber(Sim sim, int mapAs, int nextAs) {
        Map<Integer, Integer> ids = new HashMap<>();
        List<Integer> order = new ArrayList<>(sim.inv.filledMaps);
        int other = 1;
        for (int id : order) ids.put(id, sim.maps.locked.contains(id) ? other++ : mapAs);
        MapDataRecoveryTest.reconnect(sim, ids, order);
        sim.maps.nextId = nextAs;
        sim.engine.onBotTickStarting();
    }

    private static List<String> storedLines(RecordingLogger log) {
        return log.lines.stream().filter(l -> l.contains(" stored in ")).toList();
    }

    @Test
    void thePostNamesNeitherTheJobNorTheMapIdAndTheLogBoth() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0533", "Plain", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        String body = mapDone(sim).getFirst().body();
        assertTrue(body.startsWith("Plain, tile 0,0 on moss\n1 of 1 map done"), body);
        assertFalse(body.contains("#"), body);
        int id = sim.output().maps.getFirst().id();
        List<String> stored = storedLines(log);
        assertEquals(1, stored.size(), log.lines.toString());
        assertTrue(stored.getFirst().contains("#j0533 Plain [0,0] stored in ")
            && stored.getFirst().endsWith(" as map #" + id + " of this connection"), stored.getFirst());
    }

    /**
     * As tiles 11,1 and 2,2: a restart before each tile's lock renumbers the carried map to 21 and the locked copy comes
     * out as 766 both times. The lock takes the item now numbered 21, which shows the tile's art; each post names the
     * locked item's id in its connection; two distinct maps, each of its own tile, are stored.
     */
    @Test
    void theSameIdInTwoConnectionsNamesTwoMapsEachRight() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        TileArt left = Sim.single(Sim.WHITE, 4);
        TileArt right = Sim.single(Sim.RED, 4);
        Job job = sim.addJob("j0531", "Twice", 2, 1, List.of("moss"), (x, z) -> x == 0 ? left : right);
        sim.start();
        for (int tile = 0; tile < 2; tile++) {
            int t = tile;
            // LOCK has started its lock (the menu takes a few ticks): no carried map is locked yet
            assertTrue(sim.runUntil(() -> sim.phase() == Phase.LOCK && sim.output().maps.size() == t
                && sim.inv.filledMaps.stream().noneMatch(sim.maps.locked::contains), LONG), sim.state());
            renumber(sim, 21, 766);
            int stored = t + 1;
            assertTrue(sim.runUntil(() -> sim.output().maps.size() == stored, LONG), sim.state());
        }
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        // each lock cut short by the reconnect is done again on the item now numbered 21
        assertEquals(2, sim.bot.count("lockMap 21"), sim.bot.history.toString());
        List<FakeBot.MapItem> out = sim.output().maps;
        assertEquals(2, out.size(), out.toString());
        assertEquals(766, out.get(0).id());
        assertEquals(766, out.get(1).id());
        assertTrue(out.get(0).locked() && out.get(1).locked());
        assertEquals("Twice 0,0", out.get(0).name());
        assertEquals("Twice 1,0", out.get(1).name());
        List<Msg> done = mapDone(sim);
        assertEquals(2, done.size(), sim.notifier.toString());
        for (Msg m : done) assertFalse(m.body().contains("map #"), m.body());
        List<String> stored = storedLines(log);
        assertEquals(2, stored.size(), log.lines.toString());
        for (String l : stored) assertTrue(l.endsWith(" as map #766 of this connection"), l);
    }

    /** As tile 11,1 at STORE: the locked map renumbered by a reconnect is stored and posted under its new id. */
    @Test
    void aLockedMapRenumberedBeforeStoreIsPostedUnderItsNewId() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0532", "Renumbered", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.STORE, LONG), sim.state());
        int before = sim.inv.filledMaps.getLast();
        byte[] picture = sim.maps.colors.get(before).clone();
        MapDataRecoveryTest.reconnect(sim, Map.of(before, 766), List.of(before));
        sim.engine.onBotTickStarting();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());

        assertEquals(1, sim.output().maps.size());
        assertEquals(766, sim.output().maps.getFirst().id(), "the item stored is the one now numbered 766");
        assertArrayEquals(picture, sim.maps.colors.get(766), "the same picture");
        String stored = storedLines(log).getFirst();
        assertTrue(stored.endsWith(" as map #766 of this connection"), stored);
        assertFalse(stored.contains("map #" + before + " "), "the old connection's id is not logged: " + stored);
    }
}
