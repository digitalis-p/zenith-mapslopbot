package io.github.digitalisp.mapart.model;

/** Tile index inside an NxM art: x west→east, z north→south, both 0-based. CONTRACT (lead-owned). */
public record TileCoord(int x, int z) implements Comparable<TileCoord> {
    @Override
    public int compareTo(TileCoord o) {
        return z != o.z ? Integer.compare(z, o.z) : Integer.compare(x, o.x);
    }

    @Override
    public String toString() {
        return x + "," + z;
    }
}
