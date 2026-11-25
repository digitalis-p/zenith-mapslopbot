package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where the bot walks to after it hit a creeper ({@link CombatTask}, {@link CreeperFight}; pure, unit-tested): a floor
 * cell on loaded ground, outside the no-go zones, at least {@code minDist} (horizontal) from every creeper, preferably
 * on the far side of the bot from the nearest one, the nearest such cell first. The walk there is under
 * {@link #pathRefusal}: no way past a creeper, and never down off the floor the bot stands on (a platform edge).
 */
final class CreeperRetreat {
    private CreeperRetreat() { }

    /** How far around the bot cells are looked at. */
    static final int RADIUS = 16;
    /** A path cell this close to a creeper (horizontal) is refused unless the bot started closer. */
    static final double PASS = 2.5;

    /** A feet cell to walk to, its squared horizontal distance from the bot, and whether it lies away from the creeper. */
    record Candidate(Pos feet, int distSq, boolean farSide) { }

    /**
     * The cells to walk to, best first: far side before the rest, then the shortest walk. {@code creepers}: x, z of each
     * (the first is the one the far side is judged by: the nearest).
     */
    static List<Candidate> plan(EscapePlanner.Cells w, Pos bot, List<double[]> creepers, double minDist) {
        List<Candidate> out = new ArrayList<>();
        if (creepers.isEmpty()) return out;
        double ax = bot.x() + 0.5 - creepers.getFirst()[0];
        double az = bot.z() + 0.5 - creepers.getFirst()[1];
        for (int down = 0; down <= 1; down++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                    Pos feet = new Pos(bot.x() + dx, bot.y() - down, bot.z() + dz);
                    if (!clear(feet, creepers, minDist) || !EscapePlanner.standable(w, feet)) continue;
                    out.add(new Candidate(feet, dx * dx + dz * dz, dx * ax + dz * az > 0));
                }
            }
        }
        out.sort(Comparator.comparing((Candidate c) -> !c.farSide()).thenComparingInt(Candidate::distSq)
            .thenComparingInt(c -> -c.feet().y()).thenComparingInt(c -> c.feet().x()).thenComparingInt(c -> c.feet().z()));
        return List.copyOf(out);
    }

    /** The cell is at least {@code minDist} (horizontal) from every creeper. */
    static boolean clear(Pos feet, List<double[]> creepers, double minDist) {
        for (double[] c : creepers) {
            if (Math.hypot(feet.x() + 0.5 - c[0], feet.z() + 0.5 - c[1]) < minDist) return false;
        }
        return true;
    }

    /**
     * Why the walker must not walk this path (feet cells in walking order), or null: a cell closer than {@link #PASS} to a
     * creeper (and closer than the bot's start was), or a cell more than one below the bot's start (off the floor).
     */
    static @Nullable String pathRefusal(List<Pos> path, List<double[]> creepers) {
        if (path.isEmpty()) return null;
        Pos start = path.getFirst();
        for (int i = 1; i < path.size(); i++) {
            Pos p = path.get(i);
            if (p.y() < start.y() - 1) return "drops off the floor at " + p.key();
            for (double[] c : creepers) {
                double d = Math.hypot(p.x() + 0.5 - c[0], p.z() + 0.5 - c[1]);
                double d0 = Math.hypot(start.x() + 0.5 - c[0], start.z() + 0.5 - c[1]);
                if (d < PASS && d < d0) return "passes next to the creeper at " + p.key();
            }
        }
        return null;
    }
}
