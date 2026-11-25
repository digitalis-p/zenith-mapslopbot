package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;
import io.github.digitalisp.mapart.testutil.ContainerSites;
import io.github.digitalisp.mapart.testutil.SupplyColumnSite;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.GLASS;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;

/** {@link Approach} on a simulated world with wall signs: where it stands, and that it looks first and clicks next. */
class ApproachTest {
    private static final double REACH = 4.3;

    private static Approach<ContainerSim.Shot> approach(ContainerSim s, Pos preferred) {
        return new Approach<>("chest", s.targets, REACH, preferred, s);
    }

    /** Grim-safe click: rotation sent on the tick before, none requested and no keys on the click tick. */
    private static void assertCleanClick(ContainerSim s) {
        assertEquals(0, s.rotateAndClick, "no rotation change in the click tick");
        assertEquals(0, s.clickWhileMoving, "no movement keys in the click tick");
        assertEquals(0, s.clickOffRotation, "clicked with the rotation in effect");
        assertEquals(s.clickTick - 1, s.aimedTick, "looked on the tick before the click");
    }

    @Test
    void signedOutputChestIsOpenedFromTheSouthSide() {
        ContainerSim s = new ContainerSim(11);
        Pos out = new Pos(10, 65, 20);
        double[] start = ContainerSites.outputColumn(s, out);
        s.teleport(start[0], start[1], start[2]);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 600), a.failure() + " " + s.log);
        assertEquals(out, s.opened);
        assertEquals(1, s.clicks);
        assertEquals(Aim.SOUTH, s.lastClick.face(), "the south face, the one whose center is in sight");
        assertTrue(s.clickEye[2] > out.z() + 1, "clicked from the south side: " + s.clickEye[2]);
        assertEquals(1, a.walks());
        assertEquals(1, s.baritoneWalks);
        assertEquals(new Pos(out.x() + 1, out.y() - 1, out.z() + 1), a.standingCell());
        assertCleanClick(s);
        // the hit point is the face centre
        assertEquals(out.x() + 0.5, s.lastClick.hx(), 0.02);
        assertEquals(out.y() + 7.0 / 16, s.lastClick.hy(), 0.02);
    }

    @Test
    void inSightAlreadyNoWalk() {
        ContainerSim s = new ContainerSim(12);
        Pos out = new Pos(10, 65, 20);
        ContainerSites.outputColumn(s, out);
        s.teleport(out.x() + 0.5, out.y() - 1, out.z() + 2.4);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 50), a.failure());
        assertEquals(0, a.walks());
        assertEquals(0, s.baritoneWalks);
        assertEquals(out, s.opened);
        assertTrue(s.clickTick <= 3, "aim, then click: " + s.clickTick);
        assertCleanClick(s);

        // the menu did not open: aim and click again from the same place
        a.retry();
        s.opened = null;
        assertEquals(TaskStatus.SUCCESS, s.run(a, 50));
        assertEquals(2, s.clicks);
        assertEquals(0, a.walks());
        assertCleanClick(s);
    }

    @Test
    void inSightButStandingInWaterTheBotWalksToADrySpotFirst() {
        ContainerSim s = new ContainerSim(13);
        Pos out = new Pos(10, 65, 20);
        ContainerSites.outputColumn(s, out);
        s.teleport(out.x() + 0.5, out.y() - 1, out.z() + 2.4);
        Pos feet = StandSpots.feetCell(out.x() + 0.5, out.y() - 1, out.z() + 2.4);
        s.set(feet, BlockSpec.of("minecraft:water"));
        s.set(feet.above(), BlockSpec.of("minecraft:water"));
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 600), a.failure() + " " + s.log);
        assertEquals(1, a.walks(), "it does not click from the water");
        Pos at = a.standingCell();
        assertFalse(s.hasFluid(at) || s.hasFluid(at.above()), "clicked from a dry cell: " + at);
        assertCleanClick(s);
    }

    @Test
    void aSpotBaritoneCannotReachIsLoggedAndTheNextIsTried() {
        ContainerSim s = new ContainerSim(13);
        Pos out = new Pos(10, 65, 20);
        double[] start = ContainerSites.outputColumn(s, out);
        s.teleport(start[0], start[1], start[2]);
        Pos first = new Pos(out.x() + 1, out.y() - 1, out.z() + 1);
        s.unpathable.add(first);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure() + " " + s.log);
        assertEquals(2, a.walks());
        assertFalse(a.standingCell().equals(first));
        assertTrue(s.log.stream().anyMatch(l -> l.contains(first.toString()) && l.contains("cannot walk there")), s.log.toString());
        assertEquals(out, s.opened);
        assertCleanClick(s);
    }

    @Test
    void theRememberedSpotIsWalkedToFirst() {
        ContainerSim s = new ContainerSim(14);
        Pos out = new Pos(10, 65, 20);
        double[] start = ContainerSites.outputColumn(s, out);
        s.teleport(start[0], start[1], start[2]);
        Pos remembered = new Pos(out.x() - 1, out.y() - 1, out.z() + 2);
        Approach<ContainerSim.Shot> a = approach(s, remembered);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure());
        assertEquals(remembered, a.standingCell());
        assertEquals(1, a.walks());
    }

    @Test
    void topRowSorterChestIsOpenedFromTheWalkway() {
        ContainerSim s = new ContainerSim(15);
        ContainerSites.sorterWall(s, 0, 8, 63, 10);
        s.targets = ContainerSites.sorterChest(4, 68, 10);
        s.teleport(1.02, 64 + 1.0 / 16, 11.5);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 600), a.failure() + " " + s.log);
        assertEquals(new Pos(4, 68, 10), s.opened, "the front half (the back one has no face in sight)");
        assertEquals(Aim.SOUTH, s.lastClick.face());
        assertEquals(64, a.standingCell().y(), "from the walkway");
        assertCleanClick(s);
    }

    @Test
    void topRowSorterChestThirtyBlocksAlongTheWalkwayIsOpenedFromTheWalkway() {
        ContainerSim s = new ContainerSim(22);
        ContainerSites.sorterWall(s, 0, 90, 63, 10);
        s.targets = ContainerSites.sorterChest(20, 68, 10);
        s.teleport(50.5, 64, 13.5);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 1200), a.failure() + " " + s.log);
        assertEquals(new Pos(20, 68, 10), s.opened);
        assertEquals(64, a.standingCell().y(), "from the walkway, not the top of the wall");
        assertEquals(1, a.walks());
        assertEquals(0, s.failedPaths);
        assertCleanClick(s);
    }

    @Test
    void aChestOnlyTheTopOfTheSorterWallSeesFailsWithoutAWalk() {
        ContainerSim s = new ContainerSim(23);
        ContainerSites.sorterWall(s, 0, 90, 63, 10);
        s.fill(-4, 68, 11, 94, 68, 11, GLASS); // a glass sill in front of the top row: the walkway sees none of it
        s.targets = ContainerSites.sorterChest(20, 68, 10);
        s.teleport(50.5, 64, 13.5);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.FAILED, s.run(a, 2000));
        assertTrue(a.failure().startsWith("no stand spot: no face center in sight"), a.failure());
        assertTrue(a.failure().contains("cannot be walked to"), a.failure());
        assertEquals(0, a.walks(), "no walk to the top of the wall");
        assertEquals(0, s.clicks);
    }

    @Test
    void chestBehindACornerIsReachedAroundTheWall() {
        ContainerSim s = new ContainerSim(16);
        s.fill(-12, 63, -12, 12, 63, 12, STONE);
        Pos c = new Pos(0, 64, 0);
        s.set(c, chest("south", "single"));
        s.fill(-1, 64, -1, -1, 66, 1, STONE);
        s.fill(1, 64, -1, 1, 66, 1, STONE);
        s.fill(-1, 64, -1, 1, 66, -1, STONE);
        s.fill(-1, 65, 0, 1, 65, 0, GLASS);
        s.fill(-8, 64, -3, 8, 66, -3, STONE);
        s.targets = List.of(c);
        s.teleport(0.5, 64, -5.5);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 1200), a.failure() + " " + s.log);
        assertEquals(c, s.opened);
        assertTrue(a.standingCell().z() >= 1, "in front of the alcove: " + a.standingCell());
        assertCleanClick(s);
    }

    @Test
    void unreachableChestFailsWithTheReason() {
        ContainerSim s = new ContainerSim(17);
        s.fill(-6, 63, -6, 6, 63, 6, STONE);
        Pos c = new Pos(0, 64, 0);
        s.fill(-1, 64, -1, 1, 65, 1, GLASS);
        s.set(c, chest("north", "single"));
        s.targets = List.of(c);
        s.teleport(3.5, 64, 3.5);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.FAILED, s.run(a, 100));
        assertTrue(a.failure().startsWith("no stand spot: no face center in sight"), a.failure());
        assertTrue(a.failure().contains("minecraft:glass"), a.failure());
        assertEquals(0, s.clicks);
        assertEquals(0, a.walks());
    }

    @Test
    void everySpotFailingEndsTheApproachWithEachReason() {
        ContainerSim s = new ContainerSim(18);
        Pos out = new Pos(10, 65, 20);
        double[] start = ContainerSites.outputColumn(s, out);
        s.teleport(start[0], start[1], start[2]);
        for (int x = out.x() - 6; x <= out.x() + 8; x++)
            for (int z = out.z() - 8; z <= out.z() + 8; z++) s.unpathable.add(new Pos(x, out.y() - 1, z));
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.FAILED, s.run(a, 2000));
        assertTrue(a.failure().startsWith("tried " + Approach.MAX_SPOTS + " stand spots: "), a.failure());
        assertTrue(a.failure().contains("cannot walk there"), a.failure());
        assertEquals(Approach.MAX_SPOTS, a.walks());
        assertEquals(0, s.clicks);
    }

    // ------------------------------------------------------------------------------------------ gate B: the food chest

    @Test
    void theFoodChestIsOpenedFromTheWalkwayNotFromThePitBehindIt() {
        ContainerSim s = new ContainerSim(19);
        Pos target = SupplyColumnSite.build(s, 0, 63, 0);
        double[] bot = SupplyColumnSite.botNorthEast(0, 63, 0);
        s.teleport(bot[0], bot[1], bot[2]);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure() + " " + s.log);
        assertEquals(target, s.opened);
        assertEquals(1, a.walks(), "the first spot was a walkable one");
        assertEquals(0, s.failedPaths);
        assertFalse(SupplyColumnSite.pit(0, 63, 0).contains(a.standingCell()));
        assertCleanClick(s);
    }

    @Test
    void aReachableSpotBaritoneStillCannotPathToCostsOneAttemptNotFour() {
        ContainerSim s = new ContainerSim(20);
        Pos out = new Pos(10, 65, 20);
        double[] start = ContainerSites.outputColumn(s, out);
        s.teleport(start[0], start[1], start[2]);
        Pos first = new Pos(out.x() + 1, out.y() - 1, out.z() + 1);
        s.unpathable.add(first); // something the flood cannot see (a mob, a door): Baritone finds no path
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure() + " " + s.log);
        assertEquals(1, s.failedPaths, "one failed path search, then the next spot");
        assertEquals(2, a.walks());
        assertTrue(s.log.stream().anyMatch(l -> l.contains(first.toString()) && l.contains("failed once")), s.log.toString());
        assertEquals(out, s.opened);
    }

    // ------------------------------------------------------- live 2b2t: arriving at walking speed, the stand spot missed

    /** The live case: Baritone runs into the stand cell at sprint speed along the sorter walkway and stops pressing. */
    private static ContainerSim sorterArrival(long seed) {
        ContainerSim s = new ContainerSim(seed);
        ContainerSites.sorterWall(s, 0, 30, 63, 10);
        s.targets = ContainerSites.sorterChest(10, 64, 10);
        s.teleport(26.5, 64 + 1.0 / 16, 11.5);
        s.momentum = true;
        s.arriveLateTicks = 2;
        return s;
    }

    @Test
    void theBotStandsStillOnTheCellCentreBeforeItAimsAfterArrivingAtSpeed() {
        ContainerSim s = sorterArrival(30);
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure() + " " + s.log);
        assertEquals(1, a.walks());
        Pos spot = a.standingCell();
        assertEquals(new Pos(13, 64, 11), spot, "the walkway cell nearest the bot that sees the chest");
        assertEquals(spot.x() + 0.5, s.clickEye[0], Approach.CENTRED, "centered along the walkway");
        assertEquals(spot.z() + 0.5, s.clickEye[2], Approach.CENTRED);
        assertTrue(a.sightMisses().isEmpty(), a.sightMisses().toString());
        assertCleanClick(s);
    }

    @Test
    void coastClickOpensWhileSlidingAndSoonerThanTheBrake() {
        ContainerSim braked = sorterArrival(30);
        Approach<ContainerSim.Shot> b = approach(braked, null);
        assertEquals(TaskStatus.SUCCESS, braked.run(b, 800), b.failure() + " " + braked.log);
        ContainerSim s = sorterArrival(30);
        Approach<ContainerSim.Shot> a = approach(s, null).coast(true);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure() + " " + s.log);
        assertEquals(1, a.coastClicks(), "clicked while sliding to rest");
        assertEquals(b.standingCell(), a.standingCell(), "the same stand cell");
        assertTrue(s.clickTick < braked.clickTick, "coast " + s.clickTick + " vs brake " + braked.clickTick);
        assertCleanClick(s);
    }

    @Test
    void withoutTheBrakeTheBotSlidesPastTheCentreAsOnTwoBTwoT() {
        ContainerSim s = sorterArrival(31);
        Approach<ContainerSim.Shot> a = approach(s, null).brake(false);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure() + " " + s.log);
        Pos spot = a.standingCell();
        assertTrue(Math.abs(s.clickEye[0] - (spot.x() + 0.5)) > Approach.CENTRED,
            "the old centering takes the pass through the center at speed for centered: " + s.clickEye[0]);
    }

    @Test
    void aCellToAvoidIsNotWalkedToAndAMissIsReported() {
        ContainerSim s = new ContainerSim(32);
        Pos out = new Pos(10, 65, 20);
        double[] start = ContainerSites.outputColumn(s, out);
        s.teleport(start[0], start[1], start[2]);
        Pos first = new Pos(out.x() + 1, out.y() - 1, out.z() + 1); // the spot chosen without the memory
        Approach<ContainerSim.Shot> a = approach(s, first).avoid(java.util.Set.of(first));
        assertEquals(TaskStatus.SUCCESS, s.run(a, 800), a.failure() + " " + s.log);
        assertFalse(a.standingCell().equals(first), "avoided: " + a.standingCell());
        assertEquals(1, a.walks());
        assertEquals(out, s.opened);
    }

    @Test
    void aSpotWithNoFaceInSightIsReportedAsASightMiss() {
        ContainerSim s = new ContainerSim(33);
        Pos out = new Pos(10, 65, 20);
        double[] start = ContainerSites.outputColumn(s, out);
        s.teleport(start[0], start[1], start[2]);
        Pos first = new Pos(out.x() + 1, out.y() - 1, out.z() + 1);
        s.hideAfterSearch = first; // the search sees a face centre from there; standing there, none is in sight
        Approach<ContainerSim.Shot> a = approach(s, first);
        assertEquals(TaskStatus.SUCCESS, s.run(a, 1200), a.failure() + " " + s.log);
        assertEquals(java.util.List.of(first), a.sightMisses());
        assertEquals(2, a.walks());
        assertEquals(out, s.opened);
    }

    @Test
    void aSpotOfTheStraightLineFallbackKeepsBaritonesRetries() {
        ContainerSim s = new ContainerSim(21);
        Pos out = new Pos(10, 65, 20);
        ContainerSites.outputColumn(s, out);
        s.teleport(out.x() + 1.5, out.y() + 6, out.z() - 0.5); // standing on nothing: reachability unknown
        for (int x = out.x() - 6; x <= out.x() + 8; x++)
            for (int y = out.y() - 4; y <= out.y() + 6; y++)
                for (int z = out.z() - 8; z <= out.z() + 8; z++) s.unpathable.add(new Pos(x, y, z));
        Approach<ContainerSim.Shot> a = approach(s, null);
        assertEquals(TaskStatus.FAILED, s.run(a, 2000));
        assertEquals(Approach.MAX_SPOTS * (Walker.MAX_FAILURES + 1), s.failedPaths, "4 tries per spot, as before");
    }
}
