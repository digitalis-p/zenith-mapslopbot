package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.ContainerVisit;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;
import io.github.digitalisp.mapart.testutil.ContainerSites;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.GLASS;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;

/**
 * The scan sweep on a synthetic 62-column, 5-row sorter of double chests (the moss sorter's shape, relative
 * coordinates): one pass along the walkway, several containers per stand cell, every container once.
 */
class SweepPlannerTest {
    private static final double REACH = 4.3;
    private static final int X0 = 0;
    private static final int X1 = 61;
    private static final int FLOOR = 63;
    private static final int FRONT = 10;
    private static final int COLUMNS = X1 - X0 + 1;
    private static final int ROWS = 5;

    /** Sight of one container: the simulated raycast at its blocks. */
    private static StandSpots.Sight sight(ContainerSim cs, List<Pos> blocks) {
        return new StandSpots.Sight() {
            @Override
            public boolean visibleFrom(double ex, double ey, double ez, double reach) {
                cs.targets = blocks;
                return cs.visibleFrom(ex, ey, ez, reach);
            }

            @Override
            public String hiddenFrom(double ex, double ey, double ez, double reach) {
                cs.targets = blocks;
                return cs.hiddenFrom(ex, ey, ez, reach);
            }
        };
    }

    private static ContainerSim sorter() {
        ContainerSim cs = new ContainerSim(7);
        ContainerSites.sorterWall(cs, X0, X1, FLOOR, FRONT);
        return cs;
    }

    /** Every container of the sorter wall: column by column, bottom row first (the engine's order does not matter). */
    private static List<SweepPlanner.Target> targets(ContainerSim cs) {
        List<SweepPlanner.Target> out = new ArrayList<>();
        for (int x = X0; x <= X1; x++) {
            for (int y = FLOOR + 1; y <= FLOOR + ROWS; y++) {
                List<Pos> blocks = ContainerSites.sorterChest(x, y, FRONT);
                out.add(new SweepPlanner.Target(blocks.getFirst(), blocks, sight(cs, blocks)));
            }
        }
        return out;
    }

    private static SweepPlanner.Plan plan(ContainerSim cs, List<SweepPlanner.Target> ts, double bx, double bz) {
        return SweepPlanner.plan(cs, ts, REACH, bx, FLOOR + 1, bz);
    }

    /** Every target's container exactly once in the visits. */
    private static void assertEveryOnce(SweepPlanner.Plan p, List<SweepPlanner.Target> ts) {
        List<ContainerVisit> v = p.visits();
        Set<Pos> seen = new HashSet<>();
        for (ContainerVisit cv : v) assertTrue(seen.add(cv.container()), "twice: " + cv.container());
        Set<Pos> want = new HashSet<>();
        for (SweepPlanner.Target t : ts) want.add(t.container());
        assertEquals(want, seen);
        assertEquals(want.size(), v.size());
    }

    /** Direction changes of the stops along x (0 = one pass). */
    private static int reversals(SweepPlanner.Plan p) {
        int rev = 0;
        int last = 0;
        List<SweepPlanner.Stop> s = p.stops();
        for (int i = 1; i < s.size(); i++) {
            int d = Integer.signum(s.get(i).cell().x() - s.get(i - 1).cell().x());
            if (d == 0) continue;
            if (last != 0 && d != last) rev++;
            last = d;
        }
        return rev;
    }

    private static String stats(String what, SweepPlanner.Plan p, double bx, double bz) {
        int min = Integer.MAX_VALUE;
        for (SweepPlanner.Stop s : p.stops()) min = Math.min(min, s.containers().size());
        double mean = (double) (p.visits().size() - p.unplanned().size()) / Math.max(1, p.stops().size());
        return String.format(Locale.ROOT, "%s: %d stops, %.1f containers per stop (min %d), walk %.1f blocks, %d unplanned,"
            + " %d reversals", what, p.stops().size(), mean, min, p.walk(bx, bz), p.unplanned().size(), reversals(p));
    }

