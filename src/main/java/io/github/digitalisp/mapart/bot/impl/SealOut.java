package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** VERIFY's one refill walk: open the cheapest way out, then close holes while retreating towards the approach. */
final class SealOut {
    private SealOut() { }
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Blocks one route may break to open a way out or into a sealed group (live remu [0,2]: 11). */
    static final int MAX_WALLS = 32;

    record Step(Pos hole, Pos stand) { }
    record Plan(Set<Pos> outside, List<WallRoute.Route> routes, List<Step> steps, int groups) { }

    /** Permanent floor with the most border cells, then the most cells; iteration order breaks remaining ties. */
    static Set<Pos> outside(LaneRun.Area area, int y, Predicate<Pos> floor) {
        Set<Pos> seen = new HashSet<>();
        Set<Pos> best = Set.of();
        int border = -1;
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                Pos p = new Pos(x, y, z);
                if (seen.contains(p) || !floor.test(p)) continue;
                Set<Pos> component = distances(List.of(p), area, floor).keySet();
                seen.addAll(component);
                int edges = 0;
                for (Pos c : component) if (c.x() == area.minX() || c.x() == area.maxX()
                    || c.z() == area.minZ() || c.z() == area.maxZ()) edges++;
                if (edges > border || edges == border && component.size() > best.size()) {
                    border = edges;
                    best = Set.copyOf(component);
                }
            }
        }
        return best;
    }

    /**
     * Regular holes have a stand cell in the permanent outside within sight and reach. Others share one refill;
     * every hole on its retreat paths joins it. Nothing outside the stand area or in a forbidden cell is walked.
     */
    static @Nullable Plan plan(BreakIn.Grid grid, LaneRun.Area area, Pos start, List<BreakIn.Target> targets,
                               BreakIn.Reach reach, Function<Pos, BreakIn.@Nullable Target> targetOf) {
        Map<Pos, BreakIn.Target> holes = new HashMap<>();
        for (BreakIn.Target t : targets) if (t.solid() && grid.floor(t.cell())) holes.put(t.cell(), t);
        if (holes.isEmpty()) return null;
        Predicate<Pos> floor = c -> grid.floor(c) && grid.standAllowed(c);
        Set<Pos> outside = outside(area, start.y(), c -> {
            BreakIn.Target t = targetOf.apply(c);
            return floor.test(c) && (t == null || !t.solid());
        });
        if (outside.isEmpty()) return null;
        Set<Pos> selected = new LinkedHashSet<>();
        for (BreakIn.Target t : targets) {
            if (!holes.containsKey(t.cell())) continue;
            boolean regular = false;
            for (int dx = -BreakIn.STAND_RINGS; dx <= BreakIn.STAND_RINGS && !regular; dx++) {
                for (int dz = -BreakIn.STAND_RINGS; dz <= BreakIn.STAND_RINGS && !regular; dz++) {
                    Pos s = new Pos(t.cell().x() + dx, start.y(), t.cell().z() + dz);
                    if (!outside.contains(s)) continue;
                    Set<Pos> need = BreakIn.sightWalls(grid, s, t, reach, Set.of());
                    regular = need != null && need.isEmpty();
                }
            }
            if (!regular) selected.add(t.cell());
        }
        if (selected.isEmpty() && outside.contains(start)) return null;
        // A sealed bot must leave before any refill; restore that route as part of the same walk.
        Set<Pos> opened = new LinkedHashSet<>();
        List<WallRoute.Route> routes = new ArrayList<>();
        WallRoute.Grid walls = new WallRoute.Grid() {
            @Override public boolean passable(Pos c) { return area.contains(c.x(), c.z()) && (opened.contains(c) || floor.test(c)); }
            @Override public boolean breakable(Pos c) {
                return area.contains(c.x(), c.z()) && grid.standAllowed(c) && grid.breakable(c) && targetOf.apply(c) != null;
            }
        };
        if (!outside.contains(start)) {
            WallRoute.Route route = WallRoute.plan(walls, start, start, Math.max(area.maxX() - area.minX(),
                area.maxZ() - area.minZ()), MAX_WALLS, outside::contains);
            if (route == null) return null;
            routes.add(route);
            opened.addAll(route.walls());
        }
        Pos approach = outside.stream().min(Comparator.comparingInt(Pos::x).thenComparingInt(Pos::z)).orElseThrow();
        Map<Pos, Integer> distance = distances(outside, area, walls::passable);
        for (Pos hole : List.copyOf(selected)) {
            if (distance.containsKey(hole)) continue;
            WallRoute.Route route = WallRoute.plan(walls, approach, hole, Math.max(area.maxX() - area.minX(),
                area.maxZ() - area.minZ()), MAX_WALLS, hole::equals);
            if (route == null) return null;
            routes.add(route);
            opened.addAll(route.walls());
            distance = distances(outside, area, walls::passable);
        }
        selected.addAll(opened);
        // Include holes used on any shortest retreat, so ordinary repairs cannot close this walk early.
        ArrayDeque<Pos> paths = new ArrayDeque<>(selected);
        Set<Pos> pathSeen = new HashSet<>(selected);
        while (!paths.isEmpty()) {
            Pos p = paths.poll();
            Integer d = distance.get(p);
            if (d == null) return null;
            if (d == 0) continue;
            for (int[] side : SIDES) {
                Pos n = new Pos(p.x() + side[0], p.y(), p.z() + side[1]);
                if (distance.getOrDefault(n, Integer.MAX_VALUE) != d - 1) continue;
                if (holes.containsKey(n)) selected.add(n);
                if (pathSeen.add(n)) paths.add(n);
            }
        }
        int groups = 0;
        Set<Pos> counted = new HashSet<>();
        for (Pos c : selected) if (!counted.contains(c)) {
            counted.addAll(distances(List.of(c), area, selected::contains).keySet());
            groups++;
        }
        Map<Pos, Integer> orderDistance = distance;
        List<Pos> order = new ArrayList<>(selected);
        order.sort(Comparator.<Pos>comparingInt(c -> -orderDistance.get(c)).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        Set<Pos> placed = new HashSet<>();
        List<Step> steps = new ArrayList<>();
        for (Pos c : order) {
            BreakIn.Target t = holes.get(c);
            if (t == null) t = targetOf.apply(c);
            if (t == null) return null;
            Pos stand = null;
            for (int[] side : SIDES) {
                Pos n = new Pos(c.x() + side[0], c.y(), c.z() + side[1]);
                if (distance.getOrDefault(n, Integer.MAX_VALUE) >= distance.get(c) || placed.contains(n)) continue;
                Set<Pos> need = BreakIn.sightWalls(BreakIn.placedOver(grid, placed), n, t, reach, opened);
                if (need != null && need.isEmpty()) { stand = n; break; }
            }
            if (stand == null) return null;
            steps.add(new Step(c, stand));
            placed.add(c);
            opened.remove(c);
        }
        return new Plan(outside, List.copyOf(routes), List.copyOf(steps), groups);
    }

    static Map<Pos, Integer> distances(Iterable<Pos> starts, LaneRun.Area area, Predicate<Pos> floor) {
        Map<Pos, Integer> distance = new HashMap<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        for (Pos s : starts) if (floor.test(s) && distance.putIfAbsent(s, 0) == null) queue.add(s);
        while (!queue.isEmpty()) {
            Pos c = queue.poll();
            for (int[] side : SIDES) {
                Pos n = new Pos(c.x() + side[0], c.y(), c.z() + side[1]);
                if (!area.contains(n.x(), n.z()) || distance.containsKey(n) || !floor.test(n)) continue;
                distance.put(n, distance.get(c) + 1);
                queue.add(n);
            }
        }
        return distance;
    }
}
