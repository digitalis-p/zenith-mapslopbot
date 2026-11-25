package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.impl.StandSpots;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;

/**
 * The presence rule of a piston clear (pure,
 * unit-tested): every chunk the flying machines sweep or park in must stay within the server's simulation distance of
 * the bot while they run, or they stop mid-way and tear; the carts and the items they collect are entities and stop
 * there too. The sweep is the tile plus {@link StandSpots#SWEEP_MARGIN} beyond its edges along either axis (a cross, as
 * for the presses: {@link StandSpots#awayFromClearSweeps}). The distance is vanilla's chunk ticking distance: the larger
 * of |dx| and |dz| in chunks from the chunk the bot stands in. Vanilla and Paper tick entities up to the simulation
 * distance and block events and block entities (pistons, moving blocks) one chunk further.
 *
 * <p>2b2t (every replay of 2026-10-11: login and set_simulation_distance 5, view distance 10, no change in 11.6 h): from
 * the moss bridge centre the farthest sweep chunk is 5 away, no chunk to spare; the supply walkway and the sorter are 8
 * (9 with the sweep's north and south margin), so the bot must not go there during a clear.
 */
final class ClearPresence {
    private ClearPresence() {
    }

    /** The chunk of block coordinate {@code v}. */
    static int chunk(int v) {
        return Math.floorDiv(v, 16);
    }

    /**
     * The farthest chunk of the clear sweep of {@code g} from the chunk holding block (x, z), in chunks (Chebyshev).
     */
    static int farthestSweepChunk(PlatformGeometry g, int x, int z) {
        int m = StandSpots.SWEEP_MARGIN;
        int maxX = g.minX() + TileArt.SIZE - 1;
        int maxZ = g.minZ() + TileArt.SIZE - 1;
        int armX = farthest(chunk(x), chunk(z), chunk(g.minX() - m), chunk(maxX + m), chunk(g.minZ()), chunk(maxZ));
        int armZ = farthest(chunk(x), chunk(z), chunk(g.minX()), chunk(maxX), chunk(g.minZ() - m), chunk(maxZ + m));
        return Math.max(armX, armZ);
    }

    /** Chebyshev distance from chunk (cx, cz) to the farthest chunk of the box [x0, x1] × [z0, z1]. */
    private static int farthest(int cx, int cz, int x0, int x1, int z0, int z1) {
        int dx = Math.max(Math.abs(cx - x0), Math.abs(cx - x1));
        int dz = Math.max(Math.abs(cz - z0), Math.abs(cz - z1));
        return Math.max(dx, dz);
    }

    /**
     * The log line for where the bot waits for a clear: the farthest sweep chunk against the server's simulation
     * distance {@code sim} (0 or less: unknown) and view distance {@code view}. {@code covered} tells the caller
     * whether to log it as a warning.
     */
    record Check(int farthest, int sim, boolean covered, String text) { }

    static Check check(String platformId, PlatformGeometry g, int x, int z, int sim, int view) {
        int far = farthestSweepChunk(g, x, z);
        boolean known = sim > 0;
        boolean covered = !known || far <= sim;
        String text = "clear on " + platformId + ": the farthest chunk of the sweep is " + far + " chunks from where the"
            + " bot waits; server simulation distance " + (known ? sim + " chunks" : "unknown")
            + (view > 0 ? " (view " + view + ")" : "")
            + (!known ? "" : covered ? ", " + (sim - far) + " to spare"
                : ": the flying machines and carts beyond it are not simulated and may stall (presence rule)");
        return new Check(far, sim, covered, text);
    }
}
