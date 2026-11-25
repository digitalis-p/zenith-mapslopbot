package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;
import io.github.digitalisp.mapart.testutil.ContainerSites;
import io.github.digitalisp.mapart.testutil.SupplyColumnSite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.GLASS;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;

/** Stand-spot search on synthetic geometry (wall signs block rays, as in ZenithProxy's raycast). */
class StandSpotsTest {
    private static final double REACH = 4.3;
    private static final double EYE = Aim.EYE_HEIGHT;

    private static StandSpots.Search search(ContainerSim s, double[] bot) {
        return StandSpots.search(s, s, s.targets, REACH, bot[0], bot[1], bot[2], null, Set.of(), 4);
    }

    /** Every returned spot sees a face centre of the target from its centre, with the margin. */
    private static void assertAllVisible(ContainerSim s, StandSpots.Search r) {
        for (StandSpots.Spot sp : r.spots()) {
            assertTrue(s.visibleFrom(sp.eyeX(), sp.eyeY(), sp.eyeZ(), REACH - StandSpots.CENTRE_MARGIN), "visible from " + sp);
        }
    }

    // ------------------------------------------------------------------------------------------ the output column

    @Test
    void signBlockedFacesSendTheBotToTheSouthSide() {
        ContainerSim s = new ContainerSim(1);
        Pos out = new Pos(10, 65, 20);
        double[] bot = ContainerSites.outputColumn(s, out);

        // where the bot stood at gate A no face centre is in sight: the east face centre is behind the board
        assertFalse(s.visibleFrom(bot[0], bot[1] + EYE, bot[2], REACH));
        // the old range-1 approach goal is met in the cell right east of the chest, among the signs: nothing in sight there
        Pos east = new Pos(out.x() + 1, out.y() - 1, out.z());
        assertTrue(new GoalNearXZ(out.x(), out.y(), out.z(), 1, 2).isInGoal(east.x(), east.y(), east.z()));
        assertFalse(s.visibleFrom(east.x() + 0.5, east.y() + EYE, east.z() + 0.5, REACH));
        assertTrue(s.hiddenFrom(east.x() + 0.5, east.y() + EYE, east.z() + 0.5, REACH).contains("oak_wall_sign"),
            s.hiddenFrom(east.x() + 0.5, east.y() + EYE, east.z() + 0.5, REACH));

        StandSpots.Search r = search(s, bot);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        assertAllVisible(s, r);
        for (StandSpots.Spot sp : r.spots()) assertTrue(sp.z() > out.z(), "south of the chest: " + sp);
        // nearest visible cell from the north-east: the south-east diagonal, on the floor
        assertEquals(new Pos(out.x() + 1, out.y() - 1, out.z() + 1), r.spots().getFirst().cell());
        assertEquals(out.y() - 1, r.spots().getFirst().feetY(), 1e-9);
        assertTrue(r.hidden() >= 2, "the bot's own cell and the sign cell were checked and rejected");
    }

    @Test
    void theBotsOwnCellComesFirstThenTheRememberedOne() {
        ContainerSim s = new ContainerSim(1);
        Pos out = new Pos(10, 65, 20);
        ContainerSites.outputColumn(s, out);
        double[] south = {out.x() + 0.5, out.y() - 1, out.z() + 2.5};
        assertEquals(new Pos(out.x(), out.y() - 1, out.z() + 2), search(s, south).spots().getFirst().cell());

        Pos remembered = new Pos(out.x() - 1, out.y() - 1, out.z() + 2);
        double[] far = {out.x() + 5.5, out.y() - 1, out.z() - 4.5};
        StandSpots.Search r = StandSpots.search(s, s, s.targets, REACH, far[0], far[1], far[2], remembered, Set.of(), 4);
        assertEquals(remembered, r.spots().getFirst().cell());
        StandSpots.Search without = StandSpots.search(s, s, s.targets, REACH, far[0], far[1], far[2], remembered,
            Set.of(remembered), 4);
        assertFalse(without.spots().stream().anyMatch(sp -> sp.cell().equals(remembered)), "excluded");
    }

