package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static io.github.digitalisp.mapart.bot.impl.KeySwaps.ADDITION;
import static io.github.digitalisp.mapart.bot.impl.KeySwaps.INPUT;
import static io.github.digitalisp.mapart.bot.impl.KeySwaps.Op;
import static io.github.digitalisp.mapart.bot.impl.KeySwaps.RESULT;

/**
 * {@link KeySwaps}: every plan run click by click through a model of
 * vanilla's menu clicks ({@code AbstractContainerMenu.doClick}: PICKUP with the left and right button, SWAP with a
 * hotbar key, the slot rules of the armour slots, the cartography table and the anvil, a result slot's take). Cut off
 * after any click, nothing is on the cursor (the server drops it at a disconnect) and the item is in the inventory, worn
 * or in a table's input slot (which a disconnect drops too: {@code DropWatch} and the reconnect's pickup cover that).
 * At the end the item is where it belongs, the hotbar is as it was, and no plan takes more clicks than the old one.
 * Each switch off gives the old clicks exactly.
 */
class KeySwapsTest {
    private static final String MAP = "minecraft:filled_map";
    private static final String LOCKED = "minecraft:filled_map#locked";
    private static final String RENAMED = "minecraft:filled_map#renamed";
    private static final String PANE = "minecraft:glass_pane";
    private static final String ELYTRA = "minecraft:elytra";
    private static final int[] PLAYER_SLOTS = {9, 20, 35, 36, 40, 44};

    /** Hotbar contents before a plan: which of the 9 slots hold a stack (the item's own slot set afterwards). */
    private enum Fill {
        FULL, EMPTY, TWO_EMPTY, ONLY_HELD_EMPTY;

        boolean filled(int h, int held) {
            return switch (this) {
                case FULL -> true;
                case EMPTY -> false;
                case TWO_EMPTY -> h != 2 && h != 5;
                case ONLY_HELD_EMPTY -> h != held;
            };
        }
    }

    // ---- armour: EquipTask and UnequipBootsTask (build.swapWithoutCursor) ----

    @Test
    void offTheSwitchTheOldClicks() {
        IntPredicate none = h -> false;
        assertEquals(List.of(new FlightGear.Click(20, -1), new FlightGear.Click(8, -1), new FlightGear.Click(20, -1)),
            KeySwaps.wear(false, 20, 8, none, 0));
        assertEquals(List.of(new FlightGear.Click(40, -1), new FlightGear.Click(6, -1), new FlightGear.Click(40, -1)),
            KeySwaps.wear(false, 40, 6, h -> true, 0));
        assertEquals(List.of(new FlightGear.Click(8, -1), new FlightGear.Click(20, -1)), KeySwaps.takeOff(false, 8, 20, none, 0));
        assertEquals(List.of(new FlightGear.Click(8, -1), new FlightGear.Click(38, -1)), KeySwaps.takeOff(false, 8, 38, none, 0));
        // the menus: the map at player slot 9 (menu slot 3), the panes at 36 (menu slot 30)
        assertEquals(List.of(Op.left(3), Op.left(INPUT), Op.left(30), Op.right(ADDITION), Op.left(30), Op.WAIT,
            Op.left(RESULT), Op.left(3)), KeySwaps.lock(false, 9, 36, 64, 3, none, 0));
        assertEquals(List.of(Op.left(33), Op.left(INPUT), Op.RENAME, Op.WAIT, Op.left(RESULT), Op.left(33)),
            KeySwaps.rename(false, 39, 3, none, 0));
    }

