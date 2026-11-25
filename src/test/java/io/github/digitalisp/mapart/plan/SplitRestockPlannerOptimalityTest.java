package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Result;
import io.github.digitalisp.mapart.plan.SplitRestockPlanner.Round;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The DP against {@link SplitRestockPlannerBruteForce} on seeded small instances, both modes: the same optimum, a
 * first round whose cost is that of some optimal path, the same verdict when no path completes.
 */
class SplitRestockPlannerOptimalityTest {
    private static final int INSTANCES = 4000;

    private static void check(Request r, boolean lanesFirst, String label) {
        Result res = new SplitRestockPlanner(lanesFirst).solve(r);
        SplitRestockPlannerBruteForce bf = new SplitRestockPlannerBruteForce(r, lanesFirst, true);
        double opt = bf.optimum();
        assertTrue(res.stats().exact(), label + ": small inputs must be searched exactly, " + res.stats());
        if (opt == SplitRestockPlannerBruteForce.INF) {
            assertFalse(res.complete(), label + ": brute force finds no complete path, DP says " + res.seconds());
            assertEquals(bf.longestFirstRound(), res.rounds().isEmpty() ? 0 : res.rounds().get(0).cells(),
                label + ": fallback load");
            return;
        }
        assertTrue(res.complete(), label + ": DP finds no path, brute force " + opt);
        assertEquals(opt, res.seconds(), 1e-9, label + ": optimum");
        double sum = 0;
        for (Round rd : res.rounds()) sum += rd.seconds();
        assertEquals(res.seconds(), sum, 1e-9, label + ": rounds add up");
        assertEquals(res.rounds().size(), res.plan().rounds(), label);
        double first = res.rounds().get(0).seconds();
        List<Double> firsts = bf.optimalFirstCosts();
        boolean found = false;
        for (double f : firsts) found |= Math.abs(f - first) <= 1e-9;
        if (!found) fail(label + ": DP first round " + first + " is not an optimal first round " + firsts);
    }

    @Test
    void splitMatchesBruteForce() {
        Random rnd = new Random(20261011L);
        for (int n = 0; n < INSTANCES; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            check(r, false, "SPLIT instance " + n);
        }
    }

    @Test
    void lanesMatchesBruteForce() {
        Random rnd = new Random(77L);
        for (int n = 0; n < INSTANCES; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            check(r, true, "LANES instance " + n);
        }
    }

    @Test
    void splitWithLoadDirectionMatchesBruteForce() {
        Random rnd = new Random(20261023L);
        int flipped = 0;
        for (int n = 0; n < INSTANCES; n++) {
            Request r = SplitRestockPlannerBruteForce.randomFlipInstance(rnd);
            check(r, false, "SPLIT flip instance " + n);
            Result res = new SplitRestockPlanner(false).solve(r);
            if (!res.rounds().isEmpty()) assertEquals(res.rounds().get(0).flipped(), res.plan().flipped(), "instance " + n);
            for (Round rd : res.rounds()) if (rd.flipped()) flipped++;
        }
        // the random set must exercise the choice
        assertTrue(flipped > INSTANCES / 10, "rounds walked the other way: " + flipped);
    }

    @Test
    void lanesWithLoadDirectionMatchesBruteForce() {
        Random rnd = new Random(1023L);
        for (int n = 0; n < INSTANCES; n++) {
            Request r = SplitRestockPlannerBruteForce.randomFlipInstance(rnd);
            check(r, true, "LANES flip instance " + n);
        }
    }

    @Test
    void theLoadDirectionNeverCostsMoreInTheModel() {
        // the choice is a superset: with it the optimum is never worse, and without a site it changes nothing
        Random rnd = new Random(42L);
        for (int n = 0; n < INSTANCES; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            if (r.site() == null) continue;
            Request f = new Request(r.remaining(), r.held(), r.heldSlots(), r.freeSlots(), r.maxStack(),
                r.lastLoadReserve(), r.topUp(), r.site().flipping(true));
            Result a = new SplitRestockPlanner(false).solve(r);
            Result b = new SplitRestockPlanner(false).solve(f);
            if (!a.complete()) continue;
            assertTrue(b.complete(), "instance " + n);
            assertTrue(b.seconds() <= a.seconds() + 1e-9, "instance " + n + ": " + b.seconds() + " > " + a.seconds());
            assertFalse(a.plan().flipped(), "instance " + n + ": flipped without the switch");
        }
    }

    @Test
    void bruteForceWithLoadDirectionMemoAgreesWithFullEnumeration() {
        Random rnd = new Random(6L);
        int checked = 0;
        for (int n = 0; n < 300 && checked < 60; n++) {
            Request r = SplitRestockPlannerBruteForce.randomFlipInstance(rnd);
            int rows = 0;
            for (var l : r.remaining()) rows += (int) l.placements().stream().map(p -> p.pos().z()).distinct().count();
            if (rows > 10) continue;
            checked++;
            for (boolean lanesFirst : new boolean[] {false, true}) {
                double a = new SplitRestockPlannerBruteForce(r, lanesFirst, true).optimum();
                double b = new SplitRestockPlannerBruteForce(r, lanesFirst, false).optimum();
                if (a == SplitRestockPlannerBruteForce.INF) assertEquals(a, b);
                else assertEquals(a, b, 1e-9);
            }
        }
        assertTrue(checked >= 30, "tiny instances checked: " + checked);
    }

    @Test
    void someInstancesHaveSeveralRoundsAndMidLaneCuts() {
        // The random set must exercise the search: multi-round paths and loads ending inside a lane.
        Random rnd = new Random(20261011L);
        int multi = 0;
        int midLane = 0;
        for (int n = 0; n < INSTANCES; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            Result res = new SplitRestockPlanner(false).solve(r);
            if (!res.complete()) continue;
            if (res.rounds().size() >= 3) multi++;
            for (Round rd : res.rounds()) {
                if (rd.terminal()) continue;
                long rows = r.remaining().get(rd.toLane()).placements().stream().map(p -> p.pos().z()).distinct().count();
                if (rd.toLo() > 0 || rd.toHi() < rows - 1) midLane++;
            }
        }
        assertTrue(multi > INSTANCES / 10, "multi-round paths: " + multi);
        assertTrue(midLane > INSTANCES / 10, "mid-lane cuts: " + midLane);
    }

    @Test
    void bruteForceMemoAgreesWithFullEnumeration() {
        // The brute force itself: memoized recursion against explicit enumeration of every cut sequence.
        Random rnd = new Random(5L);
        int checked = 0;
        for (int n = 0; n < 300 && checked < 60; n++) {
            Request r = SplitRestockPlannerBruteForce.randomInstance(rnd);
            int rows = 0;
            for (var l : r.remaining()) rows += (int) l.placements().stream().map(p -> p.pos().z()).distinct().count();
            if (rows > 11) continue;
            checked++;
            for (boolean lanesFirst : new boolean[] {false, true}) {
                double a = new SplitRestockPlannerBruteForce(r, lanesFirst, true).optimum();
                double b = new SplitRestockPlannerBruteForce(r, lanesFirst, false).optimum();
                if (a == SplitRestockPlannerBruteForce.INF) assertEquals(a, b);
                else assertEquals(a, b, 1e-9);
            }
        }
        assertTrue(checked >= 30, "tiny instances checked: " + checked);
    }
}