    // ------------------------------------------------------------------------------------------ other shapes

    @Test
    void chestOneBlockUpIsUsedFromTheFloorInFrontOfIt() {
        ContainerSim s = new ContainerSim(2);
        s.fill(-6, 63, -6, 6, 63, 6, STONE);
        s.set(0, 64, 0, STONE);
        Pos c = new Pos(0, 65, 0);
        s.set(c, chest("west", "single"));
        s.targets = List.of(c);
        double[] bot = {-5.5, 64, 0.5};
        StandSpots.Search r = search(s, bot);
        assertAllVisible(s, r);
        StandSpots.Spot first = r.spots().getFirst();
        assertEquals(64, first.y());
        assertTrue(Math.hypot(first.eyeX() - bot[0], first.eyeZ() - bot[2]) < 3, "nearest visible cell, towards the bot: " + first);
        assertTrue(first.x() < 0, "west of the chest, the side the bot comes from: " + first);
    }

    @Test
    void chestBehindACornerIsUsedFromAroundIt() {
        ContainerSim s = new ContainerSim(3);
        s.fill(-12, 63, -12, 12, 63, 12, STONE);
        // an alcove open to the south: walls west, east and north of the chest, a glass roof
        Pos c = new Pos(0, 64, 0);
        s.set(c, chest("south", "single"));
        s.fill(-1, 64, -1, -1, 66, 1, STONE);
        s.fill(1, 64, -1, 1, 66, 1, STONE);
        s.fill(-1, 64, -1, 1, 66, -1, STONE);
        s.fill(-1, 65, 0, 1, 65, 0, GLASS);
        // and a long wall north of it that the bot stands behind
        s.fill(-8, 64, -3, 8, 66, -3, STONE);
        s.targets = List.of(c);
        double[] bot = {0.5, 64, -5.5};
        assertFalse(s.visibleFrom(bot[0], bot[1] + EYE, bot[2], REACH));
        StandSpots.Search r = search(s, bot);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        assertAllVisible(s, r);
        for (StandSpots.Spot sp : r.spots()) assertTrue(sp.z() >= 1, "in front of the alcove: " + sp);
        assertTrue(r.hidden() > 0, "the cells on the bot's side were rejected for sight");
    }

    @Test
    void topRowSorterChestFromTheWalkwayFourBlocksBelow() {
        ContainerSim s = new ContainerSim(4);
        ContainerSites.sorterWall(s, 0, 8, 63, 10);
        s.targets = ContainerSites.sorterChest(4, 68, 10);
        // the old approach goals (chest height ± 2) have no cell on the walkway (feet 64)
        for (int x = -3; x <= 11; x++) {
            assertFalse(new GoalNearXZ(4, 68, 10, 3, 2).isInGoal(x, 64, 11));
        }
        // three columns west, at the west edge of its cell (where the partial path left the bot): the front face centre
        // is out of reach, the west face centre is behind the neighbouring column
        double[] bot = {1.02, 64 + 1.0 / 16, 11.5};
        assertFalse(s.visibleFrom(bot[0], bot[1] + EYE, bot[2], REACH));
        assertTrue(Math.sqrt(Aim.distSq(bot[0], bot[1] + EYE, bot[2], 4.5, 68 + 7.0 / 16, 10 + 15.0 / 16)) > REACH);
        String why = s.hiddenFrom(bot[0], bot[1] + EYE, bot[2], REACH);
        assertTrue(why.startsWith("minecraft:chest at ") && why.endsWith("hides the west face center"), why);
        StandSpots.Search r = search(s, bot);
        assertAllVisible(s, r);
        StandSpots.Spot first = r.spots().getFirst();
        assertEquals(64, first.y(), "on the walkway, on the carpet row");
        assertEquals(64 + 1.0 / 16, first.feetY(), 1e-9);
        assertEquals(11, first.z());
        assertEquals(2, first.x(), "the nearest cell in reach");
    }

