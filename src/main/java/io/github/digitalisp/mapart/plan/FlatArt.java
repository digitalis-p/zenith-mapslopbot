package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Schematic;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.Arrays;

/**
 * The visible (topmost) block of every column of a structure, used to cut flat map tiles out of it.
 * Row z = 0 is the noobline and is never part of a tile; support blocks are never topmost in flat art.
 */
final class FlatArt {
    private static final int NONE = Integer.MIN_VALUE;

    private final Schematic schematic;
    private final BlockSpec[] palette;
    private final int[] topY;
    private final int[] topState;

    FlatArt(Schematic s) {
        this.schematic = s;
        this.palette = s.palette().toArray(new BlockSpec[0]);
        int n = s.sizeX() * s.sizeZ();
        this.topY = new int[n];
        this.topState = new int[n];
        Arrays.fill(topY, NONE);
        for (Schematic.Block b : s.blocks()) {
            int i = b.z() * s.sizeX() + b.x();
            if (b.y() >= topY[i]) {
                topY[i] = b.y();
                topState[i] = b.state();
            }
        }
    }

    /** {min, max} Y of the topmost non-air blocks in rows z ≥ 1, or null if every column is empty/air. */
    int[] heightRange() {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int z = 1; z < schematic.sizeZ(); z++) {
            for (int x = 0; x < schematic.sizeX(); x++) {
                int i = z * schematic.sizeX() + x;
                if (topY[i] == NONE || palette[topState[i]].isAir()) continue;
                min = Math.min(min, topY[i]);
                max = Math.max(max, topY[i]);
            }
        }
        return max == Integer.MIN_VALUE ? null : new int[] {min, max};
    }

    /** The 128x128 tile whose NW pixel is column {@code x0}, row {@code z0 + 1} (rows skip the noobline). */
    TileArt tile(int x0, int z0) {
        TileArt art = new TileArt();
        for (int pz = 0; pz < TileArt.SIZE; pz++) {
            int z = z0 + pz + 1;
            for (int px = 0; px < TileArt.SIZE; px++) {
                int i = z * schematic.sizeX() + x0 + px;
                if (topY[i] != NONE) art.set(px, pz, palette[topState[i]]);
            }
        }
        return art;
    }
}
