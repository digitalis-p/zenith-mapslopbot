package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The lethal edges of a colour-suppression platform's glass floors (docs/COLOR_SUPPRESSION.md), read from the world, as
 * no-go boxes one block wide on each side of the edge: the bot never stands, walks or ends a walk within one block of a
 * drop that would hurt it ({@link #GROW} cells), and a task stops when its feet enter one (the no-go zones' position
 * check). Pure.
 * <p>
 * Around each floor (the tile and two rows north of it, plus a ring of one cell) a cell is lethal when nothing at the
 * glass level catches the bot (no glass, walkway or block there), the bot could step into it (its feet and head cells
 * are passable), and the first block below that would catch it lies more than {@link #SAFE_DROP} blocks down. Water
 * does not count as a safe landing (a fall of 26 blocks "into water" cost 16 HP in the local test). An unloaded cell
 * counts as lethal, and the result is then incomplete.
 */
final class EdgeHazards {
    /** Largest drop counted as safe: ZenithProxy's pathfinder default {@code maxFallHeightNoWater}. */
    static final int SAFE_DROP = 3;
    /** Feet levels kept clear: on the glass, on a y+0 block, on a y+1 block, and one more for the head. */
    static final int HEIGHT = 4;
    /**
     * Cells kept clear on each side of a lethal cell. Two, not one: in the local test a lane walk stopped by the margin
     * still slid on to 0.04 blocks short of the drop (its feet entered the margin, the task stopped, the bot coasted).
     */
    static final int GROW = 2;

    /** Blocks without collision a falling bot passes through, and that a walking bot can step into. */
    private static final Set<String> PASSABLE = Set.of("rail", "powered_rail", "detector_rail", "activator_rail",
        "tripwire", "redstone_wire", "torch", "wall_torch", "redstone_torch", "redstone_wall_torch", "lever",
        "short_grass", "tall_grass", "fern", "snow", "water", "lava", "bubble_column", "light", "structure_void");

    private EdgeHazards() { }

    /** Block access: loaded and block state at a cell. */
    interface Blocks {
        boolean loaded(int x, int y, int z);

        BlockSpec block(int x, int y, int z);
    }

    /** {@code zones}: the boxes to keep out of; {@code lethal}: the lethal cells found; {@code complete}: all loaded. */
    record Result(List<RegionCfg> zones, int lethal, boolean complete) { }

    static Result compute(PlatformCfg p, Blocks w) {
        List<RegionCfg> out = new ArrayList<>();
        int lethalCells = 0;
        boolean complete = true;
        if (p == null || p.glassY == null) return new Result(out, 0, true);
        int x0 = p.minX - 1, x1 = p.minX + 128, z0 = p.minZ - 3, z1 = p.minZ + 128;
        int nx = x1 - x0 + 1, nz = z1 - z0 + 1;
        int layer = 0;
        for (Integer gy : p.glassY) {
            if (gy == null) continue;
            int g = gy;
            boolean[] lethal = new boolean[nx * nz];
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    int st = cell(w, x, g, z);
                    if (st == UNLOADED) complete = false;
                    if (st != SAFE) {
                        lethal[(z - z0) * nx + (x - x0)] = true;
                        lethalCells++;
                    }
                }
            out.addAll(boxes(lethal, x0, z0, nx, nz, g + 1, g + HEIGHT,
                (p.id == null ? "" : p.id + " ") + (layer == 0 ? "bottom" : "top") + " layer: lethal edge"));
            layer++;
        }
        return new Result(List.copyOf(out), lethalCells, complete);
    }

    private static final int SAFE = 0, LETHAL = 1, UNLOADED = 2;

    /** SAFE, LETHAL or UNLOADED for the column (x, z) next to a floor at glass level {@code g}. */
    static int cell(Blocks w, int x, int g, int z) {
        int feet = g + 1;
        if (!w.loaded(x, g, z) || !w.loaded(x, feet, z) || !w.loaded(x, feet + 1, z)) return UNLOADED;
        if (catches(w.block(x, g, z))) return SAFE; // a floor at the glass level: the platform, a walkway
        if (!passable(w.block(x, feet, z)) || !passable(w.block(x, feet + 1, z))) return SAFE; // a wall
        for (int d = 1; d <= SAFE_DROP; d++) {
            int y = g - d;
            if (!w.loaded(x, y, z)) return UNLOADED;
            if (catches(w.block(x, y, z))) return SAFE; // lands with the feet d blocks lower
        }
        return LETHAL;
    }

    static boolean passable(BlockSpec b) {
        return b == null || b.isAir() || PASSABLE.contains(path(b.id())) || path(b.id()).endsWith("_button");
    }

    static boolean catches(BlockSpec b) {
        return b != null && !b.isAir() && !PASSABLE.contains(path(b.id()));
    }

    private static String path(String id) {
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    /**
     * The lethal cells grown by {@link #GROW} cells (Chebyshev) as boxes from feet level {@code y0} to {@code y1}: runs along x per
     * row, a run continued unchanged over the next rows merged into one box.
     */
    static List<RegionCfg> boxes(boolean[] lethal, int x0, int z0, int nx, int nz, int y0, int y1, String name) {
        int gx = nx + 2 * GROW, gz = nz + 2 * GROW; // grown grid, GROW cells more on each side
        boolean[] grown = new boolean[gx * gz];
        for (int z = 0; z < nz; z++)
            for (int x = 0; x < nx; x++)
                if (lethal[z * nx + x])
                    for (int dz = 0; dz <= 2 * GROW; dz++)
                        for (int dx = 0; dx <= 2 * GROW; dx++) grown[(z + dz) * gx + (x + dx)] = true;
        List<RegionCfg> out = new ArrayList<>();
        List<int[]> open = new ArrayList<>(); // runs of the previous row: {xa, xb, zStart}
        for (int z = 0; z <= gz; z++) {
            List<int[]> runs = new ArrayList<>();
            if (z < gz)
                for (int x = 0; x < gx; ) {
                    if (!grown[z * gx + x]) {
                        x++;
                        continue;
                    }
                    int a = x;
                    while (x < gx && grown[z * gx + x]) x++;
                    runs.add(new int[]{a, x - 1});
                }
            List<int[]> next = new ArrayList<>();
            for (int[] r : runs) {
                int[] cont = null;
                for (int[] o : open) if (o[0] == r[0] && o[1] == r[1]) cont = o;
                if (cont != null) {
                    open.remove(cont);
                    next.add(cont);
                } else next.add(new int[]{r[0], r[1], z});
            }
            for (int[] o : open)
                out.add(box(x0 - GROW + o[0], y0, z0 - GROW + o[2], x0 - GROW + o[1], y1, z0 - GROW + z - 1, name));
            open = next;
        }
        return out;
    }

    private static RegionCfg box(int ax, int ay, int az, int bx, int by, int bz, String name) {
        RegionCfg r = new RegionCfg();
        r.min = new Pos(ax, ay, az);
        r.max = new Pos(bx, by, bz);
        r.name = name;
        return r;
    }
}
