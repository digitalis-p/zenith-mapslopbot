package io.github.digitalisp.mapart.catalog;

import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.Optional;

/**
 * Registry facts about blocks/items for the server's Minecraft version (1.21.4).
 * Production impl wraps ZenithProxy's BlockRegistry/ItemRegistry/MapBlockColorManager; tests use a
 * small fake. Implementations must be thread-safe (read-only). CONTRACT (lead-owned).
 */
public interface BlockCatalog {
    boolean isKnownBlock(String blockId);

    boolean isKnownItem(String itemId);

    /** Item that places this block (usually the same id). Empty if not placeable from an item. */
    Optional<String> placementItem(BlockSpec spec);

    /** Vanilla map colour id (1..61) for this state, or 0 if the block is invisible on maps. */
    int mapColorId(BlockSpec spec);

    /**
     * True if right-clicking the TOP face of the block below produces exactly {@code spec}
     * (carpets, full blocks, axis=y logs, bottom slabs, down=true lichen/resin, plates, candles...).
     * v1 only builds blocks for which this is true; validation rejects the rest.
     */
    boolean placeableOnTopFace(BlockSpec spec);

    /**
     * True if the world state {@code actual} counts as the planned {@code target}: same id, and every property the
     * target specifies matches, except volatile ones the world changes by itself (leaf {@code distance}, pale moss
     * carpet sides, huge mushroom faces, pressure plate {@code powered}/{@code power}); open and closed eyeblossoms
     * count as the same block (they flip with the time of day). Properties the target omits are ignored. A null or
     * air target matches only air. Every world-vs-plan comparison (surveys, verify, placement confirmation) uses this.
     */
    boolean matches(BlockSpec target, BlockSpec actual);

    /** Falls without support (sand, gravel, concrete powder...). */
    boolean isGravityBlock(String blockId);

    int maxStackSize(String itemId);
}
