package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WithdrawMode;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Kind;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Plan;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Step;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WithdrawMode#TOP_UP} and {@link WithdrawMode#EXACT}: the planned clicks applied to a vanilla chest menu
 * ({@link VanillaClickModel}, a single chest: container 0..26, main 27..53, hotbar 54..62). Each case checks the
 * player's and the container's contents afterwards, the slots used, that the cursor ends empty and that the plan's
 * {@code expected} is what the player gained.
 */
class WithdrawModesTest {
    private static final int TOP = 27;
    private static final String CARPET = "minecraft:white_carpet";
    private static final String LIME = "minecraft:lime_carpet";
    private static final String STONE = "minecraft:stone";
    private static final String MAP = "minecraft:map";
    private static final String PANE = "minecraft:glass_pane";

    private static VanillaClickModel chest() {
        return new VanillaClickModel(TOP);
    }

    private static Plan plan(VanillaClickModel m, Map<String, Integer> wanted, WithdrawMode mode) {
        return TransferPlanner.planWithdraw(m.containerStacks(), wanted, m.playerStacks(), m.playerFree(), mode,
            m.emptyPlayerSlots());
    }

    /** Applies the plan; the cursor must end empty and every item must have moved as {@code expected} says. */
    private static void run(VanillaClickModel m, Plan p) {
        Map<String, Integer> before = new LinkedHashMap<>();
        for (String id : p.expected().keySet()) before.put(id, m.playerCount(id));
        m.apply(p.steps());
        assertNull(m.cursor(), "cursor empty at the end: " + m);
        p.expected().forEach((id, n) -> assertEquals((int) n, m.playerCount(id) - before.get(id), "moved " + id));
    }

    private static Map<String, Integer> want(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static List<Step> steps(Object... kindSlot) {
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < kindSlot.length; i += 2) out.add(new Step((Kind) kindSlot[i], (Integer) kindSlot[i + 1]));
        return out;
    }

    private static boolean allShiftClicks(Plan p) {
        return p.steps().stream().allMatch(s -> s.kind() == Kind.QUICK_MOVE);
    }

    // ------------------------------------------------------------------------------------------------ TOP_UP

    @Test
    void topUpSavesASlot() {
        VanillaClickModel m = chest().put(60, CARPET, 10, 64);
        for (int i = 0; i < 3; i++) m.put(i, CARPET, 64, 64);
        VanillaClickModel whole = chest().put(60, CARPET, 10, 64);
        for (int i = 0; i < 3; i++) whole.put(i, CARPET, 64, 64);

        Plan p = plan(m, want(CARPET, 118), WithdrawMode.TOP_UP);
        assertEquals(steps(Kind.LEFT, 0, Kind.LEFT, 60, Kind.LEFT, 0, Kind.QUICK_MOVE, 1), p.steps());
        assertEquals(118, p.expected().get(CARPET));
        assertNull(p.problem());
        assertEquals(List.of(1), p.clicks().stream().map(TransferPlanner.Stack::slot).toList(), "the whole stack only");
        run(m, p);
        assertEquals(128, m.playerCount(CARPET));
        assertEquals(2, m.playerSlots(CARPET), "128 in 2 slots");
        assertEquals(10, m.at(0).count(), "the 10 went back into the chest stack they came from");
        assertEquals(64, m.at(2).count());

        Plan w = plan(whole, want(CARPET, 118), WithdrawMode.WHOLE_STACKS);
        run(whole, w);
        assertEquals(138, whole.playerCount(CARPET));
        assertEquals(3, whole.playerSlots(CARPET), "whole stacks: one slot more");
    }

    @Test
    void topUpNotWorthItIsPlainShiftClicks() {
        // 10 held + 64 wanted: whole stacks give 74 in 2 slots, a top-up would give 128 in 2 slots
        VanillaClickModel m = chest().put(60, CARPET, 10, 64).put(0, CARPET, 64, 64).put(1, CARPET, 64, 64);
        Plan p = plan(m, want(CARPET, 64), WithdrawMode.TOP_UP);
        Plan w = plan(m, want(CARPET, 64), WithdrawMode.WHOLE_STACKS);
        assertTrue(allShiftClicks(p));
        assertEquals(w, p);
        run(m, p);
        assertEquals(74, m.playerCount(CARPET));
        assertEquals(2, m.playerSlots(CARPET));
    }

