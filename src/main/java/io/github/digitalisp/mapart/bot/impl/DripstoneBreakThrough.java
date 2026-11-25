package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** One bounded recovery before a failed walk is returned to its caller. Shared by lanes and all Zenith tasks. */
final class DripstoneBreakThrough {
    static final int MAX_WALLS = 6;
    /**
     * A goal on another level (a chest, the storage floor) is broken towards only from a pocket: fewer floor cells
     * than this reachable from the bot; the route then ends on the first cell of an area at least this large. Live
     * 2026-10-13, remu [2,2]: boxed in at 2 cells of the art, every restock walk to the storage (one block up) failed
     * without a break-through and VERIFY ran out of items and passes.
     */
    static final int OPEN_CELLS = 64;
    /**
     * A goal on another level is also broken towards when the bot's floor never leaves the tile (every cell it can walk
     * to is art: an area of the art sealed by pointed dripstone, however large); the route then ends on the first cell
     * of a floor that does leave it. Floods stop at this many cells. Live 2026-10-13, remu [2,2] LOCK: the walk to the
     * cartography table failed from a sealed area of several hundred cells.
     */
    static final int FLOOD_MAX = 20_000;
    /** Blocks an escape from a sealed area may break (each is placed again, or the next nuke clears the tile). */
    static final int MAX_ESCAPE_WALLS = 16;
    /** An escape's route search box reaches at most this far towards the goal (and {@code 24} around it). */
    static final int ESCAPE_REACH = 96;
    interface Io {
        /**
         * Floor of the way back when the goal itself is not loaded (a far chest): the platform's own storage, which the
         * walks to and from the tile always pass. Default none.
         */
        default List<Pos> approachSeeds() { return List.of(); }
        boolean enabled();
        Pos feet();
        BreakIn.Grid grid();
        BreakIn.@Nullable Target target(Pos cell);
        BreakIn.Reach reach();
        TaskStatus breakWalls(List<Pos> walls, List<Pos> path);
        void stop();
        default void cancelBreaks() { }
        /** Enqueue even a partially broken corridor on failure or cancellation. */
        void restore(List<Pos> farEndFirst);
        void log(String line);
        String relative(Pos cell);
    }
    private final Io io;
    private List<GoalNearXZ> goals = List.of();
    private WallRoute.@Nullable Route route;
    private List<Pos> replace = List.of();
    private boolean tried;
    private boolean breaking;

    DripstoneBreakThrough(Io io) { this.io = io; }

