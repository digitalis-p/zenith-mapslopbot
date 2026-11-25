package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ElytraPhysics}: the steady glides and boost of vanilla 1.21.4's elytra, the landing slide's bounds. */
class ElytraPhysicsTest {
    private static ElytraPhysics.State steady(float pitch, boolean boost) {
        ElytraPhysics.State s = new ElytraPhysics.State(0, 100, 0, 0, 0, boost ? 0.3 : 1.0);
        for (int i = 0; i < 400; i++) s = ElytraPhysics.fly(s, 0f, pitch, boost);
        return s;
    }

    @Test
    void steadyGlides() {
        ElytraPhysics.State level = steady(0f, false);
        assertEquals(1.50, level.hSpeed(), 0.02);
        assertEquals(-0.15, level.vy(), 0.01);
        // the descent when no pitch is safe: down, but never the sink that keeps the fall distance (0.5 a tick)
        ElytraPhysics.State down = steady(FlightControl.DESCENT_PITCH, false);
        assertTrue(down.vy() < -0.15 && down.vy() > -0.5, "descent " + down.hSpeed() + " / " + down.vy());
        ElytraPhysics.State rocket = steady(0f, true);
        assertEquals(1.69, rocket.hSpeed(), 0.02);
        assertEquals(0.0, rocket.vy(), 0.02);
    }

    @Test
    void lookAndYawAgree() {
        double[] l = ElytraPhysics.look(ElytraPhysics.yawTo(0, 0, 10, 0), 0);
        assertEquals(1.0, l[0], 1e-9);
        assertEquals(0.0, l[2], 1e-9);
        double[] south = ElytraPhysics.look(ElytraPhysics.yawTo(0, 0, 0, 10), 0);
        assertEquals(1.0, south[2], 1e-9);
        assertEquals(-1.0, ElytraPhysics.look(0, 90)[1], 1e-9);
    }

    @Test
    void landingSlideIsBoundedByTheBrake() {
        // no answer from the server for a long time: the turned glide still stops within about 4.3 x the speed
        double far = ElytraPhysics.slide(1.6, 1000, 0);
        assertTrue(far < 1.6 * 4.3, "slide " + far);
        // a quick answer: 1 tick braking, then ground friction
        double near = ElytraPhysics.slide(1.6, 1, 0);
        assertTrue(near < far && near > 1.6 * 2, "slide " + near);
        // a rocket still boosting pushes on
        assertTrue(ElytraPhysics.slide(1.6, 6, 6) > far);
    }
}
