package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.goals.Goal;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The configured no-go zones ({@code site.noGoZones}) as boxes, and the checks that keep the bot out of them.
 * Pure and unit-tested. The bot is "in" a zone when its feet cell or the head cell
 * above it is: the player is 1.8 blocks high.
 *
 * <ul>
 *   <li><b>Goals</b> ({@link #avoiding}): Baritone gets the caller's goal minus every cell in a zone, and a cell in a
 *       zone looks {@link #PENALTY} further from the goal, so A* expands such cells only after every other one and
 *       never ends a walk in one. ZenithProxy 3.7.2's pathfinder has no per-position cost hook ({@code blocksToAvoid}
 *       works by block type only), so this is what avoidance can do through its API.</li>
 *   <li><b>Paths</b> ({@link #pathViolation}): the walker checks every path Baritone computes (current and next
 *       segment) and stops a walk whose path enters a zone, before it is walked.</li>
 *   <li><b>Corner cuts</b> ({@link #violation}, {@link #cornerFix}): a move whose ends both lie outside every
 *       zone but whose swept box touches one (a diagonal step past a zone's corner) is something the goal's
 *       heuristic cannot keep Baritone from planning, since both its cells look free (ZenithProxy's pathfinder has no
 *       per-move cost hook). With {@code build.noGoCornerDetour} the walker plans again around it: the move's end made
 *       to look far ({@link #avoiding(Goal, Set)}), or a walk through the step's free side cell first.</li>
 *   <li><b>The bot's own position</b> ({@link #deepAt}): the per-tick check stops a task whose feet centre is
 *       {@link #FEET_MARGIN} inside a zone, not one whose feet clip a zone cell's corner.</li>
 *   <li><b>Goals inside zones</b> ({@link #covering}): a goal whose every cell is in a zone fails at once.</li>
 *   <li><b>Stand spots</b>: container, button and lane stand spots in a zone are never chosen ({@link #at}).</li>
 * </ul>
 * A bot already inside a zone (placed there by a teleport, or the zone added while it stood there) may walk out of it;
 * entering any zone again is refused.
 */
public record NoGoZones(List<Zone> zones, int maxDrop) {
    public static final NoGoZones NONE = new NoGoZones(List.of());
    /** Heuristic added for a cell in a zone (Baritone's ActionCosts.COST_INF is 1,000,000). */
    static final double PENALTY = 1_000_000;
    /** {@link #covering} gives up (null) on goals with more cells than this. */
    static final int MAX_GOAL_CELLS = 20_000;

    /** One box, inclusive; {@code label} as the site check prints it ({@code #1 "muncher"}). */
    public record Zone(String label, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean cellsOnly) {
        public Zone(String label, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this(label, minX, minY, minZ, maxX, maxY, maxZ, false);
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /**
         * {@code o} is this zone or another box of the same configured zone: the openings of a colour-suppression job
         * cut a zone into boxes under one label ({@link NoGoZones#of(List, List)}), and a bot on its way out of the
         * zone it started in crosses from one box to the next (live 2026-11-14: off the muncher's rail field).
         */
        public boolean sameZone(@Nullable Zone o) {
            return o != null && label.equals(o.label);
        }

        /** Shares a cell with the inclusive box (x0..x1, y0..y1, z0..z1), lower corner first. */
        boolean intersects(int x0, int y0, int z0, int x1, int y1, int z1) {
            return x0 <= maxX && x1 >= minX && y0 <= maxY && y1 >= minY && z0 <= maxZ && z1 >= minZ;
        }

        @Override
        public String toString() {
            return label + " (" + minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ + ")";
        }
    }

    public NoGoZones {
        zones = List.copyOf(zones);
    }

    public NoGoZones(List<Zone> zones) {
        this(zones, 0);
    }

    /**
     * These zones, and planned paths may drop at most {@code maxDrop} blocks from one cell to the next, water landings
     * included (0 = no limit): near a colour-suppression platform a "fall into water" from the layers cost 16 HP twice
     * in the local test, although ZenithProxy's pathfinder counts water as a safe landing at any height.
     */
    public NoGoZones withMaxDrop(int maxDrop) {
        return new NoGoZones(zones, Math.max(0, maxDrop));
    }

    /**
     * These zones without the boxes of the configured zone {@code label}; the drop limit stays. For a foot route that
     * leads the bot out of that zone ({@link FootRouteTask}): its hops end inside it.
     */
    public NoGoZones without(String label) {
        List<Zone> out = new ArrayList<>();
        for (Zone z : zones) if (!z.label().equals(label)) out.add(z);
        return new NoGoZones(out, maxDrop);
    }

    /** Anything to check: zones, or a drop limit. */
    public boolean checks() {
        return !zones.isEmpty() || maxDrop > 0;
    }

    /** The complete boxes of {@code site.noGoZones} (a box without min or max is skipped; the site check names it). */
    public static NoGoZones of(@Nullable List<RegionCfg> regions) {
        if (regions == null || regions.isEmpty()) return NONE;
        List<Zone> out = new ArrayList<>();
        for (RegionCfg r : regions) {
            if (r == null || r.min == null || r.max == null) continue;
            out.add(new Zone(SiteRules.label(regions, r),
                Math.min(r.min.x(), r.max.x()), Math.min(r.min.y(), r.max.y()), Math.min(r.min.z(), r.max.z()),
                Math.max(r.min.x(), r.max.x()), Math.max(r.min.y(), r.max.y()), Math.max(r.min.z(), r.max.z())));
        }
        return out.isEmpty() ? NONE : new NoGoZones(out);
    }

    /**
     * The zones of {@code regions} without the cells of {@code openings} (the run-time {@code site.openings} of a
     * colour-suppression job): each zone is cut into the boxes around them, under its own label.
     */
    public static NoGoZones of(@Nullable List<RegionCfg> regions, @Nullable List<RegionCfg> openings) {
        NoGoZones all = of(regions);
        if (all.isEmpty() || openings == null || openings.isEmpty()) return all;
        List<int[]> holes = new ArrayList<>();
        for (RegionCfg o : openings) {
            int[] c = SiteRules.corners(o);
            if (c != null) holes.add(c);
        }
        if (holes.isEmpty()) return all;
        List<Zone> out = new ArrayList<>();
        for (Zone z : all.zones())
            for (int[] b : SiteRules.subtract(new int[]{z.minX(), z.minY(), z.minZ(), z.maxX(), z.maxY(), z.maxZ()}, holes))
                out.add(new Zone(z.label(), b[0], b[1], b[2], b[3], b[4], b[5]));
        return out.isEmpty() ? NONE : new NoGoZones(out);
    }

    /**
     * As {@link #of(List, List)}, plus the boxes of {@code hazards} (the lethal edges of a colour-suppression platform,
     * {@code site.hazards}), which openings never cut.
     */
    public static NoGoZones of(@Nullable List<RegionCfg> regions, @Nullable List<RegionCfg> openings,
                               @Nullable List<RegionCfg> hazards) {
        NoGoZones base = of(regions, openings);
        if (hazards == null || hazards.isEmpty()) return base;
        List<Zone> out = new ArrayList<>(base.zones());
        for (Zone h : of(hazards).zones())
            out.add(new Zone(h.label(), h.minX(), h.minY(), h.minZ(), h.maxX(), h.maxY(), h.maxZ(), true));
        return out.isEmpty() ? NONE : new NoGoZones(out);
    }

    public boolean isEmpty() {
        return zones.isEmpty();
    }

    /** Every zone holding the feet cell (x, y, z) or the head cell above it. */
    List<Zone> allAt(Pos p) {
        List<Zone> out = new ArrayList<>(2);
        for (Zone zn : zones) if (zn.contains(p.x(), p.y(), p.z()) || zn.contains(p.x(), p.y() + 1, p.z())) out.add(zn);
        return out;
    }

    /** The zone holding the feet cell (x, y, z) or the head cell above it, or null. */
    public @Nullable Zone at(int x, int y, int z) {
        for (Zone zn : zones) if (zn.contains(x, y, z) || zn.contains(x, y + 1, z)) return zn;
        return null;
    }

    /** {@link #at(int, int, int)} for a feet cell. */
    public @Nullable Zone at(Pos feet) {
        return at(feet.x(), feet.y(), feet.z());
    }

    /**
     * The zone a move between two feet cells touches: the box spanned by both cells with head room (one block over the
     * higher feet cell; two for a move that climbs or jumps more than one block sideways), or null.
     */
    public @Nullable Zone onMove(Pos from, Pos to) {
        int x0 = Math.min(from.x(), to.x()), x1 = Math.max(from.x(), to.x());
        int z0 = Math.min(from.z(), to.z()), z1 = Math.max(from.z(), to.z());
        int y0 = Math.min(from.y(), to.y());
        boolean jump = to.y() > from.y() || x1 - x0 > 1 || z1 - z0 > 1;
        int y1 = Math.max(from.y(), to.y()) + (jump ? 2 : 1);
        for (Zone zn : zones) {
            if (zn.cellsOnly()) {
                // a lethal-edge margin: only the cells walked into count, not a corner a diagonal move cuts
                if (zn.contains(to.x(), to.y(), to.z()) || zn.contains(to.x(), to.y() + 1, to.z())) return zn;
            } else if (zn.intersects(x0, y0, z0, x1, y1, z1)) return zn;
        }
        return null;
    }

    /**
     * Where a path (feet cells in walking order, from where the bot stands) first enters a zone, as text ("enters no-go
     * zone #1 "muncher" (...) at x,y,z"), or null when it stays out. When the bot starts inside zone {@code leaving},
     * the path may run inside that zone until it first leaves it; every later cell and move counts.
     */
    public @Nullable String pathViolation(List<Pos> path, @Nullable Zone leaving) {
        Violation v = violation(path, leaving);
        return v == null ? null : v.text();
    }

    /**
     * Why a path must not be walked ({@link #pathViolation}'s text), and for a move whose two ends are outside every zone
     * while the box it sweeps touches one (a diagonal step past a zone's corner, a jump whose head room reaches into
     * one) those ends: such a move can be planned around ({@link Violation#detour}). Null when the path stays out.
     */
    public record Violation(String text, @Nullable Pos from, @Nullable Pos to, boolean descent) {
        public Violation(String text, @Nullable Pos from, @Nullable Pos to) {
            this(text, from, to, false);
        }

        /**
         * Only the move between two free cells is at fault (a zone's corner cut, or with {@code descent} a step down
         * toward an edge, {@link EdgeDrop#descent}): a path without it may exist.
         */
        public boolean cornerCut() {
            return from != null && to != null;
        }
    }

    /**
     * What the walker does about a corner cut ({@link #cornerFix}): plan again with {@code avoid} looking far, or walk to
     * {@code via} first (the move's free side cell), then on to the goal. Exactly one is set.
     */
    public record CornerFix(@Nullable Pos avoid, @Nullable GoalNearXZ via) { }

    /**
     * The next step around a move that cut a zone's corner ({@link Violation#cornerCut}): the move's end made to look
     * far, when it may be ({@code keep}: the goal's cells and the bot's own cell may not; nor a cell in
     * {@code avoided} already); else, once ({@code viaTried}), a walk through the free side cell of the diagonal step
     * ({@link #freeSide}): the move's two cardinal halves are legal moves; else the move's start made to look far. Null:
     * nothing left, the walk fails as before. Pure.
     */
    public @Nullable CornerFix cornerFix(Violation v, Set<Pos> avoided, boolean viaTried, Predicate<Pos> keep) {
        if (!v.cornerCut()) return null;
        Pos from = v.from();
        Pos to = v.to();
        if (!avoided.contains(to) && !keep.test(to)) return new CornerFix(to, null);
        Pos side = viaTried ? null : freeSide(from, to);
        if (side != null) {
            int dy = Math.abs(from.y() - to.y());
            return new CornerFix(null, new GoalNearXZ(side.x(), Math.max(from.y(), to.y()), side.z(), 0, 1 + dy));
        }
        if (!avoided.contains(from) && !keep.test(from)) return new CornerFix(from, null);
        return null;
    }

    /**
     * For a diagonal step between two horizontally adjacent cells whose one side cell lies in a zone (at either end's
     * level, feet or head): the other side cell, when it is in no zone; the step's two cardinal halves through it touch
     * no zone. Null for any other move. Its y is the higher end's.
     */
    @Nullable Pos freeSide(Pos from, Pos to) {
        if (Math.abs(to.x() - from.x()) != 1 || Math.abs(to.z() - from.z()) != 1) return null;
        int y = Math.max(from.y(), to.y());
        Pos a = new Pos(from.x(), y, to.z());
        Pos b = new Pos(to.x(), y, from.z());
        boolean inA = at(a.x(), from.y(), a.z()) != null || at(a.x(), to.y(), a.z()) != null;
        boolean inB = at(b.x(), from.y(), b.z()) != null || at(b.x(), to.y(), b.z()) != null;
        if (inA == inB) return null;
        return inA ? b : a;
    }

    /**
     * The zone the feet point (x, y, z) stands in by at least {@code margin} blocks horizontally (its feet or head cell
     * vertically, as {@link #at}), or null. The per-tick check of every task ({@code ZenithTask.enteredNoGoZone}) asks
     * this with {@link #FEET_MARGIN}: a feet centre that only clips a zone cell at a corner or an edge while the bot
     * turns (live 2026-10-13: one tick, leaving a stand cell beside a zone) is no entry; walking in is.
     */
    public @Nullable Zone deepAt(double x, double y, double z, double margin) {
        int fy = (int) Math.floor(y + 0.1251);
        for (Zone zn : zones) {
            boolean level = (fy >= zn.minY() && fy <= zn.maxY()) || (fy + 1 >= zn.minY() && fy + 1 <= zn.maxY());
            if (!level) continue;
            if (x >= zn.minX() + margin && x <= zn.maxX() + 1 - margin && z >= zn.minZ() + margin
                && z <= zn.maxZ() + 1 - margin) return zn;
        }
        return null;
    }

    /** {@link #deepAt}'s margin for the live feet check: the player's half width. */
    public static final double FEET_MARGIN = 0.3;

    /** {@link #pathViolation} with the move's ends when only the move touches a zone ({@link Violation#cornerCut}). */
    public @Nullable Violation violation(List<Pos> path, @Nullable Zone leaving) {
        if (!checks() || path.isEmpty()) return null;
        boolean inside = leaving != null;
        // every zone the start lies in counts as left (zones overlap: a lethal-edge margin over a no-go zone)
        java.util.Set<String> starting = new java.util.HashSet<>();
        for (Zone z : allAt(path.getFirst())) starting.add(z.label());
        if (leaving != null) starting.add(leaving.label());
        Pos prev = null;
        for (Pos p : path) {
            if (maxDrop > 0 && prev != null && prev.y() - p.y() > maxDrop)
                return new Violation("drops " + (prev.y() - p.y()) + " blocks at " + p.key() + " (at most " + maxDrop
                    + " here)", null, null);
            Zone hit;
            boolean cut = false;
            if (inside) {
                List<Zone> here = allAt(p);
                if (!here.isEmpty() && here.stream().allMatch(z -> starting.contains(z.label()))) {
                    prev = p;
                    continue; // still on the way out of the zones it started in
                }
                inside = false;
                hit = here.isEmpty() ? null : here.getFirst(); // the first cell outside them: only the cell itself
                for (Zone h : here) if (!starting.contains(h.label())) hit = h;
            } else {
                hit = prev == null ? at(p) : onMove(prev, p);
                cut = hit != null && prev != null && at(prev) == null && at(p) == null;
            }
            if (hit != null) return new Violation("enters no-go zone " + hit + " at " + p.key(), cut ? prev : null,
                cut ? p : null);
            prev = p;
        }
        return null;
    }

    /**
     * A zone holding every feet cell of the goal (the walk could only end inside a zone), or null: some cell is free,
     * or the goal is too large to check ({@link #MAX_GOAL_CELLS}).
     */
    public @Nullable Zone covering(GoalNearXZ g) {
        if (zones.isEmpty()) return null;
        int r = Math.max(0, g.range());
        int t = Math.max(0, g.yTolerance());
        if ((long) (2 * r + 1) * (2 * r + 1) * (2 * t + 1) > MAX_GOAL_CELLS) return null;
        Zone first = null;
        for (int dy = -t; dy <= t; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (!GoalNearXZ.inGoal(dx, dy, dz, r, t)) continue;
                    Zone zn = at(g.x() + dx, g.y() + dy, g.z() + dz);
                    if (zn == null) return null;
                    if (first == null) first = zn;
                }
        return first;
    }

    /** The goal Baritone is given: {@code goal} itself without zones, else {@link Avoiding}. */
    public Goal avoiding(Goal goal) {
        return avoiding(goal, Set.of());
    }

    /**
     * {@link #avoiding(Goal)}, with the feet cells {@code detour} also looking {@link #PENALTY} further from the goal
     * (they stay in the goal and walkable: A* only takes them when nothing else gets there). The ends of moves that cut
     * a zone's corner ({@link Violation#detour}).
     */
    public Goal avoiding(Goal goal, Set<Pos> detour) {
        return avoiding(goal, detour, null);
    }

    /**
     * {@link #avoiding(Goal, Set)}, with the tile's border tops ({@link BorderTop}, {@code build.laneStartOnFloor}; null:
     * none) also looking {@link #PENALTY} further and never in the goal: a walk to a lane start stays off the wall along
     * the soul sand tile's south edge.
     */
    public Goal avoiding(Goal goal, Set<Pos> detour, @Nullable BorderTop border) {
        return zones.isEmpty() && detour.isEmpty() && border == null ? goal
            : new Avoiding(goal, this, Set.copyOf(detour), border);
    }

    /**
     * {@code inner} minus the cells in a zone (and on a {@code border} top), with {@link #PENALTY} added to the heuristic
     * of a cell in a zone (and of a {@code detour} cell or a border top) so A* expands it only after every other cell. A
     * record: Baritone compares goals with equals ({@code isGoalActive}).
     */
    public record Avoiding(Goal inner, NoGoZones zones, Set<Pos> detour, @Nullable BorderTop border) implements Goal {
        public Avoiding(Goal inner, NoGoZones zones) {
            this(inner, zones, Set.of());
        }

        public Avoiding(Goal inner, NoGoZones zones, Set<Pos> detour) {
            this(inner, zones, detour, null);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return inner.isInGoal(x, y, z) && zones.at(x, y, z) == null && (border == null || !border.contains(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double h = inner.heuristic(x, y, z);
            boolean far = zones.at(x, y, z) != null || (!detour.isEmpty() && detour.contains(new Pos(x, y, z)))
                || (border != null && border.contains(x, y, z));
            return far ? h + PENALTY : h;
        }

        @Override
        public double heuristic() {
            return inner.heuristic();
        }

        @Override
        public String toString() {
            return inner + " (avoiding " + zones.zones().size() + " no-go zone" + (zones.zones().size() == 1 ? "" : "s")
                + (detour.isEmpty() ? "" : " and " + detour.size() + " corner cell" + (detour.size() == 1 ? "" : "s"))
                + (border == null ? "" : " and the tile's border tops")
                + ")";
        }
    }
}
