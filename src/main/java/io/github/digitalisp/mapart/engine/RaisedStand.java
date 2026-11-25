package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.impl.StandSpots;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.model.Pos;

/**
 * The centre structure a platform's clear is waited out on, when it stands above the flying machines (pure,
 * unit-tested). Moss: the bridge at feet art + 6, up its glass stairs from the
 * walkway and back. Soul sand: the button cross at feet art + 6, reached only by the owner's east glass walkway
 * (2026-10-11), which is one-way: it ends in a 2-block drop onto the bridge, so the only way back is the 6-block hop
 * onto the empty floor (local test 2026-10-11: ZenithProxy's pathfinder finds "No path" off the cross).
 *
 * <ul>
 *   <li>{@link #atStand}: during WAIT_CLEAR the bot must be ON the structure, not just above or below its x and z:
 *   the floor under the cross has the same x and z but lies in the sweep (the machines fill the art layer up to
 *   feet + 4).</li>
 *   <li>{@link #onStructure}: anywhere on the raised structure over the tile (the cross or the bridge arms).</li>
 *   <li>{@link #hop}: whether to drop from the structure to {@code hopDownTo}: only from the structure and only with
 *   health to spare for the fall.</li>
 * </ul>
 */
final class RaisedStand {
    private RaisedStand() {
    }

    /** Horizontal distance from the centre column that still counts as standing at the centre. */
    static final double RANGE = EngineTuning.CENTER_RANGE + 1;

    /** The centre is above the flying machines: feet at least {@link StandSpots#SWEEP_HEIGHT} above the art layer. */
    static boolean raised(PlatformCfg p) {
        return p.center != null && p.center.y() >= p.artY + StandSpots.SWEEP_HEIGHT;
    }

    /**
     * The bot at (x, feetY, z) waits at the centre: within {@link #RANGE} of its column and, for a raised centre, on
     * the structure (feet no more than one block below the centre's feet level; the floor below is in the sweep).
     */
    static boolean atStand(PlatformCfg p, Pos centre, double x, double feetY, double z) {
        double dx = x - (centre.x() + 0.5);
        double dz = z - (centre.z() + 0.5);
        if (Math.sqrt(dx * dx + dz * dz) > RANGE) return false;
        return !raised(p) || feetY >= centre.y() - 1;
    }

    /**
     * The bot stands on the raised centre structure of {@code p} anywhere over its tile: x and z inside the tile, feet
     * from one block below to one above the centre's feet level.
     */
    static boolean onStructure(PlatformCfg p, double x, double feetY, double z) {
        if (!raised(p)) return false;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!p.geometry().contains(bx, bz)) return false;
        return feetY >= p.center.y() - 1 && feetY < p.center.y() + 2;
    }

    /** Fall from the structure's feet level to {@code hopDownTo}'s, in blocks (0 when not configured or not lower). */
    static int drop(PlatformCfg p) {
        if (p.center == null || p.hopDownTo == null) return 0;
        return Math.max(0, p.center.y() - p.hopDownTo.y());
    }

    /**
     * Fall damage (half hearts, before armour and enchantments) for a drop of {@code drop} blocks from a stand onto a
     * floor whose top is {@code floorTop} below a full block (soul sand: 2/16): vanilla rounds the fall distance minus
     * the safe 3 blocks up.
     */
    static int fallDamage(int drop, double floorTop) {
        double fall = drop + floorTop;
        return (int) Math.max(0, Math.ceil(fall - 3.0));
    }

    /** Why the bot does not hop down from the structure now (null: it hops). {@code health} out of 20. */
    static @Nullable String hop(PlatformCfg p, double x, double feetY, double z, float health) {
        if (p.hopDownTo == null) return "no hopDownTo configured";
        if (!raised(p)) return "the center is not above the sweep";
        if (!onStructure(p, x, feetY, z))
            return String.format(java.util.Locale.ROOT, "not on the center structure (feet %.1f, %.1f, %.1f)", x, feetY, z);
        if (drop(p) <= 0) return "hopDownTo is not below the center";
        if (health < p.hopDownMinHealth)
            return String.format(java.util.Locale.ROOT, "health %.1f", health) + " is below hopDownMinHealth " + p.hopDownMinHealth;
        return null;
    }
}
