package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The take-off hold (elytra stage 5): after the bot's ticks start (live 2026-10-25 08:33, a take-off
 * 2 s after the owner left the account, set back 20.6 blocks mid-flight), after a server teleport, while and after the
 * server is silent (08:47, frozen 37 s) and after a login until the world time has come 10 times (09:10, a take-off 7 s
 * after a redeploy with no boost seen yet).
 */
class TakeoffGuardTest {
    private static final long HOLD = 10_000;

    /** A guard past a login with its 10 world-time updates, the time at {@code t0} + 10 s, nothing armed since. */
    private static TakeoffGuard settled(long t0) {
        TakeoffGuard g = new TakeoffGuard();
        g.login(t0);
        for (int i = 1; i <= TakeoffGuard.TIME_UPDATES; i++) g.timeUpdate(t0 + i * 1000L);
        walk(g, t0 + 10_000, TakeoffGuard.GROUND_MOVES);
        return g;
    }

    /** {@code n} ticks of walking on the ground (0.2 blocks a tick) from {@code t}, the server's pulse every tick. */
    private static long walk(TakeoffGuard g, long t, int n) {
        for (int i = 0; i <= n; i++) {
            g.pulse(t);
            g.tick(true, 0.2 * i, 0, t);
            t += 50;
        }
        return t;
    }

    @Test
    void afterALoginNoTakeOffUntilTenWorldTimeUpdatesCame() {
        TakeoffGuard g = new TakeoffGuard();
        long t = 1_000_000;
        g.login(t);
        // live 09:10: 7 s after the bot's ticks started, the world time had come 7 times
        for (int i = 1; i <= 7; i++) g.timeUpdate(t + i * 1000L);
        walk(g, t + 7000, 20);
        String why = g.refusal(t + 7_500, HOLD);
        assertNotNull(why);
        assertTrue(why.contains("7 of 10 world-time updates"), why);
        for (int i = 8; i <= 10; i++) g.timeUpdate(t + i * 1000L);
        long end = walk(g, t + 10_000, TakeoffGuard.GROUND_MOVES);
        assertNull(g.refusal(end, HOLD), "10 updates, 10 s and the moves on the ground");
    }

    @Test
    void afterTheBotsTicksStartTheHoldAndGroundMovesAndTheGearCheck() {
        long t = 2_000_000;
        TakeoffGuard g = settled(t);
        long now = t + 30_000;
        g.pulse(now);
        assertNull(g.refusal(now, HOLD));
        // the owner left the account: the bot's ticks start again
        g.botTicksStarted(now);
        assertTrue(g.handoffPending());
        for (int i = 1; i <= 3; i++) g.timeUpdate(now + i * 1000L);
        assertNotNull(g.refusal(now + 2_000, HOLD), "2 s after (live 08:33:40: the take-off came then)");
        // the hold over, but no move on the ground yet (standing still: no confirmed movement)
        for (int i = 0; i < 10; i++) {
            g.pulse(now + 10_000 + i * 50);
            g.tick(true, 5, 5, now + 10_000 + i * 50);
        }
        String why = g.refusal(now + 10_500, HOLD);
        assertNotNull(why);
        assertTrue(why.contains("moves on the ground"), why);
        long end = walk(g, now + 11_000, TakeoffGuard.GROUND_MOVES);
        assertNull(g.refusal(end, HOLD));
        assertTrue(g.handoffPending(), "the gear check is FlightLeg's, once");
        g.handoffChecked();
        assertFalse(g.handoffPending());
    }

    @Test
    void aTeleportArmsTheHoldAndMovesInTheAirOrJumpsDoNotCount() {
        long t = 3_000_000;
        TakeoffGuard g = settled(t);
        long now = t + 40_000;
        g.teleport(now);
        long at = now + 11_000;
        // moves in the air, and a 20-block jump (another teleport's), count for nothing
        for (int i = 0; i < 10; i++) {
            g.pulse(at);
            g.tick(false, i * 0.5, 0, at);
            at += 50;
        }
        g.pulse(at);
        g.tick(true, 30, 0, at);
        String why = g.refusal(at, HOLD);
        assertNotNull(why);
        assertTrue(why.contains("a server teleport"), why);
        assertNull(g.refusal(walk(g, at + 50, TakeoffGuard.GROUND_MOVES), HOLD));
    }

    @Test
    void aSilentServerRefusesAndTheHoldCountsFromTheSilencesEnd() {
        long t = 4_000_000;
        TakeoffGuard g = settled(t);
        long now = t + 50_000;
        g.pulse(now);
        assertNull(g.refusal(now, HOLD));
        // live 08:47:21 to 08:47:58: nothing from the server while the client ticked on
        g.pulse(now);
        long at = now;
        for (int i = 0; i < 37 * 20; i++) {
            at += 50;
            g.tick(true, 0, 0, at);
        }
        String why = g.refusal(at, HOLD);
        assertNotNull(why);
        assertTrue(why.contains("silent"), why);
        // the server speaks again: the hold runs 10 s from then
        long back = at + 50;
        g.pulse(back);
        g.tick(true, 0, 0, back);
        g.pulse(back + 5_000);
        why = g.refusal(back + 5_000, HOLD);
        assertNotNull(why);
        assertTrue(why.contains("went silent"), why);
        assertNull(g.refusal(walk(g, back + 10_100, TakeoffGuard.GROUND_MOVES), HOLD));
    }
}
