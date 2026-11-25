package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hotbar move fence ({@code build.hotbarMoveFence}, {@link MoveFence}, {@link InventoryEchoes}). Live 2b2t
 * 2026-10-12: every "placed as" cell came within 0.5 s of a hotbar move, 69 of 83 within 0.15 s; server updates sent
 * before the move was handled put the slot's old item back into ZenithProxy's cache until the move's own answer.
 */
class MoveFenceTest {
    private static final String CLAY = "minecraft:clay";
    private static final String CANDLE = "minecraft:white_candle";

    @Test
    void waitsForAReportOfTheMovedItemAfterTheClick() {
        InventoryEchoes echoes = new InventoryEchoes();
        echoes.report(5, CLAY); // before the click: says nothing about it
        MoveFence fence = new MoveFence();
        fence.arm(5, CANDLE, echoes.mark(), 100);
        assertTrue(fence.waiting(101, echoes));
        assertEquals(5, fence.slot(), "only this hotbar slot is unsafe");
        echoes.report(4, CANDLE); // another slot
        assertTrue(fence.waiting(102, echoes));
        echoes.report(5, CLAY); // sent before the server handled the move: the old item
        assertTrue(fence.waiting(103, echoes));
        echoes.report(5, CANDLE); // the answer
        assertFalse(fence.waiting(104, echoes));
        assertEquals(-1, fence.slot());
        assertEquals(1, fence.moves());
        assertEquals(0, fence.timeouts());
        assertEquals(4, fence.waited());
        assertEquals("; 1 hotbar move waited 4 t for the server", fence.summary());
        assertFalse(fence.waiting(105, echoes), "disarmed");
    }

    @Test
    void aReportFromBeforeTheClickNeverEndsTheWait() {
        InventoryEchoes echoes = new InventoryEchoes();
        echoes.report(2, CANDLE);
        MoveFence fence = new MoveFence();
        fence.arm(2, CANDLE, echoes.mark(), 0);
        assertTrue(fence.waiting(1, echoes), "the report came before the mark");
    }

    @Test
    void endsAfterTheTimeoutWithoutAnAnswerAndSaysSoOnce() {
        InventoryEchoes echoes = new InventoryEchoes();
        MoveFence fence = new MoveFence();
        fence.arm(3, CANDLE, echoes.mark(), 10);
        echoes.report(3, CLAY); // refused: the server keeps the old item
        assertTrue(fence.waiting(10 + MoveFence.TIMEOUT, echoes));
        assertFalse(fence.waiting(11 + MoveFence.TIMEOUT, echoes));
        assertEquals(-1, fence.slot());
        assertEquals(1, fence.timeouts());
        assertNotNull(fence.takeTimeout());
        assertNull(fence.takeTimeout());
        assertTrue(fence.summary().contains("1 without an answer"), fence.summary());
    }

    @Test
    void noMovesNoSummary() {
        assertEquals("", new MoveFence().summary());
        assertFalse(new MoveFence().waiting(0, new InventoryEchoes()));
    }

    @Test
    void speculativeMoveWaitsThroughTheFirstTimeoutForItsAnswer() {
        InventoryEchoes echoes = new InventoryEchoes();
        echoes.report(3, CLAY);
        MoveFence fence = new MoveFence();
        echoes.quarantine(3, CANDLE, echoes.mark());
        fence.arm(3, CANDLE, echoes.mark(), 10, true);
        echoes.report(3, CLAY); // a cancelled move can leave only stale reports
        assertTrue(fence.waiting(51, echoes));
        assertEquals(3, fence.slot());
        assertNull(fence.takeTimeout(), "the session logs speculative recovery");
        assertTrue(fence.waiting(90, echoes));
        assertNull(fence.takeTimeout());
        assertEquals(1, fence.timeouts());
        assertEquals(80, fence.waited(), "unresolved speculative wait is counted");
        echoes.report(3, CANDLE);
        assertFalse(fence.waiting(91, echoes));
        assertEquals(81, fence.waited());
    }

