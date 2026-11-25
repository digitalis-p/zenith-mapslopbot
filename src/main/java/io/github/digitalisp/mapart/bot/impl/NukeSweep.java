package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * The lanes of the fast nuker's sweep ({@link NukeRun}): straight walking lines across a flat set of targets, each
 * covering the columns within {@code halfWidth} of its line, in serpentine order. Pure.
 *
 * <p>Lines run along Z (constant x; the bot walks north-south) or along X. Line {@code k} lies on column
 * {@code min + w + k(2w+1)} (the last one moved back onto the targets), so the lanes tile the targets' extent without
 * overlap. A lane spans its own targets' extent along the line, shortened by {@code trim} at each end (the bot reaches
 * that far beyond where it stops), and is skipped when it holds no target. The first lane end is the one nearest to
 * where the bot stands; from there the lanes follow in order, each walked the other way than the one before, joined
 * at the end of the previous lane: along the cross axis first, then along the next line.
 *
 * <p>Waypoints are feet positions (x, z) at cell centres.
 */
final class NukeSweep {
    private NukeSweep() { }

    enum Axis {
        /** Lines of constant x: the bot walks along Z (north-south). */
        Z,
        /** Lines of constant z: the bot walks along X (east-west). */
        X;

        static @Nullable Axis of(@Nullable String s) {
            if (s == null) return null;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "X", "EW", "EAST_WEST" -> X;
                case "Z", "NS", "NORTH_SOUTH" -> Z;
                default -> null;
            };
        }
    }

    /** One lane: its line (cell coordinate across the walk) and the cell range along it, in walking order. */
    record Lane(int line, int from, int to) {
        int length() {
            return Math.abs(to - from);
        }
    }

    /**
     * The sweep: axis, lanes in walking order, and the waypoints (feet x, z) joining them. A tour ({@link #tour}) also
     * has {@code covers}, the targets each of its lanes (lane segments) serves, and {@code segmentOf}, the lane each
     * waypoint belongs to; null for the serpentine.
     */
    record Plan(Axis axis, List<Lane> lanes, List<double[]> waypoints, @Nullable List<Set<Pos>> covers,
                int @Nullable [] segmentOf) {
        Plan(Axis axis, List<Lane> lanes, List<double[]> waypoints) {
            this(axis, lanes, waypoints, null, null);
        }

        /** A tour over lane segments ({@link #tour}), not the serpentine. */
        boolean isTour() {
            return covers != null && segmentOf != null;
        }

        /** Horizontal length of the walk from the first waypoint to the last (blocks). */
        double length() {
            double sum = 0;
            for (int i = 1; i < waypoints.size(); i++) {
                double[] a = waypoints.get(i - 1);
                double[] b = waypoints.get(i);
                sum += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            return sum;
        }

        /**
         * The whole walk on the art layer: from the feet at ({@code fromX}, {@code fromZ}) to the first waypoint, the
         * plan, and from its last waypoint to ({@code endX}, {@code endZ}) (NaN: no end point).
         */
        double walk(double fromX, double fromZ, double endX, double endZ) {
            if (waypoints.isEmpty()) return 0;
            double[] a = waypoints.getFirst();
            double[] b = waypoints.getLast();
            double out = Double.isNaN(endX) || Double.isNaN(endZ) ? 0 : Math.hypot(b[0] - endX, b[1] - endZ);
            return Math.hypot(a[0] - fromX, a[1] - fromZ) + length() + out;
        }

        /**
         * Indices of the waypoints where a lane ends and the walk turns across to the next line: every lane end but
         * the last lane's (the end of the sweep). A tour: the last waypoint of each lane segment but the last one.
         */
        java.util.Set<Integer> laneEnds() {
            java.util.Set<Integer> out = new java.util.HashSet<>();
            int[] seg = segmentOf;
            if (seg != null) {
                for (int i = 0; i + 1 < seg.length; i++) if (seg[i] != seg[i + 1]) out.add(i);
                return out;
            }
            int across = axis == Axis.Z ? 0 : 1; // the line's coordinate in a waypoint (x for lines along Z)
            for (int i = 0; i + 1 < waypoints.size(); i++) {
                if (Math.abs(waypoints.get(i)[across] - waypoints.get(i + 1)[across]) > 1e-9) out.add(i);
            }
            return out;
        }
    }

    /**
     * The axis for {@code wanted} (null: AUTO): the one with fewer lanes, i.e. lines along the longer side of the
     * targets; on a tie, {@code covered} decides when given (the axis whose lanes keep the larger share of their walk
     * inside it, cells (x, z) tested), else Z.
     */
    static Axis axis(Collection<Pos> targets, @Nullable Axis wanted, int halfWidth,
                     @Nullable BiPredicate<Integer, Integer> covered) {
        if (wanted != null || targets.isEmpty()) return wanted == null ? Axis.Z : wanted;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Pos p : targets) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        int w = 2 * Math.max(1, halfWidth) + 1;
        int lanesZ = (maxX - minX) / w + 1; // lines of constant x
        int lanesX = (maxZ - minZ) / w + 1;
        if (lanesZ != lanesX) return lanesZ < lanesX ? Axis.Z : Axis.X;
        if (covered == null) return Axis.Z;
        double z = coveredShare(plan(targets, Axis.Z, halfWidth, 0, minX, minZ), covered);
        double x = coveredShare(plan(targets, Axis.X, halfWidth, 0, minX, minZ), covered);
        return x > z + 1e-9 ? Axis.X : Axis.Z;
    }

    /** Share of the lanes' walking length (cells on the lines) for which {@code covered} holds. */
    static double coveredShare(Plan plan, BiPredicate<Integer, Integer> covered) {
        long all = 0;
        long in = 0;
        for (Lane l : plan.lanes()) {
            int step = l.to() >= l.from() ? 1 : -1;
            for (int v = l.from(); ; v += step) {
                int x = plan.axis() == Axis.Z ? l.line() : v;
                int z = plan.axis() == Axis.Z ? v : l.line();
                all++;
                if (covered.test(x, z)) in++;
                if (v == l.to()) break;
            }
        }
        return all == 0 ? 1.0 : (double) in / all;
    }

    /**
     * The sweep over {@code targets} (any Y; only x and z count) with lines along {@code axis}, lanes
     * {@code 2·halfWidth + 1} wide, each shortened by {@code trim} cells at both ends (never below one cell), starting
     * at the lane end nearest to the feet at ({@code fromX}, {@code fromZ}).
     */
    static Plan plan(Collection<Pos> targets, Axis axis, int halfWidth, int trim, double fromX, double fromZ) {
        return plan(targets, axis, halfWidth, trim, fromX, fromZ, Double.NaN, Double.NaN);
    }

    /**
     * {@link #plan(Collection, Axis, int, int, double, double)} that ends near ({@code endX}, {@code endZ}) (the way
     * off the art layer, {@code site.platforms[].nukeEndCorner}): of the four lane ends to start at, the one with the
     * shortest walk to the start plus from the sweep's last waypoint to that point. NaN: the lane end nearest to the
     * feet, as without it.
     */
    static Plan plan(Collection<Pos> targets, Axis axis, int halfWidth, int trim, double fromX, double fromZ,
                     double endX, double endZ) {
        int w = Math.max(1, halfWidth);
        if (targets.isEmpty()) return new Plan(axis, List.of(), List.of());
        // u: across the walk (the line's coordinate), v: along it
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        for (Pos p : targets) {
            int u = u(p, axis);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
        }
        TreeMap<Integer, int[]> extent = new TreeMap<>(); // line → {min v, max v}
        int width = 2 * w + 1;
        for (Pos p : targets) {
            int k = (u(p, axis) - minU) / width;
            int line = Math.min(minU + w + k * width, maxU);
            int v = v(p, axis);
            extent.merge(line, new int[]{v, v}, (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        List<int[]> lines = new ArrayList<>(); // {line, lo, hi}
        for (var e : extent.entrySet()) {
            int lo = e.getValue()[0];
            int hi = e.getValue()[1];
            int t = Math.max(0, Math.min(trim, (hi - lo) / 2));
            lines.add(new int[]{e.getKey(), lo + t, hi - t});
        }
        // start: the lane end nearest to the bot (with an end point: the shortest walk to the start and from the end),
        // lanes in order from that side
        double fu = axis == Axis.Z ? fromX : fromZ;
        double fv = axis == Axis.Z ? fromZ : fromX;
        boolean toEnd = !Double.isNaN(endX) && !Double.isNaN(endZ);
        int[] first = lines.getFirst();
        int[] last = lines.getLast();
        double[][] starts = {
            {first[0], first[1]}, {first[0], first[2]}, {last[0], last[1]}, {last[0], last[2]}};
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < starts.length; i++) {
            double d = Math.hypot(starts[i][0] + 0.5 - fu, starts[i][1] + 0.5 - fv);
            if (toEnd) {
                double[] e = serpentine(lines, axis, i).waypoints().getLast();
                d += Math.hypot(e[0] - endX, e[1] - endZ);
            }
            if (d < bestD - 1e-9) {
                bestD = d;
                best = i;
            }
        }
        return serpentine(lines, axis, best);
    }

    /** The lanes of {@code lines} ({line, lo, hi}, in order across) walked from start {@code best} (0-3, as above). */
    private static Plan serpentine(List<int[]> ordered, Axis axis, int best) {
        boolean reverseLanes = best >= 2;
        boolean startHigh = best % 2 == 1;
        List<int[]> lines = new ArrayList<>(ordered);
        if (reverseLanes) java.util.Collections.reverse(lines);
        List<Lane> lanes = new ArrayList<>();
        List<double[]> wps = new ArrayList<>();
        boolean high = startHigh;
        for (int i = 0; i < lines.size(); i++) {
            int[] l = lines.get(i);
            int from = high ? l[2] : l[1];
            int to = high ? l[1] : l[2];
            if (i > 0) {
                Lane prev = lanes.getLast();
                wps.add(point(axis, l[0], prev.to())); // across at the end row of the previous lane
            }
            wps.add(point(axis, l[0], from));
            if (to != from) wps.add(point(axis, l[0], to));
            lanes.add(new Lane(l[0], from, to));
            high = !high;
        }
        // drop consecutive duplicates (a corner on the next lane's start)
        List<double[]> clean = new ArrayList<>();
        for (double[] p : wps) {
            if (!clean.isEmpty()) {
                double[] q = clean.getLast();
                if (Math.abs(q[0] - p[0]) < 1e-9 && Math.abs(q[1] - p[1]) < 1e-9) continue;
            }
            clean.add(p);
        }
        return new Plan(axis, lanes, clean);
    }

    /** Lane segments above this many get no 2-opt (nearest first only): a pass is quadratic. */
    static final int TOUR_OPT_MAX = 1500;
    /** 2-opt passes at most (each improves the tour or ends the search). */
    static final int TOUR_PASSES = 25;

    /**
     * A tour over lane segments ({@code build.nukeTour}): the lanes of {@link #plan} cut where {@code gap} or more
     * rows in a row along the line hold no target (each piece shortened by {@code trim} at both ends, as a lane), then
     * walked in the order a nearest-first walk from the feet gives, improved by 2-opt (a stretch of the order reversed,
     * each segment in it walked the other way) on the whole walk: feet to the first segment, the segments and the
     * straight joins between them, and the last one's end to ({@code endX}, {@code endZ}) when given (NaN: none). Sparse art
     * (live j0015, 110-945 cobbled deepslate blocks a map, in patches) leaves most of a lane empty: the serpentine
     * walks all of it, the tour only the pieces with targets. The plan's {@code covers} lists each segment's targets
     * (those of its lane between its first and last row), so the walk can skip a segment whose targets all broke
     * before it got there (from a join or a neighbouring segment). Pure.
     */
    static Plan tour(Collection<Pos> targets, Axis axis, int halfWidth, int trim, int gap, double fromX, double fromZ,
                     double endX, double endZ) {
        int w = Math.max(1, halfWidth);
        if (targets.isEmpty()) return new Plan(axis, List.of(), List.of(), List.of(), new int[0]);
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        for (Pos p : targets) {
            minU = Math.min(minU, u(p, axis));
            maxU = Math.max(maxU, u(p, axis));
        }
        int width = 2 * w + 1;
        // line -> v -> its targets in that row of the lane
        TreeMap<Integer, TreeMap<Integer, List<Pos>>> rows = new TreeMap<>();
        for (Pos p : targets) {
            int k = (u(p, axis) - minU) / width;
            int line = Math.min(minU + w + k * width, maxU);
            rows.computeIfAbsent(line, l -> new TreeMap<>()).computeIfAbsent(v(p, axis), x -> new ArrayList<>()).add(p);
        }
        List<int[]> segs = new ArrayList<>(); // {line, lo, hi} after the trim
        List<Set<Pos>> covers = new ArrayList<>();
        int g = Math.max(1, gap);
        for (var line : rows.entrySet()) {
            int lo = Integer.MIN_VALUE;
            int prev = Integer.MIN_VALUE;
            Set<Pos> cov = new LinkedHashSet<>();
            for (var row : line.getValue().entrySet()) {
                int v = row.getKey();
                if (lo != Integer.MIN_VALUE && v - prev > g) {
                    addSegment(segs, covers, line.getKey(), lo, prev, trim, cov);
                    cov = new LinkedHashSet<>();
                    lo = Integer.MIN_VALUE;
                }
                if (lo == Integer.MIN_VALUE) lo = v;
                prev = v;
                cov.addAll(row.getValue());
            }
            addSegment(segs, covers, line.getKey(), lo, prev, trim, cov);
        }
        int n = segs.size();
        double[][] lo = new double[n][];
        double[][] hi = new double[n][];
        double[] inner = new double[n];
        for (int i = 0; i < n; i++) {
            int[] s = segs.get(i);
            lo[i] = point(axis, s[0], s[1]);
            hi[i] = point(axis, s[0], s[2]);
            inner[i] = Math.abs(s[2] - s[1]);
        }
        // nearest first from the feet: the nearer end of the nearest segment left
        int[] ord = new int[n];
        boolean[] rev = new boolean[n]; // walked from hi to lo
        boolean[] used = new boolean[n];
        double px = fromX;
        double pz = fromZ;
        for (int k = 0; k < n; k++) {
            int best = -1;
            boolean bestRev = false;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                double dl = Math.hypot(lo[i][0] - px, lo[i][1] - pz);
                double dh = Math.hypot(hi[i][0] - px, hi[i][1] - pz);
                if (dl < bestD - 1e-9) {
                    bestD = dl;
                    best = i;
                    bestRev = false;
                }
                if (dh < bestD - 1e-9) {
                    bestD = dh;
                    best = i;
                    bestRev = true;
                }
            }
            used[best] = true;
            ord[k] = best;
            rev[k] = bestRev;
            double[] e = bestRev ? lo[best] : hi[best];
            px = e[0];
            pz = e[1];
        }
        if (n <= TOUR_OPT_MAX) twoOpt(ord, rev, lo, hi, fromX, fromZ, endX, endZ);
        List<Lane> lanes = new ArrayList<>(n);
        List<double[]> wps = new ArrayList<>();
        List<Set<Pos>> cov = new ArrayList<>(n);
        List<Integer> segOf = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int[] s = segs.get(ord[k]);
            int from = rev[k] ? s[2] : s[1];
            int to = rev[k] ? s[1] : s[2];
            lanes.add(new Lane(s[0], from, to));
            cov.add(covers.get(ord[k]));
            wps.add(point(axis, s[0], from));
            segOf.add(k);
            if (to != from) {
                wps.add(point(axis, s[0], to));
                segOf.add(k);
            }
        }
        int[] seg = new int[segOf.size()];
        for (int i = 0; i < seg.length; i++) seg[i] = segOf.get(i);
        return new Plan(axis, lanes, wps, cov, seg);
    }

    /** One segment of line {@code line} from row {@code lo} to {@code hi} (shortened by the trim) and its targets. */
    private static void addSegment(List<int[]> segs, List<Set<Pos>> covers, int line, int lo, int hi, int trim,
                                   Set<Pos> cov) {
        if (lo == Integer.MIN_VALUE || cov.isEmpty()) return;
        int t = Math.max(0, Math.min(trim, (hi - lo) / 2));
        segs.add(new int[]{line, lo + t, hi - t});
        covers.add(cov);
    }

    /**
     * 2-opt on a walk over segments: reversing positions i..j (each segment in it walked the other way) changes only the
     * two joins at its ends. First improvement, until a pass finds none or {@link #TOUR_PASSES}. The walk starts at
     * (fx, fz) and ends at (ex, ez) (NaN: anywhere). Points are segments with {@code lo == hi} ({@link NukeLeftovers}).
     */
    static void twoOpt(int[] ord, boolean[] rev, double[][] lo, double[][] hi, double fx, double fz,
                       double ex, double ez) {
        int n = ord.length;
        boolean toEnd = !Double.isNaN(ex) && !Double.isNaN(ez);
        double[] from = {fx, fz};
        double[] end = {ex, ez};
        for (int pass = 0; pass < TOUR_PASSES; pass++) {
            boolean improved = false;
            for (int i = 0; i < n; i++) {
                double[] before = i == 0 ? from : exit(ord, rev, lo, hi, i - 1);
                double[] entryI = entry(ord, rev, lo, hi, i);
                double dBefore = dist(before, entryI);
                for (int j = i; j < n; j++) {
                    double[] exitJ = exit(ord, rev, lo, hi, j);
                    double[] after = j + 1 < n ? entry(ord, rev, lo, hi, j + 1) : toEnd ? end : null;
                    double old = dBefore + (after == null ? 0 : dist(exitJ, after));
                    double now = dist(before, exitJ) + (after == null ? 0 : dist(entryI, after));
                    if (now < old - 1e-6) {
                        for (int a = i, b = j; a < b; a++, b--) {
                            int o = ord[a];
                            ord[a] = ord[b];
                            ord[b] = o;
                            boolean r = rev[a];
                            rev[a] = rev[b];
                            rev[b] = r;
                        }
                        for (int k = i; k <= j; k++) rev[k] = !rev[k];
                        improved = true;
                        entryI = entry(ord, rev, lo, hi, i);
                        dBefore = dist(before, entryI);
                    }
                }
            }
            if (!improved) return;
        }
    }

    private static double[] entry(int[] ord, boolean[] rev, double[][] lo, double[][] hi, int k) {
        return rev[k] ? hi[ord[k]] : lo[ord[k]];
    }

    private static double[] exit(int[] ord, boolean[] rev, double[][] lo, double[][] hi, int k) {
        return rev[k] ? lo[ord[k]] : hi[ord[k]];
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** Targets per cell of their bounding box (x, z): 1 when every cell of it holds one. */
    static double density(Collection<Pos> targets) {
        if (targets.isEmpty()) return 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Long> cells = new java.util.HashSet<>();
        for (Pos p : targets) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
            cells.add(((long) p.x() << 32) ^ (p.z() & 0xffffffffL));
        }
        return (double) cells.size() / ((double) (maxX - minX + 1) * (maxZ - minZ + 1));
    }

    /**
     * The corner {@code name} ("NW", "NE", "SW", "SE", any case; north is −z, east +x) of the {@code size}-wide square
     * from (minX, minZ), as feet (x, z); null for a blank or unknown name.
     */
    static double @Nullable [] corner(@Nullable String name, int minX, int minZ, int size) {
        if (name == null) return null;
        return switch (name.strip().toUpperCase(Locale.ROOT)) {
            case "NW" -> new double[]{minX, minZ};
            case "NE" -> new double[]{minX + size, minZ};
            case "SW" -> new double[]{minX, minZ + size};
            case "SE" -> new double[]{minX + size, minZ + size};
            default -> null;
        };
    }

    private static int u(Pos p, Axis axis) {
        return axis == Axis.Z ? p.x() : p.z();
    }

    private static int v(Pos p, Axis axis) {
        return axis == Axis.Z ? p.z() : p.x();
    }

    /** Feet position (x, z) at the centre of the cell on line {@code line}, {@code v} along it. */
    private static double[] point(Axis axis, int line, int v) {
        return axis == Axis.Z ? new double[]{line + 0.5, v + 0.5} : new double[]{v + 0.5, line + 0.5};
    }
}
