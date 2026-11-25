package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrayGlideGuardTest {
    @Test
    void aFreshJumpInTheAirWithAnElytraWornWaits() {
        // live 2026-10-25 08:47: the fall rescue's forward and jump while falling opened the elytra
        assertTrue(StrayGlideGuard.holdJump(true, false, false, false, false, false, true, false));
    }

    @Test
    void theFlightsOwnGlideStartPasses() {
        assertFalse(StrayGlideGuard.holdJump(true, false, false, false, false, false, true, true));
    }

    @Test
    void whatCannotOpenTheElytraPasses() {
        assertFalse(StrayGlideGuard.holdJump(false, false, false, false, false, false, true, false), "no jump");
        assertFalse(StrayGlideGuard.holdJump(true, true, false, false, false, false, true, false), "held since the tick before");
        assertFalse(StrayGlideGuard.holdJump(true, false, true, false, false, false, true, false), "on the ground: a jump");
        assertFalse(StrayGlideGuard.holdJump(true, false, false, true, false, false, true, false), "already gliding");
        assertFalse(StrayGlideGuard.holdJump(true, false, false, false, true, false, true, false), "in water: swimming up");
        assertFalse(StrayGlideGuard.holdJump(true, false, false, false, false, true, true, false), "a ladder or vines: climbing");
        assertFalse(StrayGlideGuard.holdJump(true, false, false, false, false, false, false, false), "no elytra worn");
    }

    @Test
    void zenithProxysInputFieldsAreThere() {
        // the guard reads InputManager's winning request and the Bot's last jump key by reflection (3.7.2): a renamed
        // field turns it off with a WARN, this test fails first
        assertTrue(StrayGlideGuard.fieldsFound());
    }
}
