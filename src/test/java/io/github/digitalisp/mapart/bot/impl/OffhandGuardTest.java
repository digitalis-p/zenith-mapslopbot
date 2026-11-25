package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offhand and armour are the owner's; the airplace swap is the one sanctioned, same-tick exception. */
class OffhandGuardTest {
    private static final String TOTEM = "minecraft:totem_of_undying";
    private static final String CARPET = "minecraft:white_carpet";

    @Test
    void onlyHotbarAndMainSlotsAreMovable() {
        for (int s = ItemIds.MAIN_START; s <= ItemIds.HOTBAR_END; s++) assertTrue(OffhandGuard.movable(s), "slot " + s);
        for (int s : new int[] {0, 1, 4, 5, 6, 7, 8, ItemIds.OFFHAND, 46}) assertFalse(OffhandGuard.movable(s), "slot " + s);
        assertTrue(OffhandGuard.protectedSlot(ItemIds.OFFHAND));
        for (int s = 5; s <= 8; s++) assertTrue(OffhandGuard.protectedSlot(s), "armor " + s);
        assertFalse(OffhandGuard.protectedSlot(ItemIds.HOTBAR_START));
    }

    @Test
    void aSwapClosedInTheSameTickNeedsNothing() {
        OffhandGuard g = new OffhandGuard();
        g.swappedOut(TOTEM, 3, 100);
        assertTrue(g.open());
        assertEquals(OffhandGuard.Action.NONE, g.restore(100, CARPET, TOTEM).action(), "same tick: not overdue");
        g.swappedBack();
        assertFalse(g.open());
        assertEquals(OffhandGuard.Action.NONE, g.restore(101, TOTEM, CARPET).action());
    }

    @Test
    void anInterruptedSwapIsRepairedOnTheNextTick() {
        OffhandGuard g = new OffhandGuard();
        g.swappedOut(TOTEM, 3, 100);
        // the swap back never went out: the totem sits in hotbar 3, a carpet in the offhand
        OffhandGuard.Restore r = g.restore(101, CARPET, TOTEM);
        assertEquals(OffhandGuard.Action.SWAP_BACK, r.action());
        assertEquals(3, r.hotbar());
        assertEquals(TOTEM, r.item());
        assertTrue(g.open(), "open until the offhand holds the totem again");
        assertEquals(OffhandGuard.Action.NONE, g.restore(102, TOTEM, CARPET).action(), "back in place");
        assertFalse(g.open());
    }

    @Test
    void anItemMovedElsewhereIsReportedNotSearched() {
        OffhandGuard g = new OffhandGuard();
        g.swappedOut(TOTEM, 3, 100);
        OffhandGuard.Restore r = g.restore(101, null, CARPET);
        assertEquals(OffhandGuard.Action.LOST, r.action());
        assertEquals(TOTEM, r.item());
        assertFalse(g.open(), "reported once");
    }

    @Test
    void anEmptyOffhandHasNothingToProtect() {
        OffhandGuard g = new OffhandGuard();
        g.swappedOut(null, 3, 100);
        assertFalse(g.open());
        g.swappedOut(TOTEM, 9, 100);
        assertFalse(g.open(), "not a hotbar index");
    }
}
