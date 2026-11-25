package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.AdaptivePacer.Policy;
import io.github.digitalisp.mapart.bot.impl.AdaptivePacer.Refusal;
import io.github.digitalisp.mapart.config.MapArtConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The BURST pacer policy ({@link PlacePacing}, {@link AdaptivePacer#onRefusal}) and the lane clock fix
 * ({@link AdaptivePacer#newRun}).
 */
class BurstPacerTest {
    private static AdaptivePacer burst() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 16, 200);
        p.configure(PlacePacing.DEFAULT);
        return p;
    }

    @Test
    void configDefaultsAreBurstAndLegacyIsTheKillSwitch() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertEquals(PlacePacing.DEFAULT, PlacePacing.of(b));
        b.placePacer = "legacy";
        b.placeStackSettleTicks = 2;
        b.placeStackSwitch = false;
        b.placeHotbarBelady = false;
        assertEquals(PlacePacing.LEGACY, PlacePacing.of(b), "the four keys restore the previous behavior");
        assertEquals(Policy.BURST, PlacePacing.policyOf("whatever"), "unknown values count as BURST");
        assertEquals(Policy.LEGACY, PlacePacing.policyOf(" LEGACY "));
        assertEquals(16, PlacePacing.LEGACY.recover());
        assertEquals(8, PlacePacing.DEFAULT.recover());
    }

    @Test
    void stackEndsNeverSlowItDown() {
        AdaptivePacer p = burst();
        for (int i = 0; i < 5; i++) p.onRefusal(10 + i, 20 + i, Refusal.STACK_END);
        assertEquals(1, p.interval());
        assertEquals(5, p.stackEnds());
        assertEquals(5, p.rejections(), "still counted among the rejections");
    }

    @Test
    void anIsolatedRefusalChangesNothing() {
        AdaptivePacer p = burst();
        p.onRefusal(10, 15, Refusal.REFUSED);
        p.onRefusal(11, 16, Refusal.REFUSED); // clicks of the same moment: the same event
        p.onRefusal(12, 17, Refusal.REFUSED);
        assertEquals(1, p.interval());
        assertEquals(1, p.isolated());
        p.onRefusal(300, 305, Refusal.REFUSED); // 290 ticks later: isolated again
        assertEquals(1, p.interval());
        assertEquals(2, p.isolated());
        assertEquals(0, p.bursts());
        assertEquals(4, p.rejections());
    }

    @Test
    void aSecondEventWithinTheWindowDoubles() {
        AdaptivePacer p = burst();
        p.onRefusal(10, 15, Refusal.REFUSED);
        p.onRefusal(30, 35, Refusal.REFUSED); // a new event 20 ticks later
        assertEquals(2, p.interval());
        assertEquals(1, p.bursts());
        p.onRefusal(31, 36, Refusal.REFUSED); // same event
        p.onRefusal(33, 60, Refusal.REFUSED); // a new event, but its click was sent before the change: answered already
        assertEquals(2, p.interval());
        p.onRefusal(70, 75, Refusal.REFUSED); // sent after the change, within the window: doubles again
        assertEquals(4, p.interval());
    }

    @Test
    void dropsNeverSlowEitherPolicy() {
        for (PlacePacing pacing : new PlacePacing[]{PlacePacing.LEGACY, PlacePacing.DEFAULT}) {
            AdaptivePacer p = burst();
            p.configure(pacing);
            for (int i = 0; i < 100; i++) p.onRefusal(i, i + 5, Refusal.DROPPED);
            assertEquals(1, p.interval());
            assertEquals(100, p.drops());
            assertEquals(0, p.rejections());
        }
    }

    @Test
    void recoveryTakesBurstRecoverSuccessesAndLongerAfterAFailedSpeedUp() {
        AdaptivePacer p = burst();
        p.onRefusal(0, 5, Refusal.REFUSED);
        p.onRefusal(10, 15, Refusal.REFUSED); // interval 2 at tick 15
        assertEquals(8, p.recoverStreak());
        for (int i = 0; i < 8; i++) p.onConfirm(16 + 2 * i, 20 + 2 * i);
        assertEquals(1, p.interval(), "8 successes: halved at tick 34");
        p.onRefusal(40, 45, Refusal.REFUSED); // 11 ticks after the speed-up: it did not hold
        assertEquals(2, p.interval());
        assertEquals(16, p.recoverStreak(), "the next recovery takes twice as long");
        for (int i = 0; i < 16; i++) p.onConfirm(46 + 2 * i, 50 + 2 * i);
        assertEquals(1, p.interval(), "halved at tick 80");
        p.onRefusal(85, 90, Refusal.REFUSED); // again right after
        assertEquals(32, p.recoverStreak());
        for (int i = 0; i < 32; i++) p.onConfirm(91 + 2 * i, 95 + 2 * i);
        assertEquals(1, p.interval(), "halved at tick 157");
        p.onRefusal(390, 395, Refusal.REFUSED);
        p.onRefusal(400, 405, Refusal.REFUSED); // long after the speed-up: back to the short recovery
        assertEquals(8, p.recoverStreak());
        for (int k = 0; k < 10; k++) { // never more than MAX_BOOST times
            for (int i = 0; i < p.recoverStreak(); i++) p.onConfirm(1000 * (k + 1) + i, 1000 * (k + 1) + i + 4);
            long up = 1000 * (k + 1) + p.recoverStreak() + 3;
            p.onRefusal(up - 9, up - 5, Refusal.REFUSED);
            p.onRefusal(up + 1, up + 5, Refusal.REFUSED);
        }
        assertEquals(8 * AdaptivePacer.MAX_BOOST, p.recoverStreak());
    }

    @Test
    void legacyIgnoresTheKind() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 16, 200);
        p.configure(PlacePacing.LEGACY);
        p.onRefusal(10, 15, Refusal.STACK_END);
        assertEquals(2, p.interval(), "LEGACY: every rejection doubles");
        p.onRefusal(20, 25, Refusal.REFUSED);
        assertEquals(4, p.interval());
        assertEquals(16, p.recoverStreak());
        assertEquals(0, p.stackEnds());
    }

    @Test
    void configureKeepsTheIntervalAndTheCounters() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 16, 200);
        p.onReject(10, 15);
        p.configure(PlacePacing.DEFAULT);
        assertEquals(2, p.interval());
        assertEquals(1, p.rejections());
        assertEquals(Policy.BURST, p.policy());
        assertTrue(p.toString().contains("BURST: stack ends=0"), p.toString());
    }

    /**
     * Live 2026-10-11: the pacer outlives the lanes, each lane task counts its ticks from 0. A change at tick 1,500 of
     * one lane made every outcome of the next lane look older than that change: the interval stood still (here at 2)
     * until the new lane's clock passed 1,500 ticks. {@link AdaptivePacer#newRun} at the lane start forgets it.
     */
    @Test
    void aNewLaneDoesNotInheritTheLastLanesClock() {
        for (PlacePacing pacing : new PlacePacing[]{PlacePacing.LEGACY, PlacePacing.DEFAULT}) {
            AdaptivePacer frozen = new AdaptivePacer(1, 1, 8, 16, 200);
            frozen.configure(pacing);
            if (pacing.policy() == Policy.BURST) frozen.onRefusal(1480, 1485, Refusal.REFUSED);
            frozen.onRefusal(1490, 1500, AdaptivePacer.Refusal.REFUSED); // refusal burst late in lane A: interval 2
            AdaptivePacer fixed = new AdaptivePacer(1, 1, 8, 16, 200);
            fixed.configure(pacing);
            if (pacing.policy() == Policy.BURST) fixed.onRefusal(1480, 1485, Refusal.REFUSED);
            fixed.onRefusal(1490, 1500, AdaptivePacer.Refusal.REFUSED);
            fixed.newRun(); // lane B starts
            for (int i = 0; i < 40; i++) { // lane B: clicks every other tick from tick 10, all placed
                frozen.onConfirm(10 + 2 * i, 14 + 2 * i);
                fixed.onConfirm(10 + 2 * i, 14 + 2 * i);
            }
            assertEquals(2, frozen.interval(), pacing.policy() + ": without newRun the pacer stood still");
            assertEquals(1, fixed.interval(), pacing.policy() + ": recovered in the new lane");
            if (pacing.policy() == Policy.BURST) {
                frozen.onRefusal(90, 95, Refusal.REFUSED);
                fixed.onRefusal(90, 95, Refusal.REFUSED);
            }
            frozen.onRefusal(100, 105, AdaptivePacer.Refusal.REFUSED);
            fixed.onRefusal(100, 105, AdaptivePacer.Refusal.REFUSED);
            assertEquals(2, frozen.interval(), "nor could it slow down");
            assertEquals(2, fixed.interval());
        }
    }
}
