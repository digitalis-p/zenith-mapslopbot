package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.map.StoredMapData;
import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapIcon;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map view reads the plugin's own capture first and ZenithProxy's MapDataCache only when that has no picture. */
class ZenithMapViewTest {
    private final MapStore own = new MapStore(16, Set::of);
    /** ZenithProxy's cache; empty = full (it skipped every new id), as on 2b2t on 2026-10-11. */
    private final Map<Integer, StoredMapData> zenith = new HashMap<>();
    private Integer held;
    private final ZenithMapView view = new ZenithMapView(own, zenith::get, () -> held);

    private static byte[] filled(int value) {
        byte[] b = new byte[MapStore.PIXELS];
        Arrays.fill(b, (byte) value);
        return b;
    }

    private void zenithHas(int id, boolean locked, int value) {
        zenith.put(id, new StoredMapData(id, (byte) 0, locked, new MapIcon[0], filled(value)));
    }

    @Test
    void theOwnStoreIsReadFirst() {
        own.apply(990, false, new MapStore.Patch(0, 0, 128, 128, filled(5)));
        zenithHas(990, false, 9);
        assertEquals(5, view.colors(990).orElseThrow()[0]);
    }

    @Test
    void zenithProxysFullCacheDoesNotMatterWhenTheOwnStoreHasTheMap() {
        own.apply(990, false, new MapStore.Patch(0, 0, 128, 128, filled(5)));
        own.apply(990, false, new MapStore.Patch(3, 4, 1, 1, new byte[]{7}));
        byte[] px = view.colors(990).orElseThrow();
        assertEquals(7, px[4 * 128 + 3]);
        assertFalse(view.isLocked(990));
    }

    @Test
    void zenithProxysCacheIsTheFallback() {
        zenithHas(12, true, 9);
        assertEquals(9, view.colors(12).orElseThrow()[100]);
        assertTrue(view.isLocked(12));
        // an own picture still missing pixels (first packet before the module was on) does not hide ZenithProxy's
        own.apply(12, true, new MapStore.Patch(0, 0, 4, 4, new byte[16]));
        assertEquals(9, view.colors(12).orElseThrow()[100]);
    }

    @Test
    void noDataAnywhereIsEmpty() {
        assertEquals(Optional.empty(), view.colors(990));
        assertFalse(view.isLocked(990));
    }

    @Test
    void theOwnLockedFlagWins() {
        zenithHas(3, false, 1);
        own.apply(3, true, null); // a later packet: locked (ZenithProxy's copy would say the same, here it is stale)
        assertTrue(view.isLocked(3));
    }

    @Test
    void theResultIsACopy() {
        zenithHas(4, false, 2);
        view.colors(4).orElseThrow()[0] = 99;
        assertEquals(2, view.colors(4).orElseThrow()[0]);
    }

    @Test
    void theHeldMapComesFromTheMainHand() {
        assertTrue(view.heldMapId().isEmpty());
        held = 0;
        assertEquals(Optional.of(0), view.heldMapId());
    }

    @Test
    void expectedPixelsAreFlatNormalShading() {
        assertEquals((byte) (61 << 2 | 1), view.expectedPacked(61));
    }
}
