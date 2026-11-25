package io.github.digitalisp.mapart.bot;

import java.util.Optional;

/**
 * Map data received from the server: the plugin's own capture first, ZenithProxy's MapDataCache second
 * ({@code bot.impl.ZenithMapView}). Map ids are session-local on 2b2t (randomised per connection), so never persist
 * them across reconnects: after one, find the bot's map again by its name or its pixels. CONTRACT (lead-owned).
 */
public interface MapView {
    /** 16384 packed colour bytes (colorId << 2 | brightness), index = pz * 128 + px; empty if not received. */
    Optional<byte[]> colors(int mapId);

    boolean isLocked(int mapId);

    /** Map id of the filled map in the main hand, if any. */
    Optional<Integer> heldMapId();

    /** Packed byte the map should show for a block with this map colour id on a flat map (NORMAL brightness). */
    byte expectedPacked(int mapColorId);
}
