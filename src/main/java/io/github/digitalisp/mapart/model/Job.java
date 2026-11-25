package io.github.digitalisp.mapart.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A queued map-art job. Mutable POJO persisted as JSON (Gson) by the JobStore; mutate only on the
 * thread that owns the change, then call {@code JobStore.save(job)}. CONTRACT (lead-owned).
 */
public class Job {
    public String id;                 // short stable id, e.g. "j0007"
    public String name;               // display name (sanitised), used for map renames
    public String submitterId;        // Discord user id, or "terminal"
    public String submitterName;
    public long createdAt;            // epoch millis
    public int tilesX;
    public int tilesZ;
    public JobStatus status = JobStatus.QUEUED;
    public String statusDetail = "";
    public Map<String, Integer> materials = new LinkedHashMap<>();   // item id → count, whole job
    public List<String> platformIds = new ArrayList<>();             // compatible platforms
    public List<TileState> tiles = new ArrayList<>();                // build order (row-major N→S, W→E)
    public String discordChannelId;   // where the submission came from (for replies), nullable
    public String discordMessageId;   // the submission message, nullable
    /**
     * Same-colour swaps made at upload ({@code map.substituteSameColour}), sorted by the original state. The stored
     * tiles and {@link #materials} already hold the swapped-in blocks; {@link Substitution#from} keeps each original
     * state for the record. Empty for jobs without swaps and for jobs saved before swaps existed.
     */
    public List<Substitution> substitutions = new ArrayList<>();
    /**
     * A colour-suppression map (docs/COLOR_SUPPRESSION.md): what each tile is, {@code suppression.get(i)} for tile (i, 0), in
     * build order. Each such tile is one pass of the map (a level of a layer), not a map of its own. Null for flat maps
     * and for every job saved before colour suppression existed.
     */
    public List<SuppressionPass> suppression;
    /**
     * Colour-suppression: a ride of this job was lost and its cart did not stand on the brake when the bot came for
     * it, so the muncher's run has not ended (BuildEngine.finishLostRun: the cart is fetched and the reset waited
     * for before the first pass is printed again). {@link #lostCartBack}: the cart is back on the start rail (or in
     * the inventory), only the reset is still waited for.
     */
    public boolean lostRunOpen;
    public boolean lostCartBack;
    /**
     * Color suppression with {@code ride.reuseCart}: the platform's own cart is gone (it came off the brake and the
     * item was not picked up, or no cart stood on the start rail and none was carried). The ride's kit takes a spare
     * from {@code ride.cartChests}; cleared once the bot sits in a cart on the start rail.
     */
    public boolean cartLost;
    /**
     * Colour-suppression: rides of this job that reached the brake with a map not showing the finished art, each
     * followed by a reprint (BuildEngine.reprintAfterBadRide; BLOCKED instead once the limit is reached).
     */
    public int badRides;

    public boolean isSuppression() {
        return suppression != null && !suppression.isEmpty();
    }

    public TileState tile(TileCoord c) {
        for (TileState t : tiles) if (t.x == c.x() && t.z == c.z()) return t;
        return null;
    }

    /** One block state of the submitted art and what the job builds instead. */
    public static class Substitution {
        /** The state as submitted, e.g. "minecraft:decorated_pot". */
        public String from;
        /** The state built instead, e.g. "minecraft:red_terracotta"; null = kept, no stocked block has this colour. */
        public String to;
        /** Pixels of {@link #from} across the whole job. */
        public int count;
        /** The 1.21.4 map colour id both show. */
        public int mapColor;
        /** {@code "no chest"} (no material chest at this base) or {@code "time of day"} (eyeblossoms). */
        public String reason;

        public Substitution() { }

        public Substitution(String from, String to, int count, int mapColor, String reason) {
            this.from = from;
            this.to = to;
            this.count = count;
            this.mapColor = mapColor;
            this.reason = reason;
        }
    }

    /** One build pass of a colour-suppression map: see {@link SuppressionArt.Pass}. */
    public static class SuppressionPass {
        /** {@link SuppressionArt#BOTTOM} or {@link SuppressionArt#TOP}. */
        public int layer;
        /** 0 = on the glass, 1 = one block higher. */
        public int level;
        /** The tile holds the noobline row (in its row 0) instead of the map rows. */
        public boolean noobline;

        public SuppressionPass() { }

        public SuppressionPass(int layer, int level, boolean noobline) {
            this.layer = layer;
            this.level = level;
            this.noobline = noobline;
        }
    }