    @Test
    void feetHeights() {
        ContainerSim s = new ContainerSim(5);
        s.set(0, 63, 0, STONE);
        assertEquals(64, StandSpots.feetY(s, 0, 64, 0), 1e-9);
        s.set(0, 64, 0, BlockSpec.of("minecraft:white_carpet"));
        assertEquals(64 + 1.0 / 16, StandSpots.feetY(s, 0, 64, 0), 1e-9);
        assertTrue(Double.isNaN(StandSpots.feetY(s, 0, 65, 0)), "no floor in the air above a carpet");
        // a chest (14/16) carries the feet into the cell above it
        s.set(1, 63, 0, chest("north", "single"));
        assertEquals(63.875, StandSpots.feetY(s, 1, 64, 0), 1e-9);
        // head room: a block two above the feet
        s.set(2, 63, 0, STONE);
        s.set(2, 65, 0, STONE);
        assertTrue(Double.isNaN(StandSpots.feetY(s, 2, 64, 0)));
        // a wall sign at head height is no obstacle (no collision)
        s.set(3, 63, 0, STONE);
        s.set(3, 65, 0, ContainerSim.wallSign("east"));
        assertEquals(64, StandSpots.feetY(s, 3, 64, 0), 1e-9);
        // nothing below
        assertTrue(Double.isNaN(StandSpots.feetY(s, 4, 64, 0)));
        // hazards
        s.set(5, 63, 0, BlockSpec.of("minecraft:magma_block"));
        assertTrue(Double.isNaN(StandSpots.feetY(s, 5, 64, 0)));
        s.set(6, 63, 0, STONE);
        s.set(6, 64, 0, BlockSpec.of("minecraft:cobweb"));
        assertTrue(Double.isNaN(StandSpots.feetY(s, 6, 64, 0)));
        // a slab in the feet cell needs two cells of head room
        s.set(7, 64, 0, BlockSpec.of("minecraft:stone_slab", Map.of("type", "bottom")));
        s.collisionShape = b -> b.id().endsWith("_slab") ? List.of(new double[]{0, 0, 0, 1, 0.5, 1}) : defaultCollision(b);
        assertEquals(64.5, StandSpots.feetY(s, 7, 64, 0), 1e-9);
        s.set(7, 66, 0, STONE);
        assertTrue(Double.isNaN(StandSpots.feetY(s, 7, 64, 0)));
    }

    @Test
    void cellsThatHurtOrDrownAreNeverStandSpots() {
        ContainerSim s = new ContainerSim(6);
        BlockSpec water = BlockSpec.of("minecraft:water");
        BlockSpec lava = BlockSpec.of("minecraft:lava");
        // as in the game: these have no collision box, so only the hazard rule keeps the bot out of them
        Set<String> soft = Set.of("minecraft:fire", "minecraft:soul_fire", "minecraft:sweet_berry_bush",
            "minecraft:powder_snow", "minecraft:bubble_column", "minecraft:cobweb");
        var shapes = s.collisionShape;
        s.collisionShape = b -> soft.contains(b.id()) ? List.of() : shapes.apply(b);
        s.fill(0, 63, 0, 30, 63, 0, STONE);
        assertEquals(64, StandSpots.feetY(s, 0, 64, 0), 1e-9, "dry floor");
        s.set(1, 64, 0, water);
        assertTrue(Double.isNaN(StandSpots.feetY(s, 1, 64, 0)), "water at the feet");
        s.set(2, 65, 0, water);
        assertTrue(Double.isNaN(StandSpots.feetY(s, 2, 64, 0)), "water at the head (a stream over the floor)");
        s.set(3, 64, 0, lava);
        assertTrue(Double.isNaN(StandSpots.feetY(s, 3, 64, 0)), "lava at the feet");
        s.set(4, 65, 0, lava);
        assertTrue(Double.isNaN(StandSpots.feetY(s, 4, 64, 0)), "lava at the head");
        int x = 5;
        for (String id : List.of("fire", "soul_fire", "sweet_berry_bush", "powder_snow", "bubble_column", "cobweb")) {
            s.set(x, 64, 0, BlockSpec.of("minecraft:" + id));
            assertTrue(Double.isNaN(StandSpots.feetY(s, x, 64, 0)), id + " at the feet");
            s.set(x + 1, 65, 0, BlockSpec.of("minecraft:" + id));
            assertTrue(Double.isNaN(StandSpots.feetY(s, x + 1, 64, 0)), id + " at the head");
            x += 2;
        }
        for (String id : List.of("magma_block", "cactus", "campfire")) {
            s.set(x, 63, 0, BlockSpec.of("minecraft:" + id));
            assertTrue(Double.isNaN(StandSpots.feetY(s, x, 64, 0)), "standing on " + id);
            x++;
        }
        s.set(x, 65, 0, STONE);
        assertTrue(Double.isNaN(StandSpots.feetY(s, x, 64, 0)), "a block at the head (suffocation)");
    }

