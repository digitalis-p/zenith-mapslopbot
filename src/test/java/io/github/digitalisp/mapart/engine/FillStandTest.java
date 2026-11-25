package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FillStand}: the map fill stand of {@code map.fillNearStand} (made-up coordinates). */
class FillStandTest {
    /** A grid-aligned tile: x 1088..1215, z -64..63, art y 64. */
    private static final PlatformGeometry GEO = new PlatformGeometry(1088, -64, 64);
    /** The cartography table north-east of the tile, off it (like the soul sand site's, made-up). */
    private static final Pos TABLE = new Pos(1088 + 322, 65, -64 - 8);

    /** Vanilla MapItem.update at scale 0: pixel (x, z) is drawn on every pass from holder pixel (px, pz). */
    private static boolean drawnEveryPass(int px, int pz, int x, int z) {
        int q = (x - px) * (x - px) + (z - pz) * (z - pz);
        return q <= 126 * 126;
    }

    @Test
    void aCoveringCellDrawsEveryPixelOnEveryPass() {
        int checked = 0;
        for (int px = 0; px < TileArt.SIZE; px += 3) {
            for (int pz = 0; pz < TileArt.SIZE; pz += 3) {
                if (!FillStand.covers(px, pz, 0)) continue;
                checked++;
                for (int x = 0; x < TileArt.SIZE; x++)
                    for (int z = 0; z < TileArt.SIZE; z++)
                        assertTrue(drawnEveryPass(px, pz, x, z), px + "," + pz + " misses " + x + "," + z);
            }
        }
        assertTrue(checked > 400, "sampled " + checked);
        // and a cell just outside misses a corner
        assertFalse(FillStand.covers(64 + 45, 64, 0));
        assertFalse(drawnEveryPass(64 + 45, 64, 0, 0) && drawnEveryPass(64 + 45, 64, 0, 127));
    }

    @Test
    void coverageAtTheMarginIsTheDocumentedArea() {
        int n = 0;
        int minX = TileArt.SIZE, maxX = -1;
        for (int px = 0; px < TileArt.SIZE; px++) {
            for (int pz = 0; pz < TileArt.SIZE; pz++) {
                if (!FillStand.covers(px, pz, FillStand.MARGIN)) continue;
                n++;
                minX = Math.min(minX, px);
                maxX = Math.max(maxX, px);
            }
        }
        assertEquals(4320, n);
        assertEquals(21, minX);
        assertEquals(106, maxX);
        assertTrue(FillStand.covers(63, 63, FillStand.MARGIN), "the center covers");
        assertFalse(FillStand.covers(127, 0, FillStand.MARGIN), "a corner never does");
    }

    @Test
    void fromTheEastEdgeTheStandLiesOnTheWayToTheTable() {
        // the last lane ends at the east edge (live: most maps), the table lies north-east off the tile
        double botX = GEO.minX() + 127.5, botZ = GEO.minZ() + 40.5;
        Pos c = FillStand.choose(GEO, botX, botZ, TABLE, FillStand.MARGIN, p -> true);
        assertNotNull(c);
        assertEquals(GEO.artY(), c.y());
        assertTrue(FillStand.covers(c.x() - GEO.minX(), c.z() - GEO.minZ(), FillStand.MARGIN), String.valueOf(c));
        Pos centre = GEO.center();
        double viaStand = dist(botX, botZ, c) + dist(c, TABLE);
        double viaCentre = dist(botX, botZ, centre) + dist(centre, TABLE);
        assertTrue(viaStand + 40 < viaCentre, "via stand " + viaStand + " vs center " + viaCentre);
        assertTrue(FillStand.farthestCorner(GEO, c) < 126, String.valueOf(FillStand.farthestCorner(GEO, c)));
    }

    @Test
    void aBotOnACoveringCellStaysWhereItIs() {
        Pos on = GEO.cell(80, 50);
        assertTrue(FillStand.covers(80, 50, FillStand.MARGIN));
        assertEquals(on, FillStand.choose(GEO, on.x() + 0.5, on.z() + 0.5, TABLE, FillStand.MARGIN, p -> true));
        assertEquals(on, FillStand.choose(GEO, on.x() + 0.5, on.z() + 0.5, null, FillStand.MARGIN, p -> true));
    }

    @Test
    void withoutANextStopTheNearestCoveringCell() {
        double botX = GEO.minX() + 0.5, botZ = GEO.minZ() + 64.5; // west edge, middle
        Pos c = FillStand.choose(GEO, botX, botZ, null, FillStand.MARGIN, p -> true);
        assertEquals(GEO.cell(21, 64), c);
    }

    @Test
    void disallowedCellsAreSkippedAndNoneLeftIsNull() {
        double botX = GEO.minX() + 0.5, botZ = GEO.minZ() + 64.5;
        Pos first = GEO.cell(21, 64);
        Pos c = FillStand.choose(GEO, botX, botZ, null, FillStand.MARGIN, p -> !p.equals(first));
        assertNotNull(c);
        assertFalse(c.equals(first));
        assertTrue(FillStand.covers(c.x() - GEO.minX(), c.z() - GEO.minZ(), FillStand.MARGIN));
        assertNull(FillStand.choose(GEO, botX, botZ, TABLE, FillStand.MARGIN, p -> false));
    }

    @Test
    void farthestCornerOfTheCentreAndAnEdgeCell() {
        assertEquals(Math.hypot(64.5, 64.5), FillStand.farthestCorner(GEO, GEO.cell(64, 64)), 1e-9);
        assertEquals(Math.hypot(64.5, 64.5), FillStand.farthestCorner(GEO, GEO.cell(63, 63)), 1e-9);
        assertEquals(Math.hypot(127.5, 127.5), FillStand.farthestCorner(GEO, GEO.cell(0, 0)), 1e-9);
    }

    private static double dist(double x, double z, Pos p) {
        return Math.hypot(p.x() + 0.5 - x, p.z() + 0.5 - z);
    }

    private static double dist(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
