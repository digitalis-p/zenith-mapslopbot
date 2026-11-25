package io.github.digitalisp.mapart.bot.impl;

/**
 * Feet cells on top of a tile's border ({@code build.laneStartOnFloor}): the ring of
 * cells right outside the tile ({@code minX..maxX} × {@code minZ..maxZ}), {@code minY} (the art layer + 2) and higher.
 * The soul sand tile has a glass wall two blocks high along its south edge (one cell past the last row, art layer and
 * one up): its top is such a cell. Baritone walks on top of it rather than over the soul sand
 * ({@code ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST} is twice {@code WALK_ONE_BLOCK_COST}), one block wide beside a
 * drop of more than 3 blocks, and stops a walk to a south-edge lane start as soon as the feet cell is the start's column
 * on top of it. Walks given such a border ({@link Walker#border}) see these cells {@link NoGoZones#PENALTY} further from
 * their goal and never in it. A record: Baritone compares goals with equals ({@code isGoalActive}). Pure.
 */
record BorderTop(int minX, int minZ, int maxX, int maxZ, int minY) {
    /** The border tops of {@code tile} for a lane on art layer {@code artY}. */
    static BorderTop of(LaneRun.Area tile, int artY) {
        return new BorderTop(tile.minX(), tile.minZ(), tile.maxX(), tile.maxZ(), artY + 2);
    }

    /** Feet cell (x, y, z) is in the ring around the tile, at {@link #minY} or higher. */
    boolean contains(int x, int y, int z) {
        if (y < minY) return false;
        if (x < minX - 1 || x > maxX + 1 || z < minZ - 1 || z > maxZ + 1) return false;
        return x < minX || x > maxX || z < minZ || z > maxZ;
    }
}
