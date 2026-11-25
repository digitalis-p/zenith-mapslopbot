package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.movement.ActionCosts;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.LaneSim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane starts on the soul sand tile's south row ({@code build.laneStartOnFloor}).
 * Live 2026-10-24 07:50, j0016 [2,0] lane 5 (and lanes 7 and 8; 39 times on 10-23), locally in every run
 * (lane 7 of j0015 [3,1], tile-relative start 67,173,127): the walk to the start "reached
 * GoalNearXZ[x=67, y=173, z=127, range=0, yTolerance=2] but the arrival test still fails". The local debug log shows the
 * path from the previous lane's end (58,173,125) up onto the glass wall two blocks high south of the tile (z 128, top at
 * y 175), along its top to x 67 ("Pathing complete" at 67,175,128 for the range-1 goal) and, for the range-0 goal, one
 * step north: the feet cell 67,175,127 is in the goal (2 above the art layer), the box still on the wall's edge. The
 * lane's arrival test wants the feet within 1.5 of the art layer. The break walk's goal took the same cell as arrived,
 * the walker kept the failed goal list, and the lane run started again failed at once ("no walking goal left"): the
 * whole lane from stand spots.
 *
 * <p>Tile-relative coordinates, the art layer at feet y 0 here: the tile 0..127, the wall row z 128.
 */
class LaneStartFloorTest {
    // ------------------------------------------------------------------------------------------------ border tops

    private static final BorderTop TILE = BorderTop.of(new LaneRun.Area(0, 0, 127, 127), 0);

    @Test
    void borderTopsAreTheRingAroundTheTileTwoAndMoreAboveTheArtLayer() {
        assertTrue(TILE.contains(67, 2, 128), "the wall's top south of the last row");
        assertTrue(TILE.contains(67, 2, -1));
        assertTrue(TILE.contains(-1, 3, 40));
        assertTrue(TILE.contains(128, 2, 128), "the ring's corner");
        assertFalse(TILE.contains(67, 1, 128), "one up: a walkway at the art layer + 1 stays usable");
        assertFalse(TILE.contains(67, 0, -1), "the north walkway at the art layer");
        assertFalse(TILE.contains(67, 2, 127), "inside the tile: on a stack, never a border");
        assertFalse(TILE.contains(67, 2, 129), "two cells out");
        assertFalse(TILE.contains(-2, 2, 40));
    }

    @Test
    void theGoalKeepsBorderTopsOutAndMakesThemLookFar() {
        GoalNearXZ inner = new GoalNearXZ(67, 0, 127, 1, 2);
        assertSame(inner, NoGoZones.NONE.avoiding(inner, Set.of(), null), "switch off: Baritone gets the goal itself");
        Goal g = NoGoZones.NONE.avoiding(inner, Set.of(), TILE);
        assertTrue(inner.isInGoal(67, 2, 128));
        assertFalse(g.isInGoal(67, 2, 128), "never arrived on the wall");
        assertTrue(g.isInGoal(67, 0, 127));
        assertTrue(g.isInGoal(66, 0, 127));
        assertTrue(g.heuristic(60, 2, 128) >= NoGoZones.PENALTY);
        assertEquals(inner.heuristic(60, 0, 127), g.heuristic(60, 0, 127), 1e-9);
        assertEquals(NoGoZones.NONE.avoiding(inner, Set.of(), BorderTop.of(new LaneRun.Area(0, 0, 127, 127), 0)), g,
            "Baritone compares goals with equals");
        assertTrue(g.toString().endsWith("and the tile's border tops)"), g.toString());
    }

    // ------------------------------------------------------------------- Baritone's walk to the start, in miniature

    /** The previous lane's end (lane 6 walked south). */
    private static final Pos LANE6_END = new Pos(58, 0, 125);
    /** Lane 7's start (walked north). */
    private static final int SX = 67;
    private static final int SZ = 127;

