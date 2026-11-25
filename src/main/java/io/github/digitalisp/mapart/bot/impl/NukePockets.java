package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pockets of the fast nuke's floor ({@link NukeRun}, {@code build.nukePocketGuard}): floor cells walled in by blocks
 * that are no target and cannot be stepped onto. Pure; the world comes through {@link Floor}.
 *
 * <p>A cell is <b>passable</b> when the bot's box may stand in it now or once its target is broken (walkable, or a
 * target). A <b>pocket</b> is a 4-connected group of passable cells, at most {@link #POCKET_MAX} of them, with nothing
 * but walls around it: a bot in it cannot walk out. Diagonal steps do not count: the box does not pass between two
 * walls that touch at a corner (Baritone does not either). Live 2026-10-12 (ellen [1,5] on moss): the sweep broke a
 * gray terracotta under the bot whose four sides are pointed dripstone (11/16 tall, above the step height; the
 * pathfinder neither walks through nor onto it), then a carpet in a checkerboard of dripstone and carpet; every walk
 * out failed until the watchdog stopped the round six times.
 *
 * <p>{@link #escape}: the way out of a pocket through the fewest {@linkplain Floor#breakable breakable} walls (then the
 * shortest walk) to the nearest passable cell that is in no pocket: one route, never one break per pocket.
 */
final class NukePockets {
    /** A closed group of at most this many passable cells is a pocket; a larger one counts as open. */
    static final int POCKET_MAX = 64;
    /** The escape looks this far (cells, horizontally) from the pocket. */
    static final int ESCAPE_RADIUS = 24;
    /** Cost of one wall broken on the way out, in cells walked: fewer breaks always win. */
    private static final int WALL_COST = 10_000;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** The group of a cell in no pocket. */
    private static final Set<Pos> OPEN = Collections.emptySet();

    /** The floor at one level. */
    interface Floor {
        /** The box may stand in {@code c} now, or once the target there is broken. */
        boolean passable(Pos c);

        /**
         * {@code c} holds a block that is no target, may be broken to get out of a pocket, and leaves the cell
         * walkable once it is gone.
         */
        boolean breakable(Pos c);
    }

    /** The way out: walls to break (from the pocket outwards), the cells walked (from the start), and the exit cell. */
    record Route(List<Pos> walls, List<Pos> path, Pos exit) { }

    private final Floor floor;
    /** Cell → its pocket's cells ({@link #OPEN}: in no pocket); not passable cells are not kept. */
    private final Map<Pos, Set<Pos>> groups = new HashMap<>();

    NukePockets(Floor floor) {
        this.floor = floor;
    }

    /** Forgets what was learnt: the world changed in a way that may close a pocket (a cell is a wall now). */
    void forget() {
        groups.clear();
    }

    /** {@code c} is passable and in a pocket. */
    boolean pocket(Pos c) {
        Set<Pos> g = group(c);
        return g != null && g != OPEN;
    }

    /** {@code a} and {@code b} lie in the same pocket. */
    boolean samePocket(Pos a, Pos b) {
        Set<Pos> g = group(a);
        return g != null && g != OPEN && g.contains(b);
    }

    /** The pocket holding {@code c} ({@link #OPEN} if none), or null when {@code c} is not passable. */
    private @Nullable Set<Pos> group(Pos c) {
        Set<Pos> known = groups.get(c);
        if (known != null) return known;
        if (!floor.passable(c)) return null;
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        seen.add(c);
        queue.add(c);
        boolean open = false;
        while (!queue.isEmpty() && !open) {
            Pos p = queue.poll();
            for (int[] s : SIDES) {
                Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
                if (seen.contains(n)) continue;
                Set<Pos> g = groups.get(n);
                if (g == OPEN) {
                    open = true; // joins a group already known to be open
                    break;
                }
                if (g == null && !floor.passable(n)) continue;
                seen.add(n);
                queue.add(n);
                if (seen.size() > POCKET_MAX) {
                    open = true;
                    break;
                }
            }
        }
        Set<Pos> result = open ? OPEN : Collections.unmodifiableSet(seen);
        for (Pos p : seen) groups.put(p, result);
        return result;
    }

    /**
     * The way out of the pocket holding {@code from}: through the fewest breakable walls, then the fewest cells, to
     * the nearest passable cell in no pocket, within {@link #ESCAPE_RADIUS}. Null when {@code from} is in no pocket or
     * no way exists.
     */
    @Nullable Route escape(Pos from) {
        if (!pocket(from)) return null;
        return route(List.of(from), c -> !c.equals(from) && floor.passable(c) && !pocket(c), ESCAPE_RADIUS);
    }

    /**
     * The way from the cells {@code from} (each counts as passable: the bot stands there) to the nearest cell
     * {@code goal} accepts: through the fewest breakable walls, then the fewest cells, within {@code radius} cells
     * of the first; a goal cell may itself be a wall that is broken. Null if none.
     */
    @Nullable Route route(java.util.Collection<Pos> from, java.util.function.Predicate<Pos> goal, int radius) {
        if (from.isEmpty()) return null;
        Pos origin = from.iterator().next();
        Map<Pos, Integer> cost = new HashMap<>();
        Map<Pos, Pos> parent = new HashMap<>();
        PriorityQueue<Object[]> queue = new PriorityQueue<>((a, b) -> Integer.compare((int) a[1], (int) b[1]));
        for (Pos f : from) {
            cost.put(f, 0);
            queue.add(new Object[]{f, 0});
        }
        while (!queue.isEmpty()) {
            Object[] e = queue.poll();
            Pos p = (Pos) e[0];
            int c = (int) e[1];
            if (c > cost.getOrDefault(p, Integer.MAX_VALUE)) continue;
            if (goal.test(p)) return route(from, p, parent);
            for (int[] s : SIDES) {
                Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
                if (Math.abs(n.x() - origin.x()) > radius || Math.abs(n.z() - origin.z()) > radius) continue;
                int step;
                if (floor.passable(n)) step = 1;
                else if (floor.breakable(n)) step = WALL_COST + 1;
                else continue;
                int nc = c + step;
                if (nc >= cost.getOrDefault(n, Integer.MAX_VALUE)) continue;
                cost.put(n, nc);
                parent.put(n, p);
                queue.add(new Object[]{n, nc});
            }
        }
        return null;
    }

    private Route route(java.util.Collection<Pos> from, Pos exit, Map<Pos, Pos> parent) {
        List<Pos> path = new ArrayList<>();
        for (Pos p = exit; p != null; p = from.contains(p) ? null : parent.get(p)) path.add(p);
        Collections.reverse(path);
        List<Pos> walls = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) if (!floor.passable(path.get(i))) walls.add(path.get(i));
        return new Route(walls, path, exit);
    }
}
