package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingItemDebounceTest {
    private static final String RED = "minecraft:red_carpet";
    private static final String BLUE = "minecraft:blue_carpet";

    @Test
    void missingOnlyAfterConsecutiveZeroTicks() {
        MissingItemDebounce d = new MissingItemDebounce();
        for (int t = 1; t < MissingItemDebounce.TICKS; t++) {
            assertFalse(d.missing(RED, 0, t), "zero on tick " + t + " is not yet missing");
        }
        assertTrue(d.missing(RED, 0, MissingItemDebounce.TICKS));
        assertTrue(d.missing(RED, 0, MissingItemDebounce.TICKS + 1));
    }

    @Test
    void transientZeroFromADoubleInventoryUpdateIsIgnored() {
        MissingItemDebounce d = new MissingItemDebounce();
        assertFalse(d.missing(RED, 12, 1));
        assertFalse(d.missing(RED, 0, 2), "slot update shows the stack gone");
        assertFalse(d.missing(RED, 11, 3), "the full inventory update restores it");
        for (int t = 4; t < 4 + MissingItemDebounce.TICKS - 1; t++) assertFalse(d.missing(RED, 0, t));
        assertTrue(d.missing(RED, 0, 4 + MissingItemDebounce.TICKS - 1), "counting restarted after the stack came back");
    }

    @Test
    void aGapInObservationsRestartsTheCount() {
        MissingItemDebounce d = new MissingItemDebounce();
        assertFalse(d.missing(RED, 0, 1));
        assertFalse(d.missing(RED, 0, 2));
        assertFalse(d.missing(RED, 0, 10), "not consecutive");
        assertFalse(d.missing(RED, 0, 10), "same tick twice is idempotent");
    }

    @Test
    void itemsAreIndependent() {
        MissingItemDebounce d = new MissingItemDebounce();
        for (int t = 1; t <= MissingItemDebounce.TICKS; t++) {
            d.missing(RED, 0, t);
            assertFalse(d.missing(BLUE, 3, t));
        }
        assertTrue(d.missing(RED, 0, MissingItemDebounce.TICKS + 1));
        assertFalse(d.missing(BLUE, 0, MissingItemDebounce.TICKS + 1));
    }
}
