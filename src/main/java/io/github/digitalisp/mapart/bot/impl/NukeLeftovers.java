package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The fast nuke's leftover route ({@code build.nukeLeftoverRoute}, {@link NukeRun}): one walk over what the sweep left
 * instead of a Baritone walk to each block. Pure.
 *
 * <p>The targets are ordered nearest first from the feet and the order improved by 2-opt ({@link NukeSweep#twoOpt}) on
 * the whole walk, to the end point when given (the platform's {@code nukeEndCorner}; NaN: anywhere). Along that order
 * each target not covered yet gets a stand cell: of the cells that have it within {@code cover} (horizontally, from the
 * cell's centre to the nearest point of the target's cell) and that the bot may stand in ({@code standable}), the one
 * with the most targets not covered yet within {@code cover}, of those the one with the shortest walk from the stand
 * before and on to the next target not covered; every later target within {@code cover} of that cell is covered by it
 * too (several blocks from one stand). The stands are then ordered again by 2-opt. Targets within {@code cover} of
 * the feet are covered where the bot stands; a target no allowed cell covers is in no cover (the standing phase does
 * it).
 *
 * <p>The plan is a tour ({@link NukeSweep.Plan#isTour}) of one-cell "lanes" along Z (line = x, row = z): waypoint 0 at
 * the feet with the targets in reach there, then one waypoint per stand at its cell's centre with its targets, so the
 * walk ({@link NukeRun}'s sweep) skips a stand once its blocks have broken, from wherever they broke.
 */
final class NukeLeftovers {
    private NukeLeftovers() { }

    /**
     * Horizontal distance (blocks) from a stand's centre to the nearest point of a target's cell at which the stand
     * covers it: with the bot up to 0.3 off the centre on arrival 4.05, inside the 4.34 that the run's 4.38 from the
     * eye leaves at 0.62 above the top face. The run breaks every target in reach on the way, so most break before
     * their stand.
     */
    static final double COVER = 3.75;
    /** Targets (or stands) above this many get no 2-opt, nearest first only: a pass is quadratic. */
    static final int OPT_MAX = 600;

    /**
     * The route over {@code targets} (one level, {@code y}) from the feet at ({@code fx}, {@code fz}) towards
     * ({@code endX}, {@code endZ}) (NaN: anywhere), stands within {@code cover} of their targets on cells
     * {@code standable} accepts. No target: waypoint 0 only.
     */
    static NukeSweep.Plan plan(Collection<Pos> targets, double fx, double fz, int y, double endX, double endZ,
                               double cover, Predicate<Pos> standable) {
        return plan(targets, fx, fz, y, endX, endZ, t -> cover, standable);
    }

    /**
     * {@link #plan(Collection, double, double, int, double, double, double, Predicate)} with each target's own cover
     * ({@code coverOf}): a carpet in a pit between full blocks is seen only from right beside it.
     */
    static NukeSweep.Plan plan(Collection<Pos> targets, double fx, double fz, int y, double endX, double endZ,
                               ToDoubleFunction<Pos> coverOf, Predicate<Pos> standable) {
        List<Pos> ts = new ArrayList<>(new LinkedHashSet<>(targets));
        int n = ts.size();
        double cover = 0;
        for (Pos t : ts) cover = Math.max(cover, coverOf.applyAsDouble(t));
        Set<Pos> here = new LinkedHashSet<>();
        for (Pos t : ts) if (reach(fx, fz, t) <= coverOf.applyAsDouble(t)) here.add(t);
        double[][] centre = new double[n][];
        for (int i = 0; i < n; i++) centre[i] = new double[]{ts.get(i).x() + 0.5, ts.get(i).z() + 0.5};
        int[] ord = nearestFirst(centre, fx, fz);
        if (n <= OPT_MAX) NukeSweep.twoOpt(ord, new boolean[n], centre, centre, fx, fz, endX, endZ);
        boolean toEnd = !Double.isNaN(endX) && !Double.isNaN(endZ);

        Set<Pos> covered = new HashSet<>(here);
        Set<Pos> open = new HashSet<>(ts);
        open.removeAll(here);
        List<Pos> stands = new ArrayList<>();
        List<Set<Pos>> covers = new ArrayList<>();
        double cx = fx;
        double cz = fz;
        int r = (int) Math.ceil(cover + 0.5);
        for (int k = 0; k < n; k++) {
            Pos t = ts.get(ord[k]);
            if (covered.contains(t)) continue;
            double[] next = toEnd ? new double[]{endX, endZ} : null;
            for (int j = k + 1; j < n; j++) {
                if (covered.contains(ts.get(ord[j]))) continue;
                next = centre[ord[j]];
                break;
            }
            Pos best = null;
            int bestCount = 0;
            double bestCost = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    Pos s = new Pos(t.x() + dx, y, t.z() + dz);
                    double sx = s.x() + 0.5;
                    double sz = s.z() + 0.5;
                    if (reach(sx, sz, t) > coverOf.applyAsDouble(t) || !standable.test(s)) continue;
                    int count = near(open, s, r, coverOf).size();
                    double cost = Math.hypot(sx - cx, sz - cz)
                        + (next == null ? 0 : Math.hypot(next[0] - sx, next[1] - sz));
                    if (count > bestCount || (count == bestCount && cost < bestCost - 1e-9)) {
                        bestCount = count;
                        bestCost = cost;
                        best = s;
                    }
                }
            }
            if (best == null) continue; // in reach of no cell the bot may stand in: the standing phase
            Set<Pos> cov = near(open, best, r, coverOf);
            covered.addAll(cov);
            open.removeAll(cov);
            stands.add(best);
            covers.add(cov);
            cx = best.x() + 0.5;
            cz = best.z() + 0.5;
        }

        int m = stands.size();
        double[][] at = new double[m][];
        for (int i = 0; i < m; i++) at[i] = new double[]{stands.get(i).x() + 0.5, stands.get(i).z() + 0.5};
        int[] so = new int[m];
        for (int i = 0; i < m; i++) so[i] = i;
        if (m <= OPT_MAX) NukeSweep.twoOpt(so, new boolean[m], at, at, fx, fz, endX, endZ);

        List<NukeSweep.Lane> lanes = new ArrayList<>(m + 1);
        List<double[]> wps = new ArrayList<>(m + 1);
        List<Set<Pos>> cov = new ArrayList<>(m + 1);
        int[] seg = new int[m + 1];
        int feetZ = (int) Math.floor(fz);
        lanes.add(new NukeSweep.Lane((int) Math.floor(fx), feetZ, feetZ));
        wps.add(new double[]{fx, fz});
        cov.add(here);
        for (int k = 0; k < m; k++) {
            Pos s = stands.get(so[k]);
            lanes.add(new NukeSweep.Lane(s.x(), s.z(), s.z()));
            wps.add(at[so[k]]);
            cov.add(covers.get(so[k]));
            seg[k + 1] = k + 1;
        }
        return new NukeSweep.Plan(NukeSweep.Axis.Z, lanes, wps, cov, seg);
    }

    /** Indices of {@code pts} in the order of a nearest-first walk from (fx, fz). */
    private static int[] nearestFirst(double[][] pts, double fx, double fz) {
        int n = pts.length;
        int[] ord = new int[n];
        boolean[] used = new boolean[n];
        double px = fx;
        double pz = fz;
        for (int k = 0; k < n; k++) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                double d = Math.hypot(pts[i][0] - px, pts[i][1] - pz);
                if (d < bestD - 1e-9) {
                    bestD = d;
                    best = i;
                }
            }
            used[best] = true;
            ord[k] = best;
            px = pts[best][0];
            pz = pts[best][1];
        }
        return ord;
    }

    /** The targets of {@code open} within {@code cover} of stand {@code s}'s centre (all within {@code r} cells). */
    private static Set<Pos> near(Set<Pos> open, Pos s, int r, ToDoubleFunction<Pos> coverOf) {
        Set<Pos> out = new LinkedHashSet<>();
        double sx = s.x() + 0.5;
        double sz = s.z() + 0.5;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Pos u = new Pos(s.x() + dx, s.y(), s.z() + dz);
                if (open.contains(u) && reach(sx, sz, u) <= coverOf.applyAsDouble(u)) out.add(u);
            }
        }
        return out;
    }

    /** Horizontal distance from (x, z) to the nearest point of cell {@code t}. */
    static double reach(double x, double z, Pos t) {
        double dx = Math.max(Math.max(t.x() - x, 0), x - (t.x() + 1));
        double dz = Math.max(Math.max(t.z() - z, 0), z - (t.z() + 1));
        return Math.hypot(dx, dz);
    }
}
