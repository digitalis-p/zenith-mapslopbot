package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.function.Predicate;

/**
 * Where FILL_MAP fills the map from ({@code map.fillNearStand}): not the station at
 * the centre, but the cell of the tile from which the server updates every pixel and that is on the bot's way.
 *
 * <p><b>Coverage</b> (vanilla 1.21 {@code MapItem.update}, scale 0): the holder's pixel is its block cell relative to
 * the map's north-west corner; a pixel at squared distance up to {@code 126²} from it is drawn every pass, between
 * {@code 126²} and {@code 128²} only every other pixel (a checkerboard), beyond not at all. So a cell covers the map
 * when all four corner pixels lie within {@link #RADIUS} less {@code margin} of it: at margin 2 tile cells 21..106 along
 * the axes through the centre (42-43 blocks off it) and 23-24 blocks off it along the diagonals (4,320 of the 16,384
 * cells). Every such cell lies inside the tile, so a map made there centres on the tile (vanilla centres a new map on
 * the grid cell the holder stands in). The server draws a pixel only from a loaded chunk: the caller keeps the farthest
 * corner ({@link #farthestCorner}, at most about 125 blocks) inside the view distance.
 *
 * <p><b>Choice</b>: of those cells, the one with the shortest straight walk from the bot through it to {@code next}
 * (where the walk goes after the fill: the cartography table), ties to the cell nearer the bot; without {@code next}
 * the cell nearest the bot. A bot already on such a cell on its way stays put. Live 2026-10-21..23 (45 maps): the last
 * lane ended at the east edge on most maps; the walk to the centre and from there to the north-east exit of the tile
 * was 60-90 blocks longer than through the nearest covering cell.
 */
public final class FillStand {
    private FillStand() { }

    /** Pixel distance up to which vanilla draws every pixel of a held scale-0 map (squared distance ≤ 126²). */
    static final int RADIUS = 126;
    /** Blocks kept off the coverage edge: the feet's cell is exact, but a drift of a block must not cost a pixel. */
    public static final int MARGIN = 2;

    /** All four corner pixels of the tile within {@code RADIUS - margin} of tile cell ({@code px}, {@code pz}). */
    static boolean covers(int px, int pz, int margin) {
        long r = RADIUS - Math.max(0, margin);
        long r2 = r * r;
        int last = TileArt.SIZE - 1;
        for (int cx : new int[]{0, last}) {
            for (int cz : new int[]{0, last}) {
                long dx = cx - px;
                long dz = cz - pz;
                if (dx * dx + dz * dz > r2) return false;
            }
        }
        return true;
    }

    /** Blocks from cell {@code c}'s centre to the farthest corner of {@code geo}'s tile (horizontal). */
    public static double farthestCorner(PlatformGeometry geo, Pos c) {
        double far = 0;
        for (int x : new int[]{geo.minX(), geo.minX() + TileArt.SIZE}) {
            for (int z : new int[]{geo.minZ(), geo.minZ() + TileArt.SIZE}) {
                far = Math.max(far, Math.hypot(x - (c.x() + 0.5), z - (c.z() + 0.5)));
            }
        }
        return far;
    }

    /**
     * The stand cell (art-layer level) for a bot at ({@code botX}, {@code botZ}) going on to {@code next} afterwards
     * (null: none); only cells {@code allowed} accepts (no-go zones). Null when no covering cell is allowed.
     */
    public static @Nullable Pos choose(PlatformGeometry geo, double botX, double botZ, @Nullable Pos next, int margin,
                                       Predicate<Pos> allowed) {
        Pos best = null;
        double bestCost = Double.MAX_VALUE;
        double bestNear = Double.MAX_VALUE;
        for (int px = 0; px < TileArt.SIZE; px++) {
            for (int pz = 0; pz < TileArt.SIZE; pz++) {
                if (!covers(px, pz, margin)) continue;
                Pos c = geo.cell(px, pz);
                double near = Math.hypot(c.x() + 0.5 - botX, c.z() + 0.5 - botZ);
                double cost = near + (next == null ? 0 : Math.hypot(next.x() + 0.5 - (c.x() + 0.5), next.z() + 0.5 - (c.z() + 0.5)));
                if (cost > bestCost + 1e-9 || (cost > bestCost - 1e-9 && near >= bestNear)) continue;
                if (!allowed.test(c)) continue;
                best = c;
                bestCost = cost;
                bestNear = near;
            }
        }
        return best;
    }
}
