package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NukeGuard} on the live 2026-10-18 geometry (soul sand, tile-relative): the art layer at y173, the tile's east
 * edge column x 127, the no-go zone over the item trench from x 128 east; a row of decorated pots across the lane at
 * z 94 (their box inset 1/16: the bot's box stops at feet z 95.2375). The walk's keys at yaw 8° move it 8° east of
 * north (−z), as the replay's track showed (x 126.87 → 127.53 over z 99.85 → 95.24).
 */
class NukeGuardTest {
    static final int LEVEL = 173;
    /** Soul Speed on soul sand: the fastest one tick moved in the replay. */
    static final double SOUL_SPEED = 0.49;
    static final double YAW = 8;
    /** The walk's wanted direction: towards a waypoint far down the lane, no correction back onto a line. */
    static final double WANT_X = -0.008;
    static final double WANT_Z = -1;
    /** Feet z where the pots stop the box. */
    static final double POTS = 94.9375 + 0.3;

    static final Function<Pos, @Nullable String> EAST_ZONE = c -> c.x() >= 128 ? "no-go zone" : null;

    /** The wanted keys move the walk 8° east, towards the zone. */
    @Test
    void liveKeysDriftEast() {
        double[] k = NukeGuard.keyMotion(YAW, WANT_X, WANT_Z);
        assertEquals(Math.sin(Math.toRadians(YAW)), k[0], 1e-9);
        assertEquals(-Math.cos(Math.toRadians(YAW)), k[1], 1e-9);
        // NaN yaw (the simulations' exact walk): the direction itself
        double[] exact = NukeGuard.keyMotion(Double.NaN, 3, -4);
        assertEquals(0.6, exact[0], 1e-9);
        assertEquals(-0.8, exact[1], 1e-9);
    }

    /**
     * Pressed against the pots with the live keys, vanilla physics (the blocked axis loses its velocity, the other
     * slides on): without the guard the box goes into the zone, as live; with it, it never reaches x 128.
     */
    @Test
    void pressedAgainstThePotsNeverReachesTheZone() {
        double accel = NukeGuard.accel(SOUL_SPEED);
        assertTrue(press(accel, false) >= 128, "the scenario reproduces the live slide into the zone");
        double maxEast = press(accel, true);
        assertTrue(maxEast < 128, "box edge reached x " + maxEast);
    }

    /** Ticks of walking against the pots from the lane line; returns the box's farthest east edge. */
    private static double press(double accel, boolean guarded) {
        double x = 127.0;
        double z = 99.0;
        double vx = 0;
        double vz = -SOUL_SPEED * NukeGuard.FRICTION;
        double east = x + 0.3;
        for (int t = 0; t < 200; t++) {
            double[] k = NukeGuard.keyMotion(YAW, WANT_X, WANT_Z);
            if (guarded) {
                NukeGuard.Decision d = NukeGuard.decide(x, z, LEVEL, vx, vz, accel, YAW, WANT_X, WANT_Z, EAST_ZONE);
                if (d != null) k = new double[]{d.mx(), d.mz()};
            }
            vx += accel * k[0];
            vz += accel * k[1];
            x += vx;
            z += vz;
            if (z < POTS) {
                z = POTS;
                vz = 0;
            }
            east = Math.max(east, x + 0.3);
            vx *= NukeGuard.FRICTION;
            vz *= NukeGuard.FRICTION;
        }
        return east;
    }

    /** Pressed against the pots at the box's edge: the keys turn away from the zone (west), the walk is not held. */
    @Test
    void atThePotsTheKeysTurnAway() {
        double accel = NukeGuard.accel(SOUL_SPEED);
        NukeGuard.Decision d = NukeGuard.decide(127.62, POTS, LEVEL, 0.03, 0, accel, YAW, WANT_X, WANT_Z, EAST_ZONE);
        assertNotNull(d);
        assertTrue(d.hazard().startsWith("128,173,"), d.hazard());
        assertTrue(d.hazard().endsWith(": no-go zone"), d.hazard());
        assertEquals("turned", d.how());
        assertFalse(d.held());
        assertTrue(d.mx() < 0, "turned west: " + d.mx());
        assertTrue(d.mz() < 0, "still along the walk: " + d.mz());
    }

    /** Walking the edge lane's line at Soul Speed with the drifting keys: nothing held back (no slow edge lanes). */
    @Test
    void walkingTheEdgeLaneIsNotHeld() {
        double accel = NukeGuard.accel(SOUL_SPEED);
        for (double x = 127.30; x <= 127.55 + 1e-9; x += 0.05) {
            assertNull(NukeGuard.decide(x, 110, LEVEL, 0, -SOUL_SPEED * NukeGuard.FRICTION, accel, YAW, WANT_X, WANT_Z,
                EAST_ZONE), "x " + x);
        }
    }

    /** Holding still (no keys wanted) while sliding east towards the zone: braking, against the velocity. */
    @Test
    void slidingTowardsTheZoneBrakes() {
        double accel = NukeGuard.accel(SOUL_SPEED);
        assertNull(NukeGuard.decide(127.0, 100, LEVEL, 0.2, 0, accel, YAW, 0, 0, EAST_ZONE), "stops 0.44 short of it");
        NukeGuard.Decision d = NukeGuard.decide(127.3, 100, LEVEL, 0.25, 0, accel, YAW, 0, 0, EAST_ZONE);
        assertNotNull(d);
        assertEquals("braking", d.how());
        assertTrue(d.held());
        assertTrue(d.mx() < 0, "against the velocity: " + d.mx());
        // walking on east while sliding there: no key keeps clear, but letting go does not either: braking
        NukeGuard.Decision w = NukeGuard.decide(127.3, 100, LEVEL, 0.25, 0, accel, YAW, 1, 0, EAST_ZONE);
        assertNotNull(w);
        assertEquals("braking", w.how());
    }

    /** Walking east right at the zone with no velocity: no key nearby keeps clear, letting go does: held back. */
    @Test
    void noSafeKeysHoldsBack() {
        double accel = NukeGuard.accel(SOUL_SPEED);
        NukeGuard.Decision d = NukeGuard.decide(127.65, 100, LEVEL, 0, 0, accel, YAW, 1, 0, EAST_ZONE);
        assertNotNull(d);
        assertEquals("holding back", d.how());
        assertTrue(d.held());
        assertEquals(0, d.mx());
        assertEquals(0, d.mz());
    }

    /**
     * Already partly over a hazard cell: moving out of it is fine; deeper in is not, nor along it (the box would go into
     * the next hazard cell): the keys turn out of it.
     */
    @Test
    void outOfAHazardIsFine() {
        double accel = NukeGuard.accel(SOUL_SPEED);
        double x = 127.9; // box to 128.2
        assertNull(NukeGuard.decide(x, 100.5, LEVEL, 0, 0, accel, Double.NaN, -1, 0, EAST_ZONE), "out of it");
        assertNotNull(NukeGuard.decide(x, 100.5, LEVEL, 0, 0, accel, Double.NaN, 1, 0, EAST_ZONE), "deeper in");
        NukeGuard.Decision along = NukeGuard.decide(x, 100.5, LEVEL, 0, 0, accel, Double.NaN, 0, -1, EAST_ZONE);
        assertNotNull(along);
        assertEquals("turned", along.how());
        assertTrue(along.mx() < 0, "out of it: " + along.mx());
    }

    /** The west edge, mirrored: the one-wide gap over the item channel at x −1 is a drop; the walk drifts west. */
    @Test
    void theWestGapToo() {
        Function<Pos, @Nullable String> gap = c -> c.x() == -1 ? "open, 3 blocks down to water" : null;
        double accel = NukeGuard.accel(SOUL_SPEED);
        NukeGuard.Decision d = NukeGuard.decide(0.35, 40, LEVEL, -0.05, -0.2, accel, -YAW, 0.008, -1, gap);
        assertNotNull(d);
        assertTrue(d.hazard().startsWith("-1,173,"), d.hazard());
        assertTrue(d.mx() >= 0, "away from the gap: " + d.mx());
    }

    /** {@code build.nukeEdgeGuard} off: no cell is a hazard, nothing changes. */
    @Test
    void offChangesNothing() {
        double accel = NukeGuard.accel(SOUL_SPEED);
        assertNull(NukeGuard.decide(127.65, 100, LEVEL, 0.3, 0, accel, YAW, 1, 0, c -> null));
    }

    /**
     * The keys tried instead: those beside the wanted ones (45° from them) within 50° of the wanted direction, nearest
     * first; here only the one on the far side of the drift (37° off; the other is 53°). NaN yaw: ±45°.
     */
    @Test
    void nearKeys() {
        double[] k = NukeGuard.keyMotion(YAW, WANT_X, WANT_Z);
        List<double[]> near = NukeGuard.nearKeys(YAW, WANT_X, WANT_Z, k);
        assertEquals(1, near.size());
        double[] n = near.getFirst();
        assertEquals(Math.cos(Math.PI / 4), n[0] * k[0] + n[1] * k[1], 1e-9);
        assertTrue(n[0] < 0, "west of the drifting keys: " + n[0]);
        List<double[]> wide = NukeGuard.nearKeys(0, 1, -1, NukeGuard.keyMotion(0, 1, -1));
        for (int i = 1; i < wide.size(); i++) {
            double a = wide.get(i - 1)[0] - wide.get(i - 1)[1];
            double b = wide.get(i)[0] - wide.get(i)[1];
            assertTrue(a >= b - 1e-9, "nearest first");
        }
        List<double[]> exact = NukeGuard.nearKeys(Double.NaN, 0, -1, new double[]{0, -1});
        assertEquals(2, exact.size());
        assertEquals(-Math.cos(Math.PI / 4), exact.get(0)[1], 1e-9);
        assertEquals(-Math.cos(Math.PI / 4), exact.get(1)[1], 1e-9);
    }

    /** The acceleration from the top speed: Soul Speed 0.49 → 0.22; walking or less → the walking minimum. */
    @Test
    void accel() {
        assertEquals(0.49 * (1 - NukeGuard.FRICTION), NukeGuard.accel(0.49), 1e-9);
        assertEquals(NukeGuard.ACCEL_MIN, NukeGuard.accel(0.1), 1e-9);
        assertEquals(NukeGuard.TOP_SPEED_MAX * (1 - NukeGuard.FRICTION), NukeGuard.accel(5), 1e-9);
    }
}
