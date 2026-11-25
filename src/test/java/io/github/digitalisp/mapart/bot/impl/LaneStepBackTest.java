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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Short corrections of the walking line, with drops, refusals and the same safety rules as the lane walk. */
class LaneStepBackTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final String[] CARPETS = {WHITE, "minecraft:black_carpet", "minecraft:gray_carpet",
        "minecraft:light_gray_carpet"};
    private static final int ART_Y = 64;
    private static final PlacementListener NONE = new PlacementListener() {
        @Override public void placed(Placement p) { }
        @Override public void skipped(Placement p, String reason) { }
    };

    private static final class Fixture {
        final Map<Pos, BlockSpec> world = new HashMap<>();
        final MapArtConfig.BuildCfg build = new MapArtConfig.BuildCfg();
        final LaneSim sim;
        Lane lane;
        int stepBacks;

        Fixture(boolean on) {
            this(on, false);
        }

        Fixture(boolean on, boolean north) {
            this(on, north, 0);
        }

        /** {@code colours} > 0: each cell one of the first that many {@link #CARPETS}, picked with that seed. */
        Fixture(boolean on, boolean north, int colours) {
            sim = new LaneSim(new LaneSim.Blocks() {
                @Override public BlockSpec get(Pos p) { return world.getOrDefault(p, BlockSpec.AIR); }
                @Override public void set(Pos p, BlockSpec b) { world.put(p, b); }
            }, new LaneSim.Items() {
                @Override public int count(String item) { return 10_000; }
                @Override public boolean take(String item) { return true; }
            }, -64, -64, ART_Y, new AdaptivePacer(1, 1, 4), 7);
            sim.confirmDelay = 20;
            sim.countLag = 4;
            sim.echoes = true;
            sim.silent = true;
            sim.paperWindowTicks = 6;
            sim.paperWindowMax = 9;
            Random jitter = new Random(5);
            sim.clickJitterTicks = seq -> jitter.nextInt(3);
            build.pacedLaneWalk = false;
            build.placeAdaptiveWindow = false;
            build.placeClicksPerTick = 2;
            build.placeClickWindowTicks = 7;
            build.laneStepBack = on;
            List<Placement> cells = new ArrayList<>();
            Random pick = new Random(colours * 31L);
            for (int z = -62; z <= 57; z++) {
                for (int x = -64; x <= -56; x++) {
                    String c = colours > 0 ? CARPETS[pick.nextInt(Math.min(colours, CARPETS.length))] : WHITE;
                    cells.add(new Placement(new Pos(x, ART_Y, z), BlockSpec.of(c), c));
                }
            }
            Pos from = new Pos(-60, ART_Y, north ? 58 : -63);
            Pos to = new Pos(-60, ART_Y, north ? -63 : 58);
            lane = new Lane(0, from, to, List.copyOf(cells));
            sim.teleport(from.x() + 0.5, ART_Y, from.z() + 0.5);
        }

        void run() {
            run((t, run) -> true);
        }

        void run(java.util.function.BiPredicate<Long, LaneRun<LaneSim.Shot>> control) {
            TaskStatus status = sim.runLane(lane, LaneRun.Params.of(build, sim.tileArea()), NONE, 20_000,
                (t, run) -> {
                    stepBacks = run.stepBacks();
                    if (run.steppingBack()) backingTicks.add(t);
                    return control.test(t, run);
                });
            assertEquals(TaskStatus.SUCCESS, status, why());
        }

        final java.util.Set<Long> backingTicks = new java.util.HashSet<>();

        /** Moves into a block in flight ({@link LaneSim#inFlightEntries}) in a tick of a step back or the one after. */
        long inFlightWhileBacking() {
            return sim.inFlightEntryTicks.stream()
                .filter(t -> backingTicks.contains(t) || backingTicks.contains(t - 1)).count();
        }

        /**
         * Live 2026-10-23 conditions: Soul Speed on soul sand (0.49 a tick), the paced walk, four carpet colours, the
         * answer {@code delay} ticks after the click (live acks p50 2, p95 3 ticks), arrival jitter 0-2 ticks.
         */
        void soulSand(int delay, boolean clearPath) {
            sim.groundAccel = 0.2225;
            sim.confirmDelay = delay;
            sim.stepBackClearPath = clearPath;
            // this switch alone: the forward clear path's key guard also stops the walk backing into a block in flight
            sim.forwardClearPath = false;
            build.pacedLaneWalk = true;
        }

        void singleFailure(Pos target, boolean refuse) {
            sim.paperWindowTicks = 0;
            sim.clickJitterTicks = seq -> 0;
            if (refuse) sim.refuse = target::equals;
            else {
                int[] clicks = {0};
                sim.limiter = p -> p.equals(target) && clicks[0]++ < 2;
            }
        }

        int left() {
            Pattern p = Pattern.compile(": (\\d+) cells to place from stand spots");
            return sim.log.stream().map(p::matcher).filter(m -> m.find()).mapToInt(m -> Integer.parseInt(m.group(1))).sum();
        }

        String why() {
            return "ticks=" + sim.lastTicks + " placed=" + sim.accepted + " left=" + left() + " step backs="
                + stepBacks + " drops=" + sim.limiterDrops + " into blocks in flight=" + sim.inFlightEntries + " "
                + sim.inFlightHits + "\n" + String.join("\n", sim.log);
        }

        void clean() {
            assertEquals(lane.placements().size(), sim.accepted, why());
            for (Placement p : lane.placements()) assertEquals(p.block(), world.get(p.pos()), why());
            assertTrue(sim.skipReasons.isEmpty(), why());
            assertEquals(0, sim.boxRejects, why());
            assertEquals(0, sim.farRejects, why());
            assertEquals(0, sim.wrongClicks, why());
            assertEquals(0, sim.fellOff, why());
        }
    }

    @Test
    void dropsAreFixedDuringTheWalk() {
        Fixture off = new Fixture(false);
        Fixture on = new Fixture(true);
        off.run();
        on.run();
        System.out.printf("lane step back, 1080 cells: off %d ticks, %d left, %d drops; on %d ticks, %d left, %d drops, %d steps%n",
            off.sim.lastTicks, off.left(), off.sim.limiterDrops, on.sim.lastTicks, on.left(), on.sim.limiterDrops,
            on.stepBacks);
        off.clean();
        on.clean();
        // Measured on the unmodified LaneRun at HEAD: the switch off retains the exact baseline.
        assertEquals(1602, off.sim.lastTicks, off.why());
        assertEquals(5, off.left(), off.why());
        assertEquals(23, off.sim.limiterDrops, off.why());
        assertEquals(0, off.stepBacks, off.why());
        assertEquals(0, on.left(), on.why());
        assertTrue(on.sim.lastTicks * 4 < off.sim.lastTicks * 3, on.why());
        assertTrue(on.stepBacks > 0 && on.stepBacks < off.left(), "one step serves several missed cells\n" + on.why());
    }

    @Test
    void pendingAnswersHoldThePacedWalkBeforeACorrectionIsNeeded() {
        for (boolean on : new boolean[]{false, true}) {
            Fixture f = new Fixture(on);
            f.build.pacedLaneWalk = true;
            f.run();
            f.clean();
            assertEquals(1045, f.sim.lastTicks, f.why());
            assertEquals(0, f.left(), f.why());
            assertEquals(0, f.stepBacks, f.why());
            assertTrue(f.sim.log.stream().anyMatch(l -> l.contains("ack=row")), f.why());
        }
    }

    @Test
    void aRefusedCorrectionGoesToStandSpotsWithoutOscillating() {
        Pos target = new Pos(-64, ART_Y, -40);
        Fixture f = new Fixture(true);
        f.singleFailure(target, true);
        f.run();
        System.out.printf("refused correction: %d ticks, %d left, %d step, %d refusals, %d placed%n",
            f.sim.lastTicks, f.left(), f.stepBacks, f.sim.refusals, f.sim.accepted);
        assertEquals(1, f.stepBacks, f.why());
        assertEquals(1, f.left(), f.why());
        assertEquals(1079, f.sim.accepted, f.why());
        assertEquals(1, f.sim.skipReasons.values().stream().mapToInt(Integer::intValue).sum(), f.why());
        assertTrue(f.sim.refusals <= LaneRun.MAX_SENDS + 1, f.why());
        assertEquals(0, f.sim.fellOff, f.why());
    }

    @Test
    void aSecondDropGoesToStandSpotsWithoutAnotherCorrection() {
        Fixture f = new Fixture(true);
        f.singleFailure(new Pos(-64, ART_Y, -40), false);
        f.run();
        f.clean();
        assertEquals(1, f.stepBacks, f.why());
        assertEquals(1, f.left(), f.why());
        assertEquals(2, f.sim.limiterDrops, f.why());
    }

    @Test
    void refusedCellsBehindTheWalkStayWithinTheLaneLimit() {
        Fixture f = new Fixture(true);
        f.sim.confirmDelay = 30;
        // the first click of every other cell of the two outer columns is refused: each waits out its retry delay
        java.util.Set<Pos> refused = new java.util.HashSet<>();
        f.sim.refuse = p -> (p.x() == -64 || p.x() == -56) && Math.floorMod(p.z(), 2) == 0 && refused.add(p);
        f.run();
        f.clean();
        assertTrue(f.stepBacks > 0 && f.stepBacks <= LaneRun.STEP_BACK_LIMIT, f.why());
    }

    @Test
    void slowAnswersNeedMoreThanEightCorrectionsAndLeaveNothing() {
        // live 2026-10-13: the old limit of 8 was used up in the first half of most lanes
        Fixture f = new Fixture(true);
        f.sim.confirmDelay = 30;
        f.sim.forwardClearPath = false; // with it 8 step backs do (side cells placed once passed, fewer missed)
        f.run();
        f.clean();
        assertTrue(f.stepBacks > 8 && f.stepBacks < LaneRun.STEP_BACK_LIMIT, f.why());
        assertEquals(0, f.left(), f.why());
    }

    @Test
    void correctionsWorkInEitherLaneDirection() {
        Fixture f = new Fixture(true, true);
        f.run();
        f.clean();
        assertTrue(f.stepBacks > 0, f.why());
        assertEquals(0, f.left(), f.why());
    }

    @Test
    void edgesAndForbiddenCellsPreventBackingIntoThem() {
        for (int hazard = 0; hazard < 3; hazard++) {
            Fixture f = new Fixture(true);
            f.singleFailure(new Pos(-64, ART_Y, -40), false);
            if (hazard == 0) f.sim.edgeCells = p -> p.z() >= -40 && p.z() <= -35;
            else if (hazard == 1) f.sim.standCells = p -> p.x() != -60 || p.z() < -40 || p.z() > -35;
            else f.sim.avoid.add(new Pos(-60, ART_Y, -37));
            f.run();
            f.clean();
            assertEquals(0, f.stepBacks, f.why());
            assertEquals(1, f.left(), f.why());
        }
    }

    @Test
    void aCorrectionCanParkAndResumeWithoutSendingWhileParked() {
        Fixture f = new Fixture(true);
        f.singleFailure(new Pos(-64, ART_Y, -40), false);
        long[] parkedAt = {-1};
        int[] clicks = {-1};
        boolean[] requested = {false};
        f.run((t, run) -> {
            if (!requested[0] && run.stepBacks() > 0) {
                requested[0] = true;
                clicks[0] = f.sim.clicks;
                run.requestPark();
            }
            if (!run.parked()) return true;
            assertEquals(clicks[0], f.sim.clicks, "no click after the park request");
            if (parkedAt[0] < 0) parkedAt[0] = t;
            if (t - parkedAt[0] < 100) return false;
            run.resume();
            return true;
        });
        f.clean();
        assertTrue(parkedAt[0] > 0, f.why());
        assertEquals(1, f.stepBacks, f.why());
    }


    @Test
    void aWalledLineKeepsItsWalkOverBehaviour() {
        Fixture off = new Fixture(false);
        Fixture on = new Fixture(true);
        for (Fixture f : List.of(off, on)) {
            f.lane = new Lane(0, f.lane.from(), f.lane.to(), f.lane.placements().stream()
                .filter(p -> p.pos().x() != -60).toList());
            for (int z = -63; z <= 58; z++)
                f.world.put(new Pos(-60, ART_Y, z), BlockSpec.of("minecraft:white_wool"));
            f.sim.teleport(-59.5, ART_Y + 1, -62.5);
            f.run();
            f.clean();
            assertEquals(0, f.stepBacks, f.why());
        }
        assertEquals(off.sim.lastTicks, on.sim.lastTicks, on.why());
        assertEquals(off.left(), on.left(), on.why());
        assertEquals(off.sim.jumps, on.sim.jumps, on.why());
    }

    @Test
    void subTickDispatchUsesTheSameCorrectionAndClickGuards() {
        Fixture f = new Fixture(true);
        f.singleFailure(new Pos(-64, ART_Y, -40), false);
        f.build.placeSubTickDispatch = true;
        f.sim.millisecondArrivals = true;
        f.run();
        f.clean();
        assertEquals(1, f.stepBacks, f.why());
        assertEquals(1, f.left(), f.why());
        assertTrue(f.sim.timedClicks.stream().anyMatch(c -> c.sendMillis() % 50 != 0), f.why());
    }


    @Test
    void aStepBackBacksIntoBlocksInFlightUnlessItsPathIsKeptClear() {
        // live 2026-10-23: the corridor cell 0.05 behind the box goes up as the step back starts, and the box backs
        // into it before its block update arrives (the server's own collision teleport, then Grim's setbacks)
        int offSteps = 0, onSteps = 0;
        long offInto = 0, offTicks = 0, onTicks = 0, offLeft = 0, onLeft = 0;
        for (int seed = 1; seed <= 20; seed++) {
            Fixture off = new Fixture(true, false, seed);
            off.soulSand(3, false);
            off.run();
            off.clean();
            Fixture on = new Fixture(true, false, seed);
            on.soulSand(3, true);
            on.run();
            on.clean();
            assertEquals(0, on.sim.inFlightEntries, on.why());
            offSteps += off.stepBacks;
            onSteps += on.stepBacks;
            offInto += off.inFlightWhileBacking();
            offTicks += off.sim.lastTicks;
            onTicks += on.sim.lastTicks;
            offLeft += off.left();
            onLeft += on.left();
        }
        System.out.printf("soul sand, 20 lanes of 1080: off %d ticks, %d step backs, %d into blocks in flight, %d left;"
            + " clear path %d ticks, %d step backs, 0 into blocks in flight, %d left%n", offTicks, offSteps, offInto,
            offLeft, onTicks, onSteps, onLeft);
        assertTrue(offSteps > 0 && onSteps > 0, "step backs in both runs");
        assertTrue(offInto > 0, "the switch off reproduces the live setbacks' cause");
        assertTrue(onTicks <= offTicks * 1.02, "no slower: off " + offTicks + ", on " + onTicks);
    }

    @Test
    void aClearPathWaitsForTheBlockBehindAndKeepsEveryCell() {
        // the step back fixture above (20-tick answers): every step back with blocks in flight right behind the box
        Fixture off = new Fixture(true);
        off.sim.groundAccel = 0.2225;
        off.run();
        off.clean();
        Fixture on = new Fixture(true);
        on.sim.groundAccel = 0.2225;
        on.sim.stepBackClearPath = true;
        on.run();
        on.clean();
        System.out.printf("step back, 20-tick answers, Soul Speed: off %d ticks, %d step backs, %d into blocks in flight;"
            + " clear path %d ticks, %d step backs, %d%n", off.sim.lastTicks, off.stepBacks, off.inFlightWhileBacking(),
            on.sim.lastTicks, on.stepBacks, on.sim.inFlightEntries);
        assertTrue(off.inFlightWhileBacking() > 0, off.why());
        assertEquals(0, on.sim.inFlightEntries, on.why());
        assertEquals(0, on.left(), on.why());
        assertTrue(on.stepBacks > 0, on.why());
        assertTrue(on.sim.log.stream().anyMatch(l -> l.contains("ticks for blocks in flight")), on.why());
    }
}
