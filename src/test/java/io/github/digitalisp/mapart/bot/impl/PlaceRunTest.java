package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.AnvilTowerSite;
import io.github.digitalisp.mapart.testutil.ContainerSim;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;

/**
 * Placing a replacement anvil ({@link PlaceRun} on a simulated world): the support face, the stand spot, look first and
 * click on the next tick, the sneak-click on the anvil tower's crafting table, off the art layer but in the clear sweep
 * (the moss tower in the supply column, {@link AnvilTowerSite}).
 */
class PlaceRunTest {
    private static final double REACH = 4.3;
    private static final String ANVIL = "minecraft:anvil";
    private static final BlockSpec ANVIL_BLOCK = BlockSpec.of(ANVIL, Map.of("facing", "north"));
    private static final BlockSpec WALL = BlockSpec.of("minecraft:polished_blackstone_wall");
    private static final BlockSpec TABLE = BlockSpec.of("minecraft:crafting_table");

    /**
     * The anvil tower (synthetic, the shape of the new site): walkway floor at y 63 (feet 64), a crafting table at
     * walkway+1, the anvil cell above it at {@code (0, 65, 0)}, walls north and west of the tower, a chest south; the
     * walkway open to the east. Nothing in reach to place against but the crafting table.
     */
    private static Pos tower(ContainerSim s) {
        s.fill(-8, 63, -8, 8, 63, 8, STONE);
        s.set(0, 64, 0, TABLE);
        s.fill(0, 64, -1, 0, 67, -1, WALL);
        s.fill(-1, 64, 0, -1, 67, 0, WALL);
        s.fill(0, 64, 1, 0, 66, 1, ContainerSim.chest("east", "single"));
        s.placeItem = ANVIL_BLOCK;
        return new Pos(0, 65, 0);
    }

    private static TaskStatus run(ContainerSim s, PlaceRun<ContainerSim.Shot> r, int maxTicks) {
        for (long t = 1; t <= maxTicks; t++) {
            s.beginTick(t);
            TaskStatus st = r.tick(t);
            s.endTick();
            if (st != TaskStatus.RUNNING) return st;
        }
        return TaskStatus.FAILED;
    }

    private static PlaceRun<ContainerSim.Shot> place(ContainerSim s, Pos cell) {
        return new PlaceRun<>(cell, ANVIL, REACH, 10, s);
    }

    private static void assertCleanClick(ContainerSim s) {
        assertEquals(0, s.rotateAndClick, "no rotation change in the click tick");
        assertEquals(0, s.clickWhileMoving, "no movement keys in the click tick");
        assertEquals(0, s.clickOffRotation, "clicked with the rotation in effect");
        assertEquals(s.clickTick - 1, s.aimedTick, "looked on the tick before the click");
    }

