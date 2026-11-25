package io.github.digitalisp.mapart.bot;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.function.IntPredicate;

/**
 * Options of a map fill or hold ({@link BotActions#createMap(Pos, int, java.util.function.Consumer, MapHold)},
 * {@link BotActions#holdMap(Pos, int, int, MapHold)}).
 *
 * @param nearStand a cell to fill the map from before the station ({@code map.fillNearStand}): the bot walks there
 *                  first (exactly, feet within a level), and only if that walk fails to the station and its rings as
 *                  before; null = the station only
 * @param shows     the map (by id) shows the expected art: the hold ends as soon as it does, after at least
 *                  {@code minTicks} held ({@code map.holdUntilMatch}); null = the full hold time
 * @param minTicks  ticks held before {@code shows} may end the hold
 */
public record MapHold(@Nullable Pos nearStand, @Nullable IntPredicate shows, int minTicks) {
    /** The station only, the full hold time: the behaviour without options. */
    public static final MapHold PLAIN = new MapHold(null, null, 0);

    /**
     * Whether a hold of {@code holdTicks} ends now, {@code held} ticks in, for map {@code mapId}: at the hold time, or
     * earlier once {@code minTicks} are held and the map shows the art.
     */
    public boolean done(int held, int holdTicks, int mapId) {
        if (held >= holdTicks) return true;
        return shows != null && held >= Math.max(0, minTicks) && shows.test(mapId);
    }
}
