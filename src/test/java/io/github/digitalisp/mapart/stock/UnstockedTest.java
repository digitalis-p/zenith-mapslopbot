package io.github.digitalisp.mapart.stock;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UnstockedTest {
    static final String POT = "minecraft:decorated_pot";
    static final String DRIP = "minecraft:pointed_dripstone";
    static final String CANDLE = "minecraft:white_candle";
    static final String WHITE = "minecraft:white_carpet";

    @Test
    void nothingIsUnstockedBeforeTheFirstScan() {
        StockIndex st = new StockIndex();
        assertEquals(Set.of(), st.unstocked(List.of(POT, WHITE)));
        assertFalse(st.everHeld(POT));
    }

    @Test
    void itemsNoContainerEverHeldAreUnstocked() {
        StockIndex st = new StockIndex();
        st.update(new Pos(0, 64, 0), Map.of(WHITE, 640), 20, 27, 1);
        assertEquals(Set.of(CANDLE, DRIP, POT), st.unstocked(List.of(POT, WHITE, DRIP, CANDLE)));
        assertTrue(st.everHeld(WHITE));
    }

    @Test
    void anEmptiedChestStillCountsAsAChestForTheItem() {
        StockIndex st = new StockIndex();
        Pos chest = new Pos(0, 64, 0);
        st.update(chest, Map.of(WHITE, 640), 20, 27, 1);
        st.update(chest, Map.of(), 27, 27, 2); // the bot took everything
        assertEquals(0, st.total(WHITE));
        assertTrue(st.everHeld(WHITE));
        assertEquals(Set.of(), st.unstocked(List.of(WHITE)));
    }

    @Test
    void stockJsonFromBeforeTheSeenSetUsesCurrentSnapshots() {
        Gson gson = new Gson();
        StockIndex old = gson.fromJson("{\"containers\":{\"0,64,0\":{\"items\":{\"" + WHITE + "\":5},"
            + "\"freeSlots\":1,\"totalSlots\":27,\"scannedAt\":1}}}", StockIndex.class);
        assertEquals(Set.of(POT), old.unstocked(List.of(WHITE, POT)));
        old.update(new Pos(1, 64, 0), Map.of(CANDLE, 3), 0, 27, 2);
        StockIndex back = gson.fromJson(gson.toJson(old), StockIndex.class);
        assertEquals(Set.of(POT), back.unstocked(List.of(WHITE, POT, CANDLE)));
        assertTrue(back.seen.contains(CANDLE));
    }

    @Test
    void lineMatchesTheWantedWording() {
        assertEquals("No chest at this base for: decorated_pot, pointed_dripstone, white_candle. "
            + "This job can't be built until these are stocked.", Unstocked.line(List.of(POT, DRIP, CANDLE), 600));
        assertEquals("No chest at this base for: mod:thing. This job can't be built until these are stocked.",
            Unstocked.line(List.of("mod:thing"), 600));
    }

    @Test
    void longLinesEndWithAndMore() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add("minecraft:some_block_" + i);
        String s = Unstocked.line(many, 300);
        assertTrue(s.length() <= 300, s.length() + ": " + s);
        assertTrue(s.startsWith("No chest at this base for: some_block_0, some_block_1"), s);
        assertTrue(s.matches(".* and \\d+ more\\. This job can't be built until these are stocked\\."), s);
    }

    @Test
    void waitingLinesSeparateNoChestFromShort() {
        Map<String, Integer> shortfall = new LinkedHashMap<>();
        shortfall.put(DRIP, 40);
        shortfall.put(WHITE, 120);
        shortfall.put(POT, 4);
        assertEquals("\nminecraft:white_carpet → 120 missing"
                + "\nNo chest at this base for: pointed_dripstone, decorated_pot. This job can't be built until these are stocked.",
            Unstocked.waitingLines(shortfall, Set.of(POT, DRIP)));
        assertEquals("\nminecraft:white_carpet → 120 missing", Unstocked.waitingLines(Map.of(WHITE, 120), Set.of()));
    }
}
