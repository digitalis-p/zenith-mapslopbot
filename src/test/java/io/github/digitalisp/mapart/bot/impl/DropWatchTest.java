package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.DisconnectDrop;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DropWatch}: what a disconnect may have dropped, and which stack on the ground to pick up next. */
class DropWatchTest {
    private static final Pos FEET = new Pos(-40, 64, 100);
    private static final String ROCKET = "minecraft:firework_rocket";
    private static final String MAP = "minecraft:filled_map";
    private static final ToIntFunction<String> NONE = id -> 0;

    @Test
    void risksAreTheLiveCursorAndATablesInputs() {
        assertEquals(List.of(new DropWatch.Risk(ROCKET, 64, "on the cursor")),
            DropWatch.risks(ROCKET, 64, true, null, List.of()));
        // a stale cursor after a close (ZenithProxy keeps it; the server put it back): not at risk
        assertTrue(DropWatch.risks(ROCKET, 64, false, null, List.of()).isEmpty());
        assertTrue(DropWatch.risks(null, 0, true, null, List.of()).isEmpty());
        DropWatch.Risk map = new DropWatch.Risk(MAP, 1, "in the cartography table");
        DropWatch.Risk none = new DropWatch.Risk(null, 0, "in the cartography table");
        assertEquals(List.of(map), DropWatch.risks(null, 0, true, "cartography", List.of(map, none)));
        assertTrue(DropWatch.risks(null, 0, true, null, List.of(map)).isEmpty(), "no such menu open");
    }

    @Test
    void aStackSeenJustBeforeTheDisconnectCounts() {
        DropWatch w = new DropWatch();
        DropWatch.Risk r = new DropWatch.Risk(ROCKET, 64, "on the cursor");
        w.look(List.of(r), NONE, FEET, 1_000);
        w.look(List.of(), NONE, FEET, 1_050); // the put-down was sent: it may not have reached the server
        w.disconnected(1_100);
        assertEquals(List.of(new DisconnectDrop(ROCKET, 64, FEET, "on the cursor", false, 0)), w.take());
        assertTrue(w.take().isEmpty(), "taken once");
    }

    @Test
    void aStackStillThereInTheLastLookIsLast() {
        DropWatch w = new DropWatch();
        DropWatch.Risk r = new DropWatch.Risk(MAP, 1, "in the anvil");
        w.look(List.of(r), NONE, FEET, 1_000);
        w.disconnected(1_020);
        assertEquals(List.of(new DisconnectDrop(MAP, 1, FEET, "in the anvil", true, 0)), w.take());
    }

    /**
     * Local drill 2026-10-25 (10x): the lock's and the rename's map were cut off 5 ms after the click that put them into
     * the table and the anvil, and were in the inventory after the reconnect (the click had not reached the server, or
     * the server picked the map up again at the rejoin); the pickup called them lost. Each look notes how many of the
     * item the inventory held besides the stack (ZenithProxy's cache, the click already applied); that many plus the
     * stack in the inventory after the reconnect means nothing dropped.
     */
    @Test
    void aStackBackInTheInventoryAfterTheReconnectWasNotDropped() {
        DropWatch w = new DropWatch();
        // the top-up: 64 rockets taken from the chest onto the cursor, 128 in the inventory already
        w.look(List.of(new DropWatch.Risk(ROCKET, 64, "on the cursor")), id -> ROCKET.equals(id) ? 128 : 0, FEET, 1_000);
        // the put-down sent: the cache has 192 in the inventory, nothing at risk
        w.look(List.of(), id -> ROCKET.equals(id) ? 192 : 0, FEET, 1_050);
        w.disconnected(1_060);
        DisconnectDrop d = w.take().getFirst();
        assertEquals(128, d.inventory(), "what the inventory held besides the stack in the look that saw it");
        assertTrue(DropWatch.back(d, 192), "both clicks reached the server: put down, not dropped");
        assertFalse(DropWatch.back(d, 128), "the put-down was cut off: on the ground, or still in the chest");
        // the lock's map: the only one, in the table's slot 0 when the connection ended
        DisconnectDrop map = new DisconnectDrop(MAP, 1, FEET, "in the cartography table", true, 0);
        assertTrue(DropWatch.back(map, 1));
        assertFalse(DropWatch.back(map, 0));
        assertFalse(DropWatch.back(new DisconnectDrop(MAP, 1, FEET, "in the anvil", true, -1), 5), "count not known");
    }

