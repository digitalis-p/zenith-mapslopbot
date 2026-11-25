package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The yaw unwind before a Baritone walk: how many ticks it takes, and no snap. */
class UnwindYawTest {
    private static int ticksToUnwind(float yaw) {
        int ticks = 0;
        Float next;
        while ((next = Clicker.unwindStep(yaw)) != null) {
            assertTrue(Math.abs(next - yaw) <= 170.001f, "one tick turns at most 170 deg: " + yaw + " -> " + next);
            yaw = next;
            assertTrue(++ticks < 20, "does not end");
        }
        assertTrue(Math.abs(yaw) <= 180f);
        return ticks;
    }

    @Test
    void insideTheRangeNeedsNoUnwind() {
        assertNull(Clicker.unwindStep(0f));
        assertNull(Clicker.unwindStep(-180f));
        assertNull(Clicker.unwindStep(180f));
        assertNotNull(Clicker.unwindStep(180.5f));
    }

    @Test
    void theUnwindTakesAFewTicksAtMost() {
        // a typical 200-540 is 1-3 ticks (0.05-0.15 s), nowhere near the 2.2 s the roadmap modelled
        assertEquals(1, ticksToUnwind(200f));
        assertTrue(ticksToUnwind(-540f) <= 3);
        assertTrue(ticksToUnwind(1080f) <= 7);
        int worst = 0;
        for (float y = -720f; y <= 720f; y += 7.3f) worst = Math.max(worst, ticksToUnwind(y));
        assertTrue(worst <= 4, "within two turns: " + worst);
    }
}
