package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.PlatformGeometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClearPresence}: the presence rule of the clear on the moss site, in base-relative coordinates (the tile is x
 * and z −64..63, chunk-aligned as the real one; the bridge centre (−1, −2), the supply walkway z −68, the sorter's
 * chests z −73..−71, the supply column x 52).
 */
class ClearPresenceTest {
    private static final PlatformGeometry MOSS = new PlatformGeometry(-64, -64, 173);

    @Test
    void fromTheBridgeCentreTheSweepIsFiveChunksAway() {
        // the machines park 16 beyond the east and west edges: chunks −5..4; the bridge centre is in chunk (−1, −1)
        assertEquals(5, ClearPresence.farthestSweepChunk(MOSS, -1, -2));
        assertEquals(5, ClearPresence.farthestSweepChunk(MOSS, 0, 0));
    }

    @Test
    void theSupplyWalkwayAndTheSorterAreOutOfReachAtFive() {
        // walkway (z −68, chunk −5): 8 chunks to the far rows of the tile, 9 to the sweep's south margin
        assertEquals(9, ClearPresence.farthestSweepChunk(MOSS, -20, -68));
        assertEquals(9, ClearPresence.farthestSweepChunk(MOSS, -60, -72));
        // the supply column (x 52): as far again
        assertEquals(9, ClearPresence.farthestSweepChunk(MOSS, 52, -72));
        // along the bridge: chunk −2 (z −32) is 6 from the sweep's south margin (chunk 4); chunk −1 (z −16) is 5
        assertEquals(6, ClearPresence.farthestSweepChunk(MOSS, -1, -32));
        assertEquals(5, ClearPresence.farthestSweepChunk(MOSS, -1, -16));
    }

    @Test
    void theCheckLineSaysWhatIsSpare() {
        ClearPresence.Check ok = ClearPresence.check("moss", MOSS, -1, -2, 5, 10);
        assertTrue(ok.covered());
        assertEquals("clear on moss: the farthest chunk of the sweep is 5 chunks from where the bot waits; server"
            + " simulation distance 5 chunks (view 10), 0 to spare", ok.text());
        ClearPresence.Check low = ClearPresence.check("moss", MOSS, -1, -2, 4, 10);
        assertFalse(low.covered());
        assertTrue(low.text().endsWith("server simulation distance 4 chunks (view 10): the flying machines and carts"
            + " beyond it are not simulated and may stall (presence rule)"), low.text());
        ClearPresence.Check unknown = ClearPresence.check("moss", MOSS, -1, -2, 0, 0);
        assertTrue(unknown.covered());
        assertEquals("clear on moss: the farthest chunk of the sweep is 5 chunks from where the bot waits; server"
            + " simulation distance unknown", unknown.text());
    }

    @Test
    void chunksOfNegativeCoordinatesRoundDown() {
        assertEquals(-1, ClearPresence.chunk(-1));
        assertEquals(-1, ClearPresence.chunk(-16));
        assertEquals(-2, ClearPresence.chunk(-17));
        assertEquals(0, ClearPresence.chunk(15));
    }
}
