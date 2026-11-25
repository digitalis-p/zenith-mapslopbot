package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Serpentine lanes along Z. Lane {@code k} covers columns {@code [c−w, c+w]} around centre
 * {@code c = w + k(2w+1)} (clamped to the tile). Emitted lanes alternate N→S, S→N, starting N→S, so an
 * omitted (finished or empty) lane never forces a walk back. {@link Lane#index()} is {@code k}, stable across
 * replans. Placements follow the walking direction row by row; within a row, nearest the centre line first,
 * west before east on ties.
 *
 * <p><b>Even lanes</b> ({@code build.laneEvenWidths}). 128 columns in lanes of {@code 2w+1} leave a last lane of
 * {@code 128 mod (2w+1)} columns: 2 at {@code w = 4}, a full lane's walk for 256 cells, along the tile's last column.
 * With the switch the same number of lanes, {@code ceil(128 / (2w+1))}, share the columns as evenly as possible (at
 * {@code w = 4}: eight of 9, then seven of 8), each at most {@code 2w+1} wide with its centre {@code w} from its west
 * column, so no cell is farther than {@code w} from its walking line. Live 2026-10-23 (j0015, 9-wide airplace lanes):
 * lane 14 walked 14-16 s with Soul Speed and 38-56 s without it for 256 cells the click budget (9 per 310 ms) sends
 * in 9 s.
 */
public final class SerpentineBuildPlanner implements BuildPlanner {
    private static final int SIZE = TileArt.SIZE;

    private final BooleanSupplier evenWidths;

    /** Even lanes off: the planner as before. */
    public SerpentineBuildPlanner() {
        this(() -> false);
    }

    /** @param evenWidths {@code build.laneEvenWidths}, read at every plan */
    public SerpentineBuildPlanner(BooleanSupplier evenWidths) {
        this.evenWidths = evenWidths;
    }

    @Override
    public List<Lane> plan(TileArt art, PlatformGeometry geo, BlockCatalog catalog, int laneHalfWidth,
                           Predicate<Placement> done) {
        int w = Math.max(0, laneHalfWidth);
        int count = laneCount(w);
        boolean even = evenWidths.getAsBoolean();
        Map<BlockSpec, String> items = new HashMap<>();
        List<Lane> lanes = new ArrayList<>();
        boolean southward = true;
        for (int k = 0; k < count; k++) {
            int[] cols = laneColumns(k, w, even);
            int lo = cols[0];
            int hi = cols[1];
            int c = Math.min(hi, lo + w);
            List<Placement> ps = new ArrayList<>();
            int firstRow = -1, lastRow = -1;
            for (int i = 0; i < SIZE; i++) {
                int pz = southward ? i : SIZE - 1 - i;
                for (int d = 0; d <= hi - lo; d++) {
                    for (int x : d == 0 ? new int[] {c} : new int[] {c - d, c + d}) {
                        if (x < lo || x > hi) continue;
                        BlockSpec spec = art.get(x, pz);
                        if (spec == null) continue;
                        String item = items.computeIfAbsent(spec, s -> catalog.placementItem(s).orElse(s.id()));
                        Placement p = new Placement(geo.cell(x, pz), spec, item);
                        if (done != null && done.test(p)) continue;
                        ps.add(p);
                        if (firstRow < 0) firstRow = pz;
                        lastRow = pz;
                    }
                }
            }
            if (ps.isEmpty()) continue;
            int step = southward ? 1 : -1;
            lanes.add(new Lane(k, geo.cell(c, clamp(firstRow - step)), geo.cell(c, clamp(lastRow + step)), List.copyOf(ps)));
            southward = !southward;
        }
        return List.copyOf(lanes);
    }

    private static int clamp(int pz) {
        return Math.max(0, Math.min(SIZE - 1, pz));
    }

    /** Lanes of a tile at half width {@code w}: {@code ceil(128 / (2w+1))} (15 at {@code w = 4}). */
    public static int laneCount(int w) {
        int width = 2 * Math.max(0, w) + 1;
        return (SIZE + width - 1) / width;
    }

    /**
     * The tile-local columns {lo, hi} (inclusive) of lane {@code k} at half width {@code w}, with even lanes or not:
     * the planner's lane geometry, which lane bands are cut along.
     */
    public static int[] laneColumns(int k, int w, boolean even) {
        w = Math.max(0, w);
        int width = 2 * w + 1;
        int count = laneCount(w);
        if (k < 0 || k >= count) throw new IllegalArgumentException("lane " + k + " of " + count);
        int lo = even ? k * (SIZE / count) + Math.min(k, SIZE % count) : k * width;
        int hi = even ? lo + SIZE / count + (k < SIZE % count ? 1 : 0) - 1 : Math.min(SIZE - 1, lo + 2 * w);
        return new int[] {lo, hi};
    }
}
