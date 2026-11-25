package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the setback escape walks the bot ({@link EscapeTask}): an open floor
 * cell a few blocks from the cell where the server kept setting it back. Pure and unit-tested; the world comes through
 * {@link Cells}. On ZenithProxy those are server-confirmed blocks: the chunk cache keeps no predicted (ghost) block for
 * our raw clicks.
 *
 * <p>Live 2026-10-11 (job "ellen"): Baritone walked the bot across the top of placed full blocks and dropped it into
 * a floor cell walled on two sides with a 1-wide slot beside it; each time the lane task restarted there the setbacks
 * came back within half a second. So a candidate is a feet cell
 * <ul>
 *   <li>at the bot's feet level or exactly one lower: stepping down off a block is fine, never up onto one;</li>
 *   <li>with at most a carpet at the feet ({@link #LOW_FEET}: air, plates, carpets 1/16), no collision at the head, no
 *       fluid and no {@linkplain Cells#hazard hazard} in either, {@link Cells#allowed allowed} (no-go zones), and a
 *       floor below whose collision top is {@link StandSpots#SUPPORT_MIN} to 1 (full blocks, soul sand, chests): on a
 *       carpet, plate or slab below the bot would stand in that lower cell, on a fence above this one;</li>
 *   <li>open: at most {@link #MAX_TALL} of its 4 sides walled, never two opposite ones (a 1-wide slot). A side is
 *       walled when its cell at feet height is taller than a step ({@link #TALL}), its cell at head height has any
 *       collision, or it is not loaded;</li>
 *   <li>in the rung's ring around the stuck cell ({@link #minRing} to {@link #maxRing}, horizontal Chebyshev
 *       distance): further out with every rung, since the nearer cells did not help;</li>
 *   <li>not within Chebyshev distance 1 of a setback cell (the stuck cell and earlier bursts' cells, {@code avoid}).</li>
 * </ul>
 * Order: fewer walled sides first, then nearer to the bot (horizontal), then the bot's own level before one lower,
 * then x and z (stable).
 *
 * <p>{@link #pathRefusal} is the walker's path check ({@link Walker#pathCheck}) for the walk there: no step up, and once
 * out of the setback cells' neighbourhood never back into it.
 */
final class EscapePlanner {
    private EscapePlanner() { }

    /** The feet cell may hold this much collision: air, plates and glow lichen (0), carpets (1/16). */
    static final double LOW_FEET = 0.1;
    /** A side whose feet-height cell is taller than this is a wall: more than a step (a slab, 0.5, is stepped onto). */
    static final double TALL = 0.6;
    /** Walled sides a candidate may have (a cell against one wall is open; a corner or a slot is not). */
    static final int MAX_TALL = 1;
    /** Feet cells within this Chebyshev distance of a setback cell are neither escaped to nor walked back through. */
    static final int SETBACK_RADIUS = 1;

    // refusal reasons, counted for the log of an escape that finds nothing
    static final String NEAR_SETBACK = "next to a setback cell";
    static final String NOT_ALLOWED = "in a no-go zone";
    static final String UNLOADED = "not loaded";
    static final String FLUID = "fluid";
    static final String BLOCKED = "blocked at the feet or head";
    static final String NO_FLOOR = "no floor";
    static final String SLOT = "1-wide slot";
    static final String WALLED = "walled on 2+ sides";

    /** The cells the planner reads (the cached world on ZenithProxy, a map in tests). */
    interface Cells {
        boolean isLoaded(Pos p);

        /**
         * Highest collision box top of the block in the cell, block-local: 0 none (air, plates, glow lichen), 1/16
         * carpet, 0.5 bottom slab, 1 full block, 1.5 fence. Only asked for loaded cells.
         */
        double collisionTop(Pos p);

        boolean hasFluid(Pos p);

        /** A block never stood in or on (fire, cobweb, berry bush, powder snow, magma, cactus, ...). Default none. */
        default boolean hazard(Pos p) {
            return false;
        }

        /** The bot may stand with its feet in this cell (no-go zones). Default anywhere. */
        default boolean allowed(Pos feet) {
            return true;
        }
    }

    /** A feet cell to escape to, its walled sides, and its squared horizontal distance from the bot's feet cell. */
    record Candidate(Pos feet, int tallSides, int distSq) { }

    /**
     * The candidates, best first; how many cells were looked at, the ring's outer distance, and the cells refused by
     * reason (for the log when there is none).
     */
    record Plan(List<Candidate> candidates, int looked, int maxRing, Map<String, Integer> refused) {
        /** "no open floor cell within 3 of x,y,z (48 cells: 30 no floor, 18 walled on 2+ sides)". */
        String none(Pos stuck) {
            StringBuilder sb = new StringBuilder("no open floor cell within ").append(maxRing).append(" of ")
                .append(stuck.key()).append(" (").append(looked).append(" cells");
            String sep = ": ";
            for (Map.Entry<String, Integer> e : refused.entrySet()) {
                sb.append(sep).append(e.getValue()).append(' ').append(e.getKey());
                sep = ", ";
            }
            return sb.append(')').toString();
        }
    }

    /** Inner edge of the rung's ring (horizontal Chebyshev distance from the stuck cell). */
    static int minRing(int rung) {
        return rung <= 1 ? 2 : rung == 2 ? 3 : 4;
    }

    /** Outer edge of the rung's ring. */
    static int maxRing(int rung) {
        return rung <= 1 ? 3 : rung == 2 ? 5 : 6;
    }

    /**
     * The open floor cells around {@code stuck} for this rung, best first ({@code bot}: the bot's feet cell now;
     * {@code avoid}: feet cells of earlier bursts).
     */
    static Plan plan(Cells w, Pos stuck, Pos bot, int rung, Set<Pos> avoid) {
        int lo = minRing(rung);
        int hi = maxRing(rung);
        List<Pos> setback = setbackCells(stuck, avoid);
        List<Candidate> out = new ArrayList<>();
        Map<String, Integer> refused = new LinkedHashMap<>();
        int looked = 0;
        for (int down = 0; down <= 1; down++) {
            for (int dx = -hi; dx <= hi; dx++) {
                for (int dz = -hi; dz <= hi; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) < lo) continue;
                    Pos feet = new Pos(stuck.x() + dx, bot.y() - down, stuck.z() + dz);
                    looked++;
                    String why = standRefusal(w, feet, setback);
                    if (why == null) {
                        boolean[] walls = walls(w, feet);
                        int tall = count(walls);
                        if (walls[0] && walls[1] || walls[2] && walls[3]) why = SLOT;
                        else if (tall > MAX_TALL) why = WALLED;
                        else {
                            int ex = feet.x() - bot.x();
                            int ez = feet.z() - bot.z();
                            out.add(new Candidate(feet, tall, ex * ex + ez * ez));
                        }
                    }
                    if (why != null) refused.merge(why, 1, Integer::sum);
                }
            }
        }
        out.sort(Comparator.comparingInt(Candidate::tallSides)
            .thenComparingInt(Candidate::distSq)
            .thenComparingInt(c -> bot.y() - c.feet().y())
            .thenComparingInt(c -> c.feet().x())
            .thenComparingInt(c -> c.feet().z()));
        return new Plan(List.copyOf(out), looked, hi, refused);
    }

    /** The bot may stand with its feet in {@code feet}: open, loaded, allowed, solid floor (no setback cells). */
    static boolean standable(Cells w, Pos feet) {
        return standRefusal(w, feet, List.of()) == null;
    }

    /** Why the bot cannot stand (or should not escape) with its feet in {@code feet}, or null. */
    private static @Nullable String standRefusal(Cells w, Pos feet, List<Pos> setback) {
        if (nearSetback(feet, setback) != null) return NEAR_SETBACK;
        if (!w.allowed(feet)) return NOT_ALLOWED;
        if (!w.isLoaded(feet)) return UNLOADED;
        Pos head = feet.above();
        Pos below = feet.below();
        if (w.hasFluid(feet) || w.hasFluid(head)) return FLUID;
        if (w.collisionTop(feet) > LOW_FEET || w.collisionTop(head) > 0 || w.hazard(feet) || w.hazard(head)) {
            return BLOCKED;
        }
        double floor = w.collisionTop(below);
        if (floor < StandSpots.SUPPORT_MIN || floor > 1.0 + 1e-9 || w.hazard(below)) return NO_FLOOR;
        return null;
    }

    /** Walled sides of a feet cell: east (+x), west, south (+z), north. */
    static boolean[] walls(Cells w, Pos feet) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        boolean[] out = new boolean[4];
        for (int i = 0; i < 4; i++) {
            Pos side = feet.offset(dirs[i][0], 0, dirs[i][1]);
            out[i] = !w.isLoaded(side) || w.collisionTop(side) > TALL || w.collisionTop(side.above()) > 0;
        }
        return out;
    }

    private static int count(boolean[] b) {
        int n = 0;
        for (boolean v : b) if (v) n++;
        return n;
    }

    /**
     * Why the walker must not walk this path (feet cells in walking order, from where the bot stands), or null:
     * <ul>
     *   <li>a step up ("climbs onto a block at x,y,z", the block stepped onto): placed full blocks are where the
     *       bot was dropped from into the setback cell;</li>
     *   <li>a cell within {@link #SETBACK_RADIUS} of a setback cell ("passes the setback cell x,y,z"). A path that starts
     *       there (the bot stands in or next to the stuck cell) may run there until it first leaves the
     *       neighbourhood, but not through a setback cell itself; after that, any such cell counts.</li>
     * </ul>
     */
    static @Nullable String pathRefusal(List<Pos> path, Pos stuck, Set<Pos> avoid) {
        if (path.isEmpty()) return null;
        List<Pos> setback = setbackCells(stuck, avoid);
        boolean leaving = nearSetback(path.getFirst(), setback) != null;
        for (int i = 1; i < path.size(); i++) {
            Pos p = path.get(i);
            if (p.y() > path.get(i - 1).y()) return "climbs onto a block at " + p.below().key();
            Pos s = nearSetback(p, setback);
            if (s == null) {
                leaving = false;
                continue;
            }
            if (!leaving) return "passes the setback cell " + s.key();
            if (setback.contains(p)) return "passes the setback cell " + p.key();
        }
        return null;
    }

    /** The stuck cell, then the other avoid cells in a stable order. */
    private static List<Pos> setbackCells(Pos stuck, Set<Pos> avoid) {
        List<Pos> out = new ArrayList<>();
        out.add(stuck);
        avoid.stream().filter(p -> !p.equals(stuck))
            .sorted(Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z))
            .forEach(out::add);
        return out;
    }

    /** The first setback cell within {@link #SETBACK_RADIUS} (Chebyshev, all three axes) of {@code p}, or null. */
    private static @Nullable Pos nearSetback(Pos p, List<Pos> setback) {
        for (Pos s : setback) {
            int d = Math.max(Math.abs(p.x() - s.x()), Math.max(Math.abs(p.y() - s.y()), Math.abs(p.z() - s.z())));
            if (d <= SETBACK_RADIUS) return s;
        }
        return null;
    }
}
