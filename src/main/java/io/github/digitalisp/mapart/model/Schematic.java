package io.github.digitalisp.mapart.model;

import java.util.List;

/**
 * Raw contents of a vanilla structure-block .nbt file (as exported by MapArtCraft).
 * Coordinates are structure-local: x = image column, z = image row + 1 (z = 0 is the noobline), y up.
 * {@code author} is the root {@code author} string tag (MapArtCraft writes "mike2b2t.github.io/mapartcraft"),
 * or "" when the file has none.
 * CONTRACT (lead-owned).
 */
public record Schematic(int sizeX, int sizeY, int sizeZ, int dataVersion, String author,
                        List<BlockSpec> palette, List<Block> blocks) {
    public Schematic {
        if (author == null) author = "";
    }

    /** One block entry; {@code state} indexes {@link #palette()}. Duplicate palette names are possible. */
    public record Block(int x, int y, int z, int state) { }
}