    @Test
    void clickCounts() {
        IntPredicate full = h -> false;
        IntPredicate free = h -> true;
        // wear (old 3): from the hotbar 1, from the main inventory 2 through an empty hotbar slot, 3 with none
        assertEquals(1, KeySwaps.wear(true, 40, 8, full, 0).size());
        assertEquals(2, KeySwaps.wear(true, 20, 8, free, 0).size());
        assertEquals(3, KeySwaps.wear(true, 20, 8, full, 0).size());
        // take off (old 2): into the hotbar 1, into the main inventory 2 through an empty hotbar slot, 3 with none
        assertEquals(1, KeySwaps.takeOff(true, 8, 40, full, 0).size());
        assertEquals(2, KeySwaps.takeOff(true, 8, 20, free, 0).size());
        assertEquals(3, KeySwaps.takeOff(true, 8, 20, full, 0).size());
        // lock (old 7): map and panes in the hotbar or not, one pane or a stack
        assertEquals(3, KeySwaps.clicks(KeySwaps.lock(true, 40, 37, 1, 3, full, 0)));
        assertEquals(4, KeySwaps.clicks(KeySwaps.lock(true, 40, 37, 64, 3, full, 0)));
        assertEquals(4, KeySwaps.clicks(KeySwaps.lock(true, 40, 12, 1, 3, full, 0)));
        assertEquals(5, KeySwaps.clicks(KeySwaps.lock(true, 40, 12, 64, 3, full, 0)));
        assertEquals(5, KeySwaps.clicks(KeySwaps.lock(true, 9, 37, 1, 3, full, 0)));
        assertEquals(6, KeySwaps.clicks(KeySwaps.lock(true, 9, 37, 64, 3, full, 0)));
        assertEquals(6, KeySwaps.clicks(KeySwaps.lock(true, 9, 12, 1, 3, full, 0)));
        assertEquals(7, KeySwaps.clicks(KeySwaps.lock(true, 9, 12, 64, 3, full, 0)));
        assertEquals(7, KeySwaps.clicks(KeySwaps.lock(false, 40, 37, 1, 3, full, 0)));
        // rename (old 4): from the hotbar 2, from the main inventory 4
        assertEquals(2, KeySwaps.clicks(KeySwaps.rename(true, 40, 3, full, 0)));
        assertEquals(4, KeySwaps.clicks(KeySwaps.rename(true, 9, 3, full, 0)));
        assertEquals(4, KeySwaps.clicks(KeySwaps.rename(false, 40, 3, full, 0)));
    }

    @Test
    void keysAreNeverTheHeldSlotWithAStack() {
        for (int held = 0; held < 9; held++) {
            int h = held;
            assertTrue(KeySwaps.borrowKey(held, -1) != held);
            assertTrue(KeySwaps.borrowKey(held, 3) != 3);
            assertEquals(held == 8 ? 7 : 8, KeySwaps.borrowKey(held, -1), "FlightGear.borrowKey's choice");
            // the held slot only when it is the one empty slot (a stack passes through the hand for one click)
            assertEquals(held, KeySwaps.emptyKey(k -> k == h, held, -1));
            assertEquals(-1, KeySwaps.emptyKey(k -> k == h, held, held));
            assertEquals(held == 8 ? 7 : 8, KeySwaps.emptyKey(k -> true, held, -1));
        }
    }

    @Test
    void wearingNeverCarriesThePieceOnTheCursor() {
        String[][] pieces = {{"minecraft:netherite_boots", "8"}, {"minecraft:diamond_chestplate", "6"}, {ELYTRA, "6"},
            {"minecraft:iron_helmet", "5"}};
        int runs = 0;
        for (String[] piece : pieces) {
            int armour = Integer.parseInt(piece[1]);
            for (int from : PLAYER_SLOTS) {
                for (int held = 0; held < 9; held++) {
                    for (Fill fill : Fill.values()) {
                        Menu m = playerMenu(Menu.INVENTORY, fill, held);
                        m.put(from, piece[0], 1);
                        Menu before = m.copy();
                        List<FlightGear.Click> clicks = KeySwaps.wear(true, from, armour, m::hotbarEmpty, held);
                        assertTrue(clicks.size() <= 3, clicks.toString());
                        String what = piece[0] + " from " + from + " held " + held + " " + fill + ": " + clicks;
                        for (FlightGear.Click c : clicks) {
                            assertTrue(c.swapKey(), "a plain click: " + what);
                            usesHeldOnlyEmpty(before, c.hotbarKey(), held, what, from);
                            m.swap(c.slot(), c.hotbarKey());
                            assertNull(m.cursor, "on the cursor: " + what);
                            assertEquals(before.totals(), m.totals(), "left the inventory: " + what);
                        }
                        assertEquals(piece[0], m.item[armour], "not worn: " + what);
                        assertNull(m.item[from], what);
                        m.put(armour, null, 0);
                        before.put(from, null, 0);
                        assertEquals(before.slots(), m.slots(), "another slot changed: " + what);
                        runs++;
                    }
                }
            }
        }
        assertEquals(4 * PLAYER_SLOTS.length * 9 * Fill.values().length, runs);
    }