    TaskStatus tick(List<GoalNearXZ> wanted, String kind, Supplier<TaskStatus> walk) {
        if (!wanted.equals(goals)) {
            cancel();
            goals = List.copyOf(wanted);
            tried = false;
        }
        if (breaking) {
            TaskStatus s = io.breakWalls(route.walls(), route.path());
            if (s == TaskStatus.RUNNING) return s;
            breaking = false;
            io.restore(replace);
            replace = List.of();
            if (s == TaskStatus.FAILED) return s;
            io.stop();
        }
        TaskStatus s = walk.get();
        if (s != TaskStatus.FAILED || tried || !io.enabled()) {
            if (s != TaskStatus.RUNNING) { goals = List.of(); tried = false; }
            return s;
        }
        tried = true;
        BreakIn.Grid g = io.grid();
        Pos start = io.feet();
        WallRoute.Grid grid = new WallRoute.Grid() {
            @Override public boolean passable(Pos p) { return g.standAllowed(p) && g.passable(p); }
            @Override public boolean breakable(Pos p) { return g.standAllowed(p) && g.breakable(p); }
        };
        WallRoute.Route best = null;
        List<Pos> order = null;
        boolean escape = false;
        for (GoalNearXZ goal : wanted.isEmpty() ? List.<GoalNearXZ>of() : List.of(wanted.getLast())) {
            // another level, or off the tile (storage, the cartography table): the art-layer grid holds no route to the
            // goal itself, only the way out of a sealed area (live remu [2,2] LOCK: the table one block up, tolerance 3)
            boolean otherLevel = Math.abs(start.y() - goal.y()) > goal.yTolerance()
                || io.target(new Pos(goal.x(), start.y(), goal.z())) == null;
            Predicate<Pos> arrived;
            int maxWalls = MAX_WALLS;
            // an escape only needs the way off the tile: the route search aims at most ESCAPE_REACH towards a far goal
            // (live remu [2,2]: the map chests lie more than 192 blocks from the tile, so no escape was ever planned)
            Pos aim = otherLevel ? new Pos(start.x() + Math.clamp(goal.x() - start.x(), -ESCAPE_REACH, ESCAPE_REACH),
                start.y(), start.z() + Math.clamp(goal.z() - start.z(), -ESCAPE_REACH, ESCAPE_REACH))
                : new Pos(goal.x(), start.y(), goal.z());
            if (!otherLevel && (Math.abs(start.x() - goal.x()) > 192 || Math.abs(start.z() - goal.z()) > 192)) continue;
            if (!otherLevel) {
                arrived = p -> goal.isInGoal(p.x(), p.y(), p.z());
            } else {
                Set<Pos> pocket = area(grid, start, Set.of());
                if (pocket.size() < OPEN_CELLS) {
                    arrived = p -> !pocket.contains(p) && area(grid, p, pocket).size() >= OPEN_CELLS;
                } else {
                    // a large area: sealed only if none of its floor leaves the tile
                    // a large area: sealed off from the goal's own floor (flooded from the goal: the storage walkway)?
                    // Leaving the tile is not enough (live remu [2,2]: a ledge off the tile's edge led nowhere)
                    Set<Pos> goalFloor = goalFloor(grid, goal, start.y());
                    // a far goal (not loaded): the floor of the storage the walk passes on its way
                    // (live remu [2,2]: the map chest 246 blocks away, so the goal's floor was empty)
                    if (goalFloor.isEmpty()) goalFloor = floodFrom(grid, io.approachSeeds(), start.y());
                    Set<Pos> mine = flood(grid, start, start.y());
                    if (goalFloor.isEmpty()) {
                        io.log("dripstone break-through for " + kind + ": the goal's floor is not loaded and no storage"
                            + " floor is known");
                        continue;
                    }
                    if (touches(mine, goalFloor)) {
                        io.log("dripstone break-through for " + kind + ": the walk grid reaches the goal's floor ("
                            + mine.size() + " cells); the pathfinder does not");
                        continue;
                    }
                    Set<Pos> target = goalFloor;
                    arrived = p -> !mine.contains(p) && touches(Set.of(p), target);
                    maxWalls = MAX_ESCAPE_WALLS;
                    escape = true;
                }
            }
            WallRoute.Route r = WallRoute.plan(grid, start, aim, 24, maxWalls, arrived);
            if (r == null || r.walls().isEmpty()) continue;
            List<Pos> back = BreakIn.corridorOrder(g, start, r, io.reach(), io::target);
            // an escape towards another level: placed again far end first as far as it goes (VERIFY sees the rest)
            if (back == null && otherLevel) back = List.copyOf(r.walls()).reversed();
            if (back == null) continue;
            if (best == null || r.walls().size() < best.walls().size() ||
                r.walls().size() == best.walls().size() && r.path().size() < best.path().size()) {
                best = r;
                order = back;
            }
        }
        if (best == null) {
            if (escape) io.log("dripstone break-through for " + kind + ": sealed in, no way out within "
                + MAX_ESCAPE_WALLS + " blocks");
            return TaskStatus.FAILED;
        }
        route = best;
        replace = List.copyOf(order);
        breaking = true;
        io.stop();
        io.log("dripstone break-through for " + kind + ": breaking " + best.walls().size() + " block(s) "
            + best.walls().stream().map(io::relative).toList() + "; they are placed again after");
        return TaskStatus.RUNNING;
    }

    boolean active() { return route != null && !goals.isEmpty(); }

    /**
     * The floor reachable from {@code from} holds a cell that is no art of the tile ({@link Io#target} null) or more
     * than {@link #FLOOD_MAX} cells; every cell flooded gets the answer in {@code cache}.
     */
    private boolean leavesTile(WallRoute.Grid grid, Pos from, Map<Pos, Boolean> cache) {
        Boolean known = cache.get(from);
        if (known != null) return known;
        if (!grid.passable(from)) return false;
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        seen.add(from);
        queue.add(from);
        boolean out = false;
        int artY = from.y();
        while (!queue.isEmpty() && !out) {
            Pos c = queue.poll();
            // off the tile by its column at the art level: a cell on top of the art is still the tile (live remu [2,2]:
            // the flood stepped onto the art at the tile's edge and took that for the way out)
            if (io.target(new Pos(c.x(), artY, c.z())) == null || seen.size() >= FLOOD_MAX) {
                exitCell = c;
                out = true;
                break;
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                // a step up or down too: the ways off the tile (the storage walkway) are a block above the art layer
                for (int dy : new int[]{0, 1, -1}) {
                    Pos n = new Pos(c.x() + d[0], c.y() + dy, c.z() + d[1]);
                    // level changes only off the tile: on it the bot walks the art layer (never on top of the art)
                    if (dy != 0 && io.target(new Pos(n.x(), artY, n.z())) != null) continue;
                    if (!grid.passable(n)) continue;
                    Boolean k = cache.get(n);
                    if (k != null && k) out = true;
                    else if (seen.add(n)) queue.add(n);
                    break;
                }
                if (out) break;
            }
        }
        for (Pos c : seen) cache.put(c, out);
        return out;
    }

