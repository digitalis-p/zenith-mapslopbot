package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Whether a feet cell borders a place the bot must not slide into: one of its 8
 * neighbours (same feet level) is in a no-go zone, holds water or lava, or is open with nothing to land on within
 * {@link #MIN_DROP} blocks (a drop of two blocks or more), or water or lava below it. Pure, unit-tested.
 *
 * <p>Live 2026-10-13 (soul sand, ellen [2,5]): a last-resort stand spot on the platform's west edge column; the cell
 * west of it was a one-block gap over an item water channel. The walk arrived with soul-speed momentum (about 0.49
 * blocks a tick), slid to 0.11 past the edge with the box still on the floor, and the next walk's first push took it
 * into the channel, three blocks down, where no path led out.
 */
final class EdgeDrop {
    /** A drop of this many blocks or more (feet level to the landing) is a hazard; one block is a normal step down. */
    static final double MIN_DROP = 2.0;
    /** Blocks looked down a neighbour's column; nothing to land on within them is a drop of more. */
    static final int LOOK_DOWN = 3;

    /** The 8 neighbours (dx, dz): the 4 side ones first, then the 4 diagonal ones. */
    static final int[][] AROUND = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};

    private EdgeDrop() { }

    /** The directions (dx, dz) of every hazard beside {@code feet} ({@link #at}), in {@link #AROUND}'s order. */
    static List<int[]> directions(Pos feet, Cells w) {
        List<int[]> out = new ArrayList<>(2);
        for (int[] d : AROUND) if (at(feet.offset(d[0], 0, d[1]), w) != null) out.add(d);
        return out;
    }

    /** World access for the check. */
    interface Cells {
        boolean loaded(Pos p);

        BlockSpec block(Pos p);

        /** Water or lava (a waterlogged block too) in the cell. */
        boolean fluid(Pos p);

        /** Highest collision box top of the state: 0 none (air, rails, plants), 1 a full block. */
        double collisionTop(BlockSpec b);

        /**
         * {@link #collisionTop} of a state that holds a bot falling down its column: one of its collision boxes is
         * under the cell's centre. A thin plate on one side (a ladder, a wall sign, an open trapdoor) is none: 0.
         * Default: {@link #collisionTop}.
         */
        default double floorTop(BlockSpec b) {
            return collisionTop(b);
        }

        /** The feet cell (or the head cell above it) lies in a no-go zone. */
        boolean noGo(Pos feet);
    }

    /**
     * The first hazard beside {@code feet}, as "x,y,z: what" (the neighbour cell), or null: the 4 side neighbours first,
     * then the 4 diagonal ones.
     */
    static @Nullable String beside(Pos feet, Cells w) {
        for (int[] d : AROUND) {
            Pos n = feet.offset(d[0], 0, d[1]);
            String h = at(n, w);
            if (h != null) return n.key() + ": " + h;
        }
        return null;
    }

    /**
     * Why a bot that slid into feet cell {@code n} (from a neighbour at the same feet level) would be in danger, or
     * null: a no-go zone, water or lava there, or open with a drop of {@link #MIN_DROP} blocks or more (or water or lava)
     * below. A wall at the feet or the head, or a floor within one block, is no hazard. An unloaded cell counts as one.
     */
    static @Nullable String at(Pos n, Cells w) {
        if (w.noGo(n)) return "no-go zone";
        if (!w.loaded(n) || !w.loaded(n.above())) return "not loaded";
        if (w.fluid(n)) return "water or lava";
        if (w.collisionTop(w.block(n)) > 0) return null; // something to stand on at the feet level, or a wall
        if (w.collisionTop(w.block(n.above())) > 0) return null; // no head room: the bot cannot move in
        for (int d = 1; d <= LOOK_DOWN; d++) {
            Pos c = n.offset(0, -d, 0);
            if (!w.loaded(c)) return "not loaded";
            if (w.fluid(c)) return "water or lava " + d + " below";
            double top = w.floorTop(w.block(c)); // a ladder on the far wall holds nothing (live 2026-10-28)
            if (top > 0) {
                double drop = d - top;
                return drop >= MIN_DROP - 1e-6 ? "a drop of " + fmt(drop) + " blocks" : null;
            }
        }
        return "a drop of more than " + LOOK_DOWN + " blocks";
    }

    /** The most a planned move may fall ({@link #longFall}): the pathfinder's own falls onto a floor. */
    static final int MAX_PLANNED_FALL = 3;

    /** A planned move that falls more than {@link #MAX_PLANNED_FALL} blocks ({@link #longFall}): its two feet cells. */
    record Fall(Pos from, Pos to) {
        int blocks() {
            return from.y() - to.y();
        }
    }

    /**
     * The first move of a planned path (feet cells in order) whose end lies more than {@code max} blocks below its
     * start, or null. The pathfinder falls 3 blocks onto a floor at most, but any height into water, and it plans such
     * a fall whenever nothing else leads to the goal. Live 2026-11-23 (soul sand, j0039 [9,13]): a wandering trader's
     * llamas stood on the service ledge behind the storage, reachable only by the ladder from the ground; the fight's
     * chase ran 70 blocks along the walkway, stepped off it and fell 112 blocks into water, twice, and the walk back
     * ended on that ledge, BLOCKED. A climb down a ladder is one block a move and is none. Pure.
     */
    static @Nullable Fall longFall(List<Pos> cells, int max) {
        for (int i = 1; i < cells.size(); i++) {
            Pos from = cells.get(i - 1);
            Pos to = cells.get(i);
            if (from.y() - to.y() > max) return new Fall(from, to);
        }
        return null;
    }

    /** A planned move that steps down toward a hazard ({@link #descent}): its two feet cells and the hazard's cell. */
    record Descent(Pos from, Pos to, Pos toward, String hazard) { }

    /**
     * The first move of a planned path (feet cells in order) that steps down into a cell with a hazard ({@link #at})
     * straight ahead of it: the cell beyond the move's end in the move's direction, and for a diagonal move the two
     * cells beside that one too. Only moves whose end {@code applies}; an unloaded cell ahead counts as none (the
     * pathfinder plans in loaded cells only). Null: no such move. Pure.
     *
     * <p>Why (live 2026-10-17, soul sand, Galileo [7,5], from the replay's track): the bot stood on a full block of the
     * art one cell in from the tile's west edge, and a stand-spot walk stepped down west onto the edge column at walking
     * speed, 0.23 to 0.28 blocks a tick. A step down is flown, not walked: from leaving the block to landing on the floor
     * 1.125 below the box crossed 0.87 blocks, the whole edge cell, and came down on its far edge with its centre 0.30
     * past it, then slid off into the item water channel three blocks below. The edge sneak cannot help there: it is off
     * for a planned descent (the sneak stop refuses it), and in the air it changes nothing.
     */
    static @Nullable Descent descent(List<Pos> cells, Cells w, Predicate<Pos> applies) {
        for (int i = 1; i < cells.size(); i++) {
            Pos from = cells.get(i - 1);
            Pos to = cells.get(i);
            if (to.y() >= from.y()) continue;
            int dx = Integer.signum(to.x() - from.x());
            int dz = Integer.signum(to.z() - from.z());
            if (dx == 0 && dz == 0) continue; // straight down: nothing carries the bot sideways
            if (!applies.test(to)) continue;
            for (int[] d : ahead(dx, dz)) {
                Pos n = to.offset(d[0], 0, d[1]);
                if (!w.loaded(n) || !w.loaded(n.above())) continue;
                String h = at(n, w);
                if (h != null) return new Descent(from, to, n, h);
            }
        }
        return null;
    }

    /**
     * Whether cell {@code n} is a hole the bot's feet must never enter ({@code build.edgeHoles}): water or lava
     * there or below it, or open with nothing to land on within {@link #LOOK_DOWN}
     * blocks (a drop of more than 3 blocks: more than the pathfinder falls). As "what", or null: a floor, a wall, a drop
     * of 3 blocks or less, or a cell of the column not loaded (not known). A no-go zone over it changes nothing. Pure.
     */
    static @Nullable String holeAt(Pos n, Cells w) {
        for (int d = -1; d <= LOOK_DOWN; d++) if (!w.loaded(n.offset(0, -d, 0))) return null;
        if (w.collisionTop(w.block(n)) > 0) return null; // a block to stand on or a wall (waterlogged too)
        String h = at(n, withoutZones(w));
        if (h == null) return null;
        if (h.startsWith("water or lava")) return h;
        if (!h.startsWith("a drop of more than")) return null;
        // at() looks 3 cells down: a landing on the 4th is a drop of 3 (the pathfinder's own falls), no hole
        Pos c = n.offset(0, -(LOOK_DOWN + 1), 0);
        if (!w.loaded(c)) return null;
        if (w.fluid(c)) return "water or lava " + (LOOK_DOWN + 1) + " below";
        return w.floorTop(w.block(c)) > 0 ? null : h;
    }

    /** {@code w} with no no-go zones (a hole inside a zone is still a hole). */
    private static Cells withoutZones(Cells w) {
        return new Cells() {
            @Override public boolean loaded(Pos p) { return w.loaded(p); }
            @Override public BlockSpec block(Pos p) { return w.block(p); }
            @Override public boolean fluid(Pos p) { return w.fluid(p); }
            @Override public double collisionTop(BlockSpec b) { return w.collisionTop(b); }
            @Override public double floorTop(BlockSpec b) { return w.floorTop(b); }
            @Override public boolean noGo(Pos feet) { return false; }
        };
    }

    /**
     * A diagonal move of a planned path past a hole's corner ({@link #cornerCut}): its two feet cells, the hole (one of
     * the move's two side cells) and what it is.
     */
    record Corner(Pos from, Pos to, Pos hole, String hazard) { }

    /**
     * The first diagonal move of a planned path (feet cells in order) whose side cell ({@code from.x, to.z} or
     * {@code to.x, from.z}, at either end's feet level) is a hole ({@link #holeAt}): the box sweeps that cell's corner,
     * and a feet point a little off the move's line goes into it. Only moves whose end {@code applies}; a side cell the
     * path itself goes through (a planned fall, a walk through water) is none. Null: no such move. Pure.
     *
     * <p>Live 2026-10-27 (soul sand, the sorter pickup stand): the bot stood east of the sorter's 1x1 item shaft (4
     * deep) after a login, the walk went north-west past the shaft, its feet were logged in the shaft's own cell, and
     * it fell 4 blocks in.
     */
    static @Nullable Corner cornerCut(List<Pos> cells, Cells w, Predicate<Pos> applies) {
        java.util.Set<Long> path = new java.util.HashSet<>();
        for (Pos c : cells) path.add(column(c.x(), c.z()));
        for (int i = 1; i < cells.size(); i++) {
            Pos from = cells.get(i - 1);
            Pos to = cells.get(i);
            if (Math.abs(to.x() - from.x()) != 1 || Math.abs(to.z() - from.z()) != 1) continue;
            if (!applies.test(to)) continue;
            for (int y : new int[]{from.y(), to.y()}) {
                for (Pos side : new Pos[]{new Pos(from.x(), y, to.z()), new Pos(to.x(), y, from.z())}) {
                    if (path.contains(column(side.x(), side.z()))) continue;
                    String h = holeAt(side, w);
                    if (h != null) return new Corner(from, to, side, h);
                }
            }
        }
        return null;
    }

    /** A key for column (x, z). */
    static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** The cells (dx, dz) a move in direction (dx, dz) carries the bot toward: straight on, and for a diagonal its sides. */
    private static int[][] ahead(int dx, int dz) {
        if (dx == 0 || dz == 0) return new int[][]{{dx, dz}};
        return new int[][]{{dx, dz}, {dx, 0}, {0, dz}};
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Integer.toString((int) d) : String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
