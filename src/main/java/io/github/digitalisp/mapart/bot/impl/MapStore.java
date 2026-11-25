package io.github.digitalisp.mapart.bot.impl;

import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapData;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundMapItemDataPacket;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The plugin's own copy of the map data the server sends ({@code ClientboundMapItemDataPacket}), kept apart from
 * ZenithProxy's {@code MapDataCache}. That cache skips every NEW map id once it holds more than
 * {@code debug.server.cache.maxCachedMaps} (default 128), and the framed maps of a big base fill it at login: on 2b2t
 * (2026-10-11) the server sent 122 packets for the bot's map 990, ZenithProxy kept none, and CHECK_MAP went BLOCKED with
 * "no map data received". {@link ZenithMapView} reads this store first and ZenithProxy's cache second.
 *
 * <ul>
 *   <li><b>Patches</b> are applied like ZenithProxy's {@code StoredMapData.addData}: {@code columns × rows} bytes at
 *       {@code (x, y)}, row-major; bytes outside the 128 × 128 map are ignored. The locked flag of the latest packet
 *       wins.</li>
 *   <li><b>Complete only.</b> {@link #colors} answers once every pixel was covered by some patch. The server sends the
 *       whole map in the first packet to a new holder (vanilla {@code MapItemSavedData.HoldingPlayer} starts with the
 *       whole map dirty) and only changed pixels after that, so an entry whose first packet was missed (the module was
 *       off, an eviction) stays incomplete and the view falls back to ZenithProxy's cache instead of showing zeros.</li>
 *   <li><b>Capacity:</b> at most {@link #CAPACITY} entries, least recently used (applied or read) first out. Ids the bot
 *       carries ({@code pinned}, asked only when something must go) and the entry just added are never evicted, so the
 *       hundreds of framed maps sent at login cannot push out the bot's own map.</li>
 *   <li><b>Session-local:</b> 2b2t map ids are per connection (the bot's held map was 990, then 0 after a reconnect).
 *       The module {@link #reset}s the store on every disconnect, on every login packet (2b2t's queue → main server
 *       transfer is a new login too) and when it is switched on or off (packets missed meanwhile).</li>
 * </ul>
 *
 * <p>Threads: the module applies packets on the client event loop (where {@code pinned} may read the inventory cache)
 * and the engine reads on the client tick thread; every method is synchronized.
 */
public final class MapStore {
    /** Map side in pixels. */
    public static final int SIDE = 128;
    public static final int PIXELS = SIDE * SIDE;
    /** Entries kept; about 18 KB each (pixels plus the coverage bits until complete). */
    public static final int CAPACITY = 64;

    /** The store the module feeds and {@link ZenithMapView} reads; pinned = the filled maps in the hotbar and main inventory. */
    public static final MapStore SESSION = new MapStore(CAPACITY, Inv::mapIds);

    /** One map data patch: {@code data} holds {@code columns × rows} bytes, row-major, for the rectangle at (x, y). */
    public record Patch(int x, int y, int columns, int rows, byte[] data) { }

    private static final class Entry {
        final byte[] colors = new byte[PIXELS];
        /** Pixels covered so far; null once complete. */
        @Nullable BitSet seen = new BitSet(PIXELS);
        int missing = PIXELS;
        boolean locked;

        void add(Patch p) {
            byte[] src = p.data();
            if (src == null) return;
            for (int j = 0; j < p.rows(); j++) {
                int pz = p.y() + j;
                if (pz < 0 || pz >= SIDE) continue;
                for (int i = 0; i < p.columns(); i++) {
                    int px = p.x() + i;
                    int s = i + j * p.columns();
                    if (px < 0 || px >= SIDE || s >= src.length) continue;
                    int at = px + pz * SIDE;
                    colors[at] = src[s];
                    if (seen != null && !seen.get(at)) {
                        seen.set(at);
                        if (--missing == 0) seen = null;
                    }
                }
            }
        }

        boolean complete() {
            return missing == 0;
        }
    }

    private final int capacity;
    private final Supplier<? extends Collection<Integer>> pinned;
    /** Access order: the eldest entry is the least recently applied or read. */
    private final LinkedHashMap<Integer, Entry> maps = new LinkedHashMap<>(16, 0.75f, true);
    private long applied;
    private long evicted;

    public MapStore(int capacity, Supplier<? extends Collection<Integer>> pinned) {
        this.capacity = Math.max(1, capacity);
        this.pinned = pinned;
    }

    /** One map data packet: its locked flag and, when present, its pixel patch. */
    public void accept(ClientboundMapItemDataPacket packet) {
        MapData d = packet.getData();
        apply(packet.getMapId(), packet.isLocked(),
            d == null ? null : new Patch(d.getX(), d.getY(), d.getColumns(), d.getRows(), d.getData()));
    }

    public synchronized void apply(int mapId, boolean locked, @Nullable Patch patch) {
        applied++;
        Entry e = maps.get(mapId);
        if (e == null) {
            e = new Entry();
            maps.put(mapId, e);
            evictOver(mapId);
        }
        e.locked = locked;
        if (patch != null) e.add(patch);
    }

    /** A copy of the map's 16384 packed colour bytes (index = pz * 128 + px), once every pixel arrived. */
    public synchronized Optional<byte[]> colors(int mapId) {
        Entry e = maps.get(mapId);
        if (e == null || !e.complete()) return Optional.empty();
        return Optional.of(e.colors.clone());
    }

    /** The locked flag of the map's latest packet; empty if no packet for it arrived (or it was evicted). */
    public synchronized Optional<Boolean> locked(int mapId) {
        Entry e = maps.get(mapId);
        return e == null ? Optional.empty() : Optional.of(e.locked);
    }

    /** Whether any packet for this map is held (complete or not). Does not count as a use. */
    public synchronized boolean has(int mapId) {
        return maps.containsKey(mapId);
    }

    public synchronized int size() {
        return maps.size();
    }

    /** Forgets every map: a new connection (ids are session-local) or a gap in the capture. */
    public synchronized void reset() {
        maps.clear();
    }

    /** "12 maps (10 complete), 3456 packets, 0 evicted" for status and logs. */
    public synchronized String summary() {
        int complete = 0;
        for (Entry e : maps.values()) if (e.complete()) complete++;
        return maps.size() + " maps (" + complete + " complete), " + applied + " packets, " + evicted + " evicted";
    }

    /** Over capacity: drops the least recently used entries that are neither pinned nor {@code keep}. */
    private void evictOver(int keep) {
        if (maps.size() <= capacity) return;
        Collection<Integer> pins = pins();
        Iterator<Map.Entry<Integer, Entry>> it = maps.entrySet().iterator();
        while (maps.size() > capacity && it.hasNext()) {
            int id = it.next().getKey();
            if (id == keep || pins.contains(id)) continue;
            it.remove();
            evicted++;
        }
    }

    private Collection<Integer> pins() {
        try {
            Collection<Integer> p = pinned.get();
            return p == null ? List.of() : p;
        } catch (RuntimeException e) {
            return List.of(); // no inventory yet: nothing pinned
        }
    }
}
