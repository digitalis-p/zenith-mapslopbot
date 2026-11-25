package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimTest {
    private static final double EPS = 1e-4;

    /** Side faces: the face of each neighbour that looks at the cell, and the side a click on it comes from. */
    @Test
    void sideFacesOfNeighbours() {
        assertEquals(Aim.WEST, Aim.sideFace(1, 0));  // the east neighbour's west face
        assertEquals(Aim.EAST, Aim.sideFace(-1, 0));
        assertEquals(Aim.NORTH, Aim.sideFace(0, 1)); // the south neighbour's north face
        assertEquals(Aim.SOUTH, Aim.sideFace(0, -1));
        // cell (10, 20); an eye 2.5 west of the cell centre (the lane line three columns west)
        assertTrue(Aim.outsideSideFace(7.5, 20.5, 10, 20, 1, 0, 0.05));   // east wall's west face: x = 11
        assertFalse(Aim.outsideSideFace(7.5, 20.5, 10, 20, -1, 0, 0.05)); // west wall's east face: x = 10, eye behind
        assertTrue(Aim.outsideSideFace(7.5, 20.5, 10, 20, 0, 1, 0.05));   // south wall's north face: z = 21
        assertTrue(Aim.outsideSideFace(7.5, 20.5, 10, 20, 0, -1, 0.05));  // north wall's south face: z = 20
        assertFalse(Aim.outsideSideFace(7.5, 20.02, 10, 20, 0, -1, 0.05)); // within the margin of the plane
        assertTrue(Aim.outsideSideFace(12.0, 20.5, 10, 20, -1, 0, 0.05));
        assertFalse(Aim.outsideSideFace(12.0, 20.5, 10, 20, 1, 0, 0.05));
        // the look at the east wall's upper face from the line is pitched down (pointed dripstone points up)
        float[] rot = Aim.rotationTo(7.5, 64 + Aim.EYE_HEIGHT, 20.5, 11, 64.85, 20.5);
        assertTrue(rot[1] >= Aim.SIDE_MIN_PITCH, "pitch " + rot[1]);
    }

    @Test
    void rotationCardinalDirections() {
        assertRot(0, 0, Aim.rotationTo(0, 0, 0, 0, 0, 1));     // south
        assertRot(90, 0, Aim.rotationTo(0, 0, 0, -1, 0, 0));   // west
        assertRot(-90, 0, Aim.rotationTo(0, 0, 0, 1, 0, 0));   // east
        float[] north = Aim.rotationTo(0, 0, 0, 0, 0, -1);
        assertEquals(180, Math.abs(north[0]), EPS);
        assertEquals(0, north[1], EPS);
        assertEquals(90, Aim.rotationTo(0, 1.62, 0, 0, 0, 0)[1], EPS, "straight down is pitch 90");
        assertEquals(-45, Aim.rotationTo(0, 0, 0, 0, 1, 1)[1], EPS, "up is negative pitch");
    }

    @Test
    void viewVectorPointsAtTarget() {
        Random r = new Random(42);
        for (int i = 0; i < 1000; i++) {
            double sx = r.nextDouble() * 10, sy = r.nextDouble() * 10, sz = r.nextDouble() * 10;
            double tx = r.nextDouble() * 10, ty = r.nextDouble() * 10, tz = r.nextDouble() * 10;
            double len = Math.sqrt(Aim.distSq(sx, sy, sz, tx, ty, tz));
            if (len < 0.1) continue;
            float[] rot = Aim.rotationTo(sx, sy, sz, tx, ty, tz);
            double[] v = Aim.viewVector(rot[0], rot[1]);
            assertEquals((tx - sx) / len, v[0], 1e-5);
            assertEquals((ty - sy) / len, v[1], 1e-5);
            assertEquals((tz - sz) / len, v[2], 1e-5);
        }
    }

    @Test
    void topFacePointsLieInsetOnTheTopFace() {
        SplittableRandom rnd = new SplittableRandom(1);
        List<double[]> pts = Aim.topFacePoints(13.7, 66.62, -2.2, 10, 63, -5, 0.15, 0.12, rnd);
        assertEquals(7, pts.size());
        for (double[] p : pts) {
            assertEquals(64.0, p[1], 0.0, "on the plane y = by + 1");
            assertTrue(p[0] >= 10.15 - EPS && p[0] <= 10.85 + EPS, "x inset " + p[0]);
            assertTrue(p[2] >= -4.85 - EPS && p[2] <= -4.15 + EPS, "z inset " + p[2]);
        }
        double[] first = pts.getFirst();
        assertTrue(Math.abs(first[0] - 10.5) <= 0.12 + EPS && Math.abs(first[2] + 4.5) <= 0.12 + EPS,
            "first candidate is the jittered center");
        assertArrayEquals(new double[]{10.5, 64, -4.5}, pts.get(2), EPS, "exact center after the near inset point");
    }

    @Test
    void topFacePointsAreCentreFirstThenNearestInsetPoint() {
        List<double[]> pts = Aim.topFacePoints(15, 66, 0.5, 10, 63, 0, 0.15, 0, new SplittableRandom(3));
        assertArrayEquals(new double[]{10.5, 64, 0.5}, pts.getFirst(), EPS, "center first");
        assertArrayEquals(new double[]{10.85, 64, 0.5}, pts.get(1), EPS, "fallback for the reach edge");
    }

    @Test
    void centreAimClearsACarpetOnTheNearNeighbourWithinReach() {
        double carpet = 1.0 / 16;
        double eye = Aim.EYE_HEIGHT;
        // the report's geometry: a point 0.15 inside the near edge is hidden by a neighbouring carpet past ~3.9 blocks
        assertTrue(Aim.rayHeightAtNearEdge(eye, 3.8, 0.15) > carpet);
        assertTrue(Aim.rayHeightAtNearEdge(eye, 4.0, 0.15) < carpet);
        // the jittered centre (at least 0.5 - 0.12 from the edge) stays clear at any legal reach
        for (double d = 0.5; d <= 4.5; d += 0.1) {
            assertTrue(Aim.rayHeightAtNearEdge(eye, d, 0.5 - 0.12) > carpet, "d=" + d);
        }
    }

    @Test
    void sideFacePoints() {
        // west face of block (0,0,0): x = 0 plane
        List<double[]> pts = Aim.facePoints(-3, 0.5, 0.5, 0, 0, 0, new int[]{-1, 0, 0}, 0.2, 0, new SplittableRandom(1));
        for (double[] p : pts) {
            assertEquals(0.0, p[0], 0.0);
            assertTrue(p[1] >= 0.2 - EPS && p[1] <= 0.8 + EPS);
            assertTrue(p[2] >= 0.2 - EPS && p[2] <= 0.8 + EPS);
        }
        // east face: x = 1 plane
        assertEquals(1.0, Aim.facePoints(3, 0.5, 0.5, 0, 0, 0, new int[]{1, 0, 0}, 0.2, 0, new SplittableRandom(1)).getFirst()[0], 0.0);
    }

    @Test
    void jitterVariesTheFirstPoint() {
        SplittableRandom rnd = new SplittableRandom(9);
        double[] a = Aim.topFacePoints(0.5, 66, 3.5, 0, 63, 0, 0.15, 0.12, rnd).getFirst();
        double[] b = Aim.topFacePoints(0.5, 66, 3.5, 0, 63, 0, 0.15, 0.12, rnd).getFirst();
        assertFalse(a[0] == b[0] && a[2] == b[2], "two consecutive aims differ (no duplicate rotation deltas)");
    }

    @Test
    void visibleFaceCentresOnlyFacesTowardTheEye() {
        List<double[]> pts = Aim.visibleFaceCentres(0.5, 5, -3, 0, 0, 0, 1, 1, 1);
        // eye above and north of the block: top and north faces visible, then the centre
        assertEquals(3, pts.size());
        assertArrayEquals(new double[]{0.5, 0.5, 0.5}, pts.getLast(), EPS);
        boolean top = false, north = false;
        for (double[] p : pts.subList(0, 2)) {
            if (p[1] == 1.0) top = true;
            if (p[2] == 0.0) north = true;
        }
        assertTrue(top && north);
    }

    @Test
    void cellOverlapsPlayerBox() {
        double[] box = Aim.playerBox(0.5, 64, 0.5, 0);
        assertTrue(Aim.cellOverlaps(0, 64, 0, box), "feet cell");
        assertTrue(Aim.cellOverlaps(0, 65, 0, box), "head cell");
        assertFalse(Aim.cellOverlaps(0, 63, 0, box), "floor below only touches");
        assertFalse(Aim.cellOverlaps(1, 64, 0, box), "neighbor");
        double[] grown = Aim.playerBox(0.5, 64, 0.5, 0.3);
        assertTrue(Aim.cellOverlaps(1, 64, 0, grown), "neighbor within the 0.3 safety margin");
        assertFalse(Aim.cellOverlaps(2, 64, 0, grown));
        // standing on a carpet (y + 1/16) still blocks the carpet's own cell
        assertTrue(Aim.cellOverlaps(0, 64, 0, Aim.playerBox(0.5, 64.0625, 0.5, 0)));
        // straddling a cell boundary blocks both cells
        double[] straddle = Aim.playerBox(1.0, 64, 0.5, 0);
        assertTrue(Aim.cellOverlaps(0, 64, 0, straddle) && Aim.cellOverlaps(1, 64, 0, straddle));
    }

    @Test
    void movementKeysForCardinalCases() {
        assertArrayEquals(new int[]{1, 0}, Aim.movementKeys(0, 0, 1), "facing south, go south: W");
        assertArrayEquals(new int[]{-1, 0}, Aim.movementKeys(0, 0, -1), "facing south, go north: S");
        assertArrayEquals(new int[]{0, 1}, Aim.movementKeys(0, 1, 0), "facing south, east is on the left: A");
        assertArrayEquals(new int[]{0, -1}, Aim.movementKeys(0, -1, 0), "facing south, west is on the right: D");
        assertArrayEquals(new int[]{1, 0}, Aim.movementKeys(90, -1, 0), "facing west, go west: W");
        assertArrayEquals(new int[]{1, 1}, Aim.movementKeys(0, 1, 1), "south-east: W+A");
        assertArrayEquals(new int[]{0, 0}, Aim.movementKeys(37, 0, 0));
    }

    @Test
    void movementKeysAlwaysWithin22Point5Degrees() {
        Random r = new Random(5);
        double minDot = Math.cos(Math.toRadians(22.5)) - 1e-9;
        for (int i = 0; i < 5000; i++) {
            double yaw = r.nextDouble() * 720 - 360;
            double ang = r.nextDouble() * Math.PI * 2;
            double dx = Math.cos(ang), dz = Math.sin(ang);
            int[] k = Aim.movementKeys(yaw, dx, dz);
            double[] w = Aim.keysToWorld(yaw, k[0], k[1]);
            double wl = Math.hypot(w[0], w[1]);
            assertTrue((w[0] * dx + w[1] * dz) / wl >= minDot, "yaw " + yaw + " dir " + dx + "," + dz);
        }
    }

    @Test
    void cursorIsRelativeAndClamped() {
        float[] c = Aim.cursor(10.3, 65.0, -4.7, 10, 64, -5);
        assertEquals(0.3f, c[0], 1e-6);
        assertEquals(1.0f, c[1], 1e-6);
        assertEquals(0.3f, c[2], 1e-6);
        float[] clamped = Aim.cursor(11.0000001, 64.9999999, -5.0000001, 10, 64, -5);
        assertEquals(1.0f, clamped[0], 0f);
        assertEquals(0.0f, clamped[2], 0f);
    }

    @Test
    void yawWrapping() {
        assertEquals(-170, Aim.wrapDegrees(190), EPS);
        assertEquals(10, Aim.yawDelta(355, 5), EPS);
        assertEquals(180, Aim.yawDelta(0, 180), EPS);
    }

    @Test
    void doubleChestNeighbour() {
        assertArrayEquals(new int[]{1, 0}, Aim.chestNeighbour("north", "left"));
        assertArrayEquals(new int[]{-1, 0}, Aim.chestNeighbour("north", "right"));
        assertArrayEquals(new int[]{0, 1}, Aim.chestNeighbour("east", "left"));
        assertArrayEquals(new int[]{-1, 0}, Aim.chestNeighbour("south", "left"));
        assertArrayEquals(new int[]{0, 1}, Aim.chestNeighbour("west", "right"));
        assertNull(Aim.chestNeighbour("north", "single"));
        assertNull(Aim.chestNeighbour(null, "left"));
    }

    private static void assertRot(double yaw, double pitch, float[] rot) {
        assertEquals(yaw, rot[0], EPS, "yaw");
        assertEquals(pitch, rot[1], EPS, "pitch");
    }

    @Test
    void faceCentresTurnedTowardsTheEyeNearestFirst() {
        // a single chest at the origin (1/16 inset, 14/16 high), eye south-east of it and a little higher than its top
        double p = 1.0 / 16;
        List<double[]> cs = Aim.faceCentres(1.5, 1.0, 2.0, p, 0, p, 1 - p, 14 * p, 1 - p);
        assertEquals(3, cs.size(), "top, south and east face the eye");
        assertEquals(Aim.SOUTH, (int) cs.get(0)[3]);
        assertArrayEquals(new double[]{0.5, 7 * p, 1 - p, Aim.SOUTH}, cs.get(0), EPS);
        assertTrue(cs.stream().anyMatch(c -> c[3] == Aim.EAST && Math.abs(c[0] - (1 - p)) < EPS));
        assertTrue(cs.stream().anyMatch(c -> c[3] == Aim.UP && Math.abs(c[1] - 14 * p) < EPS));
        // an eye level with the face plane does not see that face
        assertTrue(Aim.faceCentres(0.5, 0.875, 2, p, 0, p, 1 - p, 14 * p, 1 - p).stream().noneMatch(c -> c[3] == Aim.UP));
        assertEquals("south", Aim.faceName(Aim.SOUTH));
    }

    @Test
    void nearestYawKeepsTheYawContinuousWhateverTheDrift() {
        assertEquals(190f, Aim.nearestYaw(170f, -170f), 1e-4f);
        assertEquals(-190f, Aim.nearestYaw(-170f, 170f), 1e-4f);
        assertEquals(910f, Aim.nearestYaw(900f, -170f), 1e-4f, "ZenithProxy's single wrap would give 550");
        assertEquals(-1270f, Aim.nearestYaw(-1260f, 170f), 1e-4f);
        assertEquals(45f, Aim.nearestYaw(40f, 45f), 1e-4f);
        for (float cur = -2000f; cur <= 2000f; cur += 37.5f)
            for (float t = -180f; t <= 180f; t += 15f) {
                float y = Aim.nearestYaw(cur, t);
                assertTrue(Math.abs(y - cur) <= 180.0001f, cur + " -> " + t + " gave " + y);
                assertEquals(0f, (float) Math.IEEEremainder(y - t, 360.0), 1e-3f);
            }
    }

    /**
     * A paused ride's lane end (docs/COLOR_SUPPRESSION.md): the hold's cell (10, 222, 20), the
     * cart back on the next lane's first level rail (9, 224, 23). Local run 2026-11-15: the walk from the walkway ended
     * 0.14 inside the cell's north edge and the click's point was 3.09 from the eye ("out of reach to get in"). From
     * the point of the cell nearest the cart it is 2.50, from the cell's middle 2.73.
     */
    @Test
    void aCartThreeRailsDownTheNextLaneIsInReachFromTheNearPointOfTheHoldCell() {
        double cx = 9.5, cy = 224.0625, cz = 23.5;
        double ey = 222 + Aim.EYE_HEIGHT;
        double[] far = Aim.cartNearestPoint(10.55, ey, 20.14, cx, cy, cz);
        assertArrayEquals(new double[]{9.89, 224.1625, 23.11}, far, EPS);
        assertEquals(3.09, Math.sqrt(Aim.distSq(10.55, ey, 20.14, far[0], far[1], far[2])), 0.005);
        double[] stand = Aim.nearestInCell(10, 20, cx, cz);
        assertArrayEquals(new double[]{10.3, 20.7}, stand, EPS);
        double[] near = Aim.cartNearestPoint(stand[0], ey, stand[1], cx, cy, cz);
        assertEquals(2.50, Math.sqrt(Aim.distSq(stand[0], ey, stand[1], near[0], near[1], near[2])), 0.005);
        double[] mid = Aim.cartNearestPoint(10.5, ey, 20.5, cx, cy, cz);
        assertEquals(2.73, Math.sqrt(Aim.distSq(10.5, ey, 20.5, mid[0], mid[1], mid[2])), 0.005);
        // a south-row hold: the next lane runs north, the near point is the cell's other side
        assertArrayEquals(new double[]{10.3, 20.3}, Aim.nearestInCell(10, 20, 9.5, 17.5), EPS);
        // an eye inside the cart's footprint and height aims straight at itself (no clamp)
        assertArrayEquals(new double[]{9.6, 224.3, 23.4}, Aim.cartNearestPoint(9.6, 224.3, 23.4, cx, cy, cz), EPS);
    }
}
