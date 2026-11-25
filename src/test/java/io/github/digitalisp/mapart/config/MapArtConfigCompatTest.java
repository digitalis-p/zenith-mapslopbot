package io.github.digitalisp.mapart.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Old plugin configs must keep loading: a mapart.json that fails to parse shuts ZenithProxy down. ZenithProxy reads it
 * with its own Gson ({@code DefaultGsonConfigSerializer}: {@code Globals.GSON}, a plain GsonBuilder with HTML escaping
 * off and pretty printing plus type adapters for its own types), which skips unknown fields. The same builder here:
 * tests never touch {@code Globals} (its static init loads ZenithProxy's launch config and logging). Checked once by
 * hand against {@code DefaultGsonConfigSerializer} itself on 2026-10-11: the same result.
 */
class MapArtConfigCompatTest {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    @Test
    void removedHealthDisconnectKeysAreIgnored() {
        // written by the build before 2026-10-11: safety.disconnectHealth and safety.starvationDisconnect existed
        String old = """
            {
              "enabled": true,
              "paused": true,
              "pauseReason": "SAFETY STOP: starving: health 6/20, hunger 0/20, no food in the inventory",
              "safety": {
                "teleportGuard": true,
                "maxDistanceFromBase": 300,
                "disconnectHealth": 4,
                "starvationDisconnect": true,
                "startGraceTicks": 120
              },
              "build": { "eatFoodLevel": 12 }
            }
            """;
        MapArtConfig c = GSON.fromJson(old, MapArtConfig.class);
        assertTrue(c.enabled);
        assertTrue(c.paused);
        assertTrue(c.pauseReason.startsWith("SAFETY STOP: starving"));
        assertEquals(300, c.safety.maxDistanceFromBase);
        assertEquals(120, c.safety.startGraceTicks);
        assertEquals(12, c.build.eatFoodLevel);

        String saved = GSON.toJson(c);
        assertFalse(saved.contains("disconnectHealth"), saved);
        assertFalse(saved.contains("starvationDisconnect"), saved);
        assertEquals(300, GSON.fromJson(saved, MapArtConfig.class).safety.maxDistanceFromBase);
    }

    @Test
    void noGoZonesAndProtectedRegionsAreOptional() {
        // a site saved before 2026-10-11 (Phase 0): no noGoZones, no protectedRegions, an old protectedBlocks list
        String old = """
            { "site": { "protectedBlocks": ["minecraft:obsidian"], "storage": { "materialRegions": [
                { "min": {"x": 0, "y": 60, "z": 0}, "max": {"x": 5, "y": 62, "z": 5} } ] } } }
            """;
        MapArtConfig c = GSON.fromJson(old, MapArtConfig.class);
        assertTrue(c.site.noGoZones.isEmpty());
        assertTrue(c.site.protectedRegions.isEmpty());
        assertTrue(c.site.storage.materialRegions.getFirst().contains(new io.github.digitalisp.mapart.model.Pos(5, 61, 0)));
        // the machinery types are protected anyway
        assertTrue(SiteRules.protectedIds(c.site).contains("minecraft:tripwire"));
        assertTrue(SiteRules.protectedIds(c.site).contains("minecraft:obsidian"));

        String now = """
            { "site": {
                "noGoZones": [ { "name": "muncher", "min": {"x": 10, "y": 80, "z": 5}, "max": {"x": -3, "y": 70, "z": 9},
                                 "_why": "comments in a box are ignored" } ],
                "protectedRegions": [ { "min": {"x": 1, "y": 2, "z": 3}, "max": {"x": 1, "y": 2, "z": 3} } ] } }
            """;
        MapArtConfig n = GSON.fromJson(now, MapArtConfig.class);
        MapArtConfig.RegionCfg zone = n.site.noGoZones.getFirst();
        assertEquals("muncher", zone.name);
        assertTrue(zone.contains(-3, 70, 9), "corners in any order");
        assertTrue(zone.contains(10, 80, 5));
        assertFalse(zone.contains(11, 75, 7));
        assertTrue(SiteRules.protectedRegionAt(n.site, new io.github.digitalisp.mapart.model.Pos(1, 2, 3)) != null);
        assertEquals(zone, SiteRules.noGoZoneAt(n.site, new io.github.digitalisp.mapart.model.Pos(0, 75, 7)));
        MapArtConfig back = GSON.fromJson(GSON.toJson(n), MapArtConfig.class);
        assertEquals("muncher", back.site.noGoZones.getFirst().name);
        assertEquals(1, back.site.protectedRegions.size());
    }

    @Test
    void theMapCounterIsOptionalAndItsColumnRoundTrips() {
        // written before the map counter existed (2026-10-11): off
        MapArtConfig old = GSON.fromJson("{ \"site\": { \"anvil\": {\"x\": 1, \"y\": 2, \"z\": 3} } }", MapArtConfig.class);
        assertEquals(null, old.site.mapCounter);
        assertEquals(null, old.site.mapCounterColumn);
        String saved = GSON.toJson(old);
        assertFalse(saved.contains("\"mapCounter\"") || saved.contains("\"mapCounterColumn\""),
            "null fields are not written: " + saved);
        // the login wait and the jam check (2026-10-11) get their defaults
        assertEquals(10, old.site.mapCounterLoginDelaySeconds);
        assertEquals(4, old.site.mapCounterCheckRadius);

        // the owner's form: the column only, the y found at run time
        MapArtConfig c = GSON.fromJson("""
            { "site": { "mapCounter": { "mode": "BUTTON" }, "mapCounterColumn": { "x": 50, "z": -70 } } }
            """, MapArtConfig.class);
        assertEquals("BUTTON", c.site.mapCounter.mode);
        assertEquals(null, c.site.mapCounter.pos);
        assertEquals(50, c.site.mapCounterColumn.x);
        assertEquals(-70, c.site.mapCounterColumn.z);
        String back = GSON.toJson(c);
        assertFalse(back.contains("\"y\""), "a column has no y to write back: " + back);
        assertEquals(-70, GSON.fromJson(back, MapArtConfig.class).site.mapCounterColumn.z);
    }

    @Test
    void discordVerboseIsOffUnlessSaved() {
        // written before discord.verbose existed (2026-10-11): off, so the channel gets only alerts and milestones
        MapArtConfig old = GSON.fromJson("{ \"discord\": { \"enabled\": true, \"progressIntervalMinutes\": 30 } }",
            MapArtConfig.class);
        assertTrue(old.discord.enabled);
        assertEquals(30, old.discord.progressIntervalMinutes);
        assertFalse(old.discord.verbose);
        assertFalse(new MapArtConfig().discord.verbose);
        old.discord.verbose = true;
        assertTrue(GSON.fromJson(GSON.toJson(old), MapArtConfig.class).discord.verbose, "round trip");
    }

    @Test
    void keepAliveDefaultsToSixtySecondsAndZeroStaysOff() {
        // written before build.keepAliveSeconds existed (2026-10-11): the keep-alive is on, every 60 s
        MapArtConfig old = GSON.fromJson("{ \"build\": { \"eatFoodLevel\": 12 } }", MapArtConfig.class);
        assertEquals(60, old.build.keepAliveSeconds);
        assertEquals(60, new MapArtConfig().build.keepAliveSeconds);
        MapArtConfig off = GSON.fromJson("{ \"build\": { \"keepAliveSeconds\": 0 } }", MapArtConfig.class);
        assertEquals(0, off.build.keepAliveSeconds);
        assertEquals(0, GSON.fromJson(GSON.toJson(off), MapArtConfig.class).build.keepAliveSeconds, "round trip");
    }

    @Test
    void configsWithoutFoodItemsGetTheDefaultFoods() {
        // written before build.foodItems existed (2026-10-11)
        MapArtConfig c = GSON.fromJson("{ \"build\": { \"foodToKeep\": 16 } }", MapArtConfig.class);
        assertEquals(16, c.build.foodToKeep);
        assertEquals(java.util.List.of("minecraft:golden_carrot", "minecraft:cooked_beef", "minecraft:bread"),
            c.build.foodItems);
        MapArtConfig custom = GSON.fromJson("{ \"build\": { \"foodItems\": [\"minecraft:bread\"] } }", MapArtConfig.class);
        assertEquals(java.util.List.of("minecraft:bread"), custom.build.foodItems);
    }

    @Test
    void configsWithoutBreakModeFastNuke() {
        // written before build.breakMode existed (2026-10-11): the fast nuke with its safe settings
        MapArtConfig c = GSON.fromJson("{ \"build\": { \"reach\": 4.3, \"toolSafetyFloor\": 30 } }", MapArtConfig.class);
        assertEquals("FAST", c.build.breakMode);
        assertEquals(4.3, c.build.reach);
        assertEquals(30, c.build.toolSafetyFloor);
        assertEquals(1, c.build.nukeStartsPerTick);
        assertTrue(c.build.nukePairSlow);
        assertEquals(5, c.build.nukeStopDelayTicks);
        assertEquals(-1, c.build.nukeReach);
        assertTrue(c.build.nukeSweep);
        assertTrue(c.site.hasteZones.isEmpty());
        MapArtConfig noBuild = GSON.fromJson("{ \"enabled\": true }", MapArtConfig.class);
        assertEquals("FAST", noBuild.build.breakMode);
    }

    @Test
    void breakModeDoubleAndHasteZonesRoundTrip() {
        MapArtConfig c = GSON.fromJson("{ \"build\": { \"breakMode\": \"DOUBLE\", \"nukeStartsPerTick\": 1 },"
            + " \"site\": { \"hasteZones\": [ { \"min\": {\"x\": -131, \"y\": 128, \"z\": -52},"
            + " \"max\": {\"x\": -30, \"y\": 320, \"z\": 49}, \"name\": \"west beacon\" } ] } }", MapArtConfig.class);
        assertEquals("DOUBLE", c.build.breakMode);
        assertEquals(1, c.build.nukeStartsPerTick);
        assertEquals(1, c.site.hasteZones.size());
        assertTrue(c.site.hasteZones.getFirst().contains(-64, 173, 0));
        MapArtConfig again = GSON.fromJson(GSON.toJson(c), MapArtConfig.class);
        assertEquals("west beacon", again.site.hasteZones.getFirst().name);
    }

    @Test
    void breakModeSingleRoundTrips() {
        MapArtConfig c = GSON.fromJson("{ \"build\": { \"breakMode\": \"SINGLE\" } }", MapArtConfig.class);
        assertEquals("SINGLE", c.build.breakMode);
        String saved = GSON.toJson(c);
        assertTrue(saved.contains("\"breakMode\": \"SINGLE\""), saved);
        assertEquals("SINGLE", GSON.fromJson(saved, MapArtConfig.class).build.breakMode);
    }
}
