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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cells a lane run leaves to stand spots: a 9-wide airplace lane of full
 * blocks in many materials, as on the colour-suppression layers, its walking line ending short of the tile's edge rows
 * as the lethal-edge clamp leaves it.
 */
class LaneTailTest {
    private static final String[] BLOCKS = {"minecraft:white_concrete", "minecraft:black_concrete", "minecraft:gray_concrete",
        "minecraft:brown_concrete", "minecraft:orange_concrete", "minecraft:yellow_concrete", "minecraft:red_concrete",
        "minecraft:blue_concrete", "minecraft:lime_concrete", "minecraft:cyan_concrete", "minecraft:oak_planks",
        "minecraft:spruce_planks"};
    private static final int ART_Y = 201;

    static final class Fixture {
        final Map<Pos, BlockSpec> world = new HashMap<>();
        final MapArtConfig.BuildCfg build = new MapArtConfig.BuildCfg();
        final LaneSim sim;
        Lane lane;
        int fromSpots, placed, skipped;
        final List<String> spotCells = new ArrayList<>();

        /**
         * A lane of columns x -64..-56 over rows z -64..63, {@code density} of its cells set; the walking line from
         * {@code first} to {@code last} (rows, either order).
         */
        Fixture(int first, int last, double density, long seed, boolean tail) {
            sim = new LaneSim(new LaneSim.Blocks() {
                @Override public BlockSpec get(Pos p) { return world.getOrDefault(p, BlockSpec.AIR); }
                @Override public void set(Pos p, BlockSpec b) { world.put(p, b); }
            }, new LaneSim.Items() {
                @Override public int count(String item) { return 10_000; }
                @Override public boolean take(String item) { return true; }
            }, -64, -64, ART_Y, new AdaptivePacer(1, 1, 4), seed);
            sim.platformBlock = BlockSpec.of("minecraft:glass");
            sim.confirmDelay = 2;
            sim.countLag = 2;
            sim.echoes = true;
            sim.paperWindowTicks = 6;
            sim.paperWindowMax = 9;
            sim.stackSize = 64;
            sim.startTail = tail;
            // the lethal-edge boxes: the tile's last two rows (and what lies south) take no stand cell
            sim.standCells = c -> c.z() <= 61;
            sim.edgeCells = c -> c.z() == 61;
            sim.edgeSpotsLast = true;
            sim.edgeSneak = true;
            build.placeMode = "AIRPLACE";
            build.rotatePlayer = true;
            build.grimRotate = true;
            build.reach = 4.5;
            build.airPlaceRange = 5.0;
            build.pacedLaneWalk = true;
            build.laneStepBack = true;
            build.placeClicksPerTick = 3;
            build.placeClickWindowTicks = 7;
            List<Placement> cells = new ArrayList<>();
            Random pick = new Random(seed * 31);
            boolean south = first <= last;
            for (int i = 0; i < 128; i++) {
                int z = south ? -64 + i : 63 - i;
                for (int d = 0; d <= 4; d++)
                    for (int x : d == 0 ? new int[] {-60} : new int[] {-60 - d, -60 + d}) {
                        if (pick.nextDouble() >= density) continue;
                        // a few common materials and a tail of rare ones
                        String b = BLOCKS[(int) Math.min(BLOCKS.length - 1, Math.abs(pick.nextGaussian()) * 4)];
                        cells.add(new Placement(new Pos(x, ART_Y, z), BlockSpec.of(b), b));
                    }
            }
            Pos from = new Pos(-60, ART_Y, -64 + first);
            Pos to = new Pos(-60, ART_Y, -64 + last);
            lane = new Lane(0, from, to, List.copyOf(cells));
            sim.teleport(from.x() + 0.5, ART_Y, from.z() + 0.5);
        }

        long run() {
            long[] ticks = {0};
            TaskStatus status = sim.runLane(lane, LaneRun.Params.of(build, sim.tileArea()), new PlacementListener() {
                @Override public void placed(Placement p) { placed++; }
                @Override public void placedFromStandSpot(Placement p) {
                    placed++;
                    fromSpots++;
                    spotCells.add((p.pos().x() + 60) + "/" + (p.pos().z() + 64));
                }
                @Override public void skipped(Placement p, String reason) { skipped++; }
            }, 40_000, (t, run) -> {
                ticks[0] = t;
                return true;
            });
            assertEquals(TaskStatus.SUCCESS, status);
            return ticks[0];
        }
    }

    private static boolean logged(Fixture f, String what) {
        return f.sim.log.stream().anyMatch(m -> m.contains(what));
    }

    /**
     * A line that starts three rows inside the tile (walking north from the south edge's clamp, or south from a north
     * one): the edge row's outer column is 3 rows back and 4 across, out of airplace range. Without the switch the walk
     * passes it out of reach and the bot comes back for it from the lane's other end, to a spot on top of its own
     * blocks; with it the cell goes up from a floor spot by the start, before the walk, and nothing is left.
     */
    @Test
    void theCellsBehindAClampedStartGoUpBeforeTheWalk() {
        for (int[] l : new int[][] {{124, 0}, {3, 127}})
            for (double density : new double[] {0.7, 0.15}) {
                Fixture off = new Fixture(l[0], l[1], density, 3, false);
                long before = off.run();
                Fixture on = new Fixture(l[0], l[1], density, 3, true);
                long after = on.run();
                String what = "line " + l[0] + "->" + l[1] + " density " + density + ": " + before + " -> " + after + " ticks";
                System.out.println("start tail, " + what);
                assertTrue(logged(off, "passed out of reach by the walk"), what);
                assertTrue(logged(on, "behind the start are out of its reach"), what);
                assertFalse(logged(on, "passed out of reach by the walk"), what);
                assertFalse(logged(on, "on top of a full block"), what);
                assertEquals(on.lane.placements().size(), on.placed, what);
                assertEquals(0, on.skipped, what);
                assertTrue(after < before - 300, what);
                assertEquals(0, on.sim.boxRejects, what);
                assertEquals(0, on.sim.fellOff, what);
                assertEquals(1, on.sim.maxPlacementsPerTick, "no more clicks a tick than before: " + what);
                for (Pos spot : on.sim.spotGoals) assertTrue(spot.z() <= 61 && spot.z() >= -64, "a stand spot in the edge rows: " + spot);
            }
    }

    /** A line whose start reaches every cell behind it: the switch changes nothing, tick for tick. */
    @Test
    void aStartThatReachesItsCellsIsUnchanged() {
        for (int[] l : new int[][] {{0, 127}, {127, 0}, {0, 124}, {2, 125}}) {
            Fixture off = new Fixture(l[0], l[1], 0.7, 3, false);
            long before = off.run();
            Fixture on = new Fixture(l[0], l[1], 0.7, 3, true);
            assertEquals(before, on.run(), "line " + l[0] + "->" + l[1]);
            assertEquals(off.sim.clicks, on.sim.clicks);
            assertFalse(logged(on, "behind the start"));
        }
    }
}
