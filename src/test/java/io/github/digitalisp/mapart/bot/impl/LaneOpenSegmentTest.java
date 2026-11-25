package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.PlacementListener;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A restock leaves early dripstone walls and a few holes; the open part still gets the lane walk. */
class LaneOpenSegmentTest {
    private static final BlockSpec CARPET = BlockSpec.of("minecraft:white_carpet");
    private static final BlockSpec DRIPSTONE = BlockSpec.of("minecraft:pointed_dripstone");

    private static TileRunner runner(boolean segment) {
        TileRunner r = LaneReentryTest.runner(7, true);
        r.build.laneOpenSegment = segment;
        r.finiteStock = false;
        r.sim.wallIds = LaneWayOutTest.WALLS;
        return r;
    }

    private record Pass(TaskStatus status, long ticks, int cells, Set<Pos> placed, Set<Pos> skipped,
                        Set<Pos> walkPlaced, Set<Pos> spotPlaced, String log) { }

    private static Pass run(TileRunner r, Lane lane) {
        Set<Pos> placed = new HashSet<>();
        Set<Pos> skipped = new HashSet<>();
        Set<Pos> walk = new HashSet<>();
        Set<Pos> spot = new HashSet<>();
        PlacementListener listener = new PlacementListener() {
            @Override public void placed(Placement p) { placed.add(p.pos()); }
            @Override public void skipped(Placement p, String reason) { skipped.add(p.pos()); }
        };
        r.sim.teleport(lane.from().x() - 5 + 0.5, r.geo.artY(), lane.from().z() + 0.5);
        TaskStatus status = r.sim.runLane(lane, r.params(), listener, 4000 + 400 * lane.placements().size(),
            (t, run) -> {
                r.sim.onAccept = p -> (run.phase() == LaneRun.Phase.SPOT ? spot : walk).add(p.pos());
                if (run.phase() == LaneRun.Phase.RUN) {
                    Pos feet = new Pos((int) Math.floor(r.sim.x), r.geo.artY(), (int) Math.floor(r.sim.z));
                    assertFalse(r.world.getOrDefault(feet, BlockSpec.AIR).equals(DRIPSTONE), "walk in a wall: " + feet);
                }
                return true;
            });
        String log = String.join("\n", r.sim.log);
        assertEquals(TaskStatus.SUCCESS, status, log);
        assertEquals(lane.placements().size(), placed.size() + skipped.size(), log);
        for (Placement p : lane.placements()) {
            assertTrue(placed.contains(p.pos()) || skipped.contains(p.pos()), "unreported cell " + p.pos());
            if (placed.contains(p.pos())) assertEquals(p.block(), r.world.get(p.pos()), "placed cell " + p.pos());
        }
        LaneReentryTest.assertClean(r, log);
        return new Pass(status, r.sim.lastTicks, lane.placements().size(), placed, skipped, walk, spot, log);
    }

    private static TileArt liveArt() {
        TileArt art = new TileArt();
        for (int row = 4; row < 124; row++) {
            for (int col = 54; col <= 62; col++) {
                boolean dripstone = row < 16 || (row % 11 == 0 && col % 2 == 0);
                art.set(col, row, dripstone ? DRIPSTONE : CARPET);
            }
        }
        return art;
    }

    private static Pass live(TileRunner r) {
        TileArt art = liveArt();
        for (int row = 4; row < 16; row++) {
            for (int col = 54; col <= 62; col++) {
                if (col == 54 && row < 12) continue; // eight early leftovers keep trimEnds' first row early
                r.world.put(r.geo.cell(col, row), art.get(col, row));
            }
        }
        return run(r, r.plan(art).getFirst());
    }

    @Test
    void earlyLeftoversDoNotSendTheLongOpenPartToStandSpots() {
        TileRunner off = runner(false);
        Pass before = live(off);
        TileRunner on = runner(true);
        Pass after = live(on);
        System.out.println("lane segment: off " + before.ticks() + " ticks, " + before.spotPlaced().size()
            + " from spots; on " + after.ticks() + " ticks, " + after.spotPlaced().size() + " from spots, "
            + after.walkPlaced().size() + " from walk, " + after.skipped().size() + " skipped");
        assertEquals(980, after.cells());
        assertTrue(after.log().contains("walking line cells 13..121 of 0..121 (972 of 980 cells), the other 8 from stand spots"), after.log());
        assertFalse(after.log().contains("walking line ends in full blocks"), after.log());
        assertFalse(after.log().contains("walking over them"), after.log());
        assertTrue(after.walkPlaced().size() >= 950, after.log());
        for (int row = 4; row < 12; row++) {
            Pos p = on.geo.cell(54, row);
            assertFalse(after.walkPlaced().contains(p), "early cell from walk " + p);
            assertTrue(after.spotPlaced().contains(p) || after.skipped().contains(p), "early cell unreported " + p);
        }
        assertTrue(after.ticks() * 3 < before.ticks() * 2, "off " + before.ticks() + "; on " + after.ticks());
    }