    @Test
    void quarantineSurvivesTaskReplacementUntilTheExpectedReport() {
        InventoryEchoes echoes = new InventoryEchoes();
        echoes.report(3, CLAY);
        echoes.quarantine(3, CANDLE, echoes.mark());
        echoes.report(3, CLAY);
        assertEquals(3, echoes.quarantinedSlot());
        Hotbar replacement = new Hotbar(new Object(), null, () -> true, true);
        // Exercise the real cross-task Hotbar fence through the session recorder.
        InventoryEchoes.SESSION.quarantine(3, CANDLE, InventoryEchoes.SESSION.mark());
        try {
            assertEquals(3, replacement.fencedSlot(100));
            assertTrue(replacement.busy(100));
            InventoryEchoes.SESSION.report(3, CLAY);
            assertEquals(3, replacement.fencedSlot(101));
            InventoryEchoes.SESSION.report(3, CANDLE);
            assertEquals(-1, replacement.fencedSlot(102));
            assertFalse(replacement.busy(102));
        } finally {
            InventoryEchoes.SESSION.reset();
        }
        echoes.report(4, CANDLE);
        assertEquals(3, echoes.quarantinedSlot());
        echoes.report(3, CANDLE);
        assertEquals(-1, echoes.quarantinedSlot());
        echoes.quarantine(3, CLAY, echoes.mark());
        echoes.reset();
        assertEquals(-1, echoes.quarantinedSlot(), "new connection discards old inventory predictions/reports");
    }

    @Test
    void missingSpeculativeAnswerReleasesByTheSecondBoundButLeavesTheSlotUnknown() {
        InventoryEchoes echoes = new InventoryEchoes();
        echoes.report(3, CLAY);
        long mark = echoes.mark();
        echoes.quarantine(3, CANDLE, mark);
        MoveFence fence = new MoveFence();
        fence.arm(3, CANDLE, mark, 0, true);
        for (int tick = 1; tick < MoveFence.TIMEOUT; tick++) {
            echoes.tick(true, false);
            assertTrue(fence.waiting(tick, echoes));
            assertFalse(echoes.needsResync());
        }
        echoes.tick(true, false);
        assertTrue(echoes.needsResync());
        assertNotNull(echoes.takeEvent());
        echoes.resyncSent();
        assertFalse(echoes.needsResync(), "one resync, not a packet every tick");
        for (int tick = MoveFence.TIMEOUT + 1; tick < MoveFence.TIMEOUT + InventoryEchoes.RESYNC_TIMEOUT; tick++) {
            echoes.tick(true, false);
            assertTrue(fence.waiting(tick, echoes));
        }
        echoes.tick(true, false);
        assertFalse(fence.waiting(140, echoes));
        assertEquals(-1, echoes.quarantinedSlot());
        assertFalse(echoes.known(3), "prediction must never become placement evidence");
        assertTrue(echoes.known(2));
        assertNotNull(echoes.takeEvent());
        echoes.report(2, CANDLE);
        assertFalse(echoes.known(3), "another slot cannot resolve uncertainty");
        echoes.report(3, CLAY);
        assertTrue(echoes.known(3), "the report names the actual item even if the move was refused");
        assertTrue(echoes.reportedSince(3, mark, CLAY));
    }

    @Test
    void resyncFullAnswerCanRejectTheMoveAndStillReleaseIt() {
        InventoryEchoes echoes = new InventoryEchoes();
        echoes.quarantine(3, CANDLE, echoes.mark());
        for (int tick = 0; tick < MoveFence.TIMEOUT; tick++) echoes.tick(true, false);
        String[] full = new String[9];
        full[3] = CLAY;
        echoes.fullReport(full);
        assertEquals(3, echoes.quarantinedSlot(), "a stale full answer before execution is not the resync");
        echoes.resyncSent();
        echoes.report(3, CLAY);
        assertEquals(3, echoes.quarantinedSlot(), "a wrong-item slot update is not the full resync answer");
        echoes.fullReport(full);
        assertEquals(-1, echoes.quarantinedSlot());
        assertFalse(echoes.recovering());
        assertTrue(echoes.known(3));
    }

    @Test
    void secondBoundAlsoAppliesWhenNoSafeResyncTickExists() {
        InventoryEchoes echoes = new InventoryEchoes();
        echoes.quarantine(3, CANDLE, echoes.mark());
        for (int tick = 0; tick < MoveFence.TIMEOUT + InventoryEchoes.RESYNC_TIMEOUT; tick++) echoes.tick(true, false);
        assertEquals(-1, echoes.quarantinedSlot());
        assertFalse(echoes.known(3));
    }

    @Test
    void switchOffAndPriorityInventoryImmediatelyClearTheHold() {
        for (boolean switchOff : List.of(false, true)) {
            InventoryEchoes echoes = new InventoryEchoes();
            echoes.quarantine(3, CANDLE, echoes.mark());
            echoes.tick(!switchOff, !switchOff); // external eating/totem/container request, or kill switch
            assertEquals(-1, echoes.quarantinedSlot());
            assertFalse(echoes.recovering());
            assertFalse(echoes.known(3), "abandoned content stays unknown to placement");
        }
    }

