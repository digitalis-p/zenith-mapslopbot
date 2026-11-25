package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.inventory.Container;
import com.zenith.cache.data.inventory.InventoryCache;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.ComponentSerializer;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.zenith.Globals.CACHE;

/**
 * Inventory cache access. While a container is open ZenithProxy updates the open container's copy of the
 * player inventory (container 0 is only refreshed on close), so player slots are read from the open container's
 * bottom section when one is open. Slot numbers here are player-inventory numbers (9..44, 45 offhand) unless
 * stated otherwise. Finding and counting cover the 36 hotbar + main slots only: the offhand and the armour are never
 * the bot's ({@link OffhandGuard}). Client tick thread only.
 */
final class Inv {
    private Inv() { }

    static InventoryCache cache() {
        return CACHE.getPlayerCache().getInventoryCache();
    }

    static int openContainerId() {
        return cache().getOpenContainerId();
    }

    /** True if a non-player container is open (or, with ncpStrict semantics, the player inventory is "active"). */
    static boolean containerOpen() {
        return cache().getOpenContainerId() != 0;
    }

    /** The open non-player container once its contents arrived, else null. */
    static @Nullable Container openContainer() {
        int id = cache().getOpenContainerId();
        if (id == 0) return null;
        Container c = cache().getOpenContainer();
        if (c == null || c.getContainerId() != id || c.getSize() <= 36) return null;
        return c;
    }

    /** Own slots of the open container (size - 36), or 0 when only the player inventory is open. */
    static int topSlots() {
        Container c = openContainer();
        return c == null ? 0 : c.getSize() - 36;
    }

    static @Nullable ItemStack stack(int playerSlot) {
        Container open = openContainer();
        if (open != null && playerSlot >= ItemIds.MAIN_START && playerSlot <= ItemIds.HOTBAR_END) {
            return open.getItemStack(ItemIds.toContainerSlot(playerSlot, open.getSize() - 36));
        }
        return cache().getPlayerInventory().getItemStack(playerSlot);
    }

    /** The stack on the cursor (ZenithProxy's cache, from the clicks sent and the server's updates), or null. */
    static @Nullable ItemStack cursor() {
        return cache().getMouseStack();
    }

    static @Nullable ItemStack mainHand() {
        return stack(ItemIds.HOTBAR_START + CACHE.getPlayerCache().getHeldItemSlot());
    }

    static int heldSlot() {
        return CACHE.getPlayerCache().getHeldItemSlot();
    }

    static boolean empty(@Nullable ItemStack s) {
        return s == Container.EMPTY_STACK || s.getAmount() <= 0;
    }

    /**
     * Material id of a stack ({@link ItemIds#material}): a closed_eyeblossom stack reads as open_eyeblossom, so
     * counts, hotbar search, withdraw/deposit/drop plans and stock scans treat both as one material.
     */
    static @Nullable String id(@Nullable ItemStack s) {
        if (empty(s)) return null;
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        return d == null ? null : ItemIds.material("minecraft:" + d.name());
    }

    static int maxStack(@Nullable ItemStack s) {
        if (empty(s)) return 64;
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        return d == null ? 64 : Math.max(1, d.stackSize());
    }

    static Predicate<ItemStack> is(String itemId) {
        String want = ItemIds.material(itemId);
        return s -> want.equals(id(s));
    }

    /** The firework rocket id ({@code build.elytraLegs}). */
    static final String ROCKET_ID = "minecraft:firework_rocket";

    /** The longest flight duration the elytra legs fly with. */
    static final int MAX_FLIGHT_DURATION = 3;

