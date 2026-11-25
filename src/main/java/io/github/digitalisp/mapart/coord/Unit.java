package io.github.digitalisp.mapart.coord;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.TileCoord;

/**
 * Work on one tile handed to this bot ({@link Coordination#unit}): the coordinator's generic "list" job unit. Three
 * kinds:
 * <ul>
 *   <li>a whole tile, key {@code <map>/<i>,<j>} (D7 step 1: one bot per platform);</li>
 *   <li>a lane band of a tile, key {@code <map>/<i>,<j>/lanes:<a>-<b>} ({@link #bandKey}; D7 step 2: several bots on one
 *       tile, the platform claimed shared);</li>
 *   <li>a tile's tail, key {@code <map>/<i>,<j>/tail} ({@link #tailKey}): after every band of the tile, the whole-tile
 *       flow from VERIFY on (map, lock, rename, store, count, nuke, clear), the platform claimed exclusive.</li>
 * </ul>
 * {@link LocalCoordination} makes a whole-tile unit for every tile of the local queue (fence 0, attempt 1, nothing
 * handed over), so the engine always works on a unit.
 *
 * @param key        the unit key: {@code <map>/<i>,<j>} ({@link #tileKey}), with {@code /lanes:<a>-<b>} or
 *                   {@code /tail} for a band or a tail
 * @param job        the local job the tile belongs to (its id is the map id: the local queue's job, or the job a
 *                   grant made of its spec)
 * @param fence      the grant's fence; every report carries it (an older one is refused as {@code stale_fence})
 * @param attempt    how many times the unit has been granted (1 = first)
 * @param spec       what the tile job needs ({@code unit.grant} "spec"); null for local units
 * @param handedOver the progress data the previous holders reported ({@code unit.grant} "progress"); null when none
 */
public record Unit(String key, String job, long fence, int attempt, @Nullable TileUnitSpec spec,
                   @Nullable TileProgress handedOver) {

    /** {@code <map>/<i>,<j>}: tile column {@code i} (west to east) and row {@code j} (north to south) of map {@code map}. */
    public static String tileKey(String map, int i, int j) {
        return map + "/" + i + "," + j;
    }

    /** {@code <map>/<i>,<j>/lanes:<a>-<b>}: lanes {@code a} to {@code b} (inclusive) of a tile. */
    public static String bandKey(String map, int i, int j, int laneA, int laneB) {
        return tileKey(map, i, j) + BAND_SUFFIX + laneA + "-" + laneB;
    }

    /** {@code <map>/<i>,<j>/tail}: the tile's tail, after all its bands. */
    public static String tailKey(String map, int i, int j) {
        return tileKey(map, i, j) + TAIL_SUFFIX;
    }

    static final String BAND_SUFFIX = "/lanes:";
    static final String TAIL_SUFFIX = "/tail";

    /** The tile part of a unit key: {@code <map>/<i>,<j>} with a band or tail suffix cut off (other keys unchanged). */
    public static @Nullable String tileKeyOf(String key) {
        if (key == null) return null;
        int band = key.lastIndexOf(BAND_SUFFIX);
        if (band > 0) return key.substring(0, band);
        if (key.endsWith(TAIL_SUFFIX) && key.length() > TAIL_SUFFIX.length())
            return key.substring(0, key.length() - TAIL_SUFFIX.length());
        return key;
    }

    /** The tile of a {@code <map>/<i>,<j>} key (a band's or a tail's too), or null for another key. */
    public static @Nullable TileCoord tileOf(String unitKey) {
        String key = tileKeyOf(unitKey);
        if (key == null) return null;
        int slash = key.lastIndexOf('/');
        if (slash <= 0) return null;
        String[] p = key.substring(slash + 1).split(",");
        if (p.length != 2) return null;
        try {
            return new TileCoord(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The map of a {@code <map>/<i>,<j>} key (a band's or a tail's too), or null for another key. */
    public static @Nullable String mapOf(String unitKey) {
        if (tileOf(unitKey) == null) return null;
        String key = tileKeyOf(unitKey);
        return key.substring(0, key.lastIndexOf('/'));
    }

    /** This unit is a lane band of its tile ({@link TileUnitSpec#band()}). */
    public boolean band() {
        return spec != null && spec.band() != null;
    }

    /** This unit is its tile's tail ({@link TileUnitSpec#tail()}). */
    public boolean tail() {
        return spec != null && spec.tail();
    }

    /**
     * Another holder may have worked on this tile: the platform's blocks that match the tile are its partial build, to
     * be resumed (INSPECT), never nuked as foreign. True for a retry, for handed-over progress that shows a start, and
     * for a spec marked {@code started} (the tile was begun on its platform before it became a unit: a local job's
     * ACTIVE tile turned into a unit). False for a first grant of a tile
     * nobody began (and for local units); INSPECT still resumes a build whose every block is the tile's own. Always
     * true for a band (the tile's other bands are other bots' work) and a tail (the tile's bands were built first).
     */
    public boolean resumable() {
        return attempt > 1 || (handedOver != null && handedOver.started()) || (spec != null && spec.started())
            || band() || tail();
    }

    /** The same grant: same job, key and fence (a revoke for an older grant must not stop the current one). */
    public boolean sameGrant(@Nullable Unit o) {
        return o != null && key.equals(o.key) && job.equals(o.job) && fence == o.fence;
    }
}