    private static List<double[]> defaultCollision(BlockSpec b) {
        if (b.isAir() || b.id().endsWith("_sign") || b.id().equals("minecraft:cobweb")) return List.of();
        if (b.id().endsWith("_carpet")) return List.of(new double[]{0, 0, 0, 1, 1.0 / 16, 1});
        if (b.id().endsWith("chest")) return List.of(new double[]{1.0 / 16, 0, 1.0 / 16, 15.0 / 16, 14.0 / 16, 15.0 / 16});
        return List.of(new double[]{0, 0, 0, 1, 1, 1});
    }

    // ------------------------------------------------------------------------------------------ unreachable

    @Test
    void chestWalledInGlassHasNoSpotAndSaysWhatHidesIt() {
        ContainerSim s = new ContainerSim(6);
        s.fill(-6, 63, -6, 6, 63, 6, STONE);
        Pos c = new Pos(0, 64, 0);
        s.fill(-1, 64, -1, 1, 65, 1, GLASS);
        s.set(c, chest("north", "single"));
        s.targets = List.of(c);
        StandSpots.Search r = search(s, new double[]{3.5, 64, 3.5});
        assertTrue(r.spots().isEmpty());
        assertTrue(r.inReach() > 0);
        // the 9 cells on the glass roof are 2 up from the floor: not walkable to, so not even checked for sight
        assertEquals(9, r.unreachable(), "the roof of the glass box");
        assertEquals(r.inReach(), r.hidden() + r.unreachable(), "every other floor cell in reach was checked");
        assertTrue(r.describe(REACH).contains("no face center in sight") && r.describe(REACH).contains("glass"), r.describe(REACH));
    }

    @Test
    void chestOutOfReachAbove() {
        ContainerSim s = new ContainerSim(7);
        // a pillar with the chest on top, floor only 4 or more blocks away from it
        s.fill(-10, 63, -10, 10, 63, 10, STONE);
        s.fill(-3, 63, -3, 3, 63, 3, BlockSpec.AIR);
        s.fill(0, 63, 0, 0, 67, 0, STONE);
        Pos c = new Pos(0, 68, 0);
        s.set(c, chest("north", "single"));
        s.targets = List.of(c);
        StandSpots.Search r = search(s, new double[]{6.5, 64, 6.5});
        assertTrue(r.spots().isEmpty());
        assertTrue(r.floorCells() > 0);
        assertEquals(0, r.inReach());
        assertTrue(r.describe(REACH).contains("within reach"), r.describe(REACH));
    }

    @Test
    void chestWithNoFloorAround() {
        ContainerSim s = new ContainerSim(8);
        Pos c = new Pos(0, 64, 0);
        s.set(c, chest("north", "single"));
        s.targets = List.of(c);
        StandSpots.Search r = search(s, new double[]{20.5, 64, 0.5});
        assertTrue(r.spots().isEmpty());
        assertEquals("no floor to stand on around it", r.describe(REACH));
    }

