package io.github.digitalisp.mapart.bot.impl;

import com.zenith.mc.food.FoodData;
import com.zenith.mc.food.FoodRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ToolType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import io.github.digitalisp.mapart.bot.InventoryView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;

/**
 * {@link InventoryView} over ZenithProxy's inventory cache: the 36 hotbar + main slots; the offhand only through
 * {@link #offhand()} ({@link OffhandGuard}). Client tick thread.
 */
public final class ZenithInventoryView implements InventoryView {
    @Override
    public int count(String itemId) {
        return Inv.count(Inv.is(itemId));
    }

    @Override
    public Map<String, Integer> contents() {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            String id = Inv.id(s);
            if (id != null) m.merge(id, s.getAmount(), Integer::sum);
        }
        return m;
    }

    @Override
    public Map<String, Integer> offhand() {
        ItemStack s = Inv.stack(ItemIds.OFFHAND);
        String id = Inv.id(s);
        return id == null ? Map.of() : Map.of(id, s.getAmount());
    }

    @Override
    public Map<String, Integer> slotsUsed() {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            String id = Inv.id(Inv.stack(i));
            if (id != null) m.merge(id, 1, Integer::sum);
        }
        return m;
    }

    @Override
    public int freeSlots() {
        return Inv.freeSlots();
    }

    @Override
    public int xpLevel() {
        return CACHE.getPlayerCache().getThePlayer().getLevel();
    }

    @Override
    public List<Integer> filledMapIds() {
        return Inv.mapIds();
    }

    @Override
    public Optional<String> mapName(int mapId) {
        int slot = Inv.find(Inv.isMap(mapId));
        if (slot < 0) return Optional.empty();
        String name = Inv.customName(Inv.stack(slot));
        return name == null || name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    @Override
    public String bootsItem() {
        return Inv.id(CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getItemStack(8));
    }

    @Override
    public double bootsDurability() {
        ItemStack s = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getItemStack(8);
        return durability(s);
    }

    /** Remaining durability fraction 0..1; 1 for empty, unbreakable or undamageable stacks. */
    static double durability(ItemStack s) {
        if (Inv.empty(s)) return 1.0;
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        DataComponents own = s.getDataComponentsOrEmpty();
        if (own.contains(DataComponentTypes.UNBREAKABLE)) return 1.0;
        Integer max = own.get(DataComponentTypes.MAX_DAMAGE);
        if (max == null && d != null) max = d.components().get(DataComponentTypes.MAX_DAMAGE);
        if (max == null || max <= 0) return 1.0;
        Integer damage = own.get(DataComponentTypes.DAMAGE);
        int dmg = damage == null ? 0 : damage;
        return Math.max(0.0, Math.min(1.0, 1.0 - (double) dmg / max));
    }

    @Override
    public boolean hasTools() {
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (Inv.empty(s)) continue;
            ItemData d = ItemRegistry.REGISTRY.get(s.getId());
            if (d == null || d.toolTag() == null) continue;
            ToolType t = d.toolTag().type();
            if (t == ToolType.PICKAXE || t == ToolType.AXE || t == ToolType.SHOVEL) return true;
        }
        return false;
    }

    @Override
    public List<Tool> tools() {
        List<Tool> out = new ArrayList<>();
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (Inv.empty(s) || Wear.kind(s) == null) continue;
            out.add(Wear.tool(s));
        }
        return out;
    }

    @Override
    public List<Armor> armor() {
        List<Armor> out = new ArrayList<>();
        var player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        for (int i = Armor.HEAD; i <= Armor.FEET; i++) addArmor(out, i, player.getItemStack(i));
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) addArmor(out, i, Inv.stack(i));
        return out;
    }

    /** A damageable armour item ({@link ItemIds#armorSlot}; heads and pumpkins take no damage) in {@code slot}. */
    private static void addArmor(List<Armor> out, int slot, ItemStack s) {
        if (Inv.empty(s) || !Wear.damageable(s)) return;
        String id = Inv.id(s);
        if (id == null || ItemIds.armorSlot(id) < 0) return;
        out.add(new Armor(slot, id, Wear.remaining(s), Wear.maxUses(s), Wear.mending(s)));
    }

    @Override
    public int flightRockets() {
        return Inv.count(Inv.FLIGHT_ROCKET);
    }

    @Override
    public int foodCount() {
        int n = 0;
        for (int i = ItemIds.MAIN_START; i <= ItemIds.HOTBAR_END; i++) {
            ItemStack s = Inv.stack(i);
            if (!Inv.empty(s) && edible(s)) n += s.getAmount();
        }
        return n;
    }

    @Override
    public int foodLevel() {
        return CACHE.getPlayerCache().getThePlayer().getFood();
    }

    @Override
    public float health() {
        return CACHE.getPlayerCache().getThePlayer().getHealth();
    }

    /** Mirrors AutoEat's food predicate (ignoring current hunger). */
    static boolean edible(ItemStack s) {
        FoodData food = FoodRegistry.REGISTRY.get(s.getId());
        if (food == null) return false;
        var cfg = CONFIG.client.extra.autoEat;
        switch (cfg.mode) {
            case BLACKLIST -> {
                if (cfg.foods.contains(food.name())) return false;
            }
            case WHITELIST -> {
                if (!cfg.foods.contains(food.name())) return false;
            }
            default -> { }
        }
        return cfg.allowUnsafeFood || food.isSafeFood();
    }

    /** True if the stack is a food at all (the engine's own food list decides which ones it eats). */
    static boolean isFood(ItemStack s) {
        return !Inv.empty(s) && FoodRegistry.REGISTRY.get(s.getId()) != null;
    }
}
