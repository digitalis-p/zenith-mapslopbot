package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans an elytra flight leg ({@code build.elytraLegs}) in place of a walk: where
 * the bot comes to rest after the landing, the corridor it flies through and the heights it must clear. Pure: the
 * world comes in through {@link Terrain} (ZenithProxy's cached chunks in the bot, a table in the tests).
 *
 * <p>The flight goes in a straight line from the bot's feet to the rest point R, which lies on the line toward the
 * walk's goal, as close to the goal as a landing pad allows; the walk's own pathfinder then walks from R to the goal.
 * Rules (each refusal says which one failed; the walk then goes on foot):
 * <ul>
 *   <li>the leg (feet to goal, horizontally) is at least {@link Cfg#minBlocks} and at most {@link Cfg#maxBlocks};</li>
 *   <li><b>the landing pad</b>: the strip the bot touches down and slides on, from {@link Cfg#padBack} before R to
 *       {@link Cfg#padFront} past it along the flight, {@link Cfg#padHalf} either side: every cell a standing floor at
 *       R's floor height ({@link #LEVEL_EPS}: all carpet or all soul sand on the art layer, never a mix, a wall or a
 *       wall top beside a lower floor), nothing taller than a carpet or plate in the feet cell (no dripstone, no
 *       candle), two clear cells above, no fluid in the floor,
 *       feet or head cell, none {@link Terrain#forbidden} (no-go zones) and none {@link Terrain#noLanding} (the tile's
 *       border tops). R's level is the
 *       goal's feet level, one below (the tile beside a walkway one block up), one above, or the start's feet level (a
 *       chest stand on a storage floor higher up);</li>
 *   <li><b>the corridor</b>: every column within {@link Cfg#halfWidth} of the line from the feet to R is loaded, has no
 *       fluid as its first block below the flight (no flight over water or the side canals) and no forbidden cell from
 *       the lower floor to the ceiling (the start's floor + {@link Cfg#cruiseMax} + 4). Each column has a <b>floor</b>
 *       (the top of its blocks from two below the lower floor up to the first gap of two empty cells) and a
 *       <b>roof</b> (the bottom of the first block above that gap: the soul sand platform's end rods 5 above the tile,
 *       the counter's line across it). In the columns the bot's box can sweep ({@link #SWEEP} of the line), the highest
 *       floor plus {@link Cfg#clearance} must lie under both the cruise limit (the start's floor +
 *       {@link Cfg#cruiseMax}) and the lowest roof less the box and {@link #ROOF_MARGIN}: one height band that clears
 *       every floor and stays under every roof. The floors and roofs are the {@link Profile} the controller's
 *       look-ahead keeps clear of (a flight under the end rods, not over them: the fly2 run
 *       hit them).</li>
 * </ul>
 */
final class FlightPlanner {
    private FlightPlanner() { }

    /** The world as the planner sees it. */
    interface Terrain {
        boolean loaded(int x, int z);

        /** Top of the block's collision box within its cell, 0..1.5; 0 when it has none (air, plates, fluids). */
        double top(int x, int y, int z);

        /** Bottom of the block's collision box within its cell, 0..1 (for a block with a {@link #top}). */
        default double bottom(int x, int y, int z) {
            return 0;
        }

        boolean fluid(int x, int y, int z);

        /** A cell no flight may cross or land in: a no-go zone. */
        boolean forbidden(int x, int y, int z);

        /** A feet cell no flight may land in, though it may fly over it: the tile's border tops ({@link BorderTop}). */
        default boolean noLanding(int x, int y, int z) {
            return false;
        }

        /**
         * A player's feet (any player but the bot: a teammate too) within {@link FlightControl#PLAYER_MARGIN} of column
         * (x, z), at most {@link FlightControl#PLAYER_DY} from feet level {@code y}: no landing there.
         */
        default boolean playerNear(int x, int y, int z) {
            return false;
        }
    }

    /**
     * {@code minBlocks}/{@code maxBlocks}: the leg lengths that fly; {@code cruiseMax}: the highest the flight may go
     * above the start's floor; {@code clearance}: blocks between the bot's box and any block under its path;
     * {@code halfWidth}: the corridor's half width; {@code padBack}/{@code padFront}/{@code padHalf}: the landing pad;
     * {@code searchBack}: how far back from the goal R may lie; {@code waterDepth}: how deep below the lower floor a
     * column is looked at for water; {@code overWater}: water may lie under the middle of the flight
     * ({@code build.elytraOverWater}, {@link #WATER_FROM_START}).
     */
    record Cfg(double minBlocks, double maxBlocks, int cruiseMax, double clearance, double halfWidth, double padBack,
               double padFront, double padHalf, int searchBack, int waterDepth, boolean overWater) {
        static Cfg defaults(double minBlocks) {
            return of(minBlocks, 8);
        }

        /**
         * The flight leg's planner: legs from {@code minBlocks}, cruise up to {@code cruiseMax} over the start's floor.
         * The pad's half width 1.2 (the box's 0.3, the landings' 0.6 across, a margin) fits the soul sand walkway's 4
         * cells, so a chest leg lands on the walkway's glass, not on the art beside it.
         */
        static Cfg of(double minBlocks, int cruiseMax) {
            return new Cfg(minBlocks, 400, cruiseMax, 1.0, 1.5, 16, 4, 1.2, 24, 12, false);
        }

        /** This config with {@code build.elytraOverWater} as given. */
        Cfg overWater(boolean on) {
            return new Cfg(minBlocks, maxBlocks, cruiseMax, clearance, halfWidth, padBack, padFront, padHalf, searchBack,
                waterDepth, on);
        }
    }

    /** The heights along the flight: column floors and roofs, the landing pad's columns, the ceiling, R and its floor. */
    static final class Profile {
        final Map<Long, Double> tops;
        /** Roofs of the columns that have one (absent: none up to the ceiling). */
        final Map<Long, Double> roofs;
        final Set<Long> pad;
        /**
         * Columns whose first block under the flight is water ({@code build.elytraOverWater}): their floor is the water
         * cell as a block, and the look-ahead never touches down on them ({@link FlightControl}).
         */
        final Set<Long> water = new HashSet<>();
        /** The apron's columns ({@link #corridor}): the look-ahead's room around the flight, not flown over as planned. */
        final Set<Long> apron = new HashSet<>();
        final double ceiling;
        final double restX;
        final double restZ;
        final int level;
        final double surface;
        final double dirX;
        final double dirZ;
        final double startX;
        final double startZ;
        /** The height band (feet) over every floor and under every roof of the sweep, NaN when not planned. */
        double bandLow = Double.NaN;
        double bandHigh = Double.NaN;
        /**
         * An arc flight's way ({@link #planArc}), null for a straight one. Then {@link #startX}/{@link #startZ} lie
         * {@link FlightPath#length} back from R along the final line, so {@link #along} is the place along the way and
         * {@link #across} the distance off it on the final line, where the landing is scored.
         */
        @Nullable FlightPath path;
        /** The highest floor the look-ahead may come down on (arc flights: the take-off's or the pad's and a block). */
        double touchMax = Double.POSITIVE_INFINITY;
        /**
         * A short landing ({@code build.elytraShortLandings}, {@link #planSpot}): {@link #pad} is the small spot around
         * R = T, and the flight ends with {@link FlightBrake}'s brake onto T ({@link FlightControl}).
         */
        boolean spot;
        /**
         * A hover ({@code build.elytraShortLandings}, {@link FlightControl}): a short landing that burns its boost over T
         * from the boost's start (on toward T at the band's middle, a spin over it until the boost ends, the brake down):
         * legs shorter than a boost's reach. Set by {@link FlightLeg#withSpot} when the simulated flight
         * ({@link FlightControl#hoverPreflight}) lands on the spot with every boost.
         */
        boolean hover;
        /**
         * Over the end rods ({@link #planHigh}): every column's floor is its highest block up to the ceiling (the end
         * rods and the counter's line pillars, nothing a roof), the band over them; the clear stand's glass at y 179.
         */
        boolean high;
        /** A hover's pre-flight numbers in words ({@link FlightLeg#hovers}), for the take-off's line. */
        String hoverNote = "";

        Profile(Map<Long, Double> tops, Map<Long, Double> roofs, Set<Long> pad, double ceiling, double startX,
                double startZ, double restX, double restZ, int level, double surface) {
            this.tops = tops;
            this.roofs = roofs;
            this.pad = pad;
            this.ceiling = ceiling;
            this.startX = startX;
            this.startZ = startZ;
            this.restX = restX;
            this.restZ = restZ;
            this.level = level;
            this.surface = surface;
            double d = Math.hypot(restX - startX, restZ - startZ);
            this.dirX = d < 1e-9 ? 0 : (restX - startX) / d;
            this.dirZ = d < 1e-9 ? 1 : (restZ - startZ) / d;
        }

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        /** The floor of column (x, z), {@link Double#NEGATIVE_INFINITY} for none, NaN outside the corridor. */
        double top(int x, int z) {
            Double t = tops.get(key(x, z));
            return t == null ? Double.NaN : t;
        }

        /** The roof of column (x, z), {@link Double#POSITIVE_INFINITY} for none (inside the corridor). */
        double roof(int x, int z) {
            Double r = roofs.get(key(x, z));
            return r == null ? Double.POSITIVE_INFINITY : r;
        }

        boolean pad(int x, int z) {
            return pad.contains(key(x, z));
        }

        boolean water(int x, int z) {
            return water.contains(key(x, z));
        }

        /** Distance along the flight from the start. */
        double along(double x, double z) {
            return (x - startX) * dirX + (z - startZ) * dirZ;
        }

        /** Signed distance across the flight (left negative). */
        double across(double x, double z) {
            return (x - startX) * -dirZ + (z - startZ) * dirX;
        }

        double length() {
            return along(restX, restZ);
        }
    }

    /** A flight: the profile, R's cell and the cruise height the corridor needs. */
    record Plan(Profile profile, int restCellX, int restCellZ, double cruiseY, double legBlocks, double flightBlocks) { }

    /** A plan, or why there is none. */
    record Result(@Nullable Plan plan, @Nullable String refusal) {
        static Result no(String why) {
            return new Result(null, why);
        }
    }

    /**
     * The flight from feet ({@code sx}, {@code sy}, {@code sz}) toward the goal cell ({@code gx}, {@code gy},
     * {@code gz}).
     */
    static Result plan(double sx, double sy, double sz, int gx, int gy, int gz, Terrain t, Cfg c) {
        return plan(sx, sy, sz, gx, gy, gz, t, c, Double.POSITIVE_INFINITY);
    }

    /**
     * {@link #plan(double, double, double, int, int, int, Terrain, Cfg)} with R at most {@code maxFlight} blocks from
     * the feet: a part of the leg flies, the rest walks (a duration-3 rocket's reach, {@link FlightLeg}).
     */
    static Result plan(double sx, double sy, double sz, int gx, int gy, int gz, Terrain t, Cfg c, double maxFlight) {
        int startLevel = (int) Math.floor(sy + 0.1251);
        double gcx = gx + 0.5;
        double gcz = gz + 0.5;
        double leg = Math.hypot(gcx - sx, gcz - sz);
        if (leg < c.minBlocks()) return Result.no(String.format("%.0f blocks, under elytraMinBlocks %.0f", leg, c.minBlocks()));
        if (leg > c.maxBlocks()) return Result.no(String.format("%.0f blocks, over %.0f", leg, c.maxBlocks()));
        double dx = (gcx - sx) / leg;
        double dz = (gcz - sz) / leg;
        double startSurface = surface(t, (int) Math.floor(sx), (int) Math.floor(sz), startLevel);
        if (Double.isNaN(startSurface)) return Result.no("no floor under the feet");
        for (int y = startLevel; y <= startLevel + 2; y++) {
            int fx = (int) Math.floor(sx);
            int fz = (int) Math.floor(sz);
            if (y > startLevel && t.top(fx, y, fz) > 0) return Result.no("no head room to take off");
        }
        int[] levels = Math.abs(startLevel - gy) <= 1 ? new int[]{gy, gy - 1, gy + 1}
            : new int[]{gy, gy - 1, gy + 1, startLevel};
        // R: the cell nearest the goal (within searchBack) whose pad, laid along the flight from the feet to it, can
        // be landed on and whose corridor is clear; else one back along the line, beyond searchBack (a part of the
        // leg flies, the rest walks: far storage not loaded yet, water on the way)
        List<int[]> near = new ArrayList<>();
        int r = c.searchBack();
        for (int x = gx - r; x <= gx + r; x++) {
            for (int z = gz - r; z <= gz + r; z++) {
                double d = Math.hypot(x - gx, z - gz);
                if (d <= r) near.add(new int[]{x, z, (int) Math.round(d * 100)});
            }
        }
        near.sort(Comparator.comparingInt(a -> a[2]));
        List<int[]> line = new ArrayList<>();
        for (double back = r + 2; back <= leg - c.minBlocks(); back += 2) {
            line.add(new int[]{(int) Math.floor(gcx - dx * back), (int) Math.floor(gcz - dz * back)});
        }
        String[] why = new String[2]; // the nearest pad's problem, the first corridor's refusal
        Map<String, Integer> problems = new java.util.TreeMap<>(); // every candidate's pad problem, by kind
        for (List<int[]> cands : List.of(near, line)) {
            int corridors = 0;
            for (int[] cand : cands) {
                double rx = cand[0] + 0.5;
                double rz = cand[1] + 0.5;
                double flight = Math.hypot(rx - sx, rz - sz);
                if (flight < c.minBlocks() * 0.75 || flight > maxFlight) continue;
                if (!t.loaded(cand[0], cand[1])) {
                    if (why[0] == null) why[0] = "not loaded at " + cand[0] + "," + cand[1];
                    problems.merge("not loaded", 1, Integer::sum);
                    continue;
                }
                double ddx = (rx - sx) / flight;
                double ddz = (rz - sz) / flight;
                boolean stand = false;
                for (int level : levels) {
                    if (Double.isNaN(surface(t, cand[0], cand[1], level)) || t.top(cand[0], level, cand[1]) > 0.2) continue;
                    stand = true;
                    String pw = padProblem(t, c, rx, rz, ddx, ddz, level);
                    if (pw == null) pw = runOutProblem(t, c, rx, rz, ddx, ddz, level, padCells(c, rx, rz, ddx, ddz));
                    if (pw != null) {
                        if (why[0] == null) why[0] = pw;
                        problems.merge(kind(pw), 1, Integer::sum);
                        continue;
                    }
                    Result res = corridor(t, c, sx, sz, startLevel, startSurface, rx, rz, level, leg);
                    if (res.plan() != null) return res;
                    if (why[1] == null) why[1] = res.refusal();
                    if (++corridors >= MAX_CORRIDORS) break;
                }
                if (!stand) problems.merge("no standing floor at the levels tried", 1, Integer::sum);
                if (corridors >= MAX_CORRIDORS) break;
            }
        }
        if (why[1] != null) return Result.no(why[1]);
        if (why[0] == null && maxFlight < leg) return Result.no(String.format("no landing pad within %.0f blocks", maxFlight));
        StringBuilder all = new StringBuilder();
        problems.forEach((k, n) -> all.append(all.isEmpty() ? "" : ", ").append(n).append(' ').append(k));
        return Result.no("no landing pad near the goal (" + why[0] + "; candidates: " + all + ")");
    }

    // ---- short landings (build.elytraShortLandings)

    /** {@link #planSpot}'s straight flights that pass the corridor's rules (nearest the goal first), or why there is none. */
    record SpotResult(List<Plan> plans, @Nullable String refusal) { }

    /**
     * A spot's half side around T (blocks): its cells are the ones that meet the square of this half side around T, 3 x 3
     * around a cell's centre, 2 x 2 around a cell's corner. The brake comes to rest within about 0.2 of T from 2.5 blocks
     * up or more, within about 0.7 from 1.5 ({@link FlightBrake}); the box at rest must lie within the cells
     * ({@link FlightControl#spotScore}).
     */
    static final double SPOT_HALF = 0.9;
    /**
     * How far a spot cell's floor may differ from the others': a carpet over the soul sand (0.19). A touch-down under
     * {@link FlightControl#SPOT_MAX_SPEED} meets a carpet's edge slowly: no fly_into_wall damage (the server deals it
     * for a loss of over 0.3 b/t across), and the slide is short.
     */
    static final double SPOT_LEVEL_EPS = 0.25;
    /** Cells around the goal cell (blocks, besides the goal's range) whose spots {@link #planSpot} tries, nearest first. */
    static final int SPOT_NEAR = 3;
    /** Plans {@link #planSpot} returns at most (the pre-flight check tries them in turn), and corridors it checks. */
    static final int SPOT_PLANS = 4;
    static final int SPOT_CORRIDORS = 8;

    /** A spot's cells around T (tx, tz) ({@link #SPOT_HALF}). */
    static Set<Long> spotCells(double tx, double tz) {
        Set<Long> out = new HashSet<>();
        for (int x = (int) Math.floor(tx - SPOT_HALF); x <= (int) Math.floor(tx + SPOT_HALF); x++) {
            for (int z = (int) Math.floor(tz - SPOT_HALF); z <= (int) Math.floor(tz + SPOT_HALF); z++) {
                out.add(Profile.key(x, z));
            }
        }
        return out;
    }

    /** The highest standing floor of a spot's cells at feet level {@code level} (the touch-down's first), NaN for none. */
    static double spotSurface(Terrain t, Set<Long> cells, int level) {
        double s = Double.NEGATIVE_INFINITY;
        for (long k : cells) {
            double f = surface(t, (int) (k >> 32), (int) k, level);
            if (Double.isNaN(f)) return Double.NaN;
            s = Math.max(s, f);
        }
        return s;
    }

    /**
     * Why the spot {@code cells} cannot be landed on at feet level {@code level}, or null: every cell loaded, a standing
     * floor within {@link #SPOT_LEVEL_EPS} of the others', nothing taller than a carpet or plate in the feet cell, two
     * clear cells above, no fluid from the floor to the head, no no-go or border-top cell, no player near
     * ({@link Terrain#playerNear}); and no no-go cell in the ring of cells around it (the slide's margin).
     */
    static @Nullable String spotProblem(Terrain t, Set<Long> cells, int level) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (long k : cells) {
            int x = (int) (k >> 32);
            int z = (int) k;
            if (!t.loaded(x, z)) return "spot not loaded at " + x + "," + z;
            double s = surface(t, x, z, level);
            if (Double.isNaN(s)) return "no floor in the spot at " + x + "," + level + "," + z;
            lo = Math.min(lo, s);
            hi = Math.max(hi, s);
            if (hi - lo > SPOT_LEVEL_EPS + 1e-9) return "the spot's floor is not level at " + x + "," + level + "," + z;
            if (t.top(x, level, z) > 0.2) return "a tall block in the spot at " + x + "," + level + "," + z;
            for (int y = level - 1; y <= level + 2; y++) {
                if (t.fluid(x, y, z)) return "fluid in the spot at " + x + "," + y + "," + z;
                if (y > level && t.top(x, y, z) > 0) return "no head room over the spot at " + x + "," + y + "," + z;
            }
            if (t.forbidden(x, level, z) || t.forbidden(x, level + 1, z) || t.noLanding(x, level, z)) {
                return "a no-go or border-top cell in the spot at " + x + "," + level + "," + z;
            }
            if (t.playerNear(x, level, z)) return "a player near the spot at " + x + "," + level + "," + z;
        }
        for (long k : cells) {
            int x0 = (int) (k >> 32);
            int z0 = (int) k;
            for (int x = x0 - 1; x <= x0 + 1; x++) {
                for (int z = z0 - 1; z <= z0 + 1; z++) {
                    if (cells.contains(Profile.key(x, z)) || !t.loaded(x, z)) continue;
                    for (int y = level - 1; y <= level + 2; y++) {
                        if (t.forbidden(x, y, z)) return "a no-go cell beside the spot at " + x + "," + y + "," + z;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Blocks past T along a short landing's final line, and off it either way, that {@link #overrunProblem} looks at.
     * Live 2026-10-28 06:50: an arc onto a 2 x 2 carpet spot in a pocket of full art blocks (gray terracotta on
     * the far side, cobbled deepslate across the line) came over T still 1.0 up at 0.86 b/t, touched down 1.8 past R
     * and 0.8 across on the pocket's rim at 0.48 b/t and took fly_into_wall damage (the rim 1.0 from T, the brake's
     * usual rest within 0.7 of it from 1.5 up and the box's half width 0.3: no margin left).
     */
    static final double SPOT_OVERRUN = 2.5;
    static final double SPOT_OVERRUN_ACROSS = 0.8;
    /**
     * Blocks short of T along the final line that {@link #overrunProblem} looks at for a step up onto the spot, and the
     * step that counts ({@link #SPOT_STEP}: more than soul sand against glass or a carpet).
     */
    static final double SPOT_APPROACH = 2.0;
    static final double SPOT_STEP = 0.5;

    /**
     * Why a short landing's profile {@code p} may not fly for what lies past T, or null: within {@link #SPOT_OVERRUN}
     * on along its final line and {@link #SPOT_OVERRUN_ACROSS} either side, a cell off the spot whose block at the feet
     * level stands over {@link #SPOT_LEVEL_EPS} (more than a carpet or plate) or with a block at head height. A brake
     * that comes down late slides into its side at speed (the server's fly_into_wall) instead of over a carpet's edge.
     * And within {@link #SPOT_APPROACH} short of T, a cell off the spot whose floor lies over {@link #SPOT_STEP} under
     * the spot's (a spot on a raised edge, the walkway's over the tile: a low brake meets its side; the simulation's
     * brake bailed out there). Cells not loaded are left to the corridor's own checks. Not for
     * hovers (their spin has no final heading).
     */
    static @Nullable String overrunProblem(Terrain t, Profile p) {
        if (!p.spot) return null;
        double ax = -p.dirZ;
        double az = p.dirX;
        for (double s = 0; s <= SPOT_OVERRUN + 1e-9; s += 0.25) {
            for (double a = -SPOT_OVERRUN_ACROSS; a <= SPOT_OVERRUN_ACROSS + 1e-9; a += 0.4) {
                int x = (int) Math.floor(p.restX + p.dirX * s + ax * a);
                int z = (int) Math.floor(p.restZ + p.dirZ * s + az * a);
                if (p.pad(x, z) || !t.loaded(x, z)) continue;
                boolean feet = t.top(x, p.level, z) > SPOT_LEVEL_EPS + 1e-9;
                if (feet || t.top(x, p.level + 1, z) > 0) {
                    return String.format("a raised block %.1f past T on the overrun at %d,%d,%d (a late brake slides into"
                        + " its side)", s, x, feet ? p.level : p.level + 1, z);
                }
            }
        }
        for (double s = 0.25; s <= SPOT_APPROACH + 1e-9; s += 0.25) {
            for (double a = -SPOT_OVERRUN_ACROSS; a <= SPOT_OVERRUN_ACROSS + 1e-9; a += 0.4) {
                int x = (int) Math.floor(p.restX - p.dirX * s + ax * a);
                int z = (int) Math.floor(p.restZ - p.dirZ * s + az * a);
                if (p.pad(x, z) || !t.loaded(x, z)) continue;
                double at = t.top(x, p.level, z);
                double below = t.top(x, p.level - 1, z);
                double under = t.top(x, p.level - 2, z);
                double floor = at > 0 ? p.level + at : below > 0 ? p.level - 1 + below : under > 0 ? p.level - 2 + under
                    : p.level - 2;
                if (p.surface - floor > SPOT_STEP + 1e-9) {
                    return String.format("a step of %.1f up onto the spot %.1f short of T at %d,%d,%d (a low brake meets"
                        + " its side)", p.surface - floor, s, x, p.level - 1, z);
                }
            }
        }
        return null;
    }

    /**
     * T's tried for a walk's goal cell (gx, gz) whose arrival is within {@code range} of it: each cell within
     * {@code range} + {@link #SPOT_NEAR}, nearest first, its centre (a 3 x 3 spot) and then its four corners (2 x 2,
     * nearest the goal first). Each T once: {x, z, distance to the goal's centre}.
     */
    static List<double[]> spotTargets(int gx, int gz, int range) {
        int r = Math.max(0, range) + SPOT_NEAR;
        List<int[]> cells = new ArrayList<>();
        for (int x = gx - r; x <= gx + r; x++) {
            for (int z = gz - r; z <= gz + r; z++) {
                double d = Math.hypot(x - gx, z - gz);
                if (d <= r + 1e-9) cells.add(new int[]{x, z, (int) Math.round(d * 100)});
            }
        }
        cells.sort(Comparator.comparingInt(a -> a[2]));
        double gcx = gx + 0.5;
        double gcz = gz + 0.5;
        List<double[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int[] c : cells) {
            out.add(new double[]{c[0] + 0.5, c[1] + 0.5, Math.hypot(c[0] + 0.5 - gcx, c[1] + 0.5 - gcz)});
            List<double[]> corners = new ArrayList<>();
            for (int cx = c[0]; cx <= c[0] + 1; cx++) {
                for (int cz = c[1]; cz <= c[1] + 1; cz++) {
                    if (seen.add(Profile.key(cx, cz))) corners.add(new double[]{cx, cz, Math.hypot(cx - gcx, cz - gcz)});
                }
            }
            corners.sort(Comparator.comparingDouble(a -> a[2]));
            out.addAll(corners);
        }
        return out;
    }

    /**
     * Short landings ({@code build.elytraShortLandings}): straight flights from feet ({@code sx}, {@code sy}, {@code sz})
     * onto a small spot at T near the goal cell ({@link #spotTargets}: in the walk's goal when it can be, so the walk on
     * is about nothing), R = T, a spot's rules ({@link #spotProblem}) at the goal's feet level, one below or above, or the
     * start's; the corridor as a long pad's ({@link #corridor}). Legs from {@code minBlocks} (feet to goal) up to
     * {@code maxFlight} flown. The ones that pass, nearest the goal first, at most {@link #SPOT_PLANS}.
     */
    static SpotResult planSpot(double sx, double sy, double sz, int gx, int gy, int gz, int range, Terrain t, Cfg c,
                               double minBlocks, double maxFlight) {
        return planSpots(sx, sy, sz, gx, gy, gz, range, t, c, minBlocks, maxFlight, false);
    }

    /**
     * {@link #planSpot} over the end rods ({@link Profile#high}): a spot on a floor higher than the roofs around it (the
     * clear stand's glass at y 179, over the end rods at y 178: under them no band reaches it). Every column's floor is
     * its highest block up to the ceiling (the higher of the start's and the spot's feet level + {@code cruiseMax} + 4),
     * nothing a roof (open sky over the platform); the cruise limit is the higher floor's + {@code cruiseMax}; the climb
     * from the take-off clears every column of the sweep at {@link #HIGH_CLIMB} blocks up a block along at the most (the
     * end rods near the take-off). Flown as a hover ({@link FlightControl#hoverPreflight} decides).
     */
    static SpotResult planHigh(double sx, double sy, double sz, int gx, int gy, int gz, int range, Terrain t, Cfg c,
                               double minBlocks) {
        return planSpots(sx, sy, sz, gx, gy, gz, range, t, c, minBlocks, Double.POSITIVE_INFINITY, true);
    }

    /**
     * Why a hover onto profile {@code p}'s spot may not fly ({@link Profile#hover}), or null: within {@link #HOVER_CLEAR}
     * of T every column loaded, no no-go cell from {@link #HOVER_DROP} under the spot's feet level to the ceiling, and a
     * block within {@link #HOVER_DROP} under it (no shaft). The spin over T moves at about 0.8 b/t when the boost ends at
     * any moment, and the brake stops it within about 3.5 blocks, wherever it was heading: a bail-out from there must
     * neither come down in a no-go zone nor fall into a shaft (the simulation 2026-10-25: a bail-out after a hover beside
     * a no-go box came down at its edge).
     */
    static @Nullable String hoverProblem(Terrain t, Profile p) {
        int r = (int) Math.ceil(HOVER_CLEAR);
        int cx = (int) Math.floor(p.restX);
        int cz = (int) Math.floor(p.restZ);
        int top = (int) Math.ceil(p.ceiling);
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (FlightControl.cellDist(p.restX, p.restZ, x, z) > HOVER_CLEAR) continue;
                if (!t.loaded(x, z)) return "not loaded within " + (int) HOVER_CLEAR + " of T at " + x + "," + z;
                boolean floor = false;
                for (int y = p.level - HOVER_DROP; y < top; y++) {
                    if (t.forbidden(x, y, z)) {
                        return "a no-go cell within " + (int) HOVER_CLEAR + " of T at " + x + "," + y + "," + z;
                    }
                    if (t.top(x, y, z) > 0 || t.fluid(x, y, z)) floor = true;
                }
                if (!floor) return "a drop of over " + HOVER_DROP + " within " + (int) HOVER_CLEAR + " of T at " + x + "," + z;
            }
        }
        return null;
    }

    /** {@link #hoverProblem}: blocks around T kept clear, and the deepest drop allowed there. */
    static final double HOVER_CLEAR = 5;
    static final int HOVER_DROP = 8;

    /** The climb over the end rods ({@link #planHigh}): blocks up a block along from the take-off at the most. */
    static final double HIGH_CLIMB = 0.8;

    private static SpotResult planSpots(double sx, double sy, double sz, int gx, int gy, int gz, int range, Terrain t,
                                        Cfg c, double minBlocks, double maxFlight, boolean high) {
        int startLevel = (int) Math.floor(sy + 0.1251);
        double leg = Math.hypot(gx + 0.5 - sx, gz + 0.5 - sz);
        if (leg < minBlocks) return new SpotResult(List.of(), String.format("%.0f blocks, under %.0f", leg, minBlocks));
        if (leg > c.maxBlocks()) return new SpotResult(List.of(), String.format("%.0f blocks, over %.0f", leg, c.maxBlocks()));
        int fx = (int) Math.floor(sx);
        int fz = (int) Math.floor(sz);
        double startSurface = surface(t, fx, fz, startLevel);
        if (Double.isNaN(startSurface)) return new SpotResult(List.of(), "no floor under the feet");
        for (int y = startLevel + 1; y <= startLevel + 2; y++) {
            if (t.top(fx, y, fz) > 0) return new SpotResult(List.of(), "no head room to take off");
        }
        // the goal's feet level, one below or above: never the start's when the goal lies higher or lower (a spot on the
        // tile under the clear stand, 6 below it, is a long walk round to the stand's way up)
        int[] levels = {gy, gy - 1, gy + 1};
        List<Plan> plans = new ArrayList<>();
        Map<String, Integer> problems = new java.util.TreeMap<>();
        String firstSpot = null;
        String firstCorridor = null;
        int corridors = 0;
        for (double[] tg : spotTargets(gx, gz, range)) {
            if (plans.size() >= SPOT_PLANS || corridors >= SPOT_CORRIDORS) break;
            double flight = Math.hypot(tg[0] - sx, tg[1] - sz);
            if (flight > maxFlight || flight < minBlocks * 0.75) continue;
            Set<Long> cells = spotCells(tg[0], tg[1]);
            for (int level : levels) {
                String sp = spotProblem(t, cells, level);
                if (sp != null) {
                    if (firstSpot == null) firstSpot = sp;
                    problems.merge(kind(sp), 1, Integer::sum);
                    continue;
                }
                corridors++;
                Result res = corridor(t, c, sx, sz, startLevel, startSurface, tg[0], tg[1], level, leg, cells, high);
                if (res.plan() != null) {
                    plans.add(res.plan());
                    break;
                }
                if (firstCorridor == null) firstCorridor = res.refusal();
                break;
            }
        }
        if (!plans.isEmpty()) return new SpotResult(plans, null);
        if (firstCorridor != null) return new SpotResult(plans, firstCorridor);
        StringBuilder all = new StringBuilder();
        problems.forEach((k, n) -> all.append(all.isEmpty() ? "" : ", ").append(n).append(' ').append(k));
        return new SpotResult(plans, "no spot near the goal (" + firstSpot + "; candidates: " + all + ")");
    }

    /** A pad problem without its position: the kind the refusal counts. */
    static String kind(String problem) {
        int at = problem.indexOf(" at ");
        return at < 0 ? problem : problem.substring(0, at);
    }

    /** Corridors tried per search (near the goal, back along the line) before the leg walks. */
    static final int MAX_CORRIDORS = 6;

    /** The standing floor height for feet level {@code level} in column (x, z), or NaN when there is no floor. */
    static double surface(Terrain t, int x, int z, int level) {
        double feet = t.top(x, level, z);
        if (feet > 0) return feet <= 0.5 ? level + feet : Double.NaN;
        double below = t.top(x, level - 1, z);
        return below >= 0.5 ? level - 1 + below : Double.NaN;
    }

    /** Why the pad around rest point (rx, rz) at {@code level} cannot be landed on, or null. */
    static @Nullable String padProblem(Terrain t, Cfg c, double rx, double rz, double dx, double dz, int level) {
        int cx = (int) Math.floor(rx);
        int cz = (int) Math.floor(rz);
        if (!t.loaded(cx, cz)) return "not loaded";
        double s = surface(t, cx, cz, level);
        if (Double.isNaN(s)) return "no floor at " + cx + "," + level + "," + cz;
        for (long k : padCells(c, rx, rz, dx, dz)) {
            int x = (int) (k >> 32);
            int z = (int) k;
            if (!t.loaded(x, z)) return "pad not loaded at " + x + "," + z;
            double ss = surface(t, x, z, level);
            if (Double.isNaN(ss) || Math.abs(ss - s) > LEVEL_EPS) return "the pad's floor is not level at " + x + "," + level + "," + z;
            if (t.top(x, level, z) > 0.2) return "a tall block in the pad at " + x + "," + level + "," + z;
            for (int y = level - 1; y <= level + 2; y++) {
                if (t.fluid(x, y, z)) return "fluid in the pad at " + x + "," + y + "," + z;
                if (y > level && t.top(x, y, z) > 0) return "no head room over the pad at " + x + "," + y + "," + z;
            }
            if (t.forbidden(x, level, z) || t.forbidden(x, level + 1, z) || t.noLanding(x, level, z)) {
                return "a no-go or border-top cell in the pad at " + x + "," + level + "," + z;
            }
            if (t.playerNear(x, level, z)) return "a player near the pad at " + x + "," + level + "," + z;
        }
        return null;
    }

    /** The pad's columns: along the flight from padBack before R to padFront past it, padHalf either side. */
    static Set<Long> padCells(Cfg c, double rx, double rz, double dx, double dz) {
        Set<Long> out = new HashSet<>();
        double ax = rx - dx * c.padBack();
        double az = rz - dz * c.padBack();
        double bx = rx + dx * c.padFront();
        double bz = rz + dz * c.padFront();
        addColumns(out, ax, az, bx, bz, c.padHalf());
        return out;
    }

    /** Adds every column whose centre is within {@code half} + 0.71 of segment A-B. */
    static void addColumns(Set<Long> out, double ax, double az, double bx, double bz, double half) {
        double r = half + 0.71;
        int x0 = (int) Math.floor(Math.min(ax, bx) - r);
        int x1 = (int) Math.floor(Math.max(ax, bx) + r);
        int z0 = (int) Math.floor(Math.min(az, bz) - r);
        int z1 = (int) Math.floor(Math.max(az, bz) + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (segDist(x + 0.5, z + 0.5, ax, az, bx, bz) <= r) out.add(Profile.key(x, z));
            }
        }
    }

    static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double len2 = vx * vx + vz * vz;
        double u = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len2));
        return Math.hypot(px - (ax + u * vx), pz - (az + u * vz));
    }

    private static Result corridor(Terrain t, Cfg c, double sx, double sz, int startLevel, double startSurface, double rx,
                                   double rz, int level, double leg) {
        return corridor(t, c, sx, sz, startLevel, startSurface, rx, rz, level, leg, null, false);
    }

    /**
     * The corridor from the feet to R ({@link #plan}); {@code spot} (not null): a short landing's spot cells around R = T
     * ({@link #planSpot}) instead of the long pad ({@link #plan} checks a long pad's run-out past R before:
     * {@link #runOutProblem}, a pad problem that tries the next R).
     * Besides the corridor's columns the profile holds an apron to {@link #APRON} of the line and the run-out (the
     * look-ahead of a burn's spin, {@link FlightControl}): an apron column out of reach counts as out of the profile, one
     * with a no-go cell as a wall up to the ceiling, water as a floor never come down on; it refuses nothing.
     */
    private static Result corridor(Terrain t, Cfg c, double sx, double sz, int startLevel, double startSurface, double rx,
                                   double rz, int level, double leg, @Nullable Set<Long> spot, boolean high) {
        double dist = Math.hypot(rx - sx, rz - sz);
        double dx = (rx - sx) / dist;
        double dz = (rz - sz) / dist;
        Set<Long> pad = spot != null ? spot : padCells(c, rx, rz, dx, dz);
        Set<Long> cols = new HashSet<>(pad);
        addColumns(cols, sx, sz, rx, rz, c.halfWidth());
        Set<Long> sweep = new HashSet<>(pad);
        addCells(sweep, sx, sz, rx, rz, SWEEP);
        int low = Math.min(startLevel, level);
        int ceilY = (high ? Math.max(startLevel, level) : startLevel) + c.cruiseMax() + 4;
        double landSurface = spot != null ? spotSurface(t, spot, level)
            : surface(t, (int) Math.floor(rx), (int) Math.floor(rz), level);
        String steep = null; // high: the column of the sweep the climb from the take-off clears worst
        double steepBy = 0;
        Map<Long, Double> tops = new HashMap<>();
        Map<Long, Double> roofs = new HashMap<>();
        double need = Math.max(startSurface, landSurface) + 2.0;
        String highest = null;
        double roofLimit = Double.POSITIVE_INFINITY;
        String lowest = null;
        Set<Long> wet = new HashSet<>();
        List<double[]> wetSweep = new ArrayList<>(); // along, x, y, z of the water the box's path crosses
        for (long k : cols) {
            int x = (int) (k >> 32);
            int z = (int) k;
            if (!t.loaded(x, z)) return Result.no("the flight path is not loaded at " + x + "," + z);
            // floor: the blocks up to the first gap of GAP empty cells; roof: the first block above that gap
            double top = Double.NEGATIVE_INFINITY;
            double roof = Double.POSITIVE_INFINITY;
            int roofY = 0;
            int empty = 0;
            for (int y = low - 2; y < ceilY; y++) {
                if (y >= low - 1 && t.forbidden(x, y, z)) return Result.no("the flight path crosses a no-go cell at " + x + "," + y + "," + z);
                if (roof < Double.POSITIVE_INFINITY) continue;
                double h = t.top(x, y, z);
                if (high) {
                    // over the end rods: the highest block is the floor, nothing a roof
                    if (h > 0) top = y + h;
                    continue;
                }
                if (h <= 0 && empty >= GAP && top > Double.NEGATIVE_INFINITY && t.fluid(x, y, z)) {
                    // water over the gap (a clear machine's channel, live 2026-10-24 at y 182): a roof the glide stays
                    // under (it would end in it), not the "first block under the flight"
                    roof = y;
                    roofY = y;
                } else if (h <= 0) {
                    empty++;
                } else if (empty >= GAP && top > Double.NEGATIVE_INFINITY) {
                    roof = y + t.bottom(x, y, z);
                    roofY = y;
                } else {
                    top = y + h;
                    empty = 0;
                }
            }
            // the first block below the flight (under the roof): never water; with build.elytraOverWater, water under the
            // middle of the flight counts as a block (the look-ahead neither glides into it nor touches down on it),
            // never within WATER_FROM_START of the take-off, in the pad or WATER_BEFORE_PAD before it
            double along = (x + 0.5 - sx) * dx + (z + 0.5 - sz) * dz;
            boolean behind = along < 1.0 && !pad.contains(k);
            int from = roof < Double.POSITIVE_INFINITY ? roofY - 1 : ceilY;
            for (int y = from; !behind && y >= low - c.waterDepth(); y--) {
                if (t.fluid(x, y, z)) {
                    boolean middle = along >= WATER_FROM_START && along <= dist - c.padBack() - WATER_BEFORE_PAD;
                    if (!c.overWater() || pad.contains(k) || !middle) {
                        return Result.no("the flight path crosses water at " + x + "," + y + "," + z);
                    }
                    top = Math.max(top, y + 1.0);
                    wet.add(k);
                    if (sweep.contains(k)) wetSweep.add(new double[]{along, x, y, z});
                    break;
                }
                if (t.top(x, y, z) > 0) break;
            }
            tops.put(k, top);
            if (roof < Double.POSITIVE_INFINITY) roofs.put(k, roof);
            if (!sweep.contains(k)) continue;
            boolean nearStart = Math.hypot(x + 0.5 - sx, z + 0.5 - sz) < 1.5;
            if (!pad.contains(k) && !nearStart && top + c.clearance() > need) {
                need = top + c.clearance();
                highest = x + "," + ((int) Math.ceil(top) - 1) + "," + z;
            }
            if (high && !pad.contains(k)) {
                // the climb over the end rods from the take-off (an end rod over the take-off's cell too)
                double by = top + c.clearance() - (startSurface + FlightControl.TAKEOFF_UP + Math.max(0, along) * HIGH_CLIMB);
                if (by > steepBy) {
                    steepBy = by;
                    steep = x + "," + ((int) Math.ceil(top) - 1) + "," + z;
                }
            }
            if (roof - ElytraPhysics.BOX_HEIGHT - ROOF_MARGIN < roofLimit) {
                roofLimit = roof - ElytraPhysics.BOX_HEIGHT - ROOF_MARGIN;
                lowest = x + "," + roofY + "," + z;
            }
        }
        String wide = wideWater(wetSweep);
        if (wide != null) return Result.no(wide);
        if (steep != null) {
            return Result.no(String.format("the climb over the blocks near the take-off is too steep: a block at %s, %.1f"
                + " over a climb of %.1f a block", steep, steepBy, HIGH_CLIMB));
        }
        double limit = (high ? Math.max(startSurface, landSurface) : startSurface) + c.cruiseMax();
        if (need > limit && highest == null) {
            return Result.no(String.format("the landing's floor (%.1f) lies too high for the cruise limit %.1f (under the"
                + " roofs: over them a hover may fly)", landSurface, limit));
        }
        if (need > limit) {
            return Result.no(String.format("the flight path is blocked: a block at %s reaches %.1f, the cruise limit is %.1f",
                highest, need - c.clearance(), limit));
        }
        if (need > roofLimit) {
            return Result.no(String.format("no room under the blocks over the flight path: a block at %s leaves the box %.1f"
                + " over the floor, %.1f needed", lowest, roofLimit + ROOF_MARGIN + ElytraPhysics.BOX_HEIGHT - (need - c.clearance()),
                c.clearance() + ElytraPhysics.BOX_HEIGHT + ROOF_MARGIN));
        }
        Profile p = new Profile(tops, roofs, pad, ceilY, sx, sz, rx, rz, level, landSurface);
        p.water.addAll(wet);
        p.bandLow = need;
        p.bandHigh = Math.min(limit, roofLimit);
        p.spot = spot != null;
        p.high = high;
        Set<Long> apron = new HashSet<>();
        addColumns(apron, sx, sz, rx + dx * RUN_OUT, rz + dz * RUN_OUT, APRON);
        apron.removeAll(cols);
        apron(t, c, p, apron, low, ceilY, high);
        return new Result(new Plan(p, (int) Math.floor(rx), (int) Math.floor(rz), need, leg, dist), null);
    }

    /**
     * Blocks past R a long pad's slide may run out to (the landing's slide comes to rest at most 3 past R, 4 or so
     * with a late answer of the server; a rocket that still boosts on the ground, its look straight down, adds about 4
     * at 1.7 b/t), and the half width of that run-out.
     */
    static final double RUN_OUT = 8;
    /** Blocks either side of the line (and the run-out) the profile holds for the look-ahead besides the corridor. */
    static final double APRON = 3.2;

    /**
     * Why a long pad's slide may not run out past R (from R to {@link #RUN_OUT} on along the flight, the pad's half
     * width), or null: every cell loaded, no no-go cell from the floor to two over the feet, no fluid in the floor or the
     * feet, and no drop: a floor within 1.2 under the pad's (a wall or a step up only stops the slide). Live
     * 2026-10-25 08:47: a slide under a boost past the longest planned ran on toward a no-go zone.
     */
    static @Nullable String runOutProblem(Terrain t, Cfg c, double rx, double rz, double dx, double dz, int level,
                                          Set<Long> pad) {
        double s = surface(t, (int) Math.floor(rx), (int) Math.floor(rz), level);
        Set<Long> cells = new HashSet<>();
        addColumns(cells, rx, rz, rx + dx * RUN_OUT, rz + dz * RUN_OUT, c.padHalf());
        cells.removeAll(pad);
        for (long k : cells) {
            int x = (int) (k >> 32);
            int z = (int) k;
            if (!t.loaded(x, z)) return "the slide's run-out past R is not loaded at " + x + "," + z;
            double floor = Double.NEGATIVE_INFINITY;
            for (int y = level - 3; y <= level + 2; y++) {
                if (y >= level - 1 && t.forbidden(x, y, z)) {
                    return "the slide's run-out past R crosses a no-go cell at " + x + "," + y + "," + z;
                }
                if (y <= level && y >= level - 1 && t.fluid(x, y, z)) {
                    return "the slide's run-out past R crosses fluid at " + x + "," + y + "," + z;
                }
                double h = t.top(x, y, z);
                if (y <= level && h > 0) floor = Math.max(floor, y + h);
            }
            if (!Double.isNaN(s) && floor < s - 1.2) {
                return "the slide's run-out past R ends at a drop at " + x + "," + level + "," + z;
            }
        }
        return null;
    }

    /** {@link #corridor}'s apron columns into profile {@code p} (see there). */
    private static void apron(Terrain t, Cfg c, Profile p, Set<Long> apron, int low, int ceilY) {
        apron(t, c, p, apron, low, ceilY, false);
    }

    /** {@link #apron}; {@code high}: the columns as over the end rods ({@link Profile#high}). */
    private static void apron(Terrain t, Cfg c, Profile p, Set<Long> apron, int low, int ceilY, boolean high) {
        for (long k : apron) {
            int x = (int) (k >> 32);
            int z = (int) k;
            Column col = column(t, x, z, low, ceilY, c.waterDepth(), high);
            if (col.problem() != null) {
                if (col.problem().contains("no-go")) p.tops.put(k, Double.POSITIVE_INFINITY);
                continue;
            }
            double top = col.top();
            if (!Double.isNaN(col.water())) {
                top = Math.max(top, col.water());
                p.water.add(k);
            }
            p.tops.put(k, top);
            p.apron.add(k);
            if (col.roof() < Double.POSITIVE_INFINITY) p.roofs.put(k, col.roof());
        }
    }

    /**
     * {@code build.elytraOverWater}: why the water under the box's path is too wide (a run longer than
     * {@link #WATER_RUN} along the line: a forced descent over it could end in the water), or null.
     */
    static @Nullable String wideWater(List<double[]> wet) {
        if (wet.isEmpty()) return null;
        wet.sort(Comparator.comparingDouble(a -> a[0]));
        double runStart = wet.getFirst()[0];
        double last = runStart;
        for (double[] w : wet) {
            if (w[0] - last > 1.5) runStart = w[0];
            last = w[0];
            if (last - runStart + 1 > WATER_RUN) {
                return String.format("the flight path crosses %.0f blocks of water in a row at %d,%d,%d (%d at most)",
                    last - runStart + 1, (int) w[1], (int) w[2], (int) w[3], WATER_RUN);
            }
        }
        return null;
    }

    /** {@code build.elytraOverWater}: water lies no nearer the take-off than this (blocks along the line). */
    static final double WATER_FROM_START = 8;
    /** ... and no nearer the pad's back end than this. */
    static final double WATER_BEFORE_PAD = 2;
    /** ... in runs of at most this many blocks along the line (a canal or a channel, not a pond). */
    static final int WATER_RUN = 4;

    // ---- arc flights (build.elytraArcLegs)

    /** {@link #planArc}'s ways that pass the corridor's rules (best first), or why there is none. */
    record ArcResult(List<Plan> plans, @Nullable String refusal) { }

    /** The turn's radii tried (blocks): 8 turns about 12 degrees a tick at 1.7 blocks a tick, a rocket's speed. */
    static final double[] ARC_RADII = {8, 11, 14};
    /** Final headings tried into R: this many round the compass. */
    static final int ARC_HEADINGS = 24;
    /** The final line's lengths tried (blocks): from, to, step. */
    static final double ARC_FINAL_MIN = 34;
    static final double ARC_FINAL_MAX = 70;
    static final double ARC_FINAL_STEP = 3;
    /**
     * The turn ends this far along the way at most (blocks): inside the shortest duration-3 boost (a 40-tick boost
     * ends 61 to 64 blocks along the way in the sims), so the glide after the boost flies the final line straight.
     */
    static final double ARC_TURN_END = 52;
    /**
     * The way's length (S to R, blocks): from the shortest straight leg that flies with the longest boost expected
     * ({@link FlightControl#minLegBlocks}, 90 at the least) to this much more, aiming at {@link #ARC_PATH_OVER} more.
     */
    static final double ARC_PATH_MIN = 90;
    static final double ARC_PATH_SPAN = 35;
    static final double ARC_PATH_OVER = 10;
    /**
     * Columns the profile holds either side of the way (the look-ahead's world): {@link #ARC_TUBE} along the final
     * line, {@link #ARC_APRON} along the take-off line and the turn (a boost that ends in the turn glides wide of it).
     */
    static final double ARC_TUBE = 3;
    static final double ARC_APRON = 6;
    /**
     * Cells either side of the take-off line and the turn that count for the height band: the box's half width and the
     * turn's drift (at most 0.63 off the way in the sims).
     */
    static final double ARC_SWEEP = 1.0;
    /** R cells the search looks at at most (nearest the goal first), ways it collects at most, corridors it checks. */
    static final int ARC_CELLS = 1900;
    static final int ARC_WAYS = 1500;
    static final int ARC_CORRIDORS = 40;
    /** Ways looked at at most under the way ({@link #arcQuick}) before their corridors. */
    static final int ARC_QUICK = 1500;
    /** Corridors checked at most for one pad's place (to 4 blocks), heading and turn side (the next cross the same ground). */
    static final int ARC_PER_PAD = 2;
    /** Cells either side of the final line that count for the height band: a straight leg's. */
    static final double ARC_SWEEP_FINAL = FlightPlanner.SWEEP;
    /** Ways {@link #planArc} returns at most (the flight leg's pre-flight check tries them in turn). */
    static final int ARC_PLANS = 6;
    /**
     * The band's bottom over the take-off floor at most (blocks): an arc flies low, over the floors and under the end
     * rods (2 over the floor); a way over a wall would have to climb over it in the first blocks of the boost.
     */
    static final double ARC_CLIMB_MAX = 4;
    /** Blocks over the take-off's or the pad's floor (the higher) that a column may stand to be come down on. */
    static final double ARC_TOUCH_OVER = 1.0;

    /**
     * An arc flight from feet ({@code sx}, {@code sy}, {@code sz}) toward the goal cell: the landing pad near the goal
     * as for a straight flight ({@link #padProblem}), but laid along any of {@link #ARC_HEADINGS} final headings, and
     * the way to it a straight line, a turn ({@link #ARC_RADII}) and the final line ({@link FlightPath}), from
     * {@code pathMin} to {@link #ARC_PATH_SPAN} more blocks long with the turn over by {@link #ARC_TURN_END}. The same
     * rules as a straight flight along the whole way ({@link #arcCorridor}). The ways that pass, best first
     * ({@link #arcScore}), at most {@link #ARC_PER_PAD} for a pad's place, heading and turn side. A leg from
     * {@code arcMin} blocks (feet to goal).
     */
    static ArcResult planArc(double sx, double sy, double sz, int gx, int gy, int gz, Terrain t, Cfg c, double arcMin,
                             double pathMin) {
        return planArc(sx, sy, sz, gx, gy, gz, t, c, arcMin, pathMin, -1);
    }

    /**
     * {@link #planArc(double, double, double, int, int, int, Terrain, Cfg, double, double)}; {@code spotRange} 0 or more:
     * short landings ({@code build.elytraShortLandings}): R = T of {@link #spotTargets} for a goal of that range, onto a
     * spot ({@link #spotProblem}) whatever the final heading.
     */
    static ArcResult planArc(double sx, double sy, double sz, int gx, int gy, int gz, Terrain t, Cfg c, double arcMin,
                             double pathMin, int spotRange) {
        boolean spot = spotRange >= 0;
        int startLevel = (int) Math.floor(sy + 0.1251);
        double gcx = gx + 0.5;
        double gcz = gz + 0.5;
        double leg = Math.hypot(gcx - sx, gcz - sz);
        if (leg < arcMin) return new ArcResult(List.of(), String.format("%.0f blocks, under elytraArcMinBlocks %.0f", leg, arcMin));
        if (leg > c.maxBlocks()) return new ArcResult(List.of(), String.format("%.0f blocks, over %.0f", leg, c.maxBlocks()));
        int fx = (int) Math.floor(sx);
        int fz = (int) Math.floor(sz);
        double startSurface = surface(t, fx, fz, startLevel);
        if (Double.isNaN(startSurface)) return new ArcResult(List.of(), "no floor under the feet");
        for (int y = startLevel + 1; y <= startLevel + 2; y++) {
            if (t.top(fx, y, fz) > 0) return new ArcResult(List.of(), "no head room to take off");
        }
        int[] levels = spot || Math.abs(startLevel - gy) <= 1 ? new int[]{gy, gy - 1, gy + 1}
            : new int[]{gy, gy - 1, gy + 1, startLevel};
        int r = c.searchBack();
        // R's: cells nearest the goal (a pad's R at the cell's centre), or a short landing's T's (centres, corners)
        List<double[]> near = new ArrayList<>();
        if (spot) {
            near.addAll(spotTargets(gx, gz, spotRange));
        } else {
            for (int x = gx - r; x <= gx + r; x++) {
                for (int z = gz - r; z <= gz + r; z++) {
                    double d = Math.hypot(x - gx, z - gz);
                    if (d <= r) near.add(new double[]{x + 0.5, z + 0.5, d});
                }
            }
            near.sort(Comparator.comparingDouble(a -> a[2]));
        }
        Map<String, Boolean> spotOk = new HashMap<>();
        // the pad's cells relative to R's cell, a list a heading (R is a cell's centre)
        double[][] heads = new double[ARC_HEADINGS][];
        List<List<long[]>> offsets = new ArrayList<>();
        for (int k = 0; k < ARC_HEADINGS; k++) {
            double a = 2 * Math.PI * k / ARC_HEADINGS;
            heads[k] = new double[]{Math.cos(a), Math.sin(a)};
            List<long[]> off = new ArrayList<>();
            for (long key : padCells(c, 0.5, 0.5, heads[k][0], heads[k][1])) off.add(new long[]{(int) (key >> 32), (int) key});
            offsets.add(off);
        }
        Map<Integer, PadGrid> grids = new HashMap<>();
        List<double[]> ways = new ArrayList<>(); // score, R x, R z, level, heading, sense, radius, final length
        int pads = 0;
        int cells = 0;
        int unloaded = 0;
        int fits = 0;
        for (double[] cand : near) {
            if (ways.size() >= ARC_WAYS || ++cells > ARC_CELLS) break;
            int cx0 = (int) Math.floor(cand[0]);
            int cz0 = (int) Math.floor(cand[1]);
            if (!t.loaded(cx0, cz0)) {
                unloaded++;
                continue;
            }
            double rx = cand[0];
            double rz = cand[1];
            double dist = Math.hypot(rx - sx, rz - sz);
            double direct = Math.atan2(rz - sz, rx - sx);
            Integer[] order = new Integer[ARC_HEADINGS];
            for (int k = 0; k < ARC_HEADINGS; k++) order[k] = k;
            java.util.Arrays.sort(order, Comparator.comparingDouble(k -> Math.abs(Math.IEEEremainder(
                2 * Math.PI * k / ARC_HEADINGS - direct, 2 * Math.PI))));
            for (int level : levels) {
                PadGrid g = grids.computeIfAbsent(level, lv -> new PadGrid(t, lv));
                double s = spot ? 0 : g.surface(cx0, cz0);
                if (Double.isNaN(s)) continue;
                if (spot && !spotOk.computeIfAbsent(rx + "," + rz + "," + level,
                    kk -> spotProblem(t, spotCells(rx, rz), level) == null)) continue;
                for (int k : order) {
                    double[] h = heads[k];
                    if (!arcHeadingFits(sx, sz, rx, rz, h, pathMin)) continue;
                    fits++;
                    if (!spot && !g.padOk(cx0, cz0, s, offsets.get(k))) continue;
                    pads++;
                    for (int sense : new int[]{1, -1}) {
                        for (double radius : ARC_RADII) {
                            for (double fl = ARC_FINAL_MIN; fl <= ARC_FINAL_MAX + 1e-9; fl += ARC_FINAL_STEP) {
                                if (!arcFinalFits(sx, sz, rx, rz, h, fl, pathMin)) continue;
                                FlightPath w = FlightPath.of(sx, sz, rx, rz, h[0], h[1], fl, radius, sense);
                                if (w == null || w.turnEnd() > ARC_TURN_END) continue;
                                double len = w.length();
                                if (len < pathMin || len > pathMin + ARC_PATH_SPAN) continue;
                                ways.add(new double[]{arcScore(len - pathMin, w.turnDegrees(), radius, cand[2]),
                                    rx, rz, level, k, sense, radius, fl});
                            }
                        }
                    }
                }
            }
        }
        if (ways.isEmpty()) {
            if (fits == 0 && unloaded < cells) {
                return new ArcResult(List.of(), String.format("%.0f blocks: no arc's way of %.0f to %.0f blocks reaches a pad"
                    + " within %d of the goal", leg, pathMin, pathMin + ARC_PATH_SPAN, c.searchBack()));
            }
            return new ArcResult(List.of(), pads == 0
                ? "no landing pad near the goal at any heading (" + cells + " cells looked at, " + unloaded + " not loaded)"
                : "no way of " + (int) pathMin + " to " + (int) (pathMin + ARC_PATH_SPAN) + " blocks with its turn within "
                    + (int) ARC_TURN_END + " (" + pads + " pads)");
        }
        ways.sort(Comparator.comparingDouble(w -> w[0]));
        List<Plan> plans = new ArrayList<>();
        Map<Integer, Map<Long, Column>> cache = new HashMap<>();
        Map<String, Integer> perPad = new HashMap<>();
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        String first = null;
        int tried = 0;
        int quick = 0;
        for (double[] w : ways) {
            if (plans.size() >= ARC_PLANS || tried >= ARC_CORRIDORS || quick >= ARC_QUICK) break;
            // the pad's place to 4 blocks: ways from neighbouring pads cross the same ground
            String key = ((int) Math.floor(w[1]) >> 2) + "," + ((int) Math.floor(w[2]) >> 2) + "," + (int) w[3] + ","
                + (int) w[4] + "," + (int) w[5];
            if (perPad.getOrDefault(key, 0) >= ARC_PER_PAD) continue;
            double[] h = heads[(int) w[4]];
            FlightPath way = FlightPath.of(sx, sz, w[1], w[2], h[0], h[1], w[7], w[6], (int) w[5]);
            quick++;
            String q = arcQuick(t, c, sx, sz, startLevel, startSurface, way, (int) w[3], cache);
            if (q == null && !spot) {
                q = runOutProblem(t, c, way.rx, way.rz, way.finalX, way.finalZ, (int) w[3],
                    padCells(c, way.rx, way.rz, way.finalX, way.finalZ));
            }
            if (q != null) {
                if (first == null) first = q;
                why.merge(refusalKind(q), 1, Integer::sum);
                continue;
            }
            perPad.merge(key, 1, Integer::sum);
            tried++;
            Result res = arcCorridor(t, c, sx, sz, startLevel, startSurface, way, (int) w[3], leg, cache, spot);
            if (res.plan() != null) {
                plans.add(res.plan());
                perPad.put(key, ARC_PER_PAD); // one plan a pad and side: the next try is another pad
            } else {
                if (first == null) first = res.refusal();
                why.merge(refusalKind(res.refusal()), 1, Integer::sum);
            }
        }
        if (!plans.isEmpty()) return new ArcResult(plans, null);
        StringBuilder kinds = new StringBuilder();
        for (Map.Entry<String, Integer> e : why.entrySet()) {
            kinds.append(kinds.length() == 0 ? "" : ", ").append(e.getValue()).append(' ').append(e.getKey());
        }
        return new ArcResult(plans, "no arc corridor (" + quick + " ways tried of " + ways.size() + " from " + pads
            + " pads: " + kinds + "; the first: " + first + ")");
    }

    /**
     * {@link #planArc}'s cheap test before a pad's check and a way's geometry: a final line of {@code fl} blocks into R
     * along heading {@code h} can be part of a way that passes the length and turn rules. Its start B lies within the
     * turn's end ({@link #ARC_TURN_END}) of S (the way to B is no shorter than the line), and the way is at least
     * |SB| + fl and at most {@link #ARC_TURN_END} + fl long. Necessary conditions only: the ways that pass are the same.
     */
    static boolean arcFinalFits(double sx, double sz, double rx, double rz, double[] h, double fl, double pathMin) {
        double sb = Math.hypot(rx - h[0] * fl - sx, rz - h[1] * fl - sz);
        return sb <= ARC_TURN_END + 1e-6 && sb + fl <= pathMin + ARC_PATH_SPAN + 1e-6 && ARC_TURN_END + fl >= pathMin - 1e-6;
    }

    /** {@link #arcFinalFits} for some final line's length: heading {@code h} into R may have a way at all. */
    static boolean arcHeadingFits(double sx, double sz, double rx, double rz, double[] h, double pathMin) {
        for (double fl = ARC_FINAL_MIN; fl <= ARC_FINAL_MAX + 1e-9; fl += ARC_FINAL_STEP) {
            if (arcFinalFits(sx, sz, rx, rz, h, fl, pathMin)) return true;
        }
        return false;
    }

    /**
     * {@link #arcCorridor}'s first filter, the columns right under the way only, every block: why the way cannot fly
     * (null: the corridor may pass).
     */
    private static @Nullable String arcQuick(Terrain t, Cfg c, double sx, double sz, int startLevel, double startSurface,
                                             FlightPath way, int level, Map<Integer, Map<Long, Column>> cache) {
        int low = Math.min(startLevel, level);
        int ceilY = startLevel + c.cruiseMax() + 4;
        double climb = startSurface + Math.min(c.cruiseMax(), ARC_CLIMB_MAX);
        Map<Long, Column> colCache = cache.computeIfAbsent(low, k -> new HashMap<>());
        double len = way.length();
        for (int i = 0; i <= (int) Math.ceil(len); i++) {
            double[] q = way.point(Math.min(len, i));
            int x = (int) Math.floor(q[0]);
            int z = (int) Math.floor(q[1]);
            Column col = colCache.computeIfAbsent(Profile.key(x, z), kk -> column(t, x, z, low, ceilY, c.waterDepth()));
            if (col.problem() != null) return col.problem();
            if (!Double.isNaN(col.water())) return "the arc crosses water at " + x + "," + col.waterY() + "," + z;
            boolean nearStart = Math.hypot(x + 0.5 - sx, z + 0.5 - sz) < 1.5;
            if (!nearStart && col.top() + c.clearance() > climb) {
                return String.format("the arc is blocked: a block at %d,%d,%d reaches %.1f, an arc flies %.0f over the"
                    + " take-off floor at most", x, (int) Math.ceil(col.top()) - 1, z, col.top(), climb - startSurface);
            }
        }
        return null;
    }

    /**
     * A way's rank (lower first): the walk from R to the goal costs most (a block walked is about 2.3 ticks, one flown
     * under 0.7), then the way's length {@code over} the shortest against {@link #ARC_PATH_OVER} (a way that long lands
     * with margin both ways), the turn, a tight radius.
     */
    static double arcScore(double over, double turnDegrees, double radius, double walk) {
        return walk + 0.3 * Math.abs(over - ARC_PATH_OVER) + turnDegrees / 60
            + (ARC_RADII[ARC_RADII.length - 1] - radius) / 6;
    }

    /** A corridor refusal's kind, for the summary. */
    private static String refusalKind(String refusal) {
        if (refusal == null) return "other";
        if (refusal.contains("water")) return "over water";
        if (refusal.contains("no-go")) return "no-go";
        if (refusal.contains("not loaded")) return "not loaded";
        if (refusal.contains("is blocked")) return "blocked";
        if (refusal.contains("no room")) return "under a roof";
        return "other";
    }

    /** {@link #planArc}'s landing cells at one level: a cell's standing floor where a pad may lie, NaN where not. */
    private static final class PadGrid {
        private final Terrain t;
        private final int level;
        private final Map<Long, Double> cells = new HashMap<>();

        PadGrid(Terrain t, int level) {
            this.t = t;
            this.level = level;
        }

        /** The cell's floor height for a pad ({@link #padProblem}'s rules but the level), NaN when it may not be one. */
        double surface(int x, int z) {
            return cells.computeIfAbsent(Profile.key(x, z), k -> compute(x, z));
        }

        private double compute(int x, int z) {
            if (!t.loaded(x, z)) return Double.NaN;
            double s = FlightPlanner.surface(t, x, z, level);
            if (Double.isNaN(s) || t.top(x, level, z) > 0.2) return Double.NaN;
            for (int y = level - 1; y <= level + 2; y++) {
                if (t.fluid(x, y, z)) return Double.NaN;
                if (y > level && t.top(x, y, z) > 0) return Double.NaN;
            }
            if (t.forbidden(x, level, z) || t.forbidden(x, level + 1, z) || t.noLanding(x, level, z)) return Double.NaN;
            return s;
        }

        /** Every pad cell ({@code offsets} from R's cell) a floor at R's height {@code s}. */
        boolean padOk(int rx, int rz, double s, List<long[]> offsets) {
            for (long[] o : offsets) {
                double c = surface(rx + (int) o[0], rz + (int) o[1]);
                if (Double.isNaN(c) || Math.abs(c - s) > LEVEL_EPS) return false;
            }
            return true;
        }
    }

    /**
     * A column's floor and roof as {@link #corridor} reads them, why no flight may cross it (null: it may), and the top
     * of the water that is its first block under the flight (NaN: none).
     */
    private record Column(double top, double roof, int roofY, @Nullable String problem, double water, int waterY) { }

    private static Column column(Terrain t, int x, int z, int low, int ceilY, int waterDepth) {
        return column(t, x, z, low, ceilY, waterDepth, false);
    }

    /** {@link #column}; {@code high}: the highest block is the floor, nothing a roof ({@link Profile#high}). */
    private static Column column(Terrain t, int x, int z, int low, int ceilY, int waterDepth, boolean high) {
        double top = Double.NEGATIVE_INFINITY;
        double roof = Double.POSITIVE_INFINITY;
        int roofY = 0;
        int empty = 0;
        if (!t.loaded(x, z)) return new Column(top, roof, 0, "the flight path is not loaded at " + x + "," + z, Double.NaN, 0);
        for (int y = low - 2; y < ceilY; y++) {
            if (y >= low - 1 && t.forbidden(x, y, z)) {
                return new Column(top, roof, roofY, "the flight path crosses a no-go cell at " + x + "," + y + "," + z,
                    Double.NaN, 0);
            }
            if (roof < Double.POSITIVE_INFINITY) continue;
            double h = t.top(x, y, z);
            if (high) {
                if (h > 0) top = y + h;
                continue;
            }
            if (h <= 0 && empty >= GAP && top > Double.NEGATIVE_INFINITY && t.fluid(x, y, z)) {
                roof = y;
                roofY = y;
            } else if (h <= 0) {
                empty++;
            } else if (empty >= GAP && top > Double.NEGATIVE_INFINITY) {
                roof = y + t.bottom(x, y, z);
                roofY = y;
            } else {
                top = y + h;
                empty = 0;
            }
        }
        int from = roof < Double.POSITIVE_INFINITY ? roofY - 1 : ceilY;
        for (int y = from; y >= low - waterDepth; y--) {
            if (t.fluid(x, y, z)) return new Column(top, roof, roofY, null, y + 1.0, y);
            if (t.top(x, y, z) > 0) break;
        }
        return new Column(top, roof, roofY, null, Double.NaN, 0);
    }

    /**
     * {@link #corridor}'s rules along an arc flight's way: every column within {@link #ARC_TUBE} of it
     * ({@link #ARC_APRON} along the take-off line and the turn) and of the pad loaded, no no-go cell from the lower
     * floor to the ceiling; no water within a straight corridor's half width of the way or in the pad (water further out,
     * which only a bail-out could reach, counts as a block the look-ahead neither glides into nor touches down on); the
     * band over every floor and under every roof of the cells within {@link #ARC_SWEEP} of it
     * ({@link #ARC_SWEEP_FINAL} along the final line).
     */
    static Result arcCorridor(Terrain t, Cfg c, double sx, double sz, int startLevel, double startSurface, FlightPath way,
                              int level, double leg, Map<Integer, Map<Long, Column>> cache) {
        return arcCorridor(t, c, sx, sz, startLevel, startSurface, way, level, leg, cache, false);
    }

    /**
     * {@link #arcCorridor}; {@code spot}: onto a short landing's spot at R = T (else a long pad, whose run-out
     * {@link #planArc} checked before).
     */
    static Result arcCorridor(Terrain t, Cfg c, double sx, double sz, int startLevel, double startSurface, FlightPath way,
                              int level, double leg, Map<Integer, Map<Long, Column>> cache, boolean spot) {
        double rx = way.rx;
        double rz = way.rz;
        Set<Long> pad = spot ? spotCells(rx, rz) : padCells(c, rx, rz, way.finalX, way.finalZ);
        Set<Long> cols = new HashSet<>(pad);
        Set<Long> sweep = new HashSet<>(pad);
        Set<Long> core = new HashSet<>(pad);
        double len = way.length();
        int n = (int) Math.ceil(len);
        double[] prev = way.point(0);
        for (int i = 1; i <= n; i++) {
            double s = Math.min(len, i);
            double[] q = way.point(s);
            boolean turning = s <= way.turnEnd() + 1;
            addColumns(cols, prev[0], prev[1], q[0], q[1], turning ? ARC_APRON : ARC_TUBE);
            addColumns(core, prev[0], prev[1], q[0], q[1], c.halfWidth());
            addCells(sweep, prev[0], prev[1], q[0], q[1], turning ? ARC_SWEEP : ARC_SWEEP_FINAL);
            prev = q;
        }
        Set<Long> wet = new HashSet<>();
        int low = Math.min(startLevel, level);
        int ceilY = startLevel + c.cruiseMax() + 4;
        double landSurface = spot ? spotSurface(t, pad, level) : surface(t, (int) Math.floor(rx), (int) Math.floor(rz), level);
        Map<Long, Column> colCache = cache.computeIfAbsent(low, k -> new HashMap<>());
        Map<Long, Double> tops = new HashMap<>();
        Map<Long, Double> roofs = new HashMap<>();
        double need = Math.max(startSurface, landSurface) + 2.0;
        String highest = null;
        double roofLimit = Double.POSITIVE_INFINITY;
        String lowest = null;
        for (long k : cols) {
            int x = (int) (k >> 32);
            int z = (int) k;
            Column col = colCache.computeIfAbsent(k, kk -> column(t, x, z, low, ceilY, c.waterDepth()));
            if (col.problem() != null) return Result.no(col.problem());
            double top = col.top();
            if (!Double.isNaN(col.water())) {
                if (core.contains(k)) return Result.no("the arc crosses water at " + x + "," + col.waterY() + "," + z);
                top = Math.max(top, col.water());
                wet.add(k);
            }
            tops.put(k, top);
            if (col.roof() < Double.POSITIVE_INFINITY) roofs.put(k, col.roof());
            if (!sweep.contains(k)) continue;
            boolean nearStart = Math.hypot(x + 0.5 - sx, z + 0.5 - sz) < 1.5;
            if (!pad.contains(k) && !nearStart && top + c.clearance() > need) {
                need = top + c.clearance();
                highest = x + "," + ((int) Math.ceil(top) - 1) + "," + z;
            }
            if (col.roof() - ElytraPhysics.BOX_HEIGHT - ROOF_MARGIN < roofLimit) {
                roofLimit = col.roof() - ElytraPhysics.BOX_HEIGHT - ROOF_MARGIN;
                lowest = x + "," + col.roofY() + "," + z;
            }
        }
        double limit = startSurface + c.cruiseMax();
        double climb = Math.min(limit, startSurface + ARC_CLIMB_MAX);
        if (need > climb) {
            return Result.no(String.format("the arc is blocked: a block at %s reaches %.1f, an arc flies %.0f over the"
                + " take-off floor at most", highest, need - c.clearance(), climb - startSurface));
        }
        if (need > roofLimit) {
            return Result.no(String.format("no room under the blocks over the arc: a block at %s leaves the box %.1f over"
                + " the floor%s, %.1f needed", lowest, roofLimit + ROOF_MARGIN + ElytraPhysics.BOX_HEIGHT - (need - c.clearance()),
                highest == null ? "" : " (the highest under the arc at " + highest + ")",
                c.clearance() + ElytraPhysics.BOX_HEIGHT + ROOF_MARGIN));
        }
        // the profile's line: the final line, its start the way's length back from R (along() is the place on the way)
        Profile p = new Profile(tops, roofs, pad, ceilY, rx - way.finalX * len, rz - way.finalZ * len, rx, rz, level,
            landSurface);
        p.path = way;
        p.touchMax = Math.max(startSurface, landSurface) + ARC_TOUCH_OVER;
        p.water.addAll(wet);
        p.bandLow = need;
        p.bandHigh = Math.min(limit, roofLimit);
        p.spot = spot;
        if (!spot) {
            // the run-out's columns for the look-ahead (a burn's spin near R)
            Set<Long> ro = new HashSet<>();
            addColumns(ro, rx, rz, rx + way.finalX * RUN_OUT, rz + way.finalZ * RUN_OUT, APRON);
            ro.removeAll(cols);
            apron(t, c, p, ro, low, ceilY);
        }
        return new Result(new Plan(p, (int) Math.floor(rx), (int) Math.floor(rz), need, leg, Math.hypot(rx - sx, rz - sz)), null);
    }

    /** Empty cells above a column's floor that make the next block above them a roof, not a part of the floor. */
    static final int GAP = 2;
    /**
     * How far a pad cell's floor may differ from R's: none to speak of. The glide slides on after the touch-down, and a
     * slide off a carpet onto the soul sand beside it (0.19 lower) leaves the ground for a tick, in which the next
     * carpet's edge stops the bot like a wall (no step-up in the air): fly_into_wall damage.
     */
    static final double LEVEL_EPS = 0.01;
    /** Blocks from the line within which a cell counts for the height band: the box's half width and some drift. */
    static final double SWEEP = 0.8;
    /** Room kept between the top of the bot's box and a roof. */
    static final double ROOF_MARGIN = 0.3;

    /** Adds every cell whose square comes within {@code r} of segment A-B. */
    static void addCells(Set<Long> out, double ax, double az, double bx, double bz, double r) {
        int x0 = (int) Math.floor(Math.min(ax, bx) - r);
        int x1 = (int) Math.floor(Math.max(ax, bx) + r);
        int z0 = (int) Math.floor(Math.min(az, bz) - r);
        int z1 = (int) Math.floor(Math.max(az, bz) + r);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (segDist(x + 0.5, z + 0.5, ax, az, bx, bz) > r + 0.71) continue;
                if (cellDist(x, z, ax, az, bx, bz) <= r) out.add(Profile.key(x, z));
            }
        }
    }

    /** Distance from segment A-B to the square of cell (x, z) (0 when the segment crosses it). */
    static double cellDist(int x, int z, double ax, double az, double bx, double bz) {
        if (crosses(x, z, ax, az, bx, bz)) return 0;
        // apart, the nearest points are a corner of the square and the segment, or an end of the segment and the square
        double best = Double.POSITIVE_INFINITY;
        for (int cx = x; cx <= x + 1; cx++) {
            for (int cz = z; cz <= z + 1; cz++) best = Math.min(best, segDist(cx, cz, ax, az, bx, bz));
        }
        for (double[] e : new double[][]{{ax, az}, {bx, bz}}) {
            double ex = Math.max(0, Math.max(x - e[0], e[0] - (x + 1)));
            double ez = Math.max(0, Math.max(z - e[1], e[1] - (z + 1)));
            best = Math.min(best, Math.hypot(ex, ez));
        }
        return best;
    }

    /** Whether segment A-B meets the square of cell (x, z) (Liang-Barsky clipping). */
    static boolean crosses(int x, int z, double ax, double az, double bx, double bz) {
        double t0 = 0;
        double t1 = 1;
        double vx = bx - ax;
        double vz = bz - az;
        double[] p = {-vx, vx, -vz, vz};
        double[] q = {ax - x, x + 1 - ax, az - z, z + 1 - az};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) {
                if (q[i] < 0) return false;
            } else {
                double u = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, u);
                else t1 = Math.min(t1, u);
                if (t0 > t1) return false;
            }
        }
        return true;
    }
}
