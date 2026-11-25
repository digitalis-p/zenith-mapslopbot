package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fast placement latch and server setbacks (live 2026-10-15: a burst of 5 setbacks in 0.3 s turned fast placement
 * off for the rest of the run, though the refusals were the setback's, not Grim's MultiPlace).
 */
class SetbackLatchTest {
    private static final long S = 1_000_000_000L;

    private static AdaptivePacer pacer(SetbackClock clock) {
        AdaptivePacer p = new AdaptivePacer(1, 1, 4);
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.placeClicksPerTick = 3;
        b.placeLatchFails = 3;
        b.placeLatchWindow = 20;
        p.configure(PlacePacing.of(b));
        p.useSetbackClock(clock);
        return p;
    }

    @Test
    void refusalsRightAfterASetbackNeverCount() {
        SetbackClock c = new SetbackClock();
        AdaptivePacer p = pacer(c);
        c.setback(100 * S);
        for (int i = 0; i < 10; i++) assertFalse(p.sharedOutcome(true, 100 * S + i * (S / 10)));
        assertTrue(p.fastPlacementOn());
        // 2 s later refusals count again: three trip it
        assertFalse(p.sharedOutcome(true, 103 * S));
        assertFalse(p.sharedOutcome(true, 103 * S + 1));
        assertTrue(p.sharedOutcome(true, 103 * S + 2));
        assertFalse(p.fastPlacementOn());
    }

    @Test
    void aTripJustBeforeASetbackIsUndoneOnce() {
        SetbackClock c = new SetbackClock();
        AdaptivePacer p = pacer(c);
        assertFalse(p.sharedOutcome(true, 50 * S));
        assertFalse(p.sharedOutcome(true, 50 * S + 1));
        assertTrue(p.sharedOutcome(true, 50 * S + 2));
        assertFalse(p.fastPlacementOn());
        c.setback(51 * S); // the setback packet arrives after its refusals
        assertTrue(p.fastPlacementOn());
        assertNotNull(p.takeRestored());
        assertNull(p.takeRestored());
    }

    @Test
    void aTripWithNoSetbackNearStaysForTheConnectionAndANewConnectionUndoesIt() {
        SetbackClock c = new SetbackClock();
        AdaptivePacer p = pacer(c);
        c.setback(10 * S);
        assertFalse(p.sharedOutcome(true, 50 * S));
        assertFalse(p.sharedOutcome(true, 50 * S + 1));
        assertTrue(p.sharedOutcome(true, 50 * S + 2));
        assertFalse(p.fastPlacementOn());
        c.setback(60 * S); // 10 s later: not this trip's
        assertFalse(p.fastPlacementOn());
        assertNull(p.takeRestored());
        c.newConnection();
        assertTrue(p.fastPlacementOn());
        assertNotNull(p.takeRestored());
    }

    @Test
    void withoutATimeTheLatchWorksAsBefore() {
        SetbackClock c = new SetbackClock();
        AdaptivePacer p = pacer(c);
        c.setback(0);
        assertFalse(p.sharedOutcome(true));
        assertFalse(p.sharedOutcome(true));
        assertTrue(p.sharedOutcome(true));
        assertFalse(p.fastPlacementOn());
    }
}
