package io.github.digitalisp.mapart.bot.impl;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.digitalisp.mapart.bot.impl.PlaceTracker.Outcome.*;

class AdaptiveClickWindowTest {
    private static MapArtConfig.BuildCfg config() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.placeClicksPerTick = 2;
        return b;
    }

    private static AdaptiveClickWindow window(MapArtConfig.BuildCfg b) {
        AdaptiveClickWindow w = new AdaptiveClickWindow();
        w.configure(PlacePacing.of(b));
        return w;
    }

    @Test
    void defaultsAndOldConfigKeepSevenAndOneClick() {
        MapArtConfig.BuildCfg b = new Gson().fromJson("{}", MapArtConfig.BuildCfg.class);
        assertTrue(b.placeAdaptiveWindow);
        assertEquals(7, b.placeClickWindowTicks);
        assertEquals(6, b.placeClickWindowTicksMin);
        assertEquals(60, b.placeClickWindowCleanClicks);
        assertEquals(0.02, b.placeDropRateMax);
        assertEquals(3, b.placeLatchFails);
        assertEquals(20, b.placeLatchWindow);
        assertEquals(PlacePacing.DEFAULT, PlacePacing.of(b));
        AdaptiveClickWindow w = window(b);
        for (int i = 0; i < 200; i++) assertNull(w.outcome(CONFIRMED, true));
        assertEquals(7, w.ticks());
    }

    @Test
    void killSwitchKeepsAFixedWindow() {
        MapArtConfig.BuildCfg b = config();
        b.placeAdaptiveWindow = false;
        AdaptiveClickWindow w = window(b);
        for (int i = 0; i < 200; i++) {
            assertNull(w.outcome(CONFIRMED, true));
            assertNull(w.outcome(DROPPED, true));
        }
        assertEquals(7, w.ticks());
    }

    @Test
    void onlyCleanSharedClicksTightenAndEachChangeLogsOnce() {
        AdaptiveClickWindow w = window(config());
        for (int i = 0; i < 100; i++) assertNull(w.outcome(CONFIRMED, false));
        for (int i = 0; i < 59; i++) assertNull(w.outcome(CONFIRMED, true));
        assertEquals(7, w.ticks());
        assertEquals("click window 9 per 6 t (drops 0.0 % of the last 100)", w.outcome(CONFIRMED, true));
        for (int i = 0; i < 200; i++) assertNull(w.outcome(CONFIRMED, true));
        assertEquals(6, w.ticks());
    }

    @Test
    void failedSharedClicksDoNotCountAsClean() {
        for (PlaceTracker.Outcome failure : new PlaceTracker.Outcome[]{DROPPED, REJECTED, STACK_END, UNKNOWN}) {
            AdaptiveClickWindow w = window(config());
            for (int i = 0; i < 59; i++) w.outcome(CONFIRMED, true);
            assertNull(w.outcome(failure, true));
            assertEquals(7, w.ticks());
            assertNotNull(w.outcome(CONFIRMED, true));
        }
    }

    @Test
    void dropsOverLastHundredWidenOneTickAndCapAtBasePlusOne() {
        AdaptiveClickWindow w = window(config());
        for (int i = 0; i < 60; i++) w.outcome(CONFIRMED, true);
        assertEquals(6, w.ticks());
        // Complete the sample, then exceed 2%. The first widening reacts immediately.
        for (int i = 0; i < 40; i++) assertNull(w.outcome(CONFIRMED, false));
        assertNull(w.outcome(DROPPED, false));
        assertNull(w.outcome(DROPPED, false));
        assertEquals("click window 9 per 7 t (drops 3.0 % of the last 100)", w.outcome(DROPPED, false));
        for (int i = 0; i < 99; i++) assertNull(w.outcome(DROPPED, false));
        assertNotNull(w.outcome(DROPPED, false));
        assertEquals(8, w.ticks());
        for (int i = 0; i < 200; i++) assertNull(w.outcome(DROPPED, true));
        assertEquals(8, w.ticks());
        for (int i = 0; i < 100; i++) w.outcome(CONFIRMED, false);
        assertEquals(0, w.dropRate());
        for (int i = 0; i < 60; i++) w.outcome(CONFIRMED, true);
        assertEquals(7, w.ticks());
        for (int i = 0; i < 60; i++) w.outcome(CONFIRMED, true);
        assertEquals(6, w.ticks());
    }

    @Test
    void exactlyTwoPercentDoesNotWidenAndRefusalsAreNotDrops() {
        AdaptiveClickWindow w = window(config());
        for (int i = 0; i < 100; i++) assertNull(w.outcome(i < 2 ? DROPPED : REJECTED, false));
        assertEquals(7, w.ticks());
        assertEquals(0.02, w.dropRate());
    }

    @Test
    void learnedWindowAndLatchSurviveNewLanesButSettingsResetWindow() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 4);
        MapArtConfig.BuildCfg b = config();
        b.placeLatchFails = 2;
        b.placeLatchWindow = 8;
        p.configure(PlacePacing.of(b));
        for (int i = 0; i < 60; i++) p.clickWindow().outcome(CONFIRMED, true);
        assertFalse(p.sharedOutcome(true));
        p.newRun();
        p.configure(PlacePacing.of(b));
        assertEquals(6, p.clickWindow().ticks());
        assertTrue(p.sharedOutcome(true));
        assertFalse(p.fastPlacementOn());
        b.placeClickWindowTicks = 8;
        p.configure(PlacePacing.of(b));
        assertEquals(8, p.clickWindow().ticks());
        assertFalse(p.fastPlacementOn());
    }

    @Test
    void latchUsesOnlyTheConfiguredRecentSharedOutcomes() {
        AdaptivePacer p = new AdaptivePacer(1, 1, 4);
        MapArtConfig.BuildCfg b = config();
        b.placeLatchWindow = 4;
        b.placeLatchFails = 2;
        p.configure(PlacePacing.of(b));
        assertFalse(p.sharedOutcome(true));
        for (int i = 0; i < 4; i++) assertFalse(p.sharedOutcome(false));
        assertFalse(p.sharedOutcome(true));
        assertTrue(p.sharedOutcome(true));
    }
}