    // ------------------------------------------------------------------------------------------ reachability (gate B)

    @Test
    void aWalledInPitBehindTheColumnIsNeverChosen() {
        ContainerSim s = new ContainerSim(9);
        Pos target = SupplyColumnSite.build(s, 0, 63, 0);
        double[] bot = SupplyColumnSite.botNorthEast(0, 63, 0);
        List<Pos> pit = SupplyColumnSite.pit(0, 63, 0);
        // the old ranking's first cell: the pit is nearer in a straight line and sees the west face centre
        Pos pitCell = pit.getFirst();
        assertTrue(s.visibleFrom(pitCell.x() + 0.5, pitCell.y() + EYE, pitCell.z() + 0.5, REACH - StandSpots.CENTRE_MARGIN));
        assertTrue(Math.hypot(pitCell.x() + 0.5 - bot[0], pitCell.z() + 0.5 - bot[2]) < 4);

        StandSpots.Search r = search(s, bot);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        assertAllVisible(s, r);
        for (StandSpots.Spot sp : r.spots()) {
            assertFalse(pit.contains(sp.cell()), "never the pit: " + sp);
            assertTrue(sp.reachable(), "walkable to from the bot: " + sp);
            assertTrue(sp.x() > target.x(), "on the walkway side: " + sp);
        }
        assertTrue(r.unreachable() >= pit.size(), "the pit cells were left out: " + r.unreachable());
        assertEquals(target.y() - 2, r.spots().getFirst().y(), "on the walkway");
    }

    @Test
    void walkingDistanceNotStraightLineRanksTheSpots() {
        ContainerSim s = new ContainerSim(10);
        SupplyColumnSite.build(s, 0, 63, 0);
        double[] bot = SupplyColumnSite.botNorthEast(0, 63, 0);
        Map<Pos, Double> costs = StandSpots.pathCosts(s, s.targets, bot[0], bot[1], bot[2]);
        assertTrue(costs != null);
        assertEquals(0.0, costs.get(StandSpots.feetCell(bot[0], bot[1], bot[2])), 1e-9, "the bot's own cell");
        for (Pos p : SupplyColumnSite.pit(0, 63, 0)) assertFalse(costs.containsKey(p), "pit cell " + p);
        // straight south along the walkway: one per step
        assertEquals(5.0, costs.get(new Pos(1, 64, 1)), 1e-9);
        // round the corner of the column to the south strip: further on foot than in a straight line
        Pos south = new Pos(-3, 64, 3);
        assertTrue(costs.get(south) > Math.hypot(south.x() + 0.5 - bot[0], south.z() + 0.5 - bot[2]));
    }

    @Test
    void aRememberedSpotThatCannotBeWalkedToIsNotFirst() {
        ContainerSim s = new ContainerSim(11);
        SupplyColumnSite.build(s, 0, 63, 0);
        double[] bot = SupplyColumnSite.botNorthEast(0, 63, 0);
        Pos pitCell = SupplyColumnSite.pit(0, 63, 0).get(1);
        StandSpots.Search r = StandSpots.search(s, s, s.targets, REACH, bot[0], bot[1], bot[2], pitCell, Set.of(), 4);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        assertFalse(r.spots().stream().anyMatch(sp -> sp.cell().equals(pitCell)), "a stale memory of the pit");
        // a reachable remembered cell still comes first
        Pos good = r.spots().get(r.spots().size() - 1).cell();
        StandSpots.Search again = StandSpots.search(s, s, s.targets, REACH, bot[0], bot[1], bot[2], good, Set.of(), 4);
        assertEquals(good, again.spots().getFirst().cell());
    }

