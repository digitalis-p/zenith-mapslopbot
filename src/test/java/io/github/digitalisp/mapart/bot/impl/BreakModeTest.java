package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakModeTest {
    @Test
    void parsesTheBreakModeCaseInsensitively() {
        assertEquals(BreakMode.FAST, BreakMode.of("FAST"));
        assertEquals(BreakMode.FAST, BreakMode.of(" fast "));
        assertEquals(BreakMode.DOUBLE, BreakMode.of("DOUBLE"));
        assertEquals(BreakMode.DOUBLE, BreakMode.of("double"));
        assertEquals(BreakMode.DOUBLE, BreakMode.of(" DOUBLE "));
        assertEquals(BreakMode.DOUBLE, BreakMode.of("doublebreak"));
        assertEquals(BreakMode.SINGLE, BreakMode.of("SINGLE"));
        assertEquals(BreakMode.SINGLE, BreakMode.of("single"));
        assertEquals(BreakMode.SINGLE, BreakMode.of(" Single "));
        assertEquals(BreakMode.SINGLE, BreakMode.of("vanilla"));
        assertEquals(BreakMode.SINGLE, BreakMode.of("VANILLA"));
        assertEquals(BreakMode.SINGLE, BreakMode.of("off"));
        assertEquals(BreakMode.SINGLE, BreakMode.of("Off"));
    }

    @Test
    void unknownOrMissingModeIsFast() {
        assertEquals(BreakMode.FAST, BreakMode.of((String) null));
        assertEquals(BreakMode.FAST, BreakMode.of("bogus"));
        assertEquals(BreakMode.FAST, BreakMode.of(""));
    }

    @Test
    void buildDefaultIsFastWithSafeSettings() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertEquals("FAST", b.breakMode);
        assertEquals(BreakMode.FAST, BreakMode.of(b));
        assertEquals(1, b.nukeStartsPerTick);
        assertTrue(b.nukePairSlow);
        assertEquals(5, b.nukeStopDelayTicks);
        assertEquals(100, b.nukeMaxDigsPerSecond);
        b.breakMode = "single";
        assertEquals(BreakMode.SINGLE, BreakMode.of(b));
        b.breakMode = null;
        assertEquals(BreakMode.FAST, BreakMode.of(b));
    }
}
