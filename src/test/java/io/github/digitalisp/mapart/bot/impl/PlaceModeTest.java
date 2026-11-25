package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceModeTest {
    @Test
    void parsesThePlaceModeCaseInsensitively() {
        assertEquals(PlaceMode.AIRPLACE, PlaceMode.of("AIRPLACE"));
        assertEquals(PlaceMode.AIRPLACE, PlaceMode.of("airplace"));
        assertEquals(PlaceMode.AIRPLACE, PlaceMode.of(" AirPlace "));
        assertEquals(PlaceMode.AIRPLACE, PlaceMode.of("air_place"));
        assertEquals(PlaceMode.AIRPLACE, PlaceMode.of("air"));
        assertEquals(PlaceMode.LOOK, PlaceMode.of("LOOK"));
        assertEquals(PlaceMode.LOOK, PlaceMode.of("look"));
    }

    @Test
    void unknownOrMissingModeIsLook() {
        assertEquals(PlaceMode.LOOK, PlaceMode.of((String) null));
        assertEquals(PlaceMode.LOOK, PlaceMode.of("bogus"));
        assertEquals(PlaceMode.LOOK, PlaceMode.of(""));
    }

    @Test
    void buildDefaultsAreThePrinterMeta() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertEquals("AIRPLACE", b.placeMode);
        assertEquals(5.0, b.airPlaceRange);
        assertEquals(-1, b.airPlaceLaneHalfWidth);
        assertFalse(b.rotatePlayer);
        assertTrue(b.grimRotate);
        assertEquals(4.5, b.reach);
        assertEquals(PlaceMode.AIRPLACE, PlaceMode.of(b));
        assertEquals(1.12, PlaceMode.EYE_ABOVE_CELL_CENTRE, 1e-9);
    }

    @Test
    void laneHalfWidthPerMode() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        assertEquals(4, PlaceMode.laneHalfWidth(b), "AIRPLACE auto at range 5.0: 9-wide lanes");
        b.airPlaceRange = 4.5;
        assertEquals(3, PlaceMode.laneHalfWidth(b));
        b.airPlaceLaneHalfWidth = 3;
        b.airPlaceRange = 5.0;
        assertEquals(3, PlaceMode.laneHalfWidth(b), "explicit");
        b.airPlaceLaneHalfWidth = 0;
        assertEquals(0, PlaceMode.laneHalfWidth(b), "explicit 0 is not auto");

        MapArtConfig.BuildCfg look = new MapArtConfig.BuildCfg();
        look.placeMode = "LOOK";
        assertEquals(2, PlaceMode.laneHalfWidth(look), "LOOK: laneHalfWidth");
        look.airPlaceLaneHalfWidth = 4;
        assertEquals(2, PlaceMode.laneHalfWidth(look), "airPlaceLaneHalfWidth is ignored by LOOK");
        look.laneHalfWidth = -3;
        assertEquals(0, PlaceMode.laneHalfWidth(look), "never negative");
    }

    @Test
    void silentRotationOnlyWithGrimRotateAndNoPlayerRotation() {
        MapArtConfig.BuildCfg b = new MapArtConfig.BuildCfg();
        b.rotatePlayer = false;
        b.grimRotate = true;
        assertTrue(PlaceMode.silentRotation(b));
        b.rotatePlayer = true;
        assertFalse(PlaceMode.silentRotation(b));
        b.rotatePlayer = false;
        b.grimRotate = false;
        assertFalse(PlaceMode.silentRotation(b));
        b.rotatePlayer = true;
        assertFalse(PlaceMode.silentRotation(b));
    }
}