    @Test
    void sorterFromOneEndIsOnePassWithFewStops() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        double bx = X0 + 0.5;
        double bz = FRONT + 3.5;
        long t0 = System.nanoTime();
        SweepPlanner.Plan p = plan(cs, ts, bx, bz);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.println(stats("62x5 sorter, bot at the west end", p, bx, bz)
            + String.format(Locale.ROOT, "; planned in %.1f ms", ms));

        assertEveryOnce(p, ts);
        assertEquals(COLUMNS * ROWS, p.visits().size());
        assertTrue(p.unplanned().isEmpty(), p.unplanned().toString());
        // the top row (4 above the feet) is seen from 2 columns either side: ceil(62 / 5) = 13 cells at best
        assertTrue(p.stops().size() <= (COLUMNS + 4) / 5 + 2, stats("stops", p, bx, bz));
        for (SweepPlanner.Stop s : p.stops()) {
            assertTrue(s.containers().size() >= 5, "a stop serves several containers: " + s);
            assertEquals(FLOOR + 1, s.cell().y(), "on the walkway, never on top of the sorter: " + s.cell());
            assertTrue(s.cell().z() > FRONT, "in front of the chests: " + s.cell());
        }
        for (int i = 1; i < p.stops().size(); i++) {
            assertTrue(p.stops().get(i).cell().x() > p.stops().get(i - 1).cell().x(), "eastwards, one pass: " + p.stops());
        }
        assertEquals(0, reversals(p));
        assertTrue(p.walk(bx, bz) <= COLUMNS + 4, stats("walk", p, bx, bz));
        // every container is in reach and sight from its stop
        for (SweepPlanner.Stop s : p.stops()) {
            double ey = StandSpots.feetY(cs, s.cell().x(), s.cell().y(), s.cell().z()) + Aim.EYE_HEIGHT;
            for (Pos c : s.containers()) {
                cs.targets = ContainerSites.sorterChest(c.x(), c.y(), FRONT);
                assertTrue(cs.visibleFrom(s.cell().x() + 0.5, ey, s.cell().z() + 0.5, REACH - StandSpots.CENTRE_MARGIN),
                    c + " from " + s.cell());
            }
        }
    }

    @Test
    void fromTheOtherEndTheSweepRunsTheOtherWay() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        SweepPlanner.Plan west = plan(cs, ts, X0 + 0.5, FRONT + 3.5);
        double bx = X1 + 0.5;
        double bz = FRONT + 3.5;
        SweepPlanner.Plan p = plan(cs, ts, bx, bz);
        System.out.println(stats("62x5 sorter, bot at the east end", p, bx, bz));
        assertEveryOnce(p, ts);
        assertEquals(west.stops().size(), p.stops().size());
        assertTrue(p.stops().getFirst().cell().x() >= X1 - 4, "starts at the east end: " + p.stops().getFirst());
        for (int i = 1; i < p.stops().size(); i++) {
            assertTrue(p.stops().get(i).cell().x() < p.stops().get(i - 1).cell().x(), "westwards: " + p.stops());
        }
        assertTrue(p.walk(bx, bz) <= COLUMNS + 4, stats("walk", p, bx, bz));
    }

    @Test
    void fromTheMiddleItGoesToTheNearerEndFirstThenOnePass() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        double bx = X0 + 31.5;
        double bz = FRONT + 3.5;
        SweepPlanner.Plan p = plan(cs, ts, bx, bz);
        System.out.println(stats("62x5 sorter, bot in the middle", p, bx, bz));
        assertEveryOnce(p, ts);
        assertEquals(0, reversals(p), "the stops are one pass; only the walk to the first one goes the other way");
        assertTrue(p.walk(bx, bz) <= COLUMNS + COLUMNS / 2 + 4, stats("walk", p, bx, bz));
    }

    @Test
    void columnsAreOpenedWholeAndInASerpentine() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        SweepPlanner.Plan p = plan(cs, ts, X0 + 0.5, FRONT + 3.5);
        List<ContainerVisit> v = p.visits();
        // a column's five chests come one after the other, from one stand cell
        List<List<ContainerVisit>> runs = new ArrayList<>();
        for (ContainerVisit cv : v) {
            List<ContainerVisit> last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && last.getFirst().container().x() == cv.container().x()) last.add(cv);
            else runs.add(new ArrayList<>(List.of(cv)));
        }
        assertEquals(COLUMNS, runs.size(), "each column once, whole");
        int lastDir = 0;
        for (List<ContainerVisit> run : runs) {
            assertEquals(ROWS, run.size());
            for (ContainerVisit cv : run) assertEquals(run.getFirst().standAt(), cv.standAt(), "one cell per column");
            int dir = Integer.signum(run.get(1).container().y() - run.get(0).container().y());
            for (int i = 1; i < run.size(); i++) {
                assertEquals(dir, Integer.signum(run.get(i).container().y() - run.get(i - 1).container().y()),
                    "monotone in the column: " + run);
            }
            if (lastDir != 0) assertEquals(-lastDir, dir, "the next column goes the other way: " + run);
            lastDir = dir;
        }
        // the first column starts at the end nearer the eye (the bottom row, 1.12 below it, vs 2.88 for the top)
        assertEquals(FLOOR + 1, v.getFirst().container().y());
        // columns follow the walk east
        for (int i = 1; i < runs.size(); i++) {
            assertTrue(runs.get(i).getFirst().container().x() > runs.get(i - 1).getFirst().container().x());
        }
    }

    /**
     * A chest walled in with glass on every side: no cell sees a face centre. (A glass pillar in front of one sorter
     * column is not enough: its side faces stay in sight through the 2/16 gaps between the columns, as with the real
     * chest shapes, and the bottom faces through the gaps between the rows.)
     */
    @Test
    void aContainerHiddenFromEveryCellIsUnplannedAndLast() {
        ContainerSim cs = sorter();
        Pos boxed = new Pos(X0 + 30, FLOOR + 1, FRONT + 5);
        cs.fill(boxed.x() - 1, boxed.y(), boxed.z() - 1, boxed.x() + 1, boxed.y() + 1, boxed.z() + 1, GLASS);
        cs.set(boxed, chest("north", "single"));
        List<SweepPlanner.Target> ts = new ArrayList<>(targets(cs));
        ts.add(15, new SweepPlanner.Target(boxed, List.of(boxed), sight(cs, List.of(boxed))));
        SweepPlanner.Plan p = plan(cs, ts, X0 + 0.5, FRONT + 3.5);
        System.out.println(stats("62x5 sorter plus a chest walled in with glass", p, X0 + 0.5, FRONT + 3.5));
        assertEveryOnce(p, ts);
        Set<Pos> want = Set.of(boxed);
        assertEquals(want, new HashSet<>(p.unplanned()));
        List<ContainerVisit> v = p.visits();
        for (int i = 0; i < v.size(); i++) {
            boolean last = i >= v.size() - want.size();
            assertEquals(last, want.contains(v.get(i).container()), "the unplanned ones come last");
            if (last) assertNull(v.get(i).standAt());
            else assertNotNull(v.get(i).standAt());
        }
        assertEquals(0, reversals(p));
    }

    @Test
    void containersNotLoadedAreUnplanned() {
        ContainerSim cs = sorter();
        int edge = X1 - 1; // the last two columns are not loaded
        StandSpots.World w = new StandSpots.World() {
            @Override public boolean isLoaded(Pos pos) { return pos.x() < edge; }
            @Override public BlockSpec block(Pos pos) { return isLoaded(pos) ? cs.block(pos) : BlockSpec.AIR; }
            @Override public boolean hasFluid(Pos pos) { return cs.hasFluid(pos); }
            @Override public double collisionTop(BlockSpec spec) { return cs.collisionTop(spec); }
        };
        List<SweepPlanner.Target> ts = targets(cs);
        SweepPlanner.Plan p = SweepPlanner.plan(w, ts, REACH, X0 + 0.5, FLOOR + 1, FRONT + 3.5);
        assertEveryOnce(p, ts);
        assertEquals(2 * ROWS, p.unplanned().size());
        for (Pos c : p.unplanned()) assertTrue(c.x() >= edge, c.toString());
    }

    @Test
    void duplicatesAreOpenedOnce() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = new ArrayList<>(targets(cs).subList(0, 10));
        ts.addAll(targets(cs).subList(0, 3));
        SweepPlanner.Plan p = plan(cs, ts, X0 + 0.5, FRONT + 3.5);
        assertEquals(10, p.visits().size());
        assertEveryOnce(p, ts);
        assertEquals(1, p.stops().size(), "two columns from one cell: " + p.stops());
    }

    @Test
    void emptyListIsAnEmptyPlan() {
        SweepPlanner.Plan p = plan(sorter(), List.of(), X0 + 0.5, FRONT + 3.5);
        assertTrue(p.stops().isEmpty());
        assertTrue(p.visits().isEmpty());
    }

    /**
     * A second sorter wall facing the first across a hall 10 wide: no cell sees both walls. One walkway along each
     * wall; the second is walked back (serpentine), not from the start again.
     */
    @Test
    void twoWallsAreWalkedAsASerpentine() {
        ContainerSim cs = sorter();
        int back = FRONT + 11; // the far wall's front half
        cs.fill(X0 - 4, FLOOR, FRONT + 7, X1 + 4, FLOOR, back - 1, GLASS);
        List<SweepPlanner.Target> ts = targets(cs);
        for (int x = X0; x <= X1; x++) {
            cs.set(x, FLOOR + 1, back - 1, BlockSpec.of("minecraft:lime_carpet"));
            for (int y = FLOOR + 1; y <= FLOOR + ROWS; y++) {
                cs.set(x, y, back, chest("west", "right"));
                cs.set(x, y, back + 1, chest("west", "left"));
                cs.set(x, y, back + 2, BlockSpec.of("minecraft:hopper"));
                List<Pos> blocks = List.of(new Pos(x, y, back), new Pos(x, y, back + 1));
                ts.add(new SweepPlanner.Target(blocks.getFirst(), blocks, sight(cs, blocks)));
            }
        }
        double bx = X0 + 0.5;
        double bz = FRONT + 3.5;
        SweepPlanner.Plan p = plan(cs, ts, bx, bz);
        System.out.println(stats("two facing 62x5 walls", p, bx, bz));
        assertEveryOnce(p, ts);
        assertTrue(p.unplanned().isEmpty());
        List<SweepPlanner.Stop> s = p.stops();
        int split = 0;
        while (split < s.size() && s.get(split).cell().z() < (FRONT + back) / 2) split++;
        for (int i = split; i < s.size(); i++) assertTrue(s.get(i).cell().z() > (FRONT + back) / 2, "near wall first: " + s);
        assertTrue(split > 0 && split < s.size());
        for (int i = 1; i < split; i++) assertTrue(s.get(i).cell().x() > s.get(i - 1).cell().x(), "near wall eastwards");
        for (int i = split + 1; i < s.size(); i++) assertTrue(s.get(i).cell().x() < s.get(i - 1).cell().x(), "far wall back west");
        assertEquals(1, reversals(p), "one turn at the east end");
        for (SweepPlanner.Stop st : s.subList(0, split)) {
            for (Pos c : st.containers()) assertEquals(FRONT - 1, c.z(), "the near wall's chests from the near walkway");
        }
        assertTrue(p.walk(bx, bz) <= 2 * COLUMNS + (back - FRONT) + 6, stats("walk", p, bx, bz));
    }

    // ------------------------------------------------------------------------------------------------ end point

    private static SweepPlanner.Plan plan(ContainerSim cs, List<SweepPlanner.Target> ts, double bx, double bz, Pos then) {
        return SweepPlanner.plan(cs, ts, REACH, bx, FLOOR + 1, bz, then);
    }

    /** Bot → stops → {@code then}, straight lines (what the end point is chosen by). */
    private static double walkTo(ContainerSim cs, List<SweepPlanner.Target> ts, double bx, double bz, Pos then,
                                 SweepPlanner.Plan p) {
        return new SweepPlanner(cs, ts, REACH, bx, FLOOR + 1, bz, then).walkTo(p);
    }

    @Test
    void withoutAnEndPointThePlanIsTodays() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        for (double bx : new double[]{X0 + 0.5, X0 + 31.5, X1 + 0.5}) {
            assertEquals(plan(cs, ts, bx, FRONT + 3.5), plan(cs, ts, bx, FRONT + 3.5, null), "bot at x " + bx);
        }
    }

    /**
     * A line of chests, the bot near the middle (nearer the east end, so today's sweep starts there and ends in the
     * west): with the next lane at the east end the sweep runs the other way and ends there; at the west end it stays.
     */
    @Test
    void fromTheMiddleTheSweepEndsAtTheEndPoint() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        double bx = X0 + 31.5;
        double bz = FRONT + 3.5;
        SweepPlanner.Plan today = plan(cs, ts, bx, bz);
        Pos east = new Pos(X1 + 2, FLOOR + 1, FRONT + 3);
        Pos west = new Pos(X0 - 2, FLOOR + 1, FRONT + 3);
        for (Pos then : List.of(east, west)) {
            SweepPlanner.Plan p = plan(cs, ts, bx, bz, then);
            System.out.println(stats("62x5 sorter, bot in the middle, then " + then, p, bx, bz)
                + String.format(Locale.ROOT, "; to then %.1f (today's order %.1f)", walkTo(cs, ts, bx, bz, then, p),
                walkTo(cs, ts, bx, bz, then, today)));
            assertEveryOnce(p, ts);
            assertTrue(p.unplanned().isEmpty());
            assertEquals(0, reversals(p), "one pass");
            Pos first = p.stops().getFirst().cell();
            Pos last = p.stops().getLast().cell();
            assertTrue(Math.abs(last.x() - then.x()) <= 6, "ends at the end point's end: " + last + " for " + then);
            assertTrue(Math.abs(first.x() - then.x()) >= X1 - X0 - 12, "starts at the other end: " + first);
            assertTrue(walkTo(cs, ts, bx, bz, then, p) <= walkTo(cs, ts, bx, bz, then, today) + 1e-9);
            assertTrue(p.walk(bx, bz) <= COLUMNS + COLUMNS / 2 + 4, stats("walk", p, bx, bz));
        }
        assertEquals(today, plan(cs, ts, bx, bz, west), "today's sweep already ends in the west");
        assertTrue(plan(cs, ts, bx, bz, east).stops().getLast().cell().x() > today.stops().getLast().cell().x());
    }

    @Test
    void anEndPointWhereTodaysSweepEndsKeepsIt() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        Pos east = new Pos(X1 + 2, FLOOR + 1, FRONT + 3);
        assertEquals(plan(cs, ts, X0 + 0.5, FRONT + 3.5), plan(cs, ts, X0 + 0.5, FRONT + 3.5, east));
        Pos west = new Pos(X0 - 2, FLOOR + 1, FRONT + 3);
        assertEquals(plan(cs, ts, X1 + 0.5, FRONT + 3.5), plan(cs, ts, X1 + 0.5, FRONT + 3.5, west));
    }

    /** The bot at the west end and the next lane there too: the sweep goes out and comes back on its own (the far end). */
    @Test
    void anEndPointAtTheStartStillSweepsOnce() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        double bx = X0 + 0.5;
        double bz = FRONT + 3.5;
        Pos west = new Pos(X0 - 2, FLOOR + 1, FRONT + 3);
        SweepPlanner.Plan p = plan(cs, ts, bx, bz, west);
        assertEveryOnce(p, ts);
        assertEquals(0, reversals(p));
        assertEquals(plan(cs, ts, bx, bz).stops().size(), p.stops().size());
    }

    /** Two facing walls (as {@link #twoWallsAreWalkedAsASerpentine}) with the end point at the far wall's east end. */
    @Test
    void twoWallsWithAnEndPointAreStillASerpentine() {
        ContainerSim cs = sorter();
        int back = FRONT + 11;
        cs.fill(X0 - 4, FLOOR, FRONT + 7, X1 + 4, FLOOR, back - 1, GLASS);
        List<SweepPlanner.Target> ts = targets(cs);
        for (int x = X0; x <= X1; x++) {
            cs.set(x, FLOOR + 1, back - 1, BlockSpec.of("minecraft:lime_carpet"));
            for (int y = FLOOR + 1; y <= FLOOR + ROWS; y++) {
                cs.set(x, y, back, chest("west", "right"));
                cs.set(x, y, back + 1, chest("west", "left"));
                cs.set(x, y, back + 2, BlockSpec.of("minecraft:hopper"));
                List<Pos> blocks = List.of(new Pos(x, y, back), new Pos(x, y, back + 1));
                ts.add(new SweepPlanner.Target(blocks.getFirst(), blocks, sight(cs, blocks)));
            }
        }
        double bx = X0 + 0.5;
        double bz = FRONT + 3.5;
        SweepPlanner.Plan today = plan(cs, ts, bx, bz);
        for (Pos then : List.of(new Pos(X1 + 1, FLOOR + 1, back - 3), new Pos(X0 - 1, FLOOR + 1, back - 3),
            new Pos(X0 - 1, FLOOR + 1, FRONT + 3))) {
            SweepPlanner.Plan p = plan(cs, ts, bx, bz, then);
            System.out.println(stats("two facing 62x5 walls, then " + then, p, bx, bz)
                + String.format(Locale.ROOT, "; to then %.1f (today's order %.1f)", walkTo(cs, ts, bx, bz, then, p),
                walkTo(cs, ts, bx, bz, then, today)));
            assertEveryOnce(p, ts);
            assertTrue(p.unplanned().isEmpty());
            assertEquals(1, reversals(p), "one turn: " + p.stops());
            assertTrue(walkTo(cs, ts, bx, bz, then, p) <= walkTo(cs, ts, bx, bz, then, today) + 1e-9);
            List<SweepPlanner.Stop> s = p.stops();
            int split = 0;
            boolean nearFirst = s.getFirst().cell().z() < (FRONT + back) / 2;
            while (split < s.size() && (s.get(split).cell().z() < (FRONT + back) / 2) == nearFirst) split++;
            for (int i = split; i < s.size(); i++) {
                assertEquals(!nearFirst, s.get(i).cell().z() < (FRONT + back) / 2, "one walkway, then the other: " + s);
            }
        }
    }

    @Test
    void stepByStepGivesTheSamePlan() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        SweepPlanner sp = new SweepPlanner(cs, ts, REACH, X0 + 0.5, FLOOR + 1, FRONT + 3.5);
        int calls = 1;
        while (!sp.step(SweepPlanner.CONTAINERS_PER_TICK)) calls++;
        assertEquals((ts.size() + SweepPlanner.CONTAINERS_PER_TICK - 1) / SweepPlanner.CONTAINERS_PER_TICK, calls);
        assertEquals(plan(cs, ts, X0 + 0.5, FRONT + 3.5), sp.plan());
        assertEquals(sp.plan(), sp.plan(), "the plan is kept");
    }

    /** A cell that did not see a container after all (the session memory) is never its stop; the others keep theirs. */
    @Test
    void aCellToAvoidForAContainerIsNotItsStop() {
        ContainerSim cs = sorter();
        List<SweepPlanner.Target> ts = targets(cs);
        SweepPlanner.Plan before = plan(cs, ts, X0 + 0.5, FRONT + 3.5);
        Pos victim = ts.get(7).container();
        Pos stop = null;
        for (ContainerVisit v : before.visits()) if (v.container().equals(victim)) stop = v.standAt();
        assertNotNull(stop);
        List<SweepPlanner.Target> avoiding = new ArrayList<>(ts);
        SweepPlanner.Target t = ts.get(7);
        avoiding.set(7, new SweepPlanner.Target(t.container(), t.blocks(), t.sight(), Set.of(stop)));
        SweepPlanner.Plan after = plan(cs, avoiding, X0 + 0.5, FRONT + 3.5);
        assertEveryOnce(after, avoiding);
        for (ContainerVisit v : after.visits()) {
            if (v.container().equals(victim)) {
                assertNotNull(v.standAt(), "still planned from another cell");
                assertTrue(!v.standAt().equals(stop), "not from the avoided cell " + stop);
            }
        }
    }
}
