package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.plan.RestockPlanner.Plan;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Result;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Round;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.STACK64;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.lane;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.request;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.rows;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.serpentine;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.times;

/** Slot feasibility of the returned path: capacity, reserve, held items, top-up and whole stacks, stack sizes. */
class SplitRestockPlannerConstraintTest {
    private static final SplitRestockPlanner SPLIT = new SplitRestockPlanner(false);
    private static final SplitRestockPlanner LANES = new SplitRestockPlanner(true);

    /** Capacity as the contract defines it: free slots plus the slots the held items take. */
    private static int capacity(Request r) {
        int cap = Math.max(0, r.freeSlots());
        for (var e : r.held().entrySet()) {
            if (e.getValue() <= 0) continue;
            int s = r.maxStack().applyAsInt(e.getKey());
            int need = (e.getValue() + s - 1) / s;
            cap += Math.max(r.heldSlots().getOrDefault(e.getKey(), need), need);
        }
        return cap;
    }

    private static void assertFits(Request r, Result res, String label) {
        int cap = capacity(r);
        for (int q = 0; q < res.rounds().size(); q++) {
            Round rd = res.rounds().get(q);
            int expected = rd.terminal() ? cap - Math.max(0, r.lastLoadReserve()) : cap;
            assertEquals(expected, rd.capacity(), label + " round " + q);
            assertTrue(rd.slots() <= rd.capacity(), label + " round " + q + ": " + rd.slots() + " > " + rd.capacity());
            assertTrue(rd.cells() > 0, label + " round " + q + " places nothing");
        }
        if (res.complete()) assertTrue(res.rounds().get(res.rounds().size() - 1).terminal(), label);
    }

    @Test
    void everyRoundFitsItsCapacityOnRandomInstances() {
        Random rnd = new Random(42L);
        for (int n = 0; n < 400; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            assertFits(r, SPLIT.solve(r), "SPLIT " + n);
            assertFits(r, LANES.solve(r), "LANES " + n);
        }
    }

    @Test
    void everyRoundFitsOnFullTilesAndTheLastLoadLeavesTheReserve() {
        for (int hw : new int[] {4, 2}) {
            List<Lane> lanes = SplitRestockPlannerRuntimeTest.lanes(SplitRestockPlannerRuntimeTest.skewedTile(3), hw);
            Request r = SplitRestockPlannerRuntimeTest.request(lanes, 31);
            for (SplitRestockPlanner p : List.of(SPLIT, LANES)) {
                Result res = p.solve(r);
                assertTrue(res.complete());
                assertFits(r, res, (2 * hw + 1) + "-wide");
                assertEquals(29, res.rounds().get(res.rounds().size() - 1).capacity(), "31 slots minus the reserve of 2");
                int cells = 0;
                for (Round rd : res.rounds()) cells += rd.cells();
                assertTrue(cells >= SplitRestockPlannerFixtures.placements(lanes), "every placement is loaded");
            }
        }
    }

    @Test
    void heldItemsCoveringTheLoadNeedNoWithdrawal() {
        List<Lane> lanes = List.of(lane(0, 1, true, rows(2, times(5, "a"))));
        Result res = SPLIT.solve(request(lanes, Map.of("a", 64), 0, false, STACK64));
        assertEquals(Map.of(), res.plan().withdraw());
        assertEquals(1, res.plan().lanesCovered());
        assertEquals(1, res.rounds().size());
        assertEquals(1, res.rounds().get(0).slots());
        assertEquals(0, res.rounds().get(0).clicks());
        assertEquals(0.0, res.rounds().get(0).chestSeconds());
    }

    @Test
    void heldItemsTheLoadDoesNotUseKeepTheirSlots() {
        // Lane 0 needs 80 a (2 slots); lane 1 needs the 5 b carried. 1 free slot + b's slot = 2: b keeps its slot, so
        // the first load cannot take lane 0 whole.
        List<Lane> lanes = serpentine(rows(2, times(40, "a")), rows(1, times(5, "b")));
        Result res = SPLIT.solve(request(lanes, Map.of("b", 5), 1, false, STACK64));
        Round first = res.rounds().get(0);
        assertEquals(2, first.capacity());
        assertTrue(first.slots() <= 2);
        assertEquals(0, res.plan().lanesCovered(), res.plan().toString());
        assertTrue(first.items().getOrDefault("a", 0) <= 64, first.toString());
        // Without the carried b the same capacity (2 free slots) takes lane 0 whole.
        Result free = SPLIT.solve(request(lanes, Map.of(), 2, false, STACK64));
        assertTrue(free.plan().lanesCovered() >= 1, free.plan().toString());
    }

