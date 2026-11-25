package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.InventoryView.Armor;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ElytraKit}: what a RESTOCK fetches, and the chest a sign names ({@code build.elytraLegs}). */
class ElytraKitTest {
    @Test
    void aSignOutOfTheScanKeepsItsChestAndOnlyAReadOrMissingSignDropsIt() {
        SimFakes.FakeWorld w = new SimFakes.FakeWorld();
        Pos chest = new Pos(127, 64, -5), signAt = new Pos(128, 64, -5); // the sign in the next chunk east
        BlockSpec wallSign = BlockSpec.of("minecraft:oak_wall_sign", Map.of("facing", "east"));
        w.set(chest, BlockSpec.of("minecraft:chest", Map.of("facing", "south", "type", "single")));
        w.set(signAt, wallSign);
        WorldView.Sign sign = new WorldView.Sign(signAt, wallSign, "Rockets");
        w.signList.add(sign);
        List<String> notes = new java.util.ArrayList<>();
        ElytraKit kit = new ElytraKit();
        assertEquals(chest, kit.chest(List.of(), w, new Pos(0, 64, 0), 0, notes::add));
        w.signList.clear(); // its chunk out of the scan; the block still a sign
        assertEquals(chest, kit.chest(List.of(), w, new Pos(0, 64, 0), 60_000, notes::add));
        w.loaded = p -> !p.equals(signAt); // its chunk out of view
        assertEquals(chest, kit.chest(List.of(), w, new Pos(0, 64, 0), 120_000, notes::add));
        assertEquals(1, notes.size(), notes.toString());
        w.loaded = p -> true;
        w.signList.add(new WorldView.Sign(signAt, wallSign, "Elytra")); // read, other text
        assertNull(kit.chest(List.of(), w, new Pos(0, 64, 0), 180_000, notes::add));
        assertEquals(2, notes.size(), notes.toString());
        w.signList.clear();
        w.signList.add(sign);
        assertEquals(chest, kit.chest(List.of(), w, new Pos(0, 64, 0), 240_000, notes::add));
        w.signList.clear();
        w.set(signAt, BlockSpec.AIR); // broken
        assertNull(kit.chest(List.of(), w, new Pos(0, 64, 0), 300_000, notes::add));
        assertEquals(4, notes.size(), notes.toString());
    }

    @Test
    void theFoundChestStaysWhileItsSignDoesAndIsLoggedOnce() {
        SimFakes.FakeWorld w = new SimFakes.FakeWorld();
        BlockSpec chest = BlockSpec.of("minecraft:chest", Map.of("facing", "south", "type", "single"));
        BlockSpec sign = BlockSpec.of("minecraft:oak_sign", Map.of("rotation", "0"));
        Pos a = new Pos(0, 64, 0), b = new Pos(40, 64, 0);
        w.set(a, chest);
        w.set(b, chest);
        w.signList.add(new WorldView.Sign(a.offset(0, 1, 0), sign, "ROCKETS"));
        w.signList.add(new WorldView.Sign(b.offset(0, 1, 0), sign, "rocket chest"));
        List<String> notes = new java.util.ArrayList<>();
        ElytraKit kit = new ElytraKit();
        assertEquals(a, kit.chest(List.of(), w, new Pos(5, 64, 0), 0, notes::add));
        assertEquals(a, kit.chest(List.of(), w, new Pos(38, 64, 0), 60_000, notes::add), "no flip to the nearer one");
        w.loaded = p -> !p.equals(a);
        assertEquals(a, kit.chest(List.of(), w, new Pos(38, 64, 0), 70_000, notes::add), "out of view: remembered");
        assertEquals(a, kit.chest(List.of(), w, new Pos(38, 64, 0), 100_000, notes::add), "out of view: remembered");
        w.loaded = p -> true;
        assertEquals(List.of("rocket chest found by its sign at " + a + " (nearest of 2)"), notes);
        w.signList.remove(0);
        assertEquals(b, kit.chest(List.of(), w, new Pos(5, 64, 0), 120_000, notes::add), "its sign gone: the other");
        w.signList.clear();
        assertNull(kit.chest(List.of(), w, new Pos(5, 64, 0), 180_000, notes::add));
        assertEquals(3, notes.size(), notes.toString());
        assertEquals(b, kit.chest(List.of(b), w, a, 200_000, notes::add), "the list wins");
    }