    /**
     * A firework rocket's flight duration when the elytra legs fly with it ({@code build.elytraLegs}), else 0: no
     * firework star (its explosion at the end of the boost would hurt the bot) and flight duration 1 to
     * {@link #MAX_FLIGHT_DURATION} (a boost of 10 * (1 + d) to 10 * (1 + d) + 11 ticks, which the flight controller
     * plans with: {@code FlightControl.Cfg#forDuration}). A stack without the component patch has the item's default
     * (duration 1, no explosions).
     */
    static int flightDuration(@Nullable ItemStack s) {
        if (!ROCKET_ID.equals(id(s))) return 0;
        var fw = s.getDataComponentsOrEmpty().get(DataComponentTypes.FIREWORKS);
        if (fw == null) {
            ItemData d = ItemRegistry.REGISTRY.get(s.getId());
            if (d != null && d.components() != null) fw = d.components().get(DataComponentTypes.FIREWORKS);
        }
        if (fw == null) return 1;
        if (fw.getExplosions() != null && !fw.getExplosions().isEmpty()) return 0;
        int d = fw.getFlightDuration();
        return d >= 1 && d <= MAX_FLIGHT_DURATION ? d : 0;
    }

    /** A rocket the elytra legs fly with ({@link #flightDuration} 1 to 3). */
    static final Predicate<ItemStack> FLIGHT_ROCKET = s -> flightDuration(s) > 0;

    /** A flight rocket of duration {@code d} exactly. */
    static Predicate<ItemStack> flightRocket(int d) {
        return s -> flightDuration(s) == d;
    }

    static @Nullable Integer mapId(@Nullable ItemStack s) {
        if (empty(s) || s.getId() != ItemRegistry.FILLED_MAP.id()) return null;
        return s.getDataComponentsOrEmpty().get(DataComponentTypes.MAP_ID);
    }

    static Predicate<ItemStack> isMap(int mapId) {
        return s -> {
            Integer id = mapId(s);
            return id != null && id == mapId;
        };
    }

    static @Nullable String customName(@Nullable ItemStack s) {
        if (empty(s)) return null;
        Component c = s.getDataComponentsOrEmpty().get(DataComponentTypes.CUSTOM_NAME);
        return c == null ? null : ComponentSerializer.serializePlain(c);
    }

    /**
     * First player slot holding a matching stack: hotbar, then main inventory; -1 if none. Never the offhand or the
     * armour ({@link OffhandGuard}): a slot the bot finds is one it may move.
     */
    static int find(Predicate<ItemStack> pred) {
        for (int i = ItemIds.HOTBAR_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = stack(i);
            if (!empty(s) && pred.test(s)) return i;
        }
        for (int i = ItemIds.MAIN_START; i < ItemIds.HOTBAR_START; i++) {
            ItemStack s = stack(i);
            if (!empty(s) && pred.test(s)) return i;
        }
        return -1;
    }

    /** Total count over the hotbar and main inventory (not the offhand: {@link OffhandGuard}). */
    static int count(Predicate<ItemStack> pred) {
        int n = 0;
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = stack(i);
            if (!empty(s) && pred.test(s)) n += s.getAmount();
        }
        return n;
    }

    static int freeSlots() {
        int n = 0;
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) if (empty(stack(i))) n++;
        return n;
    }

    /** Occupied hotbar+main slots as planner stacks, slot numbers in the open container's numbering. */
    static List<TransferPlanner.Stack> playerStacks(int top) {
        return playerStacks(top, s -> false);
    }

    /** {@link #playerStacks(int)} without the stacks {@code skip} accepts (they are never moved). */
    static List<TransferPlanner.Stack> playerStacks(int top, Predicate<ItemStack> skip) {
        List<TransferPlanner.Stack> out = new ArrayList<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = stack(i);
            String id = id(s);
            if (id == null || skip.test(s)) continue;
            out.add(new TransferPlanner.Stack(ItemIds.toContainerSlot(i, top), id, s.getAmount(), maxStack(s)));
        }
        return out;
    }

    /** The stack carries an enchantment (an empty or missing stack: false). */
    static boolean enchanted(@Nullable ItemStack s) {
        if (empty(s)) return false;
        var ench = s.getDataComponentsOrEmpty().get(DataComponentTypes.ENCHANTMENTS);
        return ench != null && ench.getEnchantments() != null && !ench.getEnchantments().isEmpty();
    }

    /** All filled-map ids over main inventory and hotbar, in slot order (a map in the offhand is not the bot's). */
    static List<Integer> mapIds() {
        List<Integer> out = new ArrayList<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            Integer id = mapId(stack(i));
            if (id != null) out.add(id);
        }
        return out;
    }
}
