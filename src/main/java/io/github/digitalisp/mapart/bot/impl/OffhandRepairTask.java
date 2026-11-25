package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CustomAction;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;

import java.util.List;

import static com.zenith.Globals.INVENTORY;

/**
 * {@link OffhandRepair} on ZenithProxy: holds still, then submits one InventoryActionRequest with
 * {@code MoveToHotbarSlot(slot, OFF_HAND)} (ClickContainer in the player inventory, SWAP mode, button 40), at the task's
 * input priority, when the previous tick's request pressed no movement key ({@link Clicker#stillLastTick}).
 */
final class OffhandRepairTask extends ZenithTask {
    private final AirPlaceGuard guard = AirPlaceGuard.shared();
    private final OffhandRepair repair;
    private @Nullable RequestFuture pending;

    OffhandRepairTask(ZenithBotActions ctx) {
        super(ctx, OffhandRepair.WAIT_TICKS + OffhandRepair.ANSWER_TICKS + OffhandRepair.CONFIRM_TICKS + 40);
        this.repair = new OffhandRepair(new OffhandRepair.Port() {
            @Override
            public @Nullable String offhand() {
                return ZenithAirPlace.INSTANCE.offhand();
            }

            @Override
            public int repairSlot() {
                return guard.repairSlot(ZenithAirPlace.INSTANCE);
            }

            @Override
            public String item() {
                String e = guard.expectedItem();
                return e != null ? e : AirPlaceGuard.TOTEM;
            }

            @Override
            public boolean containerOpen() {
                return Inv.containerOpen();
            }

            @Override
            public boolean inventoryBusy() {
                return INVENTORY.hasActiveRequest() || (pending != null && !pending.isCompleted());
            }

            @Override
            public boolean stillLastTick() {
                return clicker.stillLastTick(now);
            }

            @Override
            public boolean swapIntoOffhand(int playerSlot) {
                if (!OffhandGuard.movable(playerSlot)) return false;
                RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(OffhandRepairTask.this)
                    .priority(Clicker.PRIORITY)
                    .actions(List.of(new MoveToHotbarSlot(playerSlot, MoveToHotbarAction.OFF_HAND)))
                    .build());
                if (f == RequestFuture.rejected) return false;
                pending = f;
                guard.clickedOffhand(ZenithAirPlace.INSTANCE); // no airplace until the server reports the offhand
                String off = offhand();
                LOG.info("Offhand: moving {} from inventory slot {} into the offhand (it holds {})", item(), playerSlot,
                    off == null ? "nothing" : off);
                return true;
            }

            @Override
            public boolean checkWanted() {
                return guard.checkWanted(ZenithAirPlace.INSTANCE);
            }

            @Override
            public boolean requestInventory() {
                // the hotbar recovery's no-op click (Hotbar#resyncPacket): a same-slot hotbar swap with stateId -1, which
                // the server answers with the whole inventory; not within 50 ms of a use-item-on (PacketOrder)
                if (System.nanoTime() - Clicker.lastClickNanos < 50_000_000L) return false;
                int held = Inv.heldSlot();
                RequestFuture f = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(OffhandRepairTask.this)
                    .priority(Clicker.PRIORITY)
                    .actions(List.of(new CustomAction(0, () -> Inv.containerOpen() ? null
                        : Hotbar.resyncPacket(held, Inv.cache().getMouseStack()))))
                    .build());
                if (f == RequestFuture.rejected) return false;
                pending = f;
                String off = offhand();
                LOG.info("Offhand: the offhand shows {} and the inventory cache no {}: asking the server for the full"
                    + " inventory before judging", off == null ? "nothing" : off, item());
                return true;
            }

            @Override
            public long inventoryMark() {
                return InventoryEchoes.contentReports();
            }

            @Override
            public void checked() {
                guard.serverChecked(ZenithAirPlace.INSTANCE);
                String e = guard.takeEvent();
                if (e != null) LOG.warn(e);
            }
        });
    }

    @Override
    public String name() {
        return "restore offhand";
    }

    @Override
    protected String progress() {
        return repair.clickedSlot() >= 0 ? " (clicked slot " + repair.clickedSlot() + ")"
            : repair.inventoryRequested() ? " (full inventory asked of the server)" : " (waiting for a still tick)";
    }

    @Override
    protected TaskStatus step() {
        holdStill();
        return switch (repair.tick(now)) {
            case RUNNING -> TaskStatus.RUNNING;
            case DONE -> TaskStatus.SUCCESS;
            case FAILED -> fail(String.valueOf(repair.failure()));
        };
    }
}
