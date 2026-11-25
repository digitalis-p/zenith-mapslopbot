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

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lane walk's brake before a wall at its end ({@code build.laneWallBrake}, LaneRun class javadoc "Line ends") on the
 * live 2026-10-20 case (Abaddon [3,4], lane 14): a pointed dripstone on the line walls it, the walk ends in front of it
 * at Soul Speed (0.49 blocks a tick), and the dripstone is a thin column ({@link
 * io.github.digitalisp.mapart.testutil.LaneSim#thinWalls}): without the brake the bot slides 0.6 on after its keys and its feet
 * end in the dripstone's cell, where no path can start.
 */
class LaneWallBrakeTest {
    private static final BlockSpec CARPET = BlockSpec.of("minecraft:white_carpet");
    private static final BlockSpec DRIPSTONE = BlockSpec.of("minecraft:pointed_dripstone");
    /** Soul Speed on soul sand: 0.49 a tick at friction 0.546. */
    private static final double SOUL_SPEED_ACCEL = 0.49 * (1 - 0.546);

    private record Pass(TaskStatus status, int cells, int placed, long ticks, int wallTicks, Pos firstWall, String log) { }

    private static Pass run(boolean brake, double accel, boolean reverse) {
        TileRunner r = LaneReentryTest.runner(7, true);
        r.finiteStock = false;
        r.build.pacedLaneWalk = true; // live
        r.sim.wallIds = LaneWayOutTest.WALLS;
        r.sim.thinWalls = true;
        r.sim.thinWallOffset = 0.08; // the live dripstone's: the box stopped at feet 0.096 into its cell
        r.sim.groundAccel = accel;
        r.sim.wallBrake = brake;
        // the lane's open segment: 60 rows of carpet on the line itself (the live edge lane: few cells a row, the walk
        // at full speed, nothing left behind it at the end), the line walled by a dripstone after it; the cells past it
        // from stand spots
        TileArt art = TileRunner.fill(CARPET.id(), 58, 4, 59, 70);
        int wallRow = 64;
        art.set(58, wallRow, DRIPSTONE);
        r.world.put(r.geo.cell(58, wallRow), DRIPSTONE);
        // the end cell already placed (live: nothing left on the line behind the walk at its end, no walk back)
        r.world.put(r.geo.cell(58, wallRow - 1), CARPET);
        Lane lane = r.plan(art).getFirst();
        if (reverse) lane = new Lane(lane.index(), lane.to(), lane.from(), lane.placements());
        Set<Pos> placed = new HashSet<>();
        Set<Pos> skipped = new HashSet<>();
        PlacementListener listener = new PlacementListener() {
            @Override public void placed(Placement p) { placed.add(p.pos()); }
            @Override public void skipped(Placement p, String reason) { skipped.add(p.pos()); }
        };
        int[] wallTicks = {0};
        Pos[] firstWall = {null};
        r.sim.teleport(lane.from().x() - 5 + 0.5, r.geo.artY(), lane.from().z() + 0.5);
        TaskStatus status = r.sim.runLane(lane, r.params(), listener, 4000 + 400 * lane.placements().size(),
            (t, run) -> {
                Pos feet = new Pos((int) Math.floor(r.sim.x), r.geo.artY(), (int) Math.floor(r.sim.z));
                if (DRIPSTONE.equals(r.world.get(feet))) {
                    wallTicks[0]++;
                    if (firstWall[0] == null) firstWall[0] = feet;
                }
                return true;
            });
        String log = String.join("\n", r.sim.log);
        assertEquals(lane.placements().size(), placed.size() + skipped.size(), log);
        return new Pass(status, lane.placements().size(), placed.size(), r.sim.lastTicks, wallTicks[0], firstWall[0], log);
    }

    /**
     * Without the brake the live slide comes back: keys down until 0.05 before the end, 0.49 a tick, the box against
     * the column with the feet 0.09 into the dripstone's cell (live 0.096).
     */
    @Test
    void withoutTheBrakeTheSlideEndsInTheWallsCell() {
        Pass p = run(false, SOUL_SPEED_ACCEL, false);
        System.out.println("no brake: " + p.wallTicks() + " ticks in the wall's cell, first " + p.firstWall() + "; "
            + p.placed() + " of " + p.cells() + " in " + p.ticks() + " ticks");
        assertTrue(p.wallTicks() > 0, "the scenario reproduces the live slide into the dripstone's cell\n" + p.log());
    }

    /** With it, at Soul Speed and walking, forward and walking back from the far end: never in the wall's cell. */
    @Test
    void theBrakeKeepsTheFeetOutOfTheWallsCell() {
        for (double accel : new double[]{SOUL_SPEED_ACCEL, 0.098}) {
            for (boolean reverse : new boolean[]{false, true}) {
                Pass p = run(true, accel, reverse);
                String name = (accel > 0.1 ? "Soul Speed" : "walking") + (reverse ? ", reverse" : "");
                System.out.println("brake, " + name + ": " + p.status() + " " + p.placed() + " of " + p.cells() + " in "
                    + p.ticks() + " ticks, " + p.wallTicks() + " ticks in the wall's cell");
                assertEquals(TaskStatus.SUCCESS, p.status(), name + "\n" + p.log());
                assertEquals(0, p.wallTicks(), name + ": in the wall's cell at " + p.firstWall() + "\n" + p.log());
                assertEquals(p.cells(), p.placed(), name + "\n" + p.log());
            }
        }
    }
}
