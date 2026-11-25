package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VERIFY break-in ({@code build.verifyBreakIn}, LaneRun class javadoc "Break-in") on the live incident (2026-10-13
 * 01:03, ellen [1,6] on moss, fixtures/art/ellen_1_6.json): the pointed-dripstone cell at column 81, row 55 was the one
 * hole in a field of placed dripstone (columns 36-95, rows 39-61); VERIFY's repair lane 9 picked stand spots on top of
 * the dripstone around it, the pathfinder reached none ("pathing ... failed 4 times"), the cell was skipped on 4 passes
 * and the tile BLOCKED. The bot stood at column 81, row 48 (a crimson-roots cell north of the field). On the simulated
 * platform ({@code LaneSim.wallIds}: Baritone neither walks through nor onto dripstone and pots; their outline is a
 * full cube for line of sight, stricter than the game's thin column).
 */
class VerifyBreakInTest {
    static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement placement) { }
        @Override public void skipped(Placement placement, String reason) { }
    };

    /** The tile as VERIFY found it: everything placed but the hole; the bot where it stood. */
    static TileRunner verifyState(TileArt art, boolean breakIn, boolean keepReachable) {
        TileRunner r = LaneReentryTest.runner(5, true);
        r.finiteStock = false;
        r.sim.wallIds = LaneWayOutTest.WALLS;
        r.build.verifyBreakIn = breakIn;
        r.build.laneKeepReachable = keepReachable;
        for (int col = 0; col < TileArt.SIZE; col++) {
            for (int row = 0; row < TileArt.SIZE; row++) {
                BlockSpec b = art.get(col, row);
                if (b != null && !(col == 81 && row == 55)) r.world.put(KeepReachableTest.cell(r, col, row), b);
            }
        }
        r.sim.art = c -> {
            int col = c.x() - r.geo.minX();
            int row = c.z() - r.geo.minZ();
            if (c.y() != r.geo.artY() || col < 0 || row < 0 || col >= TileArt.SIZE || row >= TileArt.SIZE) return null;
            BlockSpec b = art.get(col, row);
            return b == null ? null : new Placement(c, b, b.id());
        };
        r.sim.teleport(r.geo.minX() + 81.5, r.geo.artY(), r.geo.minZ() + 48.5);
        return r;
    }

    record Run(TaskStatus status, List<String> log, List<Pos> accepted) { }

    static Run repair(TileRunner r, TileArt art, boolean breakIn) {
        List<Lane> lanes = r.plan(art);
        assertEquals(1, lanes.size(), "one repair lane");
        Lane l = lanes.getFirst();
        assertEquals(1, l.placements().size());
        List<Pos> accepted = new ArrayList<>();
        r.sim.onAccept = p -> accepted.add(p.pos());
        int from = r.sim.log.size();
        TaskStatus st = r.sim.runLane(l, r.params().withBreakIn(breakIn), NONE, 20_000);
        List<String> log = new ArrayList<>(r.sim.log.subList(from, r.sim.log.size()));
        for (String s : log) System.out.println("  " + s);
        return new Run(st, log, accepted);
    }

    static Pos cell(TileRunner r, int col, int row) {
        return KeepReachableTest.cell(r, col, row);
    }

    /** The bot can walk out of the field: to the tile's south-east, beyond it (column 120, row 120). */
    static boolean out(TileRunner r) {
        return r.sim.reachable(r.geo.minX() + 120, r.geo.minZ() + 120);
    }

    @Test
    void withoutTheBreakInTheHoleIsSkippedAsLive() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        TileRunner r = verifyState(art, false, true);
        Run run = repair(r, art, false);
        assertTrue(String.join("\n", run.log()).contains("skipped [17,64,-9] (pointed_dripstone): not reachable"),
            "skipped as live");
        assertTrue(run.accepted().isEmpty());
        assertTrue(r.sim.brokenWalls.isEmpty());
        assertFalse(r.matches(art));
    }

    @Test
    void theHoleIsPlacedFromACorridorThatClosesBehindTheBot() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        for (boolean keep : new boolean[]{true, false}) {
            TileRunner r = verifyState(art, true, keep);
            Run run = repair(r, art, true);
            String log = String.join("\n", run.log());
            System.out.println("keepReachable " + keep + ": broke " + r.sim.brokenWalls + ", placed " + run.accepted()
                + ", bot at " + (r.sim.x - r.geo.minX()) + "," + (r.sim.z - r.geo.minZ()));
            assertEquals(TaskStatus.SUCCESS, run.status(), log);
            assertTrue(log.contains("break-in for [17,64,-9] (pointed_dripstone"), log);
            List<Pos> broken = r.sim.brokenWalls;
            assertFalse(broken.isEmpty(), "a corridor was broken");
            assertTrue(broken.size() <= LaneRun.BREAK_IN_WALLS, "at most " + LaneRun.BREAK_IN_WALLS + " broken: " + broken);
            // the hole first, then every broken block again
            assertEquals(cell(r, 81, 55), run.accepted().getFirst(), "the hole first: " + run.accepted());
            assertEquals(broken.size() + 1, run.accepted().size(), "the hole and the corridor: " + run.accepted());
            assertTrue(run.accepted().containsAll(broken));
            assertTrue(r.matches(art), "the tile matches: " + r.mismatches(art));
            assertTrue(out(r), "the bot is not walled in");
        }
    }

    /** From the walkway north of the tile (the bot back from the chests): the way starts at the platform's edge. */
    @Test
    void fromTheWalkway() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        TileRunner r = verifyState(art, true, true);
        r.sim.teleport(r.geo.minX() + 81.5, r.geo.artY(), r.geo.minZ() - 2.5);
        Run run = repair(r, art, true);
        assertEquals(TaskStatus.SUCCESS, run.status(), String.join("\n", run.log()));
        assertEquals(cell(r, 81, 55), run.accepted().getFirst(), "the hole first: " + run.accepted());
        assertTrue(r.matches(art), "the tile matches: " + r.mismatches(art));
        assertTrue(out(r), "the bot is not walled in");
    }

    @Test
    void theCorridorGoesUpAgainFarEndFirst() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        TileRunner r = verifyState(art, true, true);
        // the plan as the run makes it, from where the bot stands
        Run run = repair(r, art, true);
        List<Pos> broken = r.sim.brokenWalls;
        List<Pos> again = run.accepted().subList(1, run.accepted().size());
        // broken from the bot's side inwards, placed again from the far end outwards: the reverse order
        List<Pos> reversed = new ArrayList<>(broken);
        java.util.Collections.reverse(reversed);
        // sight blocks (next to the target) are broken last and placed first, so the reverse holds for all of them
        assertEquals(reversed, again, "far end first: broken " + broken + ", placed " + again);
    }

    /**
     * The plan on the live art from where the bot stood: three dripstone on the sight line from the brown carpet south
     * of the hole (column 82, row 58), none for fewer; the far end goes up first.
     */
    @Test
    void planOnTheLiveArt() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        TileRunner r = verifyState(art, true, true);
        BreakIn.Grid g = new BreakIn.Grid() {
            BlockSpec b(Pos c) {
                return r.world.getOrDefault(c, BlockSpec.AIR);
            }

            boolean in(Pos c) {
                int x = c.x() - r.geo.minX();
                int z = c.z() - r.geo.minZ();
                return x >= 0 && z >= 0 && x < TileArt.SIZE && z < TileArt.SIZE;
            }

            @Override public boolean passable(Pos c) { return in(c) && !LaneWayOutTest.WALLS.contains(b(c).id()); }
            @Override public boolean floor(Pos c) { return passable(c) && io.github.digitalisp.mapart.testutil.LaneSim.collisionTopOf(b(c)) <= 0.6; }
            @Override public boolean breakable(Pos c) { return in(c) && LaneWayOutTest.WALLS.contains(b(c).id()); }
            @Override public double outlineTop(Pos c) { return r.sim.outlineTop(b(c)); }
            @Override public boolean standAllowed(Pos c) { return true; }
        };
        BreakIn.Target t = new BreakIn.Target(cell(r, 81, 55), false, true);
        java.util.function.Function<Pos, BreakIn.Target> again = c -> new BreakIn.Target(c, false, true);
        BreakIn.Reach reach = new BreakIn.Reach(4.25, 4.75);
        assertEquals(null, BreakIn.plan(g, cell(r, 81, 48), t, reach, 2, LaneRun.BREAK_IN_MARGIN, again));
        BreakIn.Plan p = BreakIn.plan(g, cell(r, 81, 48), t, reach, LaneRun.BREAK_IN_WALLS, LaneRun.BREAK_IN_MARGIN, again);
        assertNotNull(p);
        assertEquals(cell(r, 82, 58), p.stand());
        assertEquals(List.of(cell(r, 82, 57), cell(r, 81, 57), cell(r, 81, 56)), p.walls());
        assertEquals(List.of(cell(r, 81, 56), cell(r, 81, 57), cell(r, 82, 57)), p.replace());
        // the bot's own cell comes first, the stand cell last
        assertEquals(cell(r, 81, 48), p.path().getFirst());
        assertEquals(p.stand(), p.path().getLast());
    }

    @Test
    void plannerOnASyntheticField() {
        // a 15 x 15 field of walls with one hole in the middle; open floor around it; the bot west of the field
        int size = 25;
        boolean[][] wall = new boolean[size][size];
        for (int x = 5; x < 20; x++) for (int z = 5; z < 20; z++) wall[x][z] = true;
        Pos hole = new Pos(12, 64, 12);
        wall[12][12] = false;
        BreakIn.Grid g = grid(wall, size, true);
        BreakIn.Target t = new BreakIn.Target(hole, false, true);
        BreakIn.Plan p = BreakIn.plan(g, new Pos(2, 64, 12), t, new BreakIn.Reach(4.25, 4.75), 12, 16,
            c -> new BreakIn.Target(c, false, true));
        assertNotNull(p);
        System.out.println("synthetic: " + p);
        // 7 deep: no stand cell within reach and at most 4 broken blocks
        assertEquals(null, BreakIn.plan(g, new Pos(2, 64, 12), t, new BreakIn.Reach(4.25, 4.75), 4, 16,
            c -> new BreakIn.Target(c, false, true)));
        // blocks that may not be broken: no plan
        assertEquals(null, BreakIn.plan(grid(wall, size, false), new Pos(2, 64, 12), t,
            new BreakIn.Reach(4.25, 4.75), 12, 16, c -> new BreakIn.Target(c, false, true)));
        // the far end goes up first: the reverse of the walk in, the sight blocks before the route's
        assertEquals(p.walls().size(), p.replace().size());
        assertEquals(p.walls().getLast(), p.replace().getFirst());
        // a hole 3 deep (a field edge at x 9): 1 to 4 blocks, the stand cell in reach
        boolean[][] w2 = new boolean[size][size];
        for (int x = 9; x < 20; x++) for (int z = 5; z < 20; z++) w2[x][z] = true;
        w2[12][12] = false;
        BreakIn.Plan p2 = BreakIn.plan(grid(w2, size, true), new Pos(2, 64, 12), t, new BreakIn.Reach(4.25, 4.75),
            4, 16, c -> new BreakIn.Target(c, false, true));
        assertNotNull(p2);
        System.out.println("3 deep: " + p2);
        assertTrue(p2.walls().size() >= 1 && p2.walls().size() <= 4, p2.toString());
        assertTrue(Math.max(Math.abs(p2.stand().x() - 12), Math.abs(p2.stand().z() - 12)) >= 2, "not next to it");
    }

    /** Walls 11/16 high (dripstone), breakable or not; floor elsewhere; the area 0..size-1. */
    static BreakIn.Grid grid(boolean[][] wall, int size, boolean breakable) {
        return new BreakIn.Grid() {
            boolean in(Pos c) {
                return c.x() >= 0 && c.z() >= 0 && c.x() < size && c.z() < size;
            }

            @Override public boolean passable(Pos c) { return in(c) && !wall[c.x()][c.z()]; }
            @Override public boolean floor(Pos c) { return passable(c); }
            @Override public boolean breakable(Pos c) { return breakable && in(c) && wall[c.x()][c.z()]; }
            @Override public double outlineTop(Pos c) { return in(c) && wall[c.x()][c.z()] ? 11.0 / 16 : 0; }
            @Override public boolean standAllowed(Pos c) { return in(c); }
        };
    }
}
