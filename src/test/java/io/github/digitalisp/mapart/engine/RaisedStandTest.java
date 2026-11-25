package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RaisedStand} on the soul sand platform in base-relative coordinates (testserver/examples/
 * site-config.soulsand.json: the tile is x -320..-193, z -64..63, art y 173; the button cross at (-257, 179, -1), feet
 * on the y178 glass; the floor landing (-257, 173, -4)), and {@link ClearPresence} from the cross.
 */
class RaisedStandTest {
    private static PlatformCfg soul() {
        PlatformCfg p = new PlatformCfg();
        p.id = "soulsand";
        p.kind = "SOUL_SAND";
        p.minX = -320;
        p.minZ = -64;
        p.artY = 173;
        p.center = new Pos(-257, 179, -1);
        p.mapStandAt = new Pos(-257, 173, -1);
        p.hopDownTo = new Pos(-257, 173, -4);
        return p;
    }

    @Test
    void theCrossIsRaisedAndTheFloorBelowItIsNotTheStand() {
        PlatformCfg p = soul();
        assertTrue(RaisedStand.raised(p));
        assertTrue(RaisedStand.atStand(p, p.center, -256.6, 179.0, -0.5), "on the cross");
        assertTrue(RaisedStand.atStand(p, p.center, -255.5, 179.125, 0.5), "on a repeater of the bridge next to it");
        assertFalse(RaisedStand.atStand(p, p.center, -256.5, 172.875, -0.5), "the floor under the cross is in the sweep");
        assertFalse(RaisedStand.atStand(p, p.center, -250.5, 179.0, -0.5), "too far along the bridge");
    }

    @Test
    void aCentreAtTheArtLayerKeepsTheHorizontalTest() {
        PlatformCfg p = soul();
        p.center = new Pos(-257, 173, -1);
        assertFalse(RaisedStand.raised(p));
        assertTrue(RaisedStand.atStand(p, p.center, -256.5, 172.875, -0.5));
        assertTrue(RaisedStand.atStand(p, p.center, -256.5, 190.0, -0.5));
    }

    @Test
    void theStructureIsTheCrossAndTheBridgeArmsOverTheTile() {
        PlatformCfg p = soul();
        assertTrue(RaisedStand.onStructure(p, -256.5, 179.0, -0.5));
        assertTrue(RaisedStand.onStructure(p, -192.5 - 0.5, 179.125, -0.5), "the bridge's east end, still over the tile");
        assertFalse(RaisedStand.onStructure(p, -182.5, 180.0, -20.5), "the owner's walkway east of the tile");
        assertFalse(RaisedStand.onStructure(p, -256.5, 172.875, -0.5), "the floor");
        assertFalse(RaisedStand.onStructure(p, -256.5, 182.0, -0.5), "well above it");
    }

    @Test
    void hopNeedsTheStructureHealthAndALowerLanding() {
        PlatformCfg p = soul();
        assertEquals(6, RaisedStand.drop(p));
        assertEquals(4, RaisedStand.fallDamage(6, 0.125), "6.125 blocks onto soul sand: ceil(3.125)");
        assertEquals(0, RaisedStand.fallDamage(2, 0.0));
        assertNull(RaisedStand.hop(p, -256.5, 179.0, -0.5, 20f));
        assertNull(RaisedStand.hop(p, -256.5, 179.0, -0.5, 14f));
        assertTrue(RaisedStand.hop(p, -256.5, 179.0, -0.5, 13.6f).startsWith("health 13.6 is below hopDownMinHealth 14"));
        assertTrue(RaisedStand.hop(p, -256.5, 172.875, -0.5, 20f).startsWith("not on the center structure"));
        p.hopDownTo = null;
        assertEquals("no hopDownTo configured", RaisedStand.hop(p, -256.5, 179.0, -0.5, 20f));
        p.hopDownTo = new Pos(-257, 180, -4);
        assertEquals(0, RaisedStand.drop(p));
        assertEquals("hopDownTo is not below the center", RaisedStand.hop(p, -256.5, 179.0, -0.5, 20f));
    }

    @Test
    void presenceFromTheCrossIsFiveWithNothingToSpare() {
        // the machines park east (x -191..-185) and at the west station (x -327..-321): chunks -21..-12 with the sweep's
        // 16-block margin; the cross is in chunk (-17, -1)
        PlatformGeometry g = new PlatformGeometry(-320, -64, 173);
        assertEquals(5, ClearPresence.farthestSweepChunk(g, -257, -1));
        // the floor under it has the same chunk (presence alone would allow it: the sweep does not)
        assertEquals(5, ClearPresence.farthestSweepChunk(g, -257, -4));
        // the owner's walkway east of the tile (x -182) and the supply walkway north of it (z -67) are too far
        assertEquals(9, ClearPresence.farthestSweepChunk(g, -182, -20));
        assertEquals(9, ClearPresence.farthestSweepChunk(g, -257, -67));
        ClearPresence.Check c = ClearPresence.check("soulsand", g, -257, -1, 5, 10);
        assertTrue(c.covered());
        assertTrue(c.text().endsWith("0 to spare"), c.text());
    }
}
