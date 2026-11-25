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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stand spots in AIRPLACE mode ({@link LaneRun} class javadoc) on the
 * simulated platform with the "server rejects movement" model ({@link LaneSim}). Live 2026-10-11 (job "ellen", lane 3,
 * its walking line walled by full blocks): Baritone walked the bot across the top of placed full blocks to stand spots
 * and dropped it into a floor cell walled on two sides; 2b2t answered with setback bursts until a safety stop.
 *
 * <p>The lane: 9 wide along z, its walking line already a wall of full blocks (a repair pass: every cell from stand
 * spots), full blocks every third row in the even columns among carpets, and full blocks outside it.
 */
class AirPlaceStandSpotTest {
    private static final int MIN_X = -64;
    private static final int MIN_Z = -64;
    private static final int ART_Y = 64;
    /** The walking line (x) and the rows of the lane. */
    private static final int LX = -40;
    private static final int Z0 = -56;
    private static final int Z1 = -45;
    private static final String WALL = "minecraft:white_terracotta";
    private static final String CARPET = "minecraft:red_carpet";
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
    }, MIN_X, MIN_Z, ART_Y, new AdaptivePacer(2, 1, 8), 7);
    /** Where the bot's feet were when the server accepted each full block. */
    private final Map<Pos, Pos> solidFrom = new HashMap<>();
    private LaneRun<LaneSim.Shot> run;

    private static Pos cell(int x, int z) {
        return new Pos(x, ART_Y, z);
    }

    private static boolean solid(int c, int row) {
        return c % 2 == 0 && row % 3 == 0;
    }

    /** The walled lane (class javadoc); the world gets its wall and the full blocks around it. */
    private Lane walledLane() {
        List<Placement> ps = new ArrayList<>();
        for (int z = Z0; z <= Z1; z++) {
            world.put(cell(LX, z), BlockSpec.of(WALL));
            int row = z - Z0;
            if (row % 4 == 1) {
                world.put(cell(LX - 6, z), BlockSpec.of(WALL));
                world.put(cell(LX + 6, z), BlockSpec.of(WALL));
            }
            for (int c = -4; c <= 4; c++) {
                if (c == 0) continue;
                String id = solid(c, row) ? WALL : CARPET;
                ps.add(new Placement(cell(LX + c, z), BlockSpec.of(id), id));
            }
        }
        return new Lane(3, cell(LX, Z0), cell(LX, Z1), List.copyOf(ps));
    }

    private TaskStatus run(Lane lane, int maxTicks) {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg(); // the defaults: AIRPLACE, silent looks
        assertEquals("AIRPLACE", b.placeMode);
        // the stand spots of a walled lane: the walk over the line's blocks (LaneReentryTest) is off here
        b.walkWalledLanes = false;
        sim.silent = PlaceMode.silentRotation(b);
        sim.onAccept = p -> {
            if (LaneSim.collisionTopOf(p.block()) > 0.6) {
                solidFrom.put(p.pos(), new Pos((int) Math.floor(sim.x), (int) Math.floor(sim.y + 0.1251),
                    (int) Math.floor(sim.z)));
            }
        };
        return sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, maxTicks, (t, r) -> {
            run = r;
            return true;
        });
    }

    private List<Pos> missing(Lane lane, Set<Pos> except) {
        List<Pos> out = new ArrayList<>();
        for (Placement p : lane.placements()) {
            if (except.contains(p.pos())) continue;
            if (!TopFaceRules.equivalent(p.block(), world.getOrDefault(p.pos(), BlockSpec.AIR))) out.add(p.pos());
        }
        return out;
    }

    private String report() {
        return "ticks=" + sim.lastTicks + " spotWalks=" + (run == null ? -1 : run.spotWalks()) + " baritoneWalks="
            + sim.baritoneWalks + " baritone=" + Math.round(sim.baritoneDistance) + " walked=" + Math.round(sim.walked)
            + " maxFeetY=" + sim.maxFeetY + " setbacks=" + sim.setbacks + " stalled=" + sim.stalledPaths
            + " refused=" + sim.refusedWalks + " " + sim.refusedPaths + " narrow=" + sim.narrowStandTicks + " "
            + sim.narrowStands + " skips=" + sim.skipReasons + "\nspots=" + sim.spotGoals + "\nlog:\n"
            + String.join("\n", sim.log) + "\ntrail:\n" + String.join("\n", sim.trail);
    }

    /** Every full block was accepted with the bot's feet off the floor or at least 2 cells from it (Chebyshev). */
    private void assertNoWallBesideTheBot() {
        for (var e : solidFrom.entrySet()) {
            Pos at = e.getValue();
            int d = Math.max(Math.abs(at.x() - e.getKey().x()), Math.abs(at.z() - e.getKey().z()));
            assertTrue(at.y() > ART_Y || d >= 2, "full block " + e.getKey() + " placed with the bot at " + at + "\n"
                + report());
        }
    }

    @Test
    void walledLaneIsPlacedFromFloorSpotsWithoutClimbing() {
        Lane lane = walledLane();
        sim.teleport(LX - 6.5, ART_Y, Z0 - 2.5);
        TaskStatus s = run(lane, 30_000);
        String rep = report();
        assertEquals(TaskStatus.SUCCESS, s, rep);
        assertEquals(List.of(), missing(lane, Set.of()), rep);
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("walking line walled")), rep);
        assertTrue(sim.maxFeetY <= ART_Y + 0.5, "the bot climbed\n" + rep);
        assertEquals(0, sim.narrowStandTicks, "stood in a pocket or a 1-wide slot\n" + rep);
        assertEquals(0, sim.setbacks, rep);
        assertEquals(0, sim.pocketTicks, rep);
        assertEquals(0, sim.boxRejects, rep);
        assertEquals(0, run.lastResorts(), rep);
        assertEquals(0, run.floorWalkFails(), rep);
        assertTrue(run.spotWalks() > 0, rep);
        assertEquals(lane.placements().stream().filter(p -> p.block().id().equals(WALL)).count(), solidFrom.size(), rep);
        assertNoWallBesideTheBot();
        // the floor route goes round the ends of the wall: walks to the far side take legs, and none was refused
        assertTrue(sim.spotGoals.stream().anyMatch(p -> p.x() > LX) && sim.spotGoals.stream().anyMatch(p -> p.x() < LX),
            rep);
        assertEquals(0, sim.refusedWalks, rep);
        System.out.println("STAND-SPOT-SIM walled lane: " + lane.placements().size() + " cells, ticks=" + sim.lastTicks
            + " spotWalks=" + run.spotWalks() + " baritoneWalks=" + sim.baritoneWalks + " baritone="
            + Math.round(sim.baritoneDistance) + " walked=" + Math.round(sim.walked));
    }

    @Test
    void ghostBlockFromARejectedPlacementCausesNoSetbackLoop() {
        Lane lane = walledLane();
        // a full block the server refused that the proxy kept, and one more the proxy shows beside the lane
        Pos rejected = cell(LX + 2, Z0 + 6);
        assertTrue(solid(2, 6));
        sim.ghost(rejected, BlockSpec.of(WALL));
        sim.ghost(cell(LX - 5, Z0 + 4), BlockSpec.of(WALL));
        sim.teleport(LX - 6.5, ART_Y, Z0 - 2.5);
        TaskStatus s = run(lane, 30_000);
        String rep = report();
        assertEquals(TaskStatus.SUCCESS, s, rep);
        assertTrue(sim.lastTicks <= 12_000, rep);
        assertEquals(List.of(), missing(lane, Set.of(rejected)), "every other cell placed\n" + rep);
        assertTrue(sim.setbacks <= 2, "setback loop\n" + rep);
        assertTrue(sim.maxFeetY <= ART_Y + 0.5, rep);
        assertNoWallBesideTheBot();
    }

    @Test
    void serverOnlyBlockOnTheWayToAStandSpotStallsTheWalkButNotTheLane() {
        Lane lane = walledLane();
        // a row of blocks the proxy does not show across the bot's way to the lane
        for (int x = LX - 9; x <= LX - 3; x++) sim.hiddenBlock(cell(x, Z0 - 1), BlockSpec.of(WALL));
        sim.stallTicks = 60;
        sim.teleport(LX - 6.5, ART_Y, Z0 - 2.5);
        TaskStatus s = run(lane, 30_000);
        String rep = report();
        assertEquals(TaskStatus.SUCCESS, s, rep);
        assertTrue(sim.setbacks >= 1, "the bot is set back while walking\n" + rep);
        assertTrue(sim.stalledPaths >= 1, rep);
        assertTrue(sim.resyncedCells >= 1, rep);
        assertTrue(sim.setbacks <= 12, "setback loop\n" + rep);
        assertTrue(sim.lastTicks <= 15_000, rep);
        assertEquals(List.of(), missing(lane, Set.of()), rep);
        assertNoWallBesideTheBot();
    }

    @Test
    void standSpotsKeepAwayFromSetbackCells() {
        Lane lane = walledLane();
        List<Pos> avoid = List.of(cell(LX - 3, Z0 + 3), cell(LX + 2, Z0 + 8), cell(LX - 5, Z0 + 9));
        sim.avoid.addAll(avoid);
        sim.teleport(LX - 6.5, ART_Y, Z0 - 2.5);
        TaskStatus s = run(lane, 30_000);
        String rep = report();
        assertEquals(TaskStatus.SUCCESS, s, rep);
        assertEquals(List.of(), missing(lane, Set.of()), rep);
        assertTrue(sim.spotGoals.size() > 0, rep);
        for (Pos p : sim.spotGoals) assertNull(LaneRun.nearAvoid(p, avoid), "stand spot next to a setback cell: " + p);
        assertEquals(0, sim.refusedWalks, "the floor routes keep away from them as well\n" + rep);
        assertTrue(sim.maxFeetY <= ART_Y + 0.5, rep);
    }

    /**
     * The server accepts no movement (the bot cannot move): every walk to a stand spot stalls and fails. Before the
     * cap every cell tried {@link LaneRun#MAX_SPOTS} spots (here 4 × 96 walks); now the lane gives the rest up after
     * {@link LaneRun#MAX_FRUITLESS_SPOTS} spots in a row without a placement, and a spot is never picked more than
     * {@link LaneRun#SPOT_REPICKS} times without a placement from it.
     */
    @Test
    void aBotThatCannotMoveDoesNotLoopOverStandSpots() {
        Lane lane = walledLane();
        sim.frozen = true;
        sim.stallTicks = 20;
        sim.teleport(LX - 6.5, ART_Y, Z0 - 7.5); // nothing in reach from here
        TaskStatus s = run(lane, 60_000);
        String rep = report();
        assertEquals(TaskStatus.SUCCESS, s, rep);
        assertTrue(run.spotWalks() <= LaneRun.MAX_FRUITLESS_SPOTS, rep);
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("stand spots in a row placed nothing")), rep);
        assertEquals(lane.placements().size(), run.skipped(), rep);
        assertTrue(sim.lastTicks <= 6_000, rep);
        Map<Pos, Integer> picks = new HashMap<>();
        for (Pos p : sim.spotGoals) picks.merge(new Pos(p.x(), 0, p.z()), 1, Integer::sum);
        for (var e : picks.entrySet()) {
            assertTrue(e.getValue() <= LaneRun.SPOT_REPICKS, "stand spot " + e.getKey() + " picked " + e.getValue()
                + " times\n" + rep);
        }
    }

    // ------------------------------------------------------------------------------------------ path checks

    @Test
    void pathRefusalRefusesClimbingInsideTheStandAreaAndSetbackCells() {
        LaneRun.Area area = new LaneRun.Area(0, 0, 127, 127);
        List<Pos> floor = List.of(new Pos(5, 64, 5), new Pos(6, 64, 5), new Pos(7, 64, 5));
        assertNull(LaneRun.pathRefusal(floor, area, Set.of(), true));
        List<Pos> climb = List.of(new Pos(5, 64, 5), new Pos(6, 65, 5), new Pos(7, 64, 5));
        assertEquals("climbs onto a block at 6,64,5", LaneRun.pathRefusal(climb, area, Set.of(), true));
        assertNull(LaneRun.pathRefusal(climb, area, Set.of(), false), "only stand-spot walks to floor spots");
        // stepping up outside the stand area (onto the platform from the ground around it) is not climbing onto art
        List<Pos> outside = List.of(new Pos(-2, 63, 5), new Pos(-1, 64, 5), new Pos(0, 64, 5));
        assertNull(LaneRun.pathRefusal(outside, area, Set.of(), true));

        Set<Pos> avoid = Set.of(new Pos(8, 64, 6));
        String past = LaneRun.pathRefusal(floor, area, avoid, false);
        assertNotNull(past);
        assertEquals("passes the setback cell 8,64,6", past);
        // starting next to it: walking out is allowed, coming back is not
        List<Pos> out = List.of(new Pos(9, 64, 6), new Pos(10, 64, 6), new Pos(11, 64, 6));
        assertNull(LaneRun.pathRefusal(out, area, avoid, true));
        List<Pos> back = List.of(new Pos(9, 64, 6), new Pos(10, 64, 6), new Pos(9, 64, 7));
        assertEquals("passes the setback cell 8,64,6", LaneRun.pathRefusal(back, area, avoid, true));
        // one level up or down still counts; two levels apart does not
        assertNotNull(LaneRun.nearAvoid(new Pos(7, 65, 5), avoid));
        assertNull(LaneRun.nearAvoid(new Pos(7, 66, 5), avoid));
        assertNull(LaneRun.nearAvoid(new Pos(6, 64, 6), avoid));
    }
}
