package io.github.digitalisp.mapart.bot.impl;

import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapData;
import org.geysermc.mcprotocollib.protocol.data.game.level.map.MapIcon;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundMapItemDataPacket;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's own map capture (2b2t, 2026-10-11: ZenithProxy's full map cache kept none of the 122 packets for the
 * bot's map 990 and CHECK_MAP went BLOCKED). Patches as ZenithProxy's StoredMapData.addData applies them; complete
 * pictures only; least recently used out, carried maps never; nothing survives a reset (map ids are per connection).
 */
class MapStoreTest {
    private static byte[] filled(int value) {
        byte[] b = new byte[MapStore.PIXELS];
        Arrays.fill(b, (byte) value);
        return b;
    }

    private static MapStore.Patch full(int value) {
        return new MapStore.Patch(0, 0, 128, 128, filled(value));
    }

    private static MapStore store(int capacity, Set<Integer> pinned) {
        return new MapStore(capacity, () -> pinned);
    }

    @Test
    void aPartialPatchChangesOnlyItsRectangle() {
        MapStore s = store(8, Set.of());
        s.apply(990, false, full(4));
        // 3 columns x 2 rows at (10, 20), row-major like the packet
        s.apply(990, false, new MapStore.Patch(10, 20, 3, 2, new byte[]{1, 2, 3, 5, 6, 7}));
        byte[] px = s.colors(990).orElseThrow();
        assertEquals(1, px[20 * 128 + 10]);
        assertEquals(2, px[20 * 128 + 11]);
        assertEquals(3, px[20 * 128 + 12]);
        assertEquals(5, px[21 * 128 + 10]);
        assertEquals(7, px[21 * 128 + 12]);
        assertEquals(4, px[20 * 128 + 13], "right of the patch unchanged");
        assertEquals(4, px[22 * 128 + 10], "below the patch unchanged");
        assertEquals(4, px[19 * 128 + 10], "above the patch unchanged");
        int changed = 0;
        for (byte b : px) if (b != 4) changed++;
        assertEquals(6, changed);
    }

    @Test
    void packetsAreAppliedLikeZenithProxysStoredMapData() {
        MapStore s = store(8, Set.of());
        s.accept(new ClientboundMapItemDataPacket(7, (byte) 0, false, new MapIcon[0], new MapData(128, 128, 0, 0, filled(9))));
        s.accept(new ClientboundMapItemDataPacket(7, (byte) 0, false, new MapIcon[0],
            new MapData(2, 1, 126, 127, new byte[]{33, 34})));
        // only decorations: no pixels, the locked flag still counts
        s.accept(new ClientboundMapItemDataPacket(7, (byte) 0, true, new MapIcon[0]));
        byte[] px = s.colors(7).orElseThrow();
        assertEquals(33, px[127 * 128 + 126]);
        assertEquals(34, px[127 * 128 + 127]);
        assertEquals(9, px[127 * 128 + 125]);
        assertEquals(true, s.locked(7).orElseThrow(), "the latest packet's locked flag");
    }

    @Test
    void aMapWhoseFirstPacketWasMissedIsNotShownHalfEmpty() {
        MapStore s = store(8, Set.of());
        s.apply(5, false, new MapStore.Patch(0, 0, 128, 64, filled(8)));
        assertTrue(s.colors(5).isEmpty(), "half the pixels unknown: no picture (the view falls back to ZenithProxy)");
        assertEquals(false, s.locked(5).orElseThrow(), "the locked flag is known from any packet");
        assertTrue(s.has(5));
        s.apply(5, false, new MapStore.Patch(0, 64, 128, 64, filled(8)));
        assertArrayEquals(filled(8), s.colors(5).orElseThrow(), "complete once every pixel arrived");
    }

