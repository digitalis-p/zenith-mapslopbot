package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * A tile unit's progress data ({@link Coordination#progress}): what the next holder of the unit cannot read from the
 * world. The coordinator merges it into the unit and hands it to the next holder with the grant ({@link Unit#handedOver}).
 *
 * <p>From the world (never here, decided again by every holder): which cells are placed and right (INSPECT's survey,
 * VERIFY's scan), what the NUKE still has to break, whether the art layer is clear (NUKE, WAIT_CLEAR), whether the item
 * collection is on (its lamp). Here: the facts the world cannot show or that live in one account's inventory.
 *
 * <p>JSON shape (unit.progress "data", at most 64 KiB; unknown fields are ignored, missing ones take the defaults):
 * <pre>
 * {"v": 1, "placed": 9120, "total": 16384, "phase": "BUILD", "platform": "soul",
 *  "stored": false, "counted": false, "ridePressed": false, "rideSettled": false,
 *  "clearAttempts": 0, "repairPasses": 0, "fillAttempts": 0, "startedAt": 1761300000000,
 *  "mapName": "harbor 3,1", "note": "", "unlockedMaps": 1, "mapsHolder": "jared2013", "mapId": 766, "mapPng": "iVBO..."}
 * </pre>
 *
 * @param placed        correct cells on the platform at the report (the world's count as of INSPECT/BUILD/VERIFY)
 * @param total         non-empty pixels of the tile
 * @param phase         the engine phase at the report ({@code io.github.digitalisp.mapart.engine.Phase} name)
 * @param platform      the platform id it is built on
 * @param stored        the locked map is in the output chest: the next holder goes on with COUNT/NUKE, never
 *                      builds or stores a second map
 * @param counted       the base's map counter was pressed for the stored map (never twice)
 * @param ridePressed   colour suppression: the ride's launch button was pressed for this map
 * @param rideSettled   colour suppression: the layers were empty and the machines home after the stored map
 * @param clearAttempts clear presses of this tile so far (the retry limit carries over)
 * @param repairPasses  VERIFY repair passes so far
 * @param fillAttempts  map fill attempts so far
 * @param startedAt     epoch millis the tile was first started (for the summary)
 * @param mapName       the anvil name the map gets (derived from the job; recorded for the dashboard)
 * @param note          the engine's note (a BLOCKED reason), or ""
 * @param unlockedMaps  maps of this tile the holder made and has not locked ({@code TileState.unlockedMaps}): they
 *                      live in {@code mapsHolder}'s inventory, so only that account adopts the count on a new grant
 *                      (a restart mid-tile: live 2026-10-25, two restarts in a cover wait left two stale maps)
 * @param mapsHolder    the account those maps are in, or null
 * @param mapId         the stored map item's {@code map_id} (as numbered in the storing bot's connection, the id its own
 *                      "Map done" post names), or null: set by the unit that stores the map (a whole tile or a tail,
 *                      never a band) from the store on, in progress (a successor's done report carries it on) and in
 *                      the done result
 * @param mapPng        the done result only: base64 PNG of the stored map's render (the "Map done" image), or null
 *                      (no pixels read, a band, a successor that did not store it, or too large: the whole result stays
 *                      within the coordinator's 64 KiB). Never in progress reports
 */
public record TileProgress(int placed, int total, String phase, @Nullable String platform, boolean stored,
                           boolean counted, boolean ridePressed, boolean rideSettled, int clearAttempts,
                           int repairPasses, int fillAttempts, long startedAt, @Nullable String mapName, String note,
                           int unlockedMaps, @Nullable String mapsHolder, @Nullable Integer mapId,
                           @Nullable String mapPng) {
    public static final int VERSION = 1;

    public TileProgress {
        phase = phase == null ? "" : phase;
        note = note == null ? "" : note;
        unlockedMaps = Math.max(0, unlockedMaps);
    }

    /** Without maps carried (unlockedMaps 0). */
    public TileProgress(int placed, int total, String phase, @Nullable String platform, boolean stored,
                        boolean counted, boolean ridePressed, boolean rideSettled, int clearAttempts,
                        int repairPasses, int fillAttempts, long startedAt, @Nullable String mapName, String note) {
        this(placed, total, phase, platform, stored, counted, ridePressed, rideSettled, clearAttempts, repairPasses,
            fillAttempts, startedAt, mapName, note, 0, null, null, null);
    }

    /** Without a stored map's id or render. */
    public TileProgress(int placed, int total, String phase, @Nullable String platform, boolean stored,
                        boolean counted, boolean ridePressed, boolean rideSettled, int clearAttempts,
                        int repairPasses, int fillAttempts, long startedAt, @Nullable String mapName, String note,
                        int unlockedMaps, @Nullable String mapsHolder) {
        this(placed, total, phase, platform, stored, counted, ridePressed, rideSettled, clearAttempts, repairPasses,
            fillAttempts, startedAt, mapName, note, unlockedMaps, mapsHolder, null, null);
    }

    /** The same, its maps in {@code holder}'s inventory. */
    public TileProgress withMapsHolder(@Nullable String holder) {
        return new TileProgress(placed, total, phase, platform, stored, counted, ridePressed, rideSettled, clearAttempts,
            repairPasses, fillAttempts, startedAt, mapName, note, unlockedMaps, holder, mapId, mapPng);
    }

    /** The same without maps carried (another account's maps are not this one's). */
    public TileProgress withoutMaps() {
        return new TileProgress(placed, total, phase, platform, stored, counted, ridePressed, rideSettled, clearAttempts,
            repairPasses, fillAttempts, startedAt, mapName, note, 0, null, mapId, mapPng);
    }

    /** The same with the stored map's render ({@code png} base64, null = none): for the done result only. */
    public TileProgress withMapPng(@Nullable String png) {
        return new TileProgress(placed, total, phase, platform, stored, counted, ridePressed, rideSettled, clearAttempts,
            repairPasses, fillAttempts, startedAt, mapName, note, unlockedMaps, mapsHolder, mapId, png);
    }

    /** The unit was worked on before: cells may be placed, or the map stored (a successor resumes, never nukes it). */
    public boolean started() {
        return placed > 0 || stored || startedAt > 0;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("placed", placed);
        o.addProperty("total", total);
        o.addProperty("phase", phase);
        if (platform != null) o.addProperty("platform", platform);
        o.addProperty("stored", stored);
        o.addProperty("counted", counted);
        o.addProperty("ridePressed", ridePressed);
        o.addProperty("rideSettled", rideSettled);
        o.addProperty("clearAttempts", clearAttempts);
        o.addProperty("repairPasses", repairPasses);
        o.addProperty("fillAttempts", fillAttempts);
        o.addProperty("startedAt", startedAt);
        if (mapName != null) o.addProperty("mapName", mapName);
        o.addProperty("note", note);
        if (unlockedMaps > 0) {
            o.addProperty("unlockedMaps", unlockedMaps);
            if (mapsHolder != null) o.addProperty("mapsHolder", mapsHolder);
        }
        if (mapId != null) o.addProperty("mapId", mapId);
        if (mapPng != null) o.addProperty("mapPng", mapPng);
        return o;
    }

    /** Reads {@link #toJson}'s shape; null for a missing or non-object value (a unit never worked on). */
    public static @Nullable TileProgress fromJson(@Nullable JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        return new TileProgress(Json.i(o, "placed"), Json.i(o, "total"), Json.s(o, "phase"), Json.s(o, "platform"),
            Json.b(o, "stored"), Json.b(o, "counted"), Json.b(o, "ridePressed"), Json.b(o, "rideSettled"),
            Json.i(o, "clearAttempts"), Json.i(o, "repairPasses"), Json.i(o, "fillAttempts"), Json.l(o, "startedAt"),
            Json.s(o, "mapName"), Json.s(o, "note"), Json.i(o, "unlockedMaps"), Json.s(o, "mapsHolder"),
            Json.get(o, "mapId") == null ? null : Json.i(o, "mapId"), Json.s(o, "mapPng"));
    }
}
