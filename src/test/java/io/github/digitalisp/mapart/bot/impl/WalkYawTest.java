package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The short turn for Baritone's yaw requests (Grim AimModulo360 against ZenithProxy's single wrap). */
class WalkYawTest {
    @Test
    void theLocalJumpBecomesAShortTurn() {
        // local run: the bot at 430.0, Baritone asking for -260.9; ZenithProxy's Bot turned -330.9 to 99.1
        assertEquals(-330.9f, WalkYaw.botTurn(430.0f, -260.9f), 1e-3);
        Float y = WalkYaw.shortTurn(430.0f, -260.9f);
        assertNotNull(y);
        assertEquals(459.1f, y, 1e-3, "the same heading as 99.1, reached by +29.1");
        assertEquals(29.1f, WalkYaw.botTurn(430.0f, y), 1e-3);
    }

    @Test
    void turnsTheBotAlreadyMakesShortAreLeftAlone() {
        assertNull(WalkYaw.shortTurn(170f, -170f), "the Bot's wrap: +20");
        assertNull(WalkYaw.shortTurn(10f, 90f));
        assertNull(WalkYaw.shortTurn(600f, 450f), "a relative request near the yaw");
        assertNull(WalkYaw.shortTurn(-30f, 150f), "exactly 180 is fine");
    }

    @Test
    void neverMoreThanHalfATurn() {
        for (float cur = -1000f; cur <= 1000f; cur += 37.3f) {
            for (float req = -400f; req <= 400f; req += 11.7f) {
                Float y = WalkYaw.shortTurn(cur, req);
                float turn = y == null ? WalkYaw.botTurn(cur, req) : WalkYaw.botTurn(cur, y);
                assertTrue(Math.abs(turn) <= 180.001f, cur + " -> " + req + ": " + turn);
                if (y != null) {
                    double same = ((y - req) % 360 + 360) % 360;
                    assertTrue(same < 1e-3 || same > 360 - 1e-3, "same heading: " + y + " vs " + req);
                }
            }
        }
    }
}
