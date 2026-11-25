package io.github.digitalisp.mapart.bot.impl;

/**
 * Guard against a walk whose goal keeps changing without the bot getting anywhere ({@link Walker}). Each goal change
 * restarts Baritone's path (and its stall timer), so a caller that flips between two goals keeps the bot standing
 * forever (local 10x run, 2026-10-11: a break task alternated between two blocks at the same distance, 1,000 path
 * searches each in 31 s). Pure.
 *
 * <p>Counts goal changes that come within {@link #GAP_TICKS} of each other while the bot stays within {@link #MOVED}
 * blocks (horizontally) of where the count began; at {@link #LIMIT} the walk is given up. Moving, or a pause between two
 * changes, resets the count.
 */
final class GoalChurn {
    /** Goal changes in a row without moving that end the walk. */
    static final int LIMIT = 20;
    /** Two changes further apart than this (ticks) do not count as churn. */
    static final int GAP_TICKS = 20;
    /** Horizontal distance (blocks) that counts as getting somewhere. */
    static final double MOVED = 0.5;

    private int changes;
    private long since;
    private long last = Long.MIN_VALUE / 2;
    private double x;
    private double z;

    /**
     * A goal change at tick {@code now} with the bot at ({@code bx}, {@code bz}): true when the walk should be given up
     * (the goal keeps changing and the bot does not move).
     */
    boolean changed(long now, double bx, double bz) {
        if (changes == 0 || now - last > GAP_TICKS || Math.hypot(bx - x, bz - z) > MOVED) {
            changes = 0;
            since = now;
            x = bx;
            z = bz;
        }
        last = now;
        changes++;
        return changes >= LIMIT;
    }

    /** Goal changes counted in the current run of changes. */
    int changes() {
        return changes;
    }

    /** Ticks from the first change of the current run to {@code now}. */
    long span(long now) {
        return changes == 0 ? 0 : now - since;
    }

    void reset() {
        changes = 0;
    }
}
