package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePacerTest {
    @Test
    void startsAtStartValueClampedToBounds() {
        assertEquals(2, new AdaptivePacer(2, 1, 8).interval());
        assertEquals(8, new AdaptivePacer(20, 1, 8).interval());
        assertEquals(3, new AdaptivePacer(1, 3, 8).interval());
        // min below 1 is raised to 1, max below min raised to min
        AdaptivePacer p = new AdaptivePacer(0, 0, 0);
        assertEquals(1, p.min());
        assertEquals(1, p.max());
        assertEquals(1, p.interval());
    }

    @Test
    void rejectionDoublesOncePerEvent() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 16, 200);
        // clicks sent at ticks 10..13 (one per tick); the server rejects all four (a used-up stack)
        p.onReject(10, 15);
        assertEquals(2, p.interval());
        p.onReject(11, 16);
        p.onReject(12, 17);
        p.onReject(13, 18);
        assertEquals(2, p.interval(), "clicks already in flight at the first rejection do not double it again");
        assertEquals(4, p.rejections());
        p.onReject(16, 22); // a click sent after the change: a new event
        assertEquals(4, p.interval());
        p.onReject(30, 35);
        p.onReject(40, 45);
        assertEquals(8, p.interval(), "never above max");
    }

    @Test
    void unknownSlowsByOneTick() {
        AdaptivePacer p = new AdaptivePacer(2, 1, 8, 16, 200);
        p.onUnknown(10, 50);
        assertEquals(3, p.interval());
        p.onUnknown(11, 51);
        assertEquals(3, p.interval(), "same event");
        p.onUnknown(60, 100);
        assertEquals(4, p.interval());
        assertEquals(3, p.unknowns());
        assertEquals(3, p.misses());
    }

    @Test
    void recoveryHalvesAfterSuccessesOfClicksSentAtTheCurrentInterval() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 4, 200);
        p.onReject(0, 5);
        p.onReject(6, 10);
        p.onReject(11, 15);
        assertEquals(8, p.interval());
        // confirmations of clicks sent before the last change (tick 15) do not count
        for (int t = 0; t < 15; t++) p.onConfirm(t, 16);
        assertEquals(8, p.interval());
        assertEquals(0, p.streak());
        for (int i = 0; i < 3; i++) p.onConfirm(16 + 8 * i, 20 + 8 * i);
        assertEquals(8, p.interval());
        p.onConfirm(40, 44);
        assertEquals(4, p.interval(), "4 successes: halved");
        for (int i = 0; i < 4; i++) p.onConfirm(44 + 4 * i, 48 + 4 * i);
        assertEquals(2, p.interval());
        for (int i = 0; i < 4; i++) p.onLate(60 + 2 * i, 100 + 2 * i);
        assertEquals(1, p.interval(), "late confirmations are successes");
        for (int i = 0; i < 40; i++) p.onConfirm(110 + i, 114 + i);
        assertEquals(1, p.interval(), "never below min");
    }

    @Test
    void oddIntervalHalvesRoundingUp() {
        AdaptivePacer p = new AdaptivePacer(5, 1, 8, 2, 200);
        p.onConfirm(0, 4);
        p.onConfirm(1, 5);
        assertEquals(3, p.interval());
        p.onConfirm(6, 9);
        p.onConfirm(7, 10);
        assertEquals(2, p.interval());
    }

    @Test
    void rejectionResetsTheStreak() {
        AdaptivePacer p = new AdaptivePacer(2, 1, 8, 4, 200);
        for (int i = 0; i < 3; i++) p.onConfirm(i, i + 4);
        assertEquals(3, p.streak());
        p.onReject(5, 9);
        assertEquals(4, p.interval());
        assertEquals(0, p.streak());
    }

    @Test
    void recoveryWaitsForOneRoundTripOfClicks() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 4, 200);
        assertEquals(4, p.recoverStreak());
        for (int i = 0; i < AdaptivePacer.SAMPLES; i++) p.observeLatency(10);
        assertEquals(10, p.rttTicks());
        assertEquals(10, p.recoverStreak(), "interval 1, 10-tick round trip: 10 clicks in flight");
        p.onReject(0, 10);
        assertEquals(2, p.interval());
        assertEquals(5, p.recoverStreak());
    }

    @Test
    void windowFollowsTheHighestRecentLatency() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 16, 100);
        assertEquals(40, p.window(40), "no answers yet: the configured minimum");
        for (int i = 0; i < 10; i++) p.observeLatency(4);
        assertEquals(40, p.window(40));
        p.observeLatency(70); // a late confirmation in a lag spike
        assertEquals(80, p.window(40));
        p.observeLatency(500);
        assertEquals(100, p.window(40), "capped");
        for (int i = 0; i < AdaptivePacer.SAMPLES; i++) p.observeLatency(5);
        assertEquals(40, p.window(40), "the spike left the sample");
        assertEquals(150, p.window(150), "minimum above the cap wins");
    }

    @Test
    void tickFreeShorthandsKeepTheirMeaning() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 8, 4, 200);
        p.onMiss();
        assertEquals(2, p.interval());
        p.onMiss();
        assertEquals(4, p.interval());
        for (int i = 0; i < 4; i++) p.onConfirm();
        assertEquals(2, p.interval());
        assertEquals(2, p.misses());
        assertEquals(4, p.confirmations());
    }

    @Test
    void intervalAlwaysWithinBounds() {
        AdaptivePacer p = new AdaptivePacer(2, 2, 5, 3, 200);
        Random r = new Random(7);
        for (int t = 0; t < 10_000; t++) {
            int k = r.nextInt(10);
            long sent = t - r.nextInt(8);
            if (k == 0) p.onReject(sent, t);
            else if (k == 1) p.onUnknown(sent, t);
            else if (k == 2) p.onLate(sent, t);
            else p.onConfirm(sent, t);
            assertTrue(p.interval() >= 2 && p.interval() <= 5, "interval " + p.interval());
        }
    }

    @Test
    void setBoundsReclamps() {
        AdaptivePacer p = new AdaptivePacer(8, 1, 8);
        p.setBounds(1, 4);
        assertEquals(4, p.interval());
        p.setBounds(6, 9);
        assertEquals(6, p.interval());
    }

    @Test
    void readyHonoursInterval() {
        AdaptivePacer p = new AdaptivePacer(2, 1, 8);
        assertFalse(p.ready(10, 9));
        assertTrue(p.ready(11, 9));
        assertTrue(p.ready(12, 9));
    }
}
