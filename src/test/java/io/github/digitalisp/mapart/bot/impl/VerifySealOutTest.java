package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Live remu [0,2], 2026-10-13: the south-east carpet pocket and dripstone break-out holes. */
class VerifySealOutTest {
    static final String[] LIVE = {
        ".....######################################.#..###..............",
        ".....#########################################..##..............",
        "......#######################################.#.##..............",
        "......############################################..............",
        ".......#.########################################...............",
        ".......##########################################...............",
        "........#.######################################................",
        "........#######################################.................",
        "........####.###############################....................",
        "..........#################################.....................",
        "...........##############################..................###O#",
        "...........##.############.##.##.######.#.............########O#",
        "............##############..#....####...#..........###########O#",
        "...........#.#.###################..####.......######.########O#",
        "............#.##..#.####..#...######.........####.####..######O#",
        ".............#.####.#.....####..............##################O#",
        "..............#....#...###.#.#............####################O#",
        ".................#.#######.............#######################O#",
        "................#######.#............#########################O#",
        "...............#########...........###########################O#",
        "...............########........#.######.######################O#",
        "..............####....##....##################################..",
        "...............##...#########.################################O#",
        "....................#######################################.##..",
        "...............#...##########################################...",
        "..................######################################..#.....",
        "..................########################################......",
        "................#########################################.......",
        "................######################################.#........",
        "................######################################..#.......",
        "................############################################....",
        "................##############################################O#",
        "................##############################################O#",
        "................##############################################O#",
        "................##############################################O#",
        "................##############################################O#",
        "................##############################################O#",
        "................##############################################O#",
        "...............###############################################O#",
        "...............###############################################O#",
        "...............###############################################O#",
        "...............#################################################",
        "...............#################################################",
        "...............#################################################",
        "..............##################################################",
        "..............##################################################",
        "...............#################################################",
        "...............#################################################",
        "...............#################################################",
        "..............##################################################",
        "..............##################################################",
        "..............##################################################",
        "...............########################################.B#####..",
        "...............################################OOOOOOOO.....##..",
        "...............#############################OOOO#########.#.##.#",
        "...............#############################O##############.O..#",
        "...............#########################OOOO##################..",
        "................########################O######################."
    };

    record Fixture(TileRunner runner, TileArt art, List<Pos> holes) { }

    static Fixture live(boolean enabled) {
        TileArt art = TileRunner.fill("minecraft:black_carpet", 0, 0, 128, 128);
        TileRunner r = state(art, enabled);
        List<Pos> holes = new ArrayList<>();
        assertEquals(58, LIVE.length);
        for (int row = 0; row < LIVE.length; row++) {
            assertEquals(64, LIVE[row].length(), "row " + (row + 70));
            for (int col = 0; col < 64; col++) {
                char c = LIVE[row].charAt(col);
                Pos p = cell(r, col + 64, row + 70);
                BlockSpec b = BlockSpec.of(c == '#' || c == 'O' ? "minecraft:pointed_dripstone" : "minecraft:black_carpet");
                art.set(col + 64, row + 70, b);
                if (c == 'O') { r.world.remove(p); holes.add(p); }
                else r.world.put(p, b);
                if (c == 'B') r.sim.teleport(p.x() + 0.5, p.y(), p.z() + 0.5);
            }
        }
        return new Fixture(r, art, holes);
    }

    static TileRunner state(TileArt art, boolean enabled) {
        TileRunner r = LaneReentryTest.runner(5, true);
        r.finiteStock = false;
        r.build.verifySealOut = enabled;
        r.sim.wallIds = LaneWayOutTest.WALLS;
        r.sim.platformBlock = BlockSpec.of("minecraft:soul_sand");
        r.sim.standCells = p -> r.sim.tileArea().contains(p.x(), p.z());
        for (int col = 0; col < 128; col++) for (int row = 0; row < 128; row++) {
            BlockSpec b = art.get(col, row);
            if (b != null) r.world.put(cell(r, col, row), b);
        }
        r.sim.art = p -> {
            int col = p.x() - r.geo.minX(), row = p.z() - r.geo.minZ();
            if (p.y() != r.geo.artY() || col < 0 || row < 0 || col >= 128 || row >= 128) return null;
            BlockSpec b = art.get(col, row);
            return b == null ? null : new Placement(p, b, b.id());
        };
        return r;
    }