    public static class TileState {
        public int x;
        public int z;
        public TileStatus status = TileStatus.PENDING;
        public String platformId;     // platform it is/was built on
        public long startedAt;
        public long finishedAt;
        public String mapName;        // anvil name given to the locked map
        public int placed;            // last known count of correct blocks on the platform
        public int total;             // non-empty pixels in the tile
        /**
         * The base's map counter ({@code site.mapCounter}) was pressed for this stored map. Set once the button showed
         * the press, so a restart never presses twice. False with the counter off, after a press that failed (alerted),
         * and for maps stored before the counter existed.
         */
        public boolean counted;
        /** Build counters for the "tile summary" log line; null until the engine first counts for this tile. */
        public TileMetrics metrics;
        /**
         * Unlocked maps the engine made for this tile that the bot still carries (a fill, the map held through a cover
         * wait, a fresh map when no map data came): +1 per map made, -1 when one is locked or goes into the scrap map
         * chest after STORE. Map ids change with every connection on 2b2t, so after a reconnect or a restart this tells
         * the engine that its map is still there (FILL_MAP finds it again before it makes another), and after STORE how
         * many stale maps of the tile found by their pixels are the bot's to put away (the owner's own maps are not).
         */
        public int unlockedMaps;
        /** Colour-suppression ride: the launch button was pressed for this map (a restart never presses twice). */
        public boolean ridePressed;
        /**
         * Colour-suppression ride: when the launch button was pressed and when the cart stopped at the brake (epoch
         * ms; 0 = not yet, and for maps ridden before these existed). The status shows the ride's progress from the
         * first; both together are the measured ride the next one is expected to take.
         */
        public long rideStartedAt;
        public long rideEndedAt;
        /**
         * Colour-suppression ride with {@code ride.reuseCart}: the cart was taken off the brake after the ride and is
         * to be put back on the start rail before the map is stored.
         */
        public boolean cartReturnDue;
        /** ... and that step is over (put back, one stood there already, or given up with an alert): never twice. */
        public boolean cartReturned;
        /**
         * Colour-suppression preload lap ({@code ride.preload}): its launch button was pressed and the lap has not ended
         * yet. A restart in the cart rides on without a second press; out of the cart it is BLOCKED unless the bot
         * stands by the lap's start.
         */
        public boolean preloadPressed;
        /** Colour-suppression preload lap: laps finished for this map (for the record: a lap counts only for its own connection). */
        public int preloadLaps;
        /** Colour-suppression ride: after the stored map, the layers were empty and the machines home (RIDE_RESET). */
        public boolean rideSettled;
        /** The ride was lost (out of the cart, the cart went on): the recovery runs ({@code ride.recoverLostRide}). */
        public boolean rideLost;
        /**
         * The ride is paused for an announced server restart ({@code ride.restartPause}): the lane-end hold the cart
         * stood in (the slope rail's cell, where the bot stands with the map), null when not paused; the stage of the
         * pause (BuildEngine.RP_*); the rail the cart goes back on (the next lane's first level rail) and the yaw along
         * that lane. The rail and yaw stay after the pause for the push of the ride that follows.
         */
        public Pos ridePause;
        public int ridePauseStage;
        public Pos ridePauseRail;
        public float ridePauseYaw;
        /**
         * Carry-over: cells of this tile the previous map on
         * {@link #platformId} left in place at its end (the tile is then ACTIVE there before it was ever selected, so it
         * goes on first); 0 = none. Its start line is still posted, naming them.
         */
        public int carried;
        /**
         * Colour suppression, {@code build.suppressionMergeLevels}: the lanes of this level-0 main pass carried the cells
         * of its layer's level-1 passes (one block higher) and placed some. The level-1 noobline pass then plans its row
         * alone (the map rows its geometry covers hold the raised cells), also with the switch off again.
         */
        public boolean carriesRaised;
        /**
         * Color suppression, {@code build.suppressionMergeLevels}: cells of this level-1 main pass its layer's level-0
         * sweep has placed so far. For the progress and the time left only ({@link #progress()}): {@link #placed}
         * stays the pass's own count, set when the pass itself runs, and every decision still reads that.
         */
        public int placedAhead;

        /** Cells of this pass in place for the progress: its own count, or what another pass's lanes placed for it. */
        public int progress() {
            return Math.min(Math.max(0, total), Math.max(placed, placedAhead));
        }

        public TileCoord coord() {
            return new TileCoord(x, z);
        }
    }
}