    @Test
    void topUpWithoutAFullStackInTheChestIsPlain() {
        VanillaClickModel m = chest().put(60, CARPET, 10, 64).put(0, CARPET, 40, 64).put(1, CARPET, 30, 64);
        Plan p = plan(m, want(CARPET, 50), WithdrawMode.TOP_UP);
        assertTrue(allShiftClicks(p), p.steps().toString());
        assertEquals(plan(m, want(CARPET, 50), WithdrawMode.WHOLE_STACKS), p);
        run(m, p);
    }

    @Test
    void topUpOfSeveralItemsComesBeforeEveryShiftClick() {
        VanillaClickModel m = chest()
            .put(60, CARPET, 10, 64).put(61, STONE, 20, 64)
            .put(0, CARPET, 64, 64).put(1, CARPET, 64, 64).put(2, CARPET, 64, 64)
            .put(3, STONE, 64, 64).put(4, STONE, 64, 64)
            .put(5, LIME, 64, 64);
        Plan p = plan(m, want(CARPET, 118, LIME, 64, STONE, 44), WithdrawMode.TOP_UP);
        assertNull(p.problem());
        int lastCursor = -1;
        int firstShift = Integer.MAX_VALUE;
        for (int i = 0; i < p.steps().size(); i++) {
            if (p.steps().get(i).kind() == Kind.QUICK_MOVE) firstShift = Math.min(firstShift, i);
            else lastCursor = i;
        }
        assertEquals(5, lastCursor, "two triples: " + p.steps());
        assertTrue(lastCursor < firstShift, "all top-ups before the shift-clicks: " + p.steps());
        assertEquals(steps(Kind.LEFT, 0, Kind.LEFT, 60, Kind.LEFT, 0, Kind.LEFT, 3, Kind.LEFT, 61, Kind.LEFT, 3),
            p.steps().subList(0, 6));
        assertEquals(Map.of(CARPET, 118, LIME, 64, STONE, 44), p.expected());
        run(m, p);
        assertEquals(128, m.playerCount(CARPET));
        assertEquals(2, m.playerSlots(CARPET));
        assertEquals(64, m.playerCount(STONE), "20 + 44: topped up, nothing else");
        assertEquals(1, m.playerSlots(STONE));
        assertEquals(20, m.at(3).count());
        assertEquals(64, m.playerCount(LIME));
    }

    @Test
    void topUpOfAStackOneShortOfFull() {
        VanillaClickModel m = chest().put(45, CARPET, 63, 64).put(0, CARPET, 64, 64).put(1, CARPET, 64, 64);
        Plan p = plan(m, want(CARPET, 65), WithdrawMode.TOP_UP);
        assertEquals(steps(Kind.LEFT, 0, Kind.LEFT, 45, Kind.LEFT, 0, Kind.QUICK_MOVE, 1), p.steps());
        assertEquals(65, p.expected().get(CARPET));
        run(m, p);
        assertEquals(128, m.playerCount(CARPET));
        assertEquals(2, m.playerSlots(CARPET));
        assertEquals(63, m.at(0).count());
    }

    @Test
    void topUpMayOvershootASmallRequestButKeepsOneSlot() {
        // 10 held, 5 wanted: a whole stack would leave 74 in 2 slots; the top-up leaves 64 in 1 (the planner's model)
        VanillaClickModel m = chest().put(60, CARPET, 10, 64).put(0, CARPET, 64, 64);
        Plan p = plan(m, want(CARPET, 5), WithdrawMode.TOP_UP);
        assertEquals(steps(Kind.LEFT, 0, Kind.LEFT, 60, Kind.LEFT, 0), p.steps());
        assertNull(p.problem());
        run(m, p);
        assertEquals(64, m.playerCount(CARPET));
        assertEquals(1, m.playerSlots(CARPET));
        assertEquals(10, m.containerCount(CARPET));
    }

