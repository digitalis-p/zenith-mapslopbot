package io.github.digitalisp.mapart.bot.impl;

import java.util.List;

/**
 * Where the lane placer walks next, and in which order full blocks may go (pure; {@link LaneRun}). Lane coordinates
 * as in {@link LaneGeometry}: {@code s} along the walking direction, {@code lateral} across it; the player walks on
 * the lane line.
 *
 * <p>A corridor cell (lateral 0) is placed once it is entirely behind the player's box. A cell beside the corridor
 * (lateral ±1) is placed when it does not touch the player's box grown by {@link #SAFE_INFLATE}; with the player on
 * the line that takes {@link #SIDE_CLEARANCE} along the lane, so the player must not stop on or next to its row:
 * it walks on past the cell, or, when the lane end does not let it, back before the cell. Cells further out never
 * touch the box.
 *
 * <p>Blocks that shade ({@link Cell#shades}: full blocks, roots, flowers) also wait for every unplaced cell they would
 * hide from the line ({@link #shadows}): outer columns go before inner ones, the rows behind before the row ahead. While
 * one waits, the walk is held short of the point where it would leave reach ({@link #holdLimit}).
 */
final class LaneWalk {
    private LaneWalk() { }

    /** Half the player's width plus a margin: a corridor cell is "behind" the player beyond this. */
    static final double EDGE = Aim.PLAYER_HALF_WIDTH + 0.05;
    /** The player's box is grown by this when checking a non-corridor cell (PlaceLaneTask's safe box). */
    static final double SAFE_INFLATE = 0.3;
    /** Distance along the lane between the player and a beside-the-corridor cell's centre at which they no longer touch. */
    static final double SIDE_CLEARANCE = 0.5 + Aim.PLAYER_HALF_WIDTH + SAFE_INFLATE;
    /** Walked beyond a clearance point so the stop tolerance (0.05) still leaves it clear. */
    static final double MARGIN = 0.15;
    /** Lowest target when walking back: the player's box stays on the lane's start cell. */
    static final double MIN_BACK_S = -(0.5 - Aim.PLAYER_HALF_WIDTH);

    /** Up to this far behind a full block (along the walk) a cell can be hidden by it from the walking line. */
    static final double SHADOW_BEHIND = 2.5;
    /** A held cell stops the walk this far before it would leave reach behind the player. */
    static final double HOLD_MARGIN = 0.6;
    /** Ground friction × 0.91 per tick (vanilla, slipperiness 0.6: soul sand, stone, moss, carpet). */
    static final double FRICTION = 0.546;
    /** Least velocity a tick of movement keys adds (walking: 0.1 × 0.98). */
    static final double ACCEL_MIN = 0.098;
    /** Moves above this in one tick are teleports or setbacks, not walking. */
    static final double TOP_SPEED_MAX = 0.7;
    /**
     * Before a wall right after the walk's end cell, the feet stop at most this far past the end cell's centre: 0.1
     * inside the cell ({@code LaneRun}, class javadoc "Line ends"). A full block stops the box at 0.2; a pointed
     * dripstone, offset up to 1/8 towards the walk, at 0.39 at the earliest.
     */
    static final double WALL_STOP = 0.4;

    /**
     * How far the bot slides on, with no movement key, after a tick that moved it {@code v} along the lane:
     * v·F + v·F² + … = v·F / (1 − F), {@link #FRICTION} F (0.59 at Soul Speed's 0.49 a tick, 0.26 walking).
     */
    static double slide(double v) {
        return v * FRICTION / (1 - FRICTION);
    }

    /**
     * How far the bot moves on after a tick that moved it {@code v}, with the movement keys down for one more tick
     * (adding {@code accel}) and then none: (v·F + accel) / (1 − F). Only the sum of the key ticks counts, not their
     * order: from a standstill one key tick moves the bot accel / (1 − F) (0.49 at Soul Speed), never less.
     */
    static double pressedStop(double v, double accel) {
        return (v * FRICTION + accel) / (1 - FRICTION);
    }

    /**
     * The velocity a tick of movement keys adds, from the fastest the bot moved in one tick ({@code topSpeed}: a steady
     * walk adds what friction takes, so Soul Speed's 0.49 means 0.22), at least {@link #ACCEL_MIN}.
     */
    static double accel(double topSpeed) {
        return Math.max(ACCEL_MIN, Math.min(topSpeed, TOP_SPEED_MAX) * (1 - FRICTION));
    }

    /**
     * An unplaced placement: position along/across the lane, whether it lies in the walking column, and whether the
     * block shades: its outline is tall enough to hide the cells beyond it from the eye (full blocks, and plants such
     * as roots and flowers, which have no collision).
     */
    record Cell(double s, double lateral, boolean corridor, boolean shades) {
        Cell(double s, double lateral, boolean corridor) {
            this(s, lateral, corridor, false);
        }

        /** In the column next to the corridor: touches the safe box of a player on the line unless far enough along. */
        boolean beside() {
            return !corridor && Math.abs(lateral) < 1.5;
        }

        /** Column across the lane: 0 = corridor, ±1 beside it, ±2 ... */
        int column() {
            return (int) Math.round(lateral);
        }
    }

