package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Test catalog: knows every block used by the fixtures plus common map-art blocks. Mutable so tests can
 * remove blocks, drop items or remap items. Top-face placeable unless axis=x/z or type=top.
 */
public final class FakeCatalog implements BlockCatalog {
    private static final String[] COLOURS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    public final Set<String> known = new HashSet<>();
    public final Set<String> noItem = new HashSet<>();
    public final Map<String, String> itemOverride = new HashMap<>();
    public final Map<String, Integer> stackSize = new HashMap<>();
    /** Map colour id per block id; every other block gets a colour from its id's hash. */
    public final Map<String, Integer> colors = new HashMap<>();

    public FakeCatalog() {
        for (String c : COLOURS)
            for (String kind : List.of("carpet", "wool", "terracotta", "concrete", "concrete_powder", "candle", "stained_glass"))
                known.add("minecraft:" + c + "_" + kind);
        for (String id : List.of(
            "air", "cave_air", "cobblestone", "stone", "tnt", "lapis_block", "diorite", "grass_block", "sand", "red_sand",
            "gravel", "cobweb", "iron_block", "oak_log", "oak_slab", "deepslate", "raw_iron_block", "glow_lichen",
            "warped_hyphae", "warped_nylium", "warped_stem", "warped_wart_block", "crimson_hyphae", "crimson_nylium",
            "pink_petals", "crimson_roots", "closed_eyeblossom", "moss_carpet", "pale_moss_carpet", "candle",
            "stone_pressure_plate", "oak_pressure_plate", "emerald_block", "bamboo_block", "clay", "netherrack",
            "packed_ice", "redstone_block", "prismarine_bricks", "cobbled_deepslate", "moss_block", "soul_sand",
            "pointed_dripstone", "decorated_pot", "resin_clump", "redstone_wire", "obsidian", "open_eyeblossom",
            "terracotta", "mushroom_stem", "dripstone_block"))
            known.add("minecraft:" + id);
        itemOverride.put("minecraft:redstone_wire", "minecraft:redstone");
        // 1.21.4 (2b2t) eyeblossom colours: open = COLOR_ORANGE (15), closed = TERRACOTTA_GREEN (49)
        colors.put("minecraft:open_eyeblossom", 15);
        colors.put("minecraft:closed_eyeblossom", 49);
    }

    @Override
    public boolean isKnownBlock(String blockId) {
        return known.contains(blockId);
    }

    @Override
    public boolean isKnownItem(String itemId) {
        return known.contains(itemId) || itemOverride.containsValue(itemId);
    }

    @Override
    public Optional<String> placementItem(BlockSpec spec) {
        if (!known.contains(spec.id()) || spec.isAir() || noItem.contains(spec.id())) return Optional.empty();
        return Optional.of(itemOverride.getOrDefault(spec.id(), spec.id()));
    }

    @Override
    public int mapColorId(BlockSpec spec) {
        if (spec.isAir()) return 0;
        Integer c = colors.get(spec.id());
        return c != null ? c : 1 + Math.floorMod(spec.id().hashCode(), 61);
    }

    @Override
    public boolean placeableOnTopFace(BlockSpec spec) {
        String axis = spec.props().get("axis");
        if ("x".equals(axis) || "z".equals(axis)) return false;
        return !"top".equals(spec.props().get("type"));
    }

    /** Same rules as production ({@link TopFaceRules#equivalent} is pure). */
    @Override
    public boolean matches(BlockSpec target, BlockSpec actual) {
        return TopFaceRules.equivalent(target, actual);
    }

    @Override
    public boolean isGravityBlock(String blockId) {
        return blockId.equals("minecraft:sand") || blockId.equals("minecraft:red_sand")
            || blockId.equals("minecraft:gravel") || blockId.endsWith("_concrete_powder");
    }

    @Override
    public int maxStackSize(String itemId) {
        return stackSize.getOrDefault(itemId, 64);
    }
}
