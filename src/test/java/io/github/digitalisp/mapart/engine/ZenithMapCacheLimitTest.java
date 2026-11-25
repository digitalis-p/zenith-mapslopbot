package io.github.digitalisp.mapart.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugin load raises ZenithProxy's {@code debug maxCachedMaps} (default 128): when full it skips new map ids, and on
 * 2b2t the base's framed maps filled it so it kept nothing of the bot's map. Never touches ZenithProxy's globals here.
 */
class ZenithMapCacheLimitTest {
    private int limit;
    private final List<String> logged = new ArrayList<>();

    private boolean apply(boolean enabled) {
        return ZenithMapCacheLimit.apply(enabled, () -> limit, v -> limit = v, logged::add);
    }

    @Test
    void zenithProxysDefaultIsRaisedTo4096AndLoggedOnce() {
        limit = 128;
        assertTrue(apply(true));
        assertEquals(4096, limit);
        assertEquals(1, logged.size());
        assertTrue(logged.getFirst().contains("from 128 to 4096"), logged.getFirst());
        assertTrue(logged.getFirst().contains("map.raiseZenithMapCache"), logged.getFirst());
        assertFalse(apply(true), "a second load leaves it");
        assertEquals(1, logged.size());
    }

    @Test
    void aLimitOf2048OrMoreIsLeftAlone() {
        for (int v : new int[]{2048, 3000, 4096, 10_000}) {
            limit = v;
            assertFalse(apply(true));
            assertEquals(v, limit);
        }
        limit = 2047;
        assertTrue(apply(true));
        assertEquals(4096, limit);
    }

    @Test
    void theToggleOffLeavesItAlone() {
        limit = 128;
        assertFalse(apply(false));
        assertEquals(128, limit);
        assertTrue(logged.isEmpty());
        assertEquals(OptionalInt.empty(), ZenithMapCacheLimit.raisedLimit(false, 0));
        assertEquals(OptionalInt.of(4096), ZenithMapCacheLimit.raisedLimit(true, 0));
    }

    @Test
    void theToggleIsOnByDefaultAlsoInAnOlderConfig() {
        assertTrue(new MapArtConfig().map.raiseZenithMapCache);
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        MapArtConfig old = gson.fromJson("{ \"map\": { \"holdTicks\": 80 } }", MapArtConfig.class);
        assertEquals(80, old.map.holdTicks);
        assertTrue(old.map.raiseZenithMapCache, "a mapart.json saved before the toggle existed");
        MapArtConfig off = gson.fromJson("{ \"map\": { \"raiseZenithMapCache\": false } }", MapArtConfig.class);
        assertFalse(off.map.raiseZenithMapCache);
    }
}