    /**
     * True if a shading block at {@code x} hides cell {@code y} from a player on the lane line who walks in direction
     * {@code dir} (+1 forward, -1 back) and is level with or past {@code x}, so {@code y} must be placed first.
     *
     * <p>A ray from the eye (h = {@link Aim#EYE_HEIGHT} above the art layer's floor face) to a cell L columns out
     * leaves column c (|c| &lt; L, same side) at height h·(1 − (|c| + 0.5)/L): below a block's top when
     * |c| + 0.5 &gt; L·(1 − 1/h). So the corridor hides the columns ±1, column ±1 hides ±2, and so on. Across the walk,
     * a full block hides the cells of its own row and of the two rows behind it (the player stands ahead of it when it
     * is placed: corridor and beside cells are only placed once passed); in its own column, the two cells behind it.
     */
    static boolean shadows(Cell x, Cell y, int dir) {
        double behind = (x.s() - y.s()) * dir; // how far y lies behind x in walking order
        int cx = x.column();
        int cy = y.column();
        if (cx == cy) return behind > 0.5 && behind < SHADOW_BEHIND;
        if (behind < -0.5 || behind > SHADOW_BEHIND) return false;
        if (cx != 0 && Integer.signum(cx) != Integer.signum(cy)) return false;
        int ax = Math.abs(cx);
        int ay = Math.abs(cy);
        return ax < ay && ax + 0.5 > ay * (1 - 1 / Aim.EYE_HEIGHT);
    }

    /**
     * Farthest {@code s} the player may walk in direction {@code dir} while cell {@code c} still waits to be placed:
     * {@link #HOLD_MARGIN} before the point where its support face leaves the horizontal reach {@code reachH}.
     */
    static double holdLimit(Cell c, double reachH, int dir) {
        double along = Math.sqrt(Math.max(0, reachH * reachH - c.lateral() * c.lateral()));
        return c.s() + dir * Math.max(0, along - HOLD_MARGIN);
    }

    /**
     * A jump across a pocket (walking over a walled line, {@code LaneRun}) is started when it lands with the centre this
     * far or farther past the middle of the block it starts from (the block beyond the pocket has its middle at 2 and
     * holds the box for centres in [1.2, 2.8])...
     */
    static final double HOP_LAND_MIN = 1.7;
    /** ... and no farther than this; faster, the bot brakes first. */
    static final double HOP_LAND_MAX = 2.4;
    /** Past the middle of the block by this much, the jump goes whatever the landing (the edge is near). */
    static final double HOP_LAST = 0.45;

    /**
     * Horizontal distance of a jump from the top of a full block onto the top of the next at {@code v} blocks per tick
     * along the jump, the movement key held: 12 ticks in the air (0.42 up, gravity 0.08, drag 0.98), the air speed
     * tending to 0.222 (drag 0.91, acceleration 0.02): Σ(0.222 + (v − 0.222)·0.91ⁿ) for n = 1..12. 2.62 at walking speed
     * (0.216), 1.14 from a standstill.
     */
    static double hopLength(double v) {
        return 12 * 0.222 + (v - 0.222) * 6.85;
    }

    /** Horizontal eye-to-face distance that keeps the eye within {@code reach} of a face {@code eyeHeight} below it. */
    static double horizontalReach(double reach, double eyeHeight) {
        return Math.sqrt(Math.max(0, reach * reach - eyeHeight * eyeHeight));
    }

    /** Walking forward: how far to go so every cell can be placed (from behind if need be), capped at {@code endCap}. */
    static double forwardTarget(List<Cell> todo, double sp, double endCap) {
        double t = Double.NEGATIVE_INFINITY;
        for (Cell c : todo) {
            double want = c.corridor() ? c.s() + 0.5 + EDGE + MARGIN
                : c.beside() ? c.s() + SIDE_CLEARANCE + MARGIN
                : c.s();
            t = Math.max(t, want);
        }
        return t == Double.NEGATIVE_INFINITY ? sp : Math.min(t, endCap);
    }

    /**
     * True at the forward end ({@code sp} at the cap) if a cell cannot be placed from here: a corridor cell still
     * under or ahead of the player, or a beside cell the player stands too close to. The walk then reverses.
     */
    static boolean blockedAtEnd(List<Cell> todo, double sp) {
        for (Cell c : todo) {
            if (c.corridor() && c.s() + 0.5 > sp - EDGE) return true;
            if (c.beside() && Math.abs(c.s() - sp) < SIDE_CLEARANCE) return true;
        }
        return false;
    }

    /** Walking back (after {@link #blockedAtEnd}): far enough before the remaining end cells; {@code sp} = stay. */
    static double reverseTarget(List<Cell> todo, double sp) {
        double t = Double.POSITIVE_INFINITY;
        for (Cell c : todo) {
            if (c.corridor()) {
                if (c.s() + 0.5 > sp - EDGE - 1.0) t = Math.min(t, c.s() - 0.5 - EDGE - 0.1);
            } else if (c.beside() && Math.abs(c.s() - sp) < SIDE_CLEARANCE + 0.3) {
                t = Math.min(t, Math.max(MIN_BACK_S, c.s() - SIDE_CLEARANCE - MARGIN));
            }
        }
        return t == Double.POSITIVE_INFINITY ? sp : t;
    }
}
