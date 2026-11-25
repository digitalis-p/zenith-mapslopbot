package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FlightGear}: the swap clicks, the fall-back rules and when the chestplate goes back on. */
class FlightGearTest {
    private static FlightGear.State state(boolean autoArmor, boolean worn, int slot, int left, int max, int rockets, float health) {
        return new FlightGear.State(autoArmor, worn, slot, left, max, rockets, health, true);
    }

    @Test
    void swapIsOneHotbarKeyOrThreeClicks() {
        // elytra in hotbar slot 3 (player slot 39): one hotbar-key click over the chest slot
        assertEquals(List.of(new FlightGear.Click(6, 3)), FlightGear.swap(39, 8));
        // elytra in the main inventory (player slot 9): three trades through the borrowed hotbar key 8
        assertEquals(List.of(new FlightGear.Click(9, 8), new FlightGear.Click(6, 8), new FlightGear.Click(9, 8)),
            FlightGear.swap(9, 8));
        assertTrue(FlightGear.swap(36, 8).getFirst().swapKey());
        // the borrowed key is never the held slot
        assertEquals(8, FlightGear.borrowKey(0));
        assertEquals(7, FlightGear.borrowKey(8));
    }

    /**
     * Local run r1off 2026-10-25: ZenithProxy was killed 80 ms into a swap from the main inventory, the elytra was on the
     * cursor, and the server dropped it (vanilla drops a menu's cursor stack when the connection ends). The clicks now
     * only trade places (vanilla's hotbar-key SWAP): after every click, cut off anywhere, nothing is on the cursor and
     * every piece is still in the inventory; at the end the piece is worn, the worn one sits where the piece was and
     * the hotbar is as before.
     */
    @Test
    void noSwapEverCarriesAPieceOnTheCursor() {
        for (int from : new int[] {9, 20, 35, 36, 40, 44}) {
            for (int held = 0; held < 9; held++) {
                for (boolean chestplate : new boolean[] {true, false}) {
                    String[] inv = new String[45];
                    for (int h = 0; h < 9; h++) inv[36 + h] = "stack" + h;
                    inv[from] = "elytra";
                    if (chestplate) inv[FlightGear.CHEST_SLOT] = "netherite_chestplate";
                    String[] before = inv.clone();
                    List<FlightGear.Click> clicks = FlightGear.swap(from, FlightGear.borrowKey(held));
                    for (FlightGear.Click k : clicks) {
                        assertTrue(k.swapKey(), "a plain click would carry a piece on the cursor: " + clicks);
                        assertTrue(k.hotbarKey() != held || ItemIds.hotbarIndex(from) == held, "the held slot borrowed");
                        int hb = 36 + k.hotbarKey();
                        String a = inv[k.slot()];
                        String b = inv[hb];
                        if (k.slot() == FlightGear.CHEST_SLOT && b != null) {
                            assertTrue(b.equals("elytra") || b.endsWith("_chestplate"), "not wearable: " + b);
                        }
                        inv[k.slot()] = b;
                        inv[hb] = a;
                        assertEquals(sorted(before), sorted(inv), "a piece left the inventory: " + clicks);
                    }
                    assertEquals("elytra", inv[FlightGear.CHEST_SLOT]);
                    assertEquals(chestplate ? "netherite_chestplate" : null, inv[from]);
                    for (int h = 0; h < 9; h++) {
                        if (36 + h != from) assertEquals(before[36 + h], inv[36 + h], "hotbar slot " + h + " changed");
                    }
                    // and back: the chestplate (or nothing) where it lies now, the elytra into its old slot
                    List<FlightGear.Click> back = FlightGear.swap(from, FlightGear.borrowKey(held));
                    for (FlightGear.Click k : back) {
                        int hb = 36 + k.hotbarKey();
                        String a = inv[k.slot()];
                        inv[k.slot()] = inv[hb];
                        inv[hb] = a;
                    }
                    assertEquals(java.util.Arrays.asList(before), java.util.Arrays.asList(inv));
                }
            }
        }
    }

    private static List<String> sorted(String[] inv) {
        return java.util.Arrays.stream(inv).filter(java.util.Objects::nonNull).sorted().toList();
    }

    @Test
    void fallBackRules() {
        FlightGear.Mode r = FlightGear.Mode.RESTOCK;
        assertNull(FlightGear.refusal(state(false, false, 9, 400, 432, 64, 20), r, 10, 14, 6));
        assertTrue(FlightGear.refusal(state(true, false, 9, 400, 432, 64, 20), r, 10, 14, 6).contains("AutoArmor"));
        assertEquals("no elytra", FlightGear.refusal(state(false, false, -1, 0, 0, 64, 20), r, 10, 14, 6));
        assertTrue(FlightGear.refusal(state(false, false, 9, 400, 432, 64, 20), FlightGear.Mode.WORN, 10, 14, 6)
            .contains("not worn"));
        assertNull(FlightGear.refusal(state(false, true, -1, 400, 432, 64, 20), FlightGear.Mode.WORN, 10, 14, 6));
        // durability floor: 10 % of 432 = 44 (rounded up): 44 left refuses, 45 flies
        assertTrue(FlightGear.refusal(state(false, false, 9, 44, 432, 64, 20), r, 10, 14, 6).contains("durability floor"));
        assertNull(FlightGear.refusal(state(false, false, 9, 45, 432, 64, 20), r, 10, 14, 6));
        // fewer uses left than the flight's seconds + 5
        assertTrue(FlightGear.refusal(state(false, false, 9, 20, 432, 64, 20), r, 0, 14, 16).contains("uses left"));
        assertEquals("no rockets", FlightGear.refusal(state(false, false, 9, 400, 432, 0, 20), r, 10, 14, 6));
        assertEquals("health 9 under 14", FlightGear.refusal(state(false, false, 9, 400, 432, 64, 9), r, 10, 14, 6));
    }

    @Test
    void chestplateBackOn() {
        FlightGear.Mode r = FlightGear.Mode.RESTOCK;
        FlightGear.Mode f = FlightGear.Mode.FLIGHT;
        // RESTOCK: worn through the restock, back on when it ends or a fight starts
        assertFalse(FlightGear.chestBack(r, true, true, false, false));
        assertTrue(FlightGear.chestBack(r, false, false, false, false));
        assertTrue(FlightGear.chestBack(r, false, true, true, false));
        // FLIGHT: back on after each landing, unless the elytra waits for its mend (until the restock ends)
        assertTrue(FlightGear.chestBack(f, true, true, false, false));
        assertFalse(FlightGear.chestBack(f, true, true, false, true));
        assertTrue(FlightGear.chestBack(f, false, false, false, true));
        // WORN: never
        assertFalse(FlightGear.chestBack(FlightGear.Mode.WORN, true, false, true, false));
        assertEquals(FlightGear.Mode.RESTOCK, FlightGear.Mode.of(" Restock "));
        assertEquals(FlightGear.Mode.FLIGHT, FlightGear.Mode.of(null));
        assertEquals(FlightGear.Mode.FLIGHT, FlightGear.Mode.of("anything else"));
    }
}
