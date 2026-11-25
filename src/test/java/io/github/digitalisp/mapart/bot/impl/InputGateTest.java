package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.InputGate.Outcome;
import io.github.digitalisp.mapart.bot.impl.InputGate.Sent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The still-tick rules for inventory clicks, outside menus and in the task's own open menu. */
class InputGateTest {
    private static Sent sent(long tick, boolean moving, Outcome o) {
        return new Sent(tick, moving, o, 10.5, 64, 20.5);
    }

    @Test
    void outsideMenusTheRequestMustHaveBeenExecutedWithoutKeys() {
        assertTrue(InputGate.stillOutside(5, sent(4, false, Outcome.EXECUTED)));
        assertFalse(InputGate.stillOutside(5, sent(4, true, Outcome.EXECUTED)), "movement keys");
        assertFalse(InputGate.stillOutside(5, sent(4, false, Outcome.DROPPED)), "not executed");
        assertFalse(InputGate.stillOutside(5, sent(4, false, Outcome.REJECTED)));
        assertFalse(InputGate.stillOutside(5, sent(4, false, Outcome.PENDING)));
        assertFalse(InputGate.stillOutside(5, sent(3, false, Outcome.EXECUTED)), "not the previous tick");
        assertFalse(InputGate.stillOutside(5, null));
    }

    /** Gate B: ZenithProxy completes every request false while a container is open, so this rule never held there. */
    @Test
    void inAnOpenMenuADroppedRequestWithoutKeysIsStill() {
        assertFalse(InputGate.stillOutside(5, sent(4, false, Outcome.DROPPED)), "the outside rule never holds in a menu");
        assertTrue(InputGate.stillInMenu(5, sent(4, false, Outcome.DROPPED), 10.5, 64, 20.5));
        assertTrue(InputGate.stillInMenu(5, sent(4, false, Outcome.EXECUTED), 10.5, 64, 20.5),
            "the menu opened after the request ran");
    }

    @Test
    void inAnOpenMenuKeysMovementOrAnotherOwnerAreNotStill() {
        assertFalse(InputGate.stillInMenu(5, sent(4, true, Outcome.DROPPED), 10.5, 64, 20.5), "our keys");
        assertFalse(InputGate.stillInMenu(5, sent(4, false, Outcome.DROPPED), 10.55, 64, 20.5), "slid 0.05");
        assertFalse(InputGate.stillInMenu(5, sent(4, false, Outcome.DROPPED), 10.5, 63.9, 20.5), "fell");
        assertFalse(InputGate.stillInMenu(5, sent(4, false, Outcome.REJECTED), 10.5, 64, 20.5), "input owned elsewhere");
        assertFalse(InputGate.stillInMenu(5, sent(4, false, Outcome.PENDING), 10.5, 64, 20.5));
        assertFalse(InputGate.stillInMenu(5, sent(3, false, Outcome.DROPPED), 10.5, 64, 20.5), "no request last tick");
        assertFalse(InputGate.stillInMenu(5, null, 10.5, 64, 20.5));
        assertTrue(InputGate.stillInMenu(5, sent(4, false, Outcome.DROPPED), 10.5004, 64, 20.5), "below 1/1000 block");
    }
}
