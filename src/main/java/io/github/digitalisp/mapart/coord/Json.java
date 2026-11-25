package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/** Lenient reads of the coordination JSON shapes: a missing, null or mistyped field gives the default. */
final class Json {
    private Json() { }

    static @Nullable JsonElement get(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e;
    }

    static int i(JsonObject o, String k) {
        JsonElement e = get(o, k);
        try {
            return e == null || !e.isJsonPrimitive() ? 0 : e.getAsInt();
        } catch (RuntimeException x) {
            return 0;
        }
    }

    static long l(JsonObject o, String k) {
        JsonElement e = get(o, k);
        try {
            return e == null || !e.isJsonPrimitive() ? 0 : e.getAsLong();
        } catch (RuntimeException x) {
            return 0;
        }
    }

    static boolean b(JsonObject o, String k) {
        JsonElement e = get(o, k);
        try {
            return e != null && e.isJsonPrimitive() && e.getAsBoolean();
        } catch (RuntimeException x) {
            return false;
        }
    }

    static @Nullable String s(JsonObject o, String k) {
        JsonElement e = get(o, k);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    static @Nullable JsonObject obj(JsonObject o, String k) {
        JsonElement e = get(o, k);
        return e == null || !e.isJsonObject() ? null : e.getAsJsonObject();
    }
}
