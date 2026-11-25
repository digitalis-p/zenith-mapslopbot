package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One airplace against a fake ZenithProxy client: the packets in wire order, and the inventory cache as ZenithProxy
 * predicts it per packet (a swap exchanges the selected hotbar slot and the offhand, a select changes the held slot).
 */
class AirPlaceTest {
    private static final String TOTEM = "minecraft:totem_of_undying";
    private static final String WHITE = "minecraft:white_carpet";
    private static final String RED = "minecraft:red_carpet";
    private static final String MOSS = "minecraft:moss_block";
    private static final Pos CELL = new Pos(10, 64, 20);

    /**
     * Fake ZenithProxy client: {@link AirPlace.Port} logs the packets ("select n", "swap", "use x,y,z seq=n",
     * "swing off"); {@link AirPlaceGuard.View} is the inventory cache. A use consumes one item from the offhand at once
     * (the server's slot update).
     */
    private static final class Client implements AirPlace.Port, AirPlaceGuard.View {
        static final Set<String> BLOCK_ITEMS = Set.of(WHITE, RED, MOSS);

        final List<String> packets = new ArrayList<>();
        final @Nullable String[] hotbar = new String[9];
        final int[] hotbarCount = new int[9];
        @Nullable String offhand;
        int offhandCount;
        int held;
        boolean inGame = true;
        boolean containerOpen;
        boolean inventoryBusy;
        boolean selectFails;
        boolean useFails;
        boolean useThrows;
        /** Swap call number (1-based, counted from creation) that throws; 0 = none. */
        int throwOnSwap;
        private int swaps;
        /** ZenithProxy's BlockStatePredictionHandler: incremented per use. */
        private int sequence;

        /** Totem in the offhand, 64 white carpet in hotbar slot 4, slot 0 selected. */
        static Client withTotem() {
            Client c = new Client();
            c.offhand = TOTEM;
            c.offhandCount = 1;
            c.give(4, WHITE, 64);
            return c;
        }

        void give(int slot, @Nullable String item, int count) {
            hotbar[slot] = item;
            hotbarCount[slot] = item == null ? 0 : count;
        }

        /** Player slot of {@code itemId} in the hotbar (36-44), or -1 (this fake has no main inventory). */
        @Override
        public int slotOf(String itemId) {
            for (int i = 0; i < 9; i++) if (itemId.equals(hotbar[i])) return 36 + i;
            return -1;
        }

        /** The packets since the last call. */
        List<String> take() {
            List<String> out = List.copyOf(packets);
            packets.clear();
            return out;
        }

        /** The server's slot updates exchange hotbar slot {@code slot} and the offhand (no packet of ours). */
        void serverSwap(int slot) {
            exchange(slot);
        }

        private void exchange(int slot) {
            String item = hotbar[slot];
            int count = hotbarCount[slot];
            hotbar[slot] = offhand;
            hotbarCount[slot] = offhandCount;
            offhand = item;
            offhandCount = count;
        }

        @Override
        public boolean inGame() {
            return inGame;
        }

        @Override
        public @Nullable String offhand() {
            return offhand;
        }

        @Override
        public @Nullable String hotbar(int i) {
            return hotbar[i];
        }

        @Override
        public int heldSlot() {
            return held;
        }

        @Override
        public boolean containerOpen() {
            return containerOpen;
        }

        @Override public boolean inventoryBusy() { return inventoryBusy; }

        @Override
        public boolean placesBlock(String itemId) {
            return BLOCK_ITEMS.contains(itemId);
        }

        @Override
        public boolean select(int slot) {
            if (!inGame || selectFails) return false;
            if (slot == held) return true; // as Clicker.selectNow: no SetCarriedItem for the selected slot
            packets.add("select " + slot);
            held = slot;
            return true;
        }

        @Override
        public boolean swapOffhand() {
            if (++swaps == throwOnSwap) throw new IllegalStateException("channel closed");
            if (!inGame) return false;
            packets.add("swap");
            exchange(held);
            return true;
        }

        @Override
        public int useItemOnInside(int x, int y, int z) {
            if (useThrows) throw new IllegalStateException("encoder failed");
            if (!inGame || useFails) return -1;
            int seq = ++sequence;
            packets.add("use " + x + "," + y + "," + z + " seq=" + seq);
            if (offhand != null && placesBlock(offhand) && --offhandCount <= 0) {
                offhand = null;
                offhandCount = 0;
            }
            return seq;
        }

