package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.map.StoredMapData;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.MapView;

import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static com.zenith.Globals.CACHE;

/**
 * {@link MapView} over the plugin's own {@link MapStore} first and ZenithProxy's MapDataCache second. ZenithProxy's
 * cache skips new map ids once it holds {@code debug.server.cache.maxCachedMaps} maps, so it can miss the bot's map
 * entirely; the own store captures every map packet while the module is on. The fallback covers maps whose first
 * packet arrived while the module was off. Map ids are session-local on 2b2t: both sources are cleared per connection.
 */
public final class ZenithMapView implements MapView {
    /** Brightness id of a flat map pixel (vanilla MapColor.Brightness.NORMAL). */
    public static final int NORMAL = 1;

    private final MapStore own;
    private final IntFunction<@Nullable StoredMapData> zenith;
    private final Supplier<@Nullable Integer> held;

    /** Production: {@link MapStore#SESSION}, ZenithProxy's cache and the main-hand map. */
    public ZenithMapView() {
        this(MapStore.SESSION, id -> CACHE.getMapDataCache().getMapDataMap().get(id), () -> Inv.mapId(Inv.mainHand()));
    }

    /**
     * @param own    the plugin's own capture, read first
     * @param zenith ZenithProxy's cache lookup (null = no entry), read when the own store has no complete picture
     * @param held   the map id in the main hand, or null
     */
    public ZenithMapView(MapStore own, IntFunction<@Nullable StoredMapData> zenith, Supplier<@Nullable Integer> held) {
        this.own = own;
        this.zenith = zenith;
        this.held = held;
    }

    @Override
    public Optional<byte[]> colors(int mapId) {
        Optional<byte[]> mine = own.colors(mapId);
        if (mine.isPresent()) return mine;
        StoredMapData d = zenith.apply(mapId);
        if (d == null || d.getData() == null) return Optional.empty();
        return Optional.of(d.getData().clone());
    }

    @Override
    public boolean isLocked(int mapId) {
        Optional<Boolean> mine = own.locked(mapId);
        if (mine.isPresent()) return mine.get();
        StoredMapData d = zenith.apply(mapId);
        return d != null && d.isLocked();
    }

    @Override
    public Optional<Integer> heldMapId() {
        return Optional.ofNullable(held.get());
    }

    @Override
    public byte expectedPacked(int mapColorId) {
        return packed(mapColorId);
    }

    /** colorId << 2 | NORMAL. Pure. */
    public static byte packed(int mapColorId) {
        return (byte) ((mapColorId << 2) | NORMAL);
    }
}
