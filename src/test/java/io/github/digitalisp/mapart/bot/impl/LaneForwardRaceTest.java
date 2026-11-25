package io.github.digitalisp.mapart.bot.impl;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forward race (forward clear path; build.laneForwardClearPath): riding
 * 0.2-0.25 off the line, the box pokes into the side column; a side cell clicked just ahead of it went up as the box
 * entered its cell (live 2026-10-27, soul sand: the server's own teleport, then Grim's setback burst).
 */
class LaneForwardRaceTest {
    private static final String[] CARPETS = {"minecraft:white_carpet", "minecraft:black_carpet",
        "minecraft:gray_carpet", "minecraft:light_gray_carpet"};
    private static final int ART_Y = 64;
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement p) { }
        @Override public void skipped(Placement p, String reason) { }
    };

    private static final class Fixture {
        final Map<Pos, BlockSpec> world = new HashMap<>();
        final MapArtConfig.BuildCfg build = new MapArtConfig.BuildCfg();
        final LaneSim sim;
        final Lane lane;

        /** Live 2026-10-27: Soul Speed, the paced walk with 2 clicks a tick, 3-tick answers, {@code offset} off the line. */
        Fixture(int seed, double offset, boolean clearPath, boolean north) {
            this(seed, offset, clearPath, north, 0.2225);
        }

        /** The same with {@code accel} a tick of movement keys adds (0.098 walking, 0.2225 Soul Speed). */
        Fixture(int seed, double offset, boolean clearPath, boolean north, double accel) {
            sim = new LaneSim(new LaneSim.Blocks() {
                @Override public BlockSpec get(Pos p) { return world.getOrDefault(p, BlockSpec.AIR); }
                @Override public void set(Pos p, BlockSpec b) { world.put(p, b); }
            }, new LaneSim.Items() {
                @Override public int count(String item) { return 10_000; }
                @Override public boolean take(String item) { return true; }
            }, -64, -64, ART_Y, new AdaptivePacer(1, 1, 4), 7);
            sim.confirmDelay = 3;
            sim.countLag = 4;
            sim.echoes = true;
            sim.silent = true;
            sim.paperWindowTicks = 6;
            sim.paperWindowMax = 9;
            sim.groundAccel = accel;
            sim.stepBackClearPath = true;
            sim.forwardClearPath = clearPath;
            Random jitter = new Random(seed);
            sim.clickJitterTicks = s -> jitter.nextInt(3);
            build.pacedLaneWalk = true;
            build.placeAdaptiveWindow = false;
            build.placeClicksPerTick = 2;
            build.placeClickWindowTicks = 7;
            build.laneStepBack = true;
            List<Placement> cells = new ArrayList<>();
            Random pick = new Random(seed * 31L);
            for (int z = -62; z <= 57; z++) {
                for (int x = -64; x <= -56; x++) {
                    String c = CARPETS[pick.nextInt(CARPETS.length)];
                    cells.add(new Placement(new Pos(x, ART_Y, z), BlockSpec.of(c), c));
                }
            }
            Pos from = new Pos(-60, ART_Y, north ? 58 : -63);
            Pos to = new Pos(-60, ART_Y, north ? -63 : 58);
            lane = new Lane(0, from, to, List.copyOf(cells));
            sim.teleport(from.x() + 0.5 + offset, ART_Y, from.z() + 0.5);
        }

        void run() {
            TaskStatus status = sim.runLane(lane, LaneRun.Params.of(build, sim.tileArea()), NONE, 20_000);
            assertEquals(TaskStatus.SUCCESS, status, why());
            assertEquals(lane.placements().size(), sim.accepted, why());
            for (Placement p : lane.placements()) assertEquals(p.block(), world.get(p.pos()), why());
            assertTrue(sim.skipReasons.isEmpty(), why());
            assertEquals(0, sim.boxRejects, why());
            assertEquals(0, sim.wrongClicks, why());
            assertEquals(0, sim.fellOff, why());
        }

        String why() {
            return "ticks=" + sim.lastTicks + " placed=" + sim.accepted + " side race=" + sim.sideRaceEntries + " "
                + sim.sideRaceHits + " in flight=" + sim.inFlightEntries + " " + sim.inFlightHits + "\n"
                + String.join("\n", sim.log);
        }
    }

    private record Totals(long ticks, long race, long inFlight) { }

    private static Totals lanes(boolean clearPath) {
        return lanes(clearPath, new double[]{0.22, -0.22}, 0.2225);
    }

    /** 10 seeds × {@code offsets} × both lane directions. */
    private static Totals lanes(boolean clearPath, double[] offsets, double accel) {
        long ticks = 0, race = 0, inFlight = 0;
        for (int seed = 1; seed <= 10; seed++) {
            for (double offset : offsets) {
                for (boolean north : new boolean[]{false, true}) {
                    Fixture f = new Fixture(seed, offset, clearPath, north, accel);
                    f.run();
                    ticks += f.sim.lastTicks;
                    race += f.sim.sideRaceEntries;
                    inFlight += f.sim.inFlightEntries;
                }
            }
        }
        return new Totals(ticks, race, inFlight);
    }

    @Test
    void sideCellsAheadOfTheBoxWaitUntilItHasPassedThem() {
        Totals off = lanes(false);
        Totals on = lanes(true);
        System.out.printf("forward race, 40 lanes of 1080, 0.22 off the line: off %d ticks, %d side-cell race entries,"
            + " %d into blocks in flight; on %d ticks, %d, %d%n", off.ticks, off.race, off.inFlight, on.ticks, on.race,
            on.inFlight);
        assertTrue(off.race > 0, "the switch off reproduces the live race");
        assertEquals(0, on.race, "no move into a side cell in flight or just shown");
        assertEquals(0, on.inFlight);
        assertTrue(on.ticks <= off.ticks * 1.05, "throughput within 5%: off " + off.ticks + ", on " + on.ticks);
    }

    @Test
    void throughputStaysWithinFivePercentOnAndOffTheLine() {
        for (double accel : new double[]{0.2225, 0.098}) {
            Totals off = lanes(false, new double[]{0, 0.1, -0.15}, accel);
            Totals on = lanes(true, new double[]{0, 0.1, -0.15}, accel);
            System.out.printf("forward clear path throughput, 60 lanes of 1080, accel %.4f: off %d ticks (%d race entries),"
                + " on %d ticks (%d)%n", accel, off.ticks, off.race, on.ticks, on.race);
            assertEquals(0, on.race);
            assertTrue(on.ticks <= off.ticks * 1.05, "throughput within 5%: off " + off.ticks + ", on " + on.ticks);
        }
    }
}
