package io.github.digitalisp.mapart.bot.impl;

/**
 * Guard against a walk that keeps asking the pathfinder for a new path while the bot only shuttles within a few blocks
 * ({@link Walker}). {@link GoalChurn} ends a walk whose goal flips while the bot stands; this one ends a walk that
 * moves but gets nowhere: live 2026-11-15 09:29-09:33, a rocket withdrawal from the colour-suppression chests to a
 * chest 195 blocks off with no way there. The pathfinder led the bot onto the ride's walkway and from then on every
 * path was given up after half a second, west and east in turn (a new path every 10-12 ticks, the bot within 4 blocks,
 * 400 paths in 4.5 minutes until the owner saw it). Each step counted as progress, so the stall watch never fired.
 * Pure.
 *
 * <p>Counts path requests that come within {@link #GAP_TICKS} of each other while the bot stays within
 * {@link #RADIUS} blocks (horizontally) of where the count began; at {@link #LIMIT} the walk is given up. Leaving the
 * radius, a pause between two requests, or an arrival (the walker resets the count) starts it anew: a walk that gets
 * on asks rarely, and never that often around one spot without arriving.
 */
final class PathChurn {
    /** Path requests in a row around one spot that end the walk (live: about 22 s of the turning). */
    static final int LIMIT = 40;
    /** Two requests further apart than this (ticks) do not count as churn. */
    static final int GAP_TICKS = 40;
    /** Horizontal distance (blocks) from where the count began that counts as getting somewhere. */
    static final double RADIUS = 4.0;

    private int asks;
    private long since;
    private long last = Long.MIN_VALUE / 2;
    private double x;
    private double z;

    /**
     * A path request at tick {@code now} with the bot at ({@code bx}, {@code bz}): true when the walk should be given
     * up (path after path, and the bot still around the same spot).
     */
    boolean asked(long now, double bx, double bz) {
        if (asks == 0 || now - last > GAP_TICKS || Math.hypot(bx - x, bz - z) > RADIUS) {
            asks = 0;
            since = now;
            x = bx;
            z = bz;
        }
        last = now;
        asks++;
        return asks >= LIMIT;
    }

    /** Path requests counted in the current run. */
    int asks() {
        return asks;
    }

    /** Ticks from the first request of the current run to {@code now}. */
    long span(long now) {
        return asks == 0 ? 0 : now - since;
    }

    void reset() {
        asks = 0;
    }
}
