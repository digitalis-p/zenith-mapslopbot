package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.RestockPlanner.Costs;
import io.github.digitalisp.mapart.plan.RestockPlanner.Request;
import io.github.digitalisp.mapart.plan.RestockPlanner.Site;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Brute force over the split DP's model, written from the spec and independent of {@link SplitDp}: states are
 * (lane, set of remaining z values, position), rounds are rebuilt from the placements with maps, every candidate end
 * of every round is tried recursively. With {@code memo} the recursion caches the best rest per state (still every
 * sequence of cuts, implicitly); without it, it enumerates every sequence explicitly (tiny inputs only).
 */
final class SplitRestockPlannerBruteForce {
    static final double INF = Double.POSITIVE_INFINITY;

    /** A state: lanes before {@code lane} done, of {@code lane} only rows {@code zs} remain; {@code lane} −1: done. */
    record Key(int lane, List<Integer> zs, int x, int z) { }

    /**
     * One candidate round: its cost, the state after it, whether it ends at a lane end, its placements, the lanes it
     * finishes counted from the state's current one (0 when it ends inside the first), whether it withdraws.
     */
    record Cand(double cost, Key next, boolean laneEnd, int cells, int lanesDone, boolean withdraws) { }

    private final boolean lanesFirst;
    private final boolean memo;
    private final List<Lane> lanes = new ArrayList<>();
    private final List<TreeSet<Integer>> rows = new ArrayList<>();
    private final List<Boolean> southward = new ArrayList<>();
    private final Map<String, Integer> firstNeed = new HashMap<>();
    private final Map<String, Integer> stack = new HashMap<>();
    private final Map<String, Integer> held = new HashMap<>();
    private final Map<String, Integer> heldUsed = new HashMap<>();
    private final int cap;
    private final int reserve;
    private final boolean topUp;
    private final Site site;
    private final Costs c;
    private final boolean xAxis;
    /** {@link Site#flipLanes}: every round may also walk its lanes the other way (its first lane S→N). */
    private final boolean flip;
    private final Map<Key, Double> cache = new HashMap<>();
    long calls;

