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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Side-face LOOK clicks ({@link LaneRun} class javadoc "Side faces", {@code build.lookSideFaces}). Live 2026-10-12
 * (2b2t, a 16,384-block tile one cell short): a pointed dripstone cell on the moss floor walled in by four placed full
 * blocks (W gray, E cyan, N gray, S light gray terracotta), three columns from its lane's walking line, the line
 * holding three full blocks ("walking over them"). Every repair pass skipped it within 2 ticks with 0 clicks, 4
 * passes, then BLOCKED. Rebuilt here on the simulated platform with the live build settings (AIRPLACE, paced walk,
 * real rotation): pointed dripstone is taller than a step (collision 11/16 in ZenithProxy's data), so in AIRPLACE mode
 * no stand spot may be next to it, and the floor face inside a 1×1 hole is hidden from every spot two cells out.
 */
class DripstoneHoleTest {
    private static final int MIN_X = -64;
    private static final int MIN_Z = -64;
    private static final int ART_Y = 64;
    /** The lane's walking line (x), the hole three columns east of it (base-relative tile cell 124, 56 as live). */
    private static final int LX = MIN_X + 121;
    private static final int TX = LX + 3;
    private static final int TZ = MIN_Z + 56;
    private static final String DRIPSTONE = "minecraft:pointed_dripstone";
    private static final BlockSpec DRIP = BlockSpec.of(DRIPSTONE,
        Map.of("thickness", "tip", "vertical_direction", "up", "waterlogged", "false"));
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
            return 41; // the bot held 41 pointed dripstone
        }

        @Override
        public boolean take(String item) {
            return true;
        }
    }, MIN_X, MIN_Z, ART_Y, new AdaptivePacer(2, 1, 8), 7);

    private static Pos cell(int x, int z) {
        return new Pos(x, ART_Y, z);
    }

    /** The live neighbourhood: the hole's four walls and three full blocks on the walking line. */
    private void liveNeighbourhood() {
        world.put(cell(TX - 1, TZ), BlockSpec.of("minecraft:gray_terracotta"));
        world.put(cell(TX + 1, TZ), BlockSpec.of("minecraft:cyan_terracotta"));
        world.put(cell(TX, TZ - 1), BlockSpec.of("minecraft:gray_terracotta"));
        world.put(cell(TX, TZ + 1), BlockSpec.of("minecraft:light_gray_terracotta"));
        world.put(cell(LX, MIN_Z + 10), BlockSpec.of("minecraft:gray_terracotta"));
        world.put(cell(LX, MIN_Z + 40), BlockSpec.of("minecraft:gray_terracotta"));
        world.put(cell(LX, MIN_Z + 90), BlockSpec.of("minecraft:gray_terracotta"));
    }

    /** The repair lane: just the hole's cell, the whole lane line as live. */
    private static Lane repairLane(BlockSpec b, String item) {
        return new Lane(13, cell(LX, MIN_Z), cell(LX, MIN_Z + 127), List.of(new Placement(cell(TX, TZ), b, item)));
    }

    /** The live build settings (placeMode AIRPLACE, rotatePlayer true, paced walk). */
    private static MapArtConfig.BuildCfg liveBuild() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertEquals("AIRPLACE", b.placeMode);
        b.rotatePlayer = true;
        b.pacedLaneWalk = true;
        return b;
    }

    private TaskStatus run(Lane lane, MapArtConfig.BuildCfg b, int maxTicks) {
        sim.silent = PlaceMode.silentRotation(b);
        sim.teleport(LX - 9 + 0.5, ART_Y, MIN_Z + 100.5); // the end of the lane before, as live
        return sim.runLane(lane, LaneRun.Params.of(b, sim.tileArea()), NONE, maxTicks);
    }

    private String report() {
        return "ticks=" + sim.lastTicks + " clicks=" + sim.clicks + " side=" + sim.sideClicks + " bad=" + sim.badSideClicks
            + " skips=" + sim.skipReasons + " spots=" + sim.spotGoals + "\nlog:\n" + String.join("\n", sim.log);
    }

    /** The cause, confirmed: without side faces the cell is skipped at once, no stand spot, no click (live). */
    @Test
    void liveHoleIsSkippedWithoutSideFaces() {
        liveNeighbourhood();
        MapArtConfig.BuildCfg b = liveBuild();
        b.lookSideFaces = false;
        assertEquals(TaskStatus.SUCCESS, run(repairLane(DRIP, DRIPSTONE), b, 4000), report());
        assertEquals(Map.of("not reachable: no stand spot in reach and line of sight", 1), sim.skipReasons, report());
        assertTrue(sim.lastTicks <= 2, report());
        assertEquals(0, sim.clicks, report());
        assertTrue(sim.spotGoals.isEmpty(), report());
        // the reason is in the log now (INFO live), once
        long lines = sim.log.stream().filter(l -> l.contains("skipped " + cell(TX, TZ) + " (pointed_dripstone): not "
            + "reachable: no stand spot in reach and line of sight")).count();
        assertEquals(1, lines, report());
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("0 placed, 1 skipped of 1")
            && l.contains("skipped: {not reachable: no stand spot in reach and line of sight=1}")), report());
    }

    /** The fix: the far wall's inner face from a floor spot two cells out, the look pitched down; dripstone points up. */
    @Test
    void liveHoleIsPlacedOnAWallsSideFace() {
        liveNeighbourhood();
        assertEquals(TaskStatus.SUCCESS, run(repairLane(DRIP, DRIPSTONE), liveBuild(), 4000), report());
        assertEquals(DRIP, world.get(cell(TX, TZ)), report());
        assertTrue(sim.skipReasons.isEmpty(), report());
        assertEquals(1, sim.sideClicks, report());
        assertEquals(1, sim.clicks, report());
        assertEquals(0, sim.badSideClicks, report());
        assertTrue(sim.minSidePitch >= Aim.SIDE_MIN_PITCH, report());
        // a floor spot (AIRPLACE: never next to a block taller than a step, never on top of one first)
        assertEquals(1, sim.spotGoals.size(), report());
        Pos spot = sim.spotGoals.getFirst();
        assertEquals(ART_Y, spot.y(), report());
        assertEquals(2, Math.max(Math.abs(spot.x() - TX), Math.abs(spot.z() - TZ)), report());
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("1 LOOK clicks on a neighbor's side face")), report());
    }

    /** Silent looks (the default rotatePlayer false, grimRotate true) check the click against the silent look. */
    @Test
    void sideFaceWithSilentLooks() {
        liveNeighbourhood();
        MapArtConfig.BuildCfg b = liveBuild();
        b.rotatePlayer = false;
        b.grimRotate = true;
        assertTrue(PlaceMode.silentRotation(b));
        assertEquals(TaskStatus.SUCCESS, run(repairLane(DRIP, DRIPSTONE), b, 4000), report());
        assertEquals(DRIP, world.get(cell(TX, TZ)), report());
        assertEquals(1, sim.sideClicks, report());
        assertEquals(0, sim.badSideClicks, report());
        assertEquals(0, sim.realTurns, report());
    }

    /** Lanterns take only the look as well (standing when it is pitched down). */
    @Test
    void lanternHoleUsesASideFace() {
        liveNeighbourhood();
        BlockSpec lantern = BlockSpec.of("minecraft:lantern", Map.of("hanging", "false", "waterlogged", "false"));
        assertEquals(TaskStatus.SUCCESS, run(repairLane(lantern, "minecraft:lantern"), liveBuild(), 4000), report());
        assertEquals(lantern, world.get(cell(TX, TZ)), report());
        assertTrue(sim.sideClicks >= 1, report());
        assertEquals(0, sim.badSideClicks, report());
    }

    /**
     * Glow lichen sticks to the clicked face first: never a side face. It is lower than a step, so it is placed on its
     * floor face from on top of a wall (the last resort spot), as before.
     */
    @Test
    void lichenHoleNeverUsesASideFace() {
        liveNeighbourhood();
        BlockSpec lichen = BlockSpec.of("minecraft:glow_lichen", Map.of("down", "true", "east", "false", "north", "false",
            "south", "false", "up", "false", "waterlogged", "false", "west", "false"));
        assertEquals(TaskStatus.SUCCESS, run(repairLane(lichen, "minecraft:glow_lichen"), liveBuild(), 4000), report());
        assertEquals(lichen, world.get(cell(TX, TZ)), report());
        assertEquals(0, sim.sideClicks, report());
        assertEquals(java.util.Set.of(0), sim.spotWalkRetries, "a last-resort spot: one path request " + report());
    }

    /** A hole next to the walking line, a full lane walked: placed from the walk, not skipped. */
    @Test
    void holeBesideTheWalkIsPlaced() {
        int tz = MIN_Z + 20;
        int tx = LX + 2;
        world.put(cell(tx - 1, tz), BlockSpec.of("minecraft:gray_terracotta"));
        world.put(cell(tx + 1, tz), BlockSpec.of("minecraft:cyan_terracotta"));
        world.put(cell(tx, tz - 1), BlockSpec.of("minecraft:gray_terracotta"));
        world.put(cell(tx, tz + 1), BlockSpec.of("minecraft:light_gray_terracotta"));
        Lane lane = new Lane(2, cell(LX, MIN_Z), cell(LX, MIN_Z + 40),
            List.of(new Placement(cell(tx, tz), DRIP, DRIPSTONE)));
        sim.silent = false;
        sim.teleport(LX + 0.5, ART_Y, MIN_Z + 0.5);
        TaskStatus s = sim.runLane(lane, LaneRun.Params.of(liveBuild(), sim.tileArea()), NONE, 4000);
        assertEquals(TaskStatus.SUCCESS, s, report());
        assertEquals(DRIP, world.get(cell(tx, tz)), report());
        assertTrue(sim.skipReasons.isEmpty(), report());
        assertEquals(0, sim.badSideClicks, report());
    }
}