    @Test
    void theBotStandingOnNothingFallsBackToStraightLines() {
        ContainerSim s = new ContainerSim(12);
        SupplyColumnSite.build(s, 0, 63, 0);
        double[] air = {1.5, 70, -3.5}; // high above the walkway (a fall, a pillar of scaffolding gone)
        assertTrue(StandSpots.pathCosts(s, s.targets, air[0], air[1], air[2]) == null, "cannot tell");
        StandSpots.Search r = search(s, air);
        assertFalse(r.spots().isEmpty(), "the old ranking, not nothing: " + r.describe(REACH));
        assertEquals(0, r.unreachable());
        assertFalse(r.spots().getFirst().reachable());
    }

    @Test
    void farFromTheTargetTheBoxEdgeStandsForTheWorldOutside() {
        ContainerSim s = new ContainerSim(13);
        SupplyColumnSite.build(s, 0, 63, 0);
        s.fill(1, 63, -60, 3, 63, -8, STONE); // a long corridor north to the walkway
        double[] far = {2.5, 64, -55.5};
        StandSpots.Search r = search(s, far);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        for (StandSpots.Spot sp : r.spots()) {
            assertFalse(SupplyColumnSite.pit(0, 63, 0).contains(sp.cell()), "never the pit: " + sp);
            assertTrue(sp.reachable(), sp.toString());
        }
    }

    // ------------------------------------------------------------------------------------------ the box edge

    /** Every cell on the top of the sorter wall (chests 14/16 high, hoppers) and on a sill in front of the top row. */
    private static void assertNoneOnTheWall(Map<Pos, Double> costs, int x1) {
        for (int x = -4; x <= x1 + 4; x++) {
            for (int z = 8; z <= 11; z++) {
                for (int y = 69; y <= 70; y++) {
                    Pos p = new Pos(x, y, z);
                    assertFalse(costs.containsKey(p), "the top of the wall cannot be walked to: " + p + " " + costs.get(p));
                }
            }
        }
    }

    @Test
    void theTopOfALongSorterWallIsNotWalkableFromTheWalkwayThirtyBlocksAway() {
        ContainerSim s = new ContainerSim(16);
        ContainerSites.sorterWall(s, 0, 90, 63, 10);
        s.targets = ContainerSites.sorterChest(20, 68, 10); // the top row, 4 above the walkway's feet
        double[] bot = {50.5, 64, 13.5}; // on the walkway, 30 blocks east: outside the box round the chest
        assertTrue(bot[0] - 21 > StandSpots.REACH_RADIUS);
        // the top of the wall runs out of that box on both sides, and the chest's top face is in sight from it
        Pos top = new Pos(21, 69, 10);
        assertEquals(68.875, StandSpots.feetY(s, top.x(), top.y(), top.z()), 1e-9);
        assertTrue(s.visibleFrom(top.x() + 0.5, 68.875 + EYE, top.z() + 0.5, REACH - StandSpots.CENTRE_MARGIN));

        Map<Pos, Double> costs = StandSpots.pathCosts(s, s.targets, bot[0], bot[1], bot[2]);
        assertTrue(costs != null);
        assertNoneOnTheWall(costs, 90);
        // the walk from the bot itself (29 west, 2 north), not a guess from the box's edge
        assertEquals(31.0, costs.get(new Pos(21, 64, 11)), 1e-9);

        StandSpots.Search r = search(s, bot);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        assertAllVisible(s, r);
        for (StandSpots.Spot sp : r.spots()) {
            assertEquals(64, sp.y(), "on the walkway, never on top of the wall: " + sp);
            assertTrue(sp.z() > 10 && sp.reachable(), sp.toString());
        }
        assertTrue(r.unreachable() > 0, "the top of the wall in reach was left out");

        // a configured stand cell is still trusted and first; a remembered one on the walkway still comes first
        Pos configured = new Pos(18, 64, 12);
        StandSpots.Search withFirst = StandSpots.search(s, s, s.targets, REACH, bot[0], bot[1], bot[2],
            List.of(configured), null, Set.of(), 4);
        assertEquals(configured, withFirst.spots().getFirst().cell());
        Pos remembered = new Pos(19, 64, 11);
        StandSpots.Search withMemory = StandSpots.search(s, s, s.targets, REACH, bot[0], bot[1], bot[2], remembered,
            Set.of(), 4);
        assertEquals(remembered, withMemory.spots().getFirst().cell());
    }

