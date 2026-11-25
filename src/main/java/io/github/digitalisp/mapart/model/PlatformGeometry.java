package io.github.digitalisp.mapart.model;

/**
 * Where a 128x128 tile sits in the world. {@code minX}/{@code minZ} are the NW corner of the map
 * area (must be ≡ -64 mod 128 — vanilla map grid). {@code artY} is the Y of the art layer (floor + 1).
 * CONTRACT (lead-owned).
 */
public record PlatformGeometry(int minX, int minZ, int artY) {
    public Pos cell(int px, int pz) {
        return new Pos(minX + px, artY, minZ + pz);
    }

    public Pos center() {
        return new Pos(minX + 64, artY, minZ + 64);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x < minX + TileArt.SIZE && z >= minZ && z < minZ + TileArt.SIZE;
    }

    public boolean gridAligned() {
        return Math.floorMod(minX + 64, 128) == 0 && Math.floorMod(minZ + 64, 128) == 0;
    }
}