    @Test
    void anvilOnTheTowersCraftingTableIsPlacedWithASneakClick() {
        ContainerSim s = new ContainerSim(51);
        Pos cell = tower(s);
        s.teleport(4.5, 64, 3.5);
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.SUCCESS, run(s, r, 1500), r.failure() + " " + s.log);
        assertEquals(ANVIL, s.block(cell).id());
        assertEquals(1, s.placed);
        assertTrue(r.sneaking());
        assertTrue(s.clickSneaking, "sneak-clicked: the table does not open");
        assertEquals(0, s.openedInstead);
        assertEquals(0, s.boxRejects);
        assertEquals(Aim.UP, s.lastClick.face(), "the table's top face, the one touching the cell");
        assertEquals(new Pos(0, 64, 0), s.lastClick.block());
        assertCleanClick(s);
        assertTrue(s.clickEye[1] < 64 + Aim.EYE_HEIGHT, "clicked with the crouched eye");
    }

    @Test
    void aPlainNeighbourIsUsedWithoutSneaking() {
        ContainerSim s = new ContainerSim(52);
        Pos cell = tower(s);
        s.fill(-1, 65, 0, -1, 67, 0, STONE); // a full, plain block west of the cell
        s.teleport(4.5, 64, 3.5);
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.SUCCESS, run(s, r, 1500), r.failure() + " " + s.log);
        assertEquals(ANVIL, s.block(cell).id());
        assertFalse(r.sneaking());
        assertFalse(s.clickSneaking);
        assertEquals(new Pos(-1, 65, 0), s.lastClick.block());
        assertEquals(Aim.EAST, s.lastClick.face());
        assertCleanClick(s);
    }

    @Test
    void theBotStandingInTheCellStepsOutFirst() {
        ContainerSim s = new ContainerSim(53);
        Pos cell = tower(s);
        s.teleport(0.5, 65, 0.5); // on the crafting table, in the anvil cell
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.SUCCESS, run(s, r, 1500), r.failure() + " " + s.log);
        assertEquals(1, s.placed);
        assertEquals(0, s.boxRejects, "never clicked with the box in the cell");
        assertTrue(r.approach().walks() >= 1);
        assertFalse(StandSpots.covers(r.approach().standingCell(), cell));
    }

    @Test
    void aLostPlacementIsClickedAgainThenItFails() {
        ContainerSim s = new ContainerSim(54);
        Pos cell = tower(s);
        s.teleport(2.5, 64, 0.5);
        s.dropClicks = 1;
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.SUCCESS, run(s, r, 600), r.failure());
        assertEquals(2, s.clicks);

        ContainerSim s2 = new ContainerSim(55);
        tower(s2);
        s2.teleport(2.5, 64, 0.5);
        s2.dropClicks = 10;
        PlaceRun<ContainerSim.Shot> r2 = place(s2, cell);
        assertEquals(TaskStatus.FAILED, run(s2, r2, 600));
        assertTrue(r2.failure().contains("not confirmed after " + PlaceRun.MAX_ATTEMPTS + " clicks"), r2.failure());
    }

    @Test
    void itWaitsStillForTheItemToReachTheHand() {
        ContainerSim s = new ContainerSim(56);
        Pos cell = tower(s);
        s.teleport(2.5, 64, 0.5);
        s.holdDelay = 5;
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.SUCCESS, run(s, r, 600), r.failure());
        assertTrue(s.clickTick > 5);

        ContainerSim s2 = new ContainerSim(57);
        tower(s2);
        s2.carried = 0;
        PlaceRun<ContainerSim.Shot> r2 = place(s2, cell);
        assertEquals(TaskStatus.FAILED, run(s2, r2, 50));
        assertTrue(r2.failure().startsWith("no minecraft:anvil"), r2.failure());
    }

    @Test
    void anOccupiedCellOrNoSupportFailsWithTheReason() {
        ContainerSim s = new ContainerSim(58);
        Pos cell = tower(s);
        s.set(cell, STONE);
        assertEquals(TaskStatus.FAILED, run(s, place(s, cell), 20));

        ContainerSim s2 = new ContainerSim(59);
        s2.fill(-8, 63, -8, 8, 63, 8, STONE);
        s2.placeItem = ANVIL_BLOCK;
        Pos floating = new Pos(0, 66, 0);
        PlaceRun<ContainerSim.Shot> r = place(s2, floating);
        s2.teleport(2.5, 64, 0.5);
        assertEquals(TaskStatus.FAILED, run(s2, r, 20));
        assertTrue(r.failure().startsWith("no full block next to"), r.failure());
    }

    @Test
    void itNeverStandsOnAPlatformsArtLayer() {
        PlatformGeometry tile = new PlatformGeometry(-64, -64, 64);
        ContainerSim s = new ContainerSim(60);
        s.fill(-8, 63, -8, 8, 63, 8, BlockSpec.of("minecraft:moss_block"));
        s.set(0, 64, 0, TABLE); // on the art layer, inside the tile
        s.placeItem = ANVIL_BLOCK;
        Pos cell = new Pos(0, 65, 0);
        s.teleport(3.5, 64, 0.5);
        s.allowedCells = PlaceRun.standCells(List.of(tile));
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.FAILED, run(s, r, 200));
        assertTrue(r.failure().contains("no stand spot: no other floor to stand on around it; "), r.failure());
        assertTrue(r.failure().endsWith(" floor cells within reach excluded: on the art layer"), r.failure());
        assertEquals(0, s.clicks);
        assertEquals(0, s.baritoneWalks);

        // the same without the sweep rule: placed from the art layer
        ContainerSim open = new ContainerSim(61);
        open.fill(-8, 63, -8, 8, 63, 8, BlockSpec.of("minecraft:moss_block"));
        open.set(0, 64, 0, TABLE);
        open.placeItem = ANVIL_BLOCK;
        open.teleport(3.5, 64, 0.5);
        assertEquals(TaskStatus.SUCCESS, run(open, place(open, cell), 600));
    }

    // ------------------------------------------------------------------------------------------ gate B2: the moss tower

    /** Synthetic anvil cell of {@link AnvilTowerSite}: its tile is (-64, -64), art layer 64. */
    private static final int AX = 52;
    private static final int AY = 66;
    private static final int AZ = -74;

    private static ContainerSim mossTower(long seed) {
        ContainerSim s = new ContainerSim(seed);
        AnvilTowerSite.build(s, AX, AY, AZ);
        s.placeItem = ANVIL_BLOCK;
        double[] bot = AnvilTowerSite.bot(AX, AY, AZ);
        s.teleport(bot[0], bot[1], bot[2]);
        return s;
    }

    /**
     * Gate B2: the anvil gone from the tower in the supply column, which lies in the tile's clear sweep with the whole
     * walkway. The placement rule leaves the walkway open: sneak-clicked onto the crafting table from the walkway east
     * of the column, with the crouched eye. (PlaceBlockTask used the presses' sweep rule: BLOCKED in RENAME.)
     */
    @Test
    void theAnvilTowerInTheSupplyColumnIsPlacedFromTheWalkwayBesideIt() {
        PlatformGeometry tile = AnvilTowerSite.tile(AX, AY, AZ);
        assertEquals(new PlatformGeometry(-64, -64, 64), tile);
        Pos cell = new Pos(AX, AY, AZ);
        Pos table = cell.below();
        ContainerSim s = mossTower(63);
        s.allowedCells = PlaceRun.standCells(List.of(tile));
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.SUCCESS, run(s, r, 1500), r.failure() + " " + s.log);
        assertEquals(ANVIL, s.block(cell).id());
        assertEquals(1, s.placed);
        assertTrue(r.sneaking());
        assertTrue(s.clickSneaking, "sneak-click: the table does not open");
        assertEquals(0, s.openedInstead);
        assertEquals(0, s.boxRejects);
        assertEquals(table, s.lastClick.block());
        assertEquals(Aim.UP, s.lastClick.face(), "the table's top face");
        assertCleanClick(s);
        Pos stood = r.approach().standingCell();
        assertEquals(AY - 1, stood.y(), "feet on the walkway, level with the table: " + stood);
        assertTrue(stood.x() > AX && stood.x() <= AX + 4, "east of the column: " + stood);
        assertFalse(StandSpots.covers(stood, cell));
        assertTrue(s.clickEye[1] < stood.y() + Aim.EYE_HEIGHT - 0.3, "clicked with the crouched eye: " + s.clickEye[1]);
        assertFalse(StandSpots.awayFromClearSweeps(List.of(tile)).test(stood), "the spot lies in the clear sweep");
    }

    /** The same tower with the presses' sweep rule: the live failure, now with the cells the rule left out. */
    @Test
    void withTheSweepRuleOnlyTheSealedPocketsUnderTheWalkwayWereLeft() {
        PlatformGeometry tile = AnvilTowerSite.tile(AX, AY, AZ);
        Pos cell = new Pos(AX, AY, AZ);
        ContainerSim s = mossTower(64);
        s.allowedCells = StandSpots.awayFromClearSweeps(List.of(tile));
        PlaceRun<ContainerSim.Shot> r = place(s, cell);
        assertEquals(TaskStatus.FAILED, run(s, r, 200));
        assertTrue(r.failure().startsWith("cannot place at " + cell + ": no stand spot: none of the 3 floor cells within"
            + " reach can be walked to from the bot (walled in, or more than a block up); "), r.failure());
        assertTrue(r.failure().endsWith(" floor cells within reach excluded: inside the clear sweep"), r.failure());
        assertEquals(0, s.clicks);
        assertEquals(0, s.baritoneWalks);

        // the search itself: the pockets are the only allowed floor in reach, the walkway is counted apart
        s.aimAt(Map.of(cell.below(), Aim.UP), true, cell);
        double[] bot = AnvilTowerSite.bot(AX, AY, AZ);
        StandSpots.Search q = StandSpots.search(s, s, s.targets, REACH, bot[0], bot[1], bot[2], null, Set.of(), 4);
        assertEquals(3, q.inReach());
        assertEquals(3, q.unreachable());
        assertTrue(q.excluded().get("inside the clear sweep") >= 10, q.excluded().toString());
        for (Pos p : AnvilTowerSite.pockets(AX, AY, AZ)) assertTrue(s.allowed(p), "pocket " + p);
    }

    @Test
    void thePlacementRuleRefusesTheArtLayerOnly() {
        PlatformGeometry tile = new PlatformGeometry(-64, -64, 64);
        StandSpots.CellRule rule = PlaceRun.standCells(List.of(tile));
        assertEquals("on the art layer", rule.refused());
        assertFalse(rule.test(new Pos(0, 64, 0)), "on the carpets");
        assertFalse(rule.test(new Pos(-64, 63, 63)), "in the floor");
        assertFalse(rule.test(new Pos(63, 66, -64)), "two above the art layer");
        assertTrue(rule.test(new Pos(0, 67, 0)), "three above");
        assertTrue(rule.test(new Pos(0, 65, -65)), "beside the tile, in the clear sweep");
        assertTrue(rule.test(new Pos(64, 65, 0)), "beside the tile, in the clear sweep");
        assertFalse(StandSpots.awayFromClearSweeps(List.of(tile)).test(new Pos(0, 65, -65)));
    }
}