        @Override
        public int useItemOnInside(int x, int y, int z, String face) {
            int seq = useItemOnInside(x, y, z);
            if (seq >= 0) packets.set(packets.size() - 1, packets.getLast() + " face=" + face);
            return seq;
        }

        @Override
        public void swingOffhand() {
            packets.add("swing off");
        }
    }

    private final AirPlaceGuard guard = new AirPlaceGuard();

    @Test
    void batchUsesOnePairAndPreservesSingleClickOrder() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        AirPlace ap = new AirPlace(guard, c);
        ap.beginBatch(true);
        try {
            assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
            assertTrue(guard.busy(c), "other inventory logic remains fenced during the batch");
            assertEquals(AirPlace.Result.SENT, ap.place(c, 4, new Pos(11, 64, 20), "east"));
            assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 6, CELL), "no slot change after a click");
        } finally {
            ap.endBatch();
        }
        assertEquals(List.of("swap", "use 10,64,20 seq=1", "swing off",
            "use 11,64,20 seq=2 face=east", "swing off", "swap"), c.take());
        assertEquals(TOTEM, c.offhand);
        assertEquals(62, c.hotbarCount[4]);
        assertFalse(guard.busy(c));
        ap.beginBatch(true);
        try { assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL)); }
        finally { ap.endBatch(); }
        assertEquals(List.of("swap", "use 10,64,20 seq=3", "swing off", "swap"), c.take());
        ap.beginBatch(true);
        ap.endBatch();
        assertTrue(c.take().isEmpty(), "no airplace means no swaps");
    }

    @Test
    void batchBlocksInventoryRequestsAndRestoresAfterAnException() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        AirPlace ap = new AirPlace(guard, c);
        ap.beginBatch(true);
        c.inventoryBusy = true;
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 4, CELL));
        assertTrue(c.take().isEmpty());
        c.inventoryBusy = false;
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
        c.inventoryBusy = true;
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 4, CELL));
        c.inventoryBusy = false;
        c.useThrows = true;
        assertThrows(IllegalStateException.class, () -> {
            try { ap.place(c, 4, CELL); }
            finally { ap.endBatch(); }
        });
        assertEquals(List.of("swap", "use 10,64,20 seq=1", "swing off", "swap"), c.take());
        assertEquals(TOTEM, c.offhand);
        assertFalse(guard.busy(c));
    }

    @Test
    void failedBatchClosingSwapRetainsGuardRecovery() {
        Client c = Client.withTotem();
        c.held = 4;
        guard.tick(c, c);
        AirPlace ap = new AirPlace(guard, c);
        ap.beginBatch(true);
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
        c.throwOnSwap = 2;
        assertThrows(IllegalStateException.class, ap::endBatch);
        assertTrue(guard.busy(c));
        assertFalse(ap.batchOpen(), "next tick must use the guard, not continue the failed batch");
        c.take();
        guard.tick(c, c);
        assertEquals(List.of("swap"), c.take());
        assertEquals(TOTEM, c.offhand);
        assertFalse(guard.busy(c));
    }

    @Test
    void sidewaysPillarsClickTheFaceOfTheirAxis() {
        assertEquals("east", AirPlace.face(io.github.digitalisp.mapart.model.BlockSpec.parse("minecraft:bamboo_block[axis=x]")));
        assertEquals("south", AirPlace.face(io.github.digitalisp.mapart.model.BlockSpec.parse("minecraft:bamboo_block[axis=z]")));
        assertEquals("up", AirPlace.face(io.github.digitalisp.mapart.model.BlockSpec.parse("minecraft:bamboo_block[axis=y]")));
        assertEquals("up", AirPlace.face(io.github.digitalisp.mapart.model.BlockSpec.of(WHITE)));
        assertEquals("up", AirPlace.face(null));
        Client c = Client.withTotem();
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL, "east"));
        assertEquals(List.of("select 4", "swap", "use 10,64,20 seq=1 face=east", "swing off", "swap"), c.packets);
        Client up = Client.withTotem();
        AirPlaceGuard g2 = new AirPlaceGuard();
        g2.tick(up, up);
        assertEquals(AirPlace.Result.SENT, new AirPlace(g2, up).place(up, 4, CELL, "up"));
        assertEquals("use 10,64,20 seq=1", up.packets.get(2), "UP keeps the plain packet");
    }

    @Test
    void selectSwapUseSwingSwapInWireOrder() {
        Client c = Client.withTotem();
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
        assertEquals(List.of("select 4", "swap", "use 10,64,20 seq=1", "swing off", "swap"), c.take());
        assertEquals(4, c.held);
        assertEquals(1, ap.placed());
    }

    @Test
    void noSelectWhenTheSlotIsAlreadyHeld() {
        Client c = Client.withTotem();
        c.held = 4;
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
        assertEquals(List.of("swap", "use 10,64,20 seq=1", "swing off", "swap"), c.take());
    }

    @Test
    void oneUsePerPlacementWithStrictlyIncreasingSequenceNumbers() {
        Client c = Client.withTotem();
        c.give(6, RED, 64);
        AirPlace ap = new AirPlace(guard, c);
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            guard.tick(c, c);
            assertEquals(AirPlace.Result.SENT, ap.place(c, i < 3 ? 4 : 6, new Pos(10 + i, 64, 20)));
            List<String> sent = c.take();
            assertEquals(1, sent.stream().filter(s -> s.startsWith("use")).count(), "one use per place(): " + sent);
            all.addAll(sent);
        }
        List<String> uses = all.stream().filter(s -> s.startsWith("use")).toList();
        assertEquals(List.of("use 10,64,20 seq=1", "use 11,64,20 seq=2", "use 12,64,20 seq=3", "use 13,64,20 seq=4",
            "use 14,64,20 seq=5", "use 15,64,20 seq=6"), uses);
        assertEquals(List.of("select 4", "select 6"), all.stream().filter(s -> s.startsWith("select")).toList(),
            "a select only when the slot changes");
        assertEquals(6, ap.placed());
    }

    @Test
    void offhandItemIsBackAfterEveryPlacement() {
        Client c = Client.withTotem();
        AirPlace ap = new AirPlace(guard, c);
        for (int i = 0; i < 5; i++) {
            guard.tick(c, c);
            assertEquals(AirPlace.Result.SENT, ap.place(c, 4, new Pos(10 + i, 64, 20)));
            assertEquals(TOTEM, c.offhand, "placement " + i);
            assertEquals(WHITE, c.hotbar[4], "placement " + i);
            assertEquals(63 - i, c.hotbarCount[4]);
            assertFalse(guard.busy(c));
            assertTrue(guard.mayAirPlace(c));
        }
        assertEquals(List.of(), drain(c, 3 * AirPlaceGuard.SETTLE_TICKS), "nothing to correct");
    }

    @Test
    void emptyOffhandStaysEmpty() {
        Client c = Client.withTotem();
        c.offhand = null;
        c.offhandCount = 0;
        AirPlace ap = new AirPlace(guard, c);
        for (int i = 0; i < 3; i++) {
            guard.tick(c, c);
            assertEquals(AirPlace.Result.SENT, ap.place(c, 4, new Pos(10 + i, 64, 20)));
            assertNull(c.offhand);
            assertEquals(WHITE, c.hotbar[4]);
        }
        assertEquals(List.of(), drain(c, 3 * AirPlaceGuard.SETTLE_TICKS));
    }

    @Test
    void lastItemOfAStackLeavesTheSlotEmptyAndTheOffhandItemBack() {
        Client c = Client.withTotem();
        c.give(4, WHITE, 2);
        c.held = 4;
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
        guard.tick(c, c);
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, new Pos(11, 64, 20)));
        assertEquals(TOTEM, c.offhand);
        assertNull(c.hotbar[4], "stack used up");
        c.take();
        assertEquals(List.of(), drain(c, 3 * AirPlaceGuard.SETTLE_TICKS), "an empty airplace slot is no swap pattern");
        assertTrue(guard.mayAirPlace(c));
    }

    @Test
    void lastItemOfAStackWithAnEmptyOffhand() {
        Client c = Client.withTotem();
        c.offhand = null;
        c.offhandCount = 0;
        c.give(4, WHITE, 1);
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.SENT, ap.place(c, 4, CELL));
        assertNull(c.offhand);
        assertNull(c.hotbar[4]);
        assertFalse(guard.busy(c));
    }

    @Test
    void notReadyBeforeTheGuardKnowsTheOffhand() {
        Client c = Client.withTotem();
        AirPlace ap = new AirPlace(guard, c);
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 4, CELL), "guard never ticked");
        assertEquals(List.of(), c.take());
    }

    @Test
    void notReadyWithABlockInTheOffhand() {
        Client c = Client.withTotem();
        c.offhand = MOSS;
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 4, CELL), "a desynced swap must never place the offhand item");
        assertEquals(List.of(), c.take());
        assertEquals(MOSS, c.offhand);
    }

    @Test
    void notReadyWhileAContainerIsOpenOrOutOfGame() {
        Client c = Client.withTotem();
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        c.containerOpen = true;
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 4, CELL));
        c.containerOpen = false;
        c.inGame = false;
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 4, CELL));
        assertEquals(List.of(), c.take());
    }

    @Test
    void notReadyForASlotOutsideTheHotbar() {
        Client c = Client.withTotem();
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, -1, CELL));
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 9, CELL));
        assertEquals(List.of(), c.take());
        assertEquals(0, ap.placed());
    }

    @Test
    void failedSelectSendsNothingElse() {
        Client c = Client.withTotem();
        c.selectFails = true;
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.NOT_SENT, ap.place(c, 4, CELL));
        assertEquals(List.of(), c.take());
        assertEquals(TOTEM, c.offhand);
    }

    @Test
    void failedUsePacketStillSwapsBack() {
        Client c = Client.withTotem();
        c.held = 4;
        c.useFails = true;
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertEquals(AirPlace.Result.NOT_SENT, ap.place(c, 4, CELL));
        assertEquals(List.of("swap", "swap"), c.take(), "no swing without a use");
        assertEquals(TOTEM, c.offhand);
        assertEquals(WHITE, c.hotbar[4]);
        assertEquals(0, ap.placed());
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
    }

    @Test
    void exceptionInTheUsePacketStillSwapsBackAndPropagates() {
        Client c = Client.withTotem();
        c.held = 4;
        c.useThrows = true;
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertThrows(IllegalStateException.class, () -> ap.place(c, 4, CELL));
        assertEquals(List.of("swap", "swap"), c.take());
        assertEquals(TOTEM, c.offhand);
        assertEquals(WHITE, c.hotbar[4]);
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
    }

    /**
     * Swap 2 throws: the offhand item is left in hotbar slot 4. The guard holds everything (busy) and sends swap 2 on
     * the next tick, selecting slot 4 first because another slot was selected meanwhile.
     */
    @Test
    void failedSecondSwapIsFinishedByTheGuardOnTheNextTick() {
        Client c = Client.withTotem();
        c.held = 4;
        c.throwOnSwap = 2;
        AirPlace ap = new AirPlace(guard, c);
        guard.tick(c, c);
        assertThrows(IllegalStateException.class, () -> ap.place(c, 4, CELL));
        assertEquals(List.of("swap", "use 10,64,20 seq=1", "swing off"), c.take());
        assertEquals(WHITE, c.offhand);
        assertEquals(TOTEM, c.hotbar[4]);
        assertTrue(guard.busy(c), "swap 2 pending");
        assertFalse(guard.mayAirPlace(c));
        assertEquals(AirPlace.Result.NOT_READY, ap.place(c, 4, new Pos(11, 64, 20)));
        assertEquals(List.of(), c.take());

        c.held = 7; // the owner selected another slot meanwhile
        guard.tick(c, c);
        assertEquals(List.of("select 4", "swap"), c.take());
        assertEquals(TOTEM, c.offhand);
        assertEquals(WHITE, c.hotbar[4]);
        assertFalse(guard.busy(c));
        assertTrue(guard.mayAirPlace(c));
        String event = guard.takeEvent();
        assertTrue(event != null && event.contains("interrupted"), String.valueOf(event));
        assertEquals(List.of(), drain(c, 3 * AirPlaceGuard.SETTLE_TICKS), "settled: nothing more");
    }

    /** Ticks the guard {@code n} times and returns the packets it sent. */
    private List<String> drain(Client c, int n) {
        c.take();
        for (int i = 0; i < n; i++) guard.tick(c, c);
        return c.take();
    }
}
