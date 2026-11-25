package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The account is the owner's own: the offhand (live: a totem, kept there by ZenithProxy's AutoTotem) and the armour slots are
 * not the bot's. Pure (no ZenithProxy calls), so it is unit-tested.
 *
 * <p><b>Rule.</b> The bot never moves anything out of the offhand (player slot 45) or the armour slots (5 head .. 8 feet):
 * no deposit, drop, throw, shift-click, hotbar swap or pick-up click takes from them. Only player slots 9..44 (main
 * inventory and hotbar) are {@link #movable}. {@code Inv.find}/{@code Inv.count} and the {@code InventoryView} the engine
 * sees cover only those 36 slots, so nothing plans with an offhand item either.
 *
 * <p><b>Exceptions.</b>
 * <ul>
 *   <li>Boots: GEAR equips configured Soul Speed boots ({@code EquipTask}) into an EMPTY feet slot; it never moves a worn
 *       piece out.</li>
 *   <li>Airplace (sanctioned, temporary): a placement may swap the selected hotbar stack with the offhand
 *       (PlayerAction SWAP_ITEM_WITH_OFFHAND), place from the offhand and swap back in the same tick. The offhand item
 *       never leaves the inventory. The click path records the swap here ({@link #swappedOut}, {@link #swappedBack});
 *       a swap still open on a later tick (the swap back was dropped, the task was cancelled between the two packets) is
 *       repaired first: {@link #restore} names the hotbar slot to swap back from, before anything else is clicked.</li>
 *   <li>Elytra legs ({@code build.elytraLegs}, off by default; {@code FlightLeg}): the worn chestplate (slot 6) and a
 *       carried elytra trade places for a flight, and back after it, at the restock's end, before a fight or an escape
 *       ({@code FlightGear.chestBack}). Both stay in the inventory; a swap the server did not answer is retried, and
 *       {@code ZenithTask} puts the chestplate back before any other task step.</li>
 * </ul>
 */
public final class OffhandGuard {
    /** Player-inventory armour slots (5 head .. 8 feet) and the offhand. */
    public static final int ARMOR_FIRST = 5;
    public static final int ARMOR_LAST = 8;

    /** True if the bot may move the stack in player slot {@code playerSlot} (main inventory or hotbar, 9..44). */
    public static boolean movable(int playerSlot) {
        return playerSlot >= ItemIds.MAIN_START && playerSlot <= ItemIds.HOTBAR_END;
    }

    /** True for the slots the bot never takes from: the armour slots and the offhand. */
    public static boolean protectedSlot(int playerSlot) {
        return playerSlot == ItemIds.OFFHAND || (playerSlot >= ARMOR_FIRST && playerSlot <= ARMOR_LAST);
    }

    // ---- airplace exception -------------------------------------------------------------------

    /** What {@link #restore} asks the click path to do this tick. */
    public enum Action {
        /** No swap open, or the offhand already holds its item again. */
        NONE,
        /** Select {@link Restore#hotbar} (if not selected) and send SWAP_ITEM_WITH_OFFHAND now, before any other click. */
        SWAP_BACK,
        /**
         * The item is neither in the offhand nor in the hotbar slot it was swapped into (moved by someone else, e.g. a
         * player or AutoTotem): stop airplacing and alert; never search for it with further clicks.
         */
        LOST
    }

    /** {@link #restore}'s answer: the action and the hotbar index (0..8) the offhand item sits in. */
    public record Restore(Action action, int hotbar, @Nullable String item) {
        static final Restore NONE = new Restore(Action.NONE, -1, null);
    }

    /** Offhand item id moved to the hotbar by an open swap; null when no swap is open. */
    private @Nullable String outItem;
    private int outHotbar = -1;
    private long outTick;

    /**
     * Record a swap BEFORE sending it: the offhand held {@code offhandItem} (null = empty, nothing to protect) and the
     * selected hotbar index is {@code hotbar}. The swap must be closed with {@link #swappedBack} in the same tick.
     */
    public void swappedOut(@Nullable String offhandItem, int hotbar, long tick) {
        if (offhandItem == null || hotbar < 0 || hotbar > 8) {
            clear();
            return;
        }
        outItem = offhandItem;
        outHotbar = hotbar;
        outTick = tick;
    }

    /** The swap back was sent (or the offhand was seen holding its item again). */
    public void swappedBack() {
        clear();
    }

    /** True while a swap is open (recorded, not yet swapped back). */
    public boolean open() {
        return outItem != null;
    }

    /**
     * Repair of an interrupted swap, checked at the start of every tick of the click path (and of any task that clicks
     * the inventory while one may be open). {@code offhandItem}: what the offhand holds now; {@code hotbarItem}: what
     * hotbar index {@link Restore#hotbar} holds now. A swap opened in this tick is not overdue yet.
     */
    public Restore restore(long tick, @Nullable String offhandItem, @Nullable String hotbarItem) {
        if (outItem == null) return Restore.NONE;
        if (Objects.equals(outItem, offhandItem)) {
            clear(); // back in place (the swap back went through)
            return Restore.NONE;
        }
        if (tick <= outTick) return Restore.NONE;
        if (Objects.equals(outItem, hotbarItem)) return new Restore(Action.SWAP_BACK, outHotbar, outItem);
        Restore lost = new Restore(Action.LOST, outHotbar, outItem);
        clear();
        return lost;
    }

    /** Hotbar index the open swap moved the offhand item to; -1 when none is open. */
    public int hotbar() {
        return outHotbar;
    }

    private void clear() {
        outItem = null;
        outHotbar = -1;
        outTick = 0;
    }
}
