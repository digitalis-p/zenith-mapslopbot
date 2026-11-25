package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The airplace guard's state machine ({@link AirPlaceGuard}), one bot tick at a time, against a fake client: what the
 * cache shows after an airplace (echo, kept swap, disconnect, someone else's item) and what the guard sends to fix it.
 */
class AirPlaceGuardTest {
    private static final String TOTEM = "minecraft:totem_of_undying";
    private static final String WHITE = "minecraft:white_carpet";
    private static final String RED = "minecraft:red_carpet";
    private static final String MOSS = "minecraft:moss_block";
    private static final String CLAY = "minecraft:clay";
    private static final Pos CELL = new Pos(10, 64, 20);
    private static final int SETTLE = AirPlaceGuard.SETTLE_TICKS;

    /**
     * Fake ZenithProxy client: the packets ("select n", "swap", "use ...", "swing off") and the inventory cache as
     * ZenithProxy predicts it per packet (a swap exchanges the selected hotbar slot and the offhand). A use consumes one
     * item from the offhand at once (the server's slot update).
     */
    private static final class Client implements AirPlace.Port, AirPlaceGuard.View {
        final List<String> packets = new ArrayList<>();
        final @Nullable String[] hotbar = new String[9];
        final int[] hotbarCount = new int[9];
        /** Main inventory, player slots 9-35 (index = slot - 9). */
        final @Nullable String[] main = new String[27];
        @Nullable String offhand;
        int offhandCount;
        int held;
        boolean inGame = true;
        boolean containerOpen;
        private int sequence;

        /** Totem in the offhand, 64 white carpet in hotbar slot 4, slot 0 selected. */
        static Client withTotem() {
            Client c = new Client();
            c.offhand = TOTEM;
            c.offhandCount = 1;
            c.give(4, WHITE, 64);
            return c;
        }

        void give(int slot, String item, int count) {
            hotbar[slot] = item;
            hotbarCount[slot] = count;
        }

        List<String> take() {
            List<String> out = List.copyOf(packets);
            packets.clear();
            return out;
        }

        /** As {@code Inv.find}: the hotbar (36-44) first, then the main inventory (9-35); -1 if none. */
        @Override
        public int slotOf(String itemId) {
            for (int i = 0; i < 9; i++) if (itemId.equals(hotbar[i])) return 36 + i;
            for (int i = 0; i < 27; i++) if (itemId.equals(main[i])) return 9 + i;
            return -1;
        }

        /** The repair's container click: player slot {@code slot} (9-44) swapped with the offhand (button 40). */
        void clickSwapWithOffhand(int slot) {
            packets.add("click " + slot + " swap offhand");
            String item = offhand;
            if (slot >= 36) {
                offhand = hotbar[slot - 36];
                hotbar[slot - 36] = item;
            } else {
                offhand = main[slot - 9];
                main[slot - 9] = item;
            }
        }

        /** The server's slot updates exchange hotbar slot {@code slot} and the offhand (no packet of ours). */
        void serverSwap(int slot) {
            String item = hotbar[slot];
            int count = hotbarCount[slot];
            hotbar[slot] = offhand;
            hotbarCount[slot] = offhandCount;
            offhand = item;
            offhandCount = count;
        }

        @Override public boolean inGame() { return inGame; }
        @Override public @Nullable String offhand() { return offhand; }
        @Override public @Nullable String hotbar(int i) { return hotbar[i]; }
        @Override public int heldSlot() { return held; }
        @Override public boolean containerOpen() { return containerOpen; }
        @Override public boolean placesBlock(String itemId) { return Set.of(WHITE, RED, MOSS, CLAY).contains(itemId); }
        /** Server reports of the offhand ({@link AirPlaceGuard.View#offhandReports}); -1: the fake cannot tell. */
        long reports = -1;
        @Override public long offhandReports() { return reports; }

        @Override
        public boolean select(int slot) {
            if (!inGame) return false;
            if (slot == held) return true;
            packets.add("select " + slot);
            held = slot;
            return true;
        }

        @Override
        public boolean swapOffhand() {
            if (!inGame) return false;
            packets.add("swap");
            serverSwap(held);
            return true;
        }

        @Override
        public int useItemOnInside(int x, int y, int z) {
            if (!inGame) return -1;
            int seq = ++sequence;
            packets.add("use " + x + "," + y + "," + z + " seq=" + seq);
            if (offhand != null && placesBlock(offhand) && --offhandCount <= 0) {
                offhand = null;
                offhandCount = 0;
            }
            return seq;
        }

        @Override
        public void swingOffhand() {
            packets.add("swing off");
        }
    }

    private final AirPlaceGuard guard = new AirPlaceGuard();

    /** Ticks the guard {@code n} times; the packets it sent. */
    private List<String> run(Client c, int n) {
        assertEquals(List.of(), c.take(), "packets of earlier ticks left unchecked");
        for (int i = 0; i < n; i++) guard.tick(c, c);
        return c.take();
    }

    /** One guard tick (learns the offhand), then an airplace from {@code slot} (already selected). */
    private void airplaceFrom(Client c, int slot) {
        c.held = slot;
        guard.tick(c, c);
        assertEquals(AirPlace.Result.SENT, new AirPlace(guard, c).place(c, slot, CELL));
        c.take();
    }

    @Test
    void firstInGameTickLearnsTheOffhandItem() {
        Client c = Client.withTotem();
        c.inGame = false;
        assertFalse(guard.mayAirPlace(c));
        assertFalse(guard.airPlaceAvailable(c));
        assertEquals("offhand unknown", guard.describe());
        guard.tick(c, c);
        assertFalse(guard.airPlaceAvailable(c), "nothing learned out of game");
        c.inGame = true;
        assertFalse(guard.mayAirPlace(c), "in game, not ticked yet");
        guard.tick(c, c);
        assertTrue(guard.mayAirPlace(c));
        assertTrue(guard.airPlaceAvailable(c));
        assertFalse(guard.busy(c));
        assertEquals("offhand " + TOTEM, guard.describe());
        assertEquals(List.of(), c.take());
    }

    @Test
    void emptyOffhandAllowsAirplace() {
        Client c = Client.withTotem();
        c.offhand = null;
        guard.tick(c, c);
        assertTrue(guard.mayAirPlace(c));
        assertTrue(guard.airPlaceAvailable(c));
        assertEquals("offhand empty", guard.describe());
    }

    @Test
    void blockKeptInTheOffhandDisablesAirplace() {
        Client c = Client.withTotem();
        c.offhand = MOSS;
        guard.tick(c, c);
        assertFalse(guard.mayAirPlace(c));
        assertFalse(guard.airPlaceAvailable(c), "the lane places by LOOK");
        assertFalse(guard.busy(c));
        assertEquals(List.of(), run(c, 3 * SETTLE), "the owner's block is never moved");
        assertEquals(MOSS, c.offhand);
    }

    /**
     * The server handled the two swaps in different ticks: its slot updates show the swapped state, then the
     * restored one. The guard waits (busy, no airplace) and sends nothing.
     */
    @Test
    void transientSwapEchoIsWaitedOutWithoutACorrectiveSwap() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.serverSwap(4);
        assertEquals(WHITE, c.offhand);
        assertEquals(TOTEM, c.hotbar[4]);
        for (int i = 0; i < SETTLE; i++) {
            guard.tick(c, c);
            assertTrue(guard.busy(c), "tick " + i);
            assertFalse(guard.mayAirPlace(c));
            assertTrue(guard.airPlaceAvailable(c), "a carpet lane does not turn to aim meanwhile");
        }
        assertEquals(List.of(), c.take(), "no corrective swap within SETTLE_TICKS");
        c.serverSwap(4); // the restored state arrives
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
        assertEquals(List.of(), run(c, 3 * SETTLE));
        assertEquals(TOTEM, c.offhand);
        assertNull(guard.takeEvent());
    }

    @Test
    void swappedStateTheServerKeptIsCorrectedAfterTheSettleTime() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.serverSwap(4); // a swap packet lost: the server keeps the material in the offhand
        c.held = 2;
        assertEquals(List.of(), run(c, SETTLE));
        assertTrue(guard.busy(c));
        assertEquals(List.of("select 4", "swap"), run(c, 1), "one corrective swap, the slot selected first");
        assertEquals(TOTEM, c.offhand);
        assertEquals(WHITE, c.hotbar[4]);
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
        assertEquals(List.of(), run(c, 3 * SETTLE), "settled: nothing more");
        assertTrue(guard.describe().contains("1 corrective swaps"), guard.describe());
    }

    @Test
    void swappedStateWithTheMaterialUsedUpIsCorrectedToo() {
        Client c = Client.withTotem();
        c.give(4, WHITE, 1);
        airplaceFrom(c, 4);
        assertNull(c.hotbar[4]);
        // the server kept swap 1: the totem in slot 4, the last carpet used from the offhand
        c.hotbar[4] = TOTEM;
        c.offhand = null;
        assertEquals(List.of(), run(c, SETTLE));
        assertTrue(guard.busy(c));
        assertEquals(List.of("swap"), run(c, 1));
        assertEquals(TOTEM, c.offhand);
        assertNull(c.hotbar[4]);
        assertFalse(guard.busy(c));
    }

    @Test
    void takeEventReportsACorrectiveSwapOnceAndClears() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.serverSwap(4);
        run(c, SETTLE);
        assertNull(guard.takeEvent(), "nothing notable while settling");
        assertEquals(List.of("swap"), run(c, 1));
        assertEquals("Offhand: offhand item found in hotbar slot 4 and " + WHITE + " in the offhand: swapped back (1/"
            + AirPlaceGuard.MAX_RESTORES + ")", guard.takeEvent());
        assertNull(guard.takeEvent(), "cleared when read");
    }

    @Test
    void noCorrectiveSwapWhileAContainerIsOpen() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.serverSwap(4);
        c.containerOpen = true;
        assertEquals(List.of(), run(c, 3 * SETTLE));
        c.containerOpen = false;
        assertEquals(List.of("swap"), run(c, 1));
        assertEquals(TOTEM, c.offhand);
    }

    /**
     * The server undoes every corrective swap (its full inventory, asked for before each further swap, confirms the
     * swapped state): MAX_RESTORES of them, then airplace is off and LOOK goes on.
     */
    @Test
    void givesUpAfterMaxRestores() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        int swaps = 0;
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 10 * SETTLE; i++) {
            c.offhand = WHITE;
            c.hotbar[4] = TOTEM;
            if (guard.checkWanted(c)) guard.serverChecked(c);
            guard.tick(c, c);
            swaps += (int) c.take().stream().filter(s -> s.equals("swap")).count();
            String e = guard.takeEvent();
            if (e != null && (e.contains("swapped back") || e.contains("gave up"))) events.add(e);
        }
        assertEquals(AirPlaceGuard.MAX_RESTORES, swaps);
        assertTrue(guard.gaveUp());
        assertFalse(guard.mayAirPlace(c));
        assertFalse(guard.airPlaceAvailable(c));
        assertFalse(guard.busy(c), "LOOK placements and inventory moves go on");
        assertEquals(AirPlaceGuard.MAX_RESTORES + 1, events.size(), String.valueOf(events));
        assertTrue(events.getLast().contains("gave up"), events.getLast());
        assertTrue(guard.describe().endsWith("airplace off (gave up)"), guard.describe());
        assertEquals(AirPlace.Result.NOT_READY, new AirPlace(guard, c).place(c, 4, CELL));
        assertEquals(List.of(), c.take());
    }

    /**
     * Disconnected after swap 1: swap 2 is dropped, and after the reconnect the guard settles before it judges the
     * server's inventory. Here the server kept swap 1, so it is corrected.
     */
    @Test
    void disconnectBetweenTheSwapsIsCorrectedAfterTheReconnect() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        assertTrue(c.swapOffhand()); // swap 1 went out ...
        guard.swappedOut(4, WHITE, TOTEM);
        c.take();
        assertTrue(guard.busy(c));
        c.inGame = false; // ... and the connection dropped before swap 2
        assertEquals(List.of(), run(c, 5), "nothing sent out of game");
        String event = guard.takeEvent();
        assertTrue(event != null && event.contains("disconnected"), String.valueOf(event));
        assertFalse(guard.mayAirPlace(c));

        c.inGame = true;
        c.held = 0;
        assertEquals(List.of(), run(c, SETTLE + 1), "the reconnect tick and SETTLE_TICKS after it");
        assertTrue(guard.busy(c));
        assertEquals(List.of("select 4", "swap"), run(c, 1));
        assertEquals(TOTEM, c.offhand);
        assertEquals(WHITE, c.hotbar[4]);
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
        assertEquals(List.of(), run(c, 3 * SETTLE));
    }

    @Test
    void disconnectBetweenTheSwapsWithTheOriginalStateKeptSendsNothing() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        assertTrue(c.swapOffhand());
        guard.swappedOut(4, WHITE, TOTEM);
        c.take();
        c.inGame = false;
        run(c, 3);
        // the server never handled swap 1: the inventory after the login is the original one
        c.offhand = TOTEM;
        c.hotbar[4] = WHITE;
        c.inGame = true;
        assertEquals(List.of(), run(c, 5 * SETTLE));
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
        assertEquals(TOTEM, c.offhand);
    }

    @Test
    void usedUpTotemIsAdoptedAndNothingIsMoved() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.offhand = null; // the totem popped
        c.offhandCount = 0;
        for (int i = 0; i < SETTLE; i++) {
            guard.tick(c, c);
            assertFalse(guard.busy(c), "no swap pattern: tick " + i);
            assertFalse(guard.mayAirPlace(c), "not settled: tick " + i);
        }
        assertNull(guard.takeEvent());
        guard.tick(c, c);
        assertTrue(guard.mayAirPlace(c), "adopted");
        assertEquals("offhand empty", guard.describe());
        assertEquals("Offhand: offhand now holds nothing (was " + TOTEM + ")", guard.takeEvent());
        assertEquals(List.of(), c.take());
    }

    /** The kill switch ({@code build.offhandRepair} false): the previous behaviour, any stable item is adopted. */
    @Test
    void withTheKillSwitchOffANewItemIsAdoptedOnlyOnceStable() {
        guard.configure(TOTEM, false, true);
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.offhand = "minecraft:shield";
        assertEquals(List.of(), run(c, SETTLE - 5));
        c.offhand = MOSS; // changed again before it was adopted: the settle time starts over
        for (int i = 0; i < SETTLE; i++) {
            guard.tick(c, c);
            assertFalse(guard.busy(c));
        }
        assertTrue(guard.describe().contains(TOTEM), guard.describe());
        guard.tick(c, c);
        assertEquals("offhand " + MOSS, guard.describe());
        assertFalse(guard.airPlaceAvailable(c), "a block is kept in the offhand: LOOK");
        assertFalse(guard.mayAirPlace(c));
        assertEquals(List.of(), c.take(), "never moved");
        assertEquals(MOSS, c.offhand);
    }

    /**
     * The totem in the last airplace's slot is not the swap pattern when the offhand holds something other than the
     * airplaced material: the guard sends no corrective swap. The red carpet is not adopted either: airplace is off, and
     * the totem's slot (hotbar 4 = player slot 40) is named for the engine's repair at the next safe point.
     */
    @Test
    void otherItemInTheOffhandWithTheTotemInTheAirplaceSlotIsNotSwappedButRepaired() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.hotbar[4] = TOTEM;
        c.offhand = RED;
        assertFalse(guard.busy(c));
        assertEquals(List.of(), run(c, 5 * SETTLE), "no packet of the guard's own");
        assertEquals(RED, c.offhand);
        assertTrue(guard.describe().startsWith("offhand " + TOTEM), "not adopted: " + guard.describe());
        assertFalse(guard.airPlaceAvailable(c));
        assertEquals(40, guard.repairSlot(c));
        c.clickSwapWithOffhand(40); // the engine's repair
        c.take();
        assertEquals(List.of(), run(c, 2));
        assertTrue(guard.airPlaceAvailable(c));
        assertTrue(guard.mayAirPlace(c));
        assertEquals(-1, guard.repairSlot(c));
        assertEquals(RED, c.hotbar[4]);
    }

    /**
     * Regression: with an empty offhand the airplace slot emptied by the last carpet looked like the swap pattern
     * (empty slot = the known empty offhand item), so a totem put into the offhand later was swapped out into it.
     */
    @Test
    void totemPutIntoAnEmptyOffhandAfterTheLastCarpetIsNeverSwappedOut() {
        Client c = new Client();
        c.give(3, WHITE, 1);
        airplaceFrom(c, 3);
        assertNull(c.hotbar[3], "the last carpet");
        assertNull(c.offhand);
        assertEquals(List.of(), run(c, 5));
        c.offhand = TOTEM;
        c.offhandCount = 1;
        for (int i = 0; i < SETTLE; i++) {
            guard.tick(c, c);
            assertFalse(guard.busy(c), "tick " + i);
            assertFalse(guard.mayAirPlace(c), "tick " + i);
        }
        guard.tick(c, c);
        assertEquals("offhand " + TOTEM, guard.describe(), "adopted after SETTLE_TICKS");
        assertEquals("Offhand: offhand now holds " + TOTEM + " (was nothing)", guard.takeEvent());
        assertTrue(guard.mayAirPlace(c));
        assertEquals(List.of(), run(c, 100 - SETTLE - 1), "no packet in 100 ticks");
        assertEquals(TOTEM, c.offhand);
        assertNull(c.hotbar[3]);
    }
    /**
     * An interrupted pair goes through {@link OffhandGuard}'s swap record: open from just before swap 1, so the record
     * decides on the next tick; here the totem sits in hotbar 4 and the carpet in the offhand: swap back now.
     */
    @Test
    void anInterruptedPairIsRepairedThroughTheOffhandRecord() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        guard.swappedOut(4, WHITE, TOTEM);
        assertTrue(guard.record().open(), "recorded before swap 1 is sent");
        assertTrue(c.swapOffhand());
        c.take();
        assertEquals(4, guard.swappedSlot(c), "the carpet in the offhand still belongs to hotbar slot 4");
        assertEquals(List.of("swap"), run(c, 1), "swap 2 on the next tick");
        assertFalse(guard.record().open());
        assertEquals(TOTEM, c.offhand);
        assertEquals(WHITE, c.hotbar[4]);
        assertEquals(-1, guard.swappedSlot(c));
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
    }

    /**
     * The record's LOST: after an interrupted pair the totem is neither in the offhand nor in the airplace slot (a
     * player moved it during a takeover): airplace goes off, nothing is sent to look for it, an alert line is left.
     */
    @Test
    void anInterruptedPairWhoseItemWasMovedElsewhereTurnsAirplaceOff() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        guard.swappedOut(4, WHITE, TOTEM);
        assertTrue(c.swapOffhand());
        c.take();
        c.hotbar[4] = null; // the totem was moved into the main inventory by someone else
        c.hotbarCount[4] = 0;
        assertEquals(List.of(), run(c, 3), "never searched for with clicks");
        assertTrue(guard.gaveUp());
        assertFalse(guard.record().open());
        assertFalse(guard.mayAirPlace(c));
        assertFalse(guard.airPlaceAvailable(c));
        assertFalse(guard.busy(c), "LOOK placements go on");
        assertEquals(-1, guard.swappedSlot(c));
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("neither in the offhand nor in hotbar slot 4"), String.valueOf(e));
    }

    /** A complete airplace leaves the record closed and nothing for the guard to do. */
    @Test
    void aCompleteAirplaceClosesTheRecordInTheSameTick() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        assertFalse(guard.record().open());
        assertEquals(-1, guard.swappedSlot(c));
        assertEquals(List.of(), run(c, 3 * SETTLE));
    }

    /** While the server's echo shows the swap, the stack of hotbar slot 4 is the one in the offhand. */
    @Test
    void theEchoOfASplitSwapPointsCountsAtTheAirplaceSlot() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.serverSwap(4); // the server's slot updates of swap 1 arrive first
        assertEquals(4, guard.swappedSlot(c));
        assertTrue(guard.busy(c));
        c.serverSwap(4); // then those of swap 2
        assertEquals(-1, guard.swappedSlot(c));
        assertFalse(guard.busy(c));
        assertEquals(List.of(), run(c, 3 * SETTLE));
    }
    /**
     * ZenithProxy fires no bot tick while disconnected: the module reports the disconnect. After the reconnect the cache
     * is empty for a moment; the guard judges nothing (no LOST, no packet) until it settled, then finds the server's
     * state: here the swapped one, corrected.
     */
    @Test
    void aReconnectSettlesBeforeTheOffhandIsJudged() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        guard.swappedOut(4, WHITE, TOTEM);
        assertTrue(c.swapOffhand()); // swap 1 went out, then the connection dropped (no tick while disconnected)
        c.take();
        guard.stopped(true);
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("disconnected"), String.valueOf(e));
        // the login: the inventory cache is empty for a few ticks
        String off = c.offhand;
        String four = c.hotbar[4];
        c.offhand = null;
        c.hotbar[4] = null;
        c.held = 0;
        assertEquals(List.of(), run(c, 5), "nothing judged on an empty cache");
        assertFalse(guard.gaveUp());
        c.offhand = off; // the server's inventory arrives: it kept swap 1
        c.hotbar[4] = four;
        assertEquals(List.of(), run(c, SETTLE - 5));
        assertEquals(List.of("select 4", "swap"), run(c, 2));
        assertEquals(TOTEM, c.offhand);
        assertFalse(guard.gaveUp());
        assertNull(guard.takeAlert(), "a correction is no alert");
    }

    /** A takeover (ticks stop, no disconnect) keeps an open pair; it is finished after the settle. */
    @Test
    void aTakeoverInTheMiddleOfAPairIsFinishedAfterTheSettle() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        guard.swappedOut(4, WHITE, TOTEM);
        assertTrue(c.swapOffhand());
        c.take();
        guard.stopped(false);
        assertTrue(guard.busy(c));
        assertEquals(List.of(), run(c, SETTLE + 1), "the restart tick and SETTLE_TICKS after it");
        assertEquals(List.of("swap"), run(c, 1));
        assertEquals(TOTEM, c.offhand);
        assertFalse(guard.busy(c));
    }

    /**
     * Live gate C: at every login the first tick saw the cache before the inventory arrived (offhand and hotbar empty),
     * and the totem arriving then was reported as a change ("offhand now holds the totem (was nothing)"). Learnt from
     * an empty cache the offhand is a guess: the inventory arriving is learnt silently.
     */
    @Test
    void theInventoryArrivingAfterTheLoginIsNoOffhandChange() {
        Client c = new Client(); // logged in, the inventory not there yet
        guard.tick(c, c);
        assertEquals("offhand unknown", guard.describe());
        assertEquals(List.of(), run(c, 3));
        c.offhand = TOTEM; // the server's inventory arrives
        c.offhandCount = 1;
        c.give(4, WHITE, 64);
        assertEquals(List.of(), run(c, 5 * SETTLE));
        assertNull(guard.takeEvent(), "not a change of the offhand");
        assertEquals("offhand " + TOTEM, guard.describe());
        assertTrue(guard.mayAirPlace(c));
        assertTrue(guard.airPlaceAvailable(c));

        c.offhand = null; // a real change later in the session still is one
        c.offhandCount = 0;
        assertEquals(List.of(), run(c, SETTLE + 1));
        assertEquals("Offhand: offhand now holds nothing (was " + TOTEM + ")", guard.takeEvent());
    }

    /** An empty offhand seen once the inventory is there is known: a totem put in later is a change (an event). */
    @Test
    void anEmptyOffhandSeenInTheFilledCacheIsKnown() {
        Client c = new Client();
        guard.tick(c, c); // empty cache: a guess
        c.give(4, WHITE, 64); // the inventory arrives, the offhand really empty
        assertEquals(List.of(), run(c, 2));
        assertEquals("offhand empty", guard.describe());
        assertNull(guard.takeEvent());
        c.offhand = TOTEM;
        c.offhandCount = 1;
        assertEquals(List.of(), run(c, SETTLE + 1));
        assertEquals("Offhand: offhand now holds " + TOTEM + " (was nothing)", guard.takeEvent());
    }

    /**
     * After a reconnect the guard waits past {@link AirPlaceGuard#SETTLE_TICKS} while the cache is still empty (a slow
     * login, or speed mode where 20 ticks are 0.1 s): no "offhand now holds nothing" and no totem "change" after it.
     */
    @Test
    void aSlowInventoryAfterAReconnectIsNoOffhandChange() {
        Client c = Client.withTotem();
        guard.tick(c, c);
        guard.stopped(true);
        c.offhand = null;
        c.hotbar[4] = null;
        assertEquals(List.of(), run(c, 4 * SETTLE), "the cache stays empty for a while");
        assertNull(guard.takeEvent());
        assertFalse(guard.mayAirPlace(c));
        c.offhand = TOTEM;
        c.hotbar[4] = WHITE;
        assertEquals(List.of(), run(c, 3 * SETTLE));
        assertNull(guard.takeEvent(), "nothing changed");
        assertTrue(guard.mayAirPlace(c));
        assertEquals("offhand " + TOTEM, guard.describe());
    }

    /** Giving up is an alert for the owner, once. */
    @Test
    void givingUpRaisesOneAlert() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        for (int i = 0; i <= AirPlaceGuard.MAX_RESTORES; i++) {
            c.serverSwap(4); // the server keeps putting the swapped state back
            c.take();
            run(c, SETTLE + 2);
            if (guard.checkWanted(c)) {
                guard.serverChecked(c); // and its full inventory says so
                run(c, 1);
            }
        }
        assertTrue(guard.gaveUp());
        String a = guard.takeAlert();
        assertTrue(a != null && a.contains("gave up"), String.valueOf(a));
        assertNull(guard.takeAlert());
    }

    // ------------------------------------------------------------------------------------------ offhand item

    /** The totem's player slot in the live repair ("inventory swap 24 45"). */
    private static final int SLOT_24 = 24;

    /**
     * Live j0002 [1,1], 2026-10-12, up to 01:23 (proxy log): an airplace used the last carpet of hotbar slot 2; during
     * setbacks (server teleports 01:22:50-01:22:59) the cache showed the totem in slot 2 and nothing in the offhand, so at
     * 01:22:58 the guard swapped back ("offhand item found in hotbar slot 2 and null in the offhand: swapped back
     * (1/3)"). The server had kept the totem in the offhand: the swap moved it into slot 2, and the lane's next hotbar
     * move swapped it into the main inventory (slot 24, where the coordinator found it). Returns the client there.
     */
    private Client liveUntil0123(boolean repairOn) {
        guard.configure(TOTEM, repairOn, true);
        Client c = Client.withTotem();
        c.give(2, WHITE, 1);
        airplaceFrom(c, 2);
        assertNull(c.hotbar[2], "the last carpet");
        assertEquals(TOTEM, c.offhand);
        // the setbacks: the cache shows the swap kept, the server has the totem in the offhand
        c.hotbar[2] = TOTEM;
        c.offhand = null;
        assertEquals(List.of(), run(c, SETTLE));
        assertEquals(List.of("swap"), run(c, 1), "the corrective swap on the stale cache");
        assertEquals("Offhand: offhand item found in hotbar slot 2 and null in the offhand: swapped back (1/"
            + AirPlaceGuard.MAX_RESTORES + ")", guard.takeEvent());
        // the server's answer: its totem went into slot 2
        c.offhand = null;
        c.hotbar[2] = TOTEM;
        // the lane's next material move into slot 2 swaps the totem into the main inventory
        c.give(2, CLAY, 64);
        c.main[SLOT_24 - 9] = TOTEM;
        return c;
    }

    /** The live sequence as it was (the kill switch off): "nothing" adopted at 01:23:00, clay at 01:49:19. */
    @Test
    void liveTile11SequenceLatchesClayWithTheKillSwitchOff() {
        Client c = liveUntil0123(false);
        assertEquals(List.of(), run(c, SETTLE + 2));
        assertEquals("Offhand: offhand now holds nothing (was " + TOTEM + ")", guard.takeEvent());
        assertTrue(guard.airPlaceAvailable(c), "an empty offhand airplaces");
        // 01:45 ZenithProxy restarted: the first tick learns the empty offhand
        guard.reset();
        assertEquals(List.of(), run(c, 1));
        assertEquals("offhand empty", guard.describe());
        // 01:49: an airplace of clay from slot 4 with the empty offhand; the cache again shows the swap kept
        c.give(4, CLAY, 64);
        airplaceFrom(c, 4);
        c.offhand = CLAY;
        c.hotbar[4] = null;
        assertEquals(List.of(), run(c, SETTLE));
        assertEquals(List.of("swap"), run(c, 1));
        assertEquals("Offhand: offhand item found in hotbar slot 4 and " + CLAY + " in the offhand: swapped back (1/"
            + AirPlaceGuard.MAX_RESTORES + ")", guard.takeEvent());
        // the server had no swap kept: the corrective swap put the clay into the offhand; the lane refilled slot 4
        c.offhand = CLAY;
        c.hotbar[4] = CLAY;
        assertEquals(List.of(), run(c, SETTLE + 2));
        assertEquals("Offhand: offhand now holds " + CLAY + " (was nothing)", guard.takeEvent());
        assertFalse(guard.airPlaceAvailable(c), "airplace off for good: the next tiles ran 5-wide LOOK lanes");
        assertEquals(-1, guard.repairSlot(c), "nothing repairs it");
    }

    /**
     * The same sequence with the fix: "nothing" is never adopted while the totem is in the inventory; airplace is off
     * with one alert of each kind, the totem's slot is named for the engine's repair, and after it airplace is back.
     * Clay put into the offhand on top of that is not adopted either.
     */
    @Test
    void liveTile11SequenceNeverAdoptsAndRepairs() {
        Client c = liveUntil0123(true);
        assertTrue(guard.airPlaceAvailable(c), "still on while the change settles");
        assertEquals(List.of(), run(c, SETTLE + 2));
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("offhand holds nothing: not adopted") && e.contains("inventory slot 24"),
            String.valueOf(e));
        assertTrue(guard.describe().startsWith("offhand " + TOTEM), guard.describe());
        assertFalse(guard.airPlaceAvailable(c));
        assertFalse(guard.mayAirPlace(c));
        assertEquals(SLOT_24, guard.repairSlot(c));
        String off = guard.takeAlert();
        assertTrue(off != null && off.contains("the offhand holds nothing"), String.valueOf(off));
        String item = guard.takeOffhandAlert();
        assertTrue(item != null && item.contains("inventory slot 24 holds " + TOTEM), String.valueOf(item));
        assertNull(guard.takeAlert(), "once");
        assertNull(guard.takeOffhandAlert(), "once");

        // 01:45 ZenithProxy restarted: the empty offhand is not adopted at the first tick either
        guard.reset();
        assertEquals(List.of(), run(c, 1));
        assertTrue(guard.describe().startsWith("offhand " + TOTEM), guard.describe());
        assertEquals(SLOT_24, guard.repairSlot(c));
        assertNotNull(guard.takeAlert(), "a restart with airplace off alerts again");
        guard.takeOffhandAlert();
        guard.takeEvent();

        // clay reaching the offhand anyway (any route): still not adopted
        c.offhand = CLAY;
        assertEquals(List.of(), run(c, 3 * SETTLE));
        assertTrue(guard.describe().startsWith("offhand " + TOTEM), guard.describe());
        assertFalse(guard.airPlaceAvailable(c));
        assertEquals(SLOT_24, guard.repairSlot(c));
        assertNull(guard.takeAlert(), "airplace was off already: no new alert");

        c.clickSwapWithOffhand(SLOT_24); // the engine's repair at the next safe point
        c.take();
        assertEquals(List.of(), run(c, 2));
        assertEquals(TOTEM, c.offhand);
        assertEquals(CLAY, c.main[SLOT_24 - 9]);
        assertTrue(guard.airPlaceAvailable(c));
        assertTrue(guard.mayAirPlace(c));
        assertEquals(-1, guard.repairSlot(c));
        String n = guard.takeNotice();
        assertTrue(n != null && n.contains("airplace back on"), String.valueOf(n));
        assertEquals("Offhand: offhand holds " + TOTEM + " again", guard.takeEvent());
    }

    /** Live 03:09 and 04:09: restarts relearnt the clay in the offhand at the first tick. Now it is not adopted. */
    @Test
    void clayInTheOffhandAtTheFirstTickIsNotAdopted() {
        Client c = Client.withTotem();
        c.offhand = CLAY;
        c.offhandCount = 64;
        c.main[SLOT_24 - 9] = TOTEM;
        guard.tick(c, c);
        assertFalse(guard.airPlaceAvailable(c), "no 9-wide lanes planned for airplace");
        assertEquals(SLOT_24, guard.repairSlot(c));
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("at the start: not adopted"), String.valueOf(e));
        String a = guard.takeAlert();
        assertTrue(a != null && a.contains(CLAY), "airplace starts off: an alert " + a);
        assertNotNull(guard.takeOffhandAlert());
        assertEquals(List.of(), run(c, 3 * SETTLE), "the guard itself never clicks the inventory");
    }

    /** A totem used up with a spare in the inventory: the empty offhand is not adopted; the spare goes in. */
    @Test
    void aPoppedTotemWithASpareIsRepaired() {
        Client c = Client.withTotem();
        c.main[0] = TOTEM;
        airplaceFrom(c, 4);
        c.offhand = null;
        c.offhandCount = 0;
        assertEquals(List.of(), run(c, SETTLE + 1));
        assertFalse(guard.airPlaceAvailable(c));
        assertEquals(9, guard.repairSlot(c));
        assertNotNull(guard.takeOffhandAlert());
    }

    /** A totem used up and none left: the empty offhand is adopted, airplace goes on, no alert. */
    @Test
    void aPoppedTotemWithNoneLeftAirplacesWithAnEmptyOffhand() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.offhand = null;
        c.offhandCount = 0;
        assertEquals(List.of(), run(c, SETTLE + 1));
        assertTrue(guard.airPlaceAvailable(c));
        assertEquals(-1, guard.repairSlot(c));
        assertNull(guard.takeAlert());
        assertNull(guard.takeOffhandAlert());
        // a totem turning up later (a chest, the owner) goes into the offhand
        c.main[3] = TOTEM;
        assertEquals(List.of(), run(c, 2));
        assertFalse(guard.airPlaceAvailable(c));
        assertEquals(12, guard.repairSlot(c));
        assertNotNull(guard.takeOffhandAlert());
    }

    /** The configured offhand item (build.offhandItem) is adopted like a totem. */
    @Test
    void theConfiguredOffhandItemIsAdopted() {
        guard.configure("shield", true, true);
        Client c = Client.withTotem();
        guard.tick(c, c);
        c.offhand = "minecraft:shield";
        assertEquals(List.of(), run(c, SETTLE + 2));
        assertEquals("offhand minecraft:shield", guard.describe());
        assertTrue(guard.airPlaceAvailable(c));
        assertNull(guard.takeOffhandAlert());
    }

    /** LOOK mode: ZenithProxy's AutoTotem keeps the offhand; no repair, no alert. */
    @Test
    void lookModeNeitherRepairsNorAlerts() {
        guard.configure(TOTEM, true, false);
        Client c = Client.withTotem();
        c.offhand = CLAY;
        c.main[SLOT_24 - 9] = TOTEM;
        assertEquals(List.of(), run(c, 3 * SETTLE));
        assertEquals(-1, guard.repairSlot(c));
        assertNull(guard.takeAlert());
        assertNull(guard.takeOffhandAlert());
    }

    /** A second airplace-off within ALERT_REPEAT_TICKS of the first is logged, not alerted. */
    @Test
    void airplaceOffAlertsAreSpacedOut() {
        Client c = Client.withTotem();
        c.main[SLOT_24 - 9] = CLAY;
        guard.tick(c, c);
        c.clickSwapWithOffhand(SLOT_24); // clay in the offhand, the totem in slot 24
        c.take();
        run(c, SETTLE + 2);
        assertNotNull(guard.takeAlert());
        c.clickSwapWithOffhand(SLOT_24); // repaired
        c.take();
        run(c, 2);
        assertNotNull(guard.takeNotice());
        c.clickSwapWithOffhand(SLOT_24); // the clay back in the offhand
        c.take();
        run(c, SETTLE + 2);
        assertFalse(guard.airPlaceAvailable(c));
        assertNull(guard.takeAlert(), "within " + AirPlaceGuard.ALERT_REPEAT_TICKS + " ticks of the last");
        run(c, AirPlaceGuard.ALERT_REPEAT_TICKS);
        c.clickSwapWithOffhand(SLOT_24);
        c.take();
        run(c, 2);
        c.clickSwapWithOffhand(SLOT_24);
        c.take();
        run(c, SETTLE + 2);
        assertNotNull(guard.takeAlert(), "later again an alert");
    }

    // ------------------------------------------------------------------------------------------ offhand confirmation

    /** The server kept a swap: the corrective swap, then no airplace on its prediction until the server reports. */
    private Client correctiveSwapSent() {
        Client c = Client.withTotem();
        c.reports = 0;
        airplaceFrom(c, 4);
        c.serverSwap(4);
        assertEquals(List.of(), run(c, SETTLE));
        assertEquals(List.of("swap"), run(c, 1));
        assertEquals(TOTEM, c.offhand, "the cache shows the swap's prediction");
        return c;
    }

    /** A delayed confirm (drill C1, 100 ms each way): nothing goes out on the corrective swap's prediction. */
    @Test
    void aDelayedSwapConfirmHoldsAirplaceUntilTheServerReportsTheOffhand() {
        Client c = correctiveSwapSent();
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("waiting for the server's report"), String.valueOf(e));
        for (int i = 0; i < AirPlaceGuard.ANSWER_TICKS - 1; i++) {
            assertTrue(guard.awaitingAnswer(), "tick " + i);
            assertFalse(guard.mayAirPlace(c), "no pair on a prediction, tick " + i);
            assertTrue(guard.busy(c), "no inventory move either");
            assertEquals(AirPlace.Result.NOT_READY, new AirPlace(guard, c).place(c, 4, CELL));
            assertEquals(List.of(), run(c, 1));
        }
        c.reports++; // the server's SetSlot(45): the totem
        assertEquals(List.of(), run(c, 1));
        assertFalse(guard.awaitingAnswer());
        assertTrue(guard.mayAirPlace(c));
        assertEquals(AirPlace.Result.SENT, new AirPlace(guard, c).place(c, 4, CELL));
        c.take();
        assertNull(guard.takeEvent());
    }

    /**
     * The answer shows the swapped state again (the corrective swap may have acted on a stale cache): no second swap on
     * the cache, as in C1 and OD1 (three in 3 s, the totem ending in the main inventory). The server's full inventory
     * is asked for; when it shows the swap kept, the swap goes out on the truth.
     */
    @Test
    void anAnswerShowingTheSwapAgainAsksTheServerBeforeAnotherSwap() {
        Client c = correctiveSwapSent();
        guard.takeEvent();
        assertEquals(List.of(), run(c, 3));
        c.serverSwap(4);
        c.reports++;
        assertEquals(List.of(), run(c, SETTLE + 1), "what the answer shows settles first");
        assertFalse(guard.checkWanted(c));
        assertEquals(List.of(), run(c, 1), "no second swap on the cache");
        assertTrue(guard.checkWanted(c));
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("asking the server for the full inventory"), String.valueOf(e));
        assertEquals(List.of(), run(c, 50));
        assertFalse(guard.mayAirPlace(c));
        guard.serverChecked(c); // the full inventory: the swap really kept
        assertFalse(guard.checkWanted(c));
        assertEquals(List.of("swap"), run(c, 1));
        assertTrue(guard.awaitingAnswer());
        assertTrue(guard.describe().contains("2 corrective swaps"), guard.describe());
    }

    /** The server's full inventory shows the totem in the offhand: the swapped state was the cache's; nothing is sent. */
    @Test
    void aServerCheckShowingTheTotemEndsTheRecheckWithoutASwap() {
        Client c = correctiveSwapSent();
        guard.takeEvent();
        c.serverSwap(4);
        c.reports++;
        run(c, SETTLE + 2);
        assertTrue(guard.checkWanted(c));
        guard.takeEvent();
        c.serverSwap(4); // the full inventory: the totem in the offhand
        guard.serverChecked(c);
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("the swapped state was the cache's"), String.valueOf(e));
        assertEquals(List.of(), run(c, 3 * SETTLE));
        assertTrue(guard.mayAirPlace(c));
    }

    /** No safe point for the check: after RECHECK_TICKS the guard swaps on the cache, as before. */
    @Test
    void anUnansweredRecheckFallsBackToASwapOnTheCache() {
        Client c = correctiveSwapSent();
        c.serverSwap(4);
        c.reports++;
        run(c, SETTLE + 2);
        assertTrue(guard.checkWanted(c));
        assertEquals(List.of(), run(c, AirPlaceGuard.RECHECK_TICKS));
        assertEquals(List.of("swap"), run(c, 1));
    }

    /** No report at all: after ANSWER_TICKS the guard judges the cache again, in one line. */
    @Test
    void noAnswerWithinTheAnswerTicksFallsBackToTheCache() {
        Client c = correctiveSwapSent();
        guard.takeEvent();
        assertEquals(List.of(), run(c, AirPlaceGuard.ANSWER_TICKS));
        assertTrue(guard.awaitingAnswer());
        assertEquals(List.of(), run(c, 1));
        assertFalse(guard.awaitingAnswer());
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("no server report of the offhand"), String.valueOf(e));
        assertTrue(guard.mayAirPlace(c));
    }

    /**
     * Drill gs2 21:19:36: Grim's SetSlot(45) after a cancelled offhand place left the carpet in the offhand of the cache
     * (and in the hotbar slot, the pair's own prediction), the totem nowhere. A block left in the offhand: airplace off at
     * once, no swap, the server check wanted; its answer (the totem in the offhand) ends it.
     */
    @Test
    void aBlockLeftInTheOffhandByAStaleCacheIsCheckedWithTheServer() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.offhand = WHITE;
        assertEquals(List.of(), run(c, 1), "no swap on a cache that lost the totem");
        assertFalse(guard.mayAirPlace(c));
        assertFalse(guard.airPlaceAvailable(c));
        assertTrue(guard.checkWanted(c));
        assertEquals(-1, guard.repairSlot(c));
        String e = guard.takeEvent();
        assertTrue(e != null && e.contains("the cache is stale"), String.valueOf(e));
        String a = guard.takeAlert();
        assertTrue(a != null && a.contains("asking the server for the full inventory"), String.valueOf(a));
        assertEquals(List.of(), run(c, 3 * SETTLE), "the guard itself never clicks");
        assertTrue(guard.checkWanted(c), "until the server answers");

        c.offhand = TOTEM; // the server's full inventory
        guard.serverChecked(c);
        e = guard.takeEvent();
        assertTrue(e != null && e.contains("the cache was stale"), String.valueOf(e));
        assertFalse(guard.checkWanted(c));
        assertTrue(guard.mayAirPlace(c));
        assertTrue(guard.airPlaceAvailable(c));
        assertEquals(List.of(), run(c, 3 * SETTLE));
        assertEquals(1, guard.checks());
        assertTrue(guard.describe().contains("1 stale offhand reads"), guard.describe());
    }

    /** The server's answer shows the totem in the hotbar: the repair names its slot. */
    @Test
    void aServerCheckFindingTheTotemInTheInventoryNamesItForTheRepair() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.offhand = WHITE;
        run(c, 1);
        assertTrue(guard.checkWanted(c));
        c.hotbar[2] = TOTEM; // the server's full inventory
        guard.serverChecked(c);
        assertFalse(guard.checkWanted(c));
        assertEquals(38, guard.repairSlot(c));
        assertFalse(guard.mayAirPlace(c), "no airplace before the totem is back");
    }

    /** The server confirms no totem: no further check for CHECK_REPEAT_TICKS, then one more. */
    @Test
    void aServerCheckConfirmingNoTotemIsRepeatedOnlyAfterTheRepeatTime() {
        Client c = Client.withTotem();
        airplaceFrom(c, 4);
        c.offhand = WHITE;
        run(c, 1);
        guard.serverChecked(c);
        assertFalse(guard.checkWanted(c));
        assertFalse(guard.airPlaceAvailable(c), "a block in the offhand, no totem: airplace stays off");
        run(c, AirPlaceGuard.CHECK_REPEAT_TICKS - 1);
        assertFalse(guard.checkWanted(c));
        run(c, 1);
        assertTrue(guard.checkWanted(c));
    }
}