    /**
     * Feet height of a column, or null: the tile's soul sand floor (0), a full block placed on the art layer (1: lane
     * 6's cobbled deepslate in its last row, the step up), the glass wall south of the tile (2); nothing else stands.
     */
    private static @Nullable Integer top(int x, int z) {
        if (x >= 40 && x <= 90 && z >= 100 && z <= 127) return x == 59 && z == 127 ? 1 : 0;
        if (z == 128 && x >= 40 && x <= 90) return 2;
        return null;
    }

    private static boolean onSoulSand(Pos p) {
        return p.y() == 0 && p.z() <= 127;
    }

    /**
     * One move's cost, after Baritone's movements (ActionCosts): a walk over soul sand costs half of
     * WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST more for each end on it, a step up a jump and the jump penalty
     * (2), a step down walking off and the fall.
     */
    private static double cost(Pos a, Pos b) {
        double soul = (ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST - ActionCosts.WALK_ONE_BLOCK_COST) / 2;
        double extra = (onSoulSand(a) ? soul : 0) + (onSoulSand(b) ? soul : 0);
        if (b.y() == a.y()) return ActionCosts.WALK_ONE_BLOCK_COST + extra;
        if (b.y() == a.y() + 1) return Math.max(ActionCosts.JUMP_ONE_BLOCK_COST, ActionCosts.WALK_ONE_BLOCK_COST) + 2 + extra;
        int n = a.y() - b.y();
        return ActionCosts.WALK_OFF_BLOCK_COST
            + Math.max(ActionCosts.FALL_N_BLOCKS_COST[n], ActionCosts.CENTER_AFTER_FALL_COST);
    }

