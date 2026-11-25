package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CustomAction;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

import static com.zenith.Globals.INVENTORY;

/**
 * Puts an item in the main hand through ZenithProxy's InventoryManager (one outstanding request per task).
 *
 * <p>Hotbar layout: slot 0 is the food slot ({@link EatTask}); 7 is where special items go when a task needs one
 * (empty/filled map, glass pane, XP bottles, anvil), 8 the mining tool. A break task that needs several tools gives
 * each its own slot, 8 then 7, 6, ... ({@link ToolRack}), so it never swaps tools through one slot. A lane's materials
 * may use every slot but the food slot ({@link #MATERIAL_SLOTS}, 1-8): a move into a slot holding a tool, a map or
 * other non-material swaps that item into the main inventory in the same click, and the task that needs it later
 * moves it back while standing still (one click). So BUILD has 8 slots for materials instead of 5-6 (gate B, 11
 * materials: 110 inventory moves in 1280 placements with tools in 6 and 8 and maps in 7).
 *
 * <p>With {@code build.hotbarMoveFence} the server answers a move with the whole inventory, and this task holds,
 * moves nothing until that answer shows the moved item in its slot ({@link MoveFence}): until then the cache can
 * show the slot's old item again. Lanes may click other slots with {@code build.placeDuringMoveFence} once the
 * inventory request has finished; every other caller keeps the full hold.
 */
final class Hotbar {
    static final int FOOD_SLOT = 0;
    /** Hotbar indexes a lane moves its materials into: all but the food slot. */
    static final int[] MATERIAL_SLOTS = {1, 2, 3, 4, 5, 6, 7, 8};
    static final int[] SPECIAL_SLOT = {7};
    static final int[] TOOL_SLOT = {8};
    /** Layout leaves food, special slot and tool slot alone, including when empty. */
    static boolean layoutSlot(int slot, @Nullable ItemStack stack) {
        return slot >= 1 && slot <= 6 && Wear.maxUses(stack) == 0 && Wear.kind(stack) == null;
    }
    /** Give up waiting for a swap request after this many ticks (it is resubmitted). */
    static final int SWAP_TIMEOUT = 100;

    private final Object owner;
    private final Clicker clicker;
    /** {@code build.hotbarMoveFence}, read when a move is planned. */
    private final BooleanSupplier fenceOn;
    /** {@code build.hotbarMoveForeignStateId}, read as a fenced move goes out. */
    private final BooleanSupplier foreignStateId;
    private final MoveFence fence = new MoveFence();
    private @Nullable RequestFuture pending;
    private long pendingSince;
    private long lastNow;
    private int roundRobin;
    private final boolean lane;
    private static @Nullable RequestFuture speculativeRequest;

    Hotbar(Object owner, Clicker clicker, BooleanSupplier fenceOn) {
        this(owner, clicker, fenceOn, false);
    }

    Hotbar(Object owner, Clicker clicker, BooleanSupplier fenceOn, boolean lane) {
        this(owner, clicker, fenceOn, lane, () -> false);
    }

    Hotbar(Object owner, Clicker clicker, BooleanSupplier fenceOn, boolean lane, BooleanSupplier foreignStateId) {
        this.lane = lane;
        if (!lane) InventoryEchoes.SESSION.inventoryNeeded();
        this.owner = owner;
        this.clicker = clicker;
        this.fenceOn = fenceOn;
        this.foreignStateId = foreignStateId;
    }

    /**
     * True if holding an item matching {@code pred} needs an inventory click (it is not in the hotbar). The caller
     * must then stand still (no movement keys) for a tick before {@link #ensureHeld} will do it.
     */
    static boolean needsClick(Predicate<ItemStack> pred) {
        ItemStack hand = Inv.mainHand();
        if (!Inv.empty(hand) && pred.test(hand)) return false;
        int slot = Inv.find(pred);
        return slot >= 0 && ItemIds.hotbarIndex(slot) < 0;
    }