    static Pos cell(TileRunner r, int col, int row) { return KeepReachableTest.cell(r, col, row); }

    static TaskStatus repair(TileRunner r, TileArt art) {
        List<Lane> lanes = r.plan(art);
        return r.sim.runLane(lanes.getFirst(), r.params().withBreakIn(r.build.verifyBreakIn)
            .withSealOut(r.build.verifySealOut), VerifyBreakInTest.NONE, 100_000, lanes.subList(1, lanes.size()));
    }

    @Test
    void livePocketEscapesAndTheTileMatches() {
        Fixture f = live(true);
        TileRunner r = f.runner();
        assertEquals(41, f.holes().size());
        assertFalse(r.sim.reachable(cell(r, 65, 120).x(), cell(r, 65, 120).z()));
        assertTrue(r.verify(f.art(), 4), r.report(f.art()));
        assertEquals(1, r.passes, "VERIFY completes in one repair pass");
        assertEquals(0, r.failures);
        assertEquals(1, r.lanesRun, "later lanes skip cells already refilled");
        assertTrue(r.matches(f.art()), r.report(f.art()));
        assertTrue(r.sim.reachable(cell(r, 65, 120).x(), cell(r, 65, 120).z()), "the bot ends outside");
        // Rows 111..121 close the eastern corridor: 11 escape walls; the bottom hole group needs one more.
        assertEquals(12, r.sim.brokenWalls.size(), "only the necessary escape and access walls: " + r.sim.brokenWalls);
        assertEquals(1, r.sim.log.stream().filter(s -> s.startsWith("VERIFY seal-out:") && s.contains("placing farthest first")).count());
        assertTrue(r.sim.log.stream().anyMatch(s -> s.contains("bot in outside=true")), String.join("\n", r.sim.log));
    }

    @Test
    void deadEndCorridorRefillsFarthestFirst() {
        TileArt art = TileRunner.fill("minecraft:black_carpet", 0, 0, 128, 128);
        for (int x = 40; x <= 60; x++) for (int z = 40; z <= 60; z++) art.set(x, z, BlockSpec.of("minecraft:pointed_dripstone"));
        TileRunner r = state(art, true);
        List<Pos> expected = new ArrayList<>();
        for (int x = 40; x <= 55; x++) { r.world.remove(cell(r, x, 50)); expected.addFirst(cell(r, x, 50)); }
        r.sim.teleport(cell(r, 38, 50).x() + 0.5, r.geo.artY(), cell(r, 38, 50).z() + 0.5);
        List<Pos> placed = new ArrayList<>();
        r.sim.onAccept = p -> placed.add(p.pos());
        assertEquals(TaskStatus.SUCCESS, repair(r, art));
        assertEquals(expected, placed);
        assertTrue(r.matches(art), r.report(art));
        assertTrue(r.sim.brokenWalls.isEmpty());
        assertTrue(r.sim.reachable(cell(r, 38, 50).x(), cell(r, 38, 50).z()));
    }

    @Test
    void killSwitchKeepsPerCellRepairs() {
        Fixture f = live(false);
        assertEquals(TaskStatus.SUCCESS, repair(f.runner(), f.art()));
        assertFalse(f.runner().matches(f.art()), "old repair leaves holes");
        assertTrue(f.runner().sim.log.stream().noneMatch(s -> s.startsWith("VERIFY seal-out:")));
        assertFalse(f.runner().params().sealOut(), "ordinary BUILD parameters never enable seal-out");
    }

    static TileRunner closeLargePocket(boolean guard) {
        TileArt art = TileRunner.fill("minecraft:black_carpet", 0, 0, 128, 128);
        for (int x = 70; x <= 82; x++) for (int z = 70; z <= 82; z++) {
            if (x == 70 || x == 82 || z == 70 || z == 82) art.set(x, z, BlockSpec.of("minecraft:pointed_dripstone"));
        }
        TileRunner r = state(art, false);
        Pos exit = cell(r, 70, 76);
        r.world.remove(exit);
        r.sim.teleport(exit.x() + 2.5, r.geo.artY(), exit.z() + 0.5);
        r.build.laneWayOut = guard;
        r.build.walkWalledLanes = false;
        r.build.trimLaneEnds = false;
        r.build.laneOpenSegment = false;
        Lane lane = new Lane(0, cell(r, 70, 70), cell(r, 70, 82), List.of(new Placement(exit,
            BlockSpec.of("minecraft:pointed_dripstone"), "minecraft:pointed_dripstone")));
        List<Pos> accepted = new ArrayList<>();
        r.sim.onAccept = p -> {
            accepted.add(p.pos());
            if (guard) assertTrue(r.sim.x < exit.x(), "the guard must refuse the click until the bot leaves");
        };
        assertEquals(TaskStatus.SUCCESS, r.sim.runLane(lane, r.params(), VerifyBreakInTest.NONE, 20_000));
        assertEquals(List.of(exit), accepted, "placed after leaving the large pocket");
        return r;
    }

