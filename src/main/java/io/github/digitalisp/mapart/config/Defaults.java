package io.github.digitalisp.mapart.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Default block lists for the base's piston-clear platforms. Sources: the owner's platform notes,
 * MapArtCraft's "Piston clearable" preset, and the base's Meteor "kek-nuker" whitelist/blacklist.
 * Editable per platform in mapart.json. CONTRACT (lead-owned).
 */
public final class Defaults {
    private Defaults() { }

    private static final String[] COLOURS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    /**
     * Removed by the flying-machine piston clear: carpets, pressure plates, candles, dripstone, pots, resin, glow
     * lichen, birch leaves, foliage. Everything here except carpets has vanilla PushReaction.DESTROY (any piston
     * push breaks it); carpets were measured cleared by the moss machines.
     */
    public static final List<String> PISTON_CLEARABLE;
    /** Full blocks used in art that the bot must break ("nuke") before the piston clear (vanilla push NORMAL). */
    public static final List<String> NUKE_BLOCKS;
    /** Never break: base infrastructure (kek-nuker blacklist), platform floors and {@link #MACHINERY_BLOCKS}. */
    public static final List<String> PROTECTED_BLOCKS;
    /**
     * Redstone parts of the colour-suppression platform and its minecart "muncher" (tripwires, note blocks, buttons,
     * torches, wire, repeaters, comparators, wither-skeleton skulls) that never appear in map art (MapArtCraft uses
     * none of them; map colour NONE or not offered). Always protected, on top of {@code site.protectedBlocks}, so a
     * mapart.json saved by an older build gets them too. Machinery built from art blocks (redstone_block,
     * decorated_pot, iron_trapdoor, concrete, TNT) is protected by position instead: {@code site.protectedRegions}.
     */
    public static final List<String> MACHINERY_BLOCKS;
    /**
     * Blocks a colour-suppression map may use (docs/COLOR_SUPPRESSION.md): PNG-to-NBT's CrubTech preset without water (60
     * blocks, one per map colour), its moss and resin shading fillers, and dirt and netherrack: the 64 labelled columns
     * of the platform's sorter. The muncher clears the layers, so nothing here depends on the piston clear.
     */
    public static final List<String> CS_BLOCKS;
    /** Interchangeable for colour suppression (same map colour): the guide's waxed copper and the preset's plain one. */
    public static final java.util.Map<String, String> CS_ALIASES =
        java.util.Map.of("minecraft:waxed_oxidized_cut_copper", "minecraft:oxidized_cut_copper");

    static {
        List<String> c = new ArrayList<>();
        for (String col : COLOURS) {
            c.add("minecraft:" + col + "_carpet");
            c.add("minecraft:" + col + "_candle");
        }
        c.addAll(List.of(
            "minecraft:moss_carpet", "minecraft:pale_moss_carpet", "minecraft:candle",
            "minecraft:stone_pressure_plate", "minecraft:polished_blackstone_pressure_plate",
            "minecraft:light_weighted_pressure_plate", "minecraft:heavy_weighted_pressure_plate",
            "minecraft:oak_pressure_plate", "minecraft:spruce_pressure_plate", "minecraft:birch_pressure_plate",
            "minecraft:jungle_pressure_plate", "minecraft:acacia_pressure_plate", "minecraft:dark_oak_pressure_plate",
            "minecraft:mangrove_pressure_plate", "minecraft:cherry_pressure_plate", "minecraft:bamboo_pressure_plate",
            "minecraft:crimson_pressure_plate", "minecraft:warped_pressure_plate", "minecraft:pale_oak_pressure_plate",
            "minecraft:pointed_dripstone", "minecraft:decorated_pot", "minecraft:resin_clump", "minecraft:glow_lichen",
            "minecraft:birch_leaves",
            // foliage — moss platform only (soul sand platform config should drop these)
            "minecraft:pink_petals", "minecraft:crimson_roots", "minecraft:closed_eyeblossom", "minecraft:open_eyeblossom"));
        PISTON_CLEARABLE = List.copyOf(c);

        List<String> n = new ArrayList<>();
        for (String col : COLOURS) n.add("minecraft:" + col + "_terracotta");
        n.addAll(List.of(
            // stocked by the moss sorter
            "minecraft:terracotta", "minecraft:dripstone_block", "minecraft:mushroom_stem",
            "minecraft:emerald_block", "minecraft:bamboo_block", "minecraft:grass_block",
            "minecraft:crimson_hyphae", "minecraft:warped_hyphae", "minecraft:prismarine_bricks",
            "minecraft:packed_ice", "minecraft:raw_iron_block", "minecraft:warped_wart_block",
            "minecraft:redstone_block", "minecraft:clay", "minecraft:lapis_block",
            "minecraft:warped_nylium", "minecraft:cobbled_deepslate", "minecraft:netherrack",
            "minecraft:crimson_nylium"));
        NUKE_BLOCKS = List.copyOf(n);

        List<String> m = new ArrayList<>(List.of(
            "minecraft:tripwire", "minecraft:tripwire_hook", "minecraft:note_block",
            "minecraft:redstone_wire", "minecraft:repeater", "minecraft:comparator",
            "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
            "minecraft:wither_skeleton_skull", "minecraft:wither_skeleton_wall_skull"));
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "pale_oak", "bamboo", "crimson", "warped")) m.add("minecraft:" + wood + "_button");
        MACHINERY_BLOCKS = List.copyOf(m);

        List<String> p = new ArrayList<>();
        for (String col : COLOURS) {
            p.add("minecraft:" + col + "_shulker_box");
            p.add("minecraft:" + col + "_stained_glass");
        }
        p.addAll(List.of(
            "minecraft:shulker_box", "minecraft:obsidian", "minecraft:ender_chest", "minecraft:chest",
            "minecraft:trapped_chest", "minecraft:barrel", "minecraft:hopper", "minecraft:polished_blackstone_bricks",
            "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:moss_block", "minecraft:tinted_glass",
            "minecraft:glass", "minecraft:beacon", "minecraft:end_rod", "minecraft:piston", "minecraft:sticky_piston",
            "minecraft:observer", "minecraft:slime_block", "minecraft:honey_block", "minecraft:lever",
            "minecraft:stone_button", "minecraft:polished_blackstone_button", "minecraft:cartography_table",
            "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil", "minecraft:rail",
            "minecraft:powered_rail", "minecraft:detector_rail", "minecraft:activator_rail"));
        p.addAll(MACHINERY_BLOCKS);
        PROTECTED_BLOCKS = List.copyOf(p);

        List<String> cs = new ArrayList<>(List.of(
            "grass_block", "birch_planks", "mushroom_stem", "tnt", "packed_ice", "iron_block", "bamboo_block", "clay",
            "jungle_planks", "cobblestone", "oak_planks", "diorite", "gold_block", "prismarine_bricks", "lapis_block",
            "emerald_block", "spruce_planks", "nether_bricks", "crimson_nylium", "crimson_planks", "crimson_hyphae",
            "oxidized_cut_copper", "waxed_oxidized_cut_copper", "warped_planks", "warped_hyphae", "warped_wart_block",
            "cobbled_deepslate", "raw_iron_block", "verdant_froglight", "moss_block", "resin_block", "dirt",
            "netherrack"));
        for (String col : COLOURS) {
            cs.add(col + "_concrete");
            cs.add(col + "_terracotta");
        }
        cs.replaceAll(id -> "minecraft:" + id);
        CS_BLOCKS = List.copyOf(cs);
    }
}
