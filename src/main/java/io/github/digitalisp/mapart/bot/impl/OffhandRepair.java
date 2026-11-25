package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

/**
 * Puts the offhand's item (the totem) back from the inventory (the airplace guard
 * names the slot, {@link AirPlaceGuard#repairSlot}). Pure: the game goes through {@link Port}. One container click in
 * the player inventory: the item's slot swapped with the offhand (ClickContainer, SWAP, button 40: the F key over the
 * slot), whatever the offhand holds going to that slot. Only while no container is open, no inventory request is in
 * flight and the previous tick's input pressed no movement key (Grim's MultiActionsC flags a click while walking); the
 * task holds still meanwhile. Done once the offhand shows the item; one click per run (the engine retries later).
 *
 * <p><b>Server check first</b>: when the guard sees no item to put back in
 * the cache ({@link Port#checkWanted}), the run first asks the server for the full inventory (the stateId -1 no-op
 * click, under the same still-tick rules), waits for the answer ({@link Port#inventoryMark} grows) and hands it to the
 * guard ({@link Port#checked}); only then does it repair, or fail with "no item to put back". Drill gs2: the cache had
 * lost the totem while the server had it, and "No totem" held the bot for 9 minutes.
 */
final class OffhandRepair {
    /** Ticks after the click for the cache to show the item in the offhand before the run fails. */
    static final int CONFIRM_TICKS = 40;
    /** Ticks the run may wait for a still tick and a free inventory before it fails. */
    static final int WAIT_TICKS = 200;
    /** Ticks after the full-inventory request for the server's answer before the run fails. */
    static final int ANSWER_TICKS = 40;

    /** The game for one repair (ZenithProxy in {@code OffhandRepairTask}, fakes in tests). */
    interface Port {
        @Nullable String offhand();

        /** The guard's slot to swap into the offhand now (player slot 9-44), -1 if none. */
        int repairSlot();

        /** The item the offhand should hold. */
        String item();

        boolean containerOpen();

        /** An inventory request (ours or another module's) is queued or executing. */
        boolean inventoryBusy();

        /** The previous tick's input pressed no movement key (and the bot does not sprint). */
        boolean stillLastTick();

        /** Queues the click that swaps player slot {@code playerSlot} with the offhand; true if queued. */
        boolean swapIntoOffhand(int playerSlot);

        /** The guard wants the server's full inventory before it believes "nothing to put back". */
        default boolean checkWanted() { return false; }

        /** Queues the full-inventory request (no-op click, stateId -1); true if queued. */
        default boolean requestInventory() { return false; }

        /** Full player-inventory answers received so far (grows by one per answer). */
        default long inventoryMark() { return 0; }

        /** The answer is in the cache: the guard judges the offhand from it. */
        default void checked() { }
    }

    enum Step { RUNNING, DONE, FAILED }

    private final Port port;
    private long start = -1;
    private long clickedAt = -1;
    private int clickedSlot = -1;
    /** The full-inventory request went out at this tick (-1: not sent), with the answer count then. */
    private long requestedAt = -1;
    private long requestMark;
    private boolean checked;
    /** Start of the wait for the guard to name a slot (after the check, if one ran). */
    private long idleFrom = -1;
    private @Nullable String failure;

    OffhandRepair(Port port) {
        this.port = port;
    }

    /** One tick ({@code now} increasing by one). */
    Step tick(long now) {
        if (start < 0) start = now;
        if (port.item().equals(port.offhand())) return Step.DONE;
        if (clickedAt >= 0) {
            if (now - clickedAt <= CONFIRM_TICKS) return Step.RUNNING;
            return fail("the offhand still holds " + name(port.offhand()) + " " + CONFIRM_TICKS
                + " ticks after the click on slot " + clickedSlot);
        }
        if (requestedAt >= 0 && !checked) {
            if (port.inventoryMark() <= requestMark) {
                if (now - requestedAt <= ANSWER_TICKS) return Step.RUNNING;
                return fail("no answer to the full inventory request in " + ANSWER_TICKS + " ticks");
            }
            checked = true;
            port.checked();
            if (port.item().equals(port.offhand())) return Step.DONE; // the cache was stale: the server has it there
        } else if (!checked && port.checkWanted()) {
            if (port.containerOpen() || port.inventoryBusy() || !port.stillLastTick()) return waiting(now);
            long mark = port.inventoryMark();
            if (port.requestInventory()) {
                requestedAt = now;
                requestMark = mark;
            }
            return Step.RUNNING;
        }
        int slot = port.repairSlot();
        if (slot < 0) return idle(now); // the guard names no slot: settling, or the item left the inventory
        if (port.containerOpen() || port.inventoryBusy() || !port.stillLastTick()) return waiting(now);
        if (port.swapIntoOffhand(slot)) {
            clickedAt = now;
            clickedSlot = slot;
        }
        return Step.RUNNING;
    }

    /** Slot clicked (player slot), -1 before the click. */
    int clickedSlot() {
        return clickedSlot;
    }

    /** The full-inventory request went out (status). */
    boolean inventoryRequested() {
        return requestedAt >= 0;
    }

    @Nullable String failure() {
        return failure;
    }

    private Step idle(long now) {
        if (idleFrom < 0) idleFrom = now;
        if (now - idleFrom > AirPlaceGuard.SETTLE_TICKS) {
            return fail("no " + port.item() + " to put back" + (checked ? " (the server's full inventory has none)" : ""));
        }
        return Step.RUNNING;
    }

    private Step waiting(long now) {
        if (now - start > WAIT_TICKS) {
            return fail("no still tick with the inventory free in " + WAIT_TICKS + " ticks");
        }
        return Step.RUNNING;
    }

    private Step fail(String reason) {
        failure = reason;
        return Step.FAILED;
    }

    private static String name(@Nullable String item) {
        return item == null ? "nothing" : item;
    }
}
