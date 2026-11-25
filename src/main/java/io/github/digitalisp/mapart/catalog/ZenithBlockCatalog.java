package io.github.digitalisp.mapart.catalog;

import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import io.github.digitalisp.mapart.bot.impl.BlockStates;
import io.github.digitalisp.mapart.bot.impl.ItemIds;
import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.Map;
import java.util.Optional;

/**
 * {@link BlockCatalog} over ZenithProxy 3.7.2's generated 1.21.4 registries (BlockRegistry, ItemRegistry,
 * block state properties). Read-only and thread-safe.
 *
 * <p>Map colour: ZenithProxy stores one map colour per block (its default state). That is exact for every state
 * {@link #placeableOnTopFace} accepts (e.g. logs: axis=y is the default and has the "top" colour); pillars on their
 * side (colour-suppression maps) take theirs from {@link #SIDE_COLOURS}.
 */
public final class ZenithBlockCatalog implements BlockCatalog {
    /** Blocks whose item has a different id. None of these are needed by v1 palettes. */
    private static final Map<String, String> ITEM_EXCEPTIONS = Map.of(
        "minecraft:redstone_wire", "minecraft:redstone",
        "minecraft:tripwire", "minecraft:string");

    /**
     * Pillars whose map colour depends on their axis: the registry holds the colour of axis=y (the end grain); lying
     * on its side (axis x or z) the block shows this one (vanilla 1.21.4 Blocks: log(top, side)). Live 2026-11-14: a
     * colour-suppression map with bamboo_block[axis=x] was expected yellow (18) and filled plant (7), "3 pixels off".
     */
    private static final Map<String, Integer> SIDE_COLOURS = Map.ofEntries(
        Map.entry("minecraft:bamboo_block", 7),
        Map.entry("minecraft:oak_log", 34),
        Map.entry("minecraft:spruce_log", 26),
        Map.entry("minecraft:birch_log", 14),
        Map.entry("minecraft:jungle_log", 34),
        Map.entry("minecraft:acacia_log", 11),
        Map.entry("minecraft:cherry_log", 43),
        Map.entry("minecraft:pale_oak_log", 11),
        Map.entry("minecraft:mangrove_log", 34),
        Map.entry("minecraft:stripped_cherry_log", 42));

    @Override
    public boolean isKnownBlock(String blockId) {
        return block(blockId) != null;
    }

    @Override
    public boolean isKnownItem(String itemId) {
        return item(itemId) != null;
    }

    /** The block's item, as its material id ({@link ItemIds#material}: either eyeblossom → open_eyeblossom). */
    @Override
    public Optional<String> placementItem(BlockSpec spec) {
        if (spec == null || spec.isAir() || block(spec.id()) == null) return Optional.empty();
        String id = ItemIds.ns(spec.id());
        String exception = ITEM_EXCEPTIONS.get(id);
        if (exception != null) return Optional.of(exception);
        return item(id) != null ? Optional.of(ItemIds.material(id)) : Optional.empty();
    }

    @Override
    public int mapColorId(BlockSpec spec) {
        if (spec == null || spec.isAir()) return 0;
        Block b = block(spec.id());
        if (b == null) return 0;
        String axis = spec.props().get("axis");
        if ("x".equals(axis) || "z".equals(axis)) {
            Integer side = SIDE_COLOURS.get(ItemIds.ns(spec.id()));
            if (side != null) return side;
        }
        return Math.max(0, b.mapColorId());
    }

    @Override
    public boolean placeableOnTopFace(BlockSpec spec) {
        if (spec == null || spec.isAir()) return false;
        Block b = block(spec.id());
        if (b == null || placementItem(spec).isEmpty()) return false;
        // every given property must exist on the block (catches typos / other-version states)
        if (!spec.props().isEmpty()) {
            BlockSpec probe = BlockSpec.of(spec.id(), spec.props());
            if (BlockStates.stateId(probe) < 0) return false;
        }
        return TopFaceRules.placeableOnTopFace(spec);
    }

    @Override
    public boolean matches(BlockSpec target, BlockSpec actual) {
        return TopFaceRules.equivalent(target, actual);
    }

    @Override
    public boolean isGravityBlock(String blockId) {
        Block b = block(blockId);
        return b != null && b.fallingBlock();
    }

    @Override
    public int maxStackSize(String itemId) {
        ItemData d = item(itemId);
        return d == null ? 64 : Math.max(1, d.stackSize());
    }

    private static Block block(String id) {
        return id == null ? null : BlockRegistry.REGISTRY.get(ItemIds.path(id));
    }

    private static ItemData item(String id) {
        return id == null ? null : ItemRegistry.REGISTRY.get(ItemIds.path(id));
    }
}