    @Test
    void anEmptyVisitHoldsTheNextForTenMinutesPerKindAndChest() {
        ElytraKit kit = new ElytraKit();
        Pos a = new Pos(1, 2, 3), b = new Pos(4, 5, 6);
        assertEquals(-1, kit.emptyAgo(true, a, 0));
        kit.empty(true, a, 1_000);
        assertEquals(59, kit.emptyAgo(true, a, 60_000));
        assertEquals(-1, kit.emptyAgo(false, a, 60_000), "the rockets are looked at on their own");
        assertEquals(-1, kit.emptyAgo(true, b, 60_000), "another chest is looked at");
        assertEquals(-1, kit.emptyAgo(true, a, 1_000 + ElytraKit.EMPTY_RETRY_MS));
        kit.stocked(true);
        assertEquals(-1, kit.emptyAgo(true, a, 2_000));
    }

    private static final String E = ElytraKit.ELYTRA;

    private static MapArtConfig.BuildCfg cfg() {
        return new MapArtConfig().build;
    }

    private static Armor carried(int remaining, boolean mending) {
        return new Armor(12, E, remaining, 432, mending);
    }

    @Test
    void noElytraAndNoRocketsFetchesBoth() {
        ElytraKit.Need n = ElytraKit.need(List.of(new Armor(6, "minecraft:diamond_chestplate", 500, 528, true)), 0, 0, cfg());
        assertTrue(n.elytra());
        assertEquals(64, n.rockets());
        assertTrue(n.why().contains("no elytra carried"), n.why());
    }

    @Test
    void rocketsAreToppedUpOnlyUnderTheLowMark() {
        assertEquals(0, ElytraKit.need(List.of(carried(400, true)), 16, 0, cfg()).rockets());
        assertEquals(49, ElytraKit.need(List.of(carried(400, true)), 15, 0, cfg()).rockets());
        assertFalse(ElytraKit.need(List.of(carried(400, true)), 40, 0, cfg()).any());
    }

    @Test
    void aWornOutElytraIsExchangedOnlyWhenMendingCannotRaiseIt() {
        // 43 of 432 is the 10 % floor
        assertFalse(ElytraKit.need(List.of(carried(44, true)), 64, 0, cfg()).elytra(), "above the floor");
        assertTrue(ElytraKit.need(List.of(carried(43, true)), 64, 0, cfg()).elytra(), "Mending but no XP bottles");
        assertFalse(ElytraKit.need(List.of(carried(43, true)), 64, 5, cfg()).elytra(), "XP bottles mend it");
        ElytraKit.Need noMending = ElytraKit.need(List.of(carried(43, false)), 64, 5, cfg());
        assertTrue(noMending.elytra());
        assertTrue(noMending.why().contains("no Mending"), noMending.why());
    }

    @Test
    void aWornElytraIsNeverExchangedAndNeverDoubled() {
        Armor worn = new Armor(Armor.CHEST, E, 10, 432, false);
        assertFalse(ElytraKit.need(List.of(worn), 64, 0, cfg()).elytra(), "the owner's or a flight's: it counts as carried");
        assertFalse(ElytraKit.need(List.of(worn, carried(10, false)), 64, 0, cfg()).elytra());
    }

    @Test
    void theFloorInUsesMatchesTheFlightsFloor() {
        assertEquals(43, ElytraKit.floorUses(432, 10));
        assertEquals(43, ElytraKit.floorUses(0, 10), "the vanilla maximum when none is known");
        assertTrue(ElytraKit.atFloor(carried(43, true), 10));
        assertFalse(ElytraKit.atFloor(carried(44, true), 10));
    }

    private static WorldView.Sign sign(Pos p, String id, Map<String, String> props, String text) {
        return new WorldView.Sign(p, BlockSpec.of(id, props), text);
    }

