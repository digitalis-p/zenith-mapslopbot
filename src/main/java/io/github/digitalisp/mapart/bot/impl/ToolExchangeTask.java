package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.mc.block.Block;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.TransferResult;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.CACHE;

/**
 * At a tool chest: unless a tool of one kind above {@code floor} uses is carried, shift-clicks the best one above the
 * floor out of the chest (Mending first, then most uses left; worn tools in the chest are never taken), and puts every
 * carried tool of that kind at or below the floor into the chest, but only when one above the floor is carried or was
 * just taken, and only a tool whose id is {@code returnable} (it came from or belongs in the tool chest; any other worn
 * tool is the owner's and stays). The offhand and armour are not visible in a chest menu and are left alone
 * ({@link OffhandGuard}). {@code moved} reports the tool taken.
 *
 * <p>Kind {@link #ELYTRA} ({@code build.elytraLegs}, the rocket chest): the same
 * for elytras, with an elytra worn in the chest slot counted as carried (the bot never carries two), and the best one
 * is the one with Mending, then the highest Unbreaking, then the most uses left.
 */
final class ToolExchangeTask extends ContainerTask {
    /** The kind that exchanges elytras ({@code build.elytraLegs}). */
    static final String ELYTRA = "elytra";
    private static final java.util.function.Predicate<ItemStack> IS_ELYTRA = Inv.is("minecraft:elytra");
    private final String kind;
    private final int floor;
    private final Predicate<String> returnable;
    private final Consumer<TransferResult> result;
    private final List<String> notes = new ArrayList<>();
    private @Nullable String takenId;
    private int deposited;
    private int usableBefore;

    ToolExchangeTask(ZenithBotActions ctx, Pos pos, String kind, int floor, Predicate<String> returnable,
                     Consumer<TransferResult> result) {
        super(ctx, pos, ctx.containerTimeout(pos));
        this.kind = kind;
        this.floor = Math.max(0, floor);
        this.returnable = returnable;
        this.result = result;
    }

    @Override
    public String name() {
        return "exchangeTool " + kind + " " + pos;
    }

    @Override
    protected @Nullable String checkBlock() {
        Block b = BLOCK_DATA.getBlockDataFromBlockStateId(ctx.world().stateId(pos));
        if (b == null || b.isAir()) return "no container at " + pos;
        if (b.blockEntityType() == null) return "block at " + pos + " is " + b.name() + ", not a container";
        return null;
    }

    @Override
    protected @Nullable String checkContainer(Container c) {
        return isStorage(c.getType()) ? null : "block at " + pos + " opened a " + typeName(c.getType()) + " menu";
    }

    @Override
    protected void onOpened(Container c) {
        usableBefore = usableCarried();
    }

    private boolean isKind(@Nullable ItemStack s) {
        if (Inv.empty(s)) return false;
        return ELYTRA.equals(kind) ? IS_ELYTRA.test(s) : kind.equals(Wear.kind(s));
    }

    private boolean worn(@Nullable ItemStack s) {
        return isKind(s) && Wear.damageable(s) && Wear.remaining(s) <= floor;
    }

    /** Tools of the kind above the floor in hotbar + main inventory (an offhand tool is not the bot's). */
    private int usableCarried() {
        int n = 0;
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (isKind(s) && !worn(s)) n++;
        }
        // an elytra in the chest slot is carried whatever its uses: never a second one (its swap back needs the slot)
        if (ELYTRA.equals(kind) && isKind(CACHE.getPlayerCache().getInventoryCache().getPlayerInventory()
            .getItemStack(InventoryView.Armor.CHEST))) n++;
        return n;
    }

    @Override
    protected List<InventoryAction> actions(Container c, int top) {
        int cid = c.getContainerId();
        int chestFree = before == null ? 0 : before.freeSlots();
        int playerFree = Inv.freeSlots();
        List<Integer> worn = new ArrayList<>();
        int usable = 0;
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (!isKind(s)) continue;
            if (!worn(s)) usable++;
            else if (returnable.test(Inv.id(s))) worn.add(i);
            else notes.add("worn " + Inv.id(s) + " kept: not a tool-chest tool");
        }
        if (worn(Inv.stack(ItemIds.OFFHAND))) notes.add("worn " + kind + " in the offhand left there");
        int best = -1;
        ItemStack bestStack = null;
        if (usable == 0 && usableBefore == 0) {
            for (int i = 0; i < c.getSize() - 36; i++) {
                ItemStack s = c.getItemStack(i);
                if (!isKind(s) || worn(s)) continue;
                if (bestStack == null || (ELYTRA.equals(kind) ? betterElytra(s, bestStack) : better(s, bestStack))) {
                    best = i;
                    bestStack = s;
                }
            }
            if (best < 0) notes.add("no " + kind + " above " + floor + " uses in the chest");
        }
        List<InventoryAction> out = new ArrayList<>();
        // a worn tool goes back only when one of its kind above the floor is carried or taken now: without any, the
        // bot keeps it for a mend later and the engine stops instead of breaking with it
        if (usable > 0 || best >= 0) {
            for (int slot : worn) {
                if (chestFree <= 0) {
                    notes.add("chest full: worn " + Inv.id(Inv.stack(slot)) + " kept");
                    continue;
                }
                out.add(new ShiftClick(cid, ItemIds.toContainerSlot(slot, top), ShiftClickItemAction.LEFT_CLICK));
                chestFree--;
                playerFree++;
                deposited++;
            }
        }
        if (best >= 0) {
            if (playerFree <= 0) {
                notes.add("inventory full: could not take a " + kind);
            } else {
                out.add(new ShiftClick(cid, best, ShiftClickItemAction.LEFT_CLICK));
                takenId = Inv.id(bestStack);
            }
        }
        return out;
    }

    /** Mending first, then more uses left. */
    private static boolean better(ItemStack a, ItemStack b) {
        boolean ma = Wear.mending(a), mb = Wear.mending(b);
        if (ma != mb) return ma;
        return Wear.remaining(a) > Wear.remaining(b);
    }

    /** Mending first, then the higher Unbreaking, then more uses left. */
    private static boolean betterElytra(ItemStack a, ItemStack b) {
        boolean ma = Wear.mending(a), mb = Wear.mending(b);
        if (ma != mb) return ma;
        int ua = Wear.unbreaking(a), ub = Wear.unbreaking(b);
        if (ua != ub) return ua > ub;
        return Wear.remaining(a) > Wear.remaining(b);
    }

    @Override
    protected TaskStatus finish() {
        Map<String, Integer> moved = new TreeMap<>();
        int usableAfter = usableCarried();
        if (takenId != null && usableAfter > usableBefore) moved.put(takenId, usableAfter - usableBefore);
        List<String> problems = new ArrayList<>();
        if (problem != null) problems.add(problem);
        problems.addAll(notes);
        if (deposited > 0) LOG.info("returned {} worn {} to {}", deposited, kind, pos);
        result.accept(new TransferResult(moved, after != null ? after : before, problems.isEmpty() ? null : String.join("; ", problems)));
        return TaskStatus.SUCCESS;
    }
}
