package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.store.TileCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A tile's art by reference: when a map's inline art does not fit the list
 * job's 256 KiB of params, each spec carries {@code "artRef": {"parts": n}} and its {@code artSha256}, and the art lives
 * in kv namespace {@code ch:mapart} under {@code art/<artSha256>/<k>}, k = 0..n-1, each value {@code {"d": "<part>"}}.
 * The parts are the base64 of the deflated UTF-8 JSON of the art's compact form (TileCodec: palette, plain cells), cut
 * into {@link #PART_CHARS} pieces, so a value stays under the coordinator's 16 KiB. Content-addressed and immutable:
 * written once with compare-and-set 0 by whoever has the art; a {@code cas_failed} means it is there already.
 *
 * <p>Sizes: a real tile is 1 to 4 parts (a 61-colour noise tile, the worst case, about 3).
 */
public final class ArtChunks {
    /** Characters per part (base64), well under the 16 KiB kv value limit with the JSON around it. */
    public static final int PART_CHARS = 12 * 1024;
    /** Parts accepted in a spec (64 x 12 KiB: far above any tile). */
    public static final int MAX_PARTS = 64;
    /** Bytes the parts may inflate to (a tile's compact JSON is under 50 KiB). */
    static final int MAX_JSON_BYTES = 512 * 1024;
    public static final String PREFIX = "art/";

    private ArtChunks() { }

    /** {@code art/<sha256>/<k>}. */
    public static String key(String sha256, int k) {
        return PREFIX + sha256.toLowerCase(java.util.Locale.ROOT) + "/" + k;
    }

    /** The art's parts, in order. */
    public static List<String> parts(TileArt art) {
        TileCodec.Stored s = TileCodec.encode(art);
        JsonObject a = new JsonObject();
        a.addProperty("version", s.version);
        com.google.gson.JsonArray pal = new com.google.gson.JsonArray();
        s.palette.forEach(pal::add);
        a.add("palette", pal);
        a.addProperty("cells", s.cells);
        String all = Base64.getEncoder().encodeToString(
            TileUnitSpec.deflateBytes(a.toString().getBytes(StandardCharsets.UTF_8)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i += PART_CHARS) out.add(all.substring(i, Math.min(all.length(), i + PART_CHARS)));
        return out;
    }

    /** A part's kv value. */
    public static JsonObject value(String part) {
        JsonObject o = new JsonObject();
        o.addProperty("d", part);
        return o;
    }

    /** The part a kv value holds; IOException for anything else (a missing part). */
    static String partOf(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("art part missing");
        String d = Json.s(value.getAsJsonObject(), "d");
        if (d == null) throw new IOException("art part has no data");
        return d;
    }

    /** The art from its parts' values, checked against {@code sha256}. */
    public static TileArt assemble(List<JsonElement> values, String sha256) throws IOException {
        StringBuilder all = new StringBuilder();
        for (JsonElement v : values) all.append(partOf(v));
        byte[] json;
        try {
            json = TileUnitSpec.inflateBytes(Base64.getDecoder().decode(all.toString()), MAX_JSON_BYTES);
        } catch (IllegalArgumentException e) {
            throw new IOException("art parts are not base64");
        }
        TileArt art = TileUnitSpec.artFromJson(new String(json, StandardCharsets.UTF_8));
        if (!TileUnitSpec.sha256(art).equalsIgnoreCase(sha256)) throw new IOException("art parts do not match artSha256");
        return art;
    }
}
