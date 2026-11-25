package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathChurnTest {
    /** Live 2026-11-15: a new path every 10-12 ticks, the bot 3.5 blocks west and east in turn. */
    @Test
    void aWalkThatTurnsBackAndForthAroundOneSpotIsGivenUp() {
        PathChurn c = new PathChurn();
        long now = 339;
        boolean given = false;
        int asks = 0;
        for (; asks < 200 && !given; asks++) {
            given = c.asked(now, asks % 2 == 0 ? 0.0 : -3.5, 13.5);
            now += asks % 2 == 0 ? 12 : 10;
        }
        assertTrue(given);
        assertEquals(PathChurn.LIMIT, asks);
        assertEquals(PathChurn.LIMIT, c.asks());
        assertTrue(c.span(now) < 30 * 20, "within half a minute: " + c.span(now));
    }

    @Test
    void aWalkThatGetsOnIsNotGivenUp() {
        PathChurn c = new PathChurn();
        // a path request every 10 ticks while the bot walks on: 2 blocks a request
        for (int i = 0; i < 500; i++) assertFalse(c.asked(i * 10L, i * 2.0, 0), "request " + i);
        // around one spot, but with pauses: a retry now and then
        PathChurn d = new PathChurn();
        for (int i = 0; i < 500; i++) assertFalse(d.asked(i * (PathChurn.GAP_TICKS + 1L), 0.2 * (i % 3), 0), "request " + i);
    }

    @Test
    void theCountStartsAnewOnceTheBotLeftTheSpot() {
        PathChurn c = new PathChurn();
        for (int i = 0; i < PathChurn.LIMIT - 1; i++) assertFalse(c.asked(i * 10L, 0, 0));
        assertFalse(c.asked(PathChurn.LIMIT * 10L, PathChurn.RADIUS + 0.5, 0), "left the spot: counted from here");
        assertEquals(1, c.asks());
        c.reset();
        assertEquals(0, c.asks());
        assertEquals(0, c.span(1_000));
    }
}
