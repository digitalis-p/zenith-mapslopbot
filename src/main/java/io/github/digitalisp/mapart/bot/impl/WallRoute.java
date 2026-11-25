package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * The way to a goal through the fewest walls the bot may break, then the shortest walk (a BUILD walk that failed:
 * {@code build.laneBreakOut}). Cells at one level, 4-connected (the box does not
 * pass between two walls that touch at a corner). Pure; the world comes through {@link Grid}.
 *
 * <p>Live 2026-10-12, ellen [0,6] on moss (a pointed-dripstone field): BUILD blocked three times in ten minutes, each
 * time Baritone found no way to a lane start, walled off by placed dripstone (neither walked through nor onto): the bot
 * caged at the end of lane 8 (10:09), a repair lane's start inside the field (10:15), lane 9's restart cell a placed
 * dripstone with the bot one block beside it (10:18). Breaking one or two of them by hand let the lanes run.
 */
final class WallRoute {
    private WallRoute() { }

    /** Cost of one wall broken, in cells walked: fewer breaks always win. */
    private static final int WALL_COST = 10_000;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The world at one level. */
    interface Grid {
        /** The bot can stand in or on {@code c} and walk on from it (a floor cell, or a block Baritone climbs). */
        boolean passable(Pos c);

        /** {@code c} holds a wall the bot may break (and the cell is walkable once it is gone). */
        boolean breakable(Pos c);
    }

    /** Walls to break in order from the bot, and the cells walked from {@code start} to the goal. */
    record Route(List<Pos> walls, List<Pos> path) { }

    /**
     * The route from {@code start} to a cell within {@code range} (Chebyshev) of {@code goal} (the goal cell itself
     * included when it holds a breakable wall), within {@code margin} cells of the box around both; null if none.
     * {@code start} counts as passable.
     */
    static @Nullable Route plan(Grid grid, Pos start, Pos goal, int range, int margin) {
        return plan(grid, start, goal, margin, Integer.MAX_VALUE,
            p -> Math.abs(p.x() - goal.x()) <= range && Math.abs(p.z() - goal.z()) <= range);
    }

    static @Nullable Route plan(Grid grid, Pos start, Pos goal, int margin, int maxWalls, Predicate<Pos> arrived) {
        int minX = Math.min(start.x(), goal.x()) - margin;
        int maxX = Math.max(start.x(), goal.x()) + margin;
        int minZ = Math.min(start.z(), goal.z()) - margin;
        int maxZ = Math.max(start.z(), goal.z()) + margin;
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
            if (arrived.test(p)) {
                return route(grid, start, p, parent);
            }
            for (int[] s : SIDES) {
                Pos n = new Pos(p.x() + s[0], p.y(), p.z() + s[1]);
                if (n.x() < minX || n.x() > maxX || n.z() < minZ || n.z() > maxZ) continue;
                int step;
                if (grid.passable(n)) step = 1;
                else if (grid.breakable(n)) step = WALL_COST + 1;
                else continue;
                int nc = c + step;
                if (nc / WALL_COST > maxWalls) continue;
                if (nc >= cost.getOrDefault(n, Integer.MAX_VALUE)) continue;
                cost.put(n, nc);
                parent.put(n, p);
                queue.add(new Object[]{n, nc});
            }
        }
        return null;
    }

    private static Route route(Grid grid, Pos start, Pos end, Map<Pos, Pos> parent) {
        List<Pos> path = new ArrayList<>();
        for (Pos p = end; p != null; p = p.equals(start) ? null : parent.get(p)) path.add(p);
        Collections.reverse(path);
        List<Pos> walls = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) if (!grid.passable(path.get(i))) walls.add(path.get(i));
        return new Route(walls, path);
    }

    /**
     * {@code id} at {@code c} is one of the clearable blocks of a platform whose art layer holds {@code c} (the 128 ×
     * 128 map and its noobline row): the platform's clear removes it anyway, and VERIFY places it again.
     */
    static boolean clearedAnyway(MapArtConfig.SiteCfg site, Pos c, String id) {
        if (site.platforms == null) return false;
        String want = SiteRules.normalizeId(id);
        for (MapArtConfig.PlatformCfg pl : site.platforms) {
            if (pl == null || !pl.enabled || pl.isColorSuppression() || pl.clearableBlocks == null) continue;
            if (c.y() != pl.artY || c.x() < pl.minX || c.x() > pl.minX + 127 || c.z() < pl.minZ - 1
                || c.z() > pl.minZ + 127) {
                continue;
            }
            for (String b : pl.clearableBlocks) if (b != null && SiteRules.normalizeId(b).equals(want)) return true;
        }
        return false;
    }
}
