package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The soul sand row chosen for a pickup leg (fake world: soul sand rows at some z, moss elsewhere, y 173 the floor). */
class SoulSandRouteTest {
    private static final Pos FROM = new Pos(64, 174, -3);
    private static final Pos TO = new Pos(126, 174, -7);

    /** Soul sand floor on the given rows, air above it, except {@code covered} rows where the walking level has a carpet. */
    private static Function<Pos, String> world(int[] soulRows, int... covered) {
        return p -> {
            if (p.y() == 173) {
                for (int z : soulRows) if (p.z() == z) return "minecraft:soul_sand";
                return "minecraft:moss_block";
            }
            if (p.y() == 174) {
                for (int z : covered) if (p.z() == z) return "minecraft:white_carpet";
            }
            return "minecraft:air";
        };
    }

    @Test
    void picksTheSoulSandRowNearTheLineAndWalksItsLength() {
        var r = SoulSandRoute.plan(FROM, TO, 4, world(new int[] {-5}), p -> true);
        assertNotNull(r);
        assertEquals(-5, r.row());
        assertEquals(1.0, r.share(), 1e-9);
        assertEquals(java.util.List.of(new Pos(64, 174, -5), new Pos(126, 174, -5)), r.waypoints());
    }

    @Test
    void aRowCoveredByACarpetIsNotSoulSandUnderTheFeet() {
        // the row at -5 is "100 % soul sand" on the floor but a carpet lies on it: no Soul Speed, so no route
        assertNull(SoulSandRoute.plan(FROM, TO, 4, world(new int[] {-5}, -5), p -> true));
        // another clear row is chosen instead
        var r = SoulSandRoute.plan(FROM, TO, 4, world(new int[] {-5, -2}, -5), p -> true);
        assertNotNull(r);
        assertEquals(-2, r.row());
    }

    @Test
    void theDiagnosisNamesTheBlocksOnTheStraightLine() {
        String d = SoulSandRoute.diagnose(FROM, TO, world(new int[] {-7}, -7), p -> true);
        assertTrue(d.startsWith("64,173,-7 floor=minecraft:soul_sand level=minecraft:white_carpet"), d);
        assertEquals("none", SoulSandRoute.diagnose(FROM, TO, world(new int[] {-7}), p -> true));
    }

    @Test
    void noRowAtEightyPercentMeansWalkingStraight() {
        Function<Pos, String> patchy = p -> p.y() == 173 && p.z() == -5 && p.x() % 2 == 0 ? "minecraft:soul_sand"
            : p.y() == 173 ? "minecraft:moss_block" : "minecraft:air";
        assertNull(SoulSandRoute.plan(FROM, TO, 4, patchy, p -> true));
    }

    @Test
    void aStraightLineThatIsAlreadySoulSandNeedsNoDetour() {
        assertNull(SoulSandRoute.plan(FROM, TO, 4, world(new int[] {-3, -7, -5}), p -> true));
    }

    @Test
    void unloadedCellsAreNotSoulSand() {
        assertNull(SoulSandRoute.plan(FROM, TO, 4, world(new int[] {-5}), p -> p.x() < 100));
    }

    @Test
    void aShortWalkIsLeftAlone() {
        assertNull(SoulSandRoute.plan(new Pos(100, 174, -3), new Pos(104, 174, -7), 4, world(new int[] {-5}), p -> true));
    }

    @Test
    void aWalkAlongZUsesColumns() {
        Function<Pos, String> w = p -> p.y() == 173 && p.x() == 8 ? "minecraft:soul_sand"
            : p.y() == 173 ? "minecraft:moss_block" : "minecraft:air";
        var r = SoulSandRoute.plan(new Pos(10, 174, 0), new Pos(12, 174, 60), 3, w, p -> true);
        assertNotNull(r);
        assertEquals(8, r.row());
        assertTrue(!r.alongX());
    }
}
