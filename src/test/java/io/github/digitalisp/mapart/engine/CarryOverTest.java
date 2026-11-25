package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The carry-over's target diff and cost model ({@link CarryOver}). */
class CarryOverTest {
    private static final String LAPIS = "minecraft:lapis_block";
    private static final String WHITE = "minecraft:white_carpet";
    private static final String RED = "minecraft:red_carpet";
    private static final List<String> CLEARABLE = List.of(WHITE, RED);
    private static final PlatformGeometry GEO = new PlatformGeometry(1000, 2000, 64);

    private final SimFakes.FakeCatalog catalog = new SimFakes.FakeCatalog();
    private final SimFakes.FakeWorld world = new SimFakes.FakeWorld();

    /** A full tile of lapis with rows {@code from..to} (inclusive) of {@code carpet}. */
    private static TileArt lapisWith(String carpet, int from, int to) {
        TileArt a = new TileArt();
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(pz >= from && pz <= to ? carpet : LAPIS));
        return a;
    }

    private static TileArt all(String id) {
        TileArt a = new TileArt();
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) a.set(px, pz, BlockSpec.of(id));
        return a;
    }

    private TilePlan plan(TileArt art) {
        return new TilePlan(art, GEO, catalog);
    }

    private void build(TileArt art) {
        for (int pz = 0; pz < 128; pz++)
            for (int px = 0; px < 128; px++) {
                BlockSpec b = art.get(px, pz);
                if (b != null) world.set(GEO.cell(px, pz), b);
            }
    }

    @Test
    void lapisTilesShareTheirBackground() {
        TileArt now = lapisWith(WHITE, 0, 15), next = lapisWith(RED, 100, 115);
        build(now);
        CarryOver.Estimate e = CarryOver.estimate(plan(now), plan(next), world, CLEARABLE);
        // rows 16..99 and 116..127 are lapis in both: 96 rows kept
        assertEquals(96 * 128, e.kept());
        assertEquals(16 * 128, e.breakHand(), "the white carpet rows, by hand");
        assertEquals(16 * 128, e.breakFull(), "the lapis where the red carpet goes");
        assertEquals(32 * 128, e.placeCarry(), "both rows of 16 placed again");
        assertEquals(112 * 128, e.nukeNormal(), "every lapis block");
        assertTrue(e.clearNormal(), "the carpets go with the clear");
        assertEquals(16_384, e.placeNormal());
        // 4,096 x 8.6 + 4,096 x 1.7 = 42,188.8 ticks against 14,336 x 4.0 + 16,384 x 1.7 = 85,196.8 ticks + 80 s
        assertEquals(2109.4, e.carrySeconds(), 0.1);
        assertEquals(4339.8, e.normalSeconds(), 0.1);
        assertTrue(e.carry(0.1));
        assertTrue(e.savedSeconds() > 35 * 60, "over 35 min saved: " + e.describe());
        List<Pos> t = CarryOver.targets(plan(next), world);
        assertEquals(32 * 128, t.size());
        assertTrue(t.contains(GEO.cell(5, 3)) && t.contains(GEO.cell(5, 110)) && !t.contains(GEO.cell(5, 50)));
    }

    @Test
    void theSameArtTwiceKeepsEverything() {
        TileArt art = lapisWith(WHITE, 10, 20);
        build(art);
        CarryOver.Estimate e = CarryOver.estimate(plan(art), plan(art), world, CLEARABLE);
        assertEquals(16_384, e.kept());
        assertEquals(0, e.breakFull() + e.breakHand() + e.placeCarry());
        assertEquals(0.0, e.carrySeconds());
        assertTrue(e.carry(0.9));
        assertTrue(CarryOver.targets(plan(art), world).isEmpty());
    }

    @Test
    void carpetsOfAnotherColourAreClearedNotBroken() {
        TileArt now = all(WHITE), next = all(RED);
        build(now);
        CarryOver.Estimate e = CarryOver.estimate(plan(now), plan(next), world, CLEARABLE);
        assertEquals(0, e.kept());
        assertFalse(e.carry(0), "nothing in common: " + e.describe());
        assertEquals(0, e.nukeNormal());
        assertTrue(e.clearNormal());
        // even with a few in common, breaking 16k carpets by hand is slower than one clear
        TileArt mostlyRed = all(RED);
        for (int px = 0; px < 128; px++) mostlyRed.set(px, 0, BlockSpec.of(WHITE));
        CarryOver.Estimate f = CarryOver.estimate(plan(now), plan(mostlyRed), world, CLEARABLE);
        assertEquals(128, f.kept());
        assertFalse(f.carry(0), f.describe());
    }

    @Test
    void cellsTheNextTileLeavesEmptyAreBrokenAndNotPlaced() {
        TileArt now = all(LAPIS), next = new TileArt();
        for (int px = 0; px < 128; px++) next.set(px, 0, BlockSpec.of(LAPIS));
        build(now);
        CarryOver.Estimate e = CarryOver.estimate(plan(now), plan(next), world, CLEARABLE);
        assertEquals(128, e.kept());
        assertEquals(16_384 - 128, e.breakFull());
        assertEquals(0, e.placeCarry());
        assertEquals(128, e.placeNormal());
        assertFalse(e.clearNormal(), "all lapis: the nuke leaves the layer empty, no clear");
        // carry: 16,256 x 8.6 ticks; normal: 16,384 x 4.0 + 128 x 1.7: breaking among kept blocks loses to the flat nuke
        assertFalse(e.carry(0), e.describe());
    }

    @Test
    void theMarginDecides() {
        CarryOver.Estimate e = new CarryOver.Estimate(100, 0, 0, 1000, 0, false, 1100, false);
        // carry 85 s, normal 93.5 s: 9 % faster
        assertTrue(e.carry(0.05));
        assertFalse(e.carry(0.1));
        assertTrue(e.carry(-1), "a negative margin counts as 0");
        assertFalse(new CarryOver.Estimate(0, 0, 0, 0, 10, false, 10, false).carry(0), "nothing kept: never");
    }

    @Test
    void theFirstLiveCarryOverIsNoLongerTaken() {
        // live 2026-11-02, Snowy Angel [11,5] → [12,5]: taken at 52 against 93 min with the first rates; measured, the
        // break alone took 56 min against about 37 for the normal nuke of [10,5]
        CarryOver.Estimate e = new CarryOver.Estimate(8_530, 3_693, 4_161, 7_854, 9_519, true, 16_384, false);
        assertFalse(e.carry(0), e.describe());
        assertTrue(e.carrySeconds() / 60 > 65 && e.normalSeconds() / 60 < 60, e.describe());
        // [0,8] → [1,8] of the same map keeps 14,620 cells: still carried
        assertTrue(new CarryOver.Estimate(14_620, 900, 864, 1_764, 9_800, true, 16_384, false).carry(0.1));
    }

    @Test
    void fluidOnTheLayerIsNoCarryOver() {
        TileArt art = lapisWith(WHITE, 0, 3);
        build(art);
        Pos wet = GEO.cell(7, 7);
        world.set(wet, BlockSpec.of("minecraft:water"));
        world.fluid.add(wet);
        CarryOver.Estimate e = CarryOver.estimate(plan(art), plan(art), world, CLEARABLE);
        assertTrue(e.fluid());
        assertFalse(e.carry(0));
        assertFalse(CarryOver.targets(plan(art), world).contains(wet), "a fluid is never a target");
    }

    @Test
    void fixturesAreNeverTargets() {
        TileArt now = all(LAPIS), next = all(RED);
        build(now);
        Pos button = GEO.cell(64, 64);
        world.set(button, BlockSpec.of("minecraft:stone_button"));
        Set<Pos> fixtures = new HashSet<>(List.of(button));
        TilePlan n = new TilePlan(next, GEO, catalog, fixtures);
        assertFalse(CarryOver.targets(n, world).contains(button));
        assertEquals(16_383, CarryOver.targets(n, world).size());
    }
}
