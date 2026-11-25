package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.model.Pos;

/**
 * One airplace (the IceTank printer's AirPlaceAction, reimplemented). Pure:
 * the packets go through {@link Port} (ZenithProxy in {@link ZenithAirPlace}, fakes in tests). In the click tick,
 * from the engine's ClientBotTick handler (priority 0, before ZenithProxy's InventoryManager -5000, InputManager
 * -10000 and Bot -20000), so everything precedes this tick's movement packet:
 * <pre>
 *   [SetCarriedItem(slot)]                       only if the selected slot changes
 *   PlayerAction SWAP_ITEM_WITH_OFFHAND          the item to place goes to the offhand, the offhand item to the hand
 *   UseItemOn(OFF_HAND, the target cell, face UP, cursor 0.5/0.5/0.5, inside, next sequence)
 *   Swing(OFF_HAND)
 *   PlayerAction SWAP_ITEM_WITH_OFFHAND          both back
 * </pre>
 * No rotation packet. The server places into the empty cell itself (BlockPlaceContext: an air cell is replaceable, so
 * the clicked position is the cell) and checks reach only (eye to the cell within the block interaction range + 1);
 * the lane keeps eye → cell centre within {@code build.airPlaceRange}. The offhand item (live: a totem) sits in the
 * selected hotbar slot between the two swaps and is back in the offhand when the method returns; an interruption between
 * the swaps is finished or corrected by {@link AirPlaceGuard#tick} on the next tick.
 *
 * <p>The printer first moves a block out of the offhand (container click + close). Here a block in the offhand
 * disables airplace instead ({@link AirPlaceGuard#mayAirPlace}): the lane places by LOOK.
 */
public final class AirPlace {
    /** The packets of one airplace (ZenithProxy client; fakes in tests). Each returns false when nothing was sent. */
    public interface Port {
        /** SetCarriedItem(slot), only if the selected slot differs. True if {@code slot} is selected afterwards. */
        boolean select(int slot);

        /** PlayerAction SWAP_ITEM_WITH_OFFHAND (position 0,0,0, face DOWN, sequence 0, as the vanilla client). */
        boolean swapOffhand();

        /**
         * UseItemOn(OFF_HAND, (x, y, z), face UP, cursor 0.5/0.5/0.5, inside true, world border false) with the next
         * block-state prediction sequence. Returns that sequence, or -1 if nothing was sent.
         */
        int useItemOnInside(int x, int y, int z);

        /** Swing(OFF_HAND). */
        void swingOffhand();

        /**
         * As {@link #useItemOnInside(int, int, int)} with the clicked face {@code face} ({@link #face}): a pillar takes
         * its axis from it. Ports that cannot send another face send UP.
         */
        default int useItemOnInside(int x, int y, int z, String face) {
            return useItemOnInside(x, y, z);
        }
    }

    /**
     * The face an airplace of {@code target} names: a pillar lying on its side (axis x or z, e.g. the colour-suppression
     * preset's bamboo_block[axis=x], whose side shows the plant colour and whose end yellow) takes its axis from the
     * clicked face, so "east" or "south"; everything else "up", the top-face click's state.
     */
    public static String face(io.github.digitalisp.mapart.model.@org.jspecify.annotations.Nullable BlockSpec target) {
        if (target == null) return "up";
        String axis = target.props().get("axis");
        return "x".equals(axis) ? "east" : "z".equals(axis) ? "south" : "up";
    }

    /** What {@link #place} did. */
    public enum Result {
        /** All packets sent: placed (pending the server's confirmation). */
        SENT,
        /** Nothing sent: the guard does not allow airplace now, or a container is open. */
        NOT_READY,
        /** Nothing placed: not connected, the slot could not be selected or the use packet failed (swapped back). */
        NOT_SENT
    }

    private final AirPlaceGuard guard;
    private final Port port;
    private int placed;
    private boolean batch;
    private int batchSlot = -1;
    private String batchMaterial;

    /** Tick scope; opening is lazy, so a tick without airplaces sends no swaps. */
    public void beginBatch(boolean enabled) {
        if (batchSlot >= 0) throw new IllegalStateException("unclosed airplace batch");
        batch = enabled;
    }

    /** True only during this tick's own open swap, never while an earlier swap awaits recovery. */
    public boolean batchOpen() { return batchSlot >= 0; }

    /** Called from the lane tick's finally block, before movement or inventory processing. */
    public void endBatch() {
        batch = false;
        if (batchSlot < 0) return;
        batchSlot = -1;
        batchMaterial = null;
        if (port.swapOffhand()) guard.swappedBack();
        // Failure keeps the guard's recovery record intact for the next tick.
    }

    public AirPlace(AirPlaceGuard guard, Port port) {
        this.guard = guard;
        this.port = port;
    }

    /** Airplaces sent so far. */
    public int placed() {
        return placed;
    }

    /**
     * Places the item in hotbar slot {@code slot} (0-8) into {@code cell}. The caller has checked reach and that the
     * cell is empty and clear of the bot's box. The caller enforces its per-tick and window click budgets.
     */
    public Result place(AirPlaceGuard.View v, int slot, Pos cell) {
        return place(v, slot, cell, "up");
    }

    /** As {@link #place(AirPlaceGuard.View, int, Pos)}, clicking {@code face} ({@link #face}). */
    public Result place(AirPlaceGuard.View v, int slot, Pos cell, String face) {
        if (!v.inGame() || v.containerOpen() || v.inventoryBusy()) return Result.NOT_READY;
        if (batchSlot >= 0) {
            if (slot != batchSlot || v.heldSlot() != batchSlot
                || !java.util.Objects.equals(batchMaterial, v.offhand())) return Result.NOT_READY;
            return use(cell, face);
        }
        if (slot < 0 || slot > 8 || !guard.mayAirPlace(v)) return Result.NOT_READY;
        if (v.heldSlot() != slot && !port.select(slot)) return Result.NOT_SENT;
        // recorded before swap 1 is sent (OffhandGuard's contract), withdrawn if it could not be sent
        String material = v.hotbar(slot);
        guard.swappedOut(slot, material, v.offhand());
        if (!port.swapOffhand()) {
            guard.swappedBack();
            return Result.NOT_SENT;
        }
        if (batch) {
            batchSlot = slot;
            batchMaterial = material;
            return use(cell, face);
        }
        try {
            return use(cell, face);
        } finally {
            // always back, whatever happened in between; if this throws, the guard finishes it next tick
            if (port.swapOffhand()) guard.swappedBack();
        }
    }

    private Result use(Pos cell, String face) {
        int seq = "up".equals(face) ? port.useItemOnInside(cell.x(), cell.y(), cell.z())
            : port.useItemOnInside(cell.x(), cell.y(), cell.z(), face);
        if (seq < 0) return Result.NOT_SENT;
        placed++;
        port.swingOffhand();
        return Result.SENT;
    }
}