    @Test
    void topUpOfOnePartialOfTwoSavesNoSlot() {
        // partials 40 and 5, 88 wanted: topping up the 40 alone (24) leaves the 5 to take the overshoot of the stack
        // after it, 3 slots either way; so whole stacks, the fewer clicks (before the fix: one triple, still 3 slots)
        VanillaClickModel m = chest().put(30, CARPET, 5, 64).put(31, CARPET, 40, 64).put(0, CARPET, 64, 64)
            .put(1, CARPET, 64, 64);
        Plan p = plan(m, want(CARPET, 88), WithdrawMode.TOP_UP);
        assertTrue(allShiftClicks(p), p.steps().toString());
        run(m, p);
        assertEquals(45 + 128, m.playerCount(CARPET));
        assertEquals(3, m.playerSlots(CARPET));
    }

    /** ZenithProxy predicts each top-up click exactly as vanilla does it: the cache never needs a correction. */
    @Test
    void topUpPredictionsMatchVanilla() {
        for (int r : new int[]{1, 10, 32, 63}) {
            VanillaClickModel m = chest().put(60, CARPET, r, 64).put(0, CARPET, 64, 64).put(1, CARPET, 64, 64);
            Plan p = plan(m, want(CARPET, 128 - r), WithdrawMode.TOP_UP);
            VanillaClickModel z = m.zenithPrediction();
            for (Step s : p.steps()) {
                if (s.kind() == Kind.QUICK_MOVE) break;
                m.click(s);
                z.click(s);
                assertEquals(List.of(), z.differences(m), "r " + r + " after " + s);
            }
            assertEquals(0, z.unsent);
            assertNull(z.cursor(), "the cache shows an empty cursor before the shift-clicks");
        }
    }

    @Test
    void topUpFillsEveryPartialStackOfTheItem() {
        // the lane's hotbar moves left white in two partial stacks (20 + 30). The restock asks for the stack-aligned
        // target 448 (7 stacks) minus the 50 held: both partials are topped up, then exactly 5 whole stacks
        VanillaClickModel m = chest().put(60, CARPET, 20, 64).put(30, CARPET, 30, 64);
        for (int i = 0; i < 12; i++) m.put(i, CARPET, 64, 64);
        Plan p = plan(m, want(CARPET, 398), WithdrawMode.TOP_UP);
        assertEquals(steps(Kind.LEFT, 0, Kind.LEFT, 30, Kind.LEFT, 0, Kind.LEFT, 1, Kind.LEFT, 60, Kind.LEFT, 1),
            p.steps().subList(0, 6), "the fuller partial first, each from its own full stack");
        assertEquals(5, p.steps().stream().filter(s -> s.kind() == Kind.QUICK_MOVE).count());
        assertNull(p.problem());
        run(m, p);
        assertEquals(448, m.playerCount(CARPET));
        assertEquals(7, m.playerSlots(CARPET), "7 slots, as the restock planner counts: ceil(448 / 64)");
        assertEquals(30 + 20, m.containerCount(CARPET) - (12 - 2 - 5) * 64, "both overflows back in the chest");
    }

    @Test
    void topUpMakesTheSlotsTheRestockPlannerCounts() {
        // every split of the held stock, every need: the bot's top-up ends with what the restock planners count for
        // round 1, max(slots held, ceil(target / 64)) slots, target = roundUp(need, 64), asked as target - held
        java.util.Random r = new java.util.Random(11);
        for (int run = 0; run < 2_000; run++) {
            VanillaClickModel m = chest();
            for (int i = 0; i < 20; i++) m.put(i, CARPET, 64, 64);
            int partials = 1 + r.nextInt(3);
            int fulls = r.nextInt(3);
            int held = 0;
            int slot = TOP;
            for (int i = 0; i < fulls; i++, slot++) {
                m.put(slot, CARPET, 64, 64);
                held += 64;
            }
            for (int i = 0; i < partials; i++, slot += 3) {
                int c = 1 + r.nextInt(63);
                m.put(slot, CARPET, c, 64);
                held += c;
            }
            int slotsHeld = fulls + partials;
            int need = held + 1 + r.nextInt(640);
            int target = 64 * ((need + 63) / 64);
            Plan p = plan(m, want(CARPET, target - held), WithdrawMode.TOP_UP);
            String why = "run " + run + ": held " + held + " in " + slotsHeld + " slots, need " + need + ", " + m;
            assertNull(p.problem(), why);
            run(m, p);
            assertTrue(m.playerCount(CARPET) >= need, why);
            assertTrue(m.playerSlots(CARPET) <= Math.max(slotsHeld, target / 64), why + " -> " + m.playerSlots(CARPET)
                + " slots");
        }
    }

