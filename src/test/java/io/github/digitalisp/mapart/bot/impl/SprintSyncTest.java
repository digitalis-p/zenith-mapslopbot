package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sprint state as a vanilla client keeps it and Grim predicts it (MC-69459, teleports), against ZenithProxy's Bot. */
class SprintSyncTest {
    @Test
    void withoutUpdatesTheBotsOwnState() {
        SprintSync s = new SprintSync();
        assertNull(s.tick(false, false));
        assertFalse(s.modifierWanted(false));
        assertNull(s.tick(true, true));
        assertTrue(s.modifierWanted(true));
    }

    /** The bot starts sprinting; an update the server sent before it heard the START counts after it: no modifier. */
    @Test
    void anUpdateCountingAfterTheStartWinsUntilTheNextToggle() {
        SprintSync s = new SprintSync();
        s.tick(false, false);         // 1
        s.tick(true, true);           // 2: the START was in tick 1's movement
        assertTrue(s.modifierWanted(true));
        s.tick(true, true);           // 3
        s.update(false, 3);           // its transaction answered after tick 2's movement
        assertFalse(s.modifierWanted(true), "vanilla keeps the server's attribute: Grim predicts without the modifier");
        s.tick(true, true);           // 4
        assertFalse(s.modifierWanted(true), "until the next update or toggle");
        s.update(true, 4);            // the server heard the START
        assertTrue(s.modifierWanted(true));
    }

    /** An update counting from the toggle's own tick: answered after the toggle's movement, so it wins. */
    @Test
    void anUpdateCountingFromTheToggleTickWins() {
        SprintSync s = new SprintSync();
        s.tick(true, true);           // 1
        s.tick(false, false);         // 2: STOP in tick 1's movement
        s.update(true, 2);            // answered after that movement
        assertTrue(s.modifierWanted(false), "vanilla keeps the modifier the update brought");
        s.tick(false, false);         // 3
        s.update(false, 3);
        assertFalse(s.modifierWanted(false));
    }

    /** An update that counted before the toggle (learnt late): the toggle wins, as in Grim. */
    @Test
    void aToggleAfterTheUpdateWins() {
        SprintSync s = new SprintSync();
        s.tick(false, false);         // 1
        s.tick(false, false);         // 2
        s.tick(true, true);           // 3: START in tick 2's movement
        s.update(false, 2);           // counted from tick 2, before that movement's toggle
        assertTrue(s.modifierWanted(true));
    }

    @Test
    void aLaterUpdateIsNotOverriddenByAnOlderOne() {
        SprintSync s = new SprintSync();
        s.tick(true, true);
        s.tick(true, true);
        s.update(true, 2);
        s.update(false, 1);
        assertTrue(s.modifierWanted(true));
    }

    @Test
    void aTeleportDoesNotClearTheSprintState() {
        SprintSync s = new SprintSync();
        s.tick(true, true);
        s.teleport();
        // ZenithProxy's Bot cleared its state on the teleport; the server last heard START
        assertEquals(Boolean.TRUE, s.tick(false, true), "restored to what the server last heard");
        assertTrue(s.modifierWanted(true));
        assertNull(s.tick(true, true), "once");
    }

    @Test
    void aTeleportWaitsForZenithProxyToHandleIt() {
        SprintSync s = new SprintSync();
        s.tick(true, true);
        s.teleport();
        assertNull(s.tick(true, true), "ZenithProxy has not handled the teleport yet");
        assertEquals(Boolean.TRUE, s.tick(false, true));
    }

    @Test
    void aTeleportWithoutSprintChangesNothing() {
        SprintSync s = new SprintSync();
        s.tick(false, false);
        s.teleport();
        for (int i = 0; i < SprintSync.TELEPORT_WINDOW + 2; i++) assertNull(s.tick(false, false));
        assertFalse(s.modifierWanted(false));
        // a later mismatch (the cache lagging a sent START) is not a teleport's
        assertNull(s.tick(true, false));
    }
}