    @Test
    void largePocketClosureAroundTheBotIsRefused() {
        TileRunner guarded = closeLargePocket(true);
        assertTrue(guarded.sim.reachable(cell(guarded, 65, 76).x(), cell(guarded, 65, 76).z()));
        TileRunner old = closeLargePocket(false);
        assertFalse(old.sim.reachable(cell(old, 65, 76).x(), cell(old, 65, 76).z()), "the unguarded closure is a trap");
    }

    @Test
    void keepReachableProtectsAHoleInALargePocket() {
        for (boolean guard : new boolean[]{true, false}) {
            TileArt art = TileRunner.fill("minecraft:black_carpet", 0, 0, 128, 128);
            BlockSpec wall = BlockSpec.of("minecraft:pointed_dripstone");
            for (int x = 70; x <= 82; x++) for (int z = 70; z <= 82; z++) {
                if (x == 70 || x == 82 || z == 70 || z == 82) art.set(x, z, wall);
            }
            art.set(78, 76, wall);
            TileRunner r = state(art, false);
            r.build.laneKeepReachable = guard;
            r.build.walkWalledLanes = false;
            r.build.trimLaneEnds = false;
            r.build.laneOpenSegment = false;
            Pos gate = cell(r, 70, 76), hole = cell(r, 78, 76);
            r.world.remove(gate);
            r.world.remove(hole);
            r.sim.teleport(gate.x() - 1.5, r.geo.artY(), gate.z() + 0.5);
            Lane lane = new Lane(0, cell(r, 70, 70), cell(r, 70, 82), List.of(
                new Placement(gate, wall, wall.id()), new Placement(hole, wall, wall.id())));
            List<Pos> order = new ArrayList<>();
            r.sim.onAccept = p -> order.add(p.pos());
            assertEquals(TaskStatus.SUCCESS, r.sim.runLane(lane, r.params(), VerifyBreakInTest.NONE, 20_000));
            if (guard) {
                assertEquals(List.of(hole, gate), order, "keep the 121-cell pocket reachable until the hole goes up");
                assertTrue(r.matches(art));
            } else {
                assertEquals(List.of(gate), order);
                assertFalse(r.matches(art));
            }
        }
    }

    @Test
    void sealOutWorksWithPerCellBreakInDisabled() {
        Fixture f = live(true);
        f.runner().build.verifyBreakIn = false;
        assertEquals(TaskStatus.SUCCESS, repair(f.runner(), f.art()));
        assertTrue(f.runner().matches(f.art()), f.runner().report(f.art()));
        assertEquals(12, f.runner().sim.brokenWalls.size());
    }

    @Test
    void outsideUsesTheApproachBorderRatherThanPocketSize() {
        LaneRun.Area area = new LaneRun.Area(0, 0, 11, 11);
        var outside = SealOut.outside(area, 64, p -> p.x() == 0
            || p.x() >= 3 && p.x() <= 8 && p.z() >= 3 && p.z() <= 8);
        assertEquals(12, outside.size(), "the 36-cell enclosed pocket is not outside");
        assertTrue(outside.contains(new Pos(0, 64, 5)));
        assertFalse(outside.contains(new Pos(5, 64, 5)));
    }

    @Test
    void protectedEscapeWallIsNeverBroken() {
        Fixture f = live(true);
        TileRunner r = f.runner();
        r.build.verifyBreakIn = false;
        // Protect the eastern escape barrier and the bottom group: no legal way out from the pocket.
        r.sim.standCells = p -> r.sim.tileArea().contains(p.x(), p.z())
            && !LaneWayOutTest.WALLS.contains(r.sim.block(p).id());
        assertEquals(TaskStatus.SUCCESS, repair(r, f.art()));
        assertTrue(r.sim.brokenWalls.isEmpty());
        assertFalse(r.matches(f.art()));
    }
}

