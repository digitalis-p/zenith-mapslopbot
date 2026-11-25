package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyRestockPlannerTest {
    private static final ToIntFunction<String> STACK64 = i -> 64;
    private final GreedyRestockPlanner planner = new GreedyRestockPlanner();

    /** A lane placing the given items, one placement per entry, in the given order. */
    private static Lane lane(int index, String... items) {
        List<Placement> ps = new ArrayList<>();
        for (int i = 0; i < items.length; i++)
            ps.add(new Placement(new Pos(index, 0, i), BlockSpec.of(items[i]), items[i]));
        return new Lane(index, new Pos(index, 0, -1), new Pos(index, 0, items.length), ps);
    }

    private static String[] times(int n, String item) {
        String[] a = new String[n];
        Arrays.fill(a, item);
        return a;
    }

    private static String[] concat(String[]... parts) {
        List<String> l = new ArrayList<>();
        for (String[] p : parts) l.addAll(List.of(p));
        return l.toArray(new String[0]);
    }

    /** 64 a then 64 b: two slots. */
    private static Lane ab(int index) {
        return lane(index, concat(times(64, "a"), times(64, "b")));
    }

    private static List<Lane> abLanes(int n) {
        List<Lane> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(ab(i));
        return l;
    }

    @Test
    void capacityIsFreeSlotsPlusTheSlotsOfHeldMaterials() {
        // 128 a held (2 slots) + 2 free slots = 4: two whole ab lanes. Before: max(2 free, 2 held) = 2 slots, not one lane.
        RestockPlanner.Plan p = planner.plan(abLanes(4), Map.of("a", 128), 2, STACK64);
        assertEquals(2, p.lanesCovered());
        assertEquals(0, p.partialCells(), "lane 2 starts with a: a third stack of a needs a fifth slot");
        assertEquals(Map.of("b", 128), p.withdraw());
    }

    @Test
    void heldLoadDoesNotCrowdOutTheFreeSlots() {
        // live: "load covers 1, withdraw {}" with free slots left; the held stacks now come on top of the free ones
        RestockPlanner.Plan p = planner.plan(abLanes(5), Map.of("a", 192, "b", 192), 2, STACK64);
        assertEquals(4, p.lanesCovered());
        assertEquals(Map.of("a", 64, "b", 64), p.withdraw());
    }

    @Test
    void wholeLanesWhileTheyFitThenTheFirstCellsOfTheNext() {
        List<Lane> lanes = abLanes(3);
        RestockPlanner.Plan two = planner.plan(lanes, Map.of(), 4, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("a", 128, "b", 128), 2, 0), two, "lane 2's first cell needs a fifth slot");
        assertEquals(3, planner.plan(lanes, Map.of(), 6, STACK64).lanesCovered());

        RestockPlanner.Plan partial = planner.plan(lanes, Map.of(), 3, STACK64);
        assertEquals(1, partial.lanesCovered());
        assertEquals(64, partial.partialCells(), "lane 1's 64 a fit the third slot, its first b does not");
        assertEquals(2, partial.lanesLoaded());
        assertEquals(Map.of("a", 128, "b", 64), partial.withdraw());
    }

    @Test
    void partialLaneIsAPrefixInPlacementOrder() {
        Lane big = lane(0, concat(times(64, "a"), times(64, "b"), times(64, "c")));
        RestockPlanner.Plan p = planner.plan(List.of(big), Map.of(), 2, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("a", 64, "b", 64), 0, 128), p);

        String[] interleaved = new String[30];
        for (int i = 0; i < 30; i++) interleaved[i] = List.of("a", "b", "c").get(i % 3);
        RestockPlanner.Plan q = planner.plan(List.of(lane(0, interleaved)), Map.of(), 2, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("a", 1, "b", 1), 0, 2), q, "stops at the first c, not after it");
    }

    @Test
    void partiallyHeldStacksAreToppedUpFirst() {
        // 40 a held (1 slot) + 1 free slot: a whole stack withdrawn tops the held stack up to 64 and puts 40 in the free
        // slot, so 104 a fit; the b after them does not
        Lane l = lane(0, concat(times(110, "a"), times(10, "b")));
        RestockPlanner.Plan p = planner.plan(List.of(l), Map.of("a", 40), 1, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("a", 64), 0, 104), p);

        // what the held partial stack already holds needs no slot: with no free slot its 40 a are still loaded
        RestockPlanner.Plan none = planner.plan(List.of(l), Map.of("a", 40), 0, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of(), 0, 40), none);

        // 4 more a top up a held 60, but the stack they come in leaves 60 in a slot of its own: b needs a second free slot
        Lane m = lane(0, concat(times(64, "a"), times(64, "b")));
        assertEquals(new RestockPlanner.Plan(Map.of("a", 4), 0, 64), planner.plan(List.of(m), Map.of("a", 60), 1, STACK64));
        assertEquals(new RestockPlanner.Plan(Map.of("a", 4, "b", 64), 1, 0),
            planner.plan(List.of(m), Map.of("a", 60), 2, STACK64));
    }

    @Test
    void heldItemsTheLoadDoesNotUseKeepTheirSlots() {
        // 640 c held (10 slots, needed later) + 2 free: only the free slots take a and b
        RestockPlanner.Plan p = planner.plan(abLanes(2), Map.of("c", 640), 2, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("a", 64, "b", 64), 1, 0), p);

        Lane onlyC = lane(0, times(64, "c"));
        RestockPlanner.Plan q = planner.plan(List.of(onlyC, ab(1)), Map.of("c", 64 * 20), 10, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("a", 64, "b", 64), 2, 0), q, "lane 0 from the held c, lane 1 in free slots");
    }

    @Test
    void manyColoursPerLaneMoreThanTheSlots() {
        // 40 colours cycling through the lane, 27 slots: the first 27 cells, one slot each
        String[] cells = new String[120];
        for (int i = 0; i < cells.length; i++) cells[i] = "c" + (i % 40);
        RestockPlanner.Plan p = planner.plan(List.of(lane(0, cells), lane(1, cells)), Map.of(), 27, STACK64);
        assertEquals(0, p.lanesCovered());
        assertEquals(27, p.partialCells());
        Map<String, Integer> want = new HashMap<>();
        for (int i = 0; i < 27; i++) want.put("c" + i, 1);
        assertEquals(want, p.withdraw());

        // colours held from the last trip keep their slots and are used first
        RestockPlanner.Plan h = planner.plan(List.of(lane(0, cells)), Map.of("c0", 3, "c1", 3), 25, STACK64);
        assertEquals(27, h.partialCells());
        assertTrue(!h.withdraw().containsKey("c0") && !h.withdraw().containsKey("c1"), h.withdraw().toString());
    }

    @Test
    void singleColour() {
        // 640-cell lanes of one colour (10 stacks each), 27 slots: two lanes and 7 stacks of the third
        List<Lane> lanes = List.of(lane(0, times(640, "w")), lane(1, times(640, "w")), lane(2, times(640, "w")));
        RestockPlanner.Plan p = planner.plan(lanes, Map.of(), 27, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("w", 1728), 2, 448), p);

        // 100 left from the last trip (64 + 36): 25 free + those 2 = 27 slots. 25 whole stacks top the 36 up and leave
        // 36 in the last slot: 1700 w
        RestockPlanner.Plan q = planner.plan(lanes, Map.of("w", 100), 25, STACK64);
        assertEquals(new RestockPlanner.Plan(Map.of("w", 1600), 2, 420), q);
    }

    @Test
    void stackSizesComeFromTheCallback() {
        Lane l = lane(0, times(20, "pearl"));
        ToIntFunction<String> stacks = i -> i.equals("pearl") ? 16 : 64;
        assertEquals(new RestockPlanner.Plan(Map.of("pearl", 16), 0, 16), planner.plan(List.of(l), Map.of(), 1, stacks));
        assertEquals(1, planner.plan(List.of(l), Map.of(), 2, stacks).lanesCovered());
        assertEquals(1, planner.plan(List.of(l), Map.of(), 1, i -> 0).lanesCovered(), "non-positive stack sizes act as 64");
        assertEquals(2, GreedyRestockPlanner.slots(Map.of("pearl", 20), Map.of(), stacks));
        assertEquals(3, GreedyRestockPlanner.slots(Map.of("a", 65), Map.of("b", 1), STACK64));
        assertEquals(2, GreedyRestockPlanner.slots(Map.of("a", 104), Map.of("a", 40), STACK64), "40 held + a whole stack");
        assertEquals(3, GreedyRestockPlanner.slots(Map.of("a", 105), Map.of("a", 40), STACK64), "40 held + two whole stacks");
        assertEquals(1, GreedyRestockPlanner.slots(Map.of("a", 30), Map.of("a", 40), STACK64), "covered by the held 40");
    }

    @Test
    void edgeCases() {
        assertEquals(new RestockPlanner.Plan(Map.of(), 0), planner.plan(List.of(), Map.of(), 10, STACK64));
        assertEquals(new RestockPlanner.Plan(Map.of(), 0), planner.plan(List.of(ab(0)), Map.of(), 0, STACK64));
        assertEquals(new RestockPlanner.Plan(Map.of(), 0), planner.plan(List.of(ab(0)), null, -3, STACK64));
        assertEquals(new RestockPlanner.Plan(Map.of(), 1), planner.plan(List.of(ab(0)), Map.of("a", 64, "b", 99), 0, STACK64));
    }

    /** Random lanes, held items and slot counts: the load stays within capacity and stops only where the next cell does not fit. */
    @Test
    void loadIsWithinCapacityAndMaximal() {
        Random r = new Random(7);
        for (int run = 0; run < 300; run++) {
            int colours = 1 + r.nextInt(8);
            List<Lane> lanes = new ArrayList<>();
            for (int i = 0, n = 1 + r.nextInt(5); i < n; i++) {
                String[] cells = new String[1 + r.nextInt(300)];
                for (int k = 0; k < cells.length; k++) cells[k] = "c" + r.nextInt(colours);
                lanes.add(lane(i, cells));
            }
            Map<String, Integer> held = new HashMap<>();
            for (int c = 0; c < colours + 2; c++) if (r.nextInt(3) == 0) held.put("c" + c, 1 + r.nextInt(150));
            int free = r.nextInt(12);
            ToIntFunction<String> stack = i -> i.equals("c1") ? 16 : 64;
            RestockPlanner.Plan p = planner.plan(lanes, held, free, stack);

            Map<String, Integer> need = new HashMap<>();
            for (int i = 0; i < p.lanesCovered(); i++)
                for (Placement pl : lanes.get(i).placements()) need.merge(pl.itemId(), 1, Integer::sum);
            List<Placement> next = p.lanesCovered() < lanes.size() ? lanes.get(p.lanesCovered()).placements() : List.of();
            assertTrue(p.partialCells() < Math.max(1, next.size()), "a whole next lane would be covered");
            for (int k = 0; k < p.partialCells(); k++) need.merge(next.get(k).itemId(), 1, Integer::sum);

            int cap = free + GreedyRestockPlanner.slots(Map.of(), held, stack);
            assertTrue(GreedyRestockPlanner.slots(need, held, stack) <= cap, "within capacity, run " + run);
            Map<String, Integer> withdraw = new HashMap<>();
            need.forEach((item, n) -> {
                if (n > held.getOrDefault(item, 0)) withdraw.put(item, n - held.getOrDefault(item, 0));
            });
            assertEquals(withdraw, p.withdraw(), "withdraw = cells planned - held, run " + run);
            if (p.partialCells() < next.size()) {
                need.merge(next.get(p.partialCells()).itemId(), 1, Integer::sum);
                assertTrue(GreedyRestockPlanner.slots(need, held, stack) > cap, "the next cell does not fit, run " + run);
            }
        }
    }

    // ---- top-up (Request.topUp): the held partial stack is filled, an item takes ceil(n / 64) slots ----------------

    private RestockPlanner.Plan planTopUp(List<Lane> lanes, Map<String, Integer> held, int free, boolean topUp) {
        return planner.plan(new RestockPlanner.Request(lanes, held, Map.of(), free, STACK64, 0, topUp, null));
    }

    @Test
    void topUpFillsTheHeldPartialStackInsteadOfTakingASlot() {
        // 10 a held (1 slot) + 1 free slot. A lane of 100 a: whole stacks give 10 + 128 = 138 a in 3 slots (no fit),
        // top-up gives 128 a in 2 slots (fits)
        List<Lane> lanes = List.of(lane(0, times(100, "a")));
        RestockPlanner.Plan whole = planTopUp(lanes, Map.of("a", 10), 1, false);
        RestockPlanner.Plan topUp = planTopUp(lanes, Map.of("a", 10), 1, true);
        assertEquals(0, whole.lanesCovered());
        assertEquals(1, topUp.lanesCovered());
        assertEquals(Map.of("a", 118), topUp.withdraw(), "the stack-aligned target 128 minus the 10 held");
        assertEquals(Map.of("a", 54 + 64), topUp.withdraw());
    }

    @Test
    void withoutTopUpTheRequestPlanIsTheOldPlan() {
        Random r = new Random(5);
        for (int run = 0; run < 50; run++) {
            List<Lane> lanes = new ArrayList<>();
            for (int l = 0; l < 4; l++) {
                String[] items = new String[40 + r.nextInt(80)];
                for (int i = 0; i < items.length; i++) items[i] = "c" + r.nextInt(4);
                lanes.add(lane(l, items));
            }
            Map<String, Integer> held = new HashMap<>();
            for (int k = 0; k < 4; k++) if (r.nextBoolean()) held.put("c" + k, 1 + r.nextInt(90));
            int free = r.nextInt(5);
            assertEquals(planner.plan(lanes, held, free, STACK64), planTopUp(lanes, held, free, false), "run " + run);
        }
    }

    @Test
    void topUpNeverCoversLessThanWholeStacks() {
        Random r = new Random(9);
        for (int run = 0; run < 100; run++) {
            List<Lane> lanes = new ArrayList<>();
            for (int l = 0; l < 4; l++) {
                String[] items = new String[40 + r.nextInt(80)];
                for (int i = 0; i < items.length; i++) items[i] = "c" + r.nextInt(5);
                lanes.add(lane(l, items));
            }
            Map<String, Integer> held = new HashMap<>();
            for (int k = 0; k < 5; k++) if (r.nextBoolean()) held.put("c" + k, 1 + r.nextInt(150));
            int free = r.nextInt(6);
            RestockPlanner.Plan whole = planTopUp(lanes, held, free, false);
            RestockPlanner.Plan topUp = planTopUp(lanes, held, free, true);
            assertTrue(topUp.lanesCovered() > whole.lanesCovered() || (topUp.lanesCovered() == whole.lanesCovered()
                && topUp.partialCells() >= whole.partialCells()), "run " + run + ": " + whole + " vs " + topUp);
        }
    }

    @Test
    void theLastLoadLeavesTheReserveFree() {
        // 2 lanes of 64 a: 2 slots; with 2 free slots and a reserve of 1 the last load stops one cell short of them
        List<Lane> lanes = List.of(lane(0, times(64, "a")), lane(1, times(64, "a")));
        RestockPlanner.Plan p = planner.plan(new RestockPlanner.Request(lanes, Map.of(), Map.of(), 2, STACK64, 1, true,
            null));
        assertEquals(1, p.lanesCovered());
        assertEquals(0, p.partialCells(), "the second lane would need the reserved slot");
    }
}
