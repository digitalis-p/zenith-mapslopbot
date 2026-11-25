package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a fallen bot gets out: dry feet cells at the floor's level near it, nearest
 * first. Pure, unit-tested.
 */
final class FallExit {
    /** Horizontal blocks searched around the bot. */
    static final int RADIUS = 6;
    /** A feet cell's own block may be this high and still be stood in (a carpet, a pressure plate). */
    static final double LOW = 0.2;

    private FallExit() { }

    /**
     * Feet cells within {@link #RADIUS} (horizontal) of {@code feet} with feet y in [{@code minY}, {@code maxY}] the bot
     * can stand in: loaded, no water or lava at the feet or the head, the feet cell low ({@link #LOW}), the head cell
     * open, something with collision below, and not in a no-go zone. Nearest first (horizontal distance, then the
     * lower feet y), at most {@code limit}.
     */
    static List<Pos> exits(Pos feet, int minY, int maxY, EdgeDrop.Cells w, int limit) {
        List<Pos> out = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                for (int y = minY; y <= maxY; y++) {
                    Pos c = new Pos(feet.x() + dx, y, feet.z() + dz);
                    if (standable(c, w)) {
                        out.add(c);
                        break; // the lowest of the column
                    }
                }
            }
        out.sort(Comparator.<Pos>comparingDouble(c -> dist(feet, c)).thenComparingInt(Pos::y));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    /** The bot can stand dry in feet cell {@code c} (see {@link #exits}). */
    static boolean standable(Pos c, EdgeDrop.Cells w) {
        Pos head = c.above();
        Pos below = c.below();
        if (!w.loaded(c) || !w.loaded(head) || !w.loaded(below)) return false;
        if (w.noGo(c) || w.fluid(c) || w.fluid(head)) return false;
        if (w.collisionTop(w.block(c)) > LOW || w.collisionTop(w.block(head)) > 0) return false;
        return w.collisionTop(w.block(below)) > 0 || w.collisionTop(w.block(c)) > 0;
    }

    /** Horizontal distance between the cells' centres. */
    static double dist(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** Whether {@code c} is next to (or in) the column of {@code feet}: reachable with the bot's own keys. */
    static boolean beside(Pos feet, Pos c) {
        return Math.max(Math.abs(feet.x() - c.x()), Math.abs(feet.z() - c.z())) <= 1;
    }
}
