package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FallGuard#effective}: ZenithProxy's {@code MovementDescend} counts a fall from the feet level down to the block
 * landed on and refuses it above {@code maxFallHeightNoWater + 1}; the soul sand cross (feet 179) onto the floor (soul
 * sand at y172, feet 173) counts 7, so the limit must be 6 = the feet-to-feet drop. The default 3 stays the floor.
 */
class FallGuardTest {
    @Test
    void theLimitIsTheFeetToFeetDropNeverBelowTheDefault() {
        assertEquals(6, FallGuard.effective(6));
        assertEquals(3, FallGuard.effective(2));
        assertEquals(3, FallGuard.effective(0));
    }
}