    /** A swap of ours is queued or executing, or its move waits for the server's answer ({@link MoveFence}). */
    boolean busy(long now) {
        boolean waiting = fenced(now);
        return requestBusy(now) || waiting;
    }

    /** Only request execution: its SetHeldItem can follow a lane click, so every slot must wait. */
    boolean requestBusy(long now) {
        if (pending == null) return false;
        if (pending.isCompleted() || now - pendingSince > SWAP_TIMEOUT) {
            pending = null;
            return false;
        }
        return true;
    }

    /** Refresh the fence and return its stale target, or -1. Moves remain blocked by busy(). */
    int fencedSlot(long now) {
        if (!fenced(now)) return -1;
        int quarantined = InventoryEchoes.SESSION.quarantinedSlot();
        return quarantined >= 0 ? quarantined : fence.slot();
    }

    /**
     * True if the main hand holds a matching item. Otherwise schedules a swap (hotbar select, or move from the main
     * inventory into one of {@code targets}; never from the offhand or armour, {@link OffhandGuard}) and returns false.
     * The stack moved out of the target slot stays in the main inventory. Never acts while a container is open. A move
     * from the main inventory is an inventory click, so it waits until the previous tick's input pressed no movement
     * key ({@link Clicker#stillLastTick}).
     */
    boolean ensureHeld(Predicate<ItemStack> pred, int[] targets, long now) {
        return ensureHeld(pred, targets, now, null);
    }

    /**
     * {@link #ensureHeld}, choosing the target slot with {@link #chooseTarget}: a slot whose stack matches
     * {@code keep} (a lane's materials) is replaced only when no target is empty or holds anything else.
     */
    boolean ensureHeld(Predicate<ItemStack> pred, int[] targets, long now, @Nullable Predicate<ItemStack> keep) {
        return ensureHeld(pred, targets, now, keep, null);
    }

    /**
     * {@link #ensureHeld(Predicate, int[], long, Predicate)}; with {@code nextUse} (hotbar index → when its stack is
     * needed next, larger = later, {@link Integer#MAX_VALUE} = not again) a move replaces the kept stack needed last
     * ({@link #chooseTarget(int[], IntFunction, int, IntUnaryOperator)}, Belady's rule).
     */
    boolean ensureHeld(Predicate<ItemStack> pred, int[] targets, long now, @Nullable Predicate<ItemStack> keep,
                       @Nullable IntUnaryOperator nextUse) {
        return ensureHeld(pred, targets, now, keep, nextUse, false, s -> true);
    }