    // ------------------------------------------------------------------------------------------------ EXACT

    @Test
    void exactOneMapFromAStackOf64() {
        VanillaClickModel m = chest().put(4, MAP, 64, 64).put(10, PANE, 64, 64).put(56, CARPET, 64, 64);
        Plan p = plan(m, want(MAP, 1), WithdrawMode.EXACT);
        assertEquals(steps(Kind.LEFT, 4, Kind.RIGHT, 27, Kind.LEFT, 4), p.steps());
        assertEquals(Map.of(MAP, 1), p.expected());
        assertTrue(p.clicks().isEmpty(), "nothing leaves the chest whole");
        assertNull(p.problem());
        run(m, p);
        assertEquals(1, m.at(27).count(), "one map in the first empty slot");
        assertEquals(63, m.at(4).count(), "63 back");
        assertEquals(1, m.playerSlots(MAP));
    }

    @Test
    void exactOntoAPartialStackUsesNoNewSlot() {
        VanillaClickModel m = chest().put(40, PANE, 3, 64).put(2, PANE, 64, 64);
        int free = m.playerFree();
        Plan p = plan(m, want(PANE, 2), WithdrawMode.EXACT);
        assertEquals(steps(Kind.LEFT, 2, Kind.RIGHT, 40, Kind.RIGHT, 40, Kind.LEFT, 2), p.steps());
        run(m, p);
        assertEquals(5, m.at(40).count());
        assertEquals(1, m.playerSlots(PANE));
        assertEquals(free, m.playerFree());
        assertEquals(62, m.containerCount(PANE));
    }

    @Test
    void exactStackOfExactlyTheCountIsOneShiftClick() {
        VanillaClickModel m = chest().put(3, MAP, 64, 64).put(7, MAP, 1, 64);
        Plan p = plan(m, want(MAP, 1), WithdrawMode.EXACT);
        assertEquals(steps(Kind.QUICK_MOVE, 7), p.steps());
        assertEquals(List.of(7), p.clicks().stream().map(TransferPlanner.Stack::slot).toList());
        run(m, p);
        assertEquals(1, m.playerCount(MAP));
        assertEquals(64, m.at(3).count());
    }

    @Test
    void exactPicksTheSmallestStackAboveTheCount() {
        VanillaClickModel m = chest().put(0, PANE, 64, 64).put(1, PANE, 5, 64).put(2, PANE, 2, 64);
        Plan p = plan(m, want(PANE, 3), WithdrawMode.EXACT);
        assertEquals(steps(Kind.LEFT, 1, Kind.RIGHT, 27, Kind.RIGHT, 27, Kind.RIGHT, 27, Kind.LEFT, 1), p.steps());
        run(m, p);
        assertEquals(3, m.playerCount(PANE));
        assertEquals(2, m.at(1).count());
    }

    @Test
    void exactWithoutAnEmptySlotOrPartialStackIsInventoryFull() {
        VanillaClickModel m = chest().put(0, MAP, 64, 64);
        for (int i = TOP; i < TOP + 36; i++) m.put(i, STONE, 64, 64);
        Plan p = plan(m, want(MAP, 1), WithdrawMode.EXACT);
        assertTrue(p.steps().isEmpty(), p.steps().toString());
        assertNotNull(p.problem());
        assertTrue(p.problem().contains(MAP + " short by 1 (inventory full)"), p.problem());
        assertTrue(p.expected().isEmpty());
    }