    /** A* as Baritone's: 4 moves, open set by cost + the goal's heuristic, the goal tested when a cell is taken. */
    private static @Nullable List<Pos> plan(Pos start, Goal goal) {
        Map<Pos, Double> g = new HashMap<>();
        Map<Pos, Pos> parent = new HashMap<>();
        record Open(Pos cell, double f) { }
        PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::f));
        g.put(start, 0.0);
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
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Integer h = top(c.x() + d[0], c.z() + d[1]);
                if (h == null || h > c.y() + 1 || c.y() - h > 3) continue;
                Pos n = new Pos(c.x() + d[0], h, c.z() + d[1]);
                double cost = g.get(c) + cost(c, n);
                if (cost >= g.getOrDefault(n, Double.MAX_VALUE)) continue;
                g.put(n, cost);
                parent.put(n, c);
                open.add(new Open(n, cost + goal.heuristic(n.x(), n.y(), n.z())));
            }
        }
        return null;
    }

    /** Where the walk ended: the feet cell, its position, the cell it stands on, and the cells planned for the leg. */
    private record Stop(Pos feet, double x, double y, double z, Pos standsOn, List<Pos> path) { }

    /**
     * Walks a planned path the way Baritone's CustomGoalProcess ends it: at the first tick whose feet cell is in the goal.
     * A step down passes through the next column at the old height first (the box still on the edge), a step up rises in
     * its own column first. The position is where the feet cell was entered (0.05 into it), or the cell's centre.
     */
    private static Stop walk(List<Pos> path, Goal goal) {
        Pos first = path.getFirst();
        if (goal.isInGoal(first.x(), first.y(), first.z())) return new Stop(first, first.x() + 0.5, first.y(), first.z() + 0.5, first, path);
        for (int i = 1; i < path.size(); i++) {
            Pos a = path.get(i - 1);
            Pos b = path.get(i);
            int dx = b.x() - a.x();
            int dz = b.z() - a.z();
            double ex = dx > 0 ? b.x() + 0.05 : dx < 0 ? b.x() + 0.95 : b.x() + 0.5;
            double ez = dz > 0 ? b.z() + 0.05 : dz < 0 ? b.z() + 0.95 : b.z() + 0.5;
            if (b.y() < a.y()) {
                Pos edge = new Pos(b.x(), a.y(), b.z());
                if (goal.isInGoal(edge.x(), edge.y(), edge.z())) return new Stop(edge, ex, a.y(), ez, a, path);
            }
            if (b.y() > a.y()) {
                Pos up = new Pos(a.x(), b.y(), a.z());
                if (goal.isInGoal(up.x(), up.y(), up.z())) return new Stop(up, a.x() + 0.5, b.y(), a.z() + 0.5, a, path);
            }
            if (goal.isInGoal(b.x(), b.y(), b.z())) return new Stop(b, ex, b.y(), ez, b, path);
        }
        Pos last = path.getLast();
        return new Stop(last, last.x() + 0.5, last.y(), last.z() + 0.5, last, path);
    }

    /** {@link LaneRun}'s nearStart for lane 7 (north from row 127, line x 67.5): the art layer at feet y 0. */
    private static boolean nearStart(Stop s) {
        double along = (SZ + 0.5) - s.z();
        double lateral = s.x() - (SX + 0.5);
        return Math.abs(along) <= 1.2 && Math.abs(lateral) <= 0.9 && Math.abs(s.y()) <= 1.5;
    }

    /** The lane start's walk: its two goals in turn until the arrival test holds; the stops, the last one arrived or not. */
    private static List<Stop> startWalk(int yTolerance, @Nullable BorderTop border) {
        List<Stop> stops = new ArrayList<>();
        Pos from = LANE6_END;
        for (int range : new int[]{1, 0}) {
            Goal goal = NoGoZones.NONE.avoiding(new GoalNearXZ(SX, 0, SZ, range, yTolerance), Set.of(), border);
            List<Pos> path = plan(from, goal);
            assertNotNull(path, "a path for range " + range);
            Stop s = walk(path, goal);
            stops.add(s);
            if (nearStart(s)) break;
            from = s.standsOn();
        }
        return stops;
    }

    private static int highest(List<Stop> stops) {
        int y = Integer.MIN_VALUE;
        for (Stop s : stops) for (Pos p : s.path()) y = Math.max(y, p.y());
        return y;
    }

    @Test
    void theOldGoalsEndOnTheWallsEdgeAboveTheStartAndTheArrivalTestFails() {
        List<Stop> stops = startWalk(2, null);
        assertEquals(2, stops.size(), stops.toString());
        // range 1: over the wall's top ("Pathing complete" at 67,175,128), cheaper than 9 blocks of soul sand
        assertEquals(new Pos(SX, 2, SZ + 1), stops.get(0).feet(), stops.toString());
        assertTrue(stops.get(0).path().contains(new Pos(60, 2, 128)), "the live edge stop at 60,175,128");
        // range 0: one step north off the wall's edge: the feet cell 67,175,127 is in the goal, the box on the wall
        assertEquals(new Pos(SX, 2, SZ), stops.get(1).feet());
        assertEquals(new Pos(SX, 2, SZ + 1), stops.get(1).standsOn());
        assertFalse(nearStart(stops.get(1)), "2.0 above the art layer: the lane's arrival test fails");
    }

    @Test
    void theNewGoalsStayOnTheTileAndArrive() {
        List<Stop> stops = startWalk(1, TILE);
        Stop last = stops.getLast();
        assertTrue(nearStart(last), stops.toString());
        assertEquals(0, last.feet().y());
        assertTrue(highest(stops) <= 1, "never on the wall: " + stops);
        for (Stop s : stops) for (Pos p : s.path()) assertTrue(p.z() <= 127, "off the tile: " + p);
    }

    @Test
    void theNarrowerToleranceAloneStillWalksTheWallAndDropsTwoBlocks() {
        // why the border tops look far: with the tolerance alone the walk arrives, but along the wall's 1-wide top
        // beside the drop south of it, and with a 2-block drop into the start cell at the end
        List<Stop> stops = startWalk(1, null);
        assertTrue(nearStart(stops.getLast()), stops.toString());
        assertEquals(2, highest(stops), stops.toString());
    }

    // ------------------------------------------------------------------------- the lane run on the simulated tile

    private static final int MIN_X = -64;
    private static final int MIN_Z = -64;
    private static final int ART_Y = 64;
    /** Lane 7's walking line and first (south) row on the simulated tile, and its last row. */
    private static final int LX = 3;
    private static final int Z0 = 63;
    private static final int Z1 = 24;
    private static final String CARPET = "minecraft:gray_carpet";
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    private final Map<Pos, BlockSpec> world = new HashMap<>();
    private final LaneSim sim = new LaneSim(new LaneSim.Blocks() {
        @Override
        public BlockSpec get(Pos p) {
            return world.getOrDefault(p, BlockSpec.AIR);
        }

        @Override
        public void set(Pos p, BlockSpec b) {
            world.put(p, b);
        }
    }, new LaneSim.Items() {
        @Override
        public int count(String item) {
            return 10_000;
        }

        @Override
        public boolean take(String item) {
            return true;
        }
    }, MIN_X, MIN_Z, ART_Y, new AdaptivePacer(2, 1, 8), 11);
    private final List<GoalNearXZ> asked = new ArrayList<>();
    private LaneRun<LaneSim.Shot> run;

    private static Pos cell(int x, int z) {
        return new Pos(x, ART_Y, z);
    }

    /** A 9-wide lane northward from the tile's last row (lane 7's shape). */
    private static Lane southEdgeLane() {
        List<Placement> ps = new ArrayList<>();
        for (int z = Z0; z >= Z1; z--) {
            for (int x = LX - 4; x <= LX + 4; x++) ps.add(new Placement(cell(x, z), BlockSpec.of(CARPET), CARPET));
        }
        return new Lane(7, cell(LX, Z0), cell(LX, Z1), List.copyOf(ps));
    }

    private TaskStatus run(Lane lane, boolean onFloor) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        sim.silent = PlaceMode.silentRotation(b);
        sim.startOnFloor = onFloor;
        // the pathfinder as live and as the model above: it walks the wall's top and stops once the feet cell is in
        // the goal, there on the wall (range 1: its top at the start's column) or on its edge (range 0)
        sim.legEnd = g -> {
            asked.add(g);
            if (g.isInGoal(LX, ART_Y + 2, Z0 + 1)) return new double[]{LX + 0.5, ART_Y + 2, Z0 + 1.5};
            if (g.isInGoal(LX, ART_Y + 2, Z0)) return new double[]{LX + 0.5, ART_Y + 2, Z0 + 0.9};
            return null;
        };
        sim.teleport(LX - 9 + 0.5, ART_Y, Z0 - 1.5); // the previous lane's end
        return sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 8000, (t, r) -> {
            run = r;
            return true;
        });
    }

    private int missing(Lane lane) {
        int n = 0;
        for (Placement p : lane.placements()) {
            if (!TopFaceRules.equivalent(p.block(), world.getOrDefault(p.pos(), BlockSpec.AIR))) n++;
        }
        return n;
    }

    @Test
    void switchOffTheRunFailsAtTheSouthEdgeStartAsLive() {
        Lane lane = southEdgeLane();
        TaskStatus s = run(lane, false);
        String log = String.join("\n", sim.log);
        assertEquals(TaskStatus.FAILED, s, log);
        assertEquals(lane.from(), run.unreachedStart(), log);
        assertTrue(run.failure().startsWith("cannot reach lane start"), run.failure());
        assertEquals(List.of(2, 2), asked.stream().map(GoalNearXZ::yTolerance).toList(), "the goals as before");
        assertEquals(List.of(1, 0), asked.stream().map(GoalNearXZ::range).toList());
        assertEquals(lane.placements().size(), missing(lane), "nothing placed");
    }

    @Test
    void switchOnTheRunStartsOnTheFloorAndPlacesTheLaneByWalking() {
        Lane lane = southEdgeLane();
        TaskStatus s = run(lane, true);
        String log = String.join("\n", sim.log);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertEquals(0, missing(lane), log);
        assertTrue(asked.stream().allMatch(g -> g.yTolerance() == 1), asked.toString());
        assertEquals(0, run.spotWalks(), log);
        assertFalse(log.contains("from stand spots"), log);
        assertTrue(sim.maxFeetY <= ART_Y + 1 + 1e-9, "feet at most on a full block: " + sim.maxFeetY);
    }
}
