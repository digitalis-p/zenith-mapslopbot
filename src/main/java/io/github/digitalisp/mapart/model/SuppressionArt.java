package io.github.digitalisp.mapart.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A colour-suppression map (PNG-to-NBT "Suppress (2-Layer)" with the CrubTech option, docs/COLOR_SUPPRESSION.md): two
 * art layers on plain-glass floors, {@link #BOTTOM} and {@link #TOP}, each with cells at two levels (0 = on the glass,
 * 1 = one block higher). Dominant pixels (column + row odd) show the bottom layer, recessive pixels the top layer;
 * every level-0 or level-1 block north of a pixel is that pixel's shading filler. Columns 0..127 west to east; rows
 * {@link #NOOBLINE} (-1, the row north of the map, fillers only) and 0..127 north to south. A cell is a block or null
 * (air). Mutable; not thread-safe. CONTRACT (lead-owned).
 */
public final class SuppressionArt {
    public static final int BOTTOM = 0;
    public static final int TOP = 1;
    public static final int LAYERS = 2;
    public static final int LEVELS = 2;
    public static final int COLS = TileArt.SIZE;
    /** Rows -1 (noobline) .. 127. */
    public static final int ROWS = TileArt.SIZE + 1;
    public static final int NOOBLINE = -1;
    /** Blocks between the two glass floors (CrubTech: 14), so the top layer's level 0 is this far above the bottom's. */
    public static final int LAYER_GAP = 14;

    private final BlockSpec[] cells = new BlockSpec[LAYERS * LEVELS * ROWS * COLS];

    /**
     * One build pass: the cells of one level of one layer, either the 128 x 128 map rows ({@code noobline} false,
     * TileArt row = map row) or the noobline row alone ({@code noobline} true, in TileArt row 0, all other rows empty).
     */
    public record Pass(int layer, int level, boolean noobline, TileArt art) {
        public String label() {
            return (layer == BOTTOM ? "bottom" : "top") + " layer " + (level == 0 ? "y+0" : "y+1")
                + (noobline ? " noobline" : "");
        }
    }

    public BlockSpec get(int layer, int level, int col, int row) {
        return cells[index(layer, level, col, row)];
    }

    public void set(int layer, int level, int col, int row, BlockSpec spec) {
        cells[index(layer, level, col, row)] = spec == null || spec.isAir() ? null : spec;
    }

    /** The highest block of a layer's column, or null. */
    public BlockSpec top(int layer, int col, int row) {
        BlockSpec b = get(layer, 1, col, row);
        return b != null ? b : get(layer, 0, col, row);
    }

    /** Level of {@link #top}, or -1 when the column is empty on that layer. */
    public int topLevel(int layer, int col, int row) {
        if (get(layer, 1, col, row) != null) return 1;
        return get(layer, 0, col, row) != null ? 0 : -1;
    }

    /** Dominant pixel (shown by the bottom layer after the ride): column + row odd. The noobline has none. */
    public static boolean dominant(int col, int row) {
        return row >= 0 && ((col + row) & 1) == 1;
    }

    public int count(int layer) {
        int n = 0;
        for (int level = 0; level < LEVELS; level++)
            for (int row = NOOBLINE; row < TileArt.SIZE; row++)
                for (int col = 0; col < COLS; col++)
                    if (get(layer, level, col, row) != null) n++;
        return n;
    }

    public int count() {
        return count(BOTTOM) + count(TOP);
    }

    /** Count of each block state (canonical string) over both layers. */
    public Map<String, Integer> blockCounts() {
        Map<String, Integer> m = new TreeMap<>();
        for (BlockSpec c : cells) if (c != null) m.merge(c.toString(), 1, Integer::sum);
        return m;
    }

    /** The top layer's passes come first in {@link #passes()} ({@code platform.topLayerFirst}); default bottom first. */
    private boolean topFirst;

    /** Sets the order of {@link #passes()}: the top layer first, or the bottom layer first. Returns this. */
    public SuppressionArt topFirst(boolean on) {
        topFirst = on;
        return this;
    }

    public boolean topFirst() {
        return topFirst;
    }

    /**
     * The build passes in build order (docs/COLOR_SUPPRESSION.md): one layer, then the other (the top layer first with
     * {@link #topFirst}, else the bottom layer); in each, level 0 before level 1, and the noobline row before the map
     * rows of the same level. Empty passes are left out.
     */
    public List<Pass> passes() {
        List<Pass> out = new ArrayList<>();
        for (int li = 0; li < LAYERS; li++) {
            int layer = topFirst ? LAYERS - 1 - li : li;
            for (int level = 0; level < LEVELS; level++) {
                TileArt noob = new TileArt();
                TileArt main = new TileArt();
                boolean anyNoob = false, anyMain = false;
                for (int col = 0; col < COLS; col++) {
                    BlockSpec n = get(layer, level, col, NOOBLINE);
                    if (n != null) {
                        noob.set(col, 0, n);
                        anyNoob = true;
                    }
                    for (int row = 0; row < TileArt.SIZE; row++) {
                        BlockSpec b = get(layer, level, col, row);
                        if (b != null) {
                            main.set(col, row, b);
                            anyMain = true;
                        }
                    }
                }
                if (anyNoob) out.add(new Pass(layer, level, true, noob));
                if (anyMain) out.add(new Pass(layer, level, false, main));
            }
        }
        return out;
    }

    /** The art back from its {@link #passes()} (as stored with a job). */
    public static SuppressionArt fromPasses(List<Pass> passes) {
        SuppressionArt a = new SuppressionArt();
        for (Pass p : passes)
            for (int col = 0; col < COLS; col++) {
                if (p.noobline()) {
                    a.set(p.layer(), p.level(), col, NOOBLINE, p.art().get(col, 0));
                    continue;
                }
                for (int row = 0; row < TileArt.SIZE; row++) {
                    BlockSpec b = p.art().get(col, row);
                    if (b != null) a.set(p.layer(), p.level(), col, row, b);
                }
            }
        return a;
    }

    private static int index(int layer, int level, int col, int row) {
        if (layer < 0 || layer >= LAYERS || level < 0 || level >= LEVELS || col < 0 || col >= COLS
            || row < NOOBLINE || row >= TileArt.SIZE)
            throw new IndexOutOfBoundsException("cell " + layer + "/" + level + " " + col + "," + row);
        return ((layer * LEVELS + level) * ROWS + (row + 1)) * COLS + col;
    }
}