    @Test
    void ordinaryTaskHotbarNeverInheritsTheSpeculativeHold() {
        for (String task : List.of("eat", "restock", "totem", "container")) {
            InventoryEchoes.SESSION.quarantine(3, CANDLE, InventoryEchoes.SESSION.mark());
            try {
                Hotbar priority = new Hotbar(task, null, () -> true);
                assertFalse(priority.busy(1), task);
                assertEquals(-1, priority.fencedSlot(1), task);
                assertFalse(InventoryEchoes.SESSION.known(3), "placement alone still excludes abandoned slots");
            } finally {
                InventoryEchoes.SESSION.reset();
            }
        }
    }

    @Test
    void offhandRestorationNeverWaitsForQuarantine() {
        InventoryEchoes.SESSION.quarantine(3, CANDLE, InventoryEchoes.SESSION.mark());
        try {
            OffhandGuard guard = new OffhandGuard();
            guard.swappedOut("minecraft:totem_of_undying", 3, 1);
            assertEquals(OffhandGuard.Action.SWAP_BACK,
                guard.restore(2, CANDLE, "minecraft:totem_of_undying").action());
            InventoryEchoes.SESSION.tick(true, true); // guard repair has inventory priority on the bot tick
            assertEquals(-1, InventoryEchoes.SESSION.quarantinedSlot());
        } finally {
            InventoryEchoes.SESSION.reset();
        }
    }

