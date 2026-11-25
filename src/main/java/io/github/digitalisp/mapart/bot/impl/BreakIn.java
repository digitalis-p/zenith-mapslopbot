package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

/**
 * VERIFY break-in ({@code build.verifyBreakIn}): the plan for a
 * missing cell no stand spot of which the pathfinder can reach, because blocks it can neither walk through nor onto
 * (pointed dripstone, pots: {@link LaneRun.Io#standOnTop} false) wall it in. Pure; the world comes through {@link Grid}.
 *
 * <p>Live 2026-10-13 01:03, ellen [1,6] on moss: a pointed-dripstone cell was a single hole in a field of placed
 * dripstone at least 7 cells deep on every side; every pass picked stand spots on top of the dripstone around it, the
 * pathfinder reached none, and VERIFY blocked after 4 passes. The plan: the fewest correct art blocks to break so that a
 * stand cell from which the target can be placed (in reach, and for a LOOK cell its floor face in sight over cells that
 * are low or broken) is reachable from the bot's floor; then the target goes up from that cell and the broken blocks
 * again, the far end first, each from a stand cell (within {@link LaneRun#SPOT_RINGS} of it, as the stand spots look)
 * on the bot's side of it while the rest of the corridor is still open, so the corridor closes behind the bot. A plan
 * none of whose blocks can be placed again that way is not taken.
 */
final class BreakIn {
    private BreakIn() { }