    @Test
    void killSwitchKeepsEveryCellAtStandSpots() {
        Pass p = live(runner(false));
        assertTrue(p.log().contains("walking line walled by full blocks; 980 cells from stand spots"), p.log());
        assertTrue(p.walkPlaced().isEmpty(), p.log());
        assertEquals(p.placed(), p.spotPlaced());
    }

    /** A carpet lane with dripstone already on the line at the supplied rows (all rows are tile-relative). */
    private static Lane walls(TileRunner r, int rows, int... walls) {
        TileArt art = TileRunner.fill(CARPET.id(), 54, 4, 63, 4 + rows);
        for (int row : walls) {
            art.set(58, row, DRIPSTONE);
            r.world.put(r.geo.cell(58, row), DRIPSTONE);
        }
        return r.plan(art).getFirst();
    }

    @Test
    void aSegmentServingFewerThanSixteenCellsKeepsStandSpots() {
        TileRunner r = runner(true);
        TileArt art = TileRunner.fill(CARPET.id(), 54, 4, 57, 10);
        for (int row = 4; row < 10; row += 2) {
            art.set(58, row, DRIPSTONE);
            r.world.put(r.geo.cell(58, row), DRIPSTONE);
        }
        Pass p = run(r, r.plan(art).getFirst());
        assertEquals(18, p.cells());
        assertTrue(p.log().contains("walking line walled by full blocks; 18 cells from stand spots"), p.log());
        assertTrue(p.walkPlaced().isEmpty(), p.log());
    }

    @Test
    void aSegmentServingLessThanAQuarterKeepsStandSpots() {
        TileRunner r = runner(true);
        Pass p = run(r, walls(r, 40, 8, 13, 18, 23, 28, 33, 38));
        assertTrue(p.log().contains("walking line walled by full blocks; " + p.cells() + " cells from stand spots"), p.log());
        assertTrue(p.walkPlaced().isEmpty(), p.log());
    }

    @Test
    void anInteriorSegmentStartsAfterAndEndsBeforeWalls() {
        TileRunner r = runner(true);
        Pass p = run(r, walls(r, 120, 13, 113));
        assertTrue(p.log().contains("walking line cells 11..109 of 0..121 (891 of 1078 cells), the other 187 from stand spots"), p.log());
        assertTrue(p.walkPlaced().size() >= 880, p.log());
        for (Pos cell : p.walkPlaced()) {
            int row = cell.z() - r.geo.minZ();
            assertTrue(row > 13 && row < 113, "outside the segment: " + cell);
        }
        assertEquals(p.cells(), p.placed().size());
    }

    @Test
    void theSegmentWithMostCellsWinsOverTheLongestSegment() {
        TileRunner r = runner(true);
        TileArt art = TileRunner.fill(CARPET.id(), 54, 4, 63, 24);
        for (int row = 24; row < 124; row++) art.set(54, row, CARPET);
        for (int row : new int[]{24, 84}) {
            art.set(58, row, DRIPSTONE);
            r.world.put(r.geo.cell(58, row), DRIPSTONE);
        }
        Pass p = run(r, r.plan(art).getFirst());
        assertTrue(p.log().contains("walking line cells 0..20 of 0..121 (180 of 280 cells), the other 100 from stand spots"), p.log());
        assertEquals(180, p.walkPlaced().size(), p.log());
    }

    @Test
    void reverseLaneUsesLaneRelativeRowsForBothEnds() {
        TileRunner r = runner(true);
        Lane lane = walls(r, 120, 13, 113);
        Pass p = run(r, new Lane(lane.index(), lane.to(), lane.from(), lane.placements()));
        assertTrue(p.log().contains("walking line cells 12..110 of 0..121 (891 of 1078 cells), the other 187 from stand spots"), p.log());
        assertTrue(p.walkPlaced().size() >= 880, p.log());
        assertEquals(p.cells(), p.placed().size());
    }

    @Test
    void aSuccessfulTrimIsIdenticalWithTheSegmentSwitchOn() {
        for (boolean start : new boolean[]{false, true}) {
            TileRunner off = runner(false);
            Lane beforeLane = walls(off, 30, start ? 4 : 33);
            Pass before = run(off, beforeLane);
            TileRunner on = runner(true);
            Pass after = run(on, walls(on, 30, start ? 4 : 33));
            assertTrue(after.log().contains("walking line ends in full blocks"), after.log());
            assertFalse(after.log().contains("walking line walled by full blocks"), after.log());
            assertEquals(before.log(), after.log());
            assertEquals(before.ticks(), after.ticks());
            assertEquals(before.walkPlaced(), after.walkPlaced());
            assertEquals(before.spotPlaced(), after.spotPlaced());
        }
    }

    @Test
    void configAndParameterCopiesCarryTheKillSwitch() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertTrue(b.laneOpenSegment);
        b.laneOpenSegment = false;
        LaneRun.Params p = LaneRun.Params.of(b, null).withTrimEnds(false).withWayOut(false)
            .withKeepReachable(false).withBreakIn(true).withGreedy(true).withSideFaces(false)
            .withPacing(PlacePacing.DEFAULT);
        assertFalse(p.openSegment());
        assertTrue(p.withOpenSegment(true).openSegment());
    }
}
