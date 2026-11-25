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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lane whose start stays out of reach ({@link LaneRun#standSpotsOnly}, {@code build.laneStartSpots}). Live 2026-10-23,
 * j0015 [4,1] lane 14: the start was the tile's corner cell beside the east trench, entered only by a step down toward
 * the trench, which the edge check refuses; the task failed 6 times and the engine went BLOCKED, twice. With the fallback
 * the lane's cells go from stand spots.
 */
class LaneStartSpotsTest {
    private static final int MIN_X = -64;
    private static final int MIN_Z = -64;
    private static final int ART_Y = 64;
    private static final int LX = 63;
    private static final int Z0 = 63;
    private static final int Z1 = 40;
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
    private LaneRun<LaneSim.Shot> run;

    private static Pos cell(int x, int z) {
        return new Pos(x, ART_Y, z);
    }

    /** A 2-wide lane at the tile's south-east corner, northward from its last row (the live lane 14's shape). */
    private static Lane cornerLane() {
        List<Placement> ps = new ArrayList<>();
        for (int z = Z0; z >= Z1; z--) {
            for (int x = LX - 1; x <= LX; x++) ps.add(new Placement(cell(x, z), BlockSpec.of(CARPET), CARPET));
        }
        return new Lane(14, cell(LX, Z0), cell(LX, Z1), List.copyOf(ps));
    }

    private TaskStatus run(Lane lane, boolean spotsOnly) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        sim.silent = PlaceMode.silentRotation(b);
        // no path to the lane start (its approach goals: range 0 or 1, a y tolerance of 2), stand spots are fine
        sim.noPath = g -> g.yTolerance() == 2 && g.range() <= 1 && Math.abs(g.x() - LX) <= 1 && Math.abs(g.z() - Z0) <= 1;
        sim.teleport(LX - 1.5, ART_Y, Z0 + 0.5);
        return sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, 6000, (t, r) -> {
            if (t == 1 && spotsOnly) r.standSpotsOnly();
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
    void anUnreachableStartFailsTheRunAsBefore() {
        Lane lane = cornerLane();
        TaskStatus s = run(lane, false);
        String log = String.join("\n", sim.log);
        assertEquals(TaskStatus.FAILED, s, log);
        assertEquals(lane.from(), run.unreachedStart(), log);
        assertTrue(run.failure().startsWith("cannot reach lane start"), run.failure());
    }

    @Test
    void standSpotsOnlyPlacesTheWholeLaneWithoutWalkingToItsStart() {
        Lane lane = cornerLane();
        TaskStatus s = run(lane, true);
        String log = String.join("\n", sim.log);
        assertEquals(TaskStatus.SUCCESS, s, log);
        assertEquals(0, missing(lane), log);
        assertTrue(log.contains("all " + lane.placements().size() + " cells from stand spots"), log);
        assertTrue(run.spotWalks() > 0, log);
    }
}
