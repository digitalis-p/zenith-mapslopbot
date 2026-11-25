package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A material container's contents as one bot saw them when it had the container open: the shared stock index entry
 * {@code stock/<x,y,z>} in kv namespace {@code ch:mapart}, written with compare-and-set by whoever opened the container
 * ({@link SharedStock}).
 *
 * <p>JSON shape (at most 16 KiB; a 54-slot chest of distinct items is about 2 KiB):
 * <pre>
 * {"items": {"minecraft:white_carpet": 1728}, "free": 27, "total": 54, "at": 1761300000000}
 * </pre>
 *
 * @param container the container's canonical block
 * @param items     item id → count, in the order seen
 * @param freeSlots empty slots
 * @param totalSlots the container's own slots
 * @param scannedAt epoch millis the bot read it (a newer reading always wins over an older one)
 */
public record StockEntry(Pos container, Map<String, Integer> items, int freeSlots, int totalSlots, long scannedAt) {
    public static final String PREFIX = "stock/";

    public StockEntry {
        items = items == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    /** {@code stock/<x,y,z>}. */
    public static String key(Pos container) {
        return PREFIX + container.key();
    }

    /** The container of a {@code stock/<x,y,z>} key, or null for another key. */
    public static @Nullable Pos containerOf(String key) {
        if (key == null || !key.startsWith(PREFIX)) return null;
        try {
            return Pos.parseKey(key.substring(PREFIX.length()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonObject it = new JsonObject();
        items.forEach(it::addProperty);
        o.add("items", it);
        o.addProperty("free", freeSlots);
        o.addProperty("total", totalSlots);
        o.addProperty("at", scannedAt);
        return o;
    }

    /** Reads {@link #toJson}'s shape for {@code container}; null for a missing or deleted value. */
    public static @Nullable StockEntry fromJson(Pos container, @Nullable JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        Map<String, Integer> items = new LinkedHashMap<>();
        JsonObject it = Json.obj(o, "items");
        if (it != null) {
            for (var en : it.entrySet()) {
                int n = Json.i(it, en.getKey());
                if (n > 0) items.put(en.getKey(), n);
            }
        }
        return new StockEntry(container, items, Json.i(o, "free"), Json.i(o, "total"), Json.l(o, "at"));
    }
}
