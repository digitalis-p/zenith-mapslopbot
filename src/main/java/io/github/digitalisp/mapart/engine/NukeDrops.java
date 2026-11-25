package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.impl.ItemIds;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What breaking an art block drops when that is not the block itself ({@code build.junkNukeDrops}):
 * the NUKE breaks with the fastest tool above the floor, mostly without silk touch, so
 * clay comes back as four clay balls, a decorated pot as four bricks, glowstone as dust. No lane places these items,
 * no material chest takes them, and {@link Leftovers} counted them as the owner's: they stayed in the inventory for good
 * (live 2026-10-21 23:42 to 2026-10-23 06:46: clay balls of the j0014 nukes grew to 404 in 7 slots, every later load
 * planned around them, "Few free slots" alert ignored for 7 hours).
 *
 * <p>Only items no art places (the block's own item, a stone's cobblestone or a grass block's dirt are materials of
 * their own) and only the drops of art blocks. Pure; unit-tested.
 */
final class NukeDrops {
    private NukeDrops() { }

    /** Block path → the item paths breaking it without silk touch may drop instead of the block. */
    private static final Map<String, List<String>> DROPS;
    /** Item path → the block paths it comes from. */
    private static final Map<String, Set<String>> SOURCES;

    static {
        Map<String, List<String>> d = new HashMap<>();
        d.put("clay", List.of("clay_ball"));
        d.put("decorated_pot", List.of("brick"));
        d.put("glowstone", List.of("glowstone_dust"));
        d.put("melon", List.of("melon_slice"));
        d.put("bookshelf", List.of("book"));
        d.put("snow_block", List.of("snowball"));
        d.put("snow", List.of("snowball"));
        d.put("sea_lantern", List.of("prismarine_crystals"));
        d.put("gravel", List.of("flint"));
        d.put("cobweb", List.of("string"));
        d.put("dead_bush", List.of("stick"));
        d.put("short_grass", List.of("wheat_seeds"));
        d.put("tall_grass", List.of("wheat_seeds"));
        d.put("fern", List.of("wheat_seeds"));
        d.put("large_fern", List.of("wheat_seeds"));
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "pale_oak"))
            d.put(wood + "_leaves", wood.equals("oak") || wood.equals("dark_oak")
                ? List.of(wood + "_sapling", "stick", "apple") : List.of(wood + "_sapling", "stick"));
        d.put("mangrove_leaves", List.of("stick"));
        d.put("azalea_leaves", List.of("azalea", "stick"));
        d.put("flowering_azalea_leaves", List.of("flowering_azalea", "stick"));
        DROPS = Map.copyOf(d);
        Map<String, Set<String>> s = new HashMap<>();
        d.forEach((block, items) -> {
            for (String item : items) s.computeIfAbsent(item, k -> new HashSet<>()).add(block);
        });
        Map<String, Set<String>> frozen = new HashMap<>();
        s.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        SOURCES = Map.copyOf(frozen);
    }

    /** Item ids ("minecraft:clay_ball") breaking {@code blockId} without silk touch may drop instead of the block. */
    static Set<String> byProducts(String blockId) {
        String p = ItemIds.path(blockId);
        List<String> items = p == null ? null : DROPS.get(p);
        if (items == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String i : items) out.add(ItemIds.ns(i));
        return out;
    }

    /** Block ids whose break drops {@code itemId} instead of the block; empty for every other item. */
    static Set<String> sources(String itemId) {
        String p = ItemIds.path(itemId);
        Set<String> blocks = p == null ? null : SOURCES.get(p);
        if (blocks == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String b : blocks) out.add(ItemIds.ns(b));
        return out;
    }

    /**
     * {@code itemId} is what breaking an art block of this base gives back: one of the blocks it comes from is a
     * material ({@code material}, given the block's item id, e.g. "minecraft:clay").
     */
    static boolean fromMaterial(String itemId, Predicate<String> material) {
        for (String block : sources(itemId)) if (material.test(block)) return true;
        return false;
    }
}