    SplitRestockPlannerBruteForce(Request r, boolean lanesFirst, boolean memo) {
        this.lanesFirst = lanesFirst;
        this.memo = memo;
        for (Lane l : r.remaining()) {
            if (l.placements().isEmpty()) continue;
            lanes.add(l);
            TreeSet<Integer> zs = new TreeSet<>();
            for (Placement p : l.placements()) {
                zs.add(p.pos().z());
                if (p.itemId() != null) firstNeed.putIfAbsent(p.itemId(), firstNeed.size());
            }
            rows.add(zs);
            boolean s;
            if (l.to().z() != l.from().z()) s = l.to().z() > l.from().z();
            else s = l.placements().get(l.placements().size() - 1).pos().z() >= l.placements().get(0).pos().z();
            southward.add(s);
        }
        for (String id : firstNeed.keySet()) stack.put(id, stackOf(r, id));
        int used = 0;
        for (Map.Entry<String, Integer> e : r.held().entrySet()) {
            if (e.getValue() <= 0) continue;
            int s = stackOf(r, e.getKey());
            int need = (e.getValue() + s - 1) / s;
            int u = Math.max(r.heldSlots().getOrDefault(e.getKey(), need), need);
            held.put(e.getKey(), e.getValue());
            heldUsed.put(e.getKey(), u);
            stack.putIfAbsent(e.getKey(), s);
            used += u;
        }
        cap = Math.max(0, r.freeSlots()) + used;
        reserve = Math.max(0, r.lastLoadReserve());
        topUp = r.topUp();
        site = r.site();
        c = site == null ? Costs.DEFAULT : site.costs();
        double minX = INF, maxX = -INF, minZ = INF, maxZ = -INF;
        if (site != null) {
            for (String id : firstNeed.keySet()) {
                Pos p = site.depot().apply(id);
                if (p == null) continue;
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z());
                maxZ = Math.max(maxZ, p.z());
            }
        }
        xAxis = minX == INF || maxX - minX >= maxZ - minZ;
        flip = site != null && site.flipLanes();
    }

    private static boolean southOf(Lane l) {
        if (l.to().z() != l.from().z()) return l.to().z() > l.from().z();
        return l.placements().get(l.placements().size() - 1).pos().z() >= l.placements().get(0).pos().z();
    }

    private static int stackOf(Request r, String id) {
        int s = r.maxStack() == null ? 64 : r.maxStack().applyAsInt(id);
        return s <= 0 ? 64 : s;
    }

    // ---- results ----

    /** The minimum total seconds (INF: no complete path). */
    double optimum() {
        double best = INF;
        for (Cand cd : firsts()) best = Math.min(best, cd.cost() + rest(cd.next()));
        return best;
    }

    /** Every allowed round 1: the given lanes, and (flip) the lanes walked the other way, each direction on its own. */
    private List<Cand> firsts() {
        List<Cand> out = new ArrayList<>(allowed(round1(false)));
        if (flip) out.addAll(allowed(round1(true)));
        return out;
    }

    /** The round-1 costs of the optimal paths. */
    List<Double> optimalFirstCosts() {
        double opt = optimum();
        List<Double> out = new ArrayList<>();
        if (opt == INF) return out;
        for (Cand cd : firsts()) if (cd.cost() + rest(cd.next()) <= opt + 1e-9) out.add(cd.cost());
        return out;
    }

    /** The longest round 1 the mode allows (the fallback load when no path completes). */
    int longestFirstRound() {
        int best = 0;
        for (Cand cd : firsts()) best = Math.max(best, cd.cells());
        return best;
    }

    private double rest(Key k) {
        if (k.lane() < 0) return 0;
        if (memo) {
            Double v = cache.get(k);
            if (v != null) return v;
        }
        calls++;
        double best = INF;
        for (Cand cd : allowed(later(k, false))) best = Math.min(best, cd.cost() + rest(cd.next()));
        if (flip) for (Cand cd : allowed(later(k, true))) best = Math.min(best, cd.cost() + rest(cd.next()));
        if (memo) cache.put(k, best);
        return best;
    }

    /** Two-lane rule: only lane ends when an end finishing the current lane and the next one fits. */
    private List<Cand> allowed(List<Cand> cands) {
        if (!lanesFirst) return cands;
        boolean any = false;
        for (Cand cd : cands) any |= cd.laneEnd() && cd.lanesDone() >= 2;
        if (!any) return cands;
        List<Cand> out = new ArrayList<>();
        for (Cand cd : cands) if (cd.laneEnd()) out.add(cd);
        return out;
    }

    // ---- rounds ----

    private List<String> itemsAt(int lane, int z) {
        List<String> out = new ArrayList<>();
        for (Placement p : lanes.get(lane).placements()) if (p.pos().z() == z) out.add(p.itemId());
        return out;
    }

    private int centre(int lane) {
        return lanes.get(lane).from().x();
    }

    private static int ceil(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * Every round from a later state: the remaining lane N→S, then S→N, N→S, ... ({@code up}: S→N, then N→S, ...); ends
     * after any row.
     */
    List<Cand> later(Key k, boolean up) {
        List<Cand> out = new ArrayList<>();
        Map<String, Integer> n = new HashMap<>();
        int cells = 0;
        int parts = 0;
        double changes = 0;
        double[] end = null;
        double[] b = {centre(k.lane()), up ? k.zs().get(k.zs().size() - 1) + 1 : k.zs().get(0) - 1};
        for (int j = k.lane(); j < lanes.size(); j++) {
            boolean s = ((j - k.lane()) % 2 == 0) != up;
            List<Integer> zs = new ArrayList<>(j == k.lane() ? new TreeSet<>(k.zs()) : rows.get(j));
            if (!s) java.util.Collections.reverse(zs);
            if (end != null) {
                double[] start = {centre(j), zs.get(0) + (s ? -1 : 1)};
                changes += c.unwindSeconds() + leg(end, start);
            }
            parts++;
            for (int q = 0; q < zs.size(); q++) {
                int z = zs.get(q);
                for (String it : itemsAt(j, z)) {
                    cells++;
                    if (it != null) n.merge(it, 1, Integer::sum);
                }
                boolean laneEnd = q == zs.size() - 1;
                boolean terminal = laneEnd && j == lanes.size() - 1;
                int slots = 0;
                for (Map.Entry<String, Integer> e : n.entrySet()) slots += ceil(e.getValue(), stack.get(e.getKey()));
                if (slots > (terminal ? cap - reserve : cap)) continue;
                Key next;
                int px = centre(j);
                int pz = z + (s ? 1 : -1);
                if (laneEnd) {
                    next = j == lanes.size() - 1 ? new Key(-1, List.of(), 0, 0)
                        : new Key(j + 1, List.copyOf(rows.get(j + 1)), px, pz);
                } else {
                    List<Integer> left = new ArrayList<>(zs.subList(q + 1, zs.size()));
                    left.sort(null);
                    next = new Key(j, List.copyOf(left), px, pz);
                }
                double cost = trip(k.x(), k.z(), n.keySet(), b) + c.unwindSeconds() + c.chestSeconds() * n.size()
                    + c.clickSeconds() * slots + changes + c.laneRunSeconds() * parts;
                out.add(new Cand(cost, next, laneEnd, cells, laneEnd ? j - k.lane() + 1 : j - k.lane(), !n.isEmpty()));
            }
            end = new double[] {centre(j), zs.get(zs.size() - 1) + (s ? 1 : -1)};
        }
        return out;
    }

    /**
     * Every round 1: the given lanes and placements ({@code up}: the lanes flipped, {@link LaneFlip}); ends after any row
     * and at the max-fill placement.
     */
    List<Cand> round1(boolean up) {
        List<Lane> lanes = up ? LaneFlip.flip(this.lanes) : this.lanes;
        List<Cand> out = new ArrayList<>();
        Map<String, Integer> n = new HashMap<>();
        int cells = 0;
        int parts = 0;
        double changes = 0;
        double[] end = null;
        double[] b = {lanes.get(0).from().x(), lanes.get(0).from().z()};
        int bx = site == null ? 0 : site.bot().x();
        int bz = site == null ? 0 : site.bot().z();
        Cand lastFeasible = null;
        boolean lastWasRowEnd = false;
        Cand lastCarried = null;
        boolean lastCarriedListed = false;
        boolean stopped = false;
        for (int a = 0; a < lanes.size() && !stopped; a++) {
            boolean s = up ? southOf(lanes.get(a)) : southward.get(a);
            List<Placement> ps = lanes.get(a).placements();
            TreeSet<Integer> zs = rows.get(a);
            if (end != null) {
                double[] start = {centre(a), s ? zs.first() - 1 : zs.last() + 1};
                changes += c.unwindSeconds() + leg(end, start);
            }
            parts++;
            for (int t = 0; t < ps.size(); t++) {
                Placement p = ps.get(t);
                cells++;
                if (p.itemId() != null) n.merge(p.itemId(), 1, Integer::sum);
                boolean laneEnd = t == ps.size() - 1;
                boolean terminal = laneEnd && a == lanes.size() - 1;
                int slots = 0;
                int clicks = 0;
                Set<String> w = new LinkedHashSet<>();
                Set<String> all = new HashSet<>(n.keySet());
                all.addAll(heldUsed.keySet());
                for (String id : all) {
                    int h = held.getOrDefault(id, 0);
                    int u = heldUsed.getOrDefault(id, 0);
                    int need = n.getOrDefault(id, 0);
                    int st = stack.get(id);
                    if (need <= h) {
                        slots += u;
                        continue;
                    }
                    w.add(id);
                    if (topUp) {
                        int total = st * ceil(need, st);
                        slots += Math.max(u, total / st);
                        int r = h % st;
                        clicks += r > 0 ? 3 + (total - h - (st - r)) / st : (total - h) / st;
                    } else {
                        int stacks = ceil(need - h, st);
                        slots += Math.max(u, ceil(h + stacks * st, st));
                        clicks += stacks;
                    }
                }
                if (slots > (terminal ? cap - reserve : cap)) {
                    stopped = true;
                    break;
                }
                Key next;
                int px = centre(a);
                int pz = p.pos().z() + (s ? 1 : -1);
                if (laneEnd) {
                    next = a == lanes.size() - 1 ? new Key(-1, List.of(), 0, 0)
                        : new Key(a + 1, List.copyOf(rows.get(a + 1)), px, pz);
                } else {
                    int nz = ps.get(t + 1).pos().z();
                    List<Integer> left = new ArrayList<>();
                    for (int z : zs) if (s ? z >= nz : z <= nz) left.add(z);
                    next = new Key(a, List.copyOf(left), px, pz);
                }
                double cost = trip(bx, bz, w, b) + c.unwindSeconds() + c.chestSeconds() * w.size()
                    + c.clickSeconds() * clicks + changes + c.laneRunSeconds() * parts;
                boolean rowEnd = laneEnd || ps.get(t + 1).pos().z() != p.pos().z();
                Cand cd = new Cand(cost, next, laneEnd, cells, laneEnd ? a + 1 : a, !w.isEmpty());
                if (rowEnd) out.add(cd);
                lastFeasible = cd;
                lastWasRowEnd = rowEnd;
                if (w.isEmpty()) {
                    lastCarried = cd;
                    lastCarriedListed = rowEnd;
                }
            }
            if (!stopped) {
                Placement lastP = ps.get(ps.size() - 1);
                end = new double[] {centre(a), lastP.pos().z() + (s ? 1 : -1)};
            }
        }
        if (lastFeasible != null && !lastWasRowEnd) out.add(lastFeasible);
        if (lastCarried != null && !lastCarriedListed && lastCarried != lastFeasible) out.add(lastCarried);
        // Withdraw-free loads that do not finish the tile: only when no load with a withdrawal fits, and then only at a
        // lane end or where what is carried runs out (the coordinator's rule).
        boolean anyWithdraw = false;
        for (Cand cd : out) anyWithdraw |= cd.withdraws();
        List<Cand> kept = new ArrayList<>();
        for (Cand cd : out) {
            boolean terminal = cd.laneEnd() && cd.lanesDone() == lanes.size();
            if (!cd.withdraws() && !terminal && (anyWithdraw
                || (!cd.laneEnd() && (lastCarried == null || cd.cells() != lastCarried.cells())))) continue;
            kept.add(cd);
        }
        return kept;
    }

    // ---- costs ----

    private double dist(double ax, double az, double bx, double bz) {
        return site == null ? 0 : Math.hypot(ax - bx, az - bz);
    }

    private double leg(double[] a, double[] b) {
        double d = dist(a[0], a[1], b[0], b[1]);
        return d > 0 ? c.legSeconds() + d / c.walkSpeed() : 0;
    }

    private double trip(int ax, int az, Set<String> w, double[] b) {
        String lo = null;
        String hi = null;
        if (site != null) {
            for (String id : w) {
                Pos p = site.depot().apply(id);
                if (p == null) continue;
                if (lo == null || proj(p) < proj(site.depot().apply(lo))
                    || (proj(p) == proj(site.depot().apply(lo)) && firstNeed.get(id) < firstNeed.get(lo))) lo = id;
                if (hi == null || proj(p) > proj(site.depot().apply(hi))
                    || (proj(p) == proj(site.depot().apply(hi)) && firstNeed.get(id) < firstNeed.get(hi))) hi = id;
            }
        }
        if (lo == null) return leg(new double[] {ax, az}, b);
        Pos pl = site.depot().apply(lo);
        Pos ph = site.depot().apply(hi);
        double span = dist(pl.x(), pl.z(), ph.x(), ph.z());
        double walk = Math.min(dist(ax, az, pl.x(), pl.z()) + span + dist(ph.x(), ph.z(), b[0], b[1]),
            dist(ax, az, ph.x(), ph.z()) + span + dist(pl.x(), pl.z(), b[0], b[1]));
        int legs = 2 + Math.abs(proj(ph) - proj(pl)) / 5;
        return legs * c.legSeconds() + walk / c.walkSpeed();
    }

    private int proj(Pos p) {
        return xAxis ? p.x() : p.z();
    }

    // =================================================================================================================
    // Random small instances
    // =================================================================================================================

    /** Seeded small instances: 2–4 lanes, 2–6 rows, 1–3 cells per row, 2–3 items, stacks 2–4, 2–6 free slots. */
    static Request randomInstance(Random rnd) {
        int nLanes = 2 + rnd.nextInt(3);
        int nItems = 2 + rnd.nextInt(2);
        String[] ids = {"a", "b", "c"};
        boolean engineOrder = rnd.nextDouble() < 0.7;
        List<Lane> lanes = new ArrayList<>();
        for (int j = 0; j < nLanes; j++) {
            int centre = 1 + 3 * j;
            boolean s = engineOrder ? j % 2 == 0 : rnd.nextBoolean();
            int nRows = 2 + rnd.nextInt(5);
            TreeSet<Integer> zs = new TreeSet<>();
            while (zs.size() < nRows) zs.add(rnd.nextInt(8));
            List<Integer> walk = new ArrayList<>(zs);
            if (!s) java.util.Collections.reverse(walk);
            List<Placement> ps = new ArrayList<>();
            for (int z : walk) {
                int perRow = 1 + rnd.nextInt(3);
                int[] dx = {0, -1, 1};
                for (int q = 0; q < perRow; q++) {
                    String id = rnd.nextDouble() < 0.05 ? null : ids[rnd.nextInt(nItems)];
                    ps.add(new Placement(new Pos(centre + dx[q], 0, z), BlockSpec.of("minecraft:stone"), id));
                }
            }
            int step = s ? 1 : -1;
            lanes.add(new Lane(j, new Pos(centre, 0, walk.get(0) - step), new Pos(centre, 0, walk.get(walk.size() - 1) + step), ps));
        }
        Map<String, Integer> stacks = new HashMap<>();
        for (int k = 0; k < nItems; k++) stacks.put(ids[k], 2 + rnd.nextInt(3));
        Map<String, Integer> held = new HashMap<>();
        Map<String, Integer> heldSlots = new HashMap<>();
        for (int k = 0; k < nItems; k++) {
            if (rnd.nextDouble() < 0.4) {
                int h = 1 + rnd.nextInt(6);
                held.put(ids[k], h);
                if (rnd.nextDouble() < 0.4) heldSlots.put(ids[k], (h + stacks.get(ids[k]) - 1) / stacks.get(ids[k]) + rnd.nextInt(2));
            }
        }
        int free = 2 + rnd.nextInt(5);
        int reserve = rnd.nextInt(3);
        boolean topUp = rnd.nextBoolean();
        Site site = null;
        if (rnd.nextDouble() < 0.85) {
            Map<String, Pos> depots = new HashMap<>();
            List<Integer> xs = new ArrayList<>();
            for (int x = -4; x <= 14; x++) xs.add(x);
            java.util.Collections.shuffle(xs, rnd);
            for (int k = 0; k < nItems; k++) if (rnd.nextDouble() < 0.85) depots.put(ids[k], new Pos(xs.get(k), 0, -6));
            Costs costs = rnd.nextBoolean() ? Costs.DEFAULT
                : new Costs(3 + 4 * rnd.nextDouble(), 0.5 * rnd.nextDouble(), 3 * rnd.nextDouble(), 2 * rnd.nextDouble(),
                    0.5 * rnd.nextDouble(), 2 * rnd.nextDouble());
            site = new Site(new Pos(rnd.nextInt(13), 0, -4 + rnd.nextInt(12)), depots::get, costs);
        }
        return new Request(lanes, held, heldSlots, free, id -> stacks.getOrDefault(id, 64), reserve, topUp, site);
    }

    /** {@link #randomInstance} with the load direction choice on ({@link Site#flipLanes}) when it has a site. */
    static Request randomFlipInstance(Random rnd) {
        Request r = randomInstance(rnd);
        if (r.site() == null) return r;
        return new Request(r.remaining(), r.held(), r.heldSlots(), r.freeSlots(), r.maxStack(), r.lastLoadReserve(),
            r.topUp(), r.site().flipping(true));
    }
}
