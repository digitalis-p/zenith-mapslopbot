package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.util.Locale;

/**
 * {@code build.nukeEntryCorner}: where the NUKE's walk
 * that loads the platform's chunks goes when the art needs breaking. Live j0015 (soul sand, 2026-10-23): the bot came
 * along the north walkway from the station 244 blocks east, stepped onto the art layer at the north-east corner, crossed
 * it to the map station at the centre (8-15 s) and then walked back out to the sweep's first lane end (3-11 s); from the
 * corner the whole tile is within the server's view distance. Pure.
 */
final class NukeEntry {
    /** Cells inside the corner (the edge column beside a no-go zone, the corner itself outside the art layer). */
    static final int INSET = 3;
    /** Range of the walk there ({@code GoalNearXZ}). */
    static final int RANGE = 2;
    /** Ticks the bot waits there for the last chunks before it walks on to the map station, as before. */
    static final int WAIT_TICKS = 100;

    private NukeEntry() { }

    /**
     * The art-layer feet cell {@link #INSET} cells inside the corner of {@code pc}'s 128 × 128 map that its
     * {@code nukeEndCorner} names (the way onto and off the art layer); null for a blank or unknown name or a
     * colour-suppression platform.
     */
    static @Nullable Pos stand(PlatformCfg pc) {
        if (pc == null || pc.isColorSuppression() || pc.nukeEndCorner == null) return null;
        int lo = INSET;
        int hi = 127 - INSET;
        return switch (pc.nukeEndCorner.strip().toUpperCase(Locale.ROOT)) {
            case "NW" -> new Pos(pc.minX + lo, pc.artY, pc.minZ + lo);
            case "NE" -> new Pos(pc.minX + hi, pc.artY, pc.minZ + lo);
            case "SW" -> new Pos(pc.minX + lo, pc.artY, pc.minZ + hi);
            case "SE" -> new Pos(pc.minX + hi, pc.artY, pc.minZ + hi);
            default -> null;
        };
    }
}
