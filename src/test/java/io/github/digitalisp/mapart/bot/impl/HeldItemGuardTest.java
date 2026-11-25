package io.github.digitalisp.mapart.bot.impl;

import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.HeldItemGuard.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The last line of "tools never break": no dig or attack goes out with a held item at the safety floor. */
class HeldItemGuardTest {
    private static final int FLOOR = 20;

    @Test
    void aboveTheFloorEverythingGoesOut() {
        for (PlayerAction a : PlayerAction.values())
            assertEquals(Verdict.SEND, HeldItemGuard.digVerdict(a, true, 21, FLOOR), a.name());
    }

    @Test
    void atTheFloorADigStartIsDroppedAndItsFinishBecomesAnAbort() {
        assertEquals(Verdict.DROP, HeldItemGuard.digVerdict(PlayerAction.START_DESTROY_BLOCK, true, 20, FLOOR));
        assertEquals(Verdict.ABORT, HeldItemGuard.digVerdict(PlayerAction.STOP_DESTROY_BLOCK, true, 20, FLOOR));
        assertEquals(Verdict.DROP, HeldItemGuard.digVerdict(PlayerAction.START_DESTROY_BLOCK, true, 1, FLOOR));
        // an abort, a drop of an item, a hand swap: nothing wears
        assertEquals(Verdict.SEND, HeldItemGuard.digVerdict(PlayerAction.ABORT_DESTROY_BLOCK, true, 1, FLOOR));
        assertEquals(Verdict.SEND, HeldItemGuard.digVerdict(PlayerAction.DROP_ITEM, true, 1, FLOOR));
        assertEquals(Verdict.SEND, HeldItemGuard.digVerdict(PlayerAction.SWAP_ITEM_WITH_OFFHAND, true, 1, FLOOR));
    }

    @Test
    void theBareHandAndBlocksAreNeverStopped() {
        assertEquals(Verdict.SEND, HeldItemGuard.digVerdict(PlayerAction.START_DESTROY_BLOCK, false, 0, FLOOR));
        assertFalse(HeldItemGuard.atFloor(false, 0, FLOOR));
    }

    @Test
    void attacksStopAtTheSameFloor() {
        assertTrue(HeldItemGuard.atFloor(true, 20, FLOOR));
        assertFalse(HeldItemGuard.atFloor(true, 21, FLOOR));
        assertTrue(HeldItemGuard.atFloor(true, 0, -5), "a negative floor reads as 0: an item at 0 uses is never used");
        assertFalse(HeldItemGuard.atFloor(true, 1, -5));
    }
}