    /** Cost of one block broken, in cells walked: fewer breaks always win. */
    private static final int WALL_COST = 10_000;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Stand cells tried for the target: up to this far (Chebyshev) from it. */
    static final int STAND_RINGS = 4;
    /** Steps along a sight line (blocks) when the cells under it are collected. */
    private static final double SIGHT_STEP = 0.02;
    /**
     * A sight line this close to a cell counts as passing over it (a ray through a corner or along an edge may hit
     * either block; the game's ray and the plan's line differ by the aim point's jitter).
     */
    private static final double SIGHT_MARGIN = 0.06;
    /** Points of a floor face tried for sight: its centre and these offsets from it. */
    private static final double[][] FACE_POINTS = {{0, 0}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}};

    /** The world at the art level. */
    interface Grid {
        /** The bot can stand in or on {@code c} now and walk on from it (a floor cell, or a block it climbs). */
        boolean passable(Pos c);

        /** {@code c} is a floor cell now: nothing taller than a step, a support below, head room (the eye 1.62 up). */
        boolean floor(Pos c);

        /** {@code c} holds a block the bot may break (and the cell is floor once it is gone). */
        boolean breakable(Pos c);

        /** Highest outline top of the block in {@code c} (0 = air): what hides floor faces beyond it from the eye. */
        double outlineTop(Pos c);

        /** The bot may stand in {@code c} (no-go zones, setback cells). */
        boolean standAllowed(Pos c);
    }

    /**
     * A cell to place: by airplace ({@code airplace}: the centre within the airplace range) or by LOOK (the support's
     * top face in reach and sight); {@code solid}: taller than a step. The target and the broken blocks may go up from a
     * cell right next to them (the corridor is 1 wide; the bot keeps its way out behind it).
     */
    record Target(Pos cell, boolean airplace, boolean solid) { }

    /**
     * The plan: stand at {@code stand} to place {@code target}; {@code walls}: blocks to break, in order from the bot;
     * {@code path}: the bot's feet cell to {@code stand}, walls included; {@code replace}: {@code walls} in the order
     * they go up again (the far end first).
     */
    record Plan(Pos target, Pos stand, List<Pos> walls, List<Pos> path, List<Pos> replace) { }

    /** Ranges for {@link #plan}: LOOK reach (eye to a face point), airplace range (eye to the centre). */
    record Reach(double look, double air) { }

    /**
     * The cheapest plan for {@code t} from the bot's feet cell {@code start} (fewest blocks broken, at most
     * {@code maxWalls}, then the shortest walk), searched within {@code margin} cells of both; null if there is none.
     * {@code targetOf}: how a broken block is placed again (null: it cannot be).
     */
    static @Nullable Plan plan(Grid g, Pos start, Target t, Reach reach, int maxWalls, int margin,
                               Function<Pos, @Nullable Target> targetOf) {
        Pos tc = t.cell();
        int minX = Math.min(start.x(), tc.x()) - margin;
        int maxX = Math.max(start.x(), tc.x()) + margin;
        int minZ = Math.min(start.z(), tc.z()) - margin;
        int maxZ = Math.max(start.z(), tc.z()) + margin;
        Box box = new Box(minX, minZ, maxX, maxZ);
        // fewest walls, then the shortest walk, from the bot to every cell of the box (never through the target)
        Map<Pos, Integer> cost = new HashMap<>();
        Map<Pos, Pos> parent = new HashMap<>();
        PriorityQueue<Object[]> queue = new PriorityQueue<>((a, b) -> Integer.compare((int) a[1], (int) b[1]));
        cost.put(start, 0);
        queue.add(new Object[]{start, 0});
        while (!queue.isEmpty()) {
            Object[] e = queue.poll();
            Pos p = (Pos) e[0];
            int c = (int) e[1];
            if (c > cost.getOrDefault(p, Integer.MAX_VALUE)) continue;
            for (int[] s : SIDES) {
                Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
                if (!box.contains(n) || n.equals(tc)) continue;
                int step;
                if (g.passable(n)) step = 1;
                else if (g.breakable(n)) step = WALL_COST + 1;
                else continue;
                int nc = c + step;
                if (nc >= cost.getOrDefault(n, Integer.MAX_VALUE)) continue;
                cost.put(n, nc);
                parent.put(n, p);
                queue.add(new Object[]{n, nc});
            }
        }
        Plan best = null;
        int bestCost = Integer.MAX_VALUE;
        for (int dx = -STAND_RINGS; dx <= STAND_RINGS; dx++) {
            for (int dz = -STAND_RINGS; dz <= STAND_RINGS; dz++) {
                if (dx == 0 && dz == 0) continue;
                Pos s = new Pos(tc.x() + dx, tc.y(), tc.z() + dz);
                Integer c = cost.get(s);
                if (c == null || !g.standAllowed(s)) continue;
                boolean wallHere = !g.passable(s);
                if (!wallHere && !g.floor(s)) continue; // on top of a block: the eye is higher, not planned for
                List<Pos> path = path(start, s, parent);
                Set<Pos> walls = new LinkedHashSet<>();
                for (int i = 1; i < path.size(); i++) if (!g.passable(path.get(i))) walls.add(path.get(i));
                // what hides the target from s and must go as well: broken from s outwards, placed again inwards
                Set<Pos> found = sightWalls(g, s, t, reach, walls);
                if (found == null) continue;
                List<Pos> near = new ArrayList<>(found);
                near.sort(java.util.Comparator.comparingDouble(u -> Math.hypot(u.x() - s.x(), u.z() - s.z())));
                Set<Pos> sight = new LinkedHashSet<>(near);
                Set<Pos> all = new LinkedHashSet<>(walls);
                all.addAll(sight);
                if (all.size() > maxWalls) continue; // none at all: a stand cell the stand spots do not look at
                int total = all.size() * WALL_COST + path.size();
                if (total >= bestCost) continue;
                List<Pos> replace = replaceOrder(g, start, t, s, walls, sight, reach, box, targetOf);
                if (replace == null) continue;
                bestCost = total;
                List<Pos> order = new ArrayList<>(walls);
                order.addAll(sight);
                best = new Plan(tc, s, List.copyOf(order), List.copyOf(path), List.copyOf(replace));
            }
        }
        return best;
    }

    /**
     * The blocks that hide {@code t} from an eye over {@code s} and that may be broken (beyond {@code open}, which will
     * be broken anyway): empty when it is placeable from {@code s} as it is; null when no face point is in reach or
     * every sight line crosses a block that may not be broken. An airplace target needs no sight.
     */
    static @Nullable Set<Pos> sightWalls(Grid g, Pos s, Target t, Reach reach, Set<Pos> open) {
        Pos c = t.cell();
        double ex = s.x() + 0.5;
        double ez = s.z() + 0.5;
        double ey = Aim.EYE_HEIGHT; // above the floor (the support's top face)
        if (t.airplace()) {
            double dx = c.x() + 0.5 - ex;
            double dz = c.z() + 0.5 - ez;
            double dy = ey - 0.5;
            return dx * dx + dy * dy + dz * dz <= reach.air() * reach.air() ? Set.of() : null;
        }
        Set<Pos> best = null;
        for (double[] o : FACE_POINTS) {
            double px = c.x() + 0.5 + o[0];
            double pz = c.z() + 0.5 + o[1];
            double d2 = (px - ex) * (px - ex) + ey * ey + (pz - ez) * (pz - ez);
            if (d2 > reach.look() * reach.look()) continue;
            Set<Pos> need = new LinkedHashSet<>();
            boolean ok = true;
            for (Map.Entry<Pos, Double> e : under(ex, ez, px, pz, c.y()).entrySet()) {
                Pos u = e.getKey();
                if (u.equals(s) || u.equals(c) || open.contains(u)) continue;
                if (g.outlineTop(u) <= ey * e.getValue()) continue; // the ray passes above it
                if (!g.breakable(u)) {
                    ok = false;
                    break;
                }
                need.add(u);
            }
            if (ok && (best == null || need.size() < best.size())) best = need;
        }
        return best;
    }

    /**
     * Cells under the 2D line from (ax, az) to (bx, bz), each with the lowest height left of the ray over it as a share
     * of the eye height (1 at the eye, 0 at the face point).
     */
    static Map<Pos, Double> under(double ax, double az, double bx, double bz, int y) {
        Map<Pos, Double> out = new HashMap<>();
        double len = Math.hypot(bx - ax, bz - az);
        int n = Math.max(1, (int) Math.ceil(len / SIGHT_STEP));
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            double x = ax + (bx - ax) * f;
            double z = az + (bz - az) * f;
            // a point near a cell edge counts for the cells on both sides
            for (int cx = (int) Math.floor(x - SIGHT_MARGIN); cx <= (int) Math.floor(x + SIGHT_MARGIN); cx++) {
                for (int cz = (int) Math.floor(z - SIGHT_MARGIN); cz <= (int) Math.floor(z + SIGHT_MARGIN); cz++) {
                    out.merge(new Pos(cx, y, cz), 1.0 - f, Math::min);
                }
            }
        }
        return out;
    }

    /**
     * The order the broken blocks go up again, the far end first ({@code sight}, in order from {@code s}, from the one
     * next to the target, then the route back to the bot), each from a stand cell within {@link LaneRun#SPOT_RINGS} of it on the bot's side while the
     * rest of the corridor is still open; null if one of them has no such cell.
     */
    private static @Nullable List<Pos> replaceOrder(Grid g, Pos start, @Nullable Target t, Pos s, Set<Pos> route, Set<Pos> sight,
                                                    Reach reach, Box box, Function<Pos, @Nullable Target> targetOf) {
        List<Pos> order = new ArrayList<>(sight);
        Collections.reverse(order); // the one next to the target first
        List<Pos> back = new ArrayList<>(route);
        Collections.reverse(back);
        order.addAll(back);
        Set<Pos> open = new HashSet<>(order); // broken and not placed again yet
        Set<Pos> placed = new HashSet<>();
        if (t != null) placed.add(t.cell()); // the target goes up first
        for (Pos w : order) {
            Target wt = targetOf.apply(w);
            if (wt == null) return null;
            open.remove(w);
            Set<Pos> reachable = reachable(g, start, box, open, placed, w);
            boolean found = false;
            for (int r = 1; r <= LaneRun.SPOT_RINGS && !found; r++) {
                for (int dx = -r; dx <= r && !found; dx++) {
                    for (int dz = -r; dz <= r && !found; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        Pos o = new Pos(w.x() + dx, w.y(), w.z() + dz);
                        if (!reachable.contains(o) || !g.standAllowed(o)) continue;
                        if (!open.contains(o) && !g.floor(o)) continue;
                        Set<Pos> need = sightWalls(placedOver(g, placed), o, wt, reach, open);
                        found = need != null && need.isEmpty();
                    }
                }
            }
            if (!found) return null;
            placed.add(w);
        }
        return order;
    }

    /** Validate a walk corridor's far-end-first restoration using the same sight and reach rules as break-in. */
    static @Nullable List<Pos> corridorOrder(Grid g, Pos start, WallRoute.Route route, Reach reach,
                                            Function<Pos, @Nullable Target> targetOf) {
        Pos end = route.path().getLast();
        int margin = 24;
        Box box = new Box(Math.min(start.x(), end.x()) - margin, Math.min(start.z(), end.z()) - margin,
            Math.max(start.x(), end.x()) + margin, Math.max(start.z(), end.z()) + margin);
        return replaceOrder(g, start, null, end, new LinkedHashSet<>(route.walls()), Set.of(), reach, box, targetOf);
    }

    /** {@code g} with the target placed: sight lines over its cell count it as a block as tall as a full one. */
    static Grid placedOver(Grid g, Set<Pos> placed) {
        return new Grid() {
            @Override public boolean passable(Pos c) { return !placed.contains(c) && g.passable(c); }
            @Override public boolean floor(Pos c) { return !placed.contains(c) && g.floor(c); }
            @Override public boolean breakable(Pos c) { return g.breakable(c); }
            @Override public double outlineTop(Pos c) { return placed.contains(c) ? 1.0 : g.outlineTop(c); }
            @Override public boolean standAllowed(Pos c) { return g.standAllowed(c); }
        };
    }

    /**
     * Cells the bot can walk to from {@code start} while the cells of {@code open} are broken, those of {@code placed}
     * (and {@code wall}) stand.
     */
    private static Set<Pos> reachable(Grid g, Pos start, Box box, Collection<Pos> open, Collection<Pos> placed, Pos wall) {
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (int[] s : SIDES) {
                Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
                if (!box.contains(n) || seen.contains(n) || n.equals(wall) || placed.contains(n)) continue;
                if (!open.contains(n) && !g.passable(n)) continue;
                seen.add(n);
                queue.add(n);
            }
        }
        return seen;
    }

    private static List<Pos> path(Pos start, Pos end, Map<Pos, Pos> parent) {
        List<Pos> path = new ArrayList<>();
        for (Pos p = end; p != null; p = p.equals(start) ? null : parent.get(p)) path.add(p);
        Collections.reverse(path);
        return path;
    }

    private record Box(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(Pos p) {
            return p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ;
        }
    }
}
