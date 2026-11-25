package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.DismountKey.Action;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sneak key of the dismount as player commands (docs/COLOR_SUPPRESSION.md, RIDE_END): 2b2t (1.21.4) gets out
 * of a cart on the "press shift key" command, which ZenithProxy's Bot never sends in a vehicle (live 2026-11-13: "still
 * in the cart after 60 ticks of sneaking").
 */
class DismountKeyTest {
    @Test
    void theKeyGoesDownOnceOnTheFirstTickAndUpOnceWhenOut() {
        DismountKey k = new DismountKey();
        assertEquals(Action.PRESS, k.inVehicle(100));
        for (long t = 101; t < 110; t++) assertEquals(Action.NONE, k.inVehicle(t), "no second press while it is down");
        assertTrue(k.down());
        assertEquals(Action.RELEASE, k.release());
        assertEquals(Action.NONE, k.release(), "one release per press");
        assertFalse(k.down());
        assertEquals(1, k.presses());
    }

    @Test
    void stillInTheCartItIsLetGoAndPressedAgainAlwaysInPairs() {
        DismountKey k = new DismountKey();
        List<Action> sent = new ArrayList<>();
        for (long t = 0; t <= 100; t++) {
            Action a = k.inVehicle(t);
            if (a != Action.NONE) sent.add(a);
        }
        Action end = k.release();
        if (end != Action.NONE) sent.add(end);
        assertTrue(k.presses() >= 3, sent.toString());
        for (int i = 0; i < sent.size(); i++)
            assertEquals(i % 2 == 0 ? Action.PRESS : Action.RELEASE, sent.get(i), "press and release alternate: " + sent);
        assertEquals(Action.RELEASE, sent.getLast(), "it ends with the key up: " + sent);
    }

    @Test
    void aTaskThatNeverPressedReleasesNothing() {
        assertEquals(Action.NONE, new DismountKey().release());
    }
}