    @Test
    void wholeStacksOvershootAndTopUpFillsTheHeldStack() {
        // 10 a carried, 70 a needed. Whole stacks: 10 + 64 = 74 a in 2 slots, 60 asked, 1 click.
        // Top-up: 128 a in 2 slots, 118 asked, the top-up triple and one stack = 4 clicks.
        List<Lane> lanes = List.of(lane(0, 1, true, rows(7, times(10, "a"))));
        Round whole = SPLIT.solve(request(lanes, Map.of("a", 10), 5, false, STACK64)).rounds().get(0);
        assertEquals(70, whole.cells());
        assertEquals(2, whole.slots());
        assertEquals(Map.of("a", 60), whole.withdraw());
        assertEquals(1, whole.clicks());
        Round top = SPLIT.solve(request(lanes, Map.of("a", 10), 5, true, STACK64)).rounds().get(0);
        assertEquals(2, top.slots());
        assertEquals(Map.of("a", 118), top.withdraw());
        assertEquals(4, top.clicks());

        // 64 a needed: whole stacks need 2 slots (h + roundUp(n − h) = 74), top-up 1 (ceil(n / s)).
        List<Lane> lanes64 = List.of(lane(0, 1, true, rows(8, times(8, "a"))));
        Round whole64 = SPLIT.solve(request(lanes64, Map.of("a", 10), 5, false, STACK64)).rounds().get(0);
        assertEquals(2, whole64.slots());
        assertEquals(Map.of("a", 54), whole64.withdraw());
        Round top64 = SPLIT.solve(request(lanes64, Map.of("a", 10), 5, true, STACK64)).rounds().get(0);
        assertEquals(1, top64.slots());
        assertEquals(Map.of("a", 54), top64.withdraw());
        assertEquals(3, top64.clicks());

        // With only the held slot: top-up finishes in one load; whole stacks first build what is carried.
        Result topOne = SPLIT.solve(request(lanes64, Map.of("a", 10), 0, true, STACK64));
        assertEquals(1, topOne.rounds().size());
        Result wholeOne = SPLIT.solve(request(lanes64, Map.of("a", 10), 0, false, STACK64));
        assertEquals(2, wholeOne.rounds().size());
        assertEquals(Map.of(), wholeOne.plan().withdraw());
        assertEquals(10, wholeOne.plan().partialCells(), "all ten carried a, then restock");
    }

    @Test
    void heldSlotsAboveTheCountAreKept() {
        // 64 a carried as two half stacks: 2 slots, so 1 free slot gives a capacity of 3.
        List<Lane> lanes = List.of(lane(0, 1, true, rows(4, "b", "c", "d")));
        Request r = new Request(lanes, Map.of("a", 64), Map.of("a", 2), 1, STACK64, 0, false, null);
        Result res = SPLIT.solve(r);
        assertEquals(3, res.rounds().get(0).capacity());
        assertTrue(res.rounds().get(0).slots() <= 3);
        assertEquals(0, res.plan().lanesCovered(), "b, c, d plus a's two slots need 5");
    }

    @Test
    void stackSixteen() {
        ToIntFunction16 snow = new ToIntFunction16();
        List<Lane> lanes = List.of(lane(0, 1, true, rows(4, times(10, "snow"))));
        Result roomy = SPLIT.solve(request(lanes, Map.of(), 5, false, snow));
        assertEquals(1, roomy.rounds().size());
        assertEquals(3, roomy.rounds().get(0).slots(), "40 snow at 16 a stack");
        Result tight = SPLIT.solve(request(lanes, Map.of(), 2, false, snow));
        assertTrue(tight.rounds().get(0).items().get("snow") <= 32);
        assertEquals(2, tight.rounds().get(0).slots());
        assertEquals(2, tight.rounds().size());
    }

    /** Stack 16 for "snow", 64 otherwise. */
    private static final class ToIntFunction16 implements java.util.function.ToIntFunction<String> {
        @Override
        public int applyAsInt(String id) {
            return id.equals("snow") ? 16 : 64;
        }
    }

    @Test
    void fewerFreeSlotsNeverGiveFewerRoundsOrLessTime() {
        for (int hw : new int[] {4, 2}) {
            List<Lane> lanes = SplitRestockPlannerRuntimeTest.lanes(SplitRestockPlannerRuntimeTest.skewedTile(5), hw);
            int lastRounds = 0;
            double lastSeconds = 0;
            for (int free = 31; free >= 27; free--) {
                Result res = SPLIT.solve(SplitRestockPlannerRuntimeTest.request(lanes, free));
                assertTrue(res.rounds().size() >= lastRounds, (2 * hw + 1) + "-wide, " + free + " slots");
                assertTrue(res.seconds() >= lastSeconds - 1e-9, (2 * hw + 1) + "-wide, " + free + " slots");
                lastRounds = res.rounds().size();
                lastSeconds = res.seconds();
            }
        }
        Random rnd = new Random(9L);
        for (int n = 0; n < 300; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            Request fewer = new Request(r.remaining(), r.held(), r.heldSlots(), r.freeSlots() - 1, r.maxStack(),
                r.lastLoadReserve(), r.topUp(), r.site());
            Result a = SPLIT.solve(r);
            Result b = SPLIT.solve(fewer);
            if (!b.complete()) continue;
            assertTrue(a.complete(), "instance " + n);
            assertTrue(b.seconds() >= a.seconds() - 1e-9, "instance " + n);
        }
    }

