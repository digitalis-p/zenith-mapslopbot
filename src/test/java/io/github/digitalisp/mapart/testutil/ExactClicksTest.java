package io.github.digitalisp.mapart.testutil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The click counts behind the carry-rule model (CarryRuleModelTest), checked by hand. */
class ExactClicksTest {
    @Test
    void oneItemRightClickedAndTheRestPutBack() {
        // pick up the container stack, right-click one item into the slot, put the rest back
        assertEquals(3, ExactClicks.into(0, 1, 64, false));
        assertEquals(10, ExactClicks.into(0, 8, 64, false));
    }

    @Test
    void aWholeStackIsTwoClicksAndNearlyWholeOnesAreGivenBack() {
        assertEquals(2, ExactClicks.into(0, 64, 64, false));
        // pick up 64, right-click one back into the container slot, put 63 down
        assertEquals(3, ExactClicks.into(0, 63, 64, false));
    }

    @Test
    void halfAStackIsTheWorstWithoutPickingUpHalf() {
        assertEquals(34, ExactClicks.into(0, 32, 64, false));
        assertEquals(2, ExactClicks.into(0, 32, 64, true));
        for (int x = 1; x <= 64; x++) {
            assertTrue(ExactClicks.into(0, x, 64, false) <= 34, "x " + x);
            assertTrue(ExactClicks.into(0, x, 64, true) <= 10, "x " + x);
        }
    }

    @Test
    void topUpIsThreeClicks() {
        // a held partial stack of 10 filled to 64: pick up, left-click onto it, put the rest back
        assertEquals(3, ExactClicks.into(10, 54, 64, false));
        assertEquals(3, ExactClicks.withdraw(10, 54, 64, false));
        // and two whole stacks more by shift-click
        assertEquals(5, ExactClicks.withdraw(10, 54 + 128, 64, false));
    }

    @Test
    void wholeStacksOntoAPartialKeepItsRemainder() {
        assertEquals(2, ExactClicks.withdraw(10, 128, 64, false));
    }

    @Test
    void anExactRemainderCostsItsRightClicks() {
        // nothing held, 70 wanted: one shift-click, then 6 exactly (pick up, 6 right clicks, put back)
        assertEquals(1 + 8, ExactClicks.withdraw(0, 70, 64, false));
        // 10 held, 20 more: 20 right clicks onto the partial stack (or 10 pick-up-half clicks less)
        assertEquals(22, ExactClicks.withdraw(10, 20, 64, false));
        assertTrue(ExactClicks.withdraw(10, 20, 64, true) < 22);
    }
}
