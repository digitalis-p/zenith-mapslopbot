package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-off of a delayed block a cancelled break task left on the server: the next break task gets it, and the bot
 * layer stays busy (no keep-alive) until it shows air or its deadline passes.
 */
class PendingDelayedTest {
    private static final Pos P = new Pos(3, 64, 4);

    @Test
    void busyUntilAirOrDeadline() {
        PendingDelayed d = new PendingDelayed();
        Set<Pos> air = new HashSet<>();
        assertFalse(d.pending(1_000, air::contains), "nothing recorded");

        d.set(P, 2_000);
        assertTrue(d.pending(1_000, air::contains));
        assertTrue(d.pending(1_999, air::contains));
        air.add(P);
        assertFalse(d.pending(1_500, air::contains), "the server broke it");
        assertFalse(d.pending(1_500, p -> false), "forgotten once it showed air");

        d.set(P, 2_000);
        assertFalse(d.pending(2_000, p -> false), "deadline passed");
        assertNull(d.take(1_000), "forgotten after the deadline");
    }

    @Test
    void theNextBreakTaskGetsItAndTheBotStaysBusyMeanwhile() {
        PendingDelayed d = new PendingDelayed();
        assertNull(d.take(0));
        d.set(P, 5_000);
        PendingDelayed.Handoff h = d.take(1_000);
        assertEquals(P, h.pos());
        assertEquals(5_000, h.untilMillis());
        assertTrue(d.pending(1_000, p -> false), "still busy while the next task waits for it");
        assertNull(d.take(6_000), "past its deadline");
        d.set(P, 5_000);
        d.clear();
        assertFalse(d.pending(1_000, p -> false), "a new connection forgets it");
    }
}