    /**
     * Local drill 2026-10-25 (10x): after the lock's last SWAP (the result into the hotbar) ZenithProxy's cache has the
     * locked map in the inventory and the old one still in the table's slot 0 until the server's update; a cut then
     * would want two maps back. The table's input keeps the least count of the looks it stayed in; a cursor stack the
     * count of its own look (the next one on the cursor is another stack).
     */
    @Test
    void aTablesInputKeepsTheLeastInventoryCountOfItsLooks() {
        DropWatch w = new DropWatch();
        DropWatch.Risk map = new DropWatch.Risk(MAP, 1, "in the cartography table");
        w.look(List.of(map), id -> 0, FEET, 1_000);     // the map into slot 0
        w.look(List.of(map), id -> 0, FEET, 1_100);     // the pane into slot 1
        w.look(List.of(map), id -> 1, FEET, 1_200);     // the result taken; slot 0 not emptied in the cache yet
        w.disconnected(1_210);
        DisconnectDrop d = w.take().getFirst();
        assertEquals(0, d.inventory());
        assertTrue(DropWatch.back(d, 1), "the locked map in the inventory: nothing dropped");
        // a cursor: 64 rockets from the chest, put down and the next 64 taken within one tick
        DropWatch.Risk rockets = new DropWatch.Risk(ROCKET, 64, "on the cursor");
        w.look(List.of(rockets), id -> 128, FEET, 2_000);
        w.look(List.of(rockets), id -> 192, FEET, 2_050);
        w.disconnected(2_060);
        DisconnectDrop c = w.take().getFirst();
        assertEquals(192, c.inventory());
        assertFalse(DropWatch.back(c, 192), "the second stack dropped: looked for on the ground");
        // the input gone for a look and back (another lock): the count starts again
        w.look(List.of(map), id -> 0, FEET, 3_000);
        w.look(List.of(), id -> 1, FEET, 3_100);
        w.look(List.of(map), id -> 1, FEET, 3_200);
        w.disconnected(3_210);
        assertEquals(1, w.take().getFirst().inventory());
    }

    /** Within reach: the server hands a stack over within about 1.4 blocks of the bot's centre on each axis. */
    @Test
    void reachIsABlockOnEachAxisFromTheBotsCentre() {
        assertTrue(DropWatch.inReach(10.5, 64, 10.5, 11.4, 64, 9.6));
        assertTrue(DropWatch.inReach(10.5, 64, 10.5, 10.6, 65, 9.5), "on top of the block beside the feet (an anvil)");
        assertFalse(DropWatch.inReach(10.5, 64, 10.0, 10.6, 65, 8.2), "where the walk beside it ended: 1.8 off");
        assertFalse(DropWatch.inReach(10.5, 64, 10.5, 10.5, 62, 10.5), "two blocks under the feet");
        assertFalse(DropWatch.inReach(10.5, 64, 10.5, 10.5, 66.5, 10.5), "over the head");
    }

    @Test
    void oldOrStaleLooksCountNothing() {
        DropWatch w = new DropWatch();
        DropWatch.Risk r = new DropWatch.Risk(ROCKET, 64, "on the cursor");
        // seen more than IN_FLIGHT_MS before the last look: its clicks have long arrived
        w.look(List.of(r), NONE, FEET, 1_000);
        w.look(List.of(), NONE, FEET, 1_000 + DropWatch.IN_FLIGHT_MS + 50);
        w.disconnected(1_000 + DropWatch.IN_FLIGHT_MS + 100);
        assertTrue(w.take().isEmpty());
        // the last look too long before the disconnect (ticks had stopped): nothing counts
        w.look(List.of(r), NONE, FEET, 10_000);
        w.disconnected(10_000 + DropWatch.STALE_MS + 1);
        assertTrue(w.take().isEmpty());
        // no look at all
        w.disconnected(20_000);
        assertTrue(w.take().isEmpty());
    }

