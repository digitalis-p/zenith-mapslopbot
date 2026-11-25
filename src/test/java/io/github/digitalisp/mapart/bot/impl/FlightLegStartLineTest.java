package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link FlightLeg#startLine}: a long leg that walks says why at its start (live 2026-11-15
 * 09:28 a 196-block leg to the rocket chest walked for 4.5 minutes and its reason was never logged: the leg's own
 * line comes when the walk ends).
 */
class FlightLegStartLineTest {
    @Test
    void aLongLegThatWalksSaysWhyAtItsStart() {
        assertEquals("Elytra leg CONTAINER: 263 blocks, on foot from the start (no landing pad near the goal)",
            FlightLeg.startLine("CONTAINER", 263.4, "no landing pad near the goal", false));
        assertEquals("Elytra leg WALK: 179 blocks, on foot from the start (take-off hold: 8 of 10 world-time updates since"
            + " the login); outside the restock", FlightLeg.startLine("WALK", 179, "take-off hold: 8 of 10 world-time"
            + " updates since the login", true));
        assertEquals("Elytra leg CONTAINER: 120 blocks, on foot from the start (x)",
            FlightLeg.startLine("CONTAINER", FlightLeg.START_LINE_BLOCKS, "x", false));
    }

    @Test
    void aShortLegSaysNothingAtItsStart() {
        assertNull(FlightLeg.startLine("CONTAINER", 119.9, "59 blocks, under elytraMinBlocks 60", false));
        assertNull(FlightLeg.startLine("LANE_START", 10, "10 blocks, under elytraMinBlocks 60", true));
    }
}