    @Test
    void takingOffNeverCarriesThePieceOnTheCursor() {
        for (int dest : PLAYER_SLOTS) {
            for (int held = 0; held < 9; held++) {
                for (Fill fill : Fill.values()) {
                    Menu m = playerMenu(Menu.INVENTORY, fill, held);
                    m.put(dest, null, 0); // UnequipBootsTask's destination is an empty slot
                    m.put(8, "minecraft:netherite_boots", 1);
                    Menu before = m.copy();
                    List<FlightGear.Click> clicks = KeySwaps.takeOff(true, 8, dest, m::hotbarEmpty, held);
                    String what = "to " + dest + " held " + held + " " + fill + ": " + clicks;
                    boolean anyEmpty = false;
                    for (int h = 0; h < 9; h++) anyEmpty |= before.hotbarEmpty(h);
                    assertEquals(ItemIds.hotbarIndex(dest) >= 0 ? 1 : anyEmpty ? 2 : 3, clicks.size(), what);
                    for (FlightGear.Click c : clicks) {
                        assertTrue(c.swapKey(), "a plain click: " + what);
                        usesHeldOnlyEmpty(before, c.hotbarKey(), held, what, dest);
                        m.swap(c.slot(), c.hotbarKey());
                        assertNull(m.cursor, "on the cursor: " + what);
                        assertEquals(before.totals(), m.totals(), "left the inventory: " + what);
                    }
                    assertEquals("minecraft:netherite_boots", m.item[dest], "not in the destination: " + what);
                    assertNull(m.item[8], what);
                    m.put(dest, null, 0);
                    before.put(8, null, 0);
                    assertEquals(before.slots(), m.slots(), "another slot changed: " + what);
                }
            }
        }
    }

    /** The model itself: the old clicks do carry the piece on the cursor (what a disconnect dropped, run r1off). */
    @Test
    void theOldClicksCarryThePiece() {
        Menu m = playerMenu(Menu.INVENTORY, Fill.FULL, 0);
        m.put(20, "minecraft:netherite_boots", 1);
        Menu before = m.copy();
        List<FlightGear.Click> clicks = KeySwaps.wear(false, 20, 8, m::hotbarEmpty, 0);
        m.left(clicks.getFirst().slot());
        assertEquals("minecraft:netherite_boots", m.cursor);
        m.left(clicks.get(1).slot());
        m.left(clicks.get(2).slot());
        assertNull(m.cursor);
        assertEquals("minecraft:netherite_boots", m.item[8]);
        assertEquals(before.totals(), m.totals());
    }

    // ---- the map: LockMapTask and RenameMapTask (build.mapSwapsWithoutCursor) ----

    @Test
    void lockingNeverCarriesTheMapOnTheCursor() {
        int[] mapSlots = {9, 30, 36, 40, 44};
        int[] paneSlots = {12, 35, 37, 43};
        int runs = 0;
        for (int mapSlot : mapSlots) {
            for (int paneSlot : paneSlots) {
                for (int panes : new int[] {1, 2, 64}) {
                    for (int held = 0; held < 9; held++) {
                        for (Fill fill : Fill.values()) {
                            Menu m = playerMenu(Menu.CARTOGRAPHY, fill, held);
                            m.put(m.slot(mapSlot), MAP, 1);
                            m.put(m.slot(paneSlot), PANE, panes);
                            Menu before = m.copy();
                            List<Op> ops = KeySwaps.lock(true, mapSlot, paneSlot, panes, m.top, m::hotbarEmpty, held);
                            String what = "map " + mapSlot + " panes " + paneSlot + "x" + panes + " held " + held + " "
                                + fill + ": " + ops;
                            assertTrue(KeySwaps.clicks(ops) <= 7, what);
                            // the one case a stack rides the cursor: a stack of panes in the main inventory
                            boolean paneCursor = ItemIds.hotbarIndex(paneSlot) < 0 && panes > 1;
                            for (Op op : ops) {
                                if (op.kind() == KeySwaps.Kind.SWAP) usesHeldOnlyEmpty(before, op.key(), held, what, mapSlot, paneSlot);
                                m.apply(op);
                                if (paneCursor) {
                                    assertTrue(m.cursor == null || m.cursor.equals(PANE), "on the cursor: " + what);
                                } else {
                                    assertNull(m.cursor, "on the cursor: " + what);
                                }
                                mapSafe(m, what);
                                othersInPlace(before, m, what);
                            }
                            assertNull(m.cursor, what);
                            assertEquals(LOCKED, m.item[m.slot(mapSlot)], "the locked map is not where the map was: " + what);
                            assertEquals(panes - 1, m.count[m.slot(paneSlot)], "the panes: " + what);
                            for (int s = 0; s < 3; s++) assertNull(m.item[s], "left in the table: " + what);
                            m.put(m.slot(mapSlot), null, 0);
                            m.put(m.slot(paneSlot), null, 0);
                            before.put(before.slot(mapSlot), null, 0);
                            before.put(before.slot(paneSlot), null, 0);
                            assertEquals(before.slots(), m.slots(), "another slot changed: " + what);
                            runs++;
                        }
                    }
                }
            }
        }
        assertEquals(5 * 4 * 3 * 9 * Fill.values().length, runs);
    }

