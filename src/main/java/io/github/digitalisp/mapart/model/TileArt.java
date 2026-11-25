package io.github.digitalisp.mapart.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * The art layer of one 128x128 map tile: one block per pixel, or null for "leave empty" (transparent).
 * Pixel (px, pz): px = 0..127 west→east, pz = 0..127 north→south. Support blocks and the
 * noobline are NOT part of TileArt — the platforms have a permanent floor and noobline.
 * CONTRACT (lead-owned).
 */
public final class TileArt {
    public static final int SIZE = 128;
    private final BlockSpec[] cells = new BlockSpec[SIZE * SIZE];

    public BlockSpec get(int px, int pz) {
        return cells[index(px, pz)];
    }

    public void set(int px, int pz, BlockSpec spec) {
        cells[index(px, pz)] = (spec == null || spec.isAir()) ? null : spec;
    }

    /** Count of each block state (canonical string) in this tile, excluding empty pixels. */
    public Map<String, Integer> blockCounts() {
        Map<String, Integer> m = new TreeMap<>();
        for (BlockSpec c : cells) if (c != null) m.merge(c.toString(), 1, Integer::sum);
        return m;
    }

    public int nonEmptyCount() {
        int n = 0;
        for (BlockSpec c : cells) if (c != null) n++;
        return n;
    }

    private static int index(int px, int pz) {
        if (px < 0 || px >= SIZE || pz < 0 || pz >= SIZE)
            throw new IndexOutOfBoundsException("pixel " + px + "," + pz);
        return pz * SIZE + px;
    }
}