    @Test
    void bytesOutsideTheMapAreIgnored() {
        MapStore s = store(8, Set.of());
        s.apply(1, false, full(0));
        s.apply(1, false, new MapStore.Patch(126, -1, 4, 2, new byte[]{1, 1, 1, 1, 2, 3, 4, 5}));
        s.apply(1, false, new MapStore.Patch(0, 0, 4, 4, new byte[]{6})); // fewer bytes than the rectangle
        byte[] px = s.colors(1).orElseThrow();
        assertEquals(2, px[126]);
        assertEquals(3, px[127]);
        assertEquals(6, px[0]);
        assertEquals(0, px[1]);
        int set = 0;
        for (byte b : px) if (b != 0) set++;
        assertEquals(3, set, "row -1 and columns 128+ dropped");
    }

    @Test
    void colorsIsACopy() {
        MapStore s = store(8, Set.of());
        s.apply(1, false, full(3));
        s.colors(1).orElseThrow()[0] = 99;
        assertEquals(3, s.colors(1).orElseThrow()[0]);
    }

    @Test
    void theLeastRecentlyUsedMapGoesFirst() {
        MapStore s = store(3, Set.of());
        s.apply(1, false, full(1));
        s.apply(2, false, full(2));
        s.apply(3, false, full(3));
        s.colors(1); // a read counts as a use
        s.apply(4, false, full(4));
        assertEquals(3, s.size());
        assertFalse(s.has(2), "2 was used least recently");
        assertTrue(s.has(1) && s.has(3) && s.has(4));
        s.apply(3, false, null); // a packet counts as a use too
        s.apply(5, false, full(5));
        assertFalse(s.has(1));
        assertTrue(s.has(3) && s.has(4) && s.has(5));
    }

    @Test
    void framedMapsAtLoginNeverPushOutTheMapTheBotCarries() {
        Set<Integer> carried = new HashSet<>(Set.of(990));
        MapStore s = store(MapStore.CAPACITY, carried);
        s.apply(990, false, full(7)); // the bot's map: its whole picture first
        for (int id = 0; id < 600; id++) if (id != 990) s.apply(id, true, full(1)); // hundreds of framed maps
        assertEquals(MapStore.CAPACITY, s.size(), "capped");
        assertArrayEquals(filled(7), s.colors(990).orElseThrow(), "the carried map kept its picture");
        assertTrue(s.has(599), "the newest framed map is kept");
        assertTrue(s.summary().contains("evicted"), s.summary());
    }

    @Test
    void moreCarriedMapsThanRoomKeepsThemAllAndTheNewOne() {
        MapStore s = store(2, Set.of(1, 2, 3));
        s.apply(1, false, full(1));
        s.apply(2, false, full(2));
        s.apply(3, false, full(3));
        s.apply(4, false, full(4));
        assertTrue(s.has(1) && s.has(2) && s.has(3) && s.has(4), "nothing that may go: over capacity for now");
        s.apply(5, false, full(5));
        assertFalse(s.has(4), "the unpinned one goes when another arrives");
        assertTrue(s.has(5));
    }

    @Test
    void anInventoryThatCannotBeReadPinsNothing() {
        MapStore s = new MapStore(1, () -> {
            throw new IllegalStateException("no inventory yet");
        });
        s.apply(1, false, full(1));
        s.apply(2, false, full(2));
        assertEquals(1, s.size());
        assertTrue(s.has(2));
    }

    @Test
    void aResetForgetsEveryMapIdsArePerConnection() {
        MapStore s = store(8, Set.of(990));
        s.apply(990, false, full(7));
        s.apply(870, true, full(7));
        s.reset(); // disconnect / next login
        assertEquals(0, s.size());
        assertTrue(s.colors(990).isEmpty(), "the old session's map 990 is not the new one's");
        assertTrue(s.locked(870).isEmpty());
        // the new connection's id 990 is some other map: only its own packets count
        s.apply(990, true, new MapStore.Patch(0, 0, 1, 1, new byte[]{1}));
        assertTrue(s.colors(990).isEmpty());
        assertEquals(true, s.locked(990).orElseThrow());
    }
}
