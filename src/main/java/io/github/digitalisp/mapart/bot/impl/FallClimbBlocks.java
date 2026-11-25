package io.github.digitalisp.mapart.bot.impl;

import com.zenith.mc.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which carried block the fall rescue's climb places ({@link FallClimb}): the first
 * of {@code build.fallRescueBlocks} the bot carries, else (with {@code build.fallRescueAnyBlock}) the carried block of
 * which it has most that suits: a full cube in collision and outline, no block entity, nothing a click uses
 * ({@link BlockStates#sideClickable}), and none of {@link #unsuitable}: blocks that fall, melt, slide, explode, power
 * redstone, light up machines, drop nothing or are worth keeping.
 */
final class FallClimbBlocks {
    private FallClimbBlocks() { }

    /** Name parts of blocks the climb never places. */
    private static final List<String> NEVER_PARTS = List.of(
        "_ore", "ancient_debris", "concrete_powder", "gravel", "glass", "leaves", "ice", "slime", "honey", "tnt",
        "sponge", "magma", "redstone", "observer", "piston", "dispenser", "dropper", "note_block", "target", "lamp",
        "bulb", "shulker", "spawner", "infested", "budding", "bedrock", "obsidian", "anvil", "beacon", "conduit",
        "lodestone", "respawn_anchor", "bookshelf", "pumpkin", "melon", "mushroom", "sculk", "soul_", "nylium",
        "grass_block", "mycelium", "podzol", "farmland", "dirt_path", "structure", "command_block", "jigsaw", "barrier",
        "reinforced", "frame", "glowstone", "sea_lantern", "shroomlight", "froglight", "crafting_table", "_table",
        "furnace", "loom", "stonecutter", "jukebox", "beehive", "bee_nest", "hay_block", "dried_kelp", "copper", "trial",
        "vault", "heavy_core", "crafter", "chiseled_bookshelf", "end_portal", "snow", "powder_snow", "mud", "moss",
        "clay");
    /** Whole blocks the climb never places. */
    private static final Set<String> NEVER = Set.of(
        "sand", "red_sand", "suspicious_sand", "dragon_egg", "diamond_block", "emerald_block", "gold_block", "iron_block",
        "netherite_block", "lapis_block", "raw_gold_block", "raw_iron_block", "raw_copper_block", "amethyst_block",
        "coal_block", "wet_sponge");

    /** The climb never places {@code id} (a path or a namespaced id), whatever its shape. */
    static boolean unsuitable(String id) {
        String path = ItemIds.path(id);
        if (NEVER.contains(path)) return true;
        for (String part : NEVER_PARTS) if (path.contains(part)) return true;
        return false;
    }

    /** {@code id} suits the climb: a block in ZenithProxy's registry, a full clickable cube, not {@link #unsuitable}. */
    static boolean suitable(String id) {
        if (id == null || unsuitable(id)) return false;
        Block b = BlockStates.block(id);
        if (b == null || b.isAir()) return false;
        return BlockStates.sideClickable(b.minStateId());
    }

    /**
     * The block to climb with: the first of {@code preferred} with a count in {@code carried} (id → count), else, when
     * {@code any}, the {@code suitable} carried block of which there are most (ties: the lower id); null when none.
     */
    static @Nullable String choose(Map<String, Integer> carried, List<String> preferred, boolean any,
                                   java.util.function.Predicate<String> suitable) {
        for (String id : preferred) {
            if (id != null && carried.getOrDefault(id, 0) > 0) return id;
        }
        if (!any) return null;
        String best = null;
        int most = 0;
        for (var e : new java.util.TreeMap<>(carried).entrySet()) {
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n <= most || !suitable.test(e.getKey())) continue;
            best = e.getKey();
            most = n;
        }
        return best;
    }

    /**
     * How many blocks the climb can place from {@code carried}: every {@code preferred} one, and with {@code any} every
     * {@code suitable} one too ({@link #choose} takes the next kind when one runs out).
     */
    static int available(Map<String, Integer> carried, List<String> preferred, boolean any,
                         java.util.function.Predicate<String> suitable) {
        int n = 0;
        for (var e : carried.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (preferred.contains(e.getKey()) || any && suitable.test(e.getKey())) n += e.getValue();
        }
        return n;
    }
}
