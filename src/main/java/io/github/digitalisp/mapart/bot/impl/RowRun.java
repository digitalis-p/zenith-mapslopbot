package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

/**
 * A walk along the platform's run row ({@code platforms[].runRow}): the storage band's
 * row whose floor is soul sand/soul soil with a clear walking level, the one place Soul Speed works there (owner,
 * 2026-10-14: "two rows out from the chests, it should run on this when restocking"). Baritone does not stay on a row
 * between two waypoints (live: 6-7 blocks/s on the row, 10.2 straight), so the plugin walks the row itself: Baritone to
 * the row cell at the start x, then forward along the row with its own keys, strafing to hold the centre line, then
 * Baritone to the goal. Pure decisions ({@link #plan}, {@link Controller}); {@link Walker} runs them.
 */
final class RowRun {
    private RowRun() { }

    /** The run row config of this tick: the platforms' runRow cells, the band (blocks in z) and the least x distance. */
    record Cfg(List<Pos> rows, int band, int minDx) {
        static final Cfg OFF = new Cfg(List.of(), 0, 0);
    }

    /** The run: the row cell to walk to first, the row's z/y and the x to run to. */
    record Plan(Pos start, int rowZ, int rowY, int goalX, int dir) { }

    /**
     * The row run for a walk from ({@code sx}, {@code sz}) to ({@code gx}, {@code gz}), or null: no row is within
     * {@code band} blocks (in z) of both ends, the feet are not at the row's level, or the x distance is under
     * {@code minDx}. {@code rows}: the runRow cells (x unused: z and feet y).
     */
    static @Nullable Plan plan(List<Pos> rows, double sx, double sy, double sz, int gx, int gz, int band, int minDx) {
        int fx = (int) Math.floor(sx);
        int fz = (int) Math.floor(sz);
        for (Pos r : rows) {
            if (r == null) continue;
            if (Math.abs(fz - r.z()) > band || Math.abs(gz - r.z()) > band) continue;
            if (Math.abs(sy - r.y()) > 2.5) continue;
            if (Math.abs(gx - fx) < minDx) continue;
            return new Plan(new Pos(fx, r.y(), r.z()), r.z(), r.y(), gx, gx > fx ? 1 : -1);
        }
        return null;
    }

    /** One tick's keys of the run along the row. */
    record Keys(boolean forward, int strafeRight, float yaw, boolean done, @Nullable String abort) { }

    /** Slide of a bot without keys relative to its speed ({@link Approach#FRICTION}). */
    private static final double SLIDE = Approach.FRICTION / (1 - Approach.FRICTION);
    /** Lateral error (blocks) from the row's centre line beyond which a strafe key is held. */
    static final double DEADBAND = 0.15;
    /** Lateral error beyond which the run is abandoned (the bot is off the row). */
    static final double OFF_ROW = 0.8;
    static final int STUCK_TICKS = 20;
    static final double STUCK_BLOCKS = 0.05;

    /** The key controller of one run. */
    static final class Controller {
        private final Plan plan;
        private final double brake;
        private double lastX = Double.NaN;
        private int sinceProgress;
        private long ticks;

        Controller(Plan plan, double brakeMargin) {
            this.plan = plan;
            this.brake = brakeMargin;
        }

        long ticks() {
            return ticks;
        }

        /** {@code x}, {@code z}: the bot's position; {@code vx}: its x speed in blocks a tick. */
        Keys tick(double x, double z, double vx) {
            ticks++;
            double err = plan.rowZ() + 0.5 - z;
            float yaw = plan.dir() > 0 ? -90f : 90f;
            if (Math.abs(err) > OFF_ROW) return new Keys(false, 0, yaw, false, "off the row (" + String.format("%.2f", err) + ")");
            double remaining = (plan.goalX() + 0.5 - x) * plan.dir();
            double speed = Math.abs(vx);
            if (remaining <= brake || remaining <= 1.0 && speed < 0.02) return new Keys(false, 0, yaw, true, null);
            if (Double.isNaN(lastX) || Math.abs(x - lastX) > STUCK_BLOCKS) {
                lastX = x;
                sinceProgress = 0;
            } else if (++sinceProgress >= STUCK_TICKS) {
                return new Keys(false, 0, yaw, false, "no progress for " + STUCK_TICKS + " ticks");
            }
            boolean forward = remaining > speed * SLIDE + brake;
            int side = 0;
            if (Math.abs(err) > DEADBAND) side = (plan.dir() > 0) == (err > 0) ? 1 : -1; // +1: the right key
            return new Keys(forward, side, yaw, false, null);
        }
    }
}
