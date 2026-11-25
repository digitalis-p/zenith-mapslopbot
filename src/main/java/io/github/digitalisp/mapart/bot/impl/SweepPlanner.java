package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.ContainerVisit;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order and the stand cells for opening many containers with the least walking: a sweep along the storage, several
 * containers from each stand cell, instead of the nearest container
 * next. Pure, like {@link StandSpots}, whose rules it uses; the world comes through {@link StandSpots.World}, each
 * container's sight through its own {@link StandSpots.Sight}.
 *
 * <ol>
 *   <li><b>Coverage.</b> For every container, the cells it can be opened from: the cells {@link StandSpots#search}
 *       would consider (feet {@link StandSpots#BELOW} below to {@link StandSpots#ABOVE} above a block, horizontally
 *       within the reach; standable, {@linkplain StandSpots.World#allowed allowed}), whose eye over the cell centre
 *       sees a face centre within the reach less {@link StandSpots#CENTRE_MARGIN}, and that the bot can walk to: one
 *       reachability flood from the bot over all the containers ({@link StandSpots#pathCosts}; when it cannot tell,
 *       every cell counts). {@link #step} does this for a few containers at a time, so a big sorter is spread over
 *       ticks.</li>
 *   <li><b>Stand cells</b> (set cover). The sweep runs along the horizontal axis on which the containers spread most,
 *       from the end nearest the bot. Each container can be served up to the furthest cell along the sweep that sees
 *       it. Repeatedly the uncovered container whose furthest cell comes first is the anchor, and the cell that sees
 *       it and the most uncovered containers is chosen (ties: further along, nearer by walking). On a line of
 *       containers this is the optimal interval stabbing; on the moss sorter about one cell per five columns.</li>
 *   <li><b>Walking order.</b> The chosen cells form lanes (one walkway: feet within {@link #LANE_LEVEL} of each other
 *       and at most {@link #LANE_PERPENDICULAR} apart across the axis). The lane nearest the bot is walked from its
 *       nearer end to the other; then the lane whose end is nearest, from that end (serpentine).</li>
 *   <li><b>Containers per stop.</b> A column (the containers above each other) goes to the first stop that sees all
 *       of it; the rest to the first stop that sees each. At a stop the columns follow the walking direction and each
 *       column is opened from the end nearer the last container opened (top-down, then bottom-up: little turning).</li>
 * </ol>
 * Containers whose blocks are not loaded, or with no cell, come last without a stand cell, nearest first: the
 * {@link Approach} searches for them as before.
 *
 * <p><b>End point.</b> When the walk goes on to a known cell afterwards ({@code then}: a restock's next lane start), the
 * plan is built for both directions along the axis: the one above, and the opposite one walked from the other end
 * (the lane end furthest back in that direction first, then serpentine as above). The one with the shorter walk from
 * the bot through the stops to {@code then} is kept (straight lines, as {@link Plan#walk}; ties: the one above).
 * Without {@code then} only the one above is built.
 */
public final class SweepPlanner {
    /** Containers whose coverage the ZenithProxy task works out per tick ({@link #step}). */
    public static final int CONTAINERS_PER_TICK = 16;
    /** Stand cells at most this far apart across the sweep axis belong to one walkway (lane). */
    static final int LANE_PERPENDICULAR = 2;
    /** ... and with feet at most this far apart in height. */
    static final double LANE_LEVEL = 1.0;

    /**
     * A container to open: its position (the stock-index key), the blocks that open it (both halves), its sight, and
     * the cells not to open it from ({@code avoid}: they did not see it after all before).
     */
    public record Target(Pos container, List<Pos> blocks, StandSpots.Sight sight, Set<Pos> avoid) {
        public Target {
            blocks = List.copyOf(blocks);
            avoid = Set.copyOf(avoid);
        }

        public Target(Pos container, List<Pos> blocks, StandSpots.Sight sight) {
            this(container, blocks, sight, Set.of());
        }
    }

    /** A stand cell (feet) and the containers opened from it, in opening order. */
    public record Stop(Pos cell, List<Pos> containers) {
        public Stop {
            containers = List.copyOf(containers);
        }
    }

    /** Stops in walking order, then the containers no stand cell could be planned for. */
    public record Plan(List<Stop> stops, List<Pos> unplanned) {
        public Plan {
            stops = List.copyOf(stops);
            unplanned = List.copyOf(unplanned);
        }

        /** Every container once, in opening order: the stops' with their cell, then the unplanned ones with none. */
        public List<ContainerVisit> visits() {
            List<ContainerVisit> out = new ArrayList<>();
            for (Stop s : stops) for (Pos c : s.containers()) out.add(new ContainerVisit(c, s.cell()));
            for (Pos c : unplanned) out.add(new ContainerVisit(c, null));
            return out;
        }

        /** Straight-line horizontal walk from (fromX, fromZ) through the stop cell centres in order (tests, logs). */
        public double walk(double fromX, double fromZ) {
            double d = 0;
            double x = fromX;
            double z = fromZ;
            for (Stop s : stops) {
                double nx = s.cell().x() + 0.5;
                double nz = s.cell().z() + 0.5;
                d += Math.hypot(nx - x, nz - z);
                x = nx;
                z = nz;
            }
            return d;
        }
    }

    private final StandSpots.World w;
    private final List<Target> targets;
    private final double reach;
    private final double near;
    private final double botX;
    private final double botY;
    private final double botZ;
    /** Where the walk goes after the last stop, or null (no end point). */
    private final @Nullable Pos then;
    /** Feet height per cell looked at (NaN: cannot stand there), shared by all containers. */
    private final Map<Pos, Double> feet = new HashMap<>();
    /** Per target (same index): the cells it can be opened from, in enumeration order. */
    private final List<List<Pos>> covering = new ArrayList<>();
    /** Feet height of every cell that opens some container. */
    private final Map<Pos, Double> cellFeet = new HashMap<>();
    private boolean flooded;
    private @Nullable Map<Pos, Double> costs;
    private int next;
    private @Nullable Plan plan;

    /**
     * @param targets containers to open (duplicates by container are dropped, the first kept)
     * @param reach   the reach the approach uses ({@code build.reach})
     * @param botX    the bot's feet now
     */
    public SweepPlanner(StandSpots.World w, List<Target> targets, double reach, double botX, double botY, double botZ) {
        this(w, targets, reach, botX, botY, botZ, null);
    }

    /**
     * @param then where the walk goes after the last stop (a restock: the start of the next lane), or null; the sweep
     *             then runs in the direction that makes bot → stops → {@code then} shortest
     */
    public SweepPlanner(StandSpots.World w, List<Target> targets, double reach, double botX, double botY, double botZ,
                        @Nullable Pos then) {
        this.w = w;
        this.then = then;
        Map<Pos, Target> byPos = new LinkedHashMap<>();
        for (Target t : targets) byPos.putIfAbsent(t.container(), t);
        this.targets = List.copyOf(byPos.values());
        this.reach = reach;
        this.near = reach - StandSpots.CENTRE_MARGIN;
        this.botX = botX;
        this.botY = botY;
        this.botZ = botZ;
    }

    /** All at once. */
    public static Plan plan(StandSpots.World w, List<Target> targets, double reach, double botX, double botY, double botZ) {
        return new SweepPlanner(w, targets, reach, botX, botY, botZ).plan();
    }

    /** All at once, the walk going on to {@code then} afterwards (null: none). */
    public static Plan plan(StandSpots.World w, List<Target> targets, double reach, double botX, double botY, double botZ,
                            @Nullable Pos then) {
        return new SweepPlanner(w, targets, reach, botX, botY, botZ, then).plan();
    }

    /**
     * {@link Plan#walk} from the bot plus the straight line from the last stop to {@code then} (no stops: 0). The
     * figure the end point is chosen by; without {@code then}, the walk alone.
     */
    public double walkTo(Plan p) {
        double d = p.walk(botX, botZ);
        if (then != null && !p.stops().isEmpty()) {
            Pos last = p.stops().getLast().cell();
            d += Math.hypot(then.x() - last.x(), then.z() - last.z());
        }
        return d;
    }

    /**
     * Works out the cells that open up to {@code n} more containers (the reachability flood on the first call).
     * True when every container is done.
     */
    public boolean step(int n) {
        if (!flooded) {
            flooded = true;
            List<Pos> all = new ArrayList<>();
            for (Target t : targets) all.addAll(t.blocks());
            costs = all.isEmpty() ? null : StandSpots.pathCosts(w, all, botX, botY, botZ);
        }
        int end = Math.min(targets.size(), next + Math.max(0, n));
        while (next < end) covering.add(cover(targets.get(next++)));
        return next >= targets.size();
    }

    /** The plan; runs the remaining {@link #step}s first. */
    public Plan plan() {
        if (plan == null) {
            while (!step(CONTAINERS_PER_TICK)) {
                // coverage of the rest
            }
            plan = build();
        }
        return plan;
    }

    // ------------------------------------------------------------------------------------------------ coverage

    private double feetY(Pos c) {
        Double v = feet.get(c);
        if (v == null) {
            v = StandSpots.feetY(w, c.x(), c.y(), c.z());
            feet.put(c, v);
        }
        return v;
    }

    /** The cells {@code t} can be opened from (StandSpots' enumeration and rules), reachable ones only when known. */
    private List<Pos> cover(Target t) {
        List<Pos> blocks = t.blocks();
        if (blocks.isEmpty()) return List.of();
        for (Pos b : blocks) if (!w.isLoaded(b)) return List.of();
        int r = (int) Math.ceil(reach);
        double eyeH = t.sight().eyeHeight();
        Set<Pos> seen = new HashSet<>();
        List<Pos> out = new ArrayList<>();
        for (Pos b : blocks) {
            for (int dy = -StandSpots.BELOW; dy <= StandSpots.ABOVE; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        Pos c = b.offset(dx, dy, dz);
                        if (!seen.add(c) || blocks.contains(c) || blocks.contains(c.below())) continue;
                        if (t.avoid().contains(c)) continue;
                        double fy = feetY(c);
                        if (Double.isNaN(fy) || !w.allowed(c) || !StandSpots.walkableFloor(w, c)) continue;
                        if (costs != null && !costs.containsKey(c)) continue;
                        double ex = c.x() + 0.5;
                        double ey = fy + eyeH;
                        double ez = c.z() + 0.5;
                        if (!StandSpots.anyInReach(ex, ey, ez, blocks, near)) continue;
                        if (!t.sight().visibleFrom(ex, ey, ez, near)) continue;
                        out.add(c);
                        cellFeet.put(c, fy);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ plan

    private Plan build() {
        int n = targets.size();
        if (n == 0) return new Plan(List.of(), List.of());
        Geometry g = geometry();
        Plan plan = build(g, false);
        if (then == null) return plan;
        Plan other = build(g.reversed(), true);
        return walkTo(other) < walkTo(plan) - 1e-9 ? other : plan;
    }

    /** The plan for sweep direction {@code g}; {@code fromFarEnd}: the walk starts at the lane end furthest back. */
    private Plan build(Geometry g, boolean fromFarEnd) {
        int n = targets.size();
        // the containers each chosen-to-be cell serves (cells in first-seen order)
        Map<Pos, List<Integer>> served = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            for (Pos c : covering.get(i)) served.computeIfAbsent(c, k -> new ArrayList<>()).add(i);
        }

        List<Pos> chosen = cover(g, served);
        List<Lane> lanes = lanes(g, chosen);
        List<Pos> order = new ArrayList<>();
        List<Integer> signs = new ArrayList<>();
        walkLanes(g, lanes, order, signs, fromFarEnd);

        int[] stopOf = assign(order, served);
        List<Stop> stops = new ArrayList<>();
        double lastY = botY + Aim.EYE_HEIGHT;
        for (int s = 0; s < order.size(); s++) {
            List<Integer> mine = new ArrayList<>();
            for (int i = 0; i < n; i++) if (stopOf[i] == s) mine.add(i);
            if (mine.isEmpty()) continue;
            List<Pos> opened = new ArrayList<>();
            lastY = openingOrder(g, mine, signs.get(s), lastY, opened);
            stops.add(new Stop(order.get(s), opened));
        }
        return new Plan(stops, unplanned(stops));
    }

    /** The sweep axis (x or z, the containers' larger horizontal spread) and the direction from the bot's end. */
    private record Geometry(boolean alongX, int dir) {
        int axis(Pos p) {
            return alongX ? p.x() : p.z();
        }

        int perpendicular(Pos p) {
            return alongX ? p.z() : p.x();
        }

        /** Position along the sweep, growing in the planned direction. */
        int key(Pos p) {
            return dir * axis(p);
        }

        /** The same axis, the other direction. */
        Geometry reversed() {
            return new Geometry(alongX, -dir);
        }
    }

    private Geometry geometry() {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Target t : targets) {
            Pos p = t.container();
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        boolean alongX = maxX - minX >= maxZ - minZ;
        double b = alongX ? botX : botZ;
        int lo = alongX ? minX : minZ;
        int hi = alongX ? maxX : maxZ;
        int dir = Math.abs(b - (lo + 0.5)) <= Math.abs(b - (hi + 0.5)) ? 1 : -1;
        return new Geometry(alongX, dir);
    }

    /** Anchor greedy: the uncovered container whose furthest cell comes first, served by its best cell. */
    private List<Pos> cover(Geometry g, Map<Pos, List<Integer>> served) {
        int n = targets.size();
        boolean[] covered = new boolean[n];
        int[] hi = new int[n];
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Pos> cs = covering.get(i);
            if (cs.isEmpty()) continue;
            int h = Integer.MIN_VALUE;
            for (Pos c : cs) h = Math.max(h, g.key(c));
            hi[i] = h;
            open.add(i);
        }
        Comparator<Integer> anchorOrder = Comparator.<Integer>comparingInt(i -> hi[i])
            .thenComparingInt(i -> g.key(targets.get(i).container()))
            .thenComparingInt(i -> -targets.get(i).container().y())
            .thenComparingInt(i -> targets.get(i).container().x())
            .thenComparingInt(i -> targets.get(i).container().z());
        List<Pos> chosen = new ArrayList<>();
        int left = open.size();
        while (left > 0) {
            Integer anchor = null;
            for (int i : open) if (!covered[i] && (anchor == null || anchorOrder.compare(i, anchor) < 0)) anchor = i;
            Pos a = targets.get(anchor).container();
            Pos best = null;
            int bestGain = -1;
            for (Pos c : covering.get(anchor)) {
                int gain = 0;
                for (int j : served.get(c)) if (!covered[j]) gain++;
                if (best == null || better(g, c, gain, best, bestGain, a)) {
                    best = c;
                    bestGain = gain;
                }
            }
            chosen.add(best);
            for (int j : served.get(best)) {
                if (!covered[j]) {
                    covered[j] = true;
                    left--;
                }
            }
        }
        return chosen;
    }

    /** Cell {@code c} (serving {@code gain} uncovered) beats {@code b}: more, further along, nearer by walking. */
    private boolean better(Geometry g, Pos c, int gain, Pos b, int bGain, Pos anchor) {
        if (gain != bGain) return gain > bGain;
        if (g.key(c) != g.key(b)) return g.key(c) > g.key(b);
        if (costs != null) {
            double cc = costs.getOrDefault(c, Double.POSITIVE_INFINITY);
            double cb = costs.getOrDefault(b, Double.POSITIVE_INFINITY);
            if (cc != cb) return cc < cb;
        }
        long dc = distSq(c, anchor);
        long db = distSq(b, anchor);
        if (dc != db) return dc < db;
        if (c.y() != b.y()) return c.y() < b.y();
        if (c.x() != b.x()) return c.x() < b.x();
        return c.z() < b.z();
    }

    private static long distSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** A walkway: its cells sorted along the axis. */
    private record Lane(List<Pos> cells) {
        Pos first() {
            return cells.getFirst();
        }

        Pos last() {
            return cells.getLast();
        }
    }

    /** Chosen cells grouped into walkways (transitively: feet close in height, close across the axis). */
    private List<Lane> lanes(Geometry g, List<Pos> chosen) {
        int m = chosen.size();
        int[] parent = new int[m];
        for (int i = 0; i < m; i++) parent[i] = i;
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                Pos a = chosen.get(i);
                Pos b = chosen.get(j);
                if (Math.abs(cellFeet.get(a) - cellFeet.get(b)) <= LANE_LEVEL + 1e-9
                    && Math.abs(g.perpendicular(a) - g.perpendicular(b)) <= LANE_PERPENDICULAR) {
                    parent[root(parent, i)] = root(parent, j);
                }
            }
        }
        Map<Integer, List<Pos>> groups = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) groups.computeIfAbsent(root(parent, i), k -> new ArrayList<>()).add(chosen.get(i));
        List<Lane> out = new ArrayList<>();
        for (List<Pos> cells : groups.values()) {
            cells.sort(Comparator.comparingInt(g::axis).thenComparingInt(g::perpendicular).thenComparingInt(Pos::y));
            out.add(new Lane(List.copyOf(cells)));
        }
        return out;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /**
     * Walking order: the lane holding the cell nearest the bot first, from its nearer end ({@code fromFarEnd}: the lane
     * whose start in the sweep direction comes furthest back, from that start; ties: nearer the bot); then the lane
     * whose end is nearest the last cell, from that end. {@code signs}: per cell, +1 when its lane is walked up the
     * axis, else -1.
     */
    private void walkLanes(Geometry g, List<Lane> lanes, List<Pos> order, List<Integer> signs, boolean fromFarEnd) {
        List<Lane> left = new ArrayList<>(lanes);
        Pos at = null;
        while (!left.isEmpty()) {
            Lane pick = null;
            boolean fromFirst = true;
            double best = Double.POSITIVE_INFINITY;
            if (at == null && fromFarEnd) {
                int bestKey = Integer.MAX_VALUE;
                for (Lane l : left) {
                    Pos start = g.dir() > 0 ? l.first() : l.last();
                    int k = g.key(start);
                    double d = fromBot(start);
                    if (k < bestKey || (k == bestKey && d < best)) {
                        bestKey = k;
                        best = d;
                        pick = l;
                    }
                }
                fromFirst = g.dir() > 0;
            } else {
                for (Lane l : left) {
                    if (at == null) {
                        for (Pos c : l.cells()) {
                            double d = fromBot(c);
                            if (d < best) {
                                best = d;
                                pick = l;
                            }
                        }
                    } else {
                        double df = between(at, l.first());
                        double dl = between(at, l.last());
                        if (Math.min(df, dl) < best) {
                            best = Math.min(df, dl);
                            pick = l;
                            fromFirst = df <= dl;
                        }
                    }
                }
                if (at == null) {
                    double df = fromBot(pick.first());
                    double dl = fromBot(pick.last());
                    fromFirst = df < dl || (df == dl && g.dir() > 0);
                }
            }
            List<Pos> cells = new ArrayList<>(pick.cells());
            if (!fromFirst) java.util.Collections.reverse(cells);
            for (Pos c : cells) {
                order.add(c);
                signs.add(fromFirst ? 1 : -1);
            }
            at = cells.getLast();
            left.remove(pick);
        }
    }

    /** Walking cost from the bot: the flood's, else straight-line with a floor change counting 4 per block. */
    private double fromBot(Pos c) {
        if (costs != null) {
            Double v = costs.get(c);
            if (v != null) return v;
        }
        return Math.hypot(c.x() + 0.5 - botX, c.z() + 0.5 - botZ) + StandSpots.CLIMB_COST * Math.abs(cellFeet.get(c) - botY);
    }

    private double between(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z()) + StandSpots.CLIMB_COST * Math.abs(cellFeet.get(a) - cellFeet.get(b));
    }

    /**
     * Stop index per container (-1: unplanned): a column goes to the first stop that sees all of it that is left, the
     * rest to the first stop that sees each.
     */
    private int[] assign(List<Pos> order, Map<Pos, List<Integer>> served) {
        int n = targets.size();
        int[] stopOf = new int[n];
        java.util.Arrays.fill(stopOf, -1);
        Map<Long, List<Integer>> columns = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (covering.get(i).isEmpty()) continue;
            Pos p = targets.get(i).container();
            columns.computeIfAbsent(((long) p.x() << 32) ^ (p.z() & 0xffffffffL), k -> new ArrayList<>()).add(i);
        }
        for (int s = 0; s < order.size(); s++) {
            Set<Integer> sees = new HashSet<>(served.get(order.get(s)));
            for (List<Integer> col : columns.values()) {
                boolean any = false;
                boolean all = true;
                for (int i : col) {
                    if (stopOf[i] >= 0) continue;
                    any = true;
                    if (!sees.contains(i)) {
                        all = false;
                        break;
                    }
                }
                if (!any || !all) continue;
                for (int i : col) if (stopOf[i] < 0) stopOf[i] = s;
            }
        }
        for (int s = 0; s < order.size(); s++) {
            for (int i : served.get(order.get(s))) if (stopOf[i] < 0) stopOf[i] = s;
        }
        return stopOf;
    }

    /**
     * The containers of one stop in opening order, appended to {@code out}: columns along the walking direction
     * ({@code sign}), each from the end nearer {@code lastY} (the last container opened). Returns the new last height.
     */
    private double openingOrder(Geometry g, List<Integer> mine, int sign, double lastY, List<Pos> out) {
        Map<Long, List<Pos>> cols = new LinkedHashMap<>();
        List<Pos> keys = new ArrayList<>();
        for (int i : mine) {
            Pos p = targets.get(i).container();
            long k = ((long) p.x() << 32) ^ (p.z() & 0xffffffffL);
            if (!cols.containsKey(k)) keys.add(p);
            cols.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
        }
        keys.sort(Comparator.<Pos>comparingInt(p -> sign * g.axis(p)).thenComparingInt(g::perpendicular));
        for (Pos key : keys) {
            List<Pos> col = new ArrayList<>(cols.get(((long) key.x() << 32) ^ (key.z() & 0xffffffffL)));
            col.sort(Comparator.comparingInt(Pos::y));
            double bottom = col.getFirst().y() + 0.5;
            double top = col.getLast().y() + 0.5;
            if (Math.abs(lastY - top) <= Math.abs(lastY - bottom)) java.util.Collections.reverse(col);
            out.addAll(col);
            lastY = col.getLast().y() + 0.5;
        }
        return lastY;
    }

    /** Containers without a cell, nearest first from the last stop (or the bot). */
    private List<Pos> unplanned(List<Stop> stops) {
        List<Pos> left = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) if (covering.get(i).isEmpty()) left.add(targets.get(i).container());
        double x = botX;
        double y = botY;
        double z = botZ;
        if (!stops.isEmpty()) {
            Pos c = stops.getLast().cell();
            x = c.x() + 0.5;
            y = cellFeet.get(c);
            z = c.z() + 0.5;
        }
        List<Pos> out = new ArrayList<>();
        while (!left.isEmpty()) {
            int bi = 0;
            double bd = Double.POSITIVE_INFINITY;
            for (int i = 0; i < left.size(); i++) {
                double d = left.get(i).distSq(x, y, z);
                if (d < bd) {
                    bd = d;
                    bi = i;
                }
            }
            Pos p = left.remove(bi);
            out.add(p);
            x = p.x() + 0.5;
            y = p.y();
            z = p.z() + 0.5;
        }
        return out;
    }
}
