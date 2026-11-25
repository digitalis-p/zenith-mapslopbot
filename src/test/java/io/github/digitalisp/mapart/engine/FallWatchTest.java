package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FallWatch} on the live 2026-10-13 fall in base-relative coordinates (testserver/examples/
 * site-config.soulsand.json: the soul sand tile x -320..-193, z -64..63, art layer y173): the bot on the floor at the
 * west edge (feet y172.875), then in the water channel at x -321, feet y170.
 */
class FallWatchTest {
    private static PlatformCfg soul() {
        PlatformCfg p = new PlatformCfg();
        p.id = "soulsand";
        p.kind = "SOUL_SAND";
        p.minX = -320;
        p.minZ = -64;
        p.artY = 173;
        return p;
    }

    private static FallWatch.Fall run(FallWatch w, List<PlatformCfg> ps, int ticks, double x, double y, double z,
                                      boolean water) {
        FallWatch.Fall f = null;
        for (int i = 0; i < ticks; i++) f = w.tick(ps, x, y, z, water, false);
        return f;
    }

    @Test
    void theLiveFallIsSeenAfterTenTicks() {
        List<PlatformCfg> ps = List.of(soul());
        FallWatch w = new FallWatch();
        assertNull(run(w, ps, 50, -319.89, 172.875, -26.05, false), "on the floor at the edge");
        assertNull(run(w, ps, FallWatch.BELOW_TICKS - 1, -320.49, 170.0, -26.4, true));
        FallWatch.Fall f = w.tick(ps, -320.49, 170.0, -26.4, true, false);
        assertEquals("feet at y170.0, 3.0 below the art layer of platform soulsand (y173), in water", f.why());
        assertSame(ps.getFirst(), f.platform());
    }

    @Test
    void underTheFloorWithoutHavingStoodOnItIsNoFall() {
        List<PlatformCfg> ps = List.of(soul());
        FallWatch w = new FallWatch();
        assertNull(run(w, ps, 100, -300.5, 165.0, 0.5, false), "logged in under the tile");
        // the floor seen, then under it: a fall
        run(w, ps, 1, -300.5, 173.0, 0.5, false);
        assertTrue(run(w, ps, FallWatch.BELOW_TICKS, -300.5, 165.0, 0.5, false).why().startsWith("feet at y165.0"));
    }

    @Test
    void theMarginIsTwoCellsAndTheNooblineRowCounts() {
        PlatformCfg p = soul();
        List<PlatformCfg> ps = List.of(p);
        assertSame(p, FallWatch.platformAt(ps, -321.5, 0));
        assertSame(p, FallWatch.platformAt(ps, -322.0, 0));
        assertNull(FallWatch.platformAt(ps, -322.01, 0));
        assertSame(p, FallWatch.platformAt(ps, -190.5, 0), "two cells east of x -193");
        assertNull(FallWatch.platformAt(ps, -189.5, 0));
        assertSame(p, FallWatch.platformAt(ps, -250, -66.5), "the noobline row z -65 and two more north");
        assertNull(FallWatch.platformAt(ps, -250, -67.5));
        p.kind = PlatformCfg.COLOR_SUPPRESSION;
        assertNull(FallWatch.platformAt(ps, -250, 0), "color-suppression floors have their own fall safety");
        p.kind = "SOUL_SAND";
        p.enabled = false;
        assertNull(FallWatch.platformAt(ps, -250, 0));
    }

    @Test
    void waterAnywhereForTwoSecondsIsAFallRidingIsNot() {
        List<PlatformCfg> ps = List.of(soul());
        FallWatch w = new FallWatch();
        assertNull(run(w, ps, FallWatch.WATER_TICKS - 1, 500.5, 64.0, 500.5, true));
        FallWatch.Fall f = w.tick(ps, 500.5, 64.0, 500.5, true, false);
        assertEquals("in water for 40 ticks (feet at y64.0)", f.why());
        assertNull(f.platform());
        w.reset();
        for (int i = 0; i < 200; i++) assertNull(w.tick(ps, 500.5, 64.0, 500.5, true, true), "in a vehicle");
    }

    @Test
    void aStepBackOntoTheFloorResetsTheCount() {
        List<PlatformCfg> ps = List.of(soul());
        FallWatch w = new FallWatch();
        run(w, ps, 5, -300.5, 172.875, 0.5, false);
        assertNull(run(w, ps, FallWatch.BELOW_TICKS - 1, -300.5, 171.0, 0.5, false));
        assertNull(w.tick(ps, -300.5, 172.875, 0.5, false, false));
        assertNull(run(w, ps, FallWatch.BELOW_TICKS - 1, -300.5, 171.0, 0.5, false));
    }
}