    @Test
    void renamingNeverCarriesTheMapOnTheCursor() {
        for (int mapSlot : new int[] {9, 30, 35, 36, 40, 44}) {
            for (int held = 0; held < 9; held++) {
                for (Fill fill : Fill.values()) {
                    Menu m = playerMenu(Menu.ANVIL, fill, held);
                    m.put(m.slot(mapSlot), MAP, 1);
                    Menu before = m.copy();
                    List<Op> ops = KeySwaps.rename(true, mapSlot, m.top, m::hotbarEmpty, held);
                    String what = "map " + mapSlot + " held " + held + " " + fill + ": " + ops;
                    assertTrue(KeySwaps.clicks(ops) <= 4, what);
                    for (Op op : ops) {
                        if (op.kind() == KeySwaps.Kind.SWAP) usesHeldOnlyEmpty(before, op.key(), held, what, mapSlot);
                        m.apply(op);
                        assertNull(m.cursor, "on the cursor: " + what);
                        mapSafe(m, what);
                        othersInPlace(before, m, what);
                    }
                    assertEquals(RENAMED, m.item[m.slot(mapSlot)], what);
                    for (int s = 0; s < 3; s++) assertNull(m.item[s], "left in the anvil: " + what);
                    m.put(m.slot(mapSlot), null, 0);
                    before.put(before.slot(mapSlot), null, 0);
                    assertEquals(before.slots(), m.slots(), "another slot changed: " + what);
                }
            }
        }
    }

    /** The model itself: the old lock carries the map on the cursor twice, and still locks it. */
    @Test
    void theOldLockCarriesTheMap() {
        Menu m = playerMenu(Menu.CARTOGRAPHY, Fill.FULL, 0);
        m.put(m.slot(9), MAP, 1);
        m.put(m.slot(36), PANE, 64);
        List<String> cursors = new ArrayList<>();
        for (Op op : KeySwaps.lock(false, 9, 36, 64, m.top, m::hotbarEmpty, 0)) {
            m.apply(op);
            cursors.add(m.cursor);
        }
        assertEquals(java.util.Arrays.asList(MAP, null, PANE, PANE, null, null, LOCKED, null), cursors);
        assertEquals(LOCKED, m.item[m.slot(9)]);
        assertEquals(63, m.count[m.slot(36)]);
    }

    // ---- helpers ----

    /**
     * A key over the held slot only when that slot was empty (the hand holds nothing it would lose for a click) or is
     * the item's own slot ({@code own}: player slots).
     */
    private static void usesHeldOnlyEmpty(Menu before, int key, int held, String what, int... own) {
        for (int o : own) if (ItemIds.hotbarIndex(o) == key) return;
        if (key == held) assertTrue(before.hotbarEmpty(held), "the held slot's stack borrowed: " + what);
    }

    /** The map (or what it became) is in the player's slots or the table's input slot: never on the cursor. */
    private static void mapSafe(Menu m, String what) {
        int where = -1;
        for (int s = 0; s < m.item.length; s++) {
            if (s == RESULT) continue; // the result slot shows what a take would give; the map is still in slot 0
            if (m.item[s] != null && m.item[s].startsWith(MAP)) {
                assertEquals(-1, where, "two maps: " + what);
                where = s;
            }
        }
        assertFalse(m.cursor != null && m.cursor.startsWith(MAP), "the map on the cursor: " + what);
        assertTrue(where == INPUT || where >= m.top, "the map is gone: " + what);
    }