    @Test
    void aSecondDisconnectKeepsTheFirstOnesDrops() {
        DropWatch w = new DropWatch();
        w.look(List.of(new DropWatch.Risk(ROCKET, 64, "on the cursor")), NONE, FEET, 1_000);
        w.disconnected(1_010);
        w.disconnected(9_000); // reconnected and lost again before the engine looked: nothing new
        w.look(List.of(new DropWatch.Risk(MAP, 1, "in the cartography table")), NONE, FEET, 20_000);
        w.disconnected(20_010);
        List<DisconnectDrop> drops = w.take();
        assertEquals(2, drops.size(), drops.toString());
        assertEquals(ROCKET, drops.get(0).item());
        assertEquals(MAP, drops.get(1).item());
    }

    /**
     * Local drill 2026-10-25 (10x): the look 0.2 s after the reconnect's login found no map, the chunk where it dropped
     * not loaded yet, and gave it up; the map lay on the ground. Now the look goes on while the chunk is not loaded, for
     * LOOK_TICKS after it is, and while a stack near it has no metadata yet (up to twice that).
     */
    @Test
    void theLookWaitsForTheWorldAfterAReconnect() {
        int look = DropRecoveryTask.LOOK_TICKS;
        assertTrue(DropWatch.keepLooking(false, 0, false, look), "chunk not loaded");
        assertTrue(DropWatch.keepLooking(true, look - 1, false, look));
        assertFalse(DropWatch.keepLooking(true, look, false, look), "loaded long enough, nothing there");
        assertTrue(DropWatch.keepLooking(true, look, true, look), "a stack without metadata yet");
        assertFalse(DropWatch.keepLooking(true, 2L * look, true, look), "not forever");
    }

    @Test
    void theNearestStackOfTheItemNearWhereItDropped() {
        Pos at = new Pos(10, 64, 10);
        List<WorldView.ItemInFlight> near = List.of(
            new WorldView.ItemInFlight(1, "minecraft:white_carpet", 64, 11.5, 64, 10.5),
            new WorldView.ItemInFlight(2, ROCKET, 64, 14.5, 64, 10.5),
            new WorldView.ItemInFlight(3, ROCKET, 12, 12.0, 64, 10.5),
            new WorldView.ItemInFlight(4, ROCKET, 64, 30.5, 64, 10.5), // too far from where it dropped
            new WorldView.ItemInFlight(5, null, 0, 10.5, 64, 10.5));   // no metadata yet
        assertEquals(3, DropWatch.next(near, ROCKET, at, 6, 10.5, 64, 10.5, Set.of()).entityId());
        assertEquals(2, DropWatch.next(near, ROCKET, at, 6, 10.5, 64, 10.5, Set.of(3)).entityId());
        assertNull(DropWatch.next(near, ROCKET, at, 6, 10.5, 64, 10.5, Set.of(2, 3)));
        assertNull(DropWatch.next(near, MAP, at, 6, 10.5, 64, 10.5, Set.of()));
        // below the platform (fell off an edge): not within the radius
        assertNull(DropWatch.next(List.of(new WorldView.ItemInFlight(6, ROCKET, 64, 10.5, 50, 10.5)), ROCKET, at, 6,
            10.5, 64, 10.5, Set.of()));
        assertEquals(20, DropWatch.distance(new Pos(30, 64, 10), at));
        // where a stack lies, in words without coordinates
        assertEquals("3 blocks below where it dropped", DropWatch.whereFrom(at, 8.1, 61.0, 9.3));
        assertEquals("1 block below where it dropped", DropWatch.whereFrom(at, 10.5, 63.0, 10.5));
        assertEquals("2 blocks from where it dropped", DropWatch.whereFrom(at, 12.6, 64.0, 10.5));
        assertEquals("where it dropped", DropWatch.whereFrom(at, 10.6, 65.0, 10.2), "on a block there");
        assertEquals(14, DropWatch.distance(new Pos(10, 50, 10), at));
    }
}
