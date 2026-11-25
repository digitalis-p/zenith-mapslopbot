package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route off the muncher's rail field (live 2026-11-14): one-wide lines over open air, joined by single link cells,
 * with bumps to climb over and a booth two below the last line.
 */
class FootRouteTest {
    /** Solid blocks; everything else is air. */
    private final Set<Pos> solid = new HashSet<>();

    private final FootRoute.Cells cells = new FootRoute.Cells() {
        @Override
        public boolean stand(Pos c) {
            return !solid.contains(c) && !solid.contains(c.above()) && solid.contains(c.below());
        }

        @Override
        public boolean pass(Pos c) {
            return !solid.contains(c);
        }
    };

    /** Floor under feet cell (x, y, z). */
    private void floor(int x, int y, int z) {
        solid.add(new Pos(x, y - 1, z));
    }

    /** Two lanes along z at x 0 and x -2 (feet y 10), a bump on each, one link cell between them, a booth 2 below. */
    private void field() {
        for (int z = 0; z <= 20; z++) {
            floor(0, 10, z);
            floor(-2, 10, z);
        }
        solid.add(new Pos(0, 10, 12));  // bump on lane 0: feet y 11 on top
        solid.add(new Pos(-2, 10, 8));  // bump on lane -2
        floor(-1, 10, 12);              // the link cell beside lane 0's bump
        floor(-3, 8, 0);                // the booth: 2 below the end of lane -2
    }

    @Test
    void theRouteCrossesByTheLinkClimbsTheBumpsAndStepsDownIntoTheBooth() {
        field();
        Pos start = new Pos(0, 10, 18), booth = new Pos(-3, 8, 0);
        List<Pos> r = FootRoute.find(cells, start, booth::equals, c -> false, 10_000);
        assertNotNull(r);
        assertEquals(start, r.getFirst());
        assertEquals(booth, r.getLast());
        assertTrue(r.contains(new Pos(-1, 10, 12)), "the only way across is the link cell: " + r);
        assertTrue(r.contains(new Pos(-2, 11, 8)), "over lane -2's bump: " + r);
        for (int i = 1; i < r.size(); i++) {
            Pos a = r.get(i - 1), b = r.get(i);
            assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()), "one cell north, south, east or west: " + a + " " + b);
            int dy = b.y() - a.y();
            assertTrue(dy <= 1 && dy >= -FootRoute.MAX_DROP, "one up, at most three down: " + a + " " + b);
            assertTrue(cells.stand(b), "a cell to stand in: " + b);
        }
    }

    @Test
    void aDropDeeperThanThreeIsNoRouteAndARefusedCellIsNeverEntered() {
        field();
        solid.remove(new Pos(-3, 7, 0));
        floor(-3, 6, 0); // the booth is now 4 below the lane
        assertNull(FootRoute.find(cells, new Pos(0, 10, 18), new Pos(-3, 6, 0)::equals, c -> false, 10_000));
        solid.clear();
        field();
        Pos link = new Pos(-1, 10, 12);
        assertNull(FootRoute.find(cells, new Pos(0, 10, 18), new Pos(-3, 8, 0)::equals, link::equals, 10_000),
            "the link refused (a tripwire in it): no way across, and never a jump over the gap");
    }

    @Test
    void aHopIsOneStraightLevelRunOrOneStep() {
        field();
        List<Pos> r = FootRoute.find(cells, new Pos(0, 10, 18), new Pos(-3, 8, 0)::equals, c -> false, 10_000);
        assertNotNull(r);
        int at = 0;
        boolean turned = false, stepped = false;
        while (at < r.size() - 1) {
            int to = FootRoute.nextWaypoint(r, at, 4);
            assertTrue(to > at && to - at <= 4, at + " -> " + to);
            Pos a = r.get(at), b = r.get(to);
            if (a.y() != b.y()) {
                assertEquals(at + 1, to, "a step up or down is a hop of its own");
                stepped = true;
            } else {
                assertTrue(a.x() == b.x() || a.z() == b.z(), "one line: " + a + " " + b);
                for (int i = at + 1; i <= to; i++) assertEquals(a.y(), r.get(i).y(), "level");
                turned |= to < r.size() - 1 && to - at < 4;
            }
            at = to;
        }
        assertEquals(r.size() - 1, at);
        assertTrue(turned && stepped, "the route has turns and steps");
    }

    /**
     * A way round a field, as on the muncher's far row: a walkway along the north side (z 0, x -128..0), a floor down
     * the west side (x -128, z 0..100) and a strip along the south side (z 100, x -128..0), each an opened box; the
     * target is the strip's east end. From the north-west corner with five chunks in view the search comes 80 cells
     * east along the walkway and 80 south along the floor. The walkway's east end is nearer the target in a straight
     * line and leads nowhere; through the opened cells the floor's end has the fewest steps left.
     */
    @Test
    void aLegGoesToTheReachedCellWithTheFewestStepsLeftThroughTheOpenedCellsNotTheNearestInAStraightLine() {
        java.util.function.Predicate<Pos> opened = p -> p.y() >= 10 && p.y() <= 11
            && ((p.z() == 0 && p.x() >= -128 && p.x() <= 0) || (p.x() == -128 && p.z() >= 0 && p.z() <= 100)
            || (p.z() == 100 && p.x() >= -128 && p.x() <= 0));
        Pos start = new Pos(-128, 10, 0), target = new Pos(0, 10, 100);
        Set<Pos> reached = new HashSet<>();
        for (int i = 0; i <= 80; i++) {
            reached.add(new Pos(-128 + i, 10, 0));
            reached.add(new Pos(-128, 10, i));
        }
        java.util.Map<Pos, Integer> steps = FootRoute.openedSteps(opened, List.of(target), reached, 100_000);
        assertEquals(228, steps.get(start), "100 down the west side, 128 along the strip");
        assertEquals(148, steps.get(new Pos(-128, 10, 80)));
        assertEquals(308, steps.get(new Pos(-48, 10, 0)), "the walkway's end: back west first");
        assertEquals(new Pos(-128, 10, 80), FootRoute.legEnd(start, target, reached, steps, 16));
        // the straight line alone (no opened cells known): the walkway's east end, 111 from the target against 130
        assertEquals(new Pos(-48, 10, 0), FootRoute.legEnd(start, target, reached, java.util.Map.of(), 16));
        // no leg without a gain: from the floor's end nothing reached is 16 steps nearer
        Set<Pos> few = new HashSet<>();
        for (int i = 0; i <= 10; i++) few.add(new Pos(-128, 10, 80 + i));
        java.util.Map<Pos, Integer> near = FootRoute.openedSteps(opened, List.of(target), few, 100_000);
        assertNull(FootRoute.legEnd(new Pos(-128, 10, 80), target, few, near, 16));
        assertEquals(new Pos(-128, 10, 96), FootRoute.legEnd(new Pos(-128, 10, 80), target,
            Set.of(new Pos(-128, 10, 90), new Pos(-128, 10, 96)), FootRoute.openedSteps(opened, List.of(target),
                Set.of(new Pos(-128, 10, 80), new Pos(-128, 10, 90), new Pos(-128, 10, 96)), 100_000), 16));
        // a start outside every opened cell has no steps: the straight line decides
        assertNull(FootRoute.openedSteps(opened, List.of(target), Set.of(new Pos(5, 10, 5)), 100_000).get(new Pos(5, 10, 5)));
    }
}
