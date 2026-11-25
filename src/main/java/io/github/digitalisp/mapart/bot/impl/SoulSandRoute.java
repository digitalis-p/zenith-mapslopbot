package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A route along soul sand for a walk between two cells (Soul Speed boots work only with soul sand or soul soil directly
 * under the feet; live 2026-10-14 the prime to edge leg took 11 s for about 62 blocks, 5.6 b/s). Looks at the rows
 * parallel to the walk's longer axis around the two cells and picks the one whose floor (one below the target's level)
 * is mostly soul sand; the walk then goes to that row's near end, along it and on to the target. A cell counts only when
 * the block under the feet is soul sand or soul soil and the walking-level cell itself is air: Soul Speed needs the soul
 * block directly under the feet, and a carpet, plate, slab or snow layer on top of it (live 2026-10-14: a row of "100 %
 * soul sand" walked at 5.6 blocks/s) means the feet stand on that, not on the soul sand. Pure.
 */
final class SoulSandRoute {
    /** A row must be at least this share soul sand/soul soil to be worth the detour. */
    static final double MIN_SHARE = 0.8;

    /** {@code waypoints}: cells to walk through before the target; {@code row}: the row's fixed coordinate. */
    record Route(List<Pos> waypoints, int row, double share, double directShare, boolean alongX) { }

    private SoulSandRoute() { }

    /**
     * The best row for the walk {@code from} → {@code to} within {@code spread} blocks either side of the two cells'
     * rows, or null when walking straight is as good or no row reaches {@link #MIN_SHARE}. {@code blockId}: the block
     * id at a cell; unloaded cells ({@code loaded} false) are not soul sand.
     */
    static @Nullable Route plan(Pos from, Pos to, int spread, Function<Pos, String> blockId, Predicate<Pos> loaded) {
        Predicate<Pos> soul = p -> good(p, blockId);
        boolean alongX = Math.abs(to.x() - from.x()) >= Math.abs(to.z() - from.z());
        int a0 = alongX ? from.x() : from.z();
        int a1 = alongX ? to.x() : to.z();
        int c0 = alongX ? from.z() : from.x();
        int c1 = alongX ? to.z() : to.x();
        if (Math.abs(a1 - a0) < 8) return null; // too short to matter
        int y = to.y() - 1;
        int lo = Math.min(c0, c1) - Math.max(0, spread);
        int hi = Math.max(c0, c1) + Math.max(0, spread);
        double bestShare = -1;
        int best = c1;
        for (int c = lo; c <= hi; c++) {
            double share = share(alongX, a0, a1, c, y, soul, loaded);
            if (share > bestShare + 1e-9 || Math.abs(share - bestShare) <= 1e-9 && Math.abs(c - c1) < Math.abs(best - c1)) {
                bestShare = share;
                best = c;
            }
        }
        double direct = Math.max(share(alongX, a0, a1, c0, y, soul, loaded), share(alongX, a0, a1, c1, y, soul, loaded));
        if (bestShare < MIN_SHARE || bestShare <= direct + 0.01) return null;
        List<Pos> wps = new ArrayList<>();
        wps.add(alongX ? new Pos(a0, to.y(), best) : new Pos(best, to.y(), a0));
        wps.add(alongX ? new Pos(a1, to.y(), best) : new Pos(best, to.y(), a1));
        return new Route(wps, best, bestShare, direct, alongX);
    }

    static boolean air(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    /** {@code floor} is the cell under the feet: soul sand/soul soil there and nothing on top of it at the walking level. */
    private static boolean good(Pos floor, Function<Pos, String> blockId) {
        String f = blockId.apply(floor);
        return (f.equals("minecraft:soul_sand") || f.equals("minecraft:soul_soil"))
            && air(blockId.apply(new Pos(floor.x(), floor.y() + 1, floor.z())));
    }

    /**
     * Why a straight walk is not on soul sand: the first few cells of the row of {@code to} along the walk whose floor is
     * not soul sand/soul soil, or that carry a block at the walking level, as "x,y,z floor=… level=…" (for the log).
     */
    static String diagnose(Pos from, Pos to, Function<Pos, String> blockId, Predicate<Pos> loaded) {
        boolean alongX = Math.abs(to.x() - from.x()) >= Math.abs(to.z() - from.z());
        int a0 = alongX ? from.x() : from.z();
        int a1 = alongX ? to.x() : to.z();
        int c = alongX ? to.z() : to.x();
        int y = to.y() - 1;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int a = Math.min(a0, a1); a <= Math.max(a0, a1) && n < 4; a++) {
            Pos p = alongX ? new Pos(a, y, c) : new Pos(c, y, a);
            if (!loaded.test(p)) {
                sb.append(n++ > 0 ? "; " : "").append(p.x()).append(',').append(p.y()).append(',').append(p.z())
                    .append(" unloaded");
            } else if (!good(p, blockId)) {
                sb.append(n++ > 0 ? "; " : "").append(p.x()).append(',').append(p.y()).append(',').append(p.z())
                    .append(" floor=").append(blockId.apply(p)).append(" level=").append(blockId.apply(new Pos(p.x(), p.y() + 1, p.z())));
            }
        }
        return n == 0 ? "none" : sb.toString();
    }

    private static double share(boolean alongX, int a0, int a1, int c, int y, Predicate<Pos> soul, Predicate<Pos> loaded) {
        int lo = Math.min(a0, a1);
        int hi = Math.max(a0, a1);
        int n = 0;
        int ok = 0;
        for (int a = lo; a <= hi; a++) {
            Pos p = alongX ? new Pos(a, y, c) : new Pos(c, y, a);
            n++;
            if (loaded.test(p) && soul.test(p)) ok++;
        }
        return n == 0 ? 0 : (double) ok / n;
    }
}