    /** Stacks other than the map and the panes never leave the player's slots. */
    private static void othersInPlace(Menu before, Menu m, String what) {
        Map<String, Integer> a = before.totals();
        Map<String, Integer> b = m.playerTotals();
        a.keySet().removeIf(k -> k.startsWith(MAP) || k.equals(PANE));
        b.keySet().removeIf(k -> k.startsWith(MAP) || k.equals(PANE));
        assertEquals(a, b, "a stack left the inventory: " + what);
    }

    /** A menu whose main inventory is full of distinct stacks and whose hotbar is filled as {@code fill} says. */
    private static Menu playerMenu(String kind, Fill fill, int held) {
        Menu m = new Menu(kind);
        for (int p = 9; p <= 35; p++) m.put(m.slot(p), "minecraft:stone#" + p, 10 + p);
        for (int h = 0; h < 9; h++) {
            if (fill.filled(h, held)) m.put(m.hotbar(h), "minecraft:dirt#" + h, 20 + h);
        }
        return m;
    }

    /**
     * Vanilla's menu clicks ({@code AbstractContainerMenu.doClick}) for the slots these plans touch: the player's
     * inventory menu (armour 5..8, main 9..35, hotbar 36..44) or a table with three slots before the player's (top 3).
     */
    private static final class Menu {
        static final String INVENTORY = "inventory", CARTOGRAPHY = "cartography", ANVIL = "anvil";
        final String kind;
        final int top;
        final String[] item;
        final int[] count;
        String cursor;
        int cursorCount;
        boolean named;

        Menu(String kind) {
            this.kind = kind;
            this.top = kind.equals(INVENTORY) ? 0 : 3;
            this.item = new String[top == 0 ? 46 : 39];
            this.count = new int[item.length];
        }

        Menu copy() {
            Menu c = new Menu(kind);
            System.arraycopy(item, 0, c.item, 0, item.length);
            System.arraycopy(count, 0, c.count, 0, count.length);
            c.cursor = cursor;
            c.cursorCount = cursorCount;
            c.named = named;
            return c;
        }

        int hotbar(int key) {
            return top == 0 ? 36 + key : top + 27 + key;
        }

        int slot(int playerSlot) {
            return ItemIds.toContainerSlot(playerSlot, top);
        }

        boolean hotbarEmpty(int key) {
            return item[hotbar(key)] == null;
        }

        void put(int slot, String it, int n) {
            item[slot] = n > 0 ? it : null;
            count[slot] = item[slot] == null ? 0 : n;
        }

        boolean table() {
            return top > 0;
        }

        boolean mayPlace(int slot, String it) {
            if (!table()) {
                return switch (slot) {
                    case 0 -> false;
                    case 5 -> it.endsWith("_helmet");
                    case 6 -> it.endsWith("_chestplate") || it.equals(ELYTRA);
                    case 7 -> it.endsWith("_leggings");
                    case 8 -> it.endsWith("_boots");
                    default -> true;
                };
            }
            if (slot == RESULT) return false;
            if (kind.equals(CARTOGRAPHY) && slot == INPUT) return it.equals(MAP);
            if (kind.equals(CARTOGRAPHY) && slot == ADDITION) {
                return it.equals(PANE) || it.equals("minecraft:paper") || it.equals("minecraft:map");
            }
            return true;
        }

        int max(int slot, String it) {
            boolean single = it.startsWith(MAP) || it.equals(ELYTRA) || it.endsWith("_boots") || it.endsWith("_helmet")
                || it.endsWith("_chestplate") || it.endsWith("_leggings");
            return single || (!table() && slot >= 5 && slot <= 8) ? 1 : 64;
        }

        /** The table's result as the server sets it after every change. */
        void update() {
            if (!table()) return;
            String r = null;
            if (kind.equals(CARTOGRAPHY) && MAP.equals(item[INPUT]) && PANE.equals(item[ADDITION])) r = LOCKED;
            if (kind.equals(ANVIL) && named && MAP.equals(item[INPUT])) r = RENAMED;
            put(RESULT, r, r == null ? 0 : 1);
        }

        /** The result slot's onTake: the inputs are used up. */
        void taken() {
            if (kind.equals(CARTOGRAPHY)) {
                take(INPUT, 1);
                take(ADDITION, 1);
            } else {
                take(INPUT, count[INPUT]);
            }
        }

        void take(int slot, int n) {
            put(slot, item[slot], count[slot] - n);
        }

