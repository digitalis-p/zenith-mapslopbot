package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.store.TileCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * What a tile job needs: the spec of one {@code <map>/<i>,<j>} unit ({@link Unit#spec()}). One is made per tile
 * ({@link #of}); a bot that is granted the unit makes a local job of it ({@link #toJob}) and builds it like a queued
 * one. {@link LocalCoordination}'s units carry none; the engine reads a spec's band, tail and next tile.
 *
 * <p>JSON shape ({@code unit.grant} "spec", at most 128 KiB; the list job's params hold 256 KiB for all units):
 * <pre>
 * {"map": "j0002", "tile": [3, 1], "platform": "soul", "dim": "overworld",
 *  "x1": 1000, "z1": 2000, "x2": 1127, "z2": 2127,
 *  "v": 2, "name": "harbor", "tilesX": 5, "tilesZ": 8, "total": 16384,
 *  "materials": {"minecraft:white_carpet": 6625, ...}, "artSha256": "9f2c...",
 *  "art": {"version": 1, "palette": ["minecraft:white_carpet", ...], "enc": "deflate", "cells": "base64..."},
 *  "started": true}
 * </pre>
 * A lane band of the tile (D7 step 2) adds
 * {@code "band": {"lanes": [a, b], "cells": {"x": [x1, x2], "z": [z1, z2]}}}: lanes {@code a..b} of the tile's lane
 * geometry and the tile-local cells (0..127, inclusive) they cover. The cells are authoritative: the bot builds every
 * cell of the rectangle with its own lane geometry clipped to it, and nothing outside it; {@code total} and
 * {@code materials} are the band's. The tile's tail adds {@code "tail": true} ({@code total} and {@code materials} the
 * whole tile's), and in a lane job the tile built next on the same platform, {@code "next"} ({@link Next}: carry-over).
 * Neither = the whole tile, as before.
 * {@code x1..z2} is the platform's 128 x 128 art area (inclusive): the list job's {@code "order": "nearest"} gives each
 * bot the tile whose box is nearest to it. {@code platform} is the one platform the tile is built on (the bot claims
 * {@code platform:<id>}). {@code art} is the tile file's own compact form ({@link TileCodec}: palette, then 16384
 * little-endian shorts), the cells deflated before base64 ({@code "enc": "deflate"}; without {@code enc} they are
 * TileCodec's plain base64, about 44 KiB).
 *
 * <p>Art by reference: a map whose inline art does not fit the params carries {@code "artRef": {"parts": n}} instead
 * of {@code art}; the art lives in kv namespace {@code ch:mapart} under {@code art/<artSha256>/<k>} for k = 0..n-1
 * ({@link ArtChunks}), content-addressed, written once by whoever has the art. The bot reads the parts on the grant.
 * Colour-suppression passes are not units (their platform is not shared work): {@link #toJob} makes plain map tiles.
 *
 * <p>{@code started} (written only when true): the tile was started on {@code platform} before it became a unit
 * (made from a local job's ACTIVE tile). Its first holder
 * takes a matching partial build there as its own and resumes it ({@link Unit#resumable()}), instead of nuking it as
 * foreign.
 *
 * @param map       the map art's id: the local job id on the bot that builds the tile, and the unit key's first part
 * @param name      display name, used for the map's anvil name
 * @param x         tile column (west → east, 0-based): the key's {@code i}
 * @param z         tile row (north → south, 0-based): the key's {@code j}
 * @param tilesX    columns of the whole map art (the anvil name carries "x,z" when the art has more than one tile)
 * @param tilesZ    rows of the whole map art
 * @param platform  the platform the tile is built on ({@code platform:<id>} is claimed)
 * @param dim       "overworld", "nether" or "end"
 * @param box       the platform's art area in world blocks, or null (then {@code "nearest"} cannot place the unit)
 * @param total     non-empty pixels of the tile
 * @param materials item id → cells of this tile (the stock check)
 * @param artSha256 SHA-256 of the art ({@link #sha256}): the bot checks the art it builds is this one
 * @param art       the tile's art inline (or fetched by reference), or null
 * @param artParts  parts of the art by reference ({@code artRef}); 0 = inline or none
 * @param started   the tile was started on {@code platform} before it became a unit: a matching build there is its own
 * @param band      the lane band this unit builds, or null (the whole tile, or its tail)
 * @param tail      this unit is the tile's tail (after all its bands): VERIFY, the map, store, nuke and clear
 * @param next      a tail only: the tile built next on the same platform ({@link Next}), or null
 */
public record TileUnitSpec(String map, String name, int x, int z, int tilesX, int tilesZ, String platform, String dim,
                           @Nullable Box box, int total, Map<String, Integer> materials, String artSha256,
                           @Nullable TileArt art, int artParts, boolean started, @Nullable Band band, boolean tail,
                           @Nullable Next next) {
    public static final int VERSION = 2;
    public static final String OVERWORLD = "overworld";

    /** A platform's art area in world blocks, inclusive bounds. */
    public record Box(int x1, int z1, int x2, int z2) {
        /** The 128 x 128 art area of a platform. */
        public static Box of(PlatformGeometry g) {
            return new Box(g.minX(), g.minZ(), g.minX() + TileArt.SIZE - 1, g.minZ() + TileArt.SIZE - 1);
        }
    }

    /**
     * Lanes {@code laneA..laneB} of a tile and the tile-local cells they cover, {@code x1..x2} by {@code z1..z2}
     * (inclusive, 0..127).
     */
    public record Band(int laneA, int laneB, int x1, int x2, int z1, int z2) {
        public Band {
            if (laneA < 0 || laneB < laneA) throw new IllegalArgumentException("band lanes " + laneA + "-" + laneB);
            if (x1 < 0 || x2 < x1 || x2 >= TileArt.SIZE || z1 < 0 || z2 < z1 || z2 >= TileArt.SIZE)
                throw new IllegalArgumentException("band cells x " + x1 + ".." + x2 + " z " + z1 + ".." + z2);
        }

        /** Tile-local cell {@code px, pz} lies in the band. */
        public boolean contains(int px, int pz) {
            return px >= x1 && px <= x2 && pz >= z1 && pz <= z2;
        }

        /** Cells of the rectangle (empty pixels included). */
        public int cells() {
            return (x2 - x1 + 1) * (z2 - z1 + 1);
        }

        /** "lanes a-b" (logs, status). */
        public String label() {
            return "lanes " + laneA + "-" + laneB;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonArray lanes = new JsonArray();
            lanes.add(laneA);
            lanes.add(laneB);
            o.add("lanes", lanes);
            JsonObject cells = new JsonObject();
            JsonArray xs = new JsonArray();
            xs.add(x1);
            xs.add(x2);
            JsonArray zs = new JsonArray();
            zs.add(z1);
            zs.add(z2);
            cells.add("x", xs);
            cells.add("z", zs);
            o.add("cells", cells);
            return o;
        }

        static Band fromJson(JsonObject o) throws IOException {
            JsonObject cells = Json.obj(o, "cells");
            if (cells == null) throw new IOException("tile unit spec band has no cells");
            int[] lanes = pair(Json.get(o, "lanes"));
            int[] xs = pair(Json.get(cells, "x"));
            int[] zs = pair(Json.get(cells, "z"));
            try {
                return new Band(lanes[0], lanes[1], xs[0], xs[1], zs[0], zs[1]);
            } catch (IllegalArgumentException e) {
                throw new IOException("tile unit spec " + e.getMessage());
            }
        }

        private static int[] pair(@Nullable JsonElement e) throws IOException {
            if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 2)
                throw new IOException("tile unit spec band wants [from, to] pairs");
            try {
                return new int[] {e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(1).getAsInt()};
            } catch (RuntimeException ex) {
                throw new IOException("tile unit spec band pair is not [from, to]");
            }
        }
    }

    /**
     * The tile built next on the same platform after this tail: the
     * platform's after chain of a lane job has its bands after this tail, so it is firmly next
     * there. The tail's holder may leave the cells the two share in place at its NUKE. Its art inline ({@code art}) or by
     * reference ({@code artRef}: the same kv parts as the next tile's own units).
     * <pre>
     * "next": {"map": "j0002", "tile": [3, 2], "artSha256": "9f2c...", "art": {...}}
     * </pre>
     *
     * @param art      the next tile's art inline (or fetched by reference), or null
     * @param artParts parts of its art by reference; 0 = inline or none
     */
    public record Next(String map, int x, int z, String artSha256, @Nullable TileArt art, int artParts) {
        /** The next tile's key, {@code <map>/<i>,<j>}. */
        public String tileKey() {
            return Unit.tileKey(map, x, z);
        }

        /** The tile of spec {@code s} as a tail's next tile (its art inline). */
        public static Next of(TileUnitSpec s) {
            return new Next(s.map(), s.x(), s.z(), s.artSha256(), s.art(), 0);
        }

        public Next withArt(TileArt a) {
            return new Next(map, x, z, artSha256, a, artParts);
        }

        public Next byReference(int parts) {
            return new Next(map, x, z, artSha256, null, parts);
        }

        JsonObject toJson(java.util.function.Function<TileArt, JsonObject> artJson) {
            JsonObject o = new JsonObject();
            o.addProperty("map", map);
            JsonArray tile = new JsonArray();
            tile.add(x);
            tile.add(z);
            o.add("tile", tile);
            o.addProperty("artSha256", artSha256);
            if (art != null && artParts == 0) o.add("art", artJson.apply(art));
            else if (artParts > 0) {
                JsonObject r = new JsonObject();
                r.addProperty("parts", artParts);
                o.add("artRef", r);
            }
            return o;
        }

        /**
         * Reads {@link #toJson}'s shape; null for none, or for one that does not read (no map or tile, an art that does
         * not decode or differs from its SHA-256, neither art nor a valid artRef): the tail is then built as before, with
         * a nuke and a clear, never failed for it.
         */
        static @Nullable Next fromJson(@Nullable JsonObject o) {
            if (o == null) return null;
            try {
                String map = Json.s(o, "map");
                JsonElement tile = Json.get(o, "tile");
                String sha = Json.s(o, "artSha256");
                if (map == null || map.isBlank() || tile == null || !tile.isJsonArray() || tile.getAsJsonArray().size() != 2
                    || sha == null || !sha.matches("[0-9a-fA-F]{64}")) return null;
                int x = tile.getAsJsonArray().get(0).getAsInt();
                int z = tile.getAsJsonArray().get(1).getAsInt();
                JsonObject a = Json.obj(o, "art");
                if (a != null) {
                    TileArt art = artFromJson(a);
                    return sha.equalsIgnoreCase(sha256(art)) ? new Next(map, x, z, sha, art, 0) : null;
                }
                JsonObject ref = Json.obj(o, "artRef");
                int parts = ref == null ? 0 : Json.i(ref, "parts");
                return parts > 0 && parts <= ArtChunks.MAX_PARTS ? new Next(map, x, z, sha, null, parts) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    public TileUnitSpec {
        if (band != null && tail) throw new IllegalArgumentException("a unit is a band or a tail, not both");
        if (next != null && !tail) throw new IllegalArgumentException("only a tail names the next tile");
        platform = platform == null ? "" : platform;
        dim = dim == null || dim.isBlank() ? OVERWORLD : dim;
        materials = materials == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(materials));
        artSha256 = artSha256 == null ? "" : artSha256;
    }

    /** A spec of a tile not started before it became a unit. */
    public TileUnitSpec(String map, String name, int x, int z, int tilesX, int tilesZ, String platform, String dim,
                        @Nullable Box box, int total, Map<String, Integer> materials, String artSha256,
                        @Nullable TileArt art, int artParts) {
        this(map, name, x, z, tilesX, tilesZ, platform, dim, box, total, materials, artSha256, art, artParts, false);
    }

    /** A whole tile's spec (no band, not a tail). */
    public TileUnitSpec(String map, String name, int x, int z, int tilesX, int tilesZ, String platform, String dim,
                        @Nullable Box box, int total, Map<String, Integer> materials, String artSha256,
                        @Nullable TileArt art, int artParts, boolean started) {
        this(map, name, x, z, tilesX, tilesZ, platform, dim, box, total, materials, artSha256, art, artParts, started,
            null, false, null);
    }

    /**
     * The unit key: {@code <map>/<i>,<j>} for a whole tile, {@code <map>/<i>,<j>/lanes:<a>-<b>} for a band,
     * {@code <map>/<i>,<j>/tail} for a tail.
     */
    public String key() {
        if (band != null) return Unit.bandKey(map, x, z, band.laneA(), band.laneB());
        if (tail) return Unit.tailKey(map, x, z);
        return tileKey();
    }

    /** The tile's key, {@code <map>/<i>,<j>}, whatever the unit's kind. */
    public String tileKey() {
        return Unit.tileKey(map, x, z);
    }

    /** The same spec with its art (fetched by reference). */
    public TileUnitSpec withArt(TileArt a) {
        return new TileUnitSpec(map, name, x, z, tilesX, tilesZ, platform, dim, box, total, materials, artSha256, a,
            artParts, started, band, tail, next);
    }

    /** The same spec marked {@code started} (or not): see the class comment. */
    public TileUnitSpec withStarted(boolean s) {
        return new TileUnitSpec(map, name, x, z, tilesX, tilesZ, platform, dim, box, total, materials, artSha256, art,
            artParts, s, band, tail, next);
    }

    /** The same spec carrying its art by reference in {@code parts} kv entries instead of inline. */
    public TileUnitSpec byReference(int parts) {
        return new TileUnitSpec(map, name, x, z, tilesX, tilesZ, platform, dim, box, total, materials, artSha256, null,
            parts, started, band, tail, next);
    }

    /**
     * This tile's band {@code b}, with the band's own {@code total} and {@code materials} (the art's cells inside the
     * rectangle; needs the art inline). The art stays the whole tile's: the bot filters its plan to the band.
     */
    public TileUnitSpec withBand(Band b) {
        if (art == null) throw new IllegalStateException("a band's totals come from the art: the spec carries none");
        Map<String, Integer> mats = new TreeMap<>();
        int n = 0;
        for (int pz = b.z1(); pz <= b.z2(); pz++)
            for (int px = b.x1(); px <= b.x2(); px++) {
                BlockSpec c = art.get(px, pz);
                if (c == null) continue;
                n++;
                mats.merge(c.id(), 1, Integer::sum);
            }
        return new TileUnitSpec(map, name, x, z, tilesX, tilesZ, platform, dim, box, n, mats, artSha256, art, artParts,
            started, b, false, null);
    }

    /** The same tail naming {@code n} as the tile built next on its platform (null: none). */
    public TileUnitSpec withNext(@Nullable Next n) {
        return new TileUnitSpec(map, name, x, z, tilesX, tilesZ, platform, dim, box, total, materials, artSha256, art,
            artParts, started, band, tail, n);
    }

    /** This tile's tail: the whole tile's totals, after every band. */
    public TileUnitSpec asTail() {
        return new TileUnitSpec(map, name, x, z, tilesX, tilesZ, platform, dim, box, total, materials, artSha256, art,
            artParts, started, null, true, next);
    }

    /**
     * The spec of tile {@code t} of a local job with its art, built on {@code platform} whose art area is {@code g}
     * (null: no box).
     */
    public static TileUnitSpec of(Job j, Job.TileState t, TileArt art, String platform, @Nullable PlatformGeometry g) {
        Map<String, Integer> mats = new TreeMap<>();
        art.blockCounts().forEach((k, v) -> mats.merge(BlockSpec.parse(k).id(), v, Integer::sum));
        return new TileUnitSpec(j.id, j.name, t.x, t.z, j.tilesX, j.tilesZ, platform, OVERWORLD,
            g == null ? null : Box.of(g), art.nonEmptyCount(), mats, sha256(art), art, 0);
    }

    /** {@link #of(Job, Job.TileState, TileArt, String, PlatformGeometry)} on the job's first platform, without a box. */
    public static TileUnitSpec of(Job j, Job.TileState t, TileArt art) {
        String p = j.platformIds == null || j.platformIds.isEmpty() ? "" : j.platformIds.getFirst();
        return of(j, t, art, p, null);
    }

    /**
     * A local job holding just this tile, PENDING (what the bot builds when granted the unit; the job's other tiles
     * belong to other units). Its materials are this tile's; its one platform is the spec's.
     */
    public Job toJob() {
        Job j = new Job();
        j.id = map;
        j.name = name;
        j.submitterId = "coordinator";
        j.submitterName = "coordinator";
        j.tilesX = tilesX;
        j.tilesZ = tilesZ;
        j.status = JobStatus.QUEUED;
        j.materials = new java.util.LinkedHashMap<>(materials);
        j.platformIds = new ArrayList<>(platform.isEmpty() ? List.of() : List.of(platform));
        j.tiles.add(newTile());
        return j;
    }

    /** This spec's tile as a fresh PENDING tile state. */
    public Job.TileState newTile() {
        Job.TileState t = new Job.TileState();
        t.x = x;
        t.z = z;
        t.total = total;
        return t;
    }

    /** SHA-256 (hex) of the art's compact form: the palette lines, then the cells. */
    public static String sha256(TileArt art) {
        TileCodec.Stored s = TileCodec.encode(art);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : s.palette) md.update((p + "\n").getBytes(StandardCharsets.UTF_8));
            md.update(s.cells.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonObject toJson() {
        return toJson(TileUnitSpec::artJson);
    }

    /**
     * {@link #toJson()} with the {@code art} object from {@code artJson} (a tile's art serialised once for all its bands
     * and its tail; the result is the same).
     */
    JsonObject toJson(java.util.function.Function<TileArt, JsonObject> artJson) {
        JsonObject o = new JsonObject();
        o.addProperty("map", map);
        JsonArray tile = new JsonArray();
        tile.add(x);
        tile.add(z);
        o.add("tile", tile);
        o.addProperty("platform", platform);
        o.addProperty("dim", dim);
        if (box != null) {
            o.addProperty("x1", box.x1());
            o.addProperty("z1", box.z1());
            o.addProperty("x2", box.x2());
            o.addProperty("z2", box.z2());
        }
        o.addProperty("v", VERSION);
        o.addProperty("name", name);
        o.addProperty("tilesX", tilesX);
        o.addProperty("tilesZ", tilesZ);
        o.addProperty("total", total);
        JsonObject m = new JsonObject();
        materials.forEach(m::addProperty);
        o.add("materials", m);
        o.addProperty("artSha256", artSha256);
        if (art != null && artParts == 0) {
            o.add("art", artJson.apply(art));
        } else if (artParts > 0) {
            JsonObject r = new JsonObject();
            r.addProperty("parts", artParts);
            o.add("artRef", r);
        }
        if (started) o.addProperty("started", true);
        if (band != null) o.add("band", band.toJson());
        if (tail) o.addProperty("tail", true);
        if (next != null) o.add("next", next.toJson(artJson));
        return o;
    }

    /** The art's compact form as the spec's {@code art} object (palette, deflated cells). */
    public static JsonObject artJson(TileArt art) {
        TileCodec.Stored s = TileCodec.encode(art);
        JsonObject a = new JsonObject();
        a.addProperty("version", s.version);
        JsonArray pal = new JsonArray();
        s.palette.forEach(pal::add);
        a.add("palette", pal);
        a.addProperty("enc", "deflate");
        a.addProperty("cells", deflate(s.cells));
        return a;
    }

    /**
     * Reads {@link #toJson}'s shape. Throws IOException for a spec that is not one (no map, no tile), an art that does
     * not decode, or an art whose SHA-256 differs from {@code artSha256}.
     */
    public static TileUnitSpec fromJson(JsonElement e) throws IOException {
        if (e == null || !e.isJsonObject()) throw new IOException("tile unit spec is not a JSON object");
        JsonObject o = e.getAsJsonObject();
        String map = Json.s(o, "map");
        if (map == null || map.isBlank()) throw new IOException("tile unit spec has no map");
        JsonElement tile = Json.get(o, "tile");
        if (tile == null || !tile.isJsonArray() || tile.getAsJsonArray().size() != 2)
            throw new IOException("tile unit spec has no tile [i, j]");
        int x, z;
        try {
            x = tile.getAsJsonArray().get(0).getAsInt();
            z = tile.getAsJsonArray().get(1).getAsInt();
        } catch (RuntimeException ex) {
            throw new IOException("tile unit spec tile is not [i, j]");
        }
        Box box = null;
        if (Json.get(o, "x1") != null && Json.get(o, "z1") != null && Json.get(o, "x2") != null
            && Json.get(o, "z2") != null)
            box = new Box(Json.i(o, "x1"), Json.i(o, "z1"), Json.i(o, "x2"), Json.i(o, "z2"));
        Map<String, Integer> mats = new TreeMap<>();
        JsonObject m = Json.obj(o, "materials");
        if (m != null) for (var en : m.entrySet()) mats.put(en.getKey(), Json.i(m, en.getKey()));
        String sha = Json.s(o, "artSha256");
        TileArt art = null;
        JsonObject a = Json.obj(o, "art");
        if (a != null) {
            art = artFromJson(a);
            String got = sha256(art);
            if (sha != null && !sha.isBlank() && !sha.equalsIgnoreCase(got))
                throw new IOException("tile unit spec art does not match its artSha256");
            if (sha == null || sha.isBlank()) sha = got;
        }
        int parts = 0;
        JsonObject ref = Json.obj(o, "artRef");
        if (art == null && ref != null) {
            parts = Json.i(ref, "parts");
            if (parts <= 0 || parts > ArtChunks.MAX_PARTS)
                throw new IOException("tile unit spec artRef has " + parts + " parts");
            if (sha == null || !sha.matches("[0-9a-fA-F]{64}"))
                throw new IOException("tile unit spec artRef needs the art's artSha256");
        }
        String name = Json.s(o, "name");
        JsonObject bandJson = Json.obj(o, "band");
        Band band = bandJson == null ? null : Band.fromJson(bandJson);
        boolean tail = Json.b(o, "tail");
        if (band != null && tail) throw new IOException("tile unit spec is a band and a tail");
        return new TileUnitSpec(map, name == null ? map : name, x, z, Math.max(1, Json.i(o, "tilesX")),
            Math.max(1, Json.i(o, "tilesZ")), Json.s(o, "platform"), Json.s(o, "dim"), box, Json.i(o, "total"), mats,
            sha, art, parts, Json.b(o, "started"), band, tail, tail ? Next.fromJson(Json.obj(o, "next")) : null);
    }

    /** The spec's {@code art} object back to the art. */
    static TileArt artFromJson(JsonObject a) throws IOException {
        TileCodec.Stored s = new TileCodec.Stored();
        s.version = Json.i(a, "version");
        String enc = Json.s(a, "enc");
        s.cells = Json.s(a, "cells");
        if ("deflate".equals(enc)) s.cells = inflate(s.cells);
        else if (enc != null && !enc.isEmpty()) throw new IOException("tile unit spec art has unknown enc " + enc);
        if (s.cells == null) throw new IOException("tile unit spec art has no cells");
        JsonElement pal = Json.get(a, "palette");
        s.palette = new ArrayList<>();
        if (pal != null && pal.isJsonArray()) for (JsonElement p : pal.getAsJsonArray()) s.palette.add(p.getAsString());
        try {
            return TileCodec.decode(s);
        } catch (RuntimeException ex) {
            throw new IOException("tile unit spec art does not decode: " + ex.getMessage(), ex);
        }
    }

    /** {@link #artFromJson} of a JSON text. */
    static TileArt artFromJson(String text) throws IOException {
        JsonElement e;
        try {
            e = JsonParser.parseString(text);
        } catch (RuntimeException ex) {
            throw new IOException("art is not JSON");
        }
        if (!e.isJsonObject()) throw new IOException("art is not a JSON object");
        return artFromJson(e.getAsJsonObject());
    }

    /** Raw cells of a 128 x 128 tile: 16384 shorts. */
    private static final int CELL_BYTES = TileArt.SIZE * TileArt.SIZE * 2;

    /** TileCodec's base64 cells to base64 of their deflated bytes. */
    static String deflate(String cells) {
        return Base64.getEncoder().encodeToString(deflateBytes(Base64.getDecoder().decode(cells)));
    }

    static byte[] deflateBytes(byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(raw);
            d.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length / 4 + 64);
            byte[] buf = new byte[8192];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    /** {@link #deflate}'s form back to TileCodec's base64 cells; never inflates past one tile's cells. */
    static String inflate(String deflated) throws IOException {
        if (deflated == null) throw new IOException("tile unit spec art has no cells");
        byte[] in;
        try {
            in = Base64.getDecoder().decode(deflated);
        } catch (IllegalArgumentException e) {
            throw new IOException("tile unit spec art cells are not valid base64", e);
        }
        byte[] raw = inflateBytes(in, CELL_BYTES);
        if (raw.length != CELL_BYTES)
            throw new IOException("tile unit spec art inflates to " + raw.length + " bytes, expected " + CELL_BYTES);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Inflates at most {@code max} bytes; more (or a truncated stream) is an error. */
    static byte[] inflateBytes(byte[] in, int max) throws IOException {
        Inflater f = new Inflater();
        try {
            f.setInput(in);
            byte[] raw = new byte[max + 1];
            int n = 0;
            while (!f.finished() && n < raw.length) {
                int k = f.inflate(raw, n, raw.length - n);
                if (k == 0 && (f.needsInput() || f.needsDictionary())) break;
                n += k;
            }
            if (!f.finished() || n > max) throw new IOException("deflate data inflates past " + max + " bytes or is cut");
            return java.util.Arrays.copyOf(raw, n);
        } catch (DataFormatException e) {
            throw new IOException("not deflate data", e);
        } finally {
            f.end();
        }
    }
}
