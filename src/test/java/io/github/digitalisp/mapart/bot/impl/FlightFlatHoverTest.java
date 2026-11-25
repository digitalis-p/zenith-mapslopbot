package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FlightLeg#flatHover}: a leg under 60 blocks hovers only to a goal at least 3 levels above or below the feet
 * (live 10-25..27 a walk of 30-60 blocks on the flat took 92-123
 * ticks, a hover about 150-220).
 */
class FlightFlatHoverTest {

    @Test
    void aShortLegOnTheFlatWalks() {
        String why = FlightLeg.flatHover(41, 174.0, 175);
        assertNotNull(why);
        assertTrue(why.contains("41 blocks on the flat (goal +1 from the feet)"), why);
        assertNotNull(FlightLeg.flatHover(30, 174.0, 174));
        assertNotNull(FlightLeg.flatHover(59.4, 174.0, 172));
        // standing on a carpet or a plate: the feet's block is still the level
        assertNotNull(FlightLeg.flatHover(45, 174.0625, 176));
    }

    @Test
    void aClimbOrDropOfThreeOrALongLegMayFly() {
        assertNull(FlightLeg.flatHover(45, 168.0, 174), "the clear stand, 6 over the art layer");
        assertNull(FlightLeg.flatHover(45, 174.0, 171));
        assertNull(FlightLeg.flatHover(45, 174.0, 177));
        assertNull(FlightLeg.flatHover(60, 174.0, 174), "60 and more: a straight flight, not a hover");
        assertNull(FlightLeg.flatHover(120, 174.0, 174));
    }

    @Test
    void aWalkWithoutAFlightLegIsNotBusy() {
        // PlaceLaneTask.clickWhileWalking places in reach only while the walk's flight leg is idle
        assertTrue(!new Walker().flightBusy());
    }
}
