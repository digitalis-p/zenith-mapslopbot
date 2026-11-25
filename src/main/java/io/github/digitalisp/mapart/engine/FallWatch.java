package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;

import java.util.List;
import java.util.Locale;

/**
 * Notices a fallen bot, one tick at a time. Pure, unit-tested.
 *
 * <ul>
 * <li><b>Below a floor</b>: the feet more than {@link #BELOW} under a MOSS or SOUL_SAND platform's art layer, inside its
 * tile grown by {@link #MARGIN} cells (the noobline row and the gaps beside the tile count), after the bot was seen at
 * that floor's level there (a bot that logs in below a floor, or walks under it on purpose, is not a fall), for
 * {@link #BELOW_TICKS} ticks in a row.</li>
 * <li><b>In water</b>: water or lava in the feet cell for {@link #WATER_TICKS} ticks in a row (no walk of the plugin
 * wades that long; the colour-suppression ride, in a vehicle, does not count).</li>
 * </ul>
 * Live 2026-10-13: the bot fell into the channel west of the soul sand tile (feet y170, art layer y173) and every walk
 * after it failed ("No path found ... 22 movements considered") until the owner rescued it by hand.
 */
final class FallWatch {
    /** Feet this far under the art layer are under the floor (soul sand's top is 0.125 under it). */
    static final double BELOW = 0.6;
    /** Cells around the tile that count as the platform (the gap and channel beside its edge). */
    static final int MARGIN = 2;
    static final int BELOW_TICKS = 10;
    static final int WATER_TICKS = 40;
    /** The water test's cell is the one this far above the feet (a soul sand floor's top is 0.125 into its cell). */
    static final double WET_ABOVE = 0.2;

    /** A fall seen: what, and the platform under whose floor it is (null: in water elsewhere). */
    record Fall(String why, @Nullable PlatformCfg platform) { }

    /** The platform the bot was last seen at the floor's level of, by id (null: none yet). */
    private @Nullable String up;
    private int belowTicks;
    private int waterTicks;

    /** The enabled MOSS or SOUL_SAND platform whose tile grown by {@link #MARGIN} holds (x, z), or null. */
    static @Nullable PlatformCfg platformAt(List<PlatformCfg> platforms, double x, double z) {
        for (PlatformCfg p : platforms) {
            if (p == null || !p.enabled || PlatformCfg.COLOR_SUPPRESSION.equals(p.kind)) continue;
            if (x >= p.minX - MARGIN && x < p.minX + 128 + MARGIN && z >= p.minZ - 1 - MARGIN && z < p.minZ + 128 + MARGIN)
                return p;
        }
        return null;
    }

    /**
     * One tick: the bot's feet at (x, y, z), water or lava in its feet cell, riding a vehicle. A fall once seen for its
     * ticks in a row (again on every later tick until {@link #reset}), else null.
     */
    @Nullable Fall tick(List<PlatformCfg> platforms, double x, double y, double z, boolean inFluid, boolean riding) {
        if (riding) {
            belowTicks = 0;
            waterTicks = 0;
            return null;
        }
        PlatformCfg p = platformAt(platforms, x, z);
        boolean below = false;
        if (p != null) {
            if (y >= p.artY - BELOW) up = p.id;
            else below = p.id != null && p.id.equals(up);
        }
        belowTicks = below ? belowTicks + 1 : 0;
        waterTicks = inFluid ? waterTicks + 1 : 0;
        if (belowTicks >= BELOW_TICKS) {
            return new Fall(String.format(Locale.ROOT, "feet at y%.1f, %.1f below the art layer of platform %s (y%d)%s",
                y, p.artY - y, p.id, p.artY, inFluid ? ", in water" : ""), p);
        }
        if (waterTicks >= WATER_TICKS) {
            return new Fall(String.format(Locale.ROOT, "in water for %d ticks (feet at y%.1f)", waterTicks, y), p);
        }
        return null;
    }

    /** After a rescue, a BLOCKED stop or a reconnect: counts start again (the floor seen is kept). */
    void reset() {
        belowTicks = 0;
        waterTicks = 0;
    }
}
