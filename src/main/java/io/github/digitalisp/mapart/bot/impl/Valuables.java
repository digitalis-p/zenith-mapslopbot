package io.github.digitalisp.mapart.bot.impl;

import java.util.Set;

/**
 * Items the bot never deposits or drops, whatever the caller asks: a backstop under the engine's rule that only
 * materials leave the inventory (a material-region chest that once held totems must not turn totems into "materials").
 * The bot's own maps, panes and XP bottles are listed too: they never go with materials and are never dropped. They
 * leave the inventory only into their own chests, each by its own transfer: the locked map into the output chest and a
 * stale map of the tile into the scrap map chest ({@code depositMap}), empty maps, panes and XP bottles into the map,
 * pane and XP chests ({@code returnWorkingItems}). Pure.
 */
public final class Valuables {
    private Valuables() { }

    /**
     * Never map-art materials (MapArtCraft blocks such as diamond or netherite blocks are not listed: a job may use
     * them, and their leftovers go back like any other material).
     */
    private static final Set<String> IDS = Set.of(
        "totem_of_undying", "elytra", "enchanted_golden_apple", "golden_apple", "enchanted_book", "nether_star",
        "beacon", "conduit", "heart_of_the_sea", "dragon_egg", "dragon_head", "player_head", "trident", "mace",
        "shield", "bow", "crossbow", "shears", "shulker_box", "filled_map", "map", "writable_book", "written_book",
        "name_tag", "experience_bottle", "firework_rocket", "end_crystal", "respawn_anchor");

    private static final String[] SUFFIXES = {"_shulker_box", "_sword", "_helmet", "_chestplate", "_leggings", "_boots",
        "_pickaxe", "_axe", "_shovel", "_hoe", "_spawn_egg", "_upgrade_smithing_template", "_armor_trim_smithing_template"};

    /**
     * Valuables that are junk when the owner lists them in {@code build.junkItems} and the stack has no enchantment: the
     * pillagers' crossbows and the skeletons' bows a fight leaves (live 2026-10-15, the owner: "crossbows are trash
     * too"). An enchanted one is never dropped ({@code DropItemsTask}).
     */
    private static final Set<String> JUNK_WHEN_PLAIN = Set.of("bow", "crossbow");

    /** {@code itemId} is one of {@link #JUNK_WHEN_PLAIN}. */
    public static boolean junkWhenPlain(String itemId) {
        String p = ItemIds.path(itemId);
        return p != null && JUNK_WHEN_PLAIN.contains(p);
    }

    /**
     * Of {@link #JUNK_WHEN_PLAIN}, the ones an enchantment keeps: the bow. A crossbow listed as junk goes even enchanted:
     * the pillagers' crossbows often carry an enchantment (live 2026-10-16: one was thrown at the junk spot twice and
     * kept twice).
     */
    public static boolean keptWhenEnchanted(String itemId) {
        String p = ItemIds.path(itemId);
        return "bow".equals(p);
    }

    /** True if {@code itemId} (namespaced or not) must never be deposited or dropped. */
    public static boolean isValuable(String itemId) {
        String p = ItemIds.path(itemId);
        if (p == null) return false;
        if (IDS.contains(p)) return true;
        for (String s : SUFFIXES) if (p.endsWith(s)) return true;
        return false;
    }
}
