package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.RestockPlanner.Plan;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Result;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Round;

import java.util.ArrayList;
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
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.times;

/** How the first load of the path becomes the {@link Plan}. */
class SplitRestockPlannerPlanTest {
    private static final SplitRestockPlanner SPLIT = new SplitRestockPlanner(false);
    private static final SplitRestockPlanner LANES = new SplitRestockPlanner(true);
    private static final Plan NOTHING = new Plan(Map.of(), 0, 0, true, 0, 0.0);

    private static void assertMapsTheFirstRound(Request r, Result res, String label) {
        Plan p = res.plan();
        assertTrue(p.stopAtCut(), label);
        if (res.rounds().isEmpty()) {
            assertEquals(NOTHING, p, label);
            return;
        }
        Round first = res.rounds().get(0);
        int covered = 0;
        for (int q = 0; q < p.lanesCovered(); q++) covered += r.remaining().get(q).placements().size();
        assertEquals(first.cells(), covered + p.partialCells(), label);
        if (p.lanesCovered() < r.remaining().size())
            assertTrue(p.partialCells() < r.remaining().get(p.lanesCovered()).placements().size(), label);
        else
            assertEquals(0, p.partialCells(), label);
        assertEquals(first.withdraw(), p.withdraw(), label);
        for (int n : p.withdraw().values()) assertTrue(n > 0, label);
        if (res.complete()) {
            assertEquals(res.rounds().size(), p.rounds(), label);
            double sum = 0;
            for (Round rd : res.rounds()) sum += rd.seconds();
            assertEquals(sum, p.seconds(), 1e-6, label);
        } else {
            assertEquals(0, p.rounds(), label);
            assertEquals(0.0, p.seconds(), label);
        }
    }

    @Test
    void thePlanIsTheFirstRoundOnRandomInstances() {
        Random rnd = new Random(123L);
        for (int n = 0; n < 400; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            assertMapsTheFirstRound(r, SPLIT.solve(r), "SPLIT " + n);
            assertMapsTheFirstRound(r, LANES.solve(r), "LANES " + n);
        }
    }

    @Test
    void aMidRowMaxFillCut() {
        // Two rows of 40 a, one slot: the load ends at the 64th placement, inside row 1. The cut row stays whole for
        // the rest of the plan, so both cuts cost the same; the longer first load wins the tie.
        List<Lane> lanes = List.of(lane(0, 1, true, rows(2, times(40, "a"))));
        Result res = SPLIT.solve(request(lanes, Map.of(), 1, false, STACK64));
        assertEquals(0, res.plan().lanesCovered());
        assertEquals(64, res.plan().partialCells());
        assertEquals(Map.of("a", 64), res.plan().withdraw());
        assertEquals(2, res.plan().rounds());
        assertEquals(0, res.rounds().get(1).fromLane());
        assertEquals(1, res.rounds().get(1).fromLo(), "row 1 is left whole");
    }

    @Test
    void theWithdrawalIsInFirstNeedOrder() {
        List<Lane> lanes = List.of(lane(0, 1, true, times(3, "b"), times(3, "a"), times(3, "c")));
        Plan p = SPLIT.plan(request(lanes, Map.of(), 5, false, STACK64));
        assertEquals(List.of("b", "a", "c"), new ArrayList<>(p.withdraw().keySet()));
        assertEquals(Map.of("a", 3, "b", 3, "c", 3), p.withdraw());
        // Top-up rounds each withdrawal up to whole stacks.
        Plan top = SPLIT.plan(request(lanes, Map.of("a", 1), 5, true, STACK64));
        assertEquals(Map.of("b", 64, "a", 63, "c", 64), top.withdraw());
    }

    @Test
    void emptyInput() {
        assertEquals(NOTHING, SPLIT.plan(List.of(), Map.of(), 31, STACK64));
        assertEquals(NOTHING, LANES.plan(Request.of(List.of(), Map.of("a", 5), 31, STACK64)));
    }

    @Test
    void nothingFits() {
        List<Lane> lanes = List.of(lane(0, 1, true, rows(2, "a")));
        assertEquals(NOTHING, SPLIT.plan(lanes, Map.of(), 0, STACK64));
        assertEquals(NOTHING, LANES.plan(lanes, Map.of("b", 64), 0, STACK64), "b's slot is not a's");
    }

    @Test
    void noCompletePathFallsBackToTheLongestFirstLoad() {
        // A row of three items with two slots: the first load can take two of them, but the rest of the plan works
        // row by row and that row never fits.
        List<Lane> lanes = List.of(lane(0, 1, true, rows(2, "a", "b", "c")));
        Result res = SPLIT.solve(request(lanes, Map.of(), 2, false, STACK64));
        assertFalse(res.complete());
        assertEquals(new Plan(Map.of("a", 1, "b", 1), 0, 2, true, 0, 0.0), res.plan());
    }

    @Test
    void theOriginalEntryPointIsRequestOf() {
        List<Lane> lanes = List.of(lane(0, 1, true, rows(3, "a", "b")), lane(1, 4, false, rows(3, "b", "c")));
        Map<String, Integer> held = Map.of("b", 2);
        assertEquals(SPLIT.plan(Request.of(lanes, held, 2, STACK64)), SPLIT.plan(lanes, held, 2, STACK64));
        assertEquals(LANES.plan(Request.of(lanes, held, 2, STACK64)), LANES.plan(lanes, held, 2, STACK64));
    }

    @Test
    void theSameInputGivesTheSamePlan() {
        List<Lane> lanes = SplitRestockPlannerRuntimeTest.lanes(SplitRestockPlannerRuntimeTest.skewedTile(13), 2);
        Request r = SplitRestockPlannerRuntimeTest.request(lanes, 30);
        assertEquals(SPLIT.plan(r), SPLIT.plan(r));
        assertEquals(LANES.plan(r), LANES.plan(r));
    }

    @Test
    void placementsWithoutAnItemNeedNoSlot() {
        List<Lane> lanes = List.of(lane(0, 1, true, rows(3, null, null)), lane(1, 4, false, rows(2, "a", null)));
        Plan p = SPLIT.plan(lanes, Map.of(), 1, STACK64);
        assertEquals(2, p.lanesCovered());
        assertEquals(Map.of("a", 2), p.withdraw());
        assertEquals(1, p.rounds());
    }

    @Test
    void lanesWithoutPlacementsKeepTheirIndex() {
        Lane empty = new Lane(9, new Pos(0, 0, 0), new Pos(0, 0, 1), List.of());
        Lane a = lane(1, 4, true, rows(2, times(32, "a")));
        Lane b = lane(3, 10, false, rows(2, times(32, "b")));
        // One slot: a's lane, then b's lane.
        Plan p = SPLIT.plan(List.of(empty, a, empty, b), Map.of(), 1, STACK64);
        assertEquals(3, p.lanesCovered(), "the empty lanes before b count as covered");
        assertEquals(0, p.partialCells());
        assertEquals(Map.of("a", 64), p.withdraw());
        assertEquals(2, p.rounds());
        Plan none = SPLIT.plan(List.of(empty, empty), Map.of(), 1, STACK64);
        assertEquals(new Plan(Map.of(), 2, 0, true, 0, 0.0), none);
    }
}
