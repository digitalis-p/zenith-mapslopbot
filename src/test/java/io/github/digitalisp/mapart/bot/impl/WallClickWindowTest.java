package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import static org.junit.jupiter.api.Assertions.*;

class WallClickWindowTest {
    @Test
    void anchorIsTheFirstActualSendAndTheTenthWaitsForTheBoundary() {
        WallClickWindow w = new WallClickWindow();
        long start = 123_000_000L;
        assertTrue(w.allows(start, 310, 9));
        for (int i = 0; i < 9; i++) {
            long time = start + i * 10_000_000L;
            assertTrue(w.allows(time, 310, 9));
            w.sent(time, 310);
        }
        assertEquals(start + 310_000_000L, w.opensAt(310));
        assertFalse(w.allows(start + 309_999_999L, 310, 9));
        assertTrue(w.allows(start + 310_000_000L, 310, 9));
        // Merely checking an expired window does not open it.
        w.allows(start + 500_000_000L, 310, 9);
        w.sent(start + 600_000_000L, 310);
        assertEquals(start + 910_000_000L, w.opensAt(310));
    }

    @Test
    void paperShapeWithSparseClicksAndNanoTimeWrap() {
        WallClickWindow w = new WallClickWindow();
        long start = Long.MAX_VALUE - 100_000_000L;
        for (int i = 0; i < 9; i++) w.sent(start + i * 20_000_000L, 300);
        assertFalse(w.allows(start + 299_999_999L, 300, 9));
        assertTrue(w.allows(start + 300_000_000L, 300, 9));
        w.sent(start + 300_000_000L, 300);
        assertTrue(w.allows(start + 301_000_000L, 300, 9));
    }

    @Test
    void fixedMillisIgnoresTickLearningAndSurvivesLaneReset() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.placeClicksPerTick = 2;
        b.placeSubTickDispatch = true;
        AdaptivePacer p = new AdaptivePacer(1, 1, 4);
        p.configure(PlacePacing.of(b));
        for (int i = 0; i < 9; i++) p.wallWindow().sent(0, 310);
        for (int i = 0; i < 200; i++) {
            assertNull(p.clickWindow().outcome(PlaceTracker.Outcome.CONFIRMED, true));
            assertNull(p.clickWindow().outcome(PlaceTracker.Outcome.DROPPED, true));
        }
        p.newRun();
        p.configure(PlacePacing.of(b));
        assertFalse(p.wallWindow().allows(309_000_000L, 310, 9));
        assertEquals(7, p.clickWindow().ticks());
        b.placeSubTickDispatch = false;
        p.configure(PlacePacing.of(b));
        for (int i = 0; i < 60; i++) p.clickWindow().outcome(PlaceTracker.Outcome.CONFIRMED, true);
        assertEquals(6, p.clickWindow().ticks());
    }
    @Test
    void rollingHistorySurvivesLaneResetAndDoesNotSpendInventoryPackets() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 4);
        WallClickWindow w = p.wallWindow();
        w.sent(0, 310);
        for (int i = 0; i < 8; i++) w.sent(305_000_000L, 310);
        // Paper restarted at 305 ms; our anchored window can reset at 310 ms and mistakenly allow nine more.
        p.newRun();
        assertTrue(w.allows(310_000_000L, 310, 9));
        w.sent(310_000_000L, 310);
        assertTrue(w.allows(311_000_000L, 310, 9), "anchored capacity alone still admits the burst");
        assertEquals(615_000_000L, p.wallWindow().rollingOpensAt(311_000_000L, 310, 9),
            "a new lane shares the previous lane's sends, and waits for Paper's shifted window");
        assertEquals(Long.MIN_VALUE, w.rollingOpensAt(615_000_000L, 310, 9));
        // No sent() for an inventory packet: only real use-item-on packets advance either budget.
        assertEquals(Long.MIN_VALUE, w.rollingOpensAt(616_000_000L, 310, 9));
    }

    @Test
    void millisecondSettingDoesNotResetLearningWhileDispatchIsOff() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.placeClicksPerTick = 2;
        AdaptivePacer p = new AdaptivePacer(1, 1, 4);
        p.configure(PlacePacing.of(b));
        for (int i = 0; i < 60; i++) p.clickWindow().outcome(PlaceTracker.Outcome.CONFIRMED, true);
        assertEquals(6, p.clickWindow().ticks());
        b.placeClickWindowMillis = 350;
        p.configure(PlacePacing.of(b));
        assertEquals(6, p.clickWindow().ticks(), "unused millisecond setting is inert");
    }

}