    @Test
    void aChestOnlyTheTopOfTheWallSeesHasNoSpotRatherThanOneUpThere() {
        ContainerSim s = new ContainerSim(17);
        ContainerSites.sorterWall(s, 0, 90, 63, 10);
        s.fill(-4, 68, 11, 94, 68, 11, GLASS); // a glass sill in front of the top row: the walkway sees none of it
        s.targets = ContainerSites.sorterChest(20, 68, 10);
        double[] bot = {50.5, 64, 13.5};
        Pos sill = new Pos(21, 69, 11);
        assertTrue(s.visibleFrom(sill.x() + 0.5, sill.y() + EYE, sill.z() + 0.5, REACH - StandSpots.CENTRE_MARGIN));

        StandSpots.Search r = search(s, bot);
        assertTrue(r.spots().isEmpty(), "nothing on the top of the wall or on the sill: " + r.spots());
        assertTrue(r.unreachable() > 0);
        String why = r.describe(REACH);
        assertTrue(why.startsWith("no face center in sight") && why.contains("more cannot be walked to"), why);
    }

    @Test
    void aBotBeyondTheCapStartsFromTheBoxEdgeOnItsSideAtItsLevel() {
        ContainerSim s = new ContainerSim(18);
        ContainerSites.sorterWall(s, 0, 199, 63, 10);
        s.targets = ContainerSites.sorterChest(20, 68, 10);
        double[] bot = {120.5, 64, 13.5}; // 100 blocks east along the walkway
        assertTrue(bot[0] - 21 > StandSpots.REACH_FAR);

        Map<Pos, Double> costs = StandSpots.pathCosts(s, s.targets, bot[0], bot[1], bot[2]);
        assertTrue(costs != null, "far away is not unknown");
        assertNoneOnTheWall(costs, 199);
        for (Pos p : costs.keySet()) assertTrue(p.x() <= 20 + StandSpots.REACH_FAR, "the box stops at the cap: " + p);
        double walk = costs.get(new Pos(21, 64, 11));
        assertTrue(walk >= 99 && walk <= 102, "about the way along the walkway: " + walk);

        StandSpots.Search r = search(s, bot);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        assertAllVisible(s, r);
        for (StandSpots.Spot sp : r.spots()) {
            assertEquals(64, sp.y(), "on the walkway: " + sp);
            assertTrue(sp.z() > 10 && sp.reachable(), sp.toString());
        }
    }

    @Test
    void theWorldOutsideLeadsBackInAtTheLevelTheFloodLeftTheBoxAt() {
        ContainerSim s = new ContainerSim(19);
        // a yard walled 3 high round the chest, with a gate on the west only
        s.fill(-6, 63, -6, 6, 63, 6, STONE);
        Pos c = new Pos(0, 64, 0);
        s.set(c, chest("north", "single"));
        for (int i = -7; i <= 7; i++) {
            s.fill(i, 64, -7, i, 66, -7, STONE);
            s.fill(i, 64, 7, i, 66, 7, STONE);
            s.fill(-7, 64, i, -7, 66, i, STONE);
            s.fill(7, 64, i, 7, 66, i, STONE);
        }
        s.fill(-7, 64, 0, -7, 65, 0, BlockSpec.AIR);
        // the bot on a deck 4 up east of it; stairs down to a path that leaves the box to the north, runs round far
        // outside it and comes back from the west to the gate, at ground level (not the bot's)
        s.fill(26, 67, -2, 34, 67, 2, STONE);
        s.set(30, 66, -3, STONE);
        s.set(30, 65, -4, STONE);
        s.set(30, 64, -5, STONE);
        s.fill(30, 63, -40, 30, 63, -6, STONE);
        s.fill(-40, 63, -40, 30, 63, -40, STONE);
        s.fill(-40, 63, -40, -40, 63, 0, STONE);
        s.fill(-40, 63, 0, -7, 63, 0, STONE);
        s.targets = List.of(c);
        double[] bot = {30.5, 68, 0.5};

        Map<Pos, Double> costs = StandSpots.pathCosts(s, s.targets, bot[0], bot[1], bot[2]);
        assertTrue(costs != null);
        Pos gate = new Pos(-7, 64, 0);
        assertTrue(costs.containsKey(gate), "back in from the west at ground level, where the path left the box");
        assertTrue(costs.get(gate) > 60, "out to the north first: " + costs.get(gate));
        StandSpots.Search r = search(s, bot);
        assertFalse(r.spots().isEmpty(), r.describe(REACH));
        for (StandSpots.Spot sp : r.spots()) {
            assertTrue(Math.abs(sp.x()) <= 6 && Math.abs(sp.z()) <= 6 && sp.y() == 64, "in the yard: " + sp);
            assertTrue(sp.reachable(), sp.toString());
        }
    }