        void apply(Op op) {
            switch (op.kind()) {
                case LEFT -> left(op.slot());
                case RIGHT -> right(op.slot());
                case SWAP -> swap(op.slot(), op.key());
                case WAIT -> update();
                case RENAME -> {
                    named = true;
                    update();
                }
            }
        }

        /** {@code ClickType.SWAP}: the slot and the hotbar key's slot trade, under the slot's rules. */
        void swap(int slot, int key) {
            int hb = hotbar(key);
            assertTrue(slot != hb, "a key over its own slot");
            String h = item[hb];
            int hn = count[hb];
            String s = item[slot];
            int sn = count[slot];
            if (h == null && s == null) return;
            if (h == null) {
                put(hb, s, sn);
                put(slot, null, 0);
                if (table() && slot == RESULT) taken();
            } else if (s == null) {
                if (!mayPlace(slot, h)) return;
                int m = max(slot, h);
                if (hn > m) {
                    put(slot, h, m);
                    take(hb, m);
                } else {
                    put(hb, null, 0);
                    put(slot, h, hn);
                }
            } else if (mayPlace(slot, h)) {
                assertTrue(hn <= max(slot, h), "a split trade (not used by any plan)");
                put(hb, s, sn);
                put(slot, h, hn);
            }
            update();
        }

        /** {@code ClickType.PICKUP} with the left button. */
        void left(int slot) {
            String s = item[slot];
            int sn = count[slot];
            if (table() && slot == RESULT) {
                if (s == null) return;
                if (cursor != null) fail("a result taken onto a stack on the cursor");
                cursor = s;
                cursorCount = sn;
                put(slot, null, 0);
                taken();
            } else if (cursor == null) {
                if (s != null) {
                    cursor = s;
                    cursorCount = sn;
                    put(slot, null, 0);
                }
            } else if (s == null) {
                if (mayPlace(slot, cursor)) {
                    int n = Math.min(cursorCount, max(slot, cursor));
                    put(slot, cursor, n);
                    cursorDown(n);
                }
            } else if (s.equals(cursor)) {
                int n = Math.min(cursorCount, max(slot, s) - sn);
                put(slot, s, sn + n);
                cursorDown(n);
            } else if (mayPlace(slot, cursor) && cursorCount <= max(slot, cursor)) {
                put(slot, cursor, cursorCount);
                cursor = s;
                cursorCount = sn;
            }
            update();
        }

        /** {@code ClickType.PICKUP} with the right button. */
        void right(int slot) {
            assertFalse(table() && slot == RESULT, "a right click on the result");
            String s = item[slot];
            int sn = count[slot];
            if (cursor == null) {
                if (s != null) {
                    int n = (sn + 1) / 2;
                    cursor = s;
                    cursorCount = n;
                    take(slot, n);
                }
            } else if (s == null || s.equals(cursor) && sn < max(slot, s)) {
                if (mayPlace(slot, cursor)) {
                    put(slot, cursor, sn + 1);
                    cursorDown(1);
                }
            } else if (!s.equals(cursor) && mayPlace(slot, cursor) && cursorCount <= max(slot, cursor)) {
                put(slot, cursor, cursorCount);
                cursor = s;
                cursorCount = sn;
            }
            update();
        }

        private void cursorDown(int n) {
            cursorCount -= n;
            if (cursorCount <= 0) {
                cursor = null;
                cursorCount = 0;
            }
        }

        /** Every stack: the slots (the result slot left out: a preview) and the cursor. */
        Map<String, Integer> totals() {
            Map<String, Integer> t = new TreeMap<>();
            for (int s = 0; s < item.length; s++) {
                if (item[s] != null && !(table() && s == RESULT)) t.merge(item[s], count[s], Integer::sum);
            }
            if (cursor != null) t.merge(cursor, cursorCount, Integer::sum);
            return t;
        }

        /** The stacks in the player's own slots only. */
        Map<String, Integer> playerTotals() {
            Map<String, Integer> t = new TreeMap<>();
            for (int s = top; s < item.length; s++) if (item[s] != null) t.merge(item[s], count[s], Integer::sum);
            return t;
        }

        List<String> slots() {
            List<String> out = new ArrayList<>();
            for (int s = 0; s < item.length; s++) out.add(item[s] == null ? null : item[s] + "x" + count[s]);
            return out;
        }
    }
}
