package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A route on foot made of the pathfinder's simplest moves only: one cell north, south, east or west on the level, one
 * up (a jump onto the next block), or down at most {@link #MAX_DROP} onto a floor. No diagonals, no jumps over gaps,
 * no long falls (ZenithProxy's pathfinder, asked for a far goal over open air, answers with a fall into the water
 * below: live 2026-11-14, from the muncher's rail field, "drops 162 blocks"). The bot is then led along it in short
 * hops ({@link FootRouteTask}), each one straight level run or one step, which the pathfinder walks as planned. Pure.
 */
final class FootRoute {
    private FootRoute() { }

    /** The deepest step down of a route (the pathfinder's own limit without water, {@code maxFallHeightNoWater}). */
    static final int MAX_DROP = 3;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** What the search asks of the world. */
    interface Cells {
        /** The bot's feet may be in this cell: it and the cell above are free, the one below is a floor. */
        boolean stand(Pos feet);

        /** The bot's body may pass through this cell. */
        boolean pass(Pos cell);
    }

    /**
     * The cheapest route from {@code start} to a cell {@code goal} accepts (walk 1, up 3, down 2 to 4), {@code start}
     * first; null when none is found within {@code maxNodes} cells. Cells {@code refused} are never entered.
     */
    static @Nullable List<Pos> find(Cells w, Pos start, Predicate<Pos> goal, Predicate<Pos> refused, int maxNodes) {
        return find(w, start, goal, refused, maxNodes, null);
    }

    /** {@link #find}; {@code reached} (when given) gets every cell the search came to, for a message. */
    static @Nullable List<Pos> find(Cells w, Pos start, Predicate<Pos> goal, Predicate<Pos> refused, int maxNodes,
                                    java.util.@Nullable Set<Pos> reached) {
        record Node(int cost, Pos at) { }
        Map<Pos, Integer> dist = new HashMap<>();
        Map<Pos, Pos> prev = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Integer.compare(a.cost(), b.cost()));
        dist.put(start, 0);
        open.add(new Node(0, start));
        int seen = 0;
        while (!open.isEmpty() && seen < maxNodes) {
            Node n = open.poll();
            Pos c = n.at();
            if (n.cost() > dist.get(c)) continue;
            seen++;
            if (reached != null) reached.add(c);
            if (goal.test(c)) {
                List<Pos> out = new ArrayList<>();
                for (Pos p = c; p != null; p = prev.get(p)) out.add(p);
                Collections.reverse(out);
                return out;
            }
            for (int[] d : SIDES) {
                Pos to = null;
                int cost = 0;
                Pos side = new Pos(c.x() + d[0], c.y(), c.z() + d[1]);
                if (w.stand(side)) {
                    to = side;
                    cost = 1;
                } else if (w.stand(side.above()) && w.pass(new Pos(c.x(), c.y() + 2, c.z()))) {
                    to = side.above();
                    cost = 3;
                } else if (w.pass(side) && w.pass(side.above())) {
                    for (int k = 1; k <= MAX_DROP; k++) {
                        Pos low = new Pos(side.x(), side.y() - k, side.z());
                        if (w.stand(low)) {
                            to = low;
                            cost = 1 + k;
                            break;
                        }
                        if (!w.pass(low)) break;
                    }
                }
                if (to == null || refused.test(to)) continue;
                int nd = n.cost() + cost;
                Integer old = dist.get(to);
                if (old != null && old <= nd) continue;
                dist.put(to, nd);
                prev.put(to, c);
                open.add(new Node(nd, to));
            }
        }
        return null;
    }

    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

    /**
     * Steps from the cells {@code seeds} (the target's) through the cells {@code opened} accepts, to any of the six
     * neighbours, a floor under them or not: where the world is not loaded the opened boxes of a way (a site's access
     * boxes: feet cells with head room) stand for the way. For every cell of {@code wanted} the search comes to; it
     * ends once all of them are found or {@code maxCells} cells were looked at. Pure.
     */
    static Map<Pos, Integer> openedSteps(Predicate<Pos> opened, java.util.Collection<Pos> seeds, java.util.Set<Pos> wanted,
                                         int maxCells) {
        Map<Pos, Integer> dist = new HashMap<>();
        Map<Pos, Integer> out = new HashMap<>();
        java.util.ArrayDeque<Pos> open = new java.util.ArrayDeque<>();
        for (Pos s : seeds) {
            if (!opened.test(s) || dist.putIfAbsent(s, 0) != null) continue;
            open.add(s);
            if (wanted.contains(s)) out.put(s, 0);
        }
        while (!open.isEmpty() && out.size() < wanted.size() && dist.size() < maxCells) {
            Pos c = open.poll();
            int d = dist.get(c) + 1;
            for (int[] s : SIX) {
                Pos n = c.offset(s[0], s[1], s[2]);
                if (dist.containsKey(n) || !opened.test(n)) continue;
                dist.put(n, d);
                open.add(n);
                if (wanted.contains(n)) out.put(n, d);
            }
        }
        return out;
    }

    /**
     * The end of a leg when no route leads to the target within the loaded chunks: of the cells the search came to
     * ({@code reached}), the one with the fewest steps left to the target through the opened cells ({@code steps},
     * {@link #openedSteps}), when that is at least {@code gain} fewer than from {@code start}. A straight line misleads
     * on a way round a field: from the booth by the brake the walkway's east end lies nearer a hold at the far row's
     * east end than any cell of the way round the west side, and leads nowhere. Without steps for the start (it
     * stands in no opened cell, or none leads to the target): the cell nearest the target in a straight line, at least
     * {@code gain} blocks nearer than the start. Null: no leg is a gain. Pure.
     */
    static @Nullable Pos legEnd(Pos start, Pos target, java.util.Collection<Pos> reached, Map<Pos, Integer> steps, int gain) {
        Integer from = steps.get(start);
        Pos best = null;
        if (from != null) {
            int least = from - gain;
            for (Pos c : reached) {
                Integer s = steps.get(c);
                if (s == null || s > least) continue;
                if (s < least || best == null || line(c, target) < line(best, target)) best = c;
                least = s;
            }
            return best;
        }
        double near = Math.sqrt(line(start, target)) - gain;
        for (Pos c : reached) {
            double d = Math.sqrt(line(c, target));
            if (d > near) continue;
            best = c;
            near = d;
        }
        return best;
    }

    private static long line(Pos a, Pos b) {
        long dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    /**
     * The index of the next waypoint after {@code from} on {@code route}: the end of the straight level run that
     * starts there, at most {@code hop} cells on; a step up or down is a hop of its own. So the pathfinder is only
     * ever asked for moves along one line or for one step: asked for more it cuts corners diagonally, past the hole
     * beside a one-wide line, which the edge guard refuses (live 2026-11-14: "MovementDiagonal has taken too long").
     */
    static int nextWaypoint(List<Pos> route, int from, int hop) {
        int last = route.size() - 1;
        if (from >= last) return last;
        Pos a = route.get(from), b = route.get(from + 1);
        if (b.y() != a.y()) return from + 1;
        int dx = b.x() - a.x(), dz = b.z() - a.z();
        int to = from + 1;
        while (to < last && to - from < hop) {
            Pos p = route.get(to), q = route.get(to + 1);
            if (q.y() != p.y() || q.x() - p.x() != dx || q.z() - p.z() != dz) break;
            to++;
        }
        return to;
    }
}
