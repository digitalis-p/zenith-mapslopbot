package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalBlock;

/**
 * Baritone goal: feet block within {@code range} blocks horizontally (Euclidean, block coordinates) of (x, z)
 * and within {@code yTolerance} blocks of y. ZenithProxy's GoalNear is spherical; standing positions on the
 * platform and next to chests need a horizontal radius with a small vertical tolerance.
 */
public record GoalNearXZ(int x, int y, int z, int range, int yTolerance) implements Goal {
    @Override
    public boolean isInGoal(int px, int py, int pz) {
        return inGoal(px - x, py - y, pz - z, range, yTolerance);
    }

    /** Pure check, unit-tested. */
    public static boolean inGoal(int dx, int dy, int dz, int range, int yTolerance) {
        return dx * dx + dz * dz <= range * range && Math.abs(dy) <= yTolerance;
    }

    @Override
    public double heuristic(int px, int py, int pz) {
        double dx = px - x;
        double dz = pz - z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= range) {
            dx = 0;
            dz = 0;
        } else {
            double scale = (d - range) / d;
            dx *= scale;
            dz *= scale;
        }
        int dy = py - y;
        int yOut = Math.abs(dy) <= yTolerance ? 0 : dy - Integer.signum(dy) * yTolerance;
        return GoalBlock.calculate(dx, yOut, dz);
    }
}