    /** Prefetch into a retired/empty slot only; an early eviction of a future material can add moves. */
    boolean ensureHeld(Predicate<ItemStack> pred, int[] targets, long now, @Nullable Predicate<ItemStack> keep,
                       @Nullable IntUnaryOperator nextUse, boolean prefetch,
                       java.util.function.IntPredicate replaceable) {
        if (fenced(now)) return noMove("fenced", now); // the cache may still show a slot's old item
        ItemStack hand = Inv.mainHand();
        if ((!lane || InventoryEchoes.SESSION.known(Inv.heldSlot())) && !Inv.empty(hand) && pred.test(hand)) return true;
        if (Inv.containerOpen()) return noMove("container open", now);
        if (busy(now)) return noMove("busy", now);
        int slot = Inv.find(pred);
        if (lane && slot >= ItemIds.HOTBAR_START && !InventoryEchoes.SESSION.known(ItemIds.hotbarIndex(slot))) {
            slot = -1;
            for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
                int h = ItemIds.hotbarIndex(i);
                if (h >= 0 && !InventoryEchoes.SESSION.known(h)) continue;
                ItemStack stack = Inv.stack(i);
                if (!Inv.empty(stack) && pred.test(stack)) { slot = i; break; }
            }
        }
        if (slot < 0) return noMove("no known stack", now);
        if (!OffhandGuard.movable(slot)) return noMove("slot " + slot + " not movable", now);
        int hotbar = ItemIds.hotbarIndex(slot);
        List<InventoryAction> actions;
        if (hotbar >= 0) {
            actions = List.of(new SetHeldItem(hotbar));
        } else {
            if (!clicker.stillLastTick(now)) return noMove("moved last tick", now);
            int target = pickTarget(targets, keep, nextUse);
            if (prefetch && !prefetchSafe(target, nextUse, replaceable)) return noMove("prefetch unsafe", now);
            // A prefetch fills another slot without changing the held-first placement order.
            actions = prefetch ? List.of(move(slot, target))
                : List.of(move(slot, target), new SetHeldItem(target));
        }
        RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(owner)
            .priority(Clicker.PRIORITY)
            .actions(actions)
            .build());
        if (f != RequestFuture.rejected) {
            pending = f;
            pendingSince = now;
            noMoveWhy = null;
        } else {
            noMove("request rejected", now);
        }
        return false;
    }

    /** Why {@link #ensureHeld} last made no move, and when (for the lane watchdog's line); null: it made one. */
    private @Nullable String noMoveWhy;
    private long noMoveAt;

    private boolean noMove(String why, long now) {
        noMoveWhy = why;
        noMoveAt = now;
        return false;
    }

    /**
     * Like {@link #ensureHeld}, for the stack in one player slot (9..44): true once the main hand is that slot.
     * A hotbar slot is selected; a main-inventory slot is moved into the first of {@code targets} (an inventory click,
     * only after a still tick).
     */
    boolean ensureSlotHeld(int playerSlot, int[] targets, long now) {
        if (fenced(now)) return false;
        int h = ItemIds.hotbarIndex(playerSlot);
        if (lane && h >= 0 && !InventoryEchoes.SESSION.known(h)) return false;
        if (playerSlot == ItemIds.HOTBAR_START + Inv.heldSlot()) return true;
        if (Inv.containerOpen() || busy(now)) return false;
        int hotbar = ItemIds.hotbarIndex(playerSlot);
        List<InventoryAction> actions;
        if (hotbar >= 0) {
            actions = List.of(new SetHeldItem(hotbar));
        } else {
            if (!OffhandGuard.movable(playerSlot) || !clicker.stillLastTick(now)) return false;
            int target = targets[0];
            actions = List.of(move(playerSlot, target), new SetHeldItem(target));
        }
        RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(owner)
            .priority(Clicker.PRIORITY)
            .actions(actions)
            .build());
        if (f != RequestFuture.rejected) {
            pending = f;
            pendingSince = now;
        }
        return false;
    }

    /**
     * The click moving player slot {@code slot} into hotbar index {@code target}. With {@code build.hotbarMoveFence}:
     * ZenithProxy's own move packet (its prediction included) with a state id the server does not have, so it answers
     * with the whole inventory, and the fence armed as the click goes out, for the item the cache shows in {@code slot}
     * then. Without: ZenithProxy's move, as before. The state id is the cache's plus one ({@link #moveStateId}), which
     * is the server's when exactly one of its inventory updates is still on the way: the server then answers nothing
     * and the move waits out {@link MoveFence#TIMEOUT}; with {@code build.hotbarMoveForeignStateId} it is -1, which no
     * server state id is.
     */
    private InventoryAction move(int slot, int target) {
        return move(slot, target, false);
    }

    private InventoryAction move(int slot, int target, boolean requireAnswer) {
        MoveToHotbarAction action = MoveToHotbarAction.from(target);
        if (!fenceOn.getAsBoolean()) return new MoveToHotbarSlot(slot, action);
        return new CustomAction(0, () -> {
            if (requireAnswer && owner instanceof PlaceLaneTask task && !task.lookaheadEnabled()) return null;
            MinecraftPacket p = new MoveToHotbarSlot(slot, action).packet();
            if (!(p instanceof ServerboundContainerClickPacket click)) return p;
            String item = Inv.id(Inv.stack(slot));
            long mark = InventoryEchoes.SESSION.mark();
            if (requireAnswer) InventoryEchoes.SESSION.quarantine(target, item, mark);
            fence.arm(target, item, mark, lastNow, requireAnswer);
            int next = Inv.cache().getPlayerInventory().incrementStateId();
            return click.withStateId(moveStateId(next, foreignStateId.getAsBoolean()));
        });
    }

    /**
     * The state id a fenced move goes out with: {@code next} (the cache's plus one, as before), or with
     * {@code foreign} ({@code build.hotbarMoveForeignStateId}) -1, which no server state id is (they run 0-32767).
     * The server answers a click whose state id is not its own with the whole inventory; one that matches, with only
     * the slots that differ from the click's prediction: none when the prediction was right, so no answer at all.
     * The cache's own state id advances either way. Pure.
     */
    static int moveStateId(int next, boolean foreign) {
        return foreign ? -1 : next;
    }

    /** One layout move, without a held-slot change, under the same still-tick/request/fence rules. */
    boolean layoutMove(String item, int target, long now) {
        if (Inv.containerOpen() || INVENTORY.hasActiveRequest() || busy(now) || !clicker.stillLastTick(now)) return false;
        if (!layoutSlot(target, Inv.stack(ItemIds.HOTBAR_START + target))) return false;
        int source = -1;
        for (int s = ItemIds.MAIN_START; s < ItemIds.HOTBAR_START; s++) {
            ItemStack stack = Inv.stack(s);
            if (!Inv.empty(stack) && Inv.is(item).test(stack)) {
                source = s;
                break;
            }
        }
        return source >= 0 && submit(List.of(move(source, target)), now) != null;
    }

    /** Lookahead fills another slot, including a second stack, and never selects it or trusts a timeout. */
    boolean prefetchMove(String item, int target, long now) {
        if (!fenceOn.getAsBoolean() || Inv.containerOpen() || INVENTORY.hasActiveRequest() || busy(now)
            || !clicker.stillLastTick(now) || target == Inv.heldSlot()) return false;
        ItemStack old = Inv.stack(ItemIds.HOTBAR_START + target);
        if (!prefetchSlot(target, old) || (!Inv.empty(old) && Inv.is(item).test(old))) return false;
        for (int source = ItemIds.MAIN_START; source < ItemIds.HOTBAR_START; source++) {
            ItemStack stack = Inv.stack(source);
            if (!Inv.empty(stack) && Inv.is(item).test(stack)) {
                speculativeRequest = submit(List.of(move(source, target, true)), now);
                return speculativeRequest != null;
            }
        }
        return false;
    }

    /** Speculation leaves food, tools and personal items alone; ordinary demand moves keep their old policy. */
    static boolean prefetchSlot(int slot, @Nullable ItemStack stack) {
        return slot >= 1 && slot <= 8 && Wear.maxUses(stack) == 0 && Wear.kind(stack) == null
            && (Inv.empty(stack) || (!ZenithInventoryView.isFood(stack) && !Valuables.isValuable(Inv.id(stack))
                && !Inv.is("totem_of_undying").test(stack)));
    }

    /**
     * A move waits for the answer, including session quarantine from a previous task; logs missing answers once. A lane
     * also sends the full-inventory request here ({@link InventoryEchoes#needsResync}: a recovery's, or one for slots
     * left unknown), under {@link #resyncAllowed}'s rules.
     */
    private boolean fenced(long now) {
        lastNow = now;
        if (!lane) InventoryEchoes.SESSION.inventoryNeeded();
        boolean waiting = fence.waiting(now, InventoryEchoes.SESSION);
        if (lane && InventoryEchoes.SESSION.needsResync() && resyncAllowed(requestBusy(now), Inv.containerOpen(),
            INVENTORY.hasActiveRequest(), clicker.stillLastTick(now), System.nanoTime() - Clicker.lastClickNanos)) {
            int slot = InventoryEchoes.SESSION.resyncSlot();
            if (slot >= 0) {
                RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder().owner(owner).priority(Clicker.PRIORITY)
                    .actions(List.of(new CustomAction(0, () -> {
                        if (!InventoryEchoes.SESSION.needsResync() || Inv.containerOpen()) return null;
                        InventoryEchoes.SESSION.resyncSent();
                        return resyncPacket(slot, Inv.cache().getMouseStack());
                    }))).build());
                if (f != RequestFuture.rejected) { pending = f; pendingSince = now; speculativeRequest = f; }
            }
        }
        String timedOut = fence.takeTimeout();
        if (timedOut != null) {
            ZenithTask.LOG.info("Hotbar move into {}: no expected answer in {} ticks; {}", timedOut, MoveFence.TIMEOUT,
                InventoryEchoes.SESSION.recovering() ? "requesting full inventory; bounded recovery"
                    : "re-planning from the inventory cache");
        }
        return waiting || InventoryEchoes.SESSION.quarantinedSlot() >= 0;
    }

    /**
     * The full-inventory request ({@link #resyncPacket}) is an inventory click: only with no request of ours or anyone's
     * in flight, no container open, after an executed still tick (Grim MultiActionsC) and not within 50 ms of a
     * use-item-on (PacketOrder). Pure.
     */
    static boolean resyncAllowed(boolean requestBusy, boolean containerOpen, boolean inventoryRequest,
                                 boolean stillLastTick, long nanosSinceClick) {
        return !requestBusy && !containerOpen && !inventoryRequest && stillLastTick && nanosSinceClick >= 50_000_000L;
    }

    /** What holds this task's inventory moves now, for a lane's hold line ({@link LaneRun.Io#holdState}). */
    String state(long now) {
        boolean req = pending != null && !pending.isCompleted() && now - pendingSince <= SWAP_TIMEOUT;
        InventoryEchoes e = InventoryEchoes.SESSION;
        return "request " + (req ? "in flight" : "none") + ", fenced slot " + fence.slot() + ", quarantined slot "
            + e.quarantinedSlot() + (e.recovering() ? " (recovering)" : "") + ", unknown slots " + e.unknownSlots()
            + (e.anyUnknown() ? " (" + e.unknownResyncs() + " full inventory requests)" : "")
            + ", still last tick " + clicker.stillLastTick(now)
            + (noMoveWhy == null ? "" : ", last move refused: " + noMoveWhy + " " + (now - noMoveAt) + " t ago");
    }

    /** A same-slot hotbar swap is a no-op, even for an empty slot or a carried stack. -1 cannot be a server state id. */
    static ServerboundContainerClickPacket resyncPacket(int slot, @Nullable ItemStack cursor) {
        return new ServerboundContainerClickPacket(0, -1, ItemIds.HOTBAR_START + slot,
            org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType.MOVE_TO_HOTBAR_SLOT,
            MoveToHotbarAction.from(slot), cursor,
            new it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap<>());
    }

    static boolean speculativeRequestBusy() {
        return speculativeRequest != null && !speculativeRequest.isCompleted();
    }

    /** The lane summary's part on hotbar moves and their waits ({@link MoveFence#summary}). */
    String fenceSummary() {
        return fence.summary();
    }

    /**
     * Hotbar index (0..8) to hold for breaking without a tool: the selected slot if it holds nothing or an item that
     * never wears, else the first empty hotbar slot, else the first holding such an item; -1 if every hotbar slot
     * holds a damageable item (tools, weapons).
     */
    static int bareHandIndex() {
        if (!Wear.damageable(Inv.mainHand())) return Inv.heldSlot();
        for (int i = 0; i < 9; i++) if (Inv.empty(Inv.stack(ItemIds.HOTBAR_START + i))) return i;
        for (int i = 0; i < 9; i++) if (!Wear.damageable(Inv.stack(ItemIds.HOTBAR_START + i))) return i;
        return -1;
    }

    /** What a target hotbar slot holds, for {@link #chooseTarget}. */
    enum Holds {
        EMPTY,
        /** Something the caller does not keep there (a tool, a map, bottles, panes): moves out first. */
        OTHER,
        /** A stack the caller keeps (a lane material), not the selected slot. */
        KEPT,
        /** A kept stack in the selected slot (most likely the next one placed): replaced last. */
        HELD
    }

    /** An early move must not evict a future material or a stack whose clicks still await settlement. */
    static boolean prefetchSafe(int target, @Nullable IntUnaryOperator nextUse,
                                java.util.function.IntPredicate replaceable) {
        return nextUse != null && nextUse.applyAsInt(target) == Integer.MAX_VALUE && replaceable.test(target);
    }

    /** Target slot for a move into the hotbar ({@link #chooseTarget}); advances the round robin. */
    private int pickTarget(int[] targets, @Nullable Predicate<ItemStack> keep, @Nullable IntUnaryOperator nextUse) {
        int held = Inv.heldSlot();
        int t = chooseTarget(targets, i -> {
            ItemStack s = Inv.stack(ItemIds.HOTBAR_START + i);
            if (Inv.empty(s)) return Holds.EMPTY;
            if (keep == null || !keep.test(s)) return Holds.OTHER;
            return i == held ? Holds.HELD : Holds.KEPT;
        }, roundRobin, nextUse);
        for (int k = 0; k < targets.length; k++) {
            if (targets[k] == t) roundRobin = k + 1;
        }
        return t;
    }

    /**
     * Pure choice of the hotbar index a stack moves into: the first empty target, else the first holding something
     * not kept, else a kept one (round robin from target position {@code start}), the selected slot last.
     */
    static int chooseTarget(int[] targets, IntFunction<Holds> holds, int start) {
        return chooseTarget(targets, holds, start, null);
    }

    /**
     * {@link #chooseTarget(int[], IntFunction, int)}; with {@code nextUse} (target index → when its stack is needed
     * next, larger = later) a kept stack is chosen by Belady's rule: the one needed last (ties in round robin order),
     * the selected slot still last.
     */
    static int chooseTarget(int[] targets, IntFunction<Holds> holds, int start, @Nullable IntUnaryOperator nextUse) {
        for (Holds want : List.of(Holds.EMPTY, Holds.OTHER)) {
            for (int t : targets) if (holds.apply(t) == want) return t;
        }
        if (nextUse != null) {
            int best = -1;
            int far = Integer.MIN_VALUE;
            for (int k = 0; k < targets.length; k++) {
                int t = targets[Math.floorMod(start + k, targets.length)];
                if (holds.apply(t) != Holds.KEPT) continue;
                int u = nextUse.applyAsInt(t);
                if (best < 0 || u > far) {
                    best = t;
                    far = u;
                }
            }
            if (best >= 0) return best;
        }
        for (Holds want : List.of(Holds.KEPT, Holds.HELD)) {
            for (int k = 0; k < targets.length; k++) {
                int t = targets[Math.floorMod(start + k, targets.length)];
                if (holds.apply(t) == want) return t;
            }
        }
        return targets[Math.floorMod(start, targets.length)];
    }

    /** Submits arbitrary inventory actions as this task's single outstanding request. */
    @Nullable RequestFuture submit(List<InventoryAction> actions, long now) {
        if (busy(now) || !clicker.stillLastTick(now)) return null;
        RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(owner)
            .priority(Clicker.PRIORITY)
            .actions(actions)
            .build());
        if (f == RequestFuture.rejected) return null;
        pending = f;
        pendingSince = now;
        return f;
    }
}