    @Test
    void everyCellInReachWalledInSaysSo() {
        ContainerSim s = new ContainerSim(14);
        s.fill(-12, 63, -12, 12, 63, 12, STONE);
        Pos c = new Pos(0, 64, 0);
        s.set(c, chest("north", "single"));
        // a ring of stone 3 high at distance 5: the bot outside, the chest and its floor inside
        for (int i = -5; i <= 5; i++) {
            s.fill(i, 64, -5, i, 66, -5, STONE);
            s.fill(i, 64, 5, i, 66, 5, STONE);
            s.fill(-5, 64, i, -5, 66, i, STONE);
            s.fill(5, 64, i, 5, 66, i, STONE);
        }
        s.targets = List.of(c);
        StandSpots.Search r = search(s, new double[]{8.5, 64, 0.5});
        assertTrue(r.spots().isEmpty());
        assertTrue(r.inReach() > 0);
        assertEquals(r.inReach(), r.unreachable());
        assertTrue(r.describe(REACH).contains("can be walked to"), r.describe(REACH));
    }

    /** Gate B2: cells a rule left out are named with the rule, apart from the ones the bot cannot walk to. */
    @Test
    void cellsARuleExcludesAreCountedApartWithTheirReason() {
        String walled = "none of the 3 floor cells within reach can be walked to from the bot (walled in, or more than a"
            + " block up)";
        assertEquals(walled, new StandSpots.Search(List.of(), 3, 3, 3, 0, null, List.of(), Map.of()).describe(REACH));
        assertEquals(walled + "; 40 floor cells within reach excluded: inside the clear sweep",
            new StandSpots.Search(List.of(), 3, 3, 3, 0, null, List.of(), Map.of("inside the clear sweep", 40))
                .describe(REACH));
        Map<String, Integer> two = new LinkedHashMap<>();
        two.put("on the art layer", 12);
        two.put(StandSpots.IN_THE_WAY, 2);
        assertEquals("no other floor to stand on around it; 14 floor cells within reach excluded: 12 on the art layer, 2 "
            + StandSpots.IN_THE_WAY, new StandSpots.Search(List.of(), 0, 0, 0, 0, null, List.of(), two).describe(REACH));

        // from the search: a ring of cells the world refuses around a chest
        ContainerSim s = new ContainerSim(15);
        s.fill(-12, 63, -12, 12, 63, 12, STONE);
        Pos c = new Pos(0, 64, 0);
        s.set(c, chest("north", "single"));
        s.targets = List.of(c);
        s.allowedCells = new StandSpots.CellRule(p -> Math.abs(p.x()) > 6 || Math.abs(p.z()) > 6, "too close");
        StandSpots.Search r = search(s, new double[]{8.5, 64, 0.5});
        assertTrue(r.spots().isEmpty());
        assertEquals(0, r.inReach());
        assertTrue(r.excluded().get("too close") > 0, r.excluded().toString());
        assertTrue(r.describe(REACH).endsWith(" floor cells within reach excluded: too close"), r.describe(REACH));
    }
}
