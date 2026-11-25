package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WalkTask#walkTimeout(double)}: a near target can be far to walk (the soul sand cross: 3 blocks from the floor
 * under it in x/z, about 280 blocks by the one-way east walkway, about 65 s at walking speed = 1,300 ticks).
 */
class WalkTimeoutTest {
    @Test
    void nearTargetsGetTheTimeOfALongWayRound() {
        assertEquals(600 + 96 * 20, WalkTask.walkTimeout(3.0));
        assertTrue(WalkTask.walkTimeout(3.0) > 1_300 * 1.5);
        assertEquals(600 + 200 * 20, WalkTask.walkTimeout(200.0), "far targets unchanged");
    }
}
