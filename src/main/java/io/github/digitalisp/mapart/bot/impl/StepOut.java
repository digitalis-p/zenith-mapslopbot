package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.function.Predicate;

/**
 * A walk never asks ZenithProxy's pathfinder for a path while the feet stand in a cell it cannot start from: a block it
 * neither walks through nor onto that is thinner than the cell (pointed dripstone: a column 6/16 wide, offset up to
 * 2/16 either way), so the box can stand partly in its cell. The pathfinder plans
 * from the feet cell all the same, and its first movement pushes into the column until it gives up, again and again.
 * The walker ({@code Walker}, {@code build.walkStepOut}) first steps with its own keys into the nearest neighbour cell
 * the bot can stand in. Pure, unit-tested.
 *
 * <p>Live 2026-10-20 (Abaddon [3,4], soul sand): a lane walk that ended in front of a pointed dripstone at Soul Speed
 * slid 0.6 blocks on after its keys and stopped with the feet 0.1 inside the dripstone's cell, the box against the
 * column. Every walk from there ("MovementTraverse has taken too long") ended in the lane watchdog without the bot
 * moving, and VERIFY BLOCKED after four repair passes.
 */
final class StepOut {
    /** Ticks one step out may take. */
    static final int MAX_TICKS = 20;
    /** Step outs per walk (goal list). */
    static final int MAX_TRIES = 2;

    /** A block in the feet cell rising more than this above the feet stands beside the bot, not under it. */
    static final double ABOVE_FEET = 0.1;

    private StepOut() { }

    /**
     * The bot must step out first: the block in its feet cell (y {@code cellY}, collision top {@code collisionTop}
     * above the cell's floor) is one the pathfinder cannot walk through ({@code walkThrough} false) and rises more than
     * {@link #ABOVE_FEET} above the feet ({@code feetY}). A block the bot stands on is no wall although the pathfinder
     * cannot walk through it either: on a bottom slab the feet cell is the slab's own (feet y + 0.1251 floored), and
     * the pathfinder starts there as from any floor.
     */
    static boolean wedged(double feetY, int cellY, double collisionTop, boolean walkThrough) {
        return !walkThrough && cellY + collisionTop > feetY + ABOVE_FEET;
    }

    /**
     * The neighbour cell (same y, the 8 around {@code feet}) to step into: the one nearest the feet point (x, z) that
     * {@code standable} accepts, the four beside it first at equal distance; null if none.
     */
    static @Nullable Pos target(double x, double z, Pos feet, Predicate<Pos> standable) {
        Pos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (int[] d : new int[][]{{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}}) {
            Pos c = new Pos(feet.x() + d[0], feet.y(), feet.z() + d[1]);
            double dist = distance(x, z, c);
            if (dist < bestD - 1e-9 && standable.test(c)) {
                best = c;
                bestD = dist;
            }
        }
        return best;
    }

    /** Horizontal distance from (x, z) to the column of cell {@code c} (0 inside it). */
    static double distance(double x, double z, Pos c) {
        double ox = Math.max(0, Math.max(c.x() - x, x - (c.x() + 1)));
        double oz = Math.max(0, Math.max(c.z() - z, z - (c.z() + 1)));
        return Math.hypot(ox, oz);
    }

    /**
     * Where the step aims: the point nearest (x, z) at which the whole box stands in {@code target}'s column (half the
     * box's width inside its edges). From a cell beside it the step goes straight across the shared edge, away from
     * a column in the middle of the cell left.
     */
    static double[] aim(double x, double z, Pos target) {
        double h = Aim.PLAYER_HALF_WIDTH;
        return new double[]{
            Math.max(target.x() + h, Math.min(target.x() + 1 - h, x)),
            Math.max(target.z() + h, Math.min(target.z() + 1 - h, z))};
    }

    /** Yaw of a walk from (x, z) towards (ax, az) (Minecraft: 0 = +z, 90 = −x). */
    static float yaw(double x, double z, double ax, double az) {
        return (float) Math.IEEEremainder(Math.toDegrees(Math.atan2(az - z, ax - x)) - 90, 360);
    }
}
