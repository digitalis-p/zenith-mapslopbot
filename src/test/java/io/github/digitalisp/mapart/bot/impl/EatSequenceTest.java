package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.EatSequence.Seen;
import io.github.digitalisp.mapart.bot.impl.EatSequence.Step;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EatSequenceTest {
    private long now;
    private long ms;
    private final EatSequence seq = new EatSequence(() -> ms);

    private Step tick(boolean still, boolean canUse, boolean holding, int count, int hunger) {
        ms += 50;
        return seq.next(++now, new Seen(still, canUse, count > 0, holding, count, hunger));
    }

    /** Ticks until the sequence asks for something other than WAIT (or {@code max} ticks). */
    private Step until(int max, boolean still, boolean canUse, boolean holding, int count, int hunger) {
        Step s = Step.WAIT;
        for (int i = 0; i < max && s == Step.WAIT; i++) s = tick(still, canUse, holding, count, hunger);
        return s;
    }

    @Test
    void noSlotChangeOrClickWhileMoving() {
        for (int i = 0; i < 50; i++) assertEquals(Step.WAIT, tick(false, true, false, 10, 12), "tick " + i);
    }

    @Test
    void holdOnlyAfterEnoughStillTicksInARow() {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < EatSequence.SETTLE_TICKS - 1; i++) steps.add(tick(true, true, false, 10, 12));
        steps.add(tick(false, true, false, 10, 12)); // moved: the count starts again
        for (int i = 0; i < EatSequence.SETTLE_TICKS - 1; i++) steps.add(tick(true, true, false, 10, 12));
        assertTrue(steps.stream().allMatch(s -> s == Step.WAIT), steps.toString());
        assertEquals(Step.HOLD, tick(true, true, false, 10, 12));
    }

    @Test
    void usesOnlyWhenHoldingAndTheInputIsOwned() {
        assertEquals(Step.HOLD, until(10, true, true, false, 10, 12));
        assertEquals(Step.HOLD, tick(true, true, false, 10, 12), "keeps asking until the food is in hand");
        assertEquals(Step.WAIT, tick(true, false, true, 10, 12), "held, but the input was not ours");
        assertEquals(Step.USE, tick(true, true, true, 10, 12));
        assertTrue(seq.using());
    }

    @Test
    void sendsNothingWhileEatingThenWaitsBeforeDone() {
        assertEquals(Step.USE, until(10, true, true, true, 10, 12));
        for (int i = 0; i < 31; i++) assertEquals(Step.WAIT, tick(true, true, true, 10, 12), "eating tick " + i);
        assertEquals(Step.WAIT, tick(true, true, true, 9, 18)); // consumed
        assertFalse(seq.using());
        for (int i = 1; i < EatSequence.AFTER_TICKS; i++) assertEquals(Step.WAIT, tick(true, true, true, 9, 18));
        assertEquals(Step.DONE, tick(true, true, true, 9, 18));
        assertEquals(1, seq.uses());
    }

    @Test
    void hungerRiseCountsAsConsumedEvenIfTheCountLags() {
        assertEquals(Step.USE, until(10, true, true, true, 10, 12));
        assertEquals(Step.WAIT, tick(true, true, true, 10, 18));
        assertFalse(seq.using());
    }

    @Test
    void movedWhileEatingReleasesAndSettlesAgain() {
        assertEquals(Step.USE, until(10, true, true, true, 10, 12));
        assertEquals(Step.RELEASE, tick(false, true, true, 10, 12));
        assertFalse(seq.using());
        for (int i = 0; i < EatSequence.SETTLE_TICKS - 1; i++) assertEquals(Step.WAIT, tick(true, true, true, 10, 12));
        assertEquals(Step.USE, tick(true, true, true, 10, 12), "second use only after settling again");
        assertEquals(2, seq.uses());
    }

    @Test
    void notConsumedIsRetriedThenFails() {
        for (int u = 0; u < EatSequence.MAX_USES; u++) {
            assertEquals(Step.USE, until(20, true, true, true, 10, 12));
            assertEquals(Step.RELEASE, until(EatSequence.USE_TIMEOUT + 1, true, true, true, 10, 12));
        }
        assertEquals(Step.FAIL, tick(true, true, true, 10, 12));
        assertTrue(seq.reason().contains("did not consume"), seq.reason());
    }

    @Test
    void slowServerGetsTheWallTimeBeforeARelease() {
        // bot ticks 10x faster than wall time (speed mode) or a lagging server: ticks alone never release the use
        assertEquals(Step.USE, until(20, true, true, true, 10, 12));
        for (int i = 0; i < EatSequence.USE_TIMEOUT * 5; i++) {
            ms -= 45; // 5 ms of wall time per tick
            Step s = tick(true, true, true, 10, 12);
            assertEquals(Step.WAIT, s, "tick " + i + " (2.5 s of wall time in all)");
        }
        ms += EatSequence.USE_TIMEOUT_MS;
        assertEquals(Step.RELEASE, tick(true, true, true, 10, 12));
    }

    @Test
    void fullHungerIsDoneWithoutEating() {
        assertEquals(Step.DONE, tick(true, true, true, 10, 20));
        assertEquals(0, seq.uses());
    }

    @Test
    void noFoodFails() {
        assertEquals(Step.FAIL, tick(true, true, false, 0, 12));
        assertTrue(seq.reason().contains("no food"), seq.reason());
    }
}
