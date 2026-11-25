package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.CreeperFight.Action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hit and run state machine against a creeper ({@link CreeperFight}). */
class CreeperFightTest {
    private static final int COOLDOWN = 14;
    private static final int WAIT = 40;
    private final CreeperFight f = new CreeperFight(7.5, WAIT);
    private long now = 100;

    private Action tick(double dist) {
        return tick(dist, false, true, false);
    }

    private Action tick(double dist, boolean fusing, boolean holding, boolean hit) {
        return f.step(now++, dist, fusing, holding, COOLDOWN, hit);
    }

    @Test
    void approachHitRetreatBeyondSafeWaitApproachAgain() {
        assertEquals(Action.APPROACH, tick(12));
        assertEquals(Action.APPROACH, tick(6));
        assertEquals(Action.APPROACH, tick(3.4));
        assertEquals(Action.STRIKE, tick(2.8), "within reach with a hit ready");
        assertEquals(Action.STRIKE, tick(2.8));
        assertEquals(Action.RETREAT, tick(2.8, false, true, true), "the hit is seen: away at once");
        assertEquals(1, f.hits());
        assertEquals(Action.RETREAT, tick(4));
        assertEquals(Action.RETREAT, tick(7.4), "7.5 is not enough, beyond it is");
        assertEquals(Action.WAIT, tick(7.6));
        for (int i = 0; i < WAIT - 1; i++) assertEquals(Action.WAIT, tick(9), "the 2 s out there, tick " + i);
        assertEquals(Action.APPROACH, tick(9), "then again");
        assertEquals(Action.STRIKE, tick(2.9));
        assertEquals(Action.RETREAT, tick(2.9, false, true, true));
        assertEquals(2, f.hits());
    }

    @Test
    void aHitNotSeenIsAssumedAfterAFewTicks() {
        assertEquals(Action.STRIKE, tick(2.5));
        for (int i = 1; i < CreeperFight.STRIKE_MAX; i++) assertEquals(Action.STRIKE, tick(2.5));
        assertEquals(Action.RETREAT, tick(2.5), "no health change seen: still backs off");
        assertEquals(1, f.hits());
    }

    @Test
    void withinThreeAndNoHitReadyBackOffFirst() {
        // the attack is not ready (cooldown after the last hit)
        assertEquals(Action.STRIKE, tick(2.5));
        assertEquals(Action.RETREAT, tick(2.5, false, true, true));
        assertEquals(Action.WAIT, tick(8));
        // it comes right back (14 ticks of cooldown not over): never stand next to it
        now += 2;
        assertEquals(Action.RETREAT, tick(2.9), "within 3 blocks with no hit ready");
    }

    @Test
    void noWeaponHeldMeansNoStrikeNextToIt() {
        assertEquals(Action.RETREAT, tick(2.9, false, false, false));
        f.step(now += 100, 9, false, false, COOLDOWN, false);
        assertEquals(Action.WAIT, tick(9, false, false, false), "nothing to hit with: no approach either");
    }

    @Test
    void aFuseRunningWithinFourBlocksAndNoHitReadyRetreatsAtOnce() {
        assertEquals(Action.RETREAT, tick(3.8, true, false, false));
        f.step(now += 100, 9, false, true, COOLDOWN, false);
        // ready and fusing but only 3.8 blocks away: not a retreat by itself (the hit is on its way)
        assertTrue(tick(3.8, true, true, false) != Action.RETREAT);
    }

    @Test
    void aFuseFurtherOnThanTheBudgetIsNoMomentToHit() {
        // its own flag shows a fuse running for 15 ticks at 5 blocks; then it is 2.9 away with a hit ready: away, no strike
        for (int i = 0; i < 15; i++) assertEquals(Action.APPROACH, tick(5, true, true, false));
        assertTrue(f.fuse() > CreeperFight.FUSE_BUDGET);
        assertEquals(Action.RETREAT, tick(2.9, true, true, false));
        assertEquals(0, f.hits());
    }

    @Test
    void theFuseDecaysOutThereAndACreeperThatArrivesEarlyIsStruckAtOnce() {
        assertEquals(Action.STRIKE, tick(2.5));
        assertEquals(Action.RETREAT, tick(2.5, false, true, true));
        assertEquals(Action.WAIT, tick(8));
        int fuse = f.fuse();
        for (int i = 0; i < 20; i++) tick(8.5);
        assertTrue(f.fuse() < fuse, "the swell counts down while it is beyond 7 blocks");
        // 22 ticks later the attack is ready again: it walks into reach during the wait
        assertEquals(Action.STRIKE, tick(2.9));
    }

    @Test
    void safeDistanceIsAboveSevenWhateverTheConfig() {
        assertEquals(7.1, new CreeperFight(3, 0).safe(), 1e-9);
        assertEquals(7.5, f.safe(), 1e-9);
    }

    @Test
    void aCreeperOutOfReachStillGetsWatchedWhenTheWeaponIsMissing() {
        assertEquals(Action.WAIT, tick(6, false, false, false), "no approach without a weapon");
        assertEquals(Action.APPROACH, tick(6, false, true, false));
    }
}
