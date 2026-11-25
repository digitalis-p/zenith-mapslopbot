package io.github.digitalisp.mapart.bot.impl;

import com.zenith.util.RequestFuture;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;

import static com.zenith.Globals.CACHE;

/**
 * Equips an armour item (boots for the soul sand platform) into the EMPTY armour slot. With
 * {@code build.swapWithoutCursor} (default) by hotbar-key trades ({@link KeySwaps#wear}): from the hotbar one click,
 * from the main inventory two through an empty hotbar slot or three through a borrowed one; nothing is ever on the
 * cursor, so a disconnect mid-swap loses nothing (the server drops a cursor stack when the player leaves). Off: three
 * plain clicks, pick up, the armour slot, the source slot again. The armour is the owner's ({@link OffhandGuard}): a slot
 * that already holds something is never clicked, the task fails instead.
 */
final class EquipTask extends ZenithTask {
    private static final int MAX_ATTEMPTS = 2;

    private final String itemId;
    private final int armorSlot;
    /** The player slot to take the piece from (the durability guard picks one of two same-id pieces), or -1: any. */
    private final int fromSlot;
    private @Nullable RequestFuture request;
    private long settleUntil = -1;
    private int attempts;

    EquipTask(ZenithBotActions ctx, String itemId) {
        this(ctx, itemId, -1);
    }

    EquipTask(ZenithBotActions ctx, String itemId, int fromSlot) {
        super(ctx, 600);
        this.itemId = ItemIds.ns(itemId);
        this.armorSlot = ItemIds.armorSlot(this.itemId);
        this.fromSlot = fromSlot;
    }

    @Override
    public String name() {
        return "equip " + itemId;
    }

    @Override
    protected TaskStatus step() {
        if (armorSlot < 0) return fail(itemId + " is not an armor item");
        holdStill();
        if (worn()) return TaskStatus.SUCCESS;
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
            if (++attempts >= MAX_ATTEMPTS) return fail("could not equip " + itemId);
        }
        int src = fromSlot >= 0 ? (Inv.is(itemId).test(Inv.stack(fromSlot)) ? fromSlot : -1) : Inv.find(Inv.is(itemId));
        if (src < 0) return fail("no " + itemId + " in inventory");
        if (!OffhandGuard.movable(src)) return fail(itemId + " is not in the hotbar or main inventory");
        String worn = Inv.id(CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getItemStack(armorSlot));
        if (worn != null) return fail("armor slot " + armorSlot + " holds " + worn + "; never moved out");
        request = hotbar.submit(clickActions(0, KeySwaps.wear(cfg.build.swapWithoutCursor, src, armorSlot,
            ZenithTask::hotbarEmpty, Inv.heldSlot())), now);
        return TaskStatus.RUNNING;
    }

    private boolean worn() {
        var s = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getItemStack(armorSlot);
        return itemId.equals(Inv.id(s));
    }
}
