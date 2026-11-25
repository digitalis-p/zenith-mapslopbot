package io.github.digitalisp.mapart.bot.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Inventory moves without the cursor: hotbar-key trades only
 * (vanilla {@code ClickType.SWAP}, ZenithProxy's {@code MoveToHotbarSlot}), so a connection that ends between two clicks
 * never leaves a stack on the cursor. The server drops a menu's cursor stack when the player leaves (vanilla
 * {@code AbstractContainerMenu.removed}); local run r1off 2026-10-25 lost the elytra that way, which
 * {@link FlightGear#swap} fixed first (25741b8). The same pattern here: a stack in the hotbar trades with the target in
 * one click; one in the main inventory goes through a hotbar slot, an empty one when there is one (it is empty again at
 * the end), else a borrowed one ({@link #borrowKey}, never the held slot) whose own stack trades back at the end, so the
 * hotbar ends as it was. Every plan with its switch off ({@code build.swapWithoutCursor},
 * {@code build.mapSwapsWithoutCursor}) is the old pick-up and put-down clicks, exactly. Pure.
 */
final class KeySwaps {
    private KeySwaps() { }

    /**
     * An empty hotbar index other than {@code avoid}, 8 down to 0, the held one last (a stack passing through the hand
     * for one click leaves it empty again); -1: none.
     */
    static int emptyKey(IntPredicate hotbarEmpty, int held, int avoid) {
        for (int h = 8; h >= 0; h--) if (h != held && h != avoid && hotbarEmpty.test(h)) return h;
        return held >= 0 && held <= 8 && held != avoid && hotbarEmpty.test(held) ? held : -1;
    }

    /**
     * The hotbar index a trade borrows when none is empty: never the held one (the hand keeps its item) nor
     * {@code avoid}, 8 first ({@link FlightGear#borrowKey} for {@code avoid} -1).
     */
    static int borrowKey(int held, int avoid) {
        for (int h = 8; h >= 0; h--) if (h != held && h != avoid) return h;
        return -1; // 9 slots, at most 2 excluded: never
    }

    /** {@link #emptyKey}, else {@link #borrowKey}. */
    static int transitKey(IntPredicate hotbarEmpty, int held, int avoid) {
        int e = emptyKey(hotbarEmpty, held, avoid);
        return e >= 0 ? e : borrowKey(held, avoid);
    }

    /**
     * The clicks that put the piece in player slot {@code from} (9..44) on in the EMPTY armour slot {@code armour}
     * (5..8; {@link EquipTask} never clicks a slot that holds something): from the hotbar one trade over the armour
     * slot; from the main inventory the piece into an empty hotbar slot and from there into the armour slot (2), or
     * with none empty into a borrowed slot, into the armour slot, and the borrowed slot's stack back (3). Off: pick up,
     * the armour slot, the source slot again (3 plain clicks, the old ones).
     */
    static List<FlightGear.Click> wear(boolean withoutCursor, int from, int armour, IntPredicate hotbarEmpty, int held) {
        if (!withoutCursor) {
            return List.of(new FlightGear.Click(from, -1), new FlightGear.Click(armour, -1), new FlightGear.Click(from, -1));
        }
        int h = ItemIds.hotbarIndex(from);
        if (h >= 0) return List.of(new FlightGear.Click(armour, h));
        int e = emptyKey(hotbarEmpty, held, -1);
        if (e >= 0) return List.of(new FlightGear.Click(from, e), new FlightGear.Click(armour, e));
        int k = borrowKey(held, -1);
        return List.of(new FlightGear.Click(from, k), new FlightGear.Click(armour, k), new FlightGear.Click(from, k));
    }

    /**
     * The clicks that take the piece in armour slot {@code armour} (5..8) off into the EMPTY player slot {@code dest}
     * (9..44): into a hotbar slot one trade; into the main inventory through an empty hotbar slot (2), else the
     * borrowed slot's stack into {@code dest} first, the piece into the borrowed slot, and the two traded back (3: an
     * armour slot takes no stack it cannot wear, so the borrowed slot must be empty for the piece). Off: pick up,
     * put down (2 plain clicks, the old ones).
     */
    static List<FlightGear.Click> takeOff(boolean withoutCursor, int armour, int dest, IntPredicate hotbarEmpty, int held) {
        if (!withoutCursor) return List.of(new FlightGear.Click(armour, -1), new FlightGear.Click(dest, -1));
        int d = ItemIds.hotbarIndex(dest);
        if (d >= 0) return List.of(new FlightGear.Click(armour, d));
        int e = emptyKey(hotbarEmpty, held, -1);
        if (e >= 0) return List.of(new FlightGear.Click(armour, e), new FlightGear.Click(dest, e));
        int k = borrowKey(held, -1);
        return List.of(new FlightGear.Click(dest, k), new FlightGear.Click(armour, k), new FlightGear.Click(dest, k));
    }

    /** What a menu click does: plain left or right click (PICKUP), a hotbar-key trade (SWAP), a wait, the anvil's name. */
    enum Kind { LEFT, RIGHT, SWAP, WAIT, RENAME }

    /** One action of a menu plan: {@code slot} in the open menu's numbering, {@code key} the hotbar key of a SWAP. */
    record Op(Kind kind, int slot, int key) {
        static final Op WAIT = new Op(Kind.WAIT, -1, -1);
        static final Op RENAME = new Op(Kind.RENAME, -1, -1);

        static Op left(int slot) {
            return new Op(Kind.LEFT, slot, -1);
        }

        static Op right(int slot) {
            return new Op(Kind.RIGHT, slot, -1);
        }

        static Op swap(int slot, int key) {
            return new Op(Kind.SWAP, slot, key);
        }

        boolean click() {
            return kind == Kind.LEFT || kind == Kind.RIGHT || kind == Kind.SWAP;
        }
    }

    /** Input slots and result slot of the cartography table and the anvil. */
    static final int INPUT = 0, ADDITION = 1, RESULT = 2;

    /**
     * The cartography table lock ({@link LockMapTask}): the map from player slot {@code mapSlot} into slot 0, one glass
     * pane of the {@code paneCount} in {@code paneSlot} into slot 1, a wait for the result, the locked map out of slot 2
     * to where the map was. Slots in the menu's numbering ({@code top} 3). With the switch:
     * <ul>
     *   <li>the map trades into slot 0 with its hotbar key, or (main inventory) through a transit key
     *       ({@link #transitKey}: an empty hotbar slot, else a borrowed one, never the pane's); the result comes back the
     *       same way (2 clicks from the hotbar, 4 from the main inventory, as before);</li>
     *   <li>the pane: from the hotbar its whole stack trades into slot 1 and what the lock leaves trades back (2, or 1
     *       for a single pane); a single pane in the main inventory goes through the map's key, empty by then (2); a
     *       stack of panes in the main inventory (only with {@code build.exactMapAndPane} off) keeps the old 3 cursor
     *       clicks, the one case a stack rides the cursor: a glass pane, while the SWAP route would take 4.</li>
     * </ul>
     * 3 to 7 clicks, never more than the old 7: pick up the map, slot 0, pick up the panes, right click one into slot
     * 1, put them back, wait, take the result, put it down where the map was.
     */
    static List<Op> lock(boolean withoutCursor, int mapSlot, int paneSlot, int paneCount, int top,
                         IntPredicate hotbarEmpty, int held) {
        int ms = ItemIds.toContainerSlot(mapSlot, top);
        int ps = ItemIds.toContainerSlot(paneSlot, top);
        if (!withoutCursor) {
            return List.of(Op.left(ms), Op.left(INPUT), Op.left(ps), Op.right(ADDITION), Op.left(ps), Op.WAIT,
                Op.left(RESULT), Op.left(ms));
        }
        int mh = ItemIds.hotbarIndex(mapSlot);
        int ph = ItemIds.hotbarIndex(paneSlot);
        boolean mapMain = mh < 0;
        int mk = mapMain ? transitKey(hotbarEmpty, held, ph) : mh;
        List<Op> out = new ArrayList<>();
        if (mapMain) out.add(Op.swap(ms, mk)); // the map into the transit slot (its stack, if any, into the map's)
        out.add(Op.swap(INPUT, mk));           // the map into slot 0: the key's slot is empty now
        List<Op> paneBack = new ArrayList<>();
        if (ph >= 0) {
            out.add(Op.swap(ADDITION, ph));
            if (paneCount > 1) paneBack.add(Op.swap(ADDITION, ph)); // what the lock leaves back into the pane's slot
        } else if (paneCount <= 1) {
            out.add(Op.swap(ps, mk));        // the single pane into the map's empty key slot
            out.add(Op.swap(ADDITION, mk));  // and into slot 1: the key's slot is empty again
        } else {
            out.add(Op.left(ps));
            out.add(Op.right(ADDITION));
            out.add(Op.left(ps));
        }
        out.add(Op.WAIT);
        out.add(Op.swap(RESULT, mk));          // the locked map into the empty key slot
        if (mapMain) out.add(Op.swap(ms, mk)); // into the map's old slot, the borrowed stack back
        out.addAll(paneBack);
        return List.copyOf(out);
    }

    /**
     * The anvil rename ({@link RenameMapTask}): the map from {@code mapSlot} into slot 0, the name, a wait, the renamed
     * map out of slot 2 to where the map was. With the switch the map trades with its hotbar key (2 clicks) or through
     * a transit key (4); off: pick up, slot 0, name, wait, take the result, put it down (4 plain clicks, the old ones).
     */
    static List<Op> rename(boolean withoutCursor, int mapSlot, int top, IntPredicate hotbarEmpty, int held) {
        int ms = ItemIds.toContainerSlot(mapSlot, top);
        if (!withoutCursor) {
            return List.of(Op.left(ms), Op.left(INPUT), Op.RENAME, Op.WAIT, Op.left(RESULT), Op.left(ms));
        }
        int mh = ItemIds.hotbarIndex(mapSlot);
        if (mh >= 0) return List.of(Op.swap(INPUT, mh), Op.RENAME, Op.WAIT, Op.swap(RESULT, mh));
        int k = transitKey(hotbarEmpty, held, -1);
        return List.of(Op.swap(ms, k), Op.swap(INPUT, k), Op.RENAME, Op.WAIT, Op.swap(RESULT, k), Op.swap(ms, k));
    }

    /** The clicks of a plan (waits and the name left out), for the click counts. */
    static long clicks(List<Op> ops) {
        return ops.stream().filter(Op::click).count();
    }
}
