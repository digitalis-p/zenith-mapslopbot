package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A stand-in for the pathfinder's route in the flight tests ({@link FlightLeg#withLongHop} stops on the walk's route):
 * the shortest walk over a {@link FlightPlanner.Terrain} from the feet to the goal, or, with the goal not loaded, to the
 * reachable loaded cell nearest it (the pathfinder's best path so far ends where the loaded world ends). Feet cells
 * that stand (a standing floor, nothing over the knees, two cells of room, no fluid, no no-go cell); moves to the 8
 * neighbours up one level, level or down up to {@link #MAX_DROP}, a diagonal only past both side cells. Bounded to the
 * start's and the goal's box grown by {@link #MARGIN}.
 */
final class SimRoute {
    static final int MAX_DROP = 3;
    static final int MARGIN = 48;

    private SimRoute() {
    }

    /** The feet cells from the start to the end, in order (the start first); empty when the start cannot stand. */
    static List<Pos> route(FlightPlanner.Terrain t, double sx, double sy, double sz, int gx, int gy, int gz) {
        return route(t, sx, sy, sz, gx, gy, gz, MARGIN);
    }

    /** {@link #route} in the box grown by {@code margin}. */
    static List<Pos> route(FlightPlanner.Terrain t, double sx, double sy, double sz, int gx, int gy, int gz, int margin) {
        int x0 = (int) Math.floor(sx);
        int z0 = (int) Math.floor(sz);
        int l0 = (int) Math.floor(sy + 0.1251);
        int minX = Math.min(x0, gx) - margin;
        int maxX = Math.max(x0, gx) + margin;
        int minZ = Math.min(z0, gz) - margin;
        int maxZ = Math.max(z0, gz) + margin;
        Pos start = new Pos(x0, l0, z0);
        Map<Pos, Boolean> memo = new HashMap<>();
        java.util.function.Predicate<Pos> ok = c -> memo.computeIfAbsent(c, k -> stands(t, k));
        if (!ok.test(start)) return List.of();
        Map<Pos, Double> cost = new HashMap<>();
        Map<Pos, Pos> from = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[0], (double) b[0]));
        cost.put(start, 0.0);
        open.add(new Object[]{0.0, start});
        Pos best = start;
        double bestD = Math.hypot(gx - x0, gz - z0);
        double bestC = 0;
        while (!open.isEmpty()) {
            Object[] e = open.poll();
            double c = (double) e[0];
            Pos p = (Pos) e[1];
            if (c > cost.getOrDefault(p, Double.MAX_VALUE)) continue;
            double d = Math.hypot(gx - p.x(), gz - p.z());
            if (p.x() == gx && p.z() == gz && Math.abs(p.y() - gy) <= 1) {
                best = p;
                break;
            }
            if (d < bestD - 1e-9 || Math.abs(d - bestD) < 1e-9 && c < bestC) {
                best = p;
                bestD = d;
                bestC = c;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = p.x() + dx;
                    int nz = p.z() + dz;
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    Pos n = step(t, ok, p, nx, nz);
                    if (n == null) continue;
                    if (dx != 0 && dz != 0 && (step(t, ok, p, p.x() + dx, p.z()) == null
                        || step(t, ok, p, p.x(), p.z() + dz) == null)) {
                        continue;
                    }
                    double nc = c + (dx != 0 && dz != 0 ? Math.sqrt(2) : 1) + (n.y() > p.y() ? 0.5 : 0);
                    if (nc < cost.getOrDefault(n, Double.MAX_VALUE)) {
                        cost.put(n, nc);
                        from.put(n, p);
                        open.add(new Object[]{nc, n});
                    }
                }
            }
        }
        List<Pos> out = new ArrayList<>();
        for (Pos p = best; p != null; p = from.get(p)) out.add(p);
        Collections.reverse(out);
        return out;
    }

    /** The walk's length in blocks along {@code route} (cell centre to centre). */
    static double length(List<Pos> route) {
        double s = 0;
        for (int i = 1; i < route.size(); i++) {
            s += Math.hypot(route.get(i).x() - route.get(i - 1).x(), route.get(i).z() - route.get(i - 1).z());
        }
        return s;
    }

    /** The cell a move from {@code p} into column (nx, nz) stands in (one up, level, or down up to MAX_DROP), or null. */
    private static Pos step(FlightPlanner.Terrain t, java.util.function.Predicate<Pos> ok, Pos p, int nx, int nz) {
        if (!t.loaded(nx, nz)) return null;
        for (int l = p.y() + 1; l >= p.y() - MAX_DROP; l--) {
            Pos n = new Pos(nx, l, nz);
            if (!ok.test(n)) continue;
            // the way there: room over the higher of the two levels in both columns
            int top = Math.max(l, p.y());
            if (t.top(nx, top + 1, nz) > 0 || t.top(p.x(), top + 1, p.z()) > 0) return null;
            for (int y = l + 1; y <= p.y() + 1; y++) if (t.top(nx, y, nz) > 0) return null; // a drop: the column clear above
            return n;
        }
        return null;
    }

    static boolean stands(FlightPlanner.Terrain t, Pos c) {
        if (!t.loaded(c.x(), c.z())) return false;
        if (Double.isNaN(FlightPlanner.surface(t, c.x(), c.z(), c.y()))) return false;
        if (t.top(c.x(), c.y() + 1, c.z()) > 0) return false;
        for (int y = c.y() - 1; y <= c.y() + 1; y++) {
            if (t.fluid(c.x(), y, c.z()) || t.forbidden(c.x(), y, c.z())) return false;
        }
        return true;
    }
}