    @Test
    void aWallSignIsOnTheBlockBehindIt() {
        Pos s = new Pos(10, 64, 10);
        assertEquals(new Pos(10, 64, 11), ElytraKit.attached(sign(s, "minecraft:oak_wall_sign", Map.of("facing", "north"), "")));
        assertEquals(new Pos(10, 64, 9), ElytraKit.attached(sign(s, "minecraft:oak_wall_sign", Map.of("facing", "south"), "")));
        assertEquals(new Pos(9, 64, 10), ElytraKit.attached(sign(s, "minecraft:birch_wall_sign", Map.of("facing", "east"), "")));
        assertEquals(new Pos(11, 64, 10), ElytraKit.attached(sign(s, "minecraft:spruce_wall_sign", Map.of("facing", "west"), "")));
        assertEquals(new Pos(10, 63, 10), ElytraKit.attached(sign(s, "minecraft:oak_sign", Map.of("rotation", "4"), "")),
            "a standing sign is on the block under it");
        assertNull(ElytraKit.attached(sign(s, "minecraft:oak_hanging_sign", Map.of(), "")));
        assertNull(ElytraKit.attached(sign(s, "minecraft:oak_wall_hanging_sign", Map.of("facing", "north"), "")));
    }

    @Test
    void theSignedChestIsAChestOrBarrelWhoseSignSaysRocket() {
        Map<Pos, BlockSpec> world = new HashMap<>();
        Pos chest = new Pos(0, 64, 1);
        Pos barrel = new Pos(20, 65, 0);
        Pos furnace = new Pos(40, 64, 1);
        Pos plain = new Pos(60, 64, 1);
        world.put(chest, BlockSpec.of("minecraft:chest", Map.of("facing", "north", "type", "single")));
        world.put(barrel, BlockSpec.of("minecraft:barrel", Map.of("facing", "up")));
        world.put(furnace, BlockSpec.of("minecraft:furnace"));
        world.put(plain, BlockSpec.of("minecraft:trapped_chest"));
        List<WorldView.Sign> signs = List.of(
            sign(new Pos(0, 64, 0), "minecraft:oak_wall_sign", Map.of("facing", "north"), "{\"text\":\"ROCKETS\"}\n\"\""),
            sign(new Pos(20, 66, 0), "minecraft:oak_sign", Map.of("rotation", "0"), "\"\"\n\"firework rocket\""),
            sign(new Pos(40, 64, 0), "minecraft:oak_wall_sign", Map.of("facing", "north"), "\"rockets\""),
            sign(new Pos(60, 64, 0), "minecraft:oak_wall_sign", Map.of("facing", "north"), "\"elytra\""));
        List<Pos> found = ElytraKit.signedChests(signs, p -> world.getOrDefault(p, BlockSpec.AIR), new Pos(25, 64, 0));
        assertEquals(List.of(barrel, chest), found, "the furnace and the trapped chest without the word are left out;"
            + " nearest first");
    }

    @Test
    void theBackTextCountsAndTheCaseDoesNot() {
        assertTrue(ElytraKit.names(sign(new Pos(0, 0, 0), "minecraft:oak_sign", Map.of(), "\"\"\n\"\"\n\"RoCkEt\"")));
        assertFalse(ElytraKit.names(sign(new Pos(0, 0, 0), "minecraft:oak_sign", Map.of(), "\"rock\"")));
    }

    /**
     * Live 2026-11-15: a colour-suppression job went for the soul sand sorter's rocket chest, 196 blocks west of its
     * platform and 42 below its chests, with no way there. Such a job takes a rocket chest by its platform or none.
     */
    @Test
    void aJobThatKeepsToItsPlatformTakesARocketChestByItOrNone() {
        int minX = 1000, minZ = 2000, blocks = 64;
        Pos sorter = new Pos(minX - 196, 174, minZ - 6);
        Pos byThePlatform = new Pos(minX + 116, 176, minZ - 7);
        Pos onTheEdge = new Pos(minX - 64, 174, minZ + 3);
        assertNull(ElytraKit.forPlatform(sorter, List.of(sorter), minX, minZ, blocks), "far storage: no trip");
        assertNull(ElytraKit.forPlatform(sorter, List.of(), minX, minZ, blocks));
        assertEquals(byThePlatform, ElytraKit.forPlatform(sorter, List.of(sorter, byThePlatform), minX, minZ, blocks),
            "a chest a sign names by the platform instead");
        assertEquals(byThePlatform, ElytraKit.forPlatform(byThePlatform, List.of(sorter), minX, minZ, blocks),
            "the known chest is by the platform: it stays");
        assertEquals(onTheEdge, ElytraKit.forPlatform(onTheEdge, List.of(), minX, minZ, blocks), "64 blocks off is not far");
        assertEquals(sorter, ElytraKit.forPlatform(sorter, List.of(), minX, minZ, 0), "far storage switched off: any chest");
    }

