package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Where to stand to use a block (a chest, barrel, cartography table, anvil): a floor cell from which the centre of
 * one of the block's faces is within reach of the eye and in line of sight.
 * Pure and unit-tested; the world comes through {@link World}, the raycast through {@link Sight}.
 *
 * <p>Candidates are the cells around the target (horizontally within the reach, from {@link #BELOW} under it to
 * {@link #ABOVE} over it) a player can stand in: no collision and no fluid at the feet (or a low block such as a
 * carpet or slab to stand on), a solid enough support below, head room for the 1.8-high box. The eye is taken over the
 * cell centre (the bot centres itself there), with {@link #CENTRE_MARGIN} of the reach kept spare.
 *
 * <p><b>Reachability.</b> A candidate must be one the bot can walk to: {@link #pathCosts} floods the standable cells
 * from the bot's feet (Baritone's moves: a step to a side, a step up of at most one block with a jump and head room
 * for it, a drop of at most {@link #MAX_DROP}), within {@link #REACH_RADIUS} blocks of the target, the box grown to
 * hold the bot up to {@link #REACH_FAR} blocks away. The world outside the box leads back in only at edge cells that
 * can be stepped onto from beyond the edge, at the level of the bot or of an edge cell the flood left the box from
 * ({@link #EDGE_BAND}): not on the top of a long sorter wall or a roof running out of the box. A bot further away
 * starts from the edge cells on its side at its own level. A cell the flood does not reach (a walled-in pit behind a
 * column: gate B; the top of the sorter wall) is not returned. When the flood cannot tell (the bot's own feet stand on
 * nothing, too many cells, far away on another level), the old straight-line ranking is used for every cell.
 *
 * <p><b>Ranking.</b> Configured {@code first} cells (an actuator's {@code standAt}: trusted, not flood-checked), the
 * cell the bot stands in, a {@code preferred} cell (the one that worked last time) if it is reachable, then by walking
 * distance (a jump costs {@link #JUMP_COST} more), the straight-line distance breaking ties. Line of sight is checked
 * lazily in that order. Cells the world does not {@linkplain World#allowed allow} are never returned, except a
 * configured first cell; the flood still walks through them. The rules are {@link CellRule}s: the art layer and the
 * flying machines' sweep for buttons and levers ({@link #awayFromClearSweeps}), only the art layer for a placement
 * ({@link #offArtLayers}). A failure counts the floor cells in reach a rule left out, with its reason
 * ({@link Search#describe}), apart from those the bot cannot walk to.
 */
public final class StandSpots {
    private StandSpots() { }

    /** A low block in the feet cell (carpet 1/16, slab 1/2, snow layers) is stood on; anything taller is a wall. */
    static final double LOW_BLOCK = 0.5;
    /** A support whose collision top is at least this (soul sand, mud, chests: 14/16) carries the feet into the cell. */
    static final double SUPPORT_MIN = 0.8;
    /** Feet cells searched from this many blocks under the target ... */
    static final int BELOW = 4;
    /** ... to this many over it. */
    static final int ABOVE = 2;
    /** The bot centres within 0.15 of the spot's centre: the sight check from the centre keeps this much reach spare. */
    public static final double CENTRE_MARGIN = 0.2;
    /** Ranking cost of one block of floor level change (stairs or a jump away, often no path at all). */
    static final double CLIMB_COST = 4;
    /** Reachability: horizontal blocks searched around the target. */
    static final int REACH_RADIUS = 24;
    /**
     * Reachability: the box grows towards the bot to hold it, with {@link #BOT_MARGIN} around it, up to this many
     * blocks beyond the targets (along x and along z); a bot further out is far away.
     */
    static final int REACH_FAR = 64;
    /** Reachability: blocks around the bot kept inside the grown box (room to walk round what is next to it). */
    static final int BOT_MARGIN = 8;
    /**
     * Reachability: the world outside the box connects edge cells only at feet within this of a level known to lead
     * out (the bot's feet, a box edge cell the flood reached).
     */
    static final double EDGE_BAND = 1.0;
    /** Reachability: rounds of edge cells the flood reached and steps out from, leading back in at their level. */
    static final int EDGE_ROUNDS = 3;
    /** Reachability: feet levels searched below the lowest target ... */
    static final int REACH_DOWN = 10;
    /** ... and above the highest. */
    static final int REACH_UP = 8;
    /** Reachability: most cells expanded; beyond it the straight-line ranking is used (tick-thread budget). */
    static final int REACH_MAX_NODES = 40_000;
    /** Walking cost of a step up (a jump), on top of the step. */
    static final double JUMP_COST = 1;
    /** Highest drop walked off (Baritone's maxFallHeightNoWater). */
    static final int MAX_DROP = 3;
    /** A rise of more than this between two cells needs a jump (a slab or carpet step does not). */
    static final double STEP_HEIGHT = 0.6;
    /** Ranking cost of a configured first cell: before everything else, in the order given. */
    static final double FIRST_COST = -100;
    /**
     * The flying machines park this far beyond the tile's edges (moss: about 10) and sweep the whole tile between,
     * on the art layer and up to {@link #SWEEP_HEIGHT} - 1 above it (moss: 3).
     */
    public static final int SWEEP_MARGIN = 16;
    /** Feet at least this far above the art layer are above every flying machine (moss: the centre structure is +6). */
    public static final int SWEEP_HEIGHT = 5;

    /** Never stood in (Baritone avoids them too, or they hurt). */
    private static final Set<String> AVOID_IN = Set.of("minecraft:fire", "minecraft:soul_fire", "minecraft:cobweb",
        "minecraft:sweet_berry_bush", "minecraft:powder_snow", "minecraft:wither_rose", "minecraft:tripwire",
        "minecraft:nether_portal", "minecraft:end_portal", "minecraft:bubble_column", "minecraft:pointed_dripstone");
    /** Never stood on. */
    private static final Set<String> AVOID_ON = Set.of("minecraft:magma_block", "minecraft:campfire",
        "minecraft:soul_campfire", "minecraft:cactus");

    /** The cached world. */
    public interface World {
        boolean isLoaded(Pos pos);

        /** Full block state; air for unloaded positions. */
        BlockSpec block(Pos pos);

        boolean hasFluid(Pos pos);

        /** Highest collision box top of the state, block-local: 0 none (air, signs, flowers), 1 full, 1.5 fences. */
        double collisionTop(BlockSpec spec);

        /** The bot may stand with its feet in this cell (default: anywhere). Configured first cells are not asked. */
        default boolean allowed(Pos feet) {
            return true;
        }

        /** Why {@link #allowed} refuses this cell, for messages ("inside the clear sweep"). */
        default String excludedBecause(Pos feet) {
            return "not allowed";
        }

        /**
         * The pathfinder walks onto a cell whose floor is {@code floor}, the block under the feet ({@link #walkableFloor};
         * default: any floor {@link #feetY} takes).
         */
        default boolean walksOn(BlockSpec floor) {
            return true;
        }
    }

    /** Reason for a cell whose floor the pathfinder does not walk onto ({@link #walkableFloor}). */
    public static final String UNWALKABLE_FLOOR = "on a floor the pathfinder does not walk onto";

    /** Reason for a cell where the bot's box would fill the cell a block is placed in ({@link #covers}). */
    public static final String IN_THE_WAY = "in the way of the block being placed";

    /**
     * Where the bot may stand, and the words for a cell it refuses ({@code refused}: "on the art layer"), which a
     * failure names with the number of floor cells in reach it left out.
     */
    public record CellRule(Predicate<Pos> allows, String refused) implements Predicate<Pos> {
        /** Every cell (containers). */
        public static final CellRule ANYWHERE = new CellRule(p -> true, "not allowed");

        @Override
        public boolean test(Pos feet) {
            return allows.test(feet);
        }
    }

    /** The target's faces as seen from an eye (a real raycast against the cached world, wall signs included). */
    public interface Sight {
        /** The centre of a face of the target is in reach and in line of sight from an eye at (ex, ey, ez). */
        boolean visibleFrom(double ex, double ey, double ez, double reach);

        /** Why no face centre is usable from that eye, for messages ("minecraft:oak_wall_sign at [x,y,z]"). */
        String hiddenFrom(double ex, double ey, double ez, double reach);

        /** Eye height above the feet the bot will click with (lower while it sneaks). */
        default double eyeHeight() {
            return Aim.EYE_HEIGHT;
        }
    }

    /**
     * A cell to stand in: feet cell (x, y, z), feet height {@code feetY}, eye height above the feet, ranking cost;
     * {@code reachable}: the flood from the bot reached it (a walk there that fails is not worth retrying).
     */
    public record Spot(int x, int y, int z, double feetY, double eyeHeight, double cost, boolean reachable) {
        public Pos cell() {
            return new Pos(x, y, z);
        }

        public double eyeX() {
            return x + 0.5;
        }

        public double eyeY() {
            return feetY + eyeHeight;
        }

        public double eyeZ() {
            return z + 0.5;
        }
    }

    /**
     * Result of a search: up to {@code max} spots, best first, and for messages the number of floor cells around the
     * target, of those within reach, of those the bot cannot walk to, of those whose sight was checked and failed, with
     * an example of what hid it. {@code excluded}: floor cells within reach that the world does not
     * {@linkplain World#allowed allow}, by {@linkplain World#excludedBecause reason}; not in the other counts.
     */
    public record Search(List<Spot> spots, int floorCells, int inReach, int unreachable, int hidden,
                         @Nullable String example, List<String> firstRejected, Map<String, Integer> excluded) {
        /** Why there is no spot (or how many were found). */
        public String describe(double reach) {
            if (!spots.isEmpty()) return spots.size() + " stand spots";
            String more = excludedText() + (firstRejected.isEmpty() ? "" : "; configured " + String.join(", ", firstRejected));
            if (floorCells == 0) return "no " + (excluded.isEmpty() ? "" : "other ") + "floor to stand on around it" + more;
            if (inReach == 0) return "none of " + floorCells + " floor cells around it is within reach (" + reach + ")" + more;
            if (unreachable >= inReach) {
                return "none of the " + inReach + " floor cells within reach can be walked to from the bot (walled in,"
                    + " or more than a block up)" + more;
            }
            return "no face center in sight from any of " + (inReach - unreachable) + " floor cells within reach"
                + (example != null ? " (" + example + ")" : "")
                + (unreachable > 0 ? "; " + unreachable + " more cannot be walked to" : "") + more;
        }

        /** Floor cells within reach left out: "; 40 floor cells within reach excluded: inside the clear sweep". */
        private String excludedText() {
            if (excluded.isEmpty()) return "";
            int n = 0;
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : excluded.entrySet()) {
                n += e.getValue();
                parts.add(e.getValue() + " " + e.getKey());
            }
            return "; " + n + " floor cells within reach excluded: "
                + (excluded.size() == 1 ? excluded.keySet().iterator().next() : String.join(", ", parts));
        }
    }

    /** Feet cell of a player at (x, y, z): ZenithProxy's convention (y + 0.1251, for soul sand and chests). */
    public static Pos feetCell(double x, double y, double z) {
        return new Pos((int) Math.floor(x), (int) Math.floor(y + 0.1251), (int) Math.floor(z));
    }

    /** Feet height of a player standing in cell (x, y, z), or NaN if one cannot stand there. */
    public static double feetY(World w, int x, int y, int z) {
        Pos feet = new Pos(x, y, z);
        if (!w.isLoaded(feet) || w.hasFluid(feet)) return Double.NaN;
        BlockSpec f = w.block(feet);
        if (AVOID_IN.contains(f.id())) return Double.NaN;
        double tf = f.isAir() ? 0 : w.collisionTop(f);
        double fy;
        if (tf > 0) {
            if (tf > LOW_BLOCK) return Double.NaN; // a wall at feet height (or it is stood on from the cell above)
            fy = y + tf;
        } else {
            Pos below = feet.below();
            if (!w.isLoaded(below)) return Double.NaN;
            BlockSpec b = w.block(below);
            if (AVOID_ON.contains(b.id())) return Double.NaN;
            double tb = b.isAir() ? 0 : w.collisionTop(b);
            if (tb < SUPPORT_MIN || tb > 1.0 + 1e-9) return Double.NaN; // nothing to stand on, or a fence / wall
            fy = y - 1 + tb;
        }
        for (int dy = 1; y + dy < fy + Aim.PLAYER_HEIGHT - 1e-9; dy++) {
            Pos p = new Pos(x, y + dy, z);
            BlockSpec h = w.block(p);
            if (w.hasFluid(p) || AVOID_IN.contains(h.id()) || (!h.isAir() && w.collisionTop(h) > 0)) return Double.NaN;
        }
        return fy;
    }

    /**
     * The pathfinder can end a walk in cell {@code c} ({@link World#walksOn} of its floor, {@code build.standSpotWalkable}):
     * a decorated pot or a hopper has a collision top of 1 and {@link #feetY} stands on it, but ZenithProxy's
     * pathfinder never walks onto one (live 2026-10-23, soul sand: "No path found" for two walkway cells over pots).
     * Feet in a low block (a slab, a carpet): true, as before. Search candidates only; the flood and configured first
     * cells keep {@link #feetY}.
     */
    public static boolean walkableFloor(World w, Pos c) {
        BlockSpec f = w.block(c);
        if (!f.isAir() && w.collisionTop(f) > 0) return true;
        return w.walksOn(w.block(c.below()));
    }

    /** {@link #search} without configured first cells. */
    public static Search search(World w, Sight sight, List<Pos> targets, double reach, double botX, double botY,
                                double botZ, @Nullable Pos preferred, Set<Pos> exclude, int max) {
        return search(w, sight, targets, reach, botX, botY, botZ, List.of(), preferred, exclude, max);
    }

    /**
     * Up to {@code max} stand spots for using a block made of {@code targets} (both halves of a double chest), best
     * first, never one of {@code exclude}. {@code first}: configured cells tried before anything else, in order, also
     * outside the search box and whatever {@link World#allowed} says (an actuator's {@code standAt}; the cell above
     * it if it names the block stood on). {@code bot*} is the player's feet position now.
     */
    public static Search search(World w, Sight sight, List<Pos> targets, double reach, double botX, double botY,
                                double botZ, List<Pos> first, @Nullable Pos preferred, Set<Pos> exclude, int max) {
        Pos botCell = feetCell(botX, botY, botZ);
        double near = reach - CENTRE_MARGIN;
        int r = (int) Math.ceil(reach);
        Set<Pos> seen = new HashSet<>();
        List<Spot> cands = new ArrayList<>();
        List<String> firstRejected = new ArrayList<>();
        Set<Pos> firstCells = new HashSet<>();
        // walking costs from the bot, computed once when the first cell needs them (null: unknown, straight-line ranking)
        Supplier<@Nullable Map<Pos, Double>> flood = new Supplier<>() {
            private boolean done;
            private @Nullable Map<Pos, Double> costs;

            @Override
            public @Nullable Map<Pos, Double> get() {
                if (!done) {
                    done = true;
                    costs = pathCosts(w, targets, botX, botY, botZ);
                }
                return costs;
            }
        };
        for (int i = 0; i < first.size(); i++) {
            Pos c = firstCell(w, first.get(i));
            if (c == null) {
                firstRejected.add(first.get(i) + ": cannot stand there");
                continue;
            }
            if (exclude.contains(c) || !firstCells.add(c)) continue;
            double fy = feetY(w, c.x(), c.y(), c.z());
            Map<Pos, Double> costs = flood.get();
            Spot s = new Spot(c.x(), c.y(), c.z(), fy, sight.eyeHeight(), FIRST_COST + i,
                costs != null && costs.containsKey(c));
            if (!sight.visibleFrom(s.eyeX(), s.eyeY(), s.eyeZ(), near)) {
                firstRejected.add(first.get(i) + ": " + sight.hiddenFrom(s.eyeX(), s.eyeY(), s.eyeZ(), near));
                continue;
            }
            cands.add(s);
        }
        int floor = 0;
        int inReach = 0;
        int unreachable = 0;
        Map<String, Integer> excluded = new LinkedHashMap<>();
        for (Pos t : targets) {
            for (int dy = -BELOW; dy <= ABOVE; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        Pos c = t.offset(dx, dy, dz);
                        if (!seen.add(c) || targets.contains(c) || targets.contains(c.below())) continue;
                        if (firstCells.contains(c)) continue;
                        double fy = feetY(w, c.x(), c.y(), c.z());
                        if (Double.isNaN(fy)) continue;
                        // where it stands already it needs no path there
                        boolean walkable = c.equals(botCell) || walkableFloor(w, c);
                        boolean allowed = walkable && w.allowed(c);
                        if (allowed) floor++;
                        double ex = c.x() + 0.5;
                        double ey = fy + sight.eyeHeight();
                        double ez = c.z() + 0.5;
                        if (!anyInReach(ex, ey, ez, targets, near)) continue;
                        if (!allowed) { // counted apart: a rule, not the world, left it out
                            excluded.merge(walkable ? w.excludedBecause(c) : UNWALKABLE_FLOOR, 1, Integer::sum);
                            continue;
                        }
                        inReach++;
                        if (exclude.contains(c)) continue;
                        double straight = Math.hypot(ex - botX, ez - botZ) + CLIMB_COST * Math.abs(fy - botY);
                        Map<Pos, Double> costs = flood.get();
                        Double walk = costs == null ? null : costs.get(c);
                        if (costs != null && walk == null) {
                            unreachable++;
                            continue;
                        }
                        double cost;
                        if (c.equals(botCell)) cost = -2;
                        else if (c.equals(preferred)) cost = -1;
                        else cost = walk == null ? straight : walk + 1e-3 * straight;
                        cands.add(new Spot(c.x(), c.y(), c.z(), fy, sight.eyeHeight(), cost, walk != null));
                    }
                }
            }
        }
        cands.sort(Comparator.comparingDouble(Spot::cost).thenComparingInt(Spot::y).thenComparingInt(Spot::x)
            .thenComparingInt(Spot::z));
        List<Spot> out = new ArrayList<>();
        int hidden = 0;
        Spot closest = null; // the rejected cell nearest the target explains the rejections best
        double closestD = Double.POSITIVE_INFINITY;
        for (Spot s : cands) {
            if (out.size() >= max) break;
            if (s.cost() < FIRST_COST + first.size()) { // a first cell: sight checked above
                out.add(s);
                continue;
            }
            if (sight.visibleFrom(s.eyeX(), s.eyeY(), s.eyeZ(), near)) {
                out.add(s);
                continue;
            }
            hidden++;
            double d = centreDistSq(s.eyeX(), s.eyeY(), s.eyeZ(), targets);
            if (d < closestD) {
                closestD = d;
                closest = s;
            }
        }
        String example = closest == null ? null
            : "from " + closest.cell() + ": " + sight.hiddenFrom(closest.eyeX(), closest.eyeY(), closest.eyeZ(), near);
        return new Search(out, floor, inReach, unreachable, hidden, example, firstRejected,
            Collections.unmodifiableMap(excluded));
    }

    /**
     * Walking cost from the bot to every standable cell it can reach near the targets: one per step,
     * {@link #JUMP_COST} more for a step up. Moves as Baritone's: to a side neighbour at the same level (a slab or
     * carpet step included), one block up with a jump (head room above the bot), or down at most {@link #MAX_DROP}
     * (the column above the landing cell clear up to the bot's head).
     *
     * <p>The box: {@link #REACH_RADIUS} blocks around the targets horizontally, feet {@link #REACH_DOWN} below to
     * {@link #REACH_UP} above them, grown towards the bot to hold it with {@link #BOT_MARGIN} around it, up to
     * {@link #REACH_FAR} beyond the targets.
     * <ul>
     *   <li><b>Bot in the box</b> (feet up to {@link #REACH_DOWN} more below or {@link #REACH_UP} more above): the flood
     *       starts at its feet cell (or the standable cell up to 2 below it, mid-jump).</li>
     *   <li><b>Bot further away</b> (or on nothing outside the targets' own box): the starts are the edge cells on the
     *       box's faces towards the bot, at the bot's level, at their straight-line cost from it.</li>
     *   <li><b>The world outside</b> may lead back into the box: an edge cell is entered from outside only when it can
     *       be stepped onto from the cell beyond the edge (one block up at most) and its feet are within
     *       {@link #EDGE_BAND} of the bot's or of an edge cell the flood reached and can step out from; at that cost
     *       plus the straight line. The top of a long wall or a roof running out of the box, 4 or 5 blocks above the
     *       walkway, is therefore not reachable from the walkway (it was when every edge cell counted).</li>
     * </ul>
     * Null when it cannot tell: the bot stands on nothing in the targets' own box, nothing to start from (far away on
     * another level), or more than {@link #REACH_MAX_NODES} cells even in the targets' own box. A grown box that has too
     * many cells is given up for the targets' own box, as from far away.
     */
    static @Nullable Map<Pos, Double> pathCosts(World w, List<Pos> targets, double botX, double botY, double botZ) {
        if (targets.isEmpty()) return null;
        int tx0 = Integer.MAX_VALUE, tx1 = Integer.MIN_VALUE, ty0 = Integer.MAX_VALUE, ty1 = Integer.MIN_VALUE;
        int tz0 = Integer.MAX_VALUE, tz1 = Integer.MIN_VALUE;
        for (Pos t : targets) {
            tx0 = Math.min(tx0, t.x());
            tx1 = Math.max(tx1, t.x());
            ty0 = Math.min(ty0, t.y());
            ty1 = Math.max(ty1, t.y());
            tz0 = Math.min(tz0, t.z());
            tz1 = Math.max(tz1, t.z());
        }
        Pos botCell = feetCell(botX, botY, botZ);
        Box own = new Box(tx0 - REACH_RADIUS, tx1 + REACH_RADIUS, ty0 - REACH_DOWN, ty1 + REACH_UP,
            tz0 - REACH_RADIUS, tz1 + REACH_RADIUS);
        Box grown = new Box(
            Math.max(Math.min(own.x0, botCell.x() - BOT_MARGIN), tx0 - REACH_FAR),
            Math.min(Math.max(own.x1, botCell.x() + BOT_MARGIN), tx1 + REACH_FAR),
            own.y0, own.y1,
            Math.max(Math.min(own.z0, botCell.z() - BOT_MARGIN), tz0 - REACH_FAR),
            Math.min(Math.max(own.z1, botCell.z() + BOT_MARGIN), tz1 + REACH_FAR));
        Lead bot = new Lead(botX, botY, botZ, 0);
        boolean level = botCell.y() >= own.y0 - REACH_DOWN && botCell.y() <= own.y1 + REACH_UP;
        if (level && grown.holdsColumn(botCell.x(), botCell.z())) {
            Pos start = null;
            for (int dy = 0; dy >= -2 && start == null; dy--) {
                Pos c = botCell.offset(0, dy, 0);
                if (!Double.isNaN(feetY(w, c.x(), c.y(), c.z()))) start = c;
            }
            if (start == null && own.holdsColumn(botCell.x(), botCell.z())) return null; // on nothing: cannot tell
            if (start != null) {
                grown.y0 = Math.min(grown.y0, start.y() - 2);
                grown.y1 = Math.max(grown.y1, start.y() + 2);
                Flood f = new Flood(w, grown);
                f.seed(start, 0);
                Map<Pos, Double> costs = f.spread(bot, Box.ALL_FACES);
                if (!f.overflowed) return costs;
            }
            // on nothing beside the targets' box, or too many cells: towards the targets' own box, as from far away
            return new Flood(w, own).spread(bot, own.facesTowards(botCell));
        }
        Flood f = new Flood(w, grown);
        Map<Pos, Double> costs = f.spread(bot, grown.facesTowards(botCell));
        if (!f.overflowed) return costs;
        return new Flood(w, own).spread(bot, own.facesTowards(botCell));
    }

    /** A known way out of the box: feet position and walking cost from the bot (the bot itself: 0). */
    private record Lead(double x, double feetY, double z, double cost) { }

    /** The reachability search's bounds (inclusive). */
    private static final class Box {
        /** Face bits, by outward direction: -x, +x, -z, +z. */
        static final int ALL_FACES = 0b1111;
        static final int[][] OUT = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        final int x0, x1, z0, z1;
        int y0, y1;

        Box(int x0, int x1, int y0, int y1, int z0, int z1) {
            this.x0 = x0;
            this.x1 = x1;
            this.y0 = y0;
            this.y1 = y1;
            this.z0 = z0;
            this.z1 = z1;
        }

        boolean holds(int x, int y, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1 && y >= y0 && y <= y1;
        }

        boolean holdsColumn(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }

        /** The faces column (x, z) lies on, as face bits (0 inside or outside). */
        int faces(int x, int z) {
            if (!holdsColumn(x, z)) return 0;
            return (x == x0 ? 1 : 0) | (x == x1 ? 2 : 0) | (z == z0 ? 4 : 0) | (z == z1 ? 8 : 0);
        }

        /** The faces a cell outside the box lies beyond (0 for a column inside). */
        int facesTowards(Pos p) {
            return (p.x() < x0 ? 1 : 0) | (p.x() > x1 ? 2 : 0) | (p.z() < z0 ? 4 : 0) | (p.z() > z1 ? 8 : 0);
        }
    }

    /** Dijkstra over standable feet cells ({@link #pathCosts}). */
    private static final class Flood {
        private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        private final World w;
        private final Box box;
        private final Map<Pos, Double> feet = new HashMap<>();
        private final Map<Pos, Double> best = new HashMap<>();
        private final PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(Node::cost));
        /** Standable cells on the box's faces (lazily). */
        private @Nullable List<Edge> edges;
        /** Edge cells already used as a way out. */
        private final Set<Pos> led = new HashSet<>();
        private int expanded;
        boolean overflowed;

        private record Node(Pos cell, double cost) { }

        /** A standable cell on the faces {@code faces} of the box; {@code entries}: the faces it is entered across (-1: unknown). */
        private static final class Edge {
            final Pos cell;
            final double feetY;
            final int faces;
            int entries = -1;

            Edge(Pos cell, double feetY, int faces) {
                this.cell = cell;
                this.feetY = feetY;
                this.faces = faces;
            }
        }

        Flood(World w, Box box) {
            this.w = w;
            this.box = box;
        }

        double feet(int x, int y, int z) {
            Pos p = new Pos(x, y, z);
            Double v = feet.get(p);
            if (v == null) {
                v = feetY(w, x, y, z);
                feet.put(p, v);
            }
            return v;
        }

        /** True if it lowered the cell's cost. */
        boolean seed(Pos c, double cost) {
            if (cost < best.getOrDefault(c, Double.POSITIVE_INFINITY)) {
                best.put(c, cost);
                queue.add(new Node(c, cost));
                return true;
            }
            return false;
        }

        /**
         * Runs what is seeded, then lets the world outside lead back in: edge cells on {@code firstFaces} at the bot's
         * level, then on every face at the level of the edge cells reached that step out of the box, for
         * {@link #EDGE_ROUNDS} rounds. Null when nothing was reached or the flood ran over {@link #REACH_MAX_NODES}.
         */
        @Nullable Map<Pos, Double> spread(Lead bot, int firstFaces) {
            if (queue.isEmpty() && firstFaces == 0) return null; // nothing to start from
            if (!run()) return null;
            List<Lead> leads = List.of(bot);
            int faces = firstFaces;
            for (int round = 0; round <= EDGE_ROUNDS && !leads.isEmpty(); round++) {
                if (seedEdges(leads, faces) && !run()) return null;
                leads = waysOut();
                faces = Box.ALL_FACES;
            }
            return best.isEmpty() ? null : best;
        }

        /** Dijkstra until the queue is empty; false when over {@link #REACH_MAX_NODES} expansions in all. */
        private boolean run() {
            while (!queue.isEmpty()) {
                Node n = queue.poll();
                if (n.cost() > best.getOrDefault(n.cell(), Double.POSITIVE_INFINITY)) continue;
                if (++expanded > REACH_MAX_NODES) {
                    overflowed = true;
                    return false;
                }
                expand(n.cell(), n.cost());
            }
            return true;
        }

        private void expand(Pos c, double cost) {
            double fc = feet(c.x(), c.y(), c.z());
            for (int[] d : SIDES) {
                Pos n = step(c, d[0], d[1], true);
                if (n == null) continue;
                double rise = feet(n.x(), n.y(), n.z()) - fc;
                seed(n, cost + 1 + (rise > STEP_HEIGHT ? JUMP_COST : 0));
            }
        }

        /**
         * Where a step from {@code c} to the side ({@code dx}, {@code dz}) lands: the highest standable cell of that
         * column from one up to {@link #MAX_DROP} down, if the move works; else null. {@code boxed}: inside the box only.
         */
        private @Nullable Pos step(Pos c, int dx, int dz, boolean boxed) {
            double fc = feet(c.x(), c.y(), c.z());
            int nx = c.x() + dx;
            int nz = c.z() + dz;
            for (int dy = 1; dy >= -MAX_DROP; dy--) {
                int ny = c.y() + dy;
                if (boxed && ny > box.y1) continue;
                if (boxed && !box.holds(nx, ny, nz)) return null;
                double fn = feet(nx, ny, nz);
                if (Double.isNaN(fn)) continue;
                double rise = fn - fc;
                boolean ok;
                if (rise > 1.0 + 1e-6) {
                    ok = false;
                } else if (rise > STEP_HEIGHT) {
                    ok = clear(c.x(), c.y() + 2, c.z()); // head room for the jump
                } else if (rise >= -STEP_HEIGHT) {
                    ok = true;
                } else {
                    ok = -rise <= MAX_DROP + 1e-6;
                    for (int y = ny + 2; ok && y <= c.y() + 1; y++) ok = clear(nx, y, nz); // the way down
                }
                return ok ? new Pos(nx, ny, nz) : null;
            }
            return null;
        }

        private List<Edge> edges() {
            if (edges == null) {
                List<Edge> out = new ArrayList<>();
                for (int x = box.x0; x <= box.x1; x++) {
                    boolean side = x == box.x0 || x == box.x1;
                    // a side column whole, else only the two face cells of this x
                    for (int z = box.z0; z <= box.z1; z = side || z == box.z1 ? z + 1 : box.z1) {
                        int faces = box.faces(x, z);
                        for (int y = box.y0; y <= box.y1; y++) {
                            double fy = feet(x, y, z);
                            if (!Double.isNaN(fy)) out.add(new Edge(new Pos(x, y, z), fy, faces));
                        }
                    }
                }
                edges = out;
            }
            return edges;
        }

        /**
         * Seeds the edge cells on {@code faces} the world outside leads into: feet within {@link #EDGE_BAND} of a lead,
         * at its cost plus the straight line and the climb, if it can be {@linkplain #entries entered} across one of
         * them. True if any cost fell.
         */
        private boolean seedEdges(List<Lead> leads, int faces) {
            boolean any = false;
            for (Edge e : edges()) {
                if ((e.faces & faces) == 0) continue;
                double cost = Double.POSITIVE_INFINITY;
                for (Lead l : leads) {
                    double dh = Math.abs(e.feetY - l.feetY());
                    if (dh > EDGE_BAND + 1e-6) continue;
                    cost = Math.min(cost, l.cost() + Math.hypot(e.cell.x() + 0.5 - l.x(), e.cell.z() + 0.5 - l.z())
                        + CLIMB_COST * dh);
                }
                if (cost == Double.POSITIVE_INFINITY || cost >= best.getOrDefault(e.cell, Double.POSITIVE_INFINITY)) {
                    continue;
                }
                if (e.entries < 0) e.entries = entries(e);
                if ((e.entries & faces) != 0) any |= seed(e.cell, cost);
            }
            return any;
        }

        /**
         * The faces across which the edge cell can be stepped onto from the column beyond: from a standable cell there
         * at most one block lower (a jump) or up to {@link #MAX_DROP} higher, the step landing in it. The end of a wall
         * top or a roof edge, with only lower ground beyond, has none.
         */
        private int entries(Edge e) {
            Pos c = e.cell;
            int out = 0;
            for (int i = 0; i < 4; i++) {
                if ((e.faces & (1 << i)) == 0) continue;
                int dx = Box.OUT[i][0];
                int dz = Box.OUT[i][1];
                for (int y = c.y() + MAX_DROP; y >= c.y() - 1; y--) {
                    if (Double.isNaN(feet(c.x() + dx, y, c.z() + dz))) continue;
                    if (c.equals(step(new Pos(c.x() + dx, y, c.z() + dz), -dx, -dz, false))) {
                        out |= 1 << i;
                        break;
                    }
                }
            }
            return out;
        }

        /** Edge cells reached, not used yet, from which a step leads out of the box: the next round's leads. */
        private List<Lead> waysOut() {
            List<Lead> out = new ArrayList<>();
            for (Edge e : edges()) {
                Double cost = best.get(e.cell);
                if (cost == null || led.contains(e.cell)) continue;
                for (int i = 0; i < 4; i++) {
                    if ((e.faces & (1 << i)) == 0 || step(e.cell, Box.OUT[i][0], Box.OUT[i][1], false) == null) continue;
                    led.add(e.cell);
                    out.add(new Lead(e.cell.x() + 0.5, e.feetY, e.cell.z() + 0.5, cost));
                    break;
                }
            }
            return out;
        }

        /** Nothing to bump into in this cell (loaded, no collision box). */
        private boolean clear(int x, int y, int z) {
            Pos p = new Pos(x, y, z);
            if (!w.isLoaded(p)) return false;
            BlockSpec b = w.block(p);
            return b.isAir() || w.collisionTop(b) <= 0;
        }
    }

    /**
     * A player standing in feet cell {@code feet} (centred) has its box in {@code cell}: the same column, from the feet
     * cell up to two above it (feet raised by a slab or carpet reach the third).
     */
    public static boolean covers(Pos feet, Pos cell) {
        return feet.x() == cell.x() && feet.z() == cell.z() && cell.y() >= feet.y() && cell.y() <= feet.y() + 2;
    }

    /** The feet cell a configured stand position means: itself, or the cell above when it names the block stood on. */
    public static @Nullable Pos firstCell(World w, Pos p) {
        if (!Double.isNaN(feetY(w, p.x(), p.y(), p.z()))) return p;
        Pos up = p.above();
        return Double.isNaN(feetY(w, up.x(), up.y(), up.z())) ? null : up;
    }

    /**
     * Cells outside every platform's clear sweep: not on the art layer of a tile, and not where its flying machines
     * park or pass. The sweep is the tile plus {@link #SWEEP_MARGIN} beyond its edges along either axis (a cross, so the
     * corners beside the tile stay free: the moss item-collection note blocks are there), from the floor up to feet
     * {@link #SWEEP_HEIGHT} above the art layer. For buttons, levers and note blocks: the bot presses a clear button
     * and then the machines run. Not for a placement ({@link #offArtLayers}): on the moss site the supply walkway and
     * the anvil tower lie in the sweep north of the tile.
     */
    public static CellRule awayFromClearSweeps(List<PlatformGeometry> platforms) {
        List<PlatformGeometry> ps = List.copyOf(platforms);
        return new CellRule(feet -> {
            for (PlatformGeometry g : ps) {
                if (feet.y() < g.artY() - 1 || feet.y() >= g.artY() + SWEEP_HEIGHT) continue;
                int maxX = g.minX() + TileArt.SIZE - 1;
                int maxZ = g.minZ() + TileArt.SIZE - 1;
                boolean inX = feet.x() >= g.minX() && feet.x() <= maxX;
                boolean inZ = feet.z() >= g.minZ() && feet.z() <= maxZ;
                boolean nearX = feet.x() >= g.minX() - SWEEP_MARGIN && feet.x() <= maxX + SWEEP_MARGIN;
                boolean nearZ = feet.z() >= g.minZ() - SWEEP_MARGIN && feet.z() <= maxZ + SWEEP_MARGIN;
                if ((nearX && inZ) || (inX && nearZ)) return false;
            }
            return true;
        }, "inside the clear sweep");
    }

    /**
     * Cells off every platform's art layer ({@link #onArtLayer}); the clear sweep beside the tile is allowed. For a
     * placement (the replacement anvil in RENAME), which runs with the flying machines parked and no clear running.
     */
    public static CellRule offArtLayers(List<PlatformGeometry> platforms) {
        List<PlatformGeometry> ps = List.copyOf(platforms);
        return new CellRule(feet -> {
            for (PlatformGeometry g : ps) if (onArtLayer(g, feet)) return false;
            return true;
        }, "on the art layer");
    }

    /** Feet on the tile's art layer: inside the tile, from the floor to two above the art layer. */
    public static boolean onArtLayer(PlatformGeometry g, Pos feet) {
        return g.contains(feet.x(), feet.z()) && feet.y() >= g.artY() - 1 && feet.y() <= g.artY() + 2;
    }

    private static double centreDistSq(double ex, double ey, double ez, List<Pos> targets) {
        double best = Double.POSITIVE_INFINITY;
        for (Pos t : targets) best = Math.min(best, t.distSq(ex, ey, ez));
        return best;
    }

    /** The nearest point of some target's unit cell is within {@code reach} of the eye (cheap pre-filter). */
    static boolean anyInReach(double ex, double ey, double ez, List<Pos> targets, double reach) {
        for (Pos t : targets) {
            double nx = Aim.clamp(ex, t.x(), t.x() + 1.0);
            double ny = Aim.clamp(ey, t.y(), t.y() + 1.0);
            double nz = Aim.clamp(ez, t.z(), t.z() + 1.0);
            if (Aim.distSq(ex, ey, ez, nx, ny, nz) <= reach * reach) return true;
        }
        return false;
    }
}