    @Test
    void theReserveMovesTheLastCellsIntoASmallLoad() {
        // a, b and 4 c fill 3 slots exactly: one load without the reserve, two with a reserve of 1.
        List<Lane> lanes = List.of(lane(0, 1, true, times(64, "a"), times(64, "b"), times(4, "c")));
        Result none = SPLIT.solve(new Request(lanes, Map.of(), Map.of(), 3, STACK64, 0, false, null));
        assertEquals(1, none.rounds().size());
        Result reserved = SPLIT.solve(new Request(lanes, Map.of(), Map.of(), 3, STACK64, 1, false, null));
        assertEquals(2, reserved.rounds().size());
        Round last = reserved.rounds().get(1);
        assertTrue(last.terminal());
        assertEquals(2, last.capacity());
        assertTrue(last.slots() <= 2);
        assertEquals(4, last.cells(), "the small last load");
        assertFalse(reserved.rounds().get(0).terminal());
    }

    @Test
    void aWithdrawFreeLoadUsesUpWhatIsCarried() {
        // Carried 3 a and 2 b; rows of a, a, b. Without withdrawing, the load ends where the carried items run out
        // (4 placements, inside row 1), never after one row.
        List<Lane> lanes = serpentine(rows(4, "a", "a", "b"), rows(4, "a", "a", "b"));
        for (boolean topUp : new boolean[] {false, true}) {
            for (int free = 0; free <= 3; free++) {
                Request r = request(lanes, Map.of("a", 3, "b", 2), free, topUp, STACK64);
                for (SplitRestockPlanner p : List.of(SPLIT, LANES)) {
                    Plan plan = p.plan(r);
                    if (plan.withdraw().isEmpty()) assertEquals(4, plan.partialCells(), "free " + free + " " + plan);
                }
            }
        }
    }

    @Test
    void noWithdrawFreeLoadWhileALoadWithAWithdrawalFits() {
        // Local 10x run: "load covers 0 + 78 cells, withdraw {}" between two full loads. Carried 3 a and 2 b, free
        // slots for more: every planned first load withdraws, with or without top-up, in both modes
        List<Lane> lanes = serpentine(rows(4, "a", "a", "b"), rows(4, "a", "c", "b"));
        for (boolean topUp : new boolean[] {false, true}) {
            for (int free = 1; free <= 4; free++) {
                Request r = request(lanes, Map.of("a", 3, "b", 2), free, topUp, STACK64);
                for (SplitRestockPlanner p : List.of(SPLIT, LANES)) {
                    Plan plan = p.plan(r);
                    assertFalse(plan.withdraw().isEmpty(), "free " + free + ", top-up " + topUp + ": " + plan);
                }
            }
        }
    }

    @Test
    void carriedPartialStacksDoNotGiveOneRowLoadsOnAFullTile() {
        // The lead's laundering case: partial stacks of 14 colours carried, whole stacks, 17 free slots (31 in all).
        for (int hw : new int[] {2, 4}) {
            List<Lane> lanes = SplitRestockPlannerRuntimeTest.lanes(SplitRestockPlannerRuntimeTest.skewedTile(7), hw);
            Map<String, Integer> held = new HashMap<>();
            int c = 0;
            for (Lane l : lanes)
                for (Placement p : l.placements())
                    if (!held.containsKey(p.itemId()) && held.size() < 14) held.put(p.itemId(), 10 + 3 * c++);
            Request r = new Request(lanes, held, Map.of(), 17, STACK64, 2, false, SplitRestockPlannerRuntimeTest.site());
            // How far the carried items reach along lane 0.
            Map<String, Integer> left = new HashMap<>(held);
            int carried = 0;
            for (Placement p : lanes.get(0).placements()) {
                int n = left.getOrDefault(p.itemId(), 0);
                if (n <= 0) break;
                left.put(p.itemId(), n - 1);
                carried++;
            }
            for (SplitRestockPlanner p : List.of(SPLIT, LANES)) {
                Plan plan = p.plan(r);
                String label = (2 * hw + 1) + "-wide " + (p.lanesFirst() ? "LANES" : "SPLIT") + ": " + plan;
                if (plan.withdraw().isEmpty()) {
                    assertTrue(plan.partialCells() == 0 || plan.partialCells() == carried, label + ", carried " + carried);
                }
                assertFalse(plan.withdraw().isEmpty() && plan.lanesCovered() == 0 && plan.partialCells() <= 2 * hw + 1,
                    label);
            }
        }
    }
}
