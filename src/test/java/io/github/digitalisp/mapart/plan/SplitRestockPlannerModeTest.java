package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.RestockPlanner.Site;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Result;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Round;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.STACK64;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.request;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.serpentine;
import static io.github.digitalisp.mapart.plan.SplitRestockPlannerFixtures.times;

/** LANES (the two-lane rule: lane ends while a lane pair fits) against SPLIT (cuts anywhere). */
class SplitRestockPlannerModeTest {
    private static final SplitRestockPlanner SPLIT = new SplitRestockPlanner(false);
    private static final SplitRestockPlanner LANES = new SplitRestockPlanner(true);
    /** The bot and the chests 60 blocks north of the lanes: every trip costs about 20 s of walking. */
    private static final Site FAR = new Site(new Pos(1, 0, -60), id -> new Pos(id.equals("a") ? 0 : 3, 0, -62),
        Costs.DEFAULT);

    /** {@code n} lanes, each a row of 64 a and a row of 64 b (2 slots), serpentine. */
    private static List<Lane> twoSlotLanes(int n) {
        String[][][] lanes = new String[n][][];
        for (int j = 0; j < n; j++) lanes[j] = new String[][] {times(64, "a"), times(64, "b")};
        return serpentine(lanes);
    }

    private static long rowsOf(Request r, int lane) {
        return r.remaining().get(lane).placements().stream().map(p -> p.pos().z()).distinct().count();
    }

    @Test
    void accessorsAndCost() {
        assertTrue(LANES.lanesFirst());
        assertFalse(SPLIT.lanesFirst());
        assertTrue(LANES.expensive());
        assertTrue(SPLIT.expensive());
    }

    @Test
    void whileALanePairFitsLoadsEndAtLaneEnds() {
        // 7 lanes of 2 slots, 5 free: a pair (4 slots) always fits, three lanes (6) never do. LANES: 2 + 2 + 2 + 1
        // lanes. SPLIT packs 14 slots into 3 loads of 5 by cutting lanes, which pays with the chests 60 blocks away.
        Request r = new Request(twoSlotLanes(7), Map.of(), Map.of(), 5, STACK64, 0, false, FAR);
        Result lanes = LANES.solve(r);
        assertEquals(4, lanes.rounds().size());
        for (Round rd : lanes.rounds()) {
            if (rd.terminal()) continue;
            assertEquals(0, rd.toLo(), rd.toString());
            assertEquals(rowsOf(r, rd.toLane()) - 1, rd.toHi(), rd.toString());
        }
        assertEquals(0, lanes.plan().partialCells());
        assertEquals(2, lanes.plan().lanesCovered());
        Result split = SPLIT.solve(r);
        assertEquals(3, split.rounds().size());
        assertTrue(split.seconds() < lanes.seconds() - 1);
    }

    @Test
    void whenNoLanePairFitsLanesAreCut() {
        // 4 lanes of 2 slots, 3 free, the chests 60 blocks away: one lane fits, a pair never does from a lane start,
        // so LANES splits like SPLIT (8 slots in 3 loads of 3) instead of one load per lane.
        Request r = new Request(twoSlotLanes(4), Map.of(), Map.of(), 3, STACK64, 0, false, FAR);
        Result lanes = LANES.solve(r);
        assertEquals(3, lanes.rounds().size(), lanes.rounds().toString());
        boolean cut = false;
        for (Round rd : lanes.rounds()) cut |= !rd.terminal() && (rd.toLo() > 0 || rd.toHi() < rowsOf(r, rd.toLane()) - 1);
        assertTrue(cut, "some load ends inside a lane");
        assertEquals(SPLIT.solve(r).seconds(), lanes.seconds(), 1e-9);
    }

    @Test
    void aLaneLargerThanTheLoadIsCut() {
        // Lane 0 needs five items (one slot each), three slots are free: no lane end fits from the start.
        Request r = request(serpentine(new String[][] {{"a", "b"}, {"c", "d"}, {"e"}}, new String[][] {{"a"}}), Map.of(),
            3, false, STACK64);
        for (SplitRestockPlanner p : List.of(LANES, SPLIT)) {
            Result res = p.solve(r);
            assertTrue(res.complete());
            assertEquals(0, res.plan().lanesCovered());
            assertTrue(res.plan().partialCells() > 0);
        }
    }

    @Test
    void splitIsNeverWorseThanLanes() {
        Random rnd = new Random(31L);
        for (int n = 0; n < 400; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            Result lanes = LANES.solve(r);
            if (!lanes.complete()) continue;
            Result split = SPLIT.solve(r);
            assertTrue(split.complete(), "instance " + n);
            assertTrue(split.seconds() <= lanes.seconds() + 1e-9, "instance " + n);
        }
        for (int hw : new int[] {4, 2}) {
            List<Lane> lanes = SplitRestockPlannerRuntimeTest.lanes(SplitRestockPlannerRuntimeTest.skewedTile(11), hw);
            for (int free : new int[] {31, 29}) {
                Request r = SplitRestockPlannerRuntimeTest.request(lanes, free);
                assertTrue(SPLIT.solve(r).seconds() <= LANES.solve(r).seconds() + 1e-9, hw + " " + free);
            }
        }
    }
}
