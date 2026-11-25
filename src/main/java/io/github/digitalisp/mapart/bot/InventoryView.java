package io.github.digitalisp.mapart.bot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only view of the bot's own inventory (ZenithProxy inventory cache). Item ids are namespaced,
 * e.g. "minecraft:white_carpet". CONTRACT (lead-owned); {@link #tools()} was added for the tool-wear rule.
 *
 * <p>The account is the owner's own: the bot works with the 36 hotbar + main slots only. The offhand (a totem, kept there by
 * AutoTotem) and the armour are never the bot's, so counts, contents, food, tools and maps leave them out
 * ({@link #offhand()} reports the offhand for the "keeping" log; {@code bot.impl.OffhandGuard} has the rule).
 */
public interface InventoryView {
    /** Total count across the hotbar and main inventory (not the offhand, not armour, not the cursor). */
    int count(String itemId);

    /** item id → count over hotbar + main. */
    Map<String, Integer> contents();

    /** Empty slots among the 36 hotbar + main slots. */
    int freeSlots();

    /** The offhand stack (item id → count; empty map when empty). Never moved by the bot. Additive. */
    default Map<String, Integer> offhand() {
        return Map.of();
    }

    /**
     * item id → hotbar + main slots it occupies (a split stack counts each slot). Additive: the default assumes full
     * stacks of 64.
     */
    default Map<String, Integer> slotsUsed() {
        Map<String, Integer> m = new java.util.TreeMap<>();
        contents().forEach((id, n) -> m.put(id, Math.max(1, (n + 63) / 64)));
        return m;
    }

    int xpLevel();

    /** Map ids of filled_map stacks in hotbar + main, in slot order. */
    List<Integer> filledMapIds();

    /** Custom (anvil) name of the filled map {@code mapId} in hotbar/main; empty if unnamed or absent. */
    Optional<String> mapName(int mapId);

    /** Item id in the feet armor slot, or null. */
    String bootsItem();

    /** Remaining durability fraction 0..1 of the equipped boots, or 1 if none/unbreakable. */
    double bootsDurability();

    /** True if any hotbar/main item matches a tool for breaking full blocks (pickaxe/axe/shovel). */
    boolean hasTools();

    /**
     * One carried block-breaking tool (see {@link #toolKind}) with its durability. {@code remaining} = uses left
     * (max − damage); an unbreakable tool reports {@code remaining == max}.
     */
    record Tool(String itemId, int remaining, int max, boolean mending) {
        /** Remaining durability as a fraction of the maximum, 0..1 (1 when the maximum is unknown). */
        public double fraction() {
            return max <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (double) remaining / max));
        }

        public String kind() {
            return toolKind(itemId);
        }
    }

    /**
     * Kind of a block-breaking tool: "pickaxe", "axe", "shovel", "hoe" or "shears"; null for anything else
     * (swords and other damageable items are not used to break blocks).
     */
    static String toolKind(String itemId) {
        if (itemId == null) return null;
        String p = itemId.substring(itemId.indexOf(':') + 1);
        if (p.endsWith("_pickaxe")) return "pickaxe";
        if (p.endsWith("_axe")) return "axe";
        if (p.endsWith("_shovel")) return "shovel";
        if (p.endsWith("_hoe")) return "hoe";
        if (p.equals("shears")) return "shears";
        return null;
    }

    /**
     * Every carried block-breaking tool (hotbar, main inventory; not the offhand or armour), in slot order, with its
     * remaining uses and whether it has Mending. Additive: the default reports none.
     */
    default List<Tool> tools() {
        return List.of();
    }

    /**
     * One damageable armour piece: worn ({@code slot} 5 head, 6 chest, 7 legs, 8 feet) or carried (player slot
     * 9..44). {@code remaining} = uses left (max − damage); an unbreakable piece is not reported.
     */
    record Armor(int slot, String itemId, int remaining, int max, boolean mending) {
        public static final int HEAD = 5;
        public static final int CHEST = 6;
        public static final int LEGS = 7;
        public static final int FEET = 8;

        /** Remaining durability as a fraction of the maximum, 0..1 (1 when the maximum is unknown). */
        public double fraction() {
            return max <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, (double) remaining / max));
        }

        /** Worn in an armour slot (else carried in the hotbar or main inventory). */
        public boolean worn() {
            return slot >= HEAD && slot <= FEET;
        }

        /** "diamond_boots" (the id without its namespace). */
        public String name() {
            return itemId.substring(itemId.indexOf(':') + 1);
        }
    }

    /**
     * The damageable armour pieces, worn ones (slot order 5..8) first, then the carried ones (hotbar and main
     * inventory) in slot order. Additive (the durability guard): the default reports the worn boots only, from
     * {@link #bootsItem} and {@link #bootsDurability}, as 1000 uses with Mending.
     */
    default List<Armor> armor() {
        String boots = bootsItem();
        if (boots == null) return List.of();
        return List.of(new Armor(Armor.FEET, boots, (int) Math.round(bootsDurability() * 1000), 1000, true));
    }

    /**
     * Firework rockets an elytra leg flies with ({@code build.elytraLegs}): plain, flight duration 1 to 3, in the hotbar and
     * main inventory. Additive: the default counts every firework rocket.
     */
    default int flightRockets() {
        return count("minecraft:firework_rocket");
    }

    /** Food items count (anything AutoEat would eat). */
    int foodCount();

    /** Hunger bar, 0..20 (20 = full). */
    int foodLevel();

    /** Health points, 20 = full (absorption not included). */
    float health();
}