    /** The last cell {@link #leavesTile} found off the tile (diagnostics). */
    private Pos exitCell = new Pos(0, 0, 0);

    /**
     * Floor connected to {@code goal}'s cells (a block of height either way off the tile; on the tile only the art level
     * {@code artY}), at most {@link #FLOOD_MAX} cells; empty when no cell in the goal is floor.
     */
    private Set<Pos> goalFloor(WallRoute.Grid grid, GoalNearXZ goal, int artY) {
        List<Pos> seeds = new java.util.ArrayList<>();
        int r = Math.max(0, goal.range());
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
            for (int dy = -goal.yTolerance(); dy <= goal.yTolerance(); dy++) {
                Pos c = new Pos(goal.x() + dx, goal.y() + dy, goal.z() + dz);
                if (goal.isInGoal(c.x(), c.y(), c.z()) && grid.passable(c)) seeds.add(c);
            }
        Set<Pos> seen = new HashSet<>(seeds);
        ArrayDeque<Pos> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty() && seen.size() < FLOOD_MAX) {
            Pos c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy : new int[]{0, 1, -1}) {
                    Pos n = new Pos(c.x() + d[0], c.y() + dy, c.z() + d[1]);
                    boolean onTile = io.target(new Pos(n.x(), artY, n.z())) != null;
                    if (onTile && n.y() != artY) continue;
                    if (!grid.passable(n)) continue;
                    if (seen.add(n)) queue.add(n);
                    break;
                }
            }
        }
        return seen;
    }

    /** The bot's floor at the art level of the tile (and off it a block either way), at most {@link #FLOOD_MAX}. */
    private Set<Pos> flood(WallRoute.Grid grid, Pos from, int artY) {
        return floodFrom(grid, List.of(from), artY);
    }

    /** {@link #flood} from several cells (those that are floor). */
    private Set<Pos> floodFrom(WallRoute.Grid grid, List<Pos> from, int artY) {
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        for (Pos f : from) if (grid.passable(f) && seen.add(f)) queue.add(f);
        while (!queue.isEmpty() && seen.size() < FLOOD_MAX) {
            Pos c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy : new int[]{0, 1, -1}) {
                    Pos n = new Pos(c.x() + d[0], c.y() + dy, c.z() + d[1]);
                    boolean onTile = io.target(new Pos(n.x(), artY, n.z())) != null;
                    if (onTile && n.y() != artY) continue;
                    if (!grid.passable(n)) continue;
                    if (seen.add(n)) queue.add(n);
                    break;
                }
            }
        }
        return seen;
    }

    /** A cell of {@code a} is in {@code b} or a step (a block of height either way) from one of its cells. */
    private static boolean touches(Set<Pos> a, Set<Pos> b) {
        for (Pos c : a) {
            if (b.contains(c)) return true;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                for (int dy = -1; dy <= 1; dy++) if (b.contains(new Pos(c.x() + d[0], c.y() + dy, c.z() + d[1]))) return true;
        }
        return false;
    }

    /** Cells reachable from {@code from} over passable cells, not entering {@code outside}; stops at {@link #OPEN_CELLS}. */
    private static Set<Pos> area(WallRoute.Grid grid, Pos from, Set<Pos> outside) {
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        seen.add(from);
        queue.add(from);
        while (!queue.isEmpty() && seen.size() < OPEN_CELLS) {
            Pos c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Pos n = new Pos(c.x() + d[0], c.y(), c.z() + d[1]);
                if (seen.contains(n) || outside.contains(n) || !grid.passable(n)) continue;
                seen.add(n);
                queue.add(n);
            }
        }
        return seen;
    }

    void cancel() {
        if (breaking) io.cancelBreaks();
        if (!replace.isEmpty()) io.restore(replace);
        replace = List.of();
        breaking = false;
        route = null;
    }
}
