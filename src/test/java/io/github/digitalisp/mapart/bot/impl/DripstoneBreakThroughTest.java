package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DripstoneBreakThroughTest {
    @Test
    void failedStandSpotOpensDenseDripstoneAndRestoresTheTile() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        art.set(81, 56, null);
        art.set(81, 57, null);
        TileRunner r = VerifyBreakInTest.verifyState(art, false, true);
        r.sim.dripstoneBreakThrough = true;
        var run = VerifyBreakInTest.repair(r, art, false);
        String log = String.join("\n", run.log());
        assertEquals(TaskStatus.SUCCESS, run.status(), log);
        assertTrue(log.contains("dripstone break-through for stand spot: breaking"), log);
        assertFalse(log.contains("cannot reach stand spot"), log);
        assertFalse(log.contains("12 stand spots in a row"), log);
        assertFalse(r.sim.brokenWalls.isEmpty());
        assertTrue(r.sim.brokenWalls.size() <= 6);
        assertTrue(run.accepted().containsAll(r.sim.brokenWalls));
        assertEquals(VerifyBreakInTest.cell(r, 81, 55), run.accepted().getFirst());
        List<Pos> reverse = new ArrayList<>(r.sim.brokenWalls);
        java.util.Collections.reverse(reverse);
        assertEquals(reverse, run.accepted().subList(1, run.accepted().size()));
        assertTrue(r.matches(art), "tile verifies: " + r.mismatches(art));
        assertTrue(VerifyBreakInTest.out(r), "the bot retains a way out");
        for (Pos p : r.sim.brokenWalls) {
            assertEquals(r.geo.artY(), p.y());
            assertNotNull(r.sim.artAt(p));
        }
    }

    @Test
    void noCandidateStandSpotAlsoRecoversDuringBuild() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        TileRunner r = VerifyBreakInTest.verifyState(art, false, true);
        r.sim.dripstoneBreakThrough = true;
        var run = VerifyBreakInTest.repair(r, art, false);
        assertEquals(TaskStatus.SUCCESS, run.status());
        assertTrue(run.log().stream().anyMatch(s -> s.startsWith("dripstone break-through for stand spot")));
        assertFalse(r.sim.brokenWalls.isEmpty());
        assertTrue(r.matches(art), "tile verifies: " + r.mismatches(art));
        assertTrue(VerifyBreakInTest.out(r));
    }

    @Test
    void killSwitchLeavesTheOldStandSpotFailure() throws Exception {
        TileArt art = LaneWayOutTest.load("ellen_1_6");
        art.set(81, 56, null);
        art.set(81, 57, null);
        TileRunner r = VerifyBreakInTest.verifyState(art, false, true);
        r.sim.dripstoneBreakThrough = false;
        var run = VerifyBreakInTest.repair(r, art, false);
        assertTrue(r.sim.brokenWalls.isEmpty());
        assertFalse(r.matches(art));
        assertTrue(run.log().stream().anyMatch(s -> s.contains("skipped")));
    }

    static final class WalkIo implements DripstoneBreakThrough.Io {
        final boolean[][] walls = new boolean[15][15];
        boolean enabled = true;
        boolean protectedWall;
        boolean failBreak;
        boolean slowBreak;
        /** Cells with x above this are no art (off the tile): {@link #target} null. */
        int artMaxX = Integer.MAX_VALUE;
        final List<Pos> broken = new ArrayList<>();
        final List<Pos> restore = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        WalkIo(int thickness) {
            for (int x = 3; x < 3 + thickness; x++) for (int z = 0; z < 15; z++) walls[x][z] = true;
        }
        @Override public boolean enabled() { return enabled; }
        @Override public Pos feet() { return new Pos(1, 64, 7); }
        @Override public BreakIn.Grid grid() { return VerifyBreakInTest.grid(walls, 15, !protectedWall); }
        @Override public BreakIn.Target target(Pos p) { return p.x() > artMaxX ? null : new BreakIn.Target(p, true, true); }
        @Override public BreakIn.Reach reach() { return new BreakIn.Reach(4.25, 4.75); }
        @Override public TaskStatus breakWalls(List<Pos> cells, List<Pos> path) {
            for (Pos p : cells) { walls[p.x()][p.z()] = false; broken.add(p); if (failBreak) return TaskStatus.FAILED; if (slowBreak) return TaskStatus.RUNNING; }
            return TaskStatus.SUCCESS;
        }
        @Override public void stop() { }
        @Override public void restore(List<Pos> cells) { for (Pos p : cells) if (!walls[p.x()][p.z()]) restore.add(p); }
        @Override public void log(String line) { log.add(line); }
        @Override public String relative(Pos p) { return "[X" + p.x() + ",64,Z" + p.z() + "]"; }
    }

    @Test
    void everyWalkKindUsesTheSameBoundedRecovery() {
        for (String kind : List.of("lane start", "lane walk", "stand spot", "restock", "container", "drop", "VERIFY", "walkTo")) {
            WalkIo io = new WalkIo(2);
            var recovery = new DripstoneBreakThrough(io);
            List<GoalNearXZ> goal = List.of(new GoalNearXZ(9, 64, 7, 0, 0));
            Supplier<TaskStatus> walk = () -> io.broken.isEmpty() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
            assertEquals(TaskStatus.RUNNING, recovery.tick(goal, kind, walk));
            assertEquals(TaskStatus.SUCCESS, recovery.tick(goal, kind, walk));
            assertEquals(2, io.broken.size());
            assertEquals(io.broken.reversed(), io.restore);
            assertEquals(1, io.log.size());
            assertTrue(io.log.getFirst().startsWith("dripstone break-through for " + kind + ": breaking 2 block(s) ["));
        }
    }

    /** Live 2026-10-13, remu [2,2]: boxed in on the art layer, the storage one block up; a 45-cell pocket here. */
    @Test
    void aBoxedInBotBreaksOutTowardsAGoalOnAnotherLevel() {
        WalkIo io = new WalkIo(2);
        var recovery = new DripstoneBreakThrough(io);
        List<GoalNearXZ> chest = List.of(new GoalNearXZ(9, 65, 7, 0, 0));
        Supplier<TaskStatus> walk = () -> io.broken.isEmpty() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        assertEquals(TaskStatus.RUNNING, recovery.tick(chest, "container", walk));
        assertEquals(TaskStatus.SUCCESS, recovery.tick(chest, "container", walk));
        assertEquals(2, io.broken.size(), "the fewest walls to the open side");
        assertEquals(io.broken.reversed(), io.restore);
        assertTrue(io.log.getFirst().startsWith("dripstone break-through for container: breaking 2 block(s)"), io.log.toString());
    }

    @Test
    void aBotInTheOpenNeverBreaksForAGoalOnAnotherLevel() {
        WalkIo io = new WalkIo(0); // no walls: the bot's floor joins the goal's
        io.artMaxX = 1;
        var recovery = new DripstoneBreakThrough(io);
        assertEquals(TaskStatus.FAILED, recovery.tick(List.of(new GoalNearXZ(14, 65, 7, 0, 0)), "container",
            () -> TaskStatus.FAILED));
        assertTrue(io.broken.isEmpty());
    }

    /** Live 2026-10-13, remu [2,2] LOCK: a sealed art area of several hundred cells; 180 here, all of it art. */
    @Test
    void aLargeSealedArtAreaBreaksOutTowardsAGoalOnAnotherLevel() {
        WalkIo io = new WalkIo(0);
        for (int z = 0; z < 15; z++) { io.walls[12][z] = true; io.walls[13][z] = true; }
        io.artMaxX = 13; // the tile ends at the wall: x 14 is the storage side
        var recovery = new DripstoneBreakThrough(io);
        List<GoalNearXZ> table = List.of(new GoalNearXZ(14, 65, 7, 0, 0));
        Supplier<TaskStatus> walk = () -> io.broken.isEmpty() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        assertEquals(TaskStatus.RUNNING, recovery.tick(table, "container", walk));
        assertEquals(TaskStatus.SUCCESS, recovery.tick(table, "container", walk));
        assertEquals(2, io.broken.size(), io.log.toString());
    }

    /** Live 2026-10-13, remu [2,2] LOCK: the cartography table off the tile, its goal tolerant of the level change. */
    @Test
    void aSealedBotBreaksOutTowardsAGoalOffTheTileOnItsLevel() {
        WalkIo io = new WalkIo(0);
        for (int z = 0; z < 15; z++) { io.walls[12][z] = true; io.walls[13][z] = true; }
        io.artMaxX = 13;
        var recovery = new DripstoneBreakThrough(io);
        List<GoalNearXZ> table = List.of(new GoalNearXZ(14, 65, 7, 0, 3));
        Supplier<TaskStatus> walk = () -> io.broken.isEmpty() ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        assertEquals(TaskStatus.RUNNING, recovery.tick(table, "container", walk));
        assertEquals(TaskStatus.SUCCESS, recovery.tick(table, "container", walk));
        assertEquals(2, io.broken.size(), io.log.toString());
    }

    /** A goal whose floor the grid cannot see (beyond what is loaded) gives the escape nothing to break towards. */
    @Test
    void aFarGoalWithoutKnownFloorIsNeverBrokenTowards() {
        WalkIo io = new WalkIo(0);
        for (int z = 0; z < 15; z++) { io.walls[12][z] = true; io.walls[13][z] = true; }
        io.artMaxX = 13;
        assertEquals(TaskStatus.FAILED, new DripstoneBreakThrough(io).tick(List.of(new GoalNearXZ(400, 65, 7, 3, 3)),
            "container", () -> TaskStatus.FAILED));
        assertTrue(io.broken.isEmpty());
    }

    @Test
    void protectedWallsAndMoreThanSixBreaksAreRefused() {
        for (WalkIo io : List.of(new WalkIo(7), new WalkIo(2))) {
            if (!io.walls[5][0]) io.protectedWall = true;
            var recovery = new DripstoneBreakThrough(io);
            assertEquals(TaskStatus.FAILED, recovery.tick(List.of(new GoalNearXZ(11, 64, 7, 0, 0)), "walkTo", () -> TaskStatus.FAILED));
            assertTrue(io.broken.isEmpty());
        }
    }

    @Test
    void recoveryDoesNotBreakInANoGoZoneOrAnotherLayer() {
        // another layer: only from a pocket (aBoxedInBotBreaksOutTowardsAGoalOnAnotherLevel); here the bot is in the open
        WalkIo open = new WalkIo(0); // no walls: the bot's floor joins the goal's
        assertEquals(TaskStatus.FAILED, new DripstoneBreakThrough(open).tick(List.of(new GoalNearXZ(14, 63, 7, 0, 0)),
            "walkTo", () -> TaskStatus.FAILED));
        assertTrue(open.broken.isEmpty());
        WalkIo io = new WalkIo(2);
        // The only cross-field cells belong to a forbidden zone, although the art walls themselves are clearable.
        DripstoneBreakThrough.Io zoned = new DripstoneBreakThrough.Io() {
            @Override public boolean enabled() { return true; }
            @Override public Pos feet() { return io.feet(); }
            @Override public BreakIn.Grid grid() {
                BreakIn.Grid g = io.grid();
                return new BreakIn.Grid() {
                    @Override public boolean passable(Pos p) { return g.passable(p); }
                    @Override public boolean floor(Pos p) { return g.floor(p); }
                    @Override public boolean breakable(Pos p) { return g.breakable(p); }
                    @Override public double outlineTop(Pos p) { return g.outlineTop(p); }
                    @Override public boolean standAllowed(Pos p) { return p.x() != 3; }
                };
            }
            @Override public BreakIn.Target target(Pos p) { return io.target(p); }
            @Override public BreakIn.Reach reach() { return io.reach(); }
            @Override public TaskStatus breakWalls(List<Pos> walls, List<Pos> path) { return io.breakWalls(walls, path); }
            @Override public void stop() { }
            @Override public void restore(List<Pos> cells) { io.restore(cells); }
            @Override public void log(String line) { io.log(line); }
            @Override public String relative(Pos p) { return io.relative(p); }
        };
        assertEquals(TaskStatus.FAILED, new DripstoneBreakThrough(zoned).tick(
            List.of(new GoalNearXZ(9, 64, 7, 0, 0)), "container", () -> TaskStatus.FAILED));
        assertTrue(io.broken.isEmpty());
    }

    @Test
    void cancelledWalkKeepsEveryAlreadyBrokenCell() {
        WalkIo io = new WalkIo(2);
        io.slowBreak = true;
        var recovery = new DripstoneBreakThrough(io);
        var goal = List.of(new GoalNearXZ(9, 64, 7, 0, 0));
        assertEquals(TaskStatus.RUNNING, recovery.tick(goal, "restock", () -> TaskStatus.FAILED));
        assertEquals(TaskStatus.RUNNING, recovery.tick(goal, "restock", () -> TaskStatus.FAILED));
        recovery.cancel();
        assertEquals(1, io.broken.size());
        assertEquals(io.broken, io.restore);
    }

    @Test
    void partialBreakFailureStillQueuesRestoration() {
        WalkIo io = new WalkIo(2);
        io.failBreak = true;
        var recovery = new DripstoneBreakThrough(io);
        var goal = List.of(new GoalNearXZ(9, 64, 7, 0, 0));
        assertEquals(TaskStatus.RUNNING, recovery.tick(goal, "drop", () -> TaskStatus.FAILED));
        assertEquals(TaskStatus.FAILED, recovery.tick(goal, "drop", () -> TaskStatus.FAILED));
        assertEquals(1, io.broken.size());
        assertEquals(io.broken, io.restore);
    }
}