    @Test
    void exactMapAndPaneThenWholeStacks() {
        VanillaClickModel m = chest().put(0, MAP, 64, 64).put(1, PANE, 64, 64).put(2, CARPET, 64, 64);
        for (int i = TOP; i < TOP + 33; i++) m.put(i, STONE, 64, 64); // three empty: 60, 61, 62
        Plan p = plan(m, want(CARPET, 64, MAP, 1, PANE, 1), WithdrawMode.EXACT);
        assertNull(p.problem());
        assertEquals(steps(Kind.LEFT, 0, Kind.RIGHT, 60, Kind.LEFT, 0, Kind.LEFT, 1, Kind.RIGHT, 61, Kind.LEFT, 1,
            Kind.QUICK_MOVE, 2), p.steps(), "the exact counts first: the shift-click must not take their slots");
        run(m, p);
        assertEquals(1, m.playerCount(MAP));
        assertEquals(1, m.playerCount(PANE));
        assertEquals(64, m.playerCount(CARPET));
        assertEquals(0, m.playerFree());
        // with one empty slot less the last item in request order has no room
        VanillaClickModel n = chest().put(0, MAP, 64, 64).put(1, PANE, 64, 64).put(2, CARPET, 64, 64);
        for (int i = TOP; i < TOP + 34; i++) n.put(i, STONE, 64, 64);
        Plan q = plan(n, want(CARPET, 64, MAP, 1, PANE, 1), WithdrawMode.EXACT);
        assertEquals(PANE + " short by 1 (inventory full)", q.problem());
        assertEquals(steps(Kind.LEFT, 0, Kind.RIGHT, 61, Kind.LEFT, 0, Kind.QUICK_MOVE, 2), q.steps());
        run(n, q);
        assertEquals(1, n.playerCount(MAP));
        assertEquals(64, n.playerCount(CARPET));
        assertEquals(0, n.playerFree());
    }

    @Test
    void exactLargeCountsGoAsWholeStacks() {
        VanillaClickModel m = chest().put(0, PANE, 64, 64).put(1, PANE, 64, 64);
        for (int w : new int[]{9, 63, 64, 100}) {
            Plan p = plan(m, want(PANE, w), WithdrawMode.EXACT);
            assertTrue(allShiftClicks(p), w + ": " + p.steps());
            assertEquals(plan(m, want(PANE, w), WithdrawMode.WHOLE_STACKS), p, "w " + w);
        }
        Plan eight = plan(m, want(PANE, 8), WithdrawMode.EXACT);
        assertEquals(10, eight.steps().size(), "8 is still exact: pick up, 8 right clicks, put back");
    }

    @Test
    void exactWithNoStackHoldingTheCountGoesAsWholeStacks() {
        VanillaClickModel m = chest().put(0, PANE, 2, 64).put(1, PANE, 2, 64).put(2, PANE, 3, 64);
        Plan p = plan(m, want(PANE, 5), WithdrawMode.EXACT);
        assertTrue(allShiftClicks(p), p.steps().toString());
        run(m, p);
        assertEquals(5, m.playerCount(PANE));
        Plan none = plan(chest(), want(PANE, 1), WithdrawMode.EXACT);
        assertEquals(PANE + " short by 1 (container)", none.problem());
    }

    /**
     * ZenithProxy predicts a right click onto an empty slot as {@code amount - 1} there (vanilla: 1); the cursor it
     * predicts is right. One map: the slot differs until the server's correction, nothing else does.
     */
    @Test
    void exactPredictionDiffersOnlyInTheTargetSlot() {
        VanillaClickModel m = chest().put(4, MAP, 64, 64);
        Plan p = plan(m, want(MAP, 1), WithdrawMode.EXACT);
        VanillaClickModel z = m.zenithPrediction();
        for (Step s : p.steps()) {
            m.click(s);
            z.click(s);
        }
        assertEquals(List.of(27), z.differences(m));
        assertEquals(63, z.at(27).count());
        assertEquals(1, m.at(27).count());
        assertEquals(0, z.unsent);
        z.correct(27, m);
        assertEquals(List.of(), z.differences(m), "the server's SetSlot fixes it");
    }

