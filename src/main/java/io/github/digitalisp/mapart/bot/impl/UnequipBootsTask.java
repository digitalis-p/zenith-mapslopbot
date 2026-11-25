package io.github.digitalisp.mapart.bot.impl;

import com.zenith.util.RequestFuture;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.TaskStatus;

import static com.zenith.Globals.CACHE;

/**
 * Takes a worn armour piece off into an empty hotbar or main inventory slot, so it never breaks: the boots (live
 * 2026-10-14: netherite boots wore out over an 8.5-hour stock wait full of storage sweeps on soul soil, with no XP
 * bottles carried to mend them) or, for the durability guard, any armour slot (5 head .. 8 feet). With
 * {@code build.swapWithoutCursor} (default) by hotbar-key trades ({@link KeySwaps#takeOff}): into a hotbar slot one
 * click, into the main inventory two through an empty hotbar slot or three through a borrowed one; nothing is ever on
 * the cursor, so a disconnect mid-swap loses nothing. Off: two plain clicks, pick up from the armour slot, put down
 * into the empty slot. Fails when no slot is free ({@link BotActions#NO_FREE_SLOT}) or the armour slot holds nothing.
 */
final class UnequipBootsTask extends ZenithTask {
    private static final int MAX_ATTEMPTS = 2;

    private final int armorSlot;
    private @Nullable RequestFuture request;
    private long settleUntil = -1;
    private int attempts;

    UnequipBootsTask(ZenithBotActions ctx) {
        this(ctx, InventoryView.Armor.FEET);
    }

    UnequipBootsTask(ZenithBotActions ctx, int armorSlot) {
        super(ctx, 600);
        this.armorSlot = armorSlot;
    }

    @Override
    public String name() {
        return armorSlot == InventoryView.Armor.FEET ? "take off the boots" : "take off armor slot " + armorSlot;
    }

    @Override
    protected TaskStatus step() {
        if (armorSlot < InventoryView.Armor.HEAD || armorSlot > InventoryView.Armor.FEET) {
            return fail("slot " + armorSlot + " is no armor slot");
        }
        holdStill();
        if (slotEmpty()) return TaskStatus.SUCCESS;
        if (Inv.containerOpen()) {
            closeContainerNow();
            return TaskStatus.RUNNING;
        }
        if (request != null) {
            if (!request.isCompleted()) return TaskStatus.RUNNING;
            if (settleUntil < 0) settleUntil = now + 5;
            if (now < settleUntil) return TaskStatus.RUNNING;
            request = null;
            settleUntil = -1;
            if (++attempts >= MAX_ATTEMPTS) return fail("could not take the piece in armor slot " + armorSlot + " off");
        }
        int dest = emptySlot();
        if (dest < 0) return fail(BotActions.NO_FREE_SLOT + " for the piece in armor slot " + armorSlot);
        request = hotbar.submit(clickActions(0, KeySwaps.takeOff(cfg.build.swapWithoutCursor, armorSlot, dest,
            ZenithTask::hotbarEmpty, Inv.heldSlot())), now);
        return TaskStatus.RUNNING;
    }

    private boolean slotEmpty() {
        return Inv.empty(CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getItemStack(armorSlot));
    }

    /** First empty main inventory slot, else hotbar; -1 if none. */
    private static int emptySlot() {
        for (int i = ItemIds.MAIN_START; i < ItemIds.HOTBAR_START; i++) if (Inv.empty(Inv.stack(i))) return i;
        for (int i = ItemIds.HOTBAR_START; i <= ItemIds.HOTBAR_END; i++) if (Inv.empty(Inv.stack(i))) return i;
        return -1;
    }
}
