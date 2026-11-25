package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

/**
 * One shared fact in the plugin's kv namespace {@code ch:mapart} ({@link Coordination#kvGet}). Versions count every
 * write of the key, deletes included; 0 = never written. A {@code kvPut} with an expected version is compare-and-set:
 * 0 = the key must not exist (a deleted key counts as not existing).
 *
 * @param key     e.g. {@code stock/-1200,64,880} ({@link StockEntry#key})
 * @param value   the JSON value; null = deleted or never written
 * @param version the key's version
 */
public record KvEntry(String key, @Nullable JsonElement value, long version) { }