    @Test
    void resyncIsASameSlotNoOpWithAnImpossibleStateId() {
        var cursor = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack(1, 2);
        var packet = Hotbar.resyncPacket(3, cursor);
        assertEquals(0, packet.getContainerId());
        assertEquals(-1, packet.getStateId());
        assertEquals(ItemIds.HOTBAR_START + 3, packet.getSlot());
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.MOVE_TO_HOTBAR_SLOT,
            packet.getActionType());
        assertEquals(org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction.SLOT_4,
            packet.getActionParam());
        assertTrue(packet.getChangedSlots().isEmpty(), "resync adds no inventory prediction");
        assertEquals(cursor, packet.getCarriedItem());
    }

    @Test
    void aNewConnectionForgetsTheReports() {
        InventoryEchoes echoes = new InventoryEchoes();
        long mark = echoes.mark();
        echoes.report(1, CANDLE);
        echoes.reset();
        assertFalse(echoes.reportedSince(1, mark, CANDLE));
        assertTrue(echoes.mark() > mark, "marks stay comparable");
    }

    /**
     * The live race in a model of the wire: a lane's cells need materials in runs, two hotbar slots hold them, the rest
     * wait in the main inventory. Each placement makes the server send the held slot (its count) and, as 2b2t does,
     * the whole inventory behind it; a move is answered with the whole inventory. Packets take {@code delay} ticks each
     * way and keep their order. ZenithProxy's cache takes a click's prediction when it goes out and every server packet
     * when it arrives. Without the fence a server packet sent before a move was handled (a placement's update, or the
     * answer to the move before) puts the slot's old item back into the cache, and the lane clicks that slot for the
     * old item while the server holds the new one. With it, no wrong block, every cell placed. Without any server
     * update the cache never goes stale: no wrong block even without the fence (the model's control).
     */
    @Test
    void theFenceRemovesTheWrongBlocksOfTheStaleUpdateRace() {
        int wrongWithout = 0;
        int placedWithout = 0;
        for (int delay = 1; delay <= 4; delay++) {
            for (long seed = 1; seed <= 20; seed++) {
                Wire off = new Wire(delay, seed, false, true).run();
                Wire on = new Wire(delay, seed, true, true).run();
                wrongWithout += off.wrong;
                placedWithout += off.placed;
                assertEquals(0, on.wrong, "fence on, delay " + delay + " seed " + seed);
                assertEquals(on.cells.length, on.placed, "fence on: every cell placed");
                assertEquals(0, on.fence.timeouts(), "every move answered");
                assertEquals(0, new Wire(delay, seed, false, false).run().wrong, "no server updates, no fence");
            }
        }
        assertTrue(wrongWithout > placedWithout / 100, "the model shows the live race without the fence: "
            + wrongWithout + " wrong of " + placedWithout);
    }

    /**
     * {@code build.hotbarMoveForeignStateId} in the wire model with state ids: the server counts one per inventory
     * update it sends and answers a move with the whole inventory only when the move's state id is not its own; when it
     * is, the prediction was right and it sends nothing. The cache's state id plus one (the old move) is the server's
     * own whenever exactly one update is on the way, which local Paper (one slot update per placement, nothing behind
     * it) hits after an answered move and a single placement (live local: 31 % of the moves waited out the 40 ticks).
     * Unanswered, the move's slot can also keep the old item a stale update put back into the cache, and the model's
     * lane, which has no full-inventory request, then waits for an update that never comes. With -1 every move is
     * answered, with 2b2t's whole inventory behind each slot update too, every cell placed and none wrong.
     */
    @Test
    void aForeignStateIdGetsEveryMoveAnswered() {
        int[] moves = new int[2];
        int[] unanswered = new int[2];
        int[] unfinished = new int[2];
        for (boolean fullBehind : List.of(false, true)) {
            int i = fullBehind ? 1 : 0;
            for (int delay = 1; delay <= 4; delay++) {
                for (long seed = 1; seed <= 20; seed++) {
                    Wire before = new Wire(delay, seed, true, true).stateIds(fullBehind, false).run();
                    Wire foreign = new Wire(delay, seed, true, true).stateIds(fullBehind, true).run();
                    String at = (fullBehind ? "2b2t" : "Paper") + " delay " + delay + " seed " + seed;
                    assertEquals(0, before.wrong, at);
                    assertEquals(0, foreign.wrong, at);
                    assertEquals(foreign.cells.length, foreign.placed, at);
                    assertEquals(0, foreign.fence.timeouts(), "every move answered: " + at);
                    assertTrue(foreign.ticks <= before.ticks, at + ": " + foreign.ticks + " vs " + before.ticks);
                    moves[i] += before.fence.moves();
                    unanswered[i] += before.fence.timeouts();
                    if (before.placed < before.cells.length) unfinished[i]++;
                }
            }
        }
        System.out.printf("FENCE-WIRE before (cache state id + 1), 80 runs each: Paper %d of %d moves unanswered,"
                + " %d runs unfinished; 2b2t %d of %d, %d unfinished. With -1: none%n",
            unanswered[0], moves[0], unfinished[0], unanswered[1], moves[1], unfinished[1]);
        assertTrue(unanswered[0] > moves[0] / 100, "the old state id goes unanswered on Paper: " + unanswered[0]);
        assertEquals(-1, Hotbar.moveStateId(7, true));
        assertEquals(7, Hotbar.moveStateId(7, false));
    }

    /** Client, server and the packets between them, tick by tick. */
    private static final class Wire {
        /** S select, P place, M move (up); L one hotbar slot, C the whole inventory (down); state: a state id. */
        record Msg(long at, char kind, int a, int b, String item, String[] hotbar, List<String> main, int state) {
            Msg(long at, char kind, int a, int b, String item) {
                this(at, kind, a, b, item, null, null, 0);
            }

            Msg(long at, char kind, int a, int b, String item, int state) {
                this(at, kind, a, b, item, null, null, state);
            }
        }

        private static final String[] MATERIALS = {"minecraft:a", "minecraft:b", "minecraft:c", "minecraft:d", "minecraft:e"};
        private static final int[] SLOTS = {1, 2};

        final int delay;
        final boolean fenceOn;
        /**
         * The server sends the held slot and the whole inventory after each placement (2b2t does) and the whole inventory
         * as a move's answer. False (the control): it sends nothing, as when every prediction matches and no state id
         * is stale.
         */
        final boolean serverUpdates;
        final String[] cells;
        final String[] serverBar = new String[9];
        final List<String> serverMain = new ArrayList<>();
        int serverHeld = 1;
        final String[] cacheBar = new String[9];
        final List<String> cacheMain = new ArrayList<>();
        int cacheHeld = 1;
        final Deque<Msg> up = new ArrayDeque<>();
        final Deque<Msg> down = new ArrayDeque<>();
        final InventoryEchoes echoes = new InventoryEchoes();
        final MoveFence fence = new MoveFence();
        int next;
        int placed;
        int wrong;
        int requestTicks;
        long ticks;
        /**
         * State ids ({@link #stateIds}): the server answers a move only when its state id is not the server's, and
         * sends the whole inventory behind each slot update only with {@code fullBehind} (2b2t; local Paper does not).
         * Off: every move answered, the whole inventory behind each slot update, as the model was written.
         */
        boolean stateIds;
        boolean fullBehind = true;
        boolean foreign;
        int serverState;
        int cacheState;

        Wire(int delay, long seed, boolean fenceOn, boolean serverUpdates) {
            this.delay = delay;
            this.fenceOn = fenceOn;
            this.serverUpdates = serverUpdates;
            Random r = new Random(seed);
            List<String> c = new ArrayList<>();
            while (c.size() < 300) {
                String m = MATERIALS[r.nextInt(MATERIALS.length)];
                for (int k = 1 + r.nextInt(4); k > 0; k--) c.add(m);
            }
            cells = c.toArray(new String[0]);
            serverBar[1] = MATERIALS[0];
            serverBar[2] = MATERIALS[1];
            serverMain.addAll(List.of(MATERIALS[2], MATERIALS[3], MATERIALS[4]));
            System.arraycopy(serverBar, 0, cacheBar, 0, 9);
            cacheMain.addAll(serverMain);
        }

        Wire stateIds(boolean fullBehind, boolean foreign) {
            this.stateIds = true;
            this.fullBehind = fullBehind;
            this.foreign = foreign;
            return this;
        }

        Wire run() {
            long t = 0;
            for (; t < 20_000 && (next < cells.length || !up.isEmpty() || !down.isEmpty()); t++) {
                while (!down.isEmpty() && down.peekFirst().at() <= t) receive(down.pollFirst());
                while (!up.isEmpty() && up.peekFirst().at() <= t) serve(up.pollFirst(), t);
                if (next < cells.length) act(t);
            }
            ticks = t;
            return this;
        }

        /** The server, in packet order. */
        private void serve(Msg m, long t) {
            switch (m.kind()) {
                case 'S' -> serverHeld = m.a();
                case 'P' -> {
                    placed++;
                    if (!serverBar[serverHeld].equals(m.item())) wrong++;
                    if (serverUpdates) {
                        down.add(new Msg(t + delay, 'L', serverHeld, 0, serverBar[serverHeld], nextState())); // the count
                        if (fullBehind) down.add(full(t)); // 2b2t's whole inventory behind it
                    }
                }
                case 'M' -> {
                    String held = serverBar[m.b()];
                    serverBar[m.b()] = serverMain.get(m.a());
                    serverMain.set(m.a(), held);
                    // the answer; with its own state id the server takes the click's prediction as the client's
                    if (serverUpdates && (!stateIds || m.state() != serverState)) down.add(full(t));
                }
                default -> throw new IllegalStateException();
            }
        }

        private Msg full(long t) {
            return new Msg(t + delay, 'C', 0, 0, null, serverBar.clone(), List.copyOf(serverMain), nextState());
        }

        /** The server's state id for the update it sends now (one per update, 0-32767). */
        private int nextState() {
            serverState = (serverState + 1) & 32767;
            return serverState;
        }

        /** ZenithProxy's cache takes every server packet as it arrives; the fence counts the reports. */
        private void receive(Msg m) {
            cacheState = m.state();
            if (m.kind() == 'L') {
                cacheBar[m.a()] = m.item();
                echoes.report(m.a(), m.item());
            } else {
                for (int i = 0; i < 9; i++) {
                    cacheBar[i] = m.hotbar()[i];
                    echoes.report(i, m.hotbar()[i]);
                }
                cacheMain.clear();
                cacheMain.addAll(m.main());
            }
        }

        /** The lane, from the cache: select and click from the hotbar, else move the item in (one request at a time). */
        private void act(long t) {
            if (requestTicks > 0) { // ZenithProxy's request: the move, the select a tick later, then done
                requestTicks--;
                return;
            }
            if (fenceOn && fence.waiting(t, echoes)) return;
            String need = cells[next];
            int h = Arrays.asList(cacheBar).indexOf(need);
            if (h >= 0) {
                if (cacheHeld != h) {
                    cacheHeld = h;
                    up.add(new Msg(t + delay, 'S', h, 0, null));
                }
                up.add(new Msg(t + delay, 'P', 0, 0, need));
                next++;
                return;
            }
            int m = cacheMain.indexOf(need);
            if (m < 0) return; // the cache lost it: wait for the server's next update
            int target = farthest();
            if (fenceOn) fence.arm(target, need, echoes.mark(), t);
            cacheMain.set(m, cacheBar[target]); // ZenithProxy's prediction of the swap
            cacheBar[target] = need;
            cacheHeld = target;
            cacheState = (cacheState + 1) & 32767; // ZenithProxy's incrementStateId
            up.add(new Msg(t + delay, 'M', m, target, null, Hotbar.moveStateId(cacheState, foreign)));
            up.add(new Msg(t + delay, 'S', target, 0, null));
            requestTicks = 1;
        }

        /** Belady: the material slot whose item the cells left need last. */
        private int farthest() {
            int best = SLOTS[0];
            int far = -1;
            for (int s : SLOTS) {
                int u = cells.length;
                for (int i = next; i < cells.length; i++) {
                    if (cells[i].equals(cacheBar[s])) {
                        u = i;
                        break;
                    }
                }
                if (u > far) {
                    far = u;
                    best = s;
                }
            }
            return best;
        }
    }
}