    /**
     * Three right clicks onto an empty slot from a stack of 64 with no correction in between: the cache reads the slot
     * as full (63, then 64) and ZenithProxy does not send the third click. With the correction after the first right
     * click (what {@link TransferPlanner#waitsAfter} waits for) all three go out and the cache ends right.
     */
    @Test
    void exactRightClicksOntoAnEmptySlotWaitForTheCorrection() {
        VanillaClickModel m = chest().put(4, PANE, 64, 64);
        Plan p = plan(m, want(PANE, 3), WithdrawMode.EXACT);
        assertEquals(steps(Kind.LEFT, 4, Kind.RIGHT, 27, Kind.RIGHT, 27, Kind.RIGHT, 27, Kind.LEFT, 4), p.steps());

        VanillaClickModel z = m.zenithPrediction();
        z.apply(p.steps());
        assertEquals(1, z.unsent, "uncorrected: the third right click is not sent");

        VanillaClickModel truth = chest().put(4, PANE, 64, 64);
        VanillaClickModel cache = truth.zenithPrediction();
        int[] waits = TransferPlanner.waitsAfter(p.steps(), truth.emptyPlayerSlots(), 1);
        for (int i = 0; i < p.steps().size(); i++) {
            truth.click(p.steps().get(i));
            cache.click(p.steps().get(i));
            if (waits[i] > 0) cache.correct(p.steps().get(i).slot(), truth);
        }
        assertEquals(0, cache.unsent);
        assertEquals(List.of(), cache.differences(truth));
        assertEquals(3, truth.playerCount(PANE));
    }

    // ------------------------------------------------------------------------------------------------ waits

    @Test
    void waitsCoverTheAckRoundTripAfterTheLastCursorClick() {
        List<Step> topUp = steps(Kind.LEFT, 0, Kind.LEFT, 60, Kind.LEFT, 0, Kind.QUICK_MOVE, 1);
        assertArrayEquals(new int[]{0, 0, 1, 0}, TransferPlanner.waitsAfter(topUp, List.of(), 5), "5 ticks: one wait");
        assertArrayEquals(new int[]{0, 0, 7, 0}, TransferPlanner.waitsAfter(topUp, List.of(), 1), "1 tick: 7 waits");
        assertArrayEquals(new int[]{0, 0, 1, 0}, TransferPlanner.waitsAfter(topUp, List.of(), 6));
        assertArrayEquals(new int[]{0, 0, 0, 0}, TransferPlanner.waitsAfter(topUp, List.of(), 8), "the delay covers it");
        assertArrayEquals(new int[]{0, 0}, TransferPlanner.waitsAfter(steps(Kind.QUICK_MOVE, 0, Kind.QUICK_MOVE, 1),
            List.of(), 1), "whole stacks: unchanged");
        for (int d = 1; d <= 10; d++) {
            int k = TransferPlanner.waitsAfter(topUp, List.of(), d)[2];
            assertTrue((k + 1) * d >= TransferPlanner.ACK_TICKS, "next click " + (k + 1) * d + " ticks after, delay " + d);
            assertTrue(k * d < TransferPlanner.ACK_TICKS, "no wait more than needed, delay " + d);
        }
    }

    @Test
    void waitsAfterTheFirstRightClickOntoAnEmptySlotOnly() {
        List<Step> ontoEmpty = steps(Kind.LEFT, 4, Kind.RIGHT, 27, Kind.RIGHT, 27, Kind.LEFT, 4);
        assertArrayEquals(new int[]{0, 7, 0, 7}, TransferPlanner.waitsAfter(ontoEmpty, List.of(27, 28), 1));
        assertArrayEquals(new int[]{0, 0, 0, 7}, TransferPlanner.waitsAfter(ontoEmpty, List.of(28), 1),
            "onto a partial stack the prediction is right");
        List<Step> one = steps(Kind.LEFT, 4, Kind.RIGHT, 27, Kind.LEFT, 4, Kind.QUICK_MOVE, 9);
        assertArrayEquals(new int[]{0, 0, 1, 0}, TransferPlanner.waitsAfter(one, List.of(27), 5));
    }
}