    /** {@code build.elytraKitFarTrip}: the trip needs the platform's foot, entry and bottom glass. */
    @Test
    void theFarTripNeedsThePlatformsFootEntryAndGlass() {
        MapArtConfig.PlatformCfg cs = new MapArtConfig.PlatformCfg();
        cs.id = "cs";
        cs.kind = "COLOR_SUPPRESSION";
        assertEquals("platform cs has no foot (the bottom of its access path)", ElytraKit.farTripProblem(cs));
        cs.foot = new Pos(-14, 174, -3);
        assertEquals("platform cs has no entry (where its access path reaches the layers)", ElytraKit.farTripProblem(cs));
        cs.entry = new Pos(-1, 202, -24);
        assertEquals("platform cs has no glassY", ElytraKit.farTripProblem(cs));
        cs.glassY = List.of(200, 214);
        assertNull(ElytraKit.farTripProblem(cs));
    }

    /**
     * {@code build.elytraKitFarTrip}: where the bot stands. The storage walkway's level (the foot, the moss tile one
     * below, a chest stand up to four above) starts the visit's leg; the access path and the platform go down to the
     * foot first. The platform's levels begin one under the bottom glass, as for the engine's access-path walks.
     */
    @Test
    void theBotIsAtTheFootsLevelOnTheAccessPathOrOnThePlatform() {
        Pos foot = new Pos(-14, 174, -3);
        List<Integer> glass = List.of(200, 214);
        for (int y : new int[]{172, 173, 174, 178}) assertFalse(ElytraKit.aboveFoot(y, foot), "y " + y);
        for (int y : new int[]{179, 187, 199, 202, 216, 222}) assertTrue(ElytraKit.aboveFoot(y, foot), "y " + y);
        for (int y : new int[]{174, 187, 198}) assertFalse(ElytraKit.onPlatform(y, glass), "y " + y);
        for (int y : new int[]{199, 201, 216, 222}) assertTrue(ElytraKit.onPlatform(y, glass), "y " + y);
        assertFalse(ElytraKit.onPlatform(216, null));
        assertFalse(ElytraKit.onPlatform(216, List.of()));
    }

    private static MapArtConfig.RegionCfg box(int x0, int y0, int z0, int x1, int y1, int z1) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = new Pos(x0, y0, z0);
        r.max = new Pos(x1, y1, z1);
        return r;
    }

    /**
     * The far trip starts on the ride's ways when the bot's feet or head are in a no-go zone and in none of the
     * platform's own openings (the booth, the ride's walkway, the start stand); the layers, the top walkway and the
     * west landing north of the muncher are not (coordinates relative to the platform's corner).
     */
    @Test
    void theBotStandsOnTheRidesWaysInAZoneOutsideThePlatformsOwnOpenings() {
        List<MapArtConfig.RegionCfg> zones = List.of(box(-130, 215, 10, 1, 235, 119), box(0, 214, -16, 127, 235, 135));
        List<MapArtConfig.RegionCfg> own = List.of(box(0, 215, -2, 127, 219, 127), box(-12, 215, -10, 127, 218, -2));
        assertTrue(ElytraKit.onRideWays(zones, own, new Pos(-128, 222, 12)), "the booth");
        assertTrue(ElytraKit.onRideWays(zones, own, new Pos(-60, 222, 13)), "the ride's walkway");
        assertTrue(ElytraKit.onRideWays(zones, own, new Pos(-1, 222, 14)), "the start stand");
        assertFalse(ElytraKit.onRideWays(zones, own, new Pos(64, 215, 64)), "the top layer");
        assertFalse(ElytraKit.onRideWays(zones, own, new Pos(30, 216, -5)), "the top walkway by the cs chests");
        assertFalse(ElytraKit.onRideWays(zones, own, new Pos(-2, 217, 0)), "the west landing, north of the muncher");
        assertFalse(ElytraKit.onRideWays(zones, own, new Pos(-14, 174, -3)), "the foot");
        // the head counts: feet in an opening's top row, the head above it in the zone
        assertTrue(ElytraKit.onRideWays(zones, own, new Pos(30, 218, -5)));
        assertFalse(ElytraKit.onRideWays(null, own, new Pos(-128, 222, 12)), "no zones");
        assertTrue(ElytraKit.onRideWays(zones, null, new Pos(64, 215, 64)), "no openings: every zone cell");
    }
}
