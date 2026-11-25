package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Silent looks turn off for the run after {@link SilentLookGuard#THRESHOLD} server setbacks within
 * {@link SilentLookGuard#LOOK_WINDOW_MILLIS} of a silent look in {@link SilentLookGuard#COUNT_WINDOW_MILLIS}.
 */
class SilentLookGuardTest {
    @Test
    void twoSetbacksAfterSilentLooksTurnThemOff() {
        SilentLookGuard g = new SilentLookGuard();
        g.sent(1_000);
        assertFalse(g.setback(1_400), "one is not enough");
        assertFalse(g.disabled());
        assertNull(g.reason());
        g.sent(10_000);
        assertTrue(g.setback(10_800), "the second one within a minute turns them off");
        assertTrue(g.disabled());
        assertEquals("2 server setbacks within 1.5 s of a silent look in the last 60 s: LOOK cells (pointed dripstone,"
            + " glow lichen, candles, ...) now turn the bot's real rotation; set build.rotatePlayer true to keep it so"
            + " after a restart", g.reason());
    }

    @Test
    void aSetbackCountsUpToOnePointFiveSecondsAfterTheLook() {
        SilentLookGuard g = new SilentLookGuard();
        g.sent(0);
        assertFalse(g.setback(1_500), "on the edge: counted");
        g.sent(5_000);
        assertFalse(g.setback(6_501), "1.501 s after the look: not counted");
        assertFalse(g.disabled());
        g.sent(7_000);
        assertTrue(g.setback(7_000), "at once: counted, the second one (the 1.5 s one counted too)");
    }

    @Test
    void setbacksWithoutALookBeforeThemDoNotCount() {
        SilentLookGuard g = new SilentLookGuard();
        for (long t = 0; t < 30_000; t += 300) assertFalse(g.setback(t));
        g.sent(40_000);
        assertFalse(g.setback(39_900), "the look came after the setback");
        g.sent(50_000);
        assertFalse(g.setback(52_000), "2 s after the look");
        assertFalse(g.setback(53_000));
        assertFalse(g.disabled());
    }

    @Test
    void oneLookDrawsOneCountedSetbackHoweverManyFollow() {
        SilentLookGuard g = new SilentLookGuard();
        g.sent(0);
        for (long t = 50; t <= 1_000; t += 50) assertFalse(g.setback(t), "a burst after one look counts once");
        assertFalse(g.disabled());
        g.sent(2_000);
        g.sent(2_050);
        assertTrue(g.setback(2_100), "a second look's setback is the second count");
    }

    @Test
    void looksEveryTickEachDrawAtMostOneSetback() {
        SilentLookGuard g = new SilentLookGuard();
        for (long t = 0; t <= 1_000; t += 50) g.sent(t);
        assertFalse(g.setback(1_020));
        assertTrue(g.setback(1_040), "the next unclaimed look (950) drew this one");
    }

    @Test
    void countedSetbacksExpireAfterAMinute() {
        SilentLookGuard g = new SilentLookGuard();
        g.sent(0);
        assertFalse(g.setback(500));
        g.sent(61_000);
        assertFalse(g.setback(61_400), "the first one is 60.9 s old: expired");
        assertFalse(g.disabled());
        g.sent(62_000);
        assertTrue(g.setback(62_500), "two within the minute again");
    }

    @Test
    void theNoticeComesOnceRightAfterTurningOff() {
        SilentLookGuard g = new SilentLookGuard();
        assertNull(g.notice());
        g.sent(0);
        g.setback(100);
        assertNull(g.notice(), "still on");
        g.sent(1_000);
        g.setback(1_100);
        String n = g.notice();
        assertNotNull(n);
        assertEquals(g.reason(), n);
        assertNull(g.notice(), "only once");
        assertNotNull(g.reason(), "the reason stays");
    }

    @Test
    void offStaysOffForTheRun() {
        SilentLookGuard g = new SilentLookGuard();
        g.sent(0);
        g.setback(100);
        g.sent(200);
        assertTrue(g.setback(300));
        String reason = g.reason();
        for (long t = 1_000; t < 3_600_000; t += 60_000) {
            g.sent(t);
            assertFalse(g.setback(t + 100), "never turned off a second time");
        }
        assertTrue(g.disabled());
        assertEquals(reason, g.reason());
        g.notice();
        assertNull(g.notice());
    }
}
