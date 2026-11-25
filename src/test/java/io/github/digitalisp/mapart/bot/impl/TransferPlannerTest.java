package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Stack;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferPlannerTest {
    private static final String CARPET = "minecraft:white_carpet";
    private static final String STONE = "minecraft:stone";
    private static final String PICK = "minecraft:diamond_pickaxe";

    private static Stack s(int slot, String id, int count) {
        return new Stack(slot, id, count, id.equals(PICK) ? 1 : 64);
    }

    private static List<Integer> slots(TransferPlanner.Plan p) {
        return p.clicks().stream().map(Stack::slot).toList();
    }

    @Test
    void withdrawWholeStacksExactly() {
        var plan = TransferPlanner.planWithdraw(
            List.of(s(0, CARPET, 64), s(1, CARPET, 64), s(2, CARPET, 10), s(3, STONE, 64)),
            Map.of(CARPET, 128), List.of(), 36);
        assertEquals(List.of(0, 1), slots(plan));
        assertEquals(128, plan.expected().get(CARPET));
        assertNull(plan.problem());
    }

    @Test
    void withdrawPrefersSmallOvershoot() {
        var plan = TransferPlanner.planWithdraw(
            List.of(s(0, CARPET, 64), s(1, CARPET, 32), s(2, CARPET, 20)),
            Map.of(CARPET, 25), List.of(), 36);
        assertEquals(List.of(1), slots(plan), "one 32 stack beats 20 + another stack");
        var plan2 = TransferPlanner.planWithdraw(
            List.of(s(0, CARPET, 64), s(1, CARPET, 64), s(2, CARPET, 64)),
            Map.of(CARPET, 100), List.of(), 36);
        assertEquals(2, plan2.clicks().size());
        assertEquals(128, plan2.expected().get(CARPET));
    }

    @Test
    void withdrawReportsContainerShort() {
        var plan = TransferPlanner.planWithdraw(List.of(s(0, CARPET, 40)), Map.of(CARPET, 64, STONE, 5), List.of(), 36);
        assertEquals(List.of(0), slots(plan));
        assertTrue(plan.problem().contains(CARPET + " short by 24"));
        assertTrue(plan.problem().contains(STONE + " short by 5"));
    }

    @Test
    void withdrawStopsWhenInventoryFull() {
        var plan = TransferPlanner.planWithdraw(List.of(s(0, CARPET, 64), s(1, CARPET, 64)), Map.of(CARPET, 128), List.of(), 1);
        assertEquals(List.of(0), slots(plan));
        assertTrue(plan.problem().contains("inventory full"));
    }

    @Test
    void withdrawUsesMergeRoomOfPartialStacks() {
        // 32 carpets held: a 32 stack merges without a free slot
        var plan = TransferPlanner.planWithdraw(List.of(s(5, CARPET, 32)), Map.of(CARPET, 32), List.of(s(40, CARPET, 32)), 0);
        assertEquals(List.of(5), slots(plan));
        assertNull(plan.problem());
    }

    @Test
    void withdrawIgnoresNonPositiveWants() {
        var plan = TransferPlanner.planWithdraw(List.of(s(0, CARPET, 64)), Map.of(CARPET, 0), List.of(), 36);
        assertTrue(plan.clicks().isEmpty());
        assertNull(plan.problem());
    }

    @Test
    void wholeStacksStepsAreOneShiftClickPerStack() {
        var container = List.of(s(0, CARPET, 64), s(1, CARPET, 64), s(2, CARPET, 10), s(3, STONE, 64), s(4, STONE, 7));
        var plan = TransferPlanner.planWithdraw(container, Map.of(CARPET, 70, STONE, 70), List.of(s(40, CARPET, 5)), 36);
        assertEquals(plan.clicks().size(), plan.steps().size());
        for (int i = 0; i < plan.clicks().size(); i++) {
            assertEquals(new TransferPlanner.Step(TransferPlanner.Kind.QUICK_MOVE, plan.clicks().get(i).slot()),
                plan.steps().get(i));
        }
        var compat = new TransferPlanner.Plan(plan.clicks(), plan.expected(), plan.problem());
        assertEquals(plan, compat, "the 3-argument Plan is whole stacks");
    }

    @Test
    void wholeStacksModeIsTheFourArgumentPlan() {
        var container = List.of(s(0, CARPET, 64), s(1, CARPET, 32), s(2, CARPET, 20), s(3, STONE, 40));
        var player = List.of(s(40, CARPET, 30), s(41, PICK, 1));
        for (int free : new int[]{0, 1, 36}) {
            for (var wanted : List.of(Map.of(CARPET, 25), Map.of(CARPET, 100, STONE, 50), Map.of(STONE, 1))) {
                var four = TransferPlanner.planWithdraw(container, wanted, player, free);
                var six = TransferPlanner.planWithdraw(container, wanted, player, free,
                    io.github.digitalisp.mapart.bot.WithdrawMode.WHOLE_STACKS, List.of(50, 51, 52));
                assertEquals(four, six, "free " + free + ", " + wanted);
            }
        }
    }

    @Test
    void depositKeepsRequestedCountAndNeverDepositsKeepItems() {
        var player = List.of(s(30, CARPET, 64), s(31, CARPET, 64), s(32, CARPET, 10), s(54, PICK, 1), s(55, STONE, 12));
        var plan = TransferPlanner.planDeposit(player, id -> true, Map.of(CARPET, 10), Set.of(PICK), List.of(), 27);
        assertEquals(List.of(30, 31, 55), slots(plan));
        assertEquals(128, plan.expected().get(CARPET));
        assertEquals(12, plan.expected().get(STONE));
        assertNull(plan.problem());
    }

    @Test
    void depositHonoursPredicate() {
        var player = List.of(s(30, CARPET, 64), s(31, STONE, 64));
        var plan = TransferPlanner.planDeposit(player, CARPET::equals, Map.of(), Set.of(), List.of(), 27);
        assertEquals(List.of(30), slots(plan));
    }

    @Test
    void depositStopsWhenContainerFull() {
        var player = List.of(s(30, CARPET, 64), s(31, CARPET, 64));
        var plan = TransferPlanner.planDeposit(player, id -> true, Map.of(), Set.of(), List.of(s(0, STONE, 64)), 1);
        assertEquals(1, plan.clicks().size());
        assertEquals("container full", plan.problem());
    }

    @Test
    void depositMergesIntoPartialContainerStacks() {
        var player = List.of(s(30, CARPET, 20));
        var plan = TransferPlanner.planDeposit(player, id -> true, Map.of(), Set.of(), List.of(s(0, CARPET, 40)), 0);
        assertEquals(List.of(30), slots(plan));
        assertNull(plan.problem());
    }
}
