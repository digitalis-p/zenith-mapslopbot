package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.model.Pos;

/**
 * Pure aiming for dropped items: finds the rotation that makes an item dropped from the player's eye land in water
 * as close as possible to a target block (a water channel), using vanilla's item drop and item entity motion:
 * <ul>
 *   <li>spawn at eye height − 0.3 with velocity {@code (−sin(yaw)·cos(pitch)·0.3, −sin(pitch)·0.3 + 0.1 ± 0.1,
 *       cos(yaw)·cos(pitch)·0.3)} plus up to 0.02 sideways;</li>
 *   <li>each tick: gravity 0.04, move, then drag 0.98 on every axis.</li>
 * </ul>
 * A pitch is accepted when the nominal path and both extreme vertical spreads end in a water block without
 * touching a solid block first. Among accepted pitches the one whose worst landing point is closest to the target
 * centre (horizontally) wins. The vertical spread moves the landing point along the throw by up to about half a
 * block, so the target should be part of a channel that runs along the throw, or have water around it.
 */
public final class ThrowPlanner {
    private ThrowPlanner() { }

    /** World access for the simulation. */
    public interface Cells {
        /** An item cannot fly through this block. */
        boolean solid(int x, int y, int z);

        /** The block holds water (the item has landed). */
        boolean water(int x, int y, int z);
    }

    /** Rotation in degrees (Minecraft convention: yaw 0 = +z, pitch +90 = straight down) and its worst miss. */
    public record Aim(float yaw, float pitch, double miss) { }

    /** Landing points further than this from the target centre are not accepted. */
    public static final double MAX_MISS = 2.5;
    static final double SPAWN_BELOW_EYE = 0.3;
    static final int MAX_TICKS = 100;

    /** @return the best aim, or null if no pitch puts every spread into water near {@code target} */
    public static Aim plan(double ex, double ey, double ez, Pos target, Cells cells) {
        double tx = target.x() + 0.5, tz = target.z() + 0.5;
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - ex), tz - ez));
        Aim best = null;
        for (int p10 = -450; p10 <= 890; p10 += 5) {
            float pitch = p10 / 10f;
            double worst = 0;
            boolean ok = true;
            for (double vyJitter : new double[]{0.0, -0.1, 0.1}) {
                double miss = landingMiss(ex, ey, ez, yaw, pitch, vyJitter, target, cells);
                if (Double.isNaN(miss)) {
                    ok = false;
                    break;
                }
                worst = Math.max(worst, miss);
            }
            if (!ok || worst > MAX_MISS) continue;
            if (best == null || worst < best.miss() - 1e-9) best = new Aim(yaw, pitch, worst);
        }
        return best;
    }

    /**
     * Horizontal distance from the target centre where the item enters water, or NaN if it touches a solid block
     * first or falls more than 4 blocks below the target without finding water.
     */
    static double landingMiss(double ex, double ey, double ez, float yaw, float pitch, double vyJitter, Pos target,
                              Cells cells) {
        double yawR = Math.toRadians(yaw), pitchR = Math.toRadians(pitch);
        double x = ex, y = ey - SPAWN_BELOW_EYE, z = ez;
        double vx = -Math.sin(yawR) * Math.cos(pitchR) * 0.3;
        double vy = -Math.sin(pitchR) * 0.3 + 0.1 + vyJitter;
        double vz = Math.cos(yawR) * Math.cos(pitchR) * 0.3;
        double tx = target.x() + 0.5, tz = target.z() + 0.5;
        for (int t = 0; t < MAX_TICKS; t++) {
            vy -= 0.04;
            // two half steps so a fast item cannot skip a thin block corner
            for (int half = 1; half <= 2; half++) {
                double px = x + vx * half / 2, py = y + vy * half / 2, pz = z + vz * half / 2;
                if (cells.water((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz)))
                    return Math.hypot(px - tx, pz - tz);
                if (blocked(px, py, pz, cells)) return Double.NaN;
            }
            x += vx;
            y += vy;
            z += vz;
            if (y < target.y() - 4) return Double.NaN;
            vx *= 0.98;
            vy *= 0.98;
            vz *= 0.98;
        }
        return Double.NaN;
    }

    /** The item (0.25 wide, 0.25 high) at this point touches a solid block. */
    private static boolean blocked(double x, double y, double z, Cells cells) {
        double h = 0.125;
        for (double dx : new double[]{-h, h}) {
            for (double dz : new double[]{-h, h}) {
                for (double dy : new double[]{0, 0.25}) {
                    int bx = (int) Math.floor(x + dx), by = (int) Math.floor(y + dy), bz = (int) Math.floor(z + dz);
                    if (cells.solid(bx, by, bz)) return true;
                }
            }
        }
        return false;
    }
}
