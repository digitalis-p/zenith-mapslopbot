package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;
import io.github.digitalisp.mapart.testutil.ContainerSites;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;

/** {@code site.noGoZones}: goals, paths and stand spots keep the bot's feet and head out of every zone. */
class NoGoZonesTest {
    private static MapArtConfig.RegionCfg box(String name, Pos a, Pos b) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.name = name;
        r.min = a;
        r.max = b;
        return r;
    }

    /** One zone: x 10..12, y 70..72, z -5..5 ("muncher"). */
    private static NoGoZones muncher() {
        return NoGoZones.of(List.of(box("muncher", new Pos(12, 72, 5), new Pos(10, 70, -5))));
    }

    @Test
    void zonesComeFromTheConfigWithLabelsAndIncompleteBoxesSkipped() {
        MapArtConfig.RegionCfg incomplete = new MapArtConfig.RegionCfg();
        incomplete.min = new Pos(0, 0, 0);
        NoGoZones z = NoGoZones.of(List.of(box("muncher", new Pos(12, 72, 5), new Pos(10, 70, -5)), incomplete,
            box(null, new Pos(0, 0, 0), new Pos(1, 1, 1))));
        assertEquals(2, z.zones().size());
        assertEquals("#1 \"muncher\" (10,70,-5 to 12,72,5)", z.zones().get(0).toString());
        assertEquals("#3 (0,0,0 to 1,1,1)", z.zones().get(1).toString());
        assertSame(NoGoZones.NONE, NoGoZones.of(List.of()));
        assertSame(NoGoZones.NONE, NoGoZones.of(null));
        assertSame(NoGoZones.NONE, NoGoZones.of(List.of(incomplete)));
    }

    @Test
    void feetOrHeadInsideCounts() {
        NoGoZones z = muncher();
        assertNotNull(z.at(10, 70, 0), "feet in it");
        assertNotNull(z.at(12, 72, 5), "corner, inclusive");
        assertNotNull(z.at(11, 69, 0), "head in it (feet one below)");
        assertNull(z.at(11, 68, 0), "head below it");
        assertNull(z.at(11, 73, 0), "standing on its top");
        assertNull(z.at(9, 70, 0));
        assertNull(z.at(13, 70, 0));
        assertNull(z.at(11, 70, 6));
        assertNull(NoGoZones.NONE.at(11, 70, 0));
    }

    @Test
    void theGoalForBaritoneExcludesZoneCellsAndMakesThemLookFar() {
        NoGoZones z = muncher();
        GoalNearXZ inner = new GoalNearXZ(9, 70, 0, 3, 1);
        Goal g = z.avoiding(inner);
        assertTrue(g instanceof NoGoZones.Avoiding, g.toString());
        assertTrue(inner.isInGoal(10, 70, 0));
        assertFalse(g.isInGoal(10, 70, 0), "a goal cell inside the zone is not a goal cell any more");
        assertTrue(g.isInGoal(8, 70, 0), "outside: unchanged");
        assertEquals(inner.heuristic(8, 70, 3), g.heuristic(8, 70, 3), 1e-9);
        assertTrue(g.heuristic(11, 70, 0) >= NoGoZones.PENALTY, "A* expands zone cells only after all others");
        // equality matters: Baritone's isGoalActive compares goals with equals
        assertEquals(z.avoiding(inner), g);
        // no zones: the very goal
        assertSame(inner, NoGoZones.NONE.avoiding(inner));
    }

    @Test
    void aGoalWhollyInsideAZoneIsRecognised() {
        NoGoZones z = muncher();
        assertNotNull(z.covering(new GoalNearXZ(11, 71, 0, 1, 0)), "every cell inside");
        assertNotNull(z.covering(new GoalNearXZ(11, 71, 0, 0, 0)));
        assertNull(z.covering(new GoalNearXZ(12, 71, 0, 1, 0)), "x 13 is outside");
        assertNull(z.covering(new GoalNearXZ(11, 74, 0, 0, 0)), "above it");
        assertNull(z.covering(new GoalNearXZ(11, 71, 0, 300, 2)), "too large to check: left to Baritone");
        assertNull(NoGoZones.NONE.covering(new GoalNearXZ(11, 71, 0, 0, 0)));
    }

    @Test
    void aPathThatEntersAZoneIsCaught() {
        NoGoZones z = muncher();
        List<Pos> around = new ArrayList<>();
        for (int x = 5; x <= 15; x++) around.add(new Pos(x, 70, 7)); // passes south of the zone (z 7 > 5)
        assertNull(z.pathViolation(around, null));

        List<Pos> through = new ArrayList<>();
        for (int x = 5; x <= 15; x++) through.add(new Pos(x, 70, 0));
        String v = z.pathViolation(through, null);
        assertNotNull(v);
        assertTrue(v.startsWith("enters no-go zone #1 \"muncher\" (10,70,-5 to 12,72,5) at 10,70,0"), v);

        // a diagonal step that cuts the zone's corner (10,70,5): neither end is inside, the move is
        assertNull(z.at(9, 70, 5));
        assertNull(z.at(10, 70, 6));
        String corner = z.pathViolation(List.of(new Pos(9, 70, 5), new Pos(10, 70, 6)), null);
        assertNotNull(corner);
        assertTrue(corner.endsWith("at 10,70,6"), corner);
    }

    @Test
    void movesTouchTheBoxTheyCrossIncludingHeadRoomAndFalls() {
        NoGoZones z = muncher();
        // walking under the zone: head at 69, the zone starts at 70
        assertNull(z.onMove(new Pos(9, 68, 0), new Pos(10, 68, 0)));
        // a step up under it needs jump room: head reaches 70
        assertNotNull(z.onMove(new Pos(9, 67, 0), new Pos(10, 68, 0)));
        // a fall straight through it
        assertNotNull(z.onMove(new Pos(11, 80, 0), new Pos(11, 60, 0)));
        // a parkour jump over its west edge from x 7 to x 10 at feet 68: jump room reaches 70
        assertNotNull(z.onMove(new Pos(7, 68, 0), new Pos(10, 68, 0)));
    }

    @Test
    void aBotInsideMayWalkOutButNotBackIn() {
        NoGoZones z = muncher();
        NoGoZones.Zone start = z.at(11, 70, 0);
        List<Pos> out = List.of(new Pos(11, 70, 0), new Pos(12, 70, 0), new Pos(13, 70, 0), new Pos(14, 70, 0));
        assertNull(z.pathViolation(out, start), "leaving the zone it started in");
        assertNotNull(z.pathViolation(out, null), "the same path from outside would enter it");
        List<Pos> outAndBack = List.of(new Pos(11, 70, 0), new Pos(13, 70, 0), new Pos(14, 70, 0), new Pos(12, 70, 0));
        String v = z.pathViolation(outAndBack, start);
        assertNotNull(v);
        assertTrue(v.endsWith("at 12,70,0"), v);
        // two zones: leaving the first straight into the second is entering
        NoGoZones two = NoGoZones.of(List.of(box("a", new Pos(10, 70, 0), new Pos(10, 71, 0)),
            box("b", new Pos(11, 70, 0), new Pos(11, 71, 0))));
        String w = two.pathViolation(List.of(new Pos(10, 70, 0), new Pos(11, 70, 0)), two.at(10, 70, 0));
        assertNotNull(w);
        assertTrue(w.contains("#2 \"b\""), w);
    }

    // ----------------------------------------------------------------------------------------- corner cuts

    /**
     * Live 2026-10-13 on the soul sand platform, in relative coordinates: the turnaround station's zone lies west of the
     * walkway (x -8..-1, from z 2 north), the drop cell south-west of its corner. Every drop walk failed at once: the
     * planned diagonal step from (0,0,2) to (-1,0,1) sweeps the zone's corner cell (-1,0,2), and both of its ends are free.
     */
    private static NoGoZones turnaround() {
        return NoGoZones.of(List.of(box("turnaround station west", new Pos(-8, -1, 2), new Pos(-1, 4, 129))));
    }

    /**
     * The walkway east of the zone (x 0..1, z 2..30), the floor south of it (x -5..1, z -1..1) and the station floor
     * under the zone (x -8..-1, z 2..30; walkable air: only the heuristic keeps A* off it), all at y 0.
     */
    private static boolean floor(Pos p) {
        if (p.y() != 0) return false;
        boolean walkway = p.x() >= 0 && p.x() <= 1 && p.z() >= 2 && p.z() <= 30;
        boolean south = p.x() >= -5 && p.x() <= 1 && p.z() >= -1 && p.z() <= 1;
        boolean station = p.x() >= -8 && p.x() <= -1 && p.z() >= 2 && p.z() <= 30;
        return walkway || south || station;
    }

    /**
     * Baritone's A* in miniature on one level: 8 moves (a diagonal needs both side cells walkable), each costing what
     * the goal heuristic's own scale gives that step, the open set ordered by cost + heuristic, and the goal tested
     * when a cell is taken from it. Null: no path.
     */
    private static List<Pos> plan(Pos start, Goal goal, Predicate<Pos> walkable) {
        double straight = GoalBlock.calculate(1, 0, 0);
        double diagonal = GoalBlock.calculate(1, 0, 1);
        Map<Pos, Double> cost = new HashMap<>();
        Map<Pos, Pos> parent = new HashMap<>();
        record Open(Pos cell, double f) { }
        PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::f));
        cost.put(start, 0.0);
        open.add(new Open(start, goal.heuristic(start.x(), start.y(), start.z())));
        Set<Pos> closed = new HashSet<>();
        while (!open.isEmpty()) {
            Pos c = open.poll().cell();
            if (!closed.add(c)) continue;
            if (goal.isInGoal(c.x(), c.y(), c.z())) {
                List<Pos> path = new ArrayList<>();
                for (Pos q = c; q != null; q = parent.get(q)) path.addFirst(q);
                return path;
            }
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Pos n = c.offset(dx, 0, dz);
                    if (!walkable.test(n)) continue;
                    boolean diag = dx != 0 && dz != 0;
                    if (diag && (!walkable.test(c.offset(dx, 0, 0)) || !walkable.test(c.offset(0, 0, dz)))) continue;
                    double g = cost.get(c) + (diag ? diagonal : straight);
                    if (g >= cost.getOrDefault(n, Double.MAX_VALUE)) continue;
                    cost.put(n, g);
                    parent.put(n, c);
                    open.add(new Open(n, g + goal.heuristic(n.x(), n.y(), n.z())));
                }
        }
        return null;
    }

    @Test
    void aCornerCutNamesBothEndsAndThePlanAroundItIsLegal() {
        NoGoZones z = turnaround();
        GoalNearXZ drop = new GoalNearXZ(-3, 0, 0, 0, 1);
        Pos start = new Pos(0, 0, 20);
        // the zone's cells are air: A* may walk past (and into) them, only the heuristic keeps it out
        List<Pos> first = plan(start, z.avoiding(drop), NoGoZonesTest::floor);
        assertNotNull(first);
        NoGoZones.Violation v = z.violation(first, null);
        assertNotNull(v, "the shortest plan cuts the zone's corner: " + first);
        assertTrue(v.cornerCut(), v.toString());
        assertEquals(new Pos(0, 0, 2), v.from());
        assertEquals(new Pos(-1, 0, 1), v.to());
        assertTrue(v.text().startsWith("enters no-go zone #1 \"turnaround station west\" (-8,-1,2 to -1,4,129) at -1,0,1"),
            v.text());
        assertEquals(v.text(), z.pathViolation(first, null), "the walker's text is unchanged");

        // the walker's fix: the move's end looks far, the next plan goes round the corner and still ends at the drop
        Set<Pos> avoided = new LinkedHashSet<>();
        NoGoZones.CornerFix fix = z.cornerFix(v, avoided, false, c -> c.equals(start) || drop.isInGoal(c.x(), c.y(), c.z()));
        assertNotNull(fix);
        assertEquals(new Pos(-1, 0, 1), fix.avoid());
        assertNull(fix.via());
        avoided.add(fix.avoid());
        Goal around = z.avoiding(drop, avoided);
        assertTrue(around.heuristic(-1, 0, 1) >= NoGoZones.PENALTY);
        assertTrue(around.isInGoal(-3, 0, 0), "a detour cell never takes a goal cell away");
        assertNotEquals(z.avoiding(drop), around, "a new goal for Baritone (isGoalActive compares with equals)");
        assertEquals(z.avoiding(drop, Set.of(new Pos(-1, 0, 1))), around);
        List<Pos> second = plan(start, around, NoGoZonesTest::floor);
        assertNotNull(second);
        assertNull(z.pathViolation(second, null), "planned around the corner: " + second);
        assertEquals(new Pos(-3, 0, 0), second.getLast());
        assertFalse(second.contains(new Pos(-1, 0, 1)), second.toString());
    }

    /**
     * Live 2026-10-13 06:59 (lane 0 BLOCKED), relative: from the glass cell just north of the zone's corner, (-1,0,1),
     * Baritone planned the flat diagonal into the platform's edge cell (0,0,2), the lane start itself. Neither end may
     * look far (the bot stands on one, the goal is the other): the walk goes through the step's free side cell (0,0,1)
     * first, two legal cardinal steps.
     */
    @Test
    void aCutBetweenTheFeetAndTheGoalGoesThroughTheFreeSideCell() {
        NoGoZones z = turnaround();
        Pos start = new Pos(-1, 0, 1);
        GoalNearXZ laneStart = new GoalNearXZ(0, 0, 2, 0, 2);
        List<Pos> first = plan(start, z.avoiding(laneStart), NoGoZonesTest::floor);
        assertEquals(List.of(start, new Pos(0, 0, 2)), first, "the shortest plan is the diagonal");
        NoGoZones.Violation v = z.violation(first, null);
        assertNotNull(v);
        assertTrue(v.cornerCut(), v.text());
        assertTrue(v.text().endsWith("at 0,0,2"), v.text());
        java.util.function.Predicate<Pos> keep = c -> c.equals(start) || laneStart.isInGoal(c.x(), c.y(), c.z());
        NoGoZones.CornerFix fix = z.cornerFix(v, Set.of(), false, keep);
        assertNotNull(fix);
        assertNull(fix.avoid(), "neither the feet nor the goal may look far");
        assertEquals(new GoalNearXZ(0, 0, 1, 0, 1), fix.via());
        // through the side cell, then on: both legs legal
        List<Pos> leg1 = plan(start, z.avoiding(fix.via()), NoGoZonesTest::floor);
        assertNull(z.pathViolation(leg1, null), leg1.toString());
        List<Pos> leg2 = plan(leg1.getLast(), z.avoiding(laneStart), NoGoZonesTest::floor);
        assertNull(z.pathViolation(leg2, null), leg2.toString());
        assertEquals(new Pos(0, 0, 2), leg2.getLast());
        // once only: tried, and nothing else may look far, the walk fails as before
        assertNull(z.cornerFix(v, Set.of(), true, keep));
    }

    @Test
    void aFixNeverTakesTheGoalOrTheFeetAndGivesUpWhenEverythingWasTried() {
        NoGoZones z = turnaround();
        NoGoZones.Violation v = z.violation(List.of(new Pos(0, 0, 3), new Pos(0, 0, 2), new Pos(-1, 0, 1)), null);
        assertNotNull(v);
        assertTrue(v.cornerCut());
        Set<Pos> none = Set.of();
        // the move's end is free to look far: that first
        assertEquals(new Pos(-1, 0, 1), z.cornerFix(v, none, false, c -> false).avoid());
        // the end is the goal: through the free side cell (0,0,1) instead
        assertEquals(new GoalNearXZ(0, 0, 1, 0, 1), z.cornerFix(v, none, false, c -> c.equals(new Pos(-1, 0, 1))).via());
        // the end tried already and the side cell too: the start looks far
        assertEquals(new Pos(0, 0, 2), z.cornerFix(v, Set.of(new Pos(-1, 0, 1)), true, c -> false).avoid());
        // all tried, or the start is where the bot stands: nothing left
        assertNull(z.cornerFix(v, Set.of(new Pos(-1, 0, 1), new Pos(0, 0, 2)), true, c -> false));
        assertNull(z.cornerFix(v, Set.of(new Pos(-1, 0, 1)), true, c -> c.equals(new Pos(0, 0, 2))));

        // a path that walks into the zone, or one over a drop limit: no corner cut, no fix
        NoGoZones.Violation into = z.violation(List.of(new Pos(0, 0, 3), new Pos(-1, 0, 3)), null);
        assertNotNull(into);
        assertFalse(into.cornerCut());
        assertNull(z.cornerFix(into, none, false, c -> false));
        NoGoZones.Violation fall = new NoGoZones(List.of(), 3).violation(List.of(new Pos(0, 10, 0), new Pos(1, 0, 0)), null);
        assertNotNull(fall);
        assertFalse(fall.cornerCut());
        // straight along the zone's edge is legal
        assertNull(z.violation(List.of(new Pos(0, 0, 3), new Pos(0, 0, 2), new Pos(0, 0, 1)), null));
        // a step up and across the corner (the platform at y -1, the walkway at y 0): the side cell at the higher end
        NoGoZones.Violation up = z.violation(List.of(new Pos(0, -1, 2), new Pos(-1, 0, 1)), null);
        assertNotNull(up);
        assertTrue(up.cornerCut(), up.text());
        assertEquals(new GoalNearXZ(0, 0, 1, 0, 2), z.cornerFix(up, none, false, c -> c.equals(new Pos(-1, 0, 1))).via());
        // both side cells in the zone: no way through
        NoGoZones wide = NoGoZones.of(List.of(box("a", new Pos(-8, -1, 2), new Pos(-1, 4, 9)),
            box("b", new Pos(0, -1, -9), new Pos(8, 4, 1))));
        NoGoZones.Violation both = wide.violation(List.of(new Pos(0, 0, 2), new Pos(-1, 0, 1)), null);
        assertNotNull(both);
        assertNull(wide.freeSide(new Pos(0, 0, 2), new Pos(-1, 0, 1)));
    }

    /** Live 2026-10-13: leaving a stand cell beside the zone, the feet clipped the zone cell for one tick. */
    @Test
    void theFeetCheckWantsTheCentreInsideNotACornerClipped() {
        NoGoZones z = turnaround(); // x -8..-1 (blocks -8.0 to 0.0), z 2..129 (2.0 to 130.0), y -1..4
        double m = NoGoZones.FEET_MARGIN;
        assertNull(z.deepAt(0.5, 0, 1.5, m), "the stand cell itself");
        assertNull(z.deepAt(-0.1, 0, 2.1, m), "the corner clipped by a tenth");
        assertNull(z.deepAt(-0.5, 0, 2.25, m), "a quarter block over the zone's north edge");
        assertNotNull(z.deepAt(-0.5, 0, 2.35, m), "a third of a block in: walking in");
        assertNotNull(z.deepAt(-4.0, 0, 60.0, m), "deep inside");
        assertNotNull(z.deepAt(-4.0, -2, 60.0, m), "head in it (feet one below)");
        assertNull(z.deepAt(-4.0, 5, 60.0, m), "standing on its top");
        assertNull(z.deepAt(-4.0, -2.9, 60.0, m), "head below it");
        assertNotNull(z.at(new Pos(-1, 0, 2)), "the cell test (paths, stand spots) still counts the whole cell");
    }

    // ------------------------------------------------------------------------------------------ stand spots

    @Test
    void containerStandSpotsAreNeverInAZone() {
        ContainerSim s = new ContainerSim(4);
        s.fill(-6, 63, -6, 6, 63, 6, STONE);
        Pos c = new Pos(0, 64, 0);
        s.set(c, chest("west", "single"));
        s.targets = List.of(c);
        double[] bot = {-5.5, 64, 0.5};
        StandSpots.Search free = StandSpots.search(s, s, s.targets, 4.3, bot[0], bot[1], bot[2], null, Set.of(), 4);
        Pos best = free.spots().getFirst().cell();
        assertTrue(best.x() < 0, "west of the chest, towards the bot: " + best);

        // a zone over the whole west side, as ApproachIo.allowed applies it
        NoGoZones z = NoGoZones.of(List.of(box("west", new Pos(-6, 60, -6), new Pos(-1, 70, 6))));
        s.allowedCells = new StandSpots.CellRule(p -> z.at(p) == null, "in a no-go zone");
        StandSpots.Search r = StandSpots.search(s, s, s.targets, 4.3, bot[0], bot[1], bot[2], null, Set.of(), 4);
        assertFalse(r.spots().isEmpty(), r.describe(4.3));
        for (StandSpots.Spot sp : r.spots()) assertNull(z.at(sp.cell()), "stand spot in the zone: " + sp);
        assertTrue(r.spots().getFirst().cell().x() >= 0, r.spots().getFirst().toString());
    }

    @Test
    void outputColumnSpotsSkipAZoneOverTheBestSpot() {
        ContainerSim s = new ContainerSim(1);
        Pos out = new Pos(10, 65, 20);
        double[] bot = ContainerSites.outputColumn(s, out);
        Pos usual = StandSpots.search(s, s, s.targets, 4.3, bot[0], bot[1], bot[2], null, Set.of(), 4)
            .spots().getFirst().cell();
        NoGoZones z = NoGoZones.of(List.of(box("spot", usual, usual)));
        s.allowedCells = new StandSpots.CellRule(p -> z.at(p) == null, "in a no-go zone");
        StandSpots.Search r = StandSpots.search(s, s, s.targets, 4.3, bot[0], bot[1], bot[2], null, Set.of(), 4);
        assertFalse(r.spots().isEmpty(), r.describe(4.3));
        assertFalse(r.spots().stream().anyMatch(sp -> sp.cell().equals(usual)), "the zone cell was chosen");
        for (StandSpots.Spot sp : r.spots()) assertNull(z.at(sp.cell()), sp.toString());
    }
}
