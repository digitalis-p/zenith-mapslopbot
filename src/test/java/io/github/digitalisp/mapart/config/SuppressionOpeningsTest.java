package io.github.digitalisp.mapart.config;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run-time openings of a colour-suppression job (docs/COLOR_SUPPRESSION.md) and the box arithmetic behind them. */
class SuppressionOpeningsTest {
    static MapArtConfig.PlatformCfg cs() {
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = "cs";
        p.kind = "COLOR_SUPPRESSION";
        p.minX = -64;
        p.minZ = -64;
        p.glassY = List.of(200, 214);
        MapArtConfig.RegionCfg a = new MapArtConfig.RegionCfg();
        a.min = new Pos(-76, 201, -70);
        a.max = new Pos(-56, 204, -66);
        a.name = "path";
        p.access.add(a);
        return p;
    }

    static MapArtConfig.RegionCfg box(int x0, int y0, int z0, int x1, int y1, int z1, String name) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = new Pos(x0, y0, z0);
        r.max = new Pos(x1, y1, z1);
        r.name = name;
        return r;
    }

    @Test
    void openingsAreBothArtVolumesAndTheAccessBoxes() {
        List<MapArtConfig.RegionCfg> o = SiteRules.suppressionOpenings(cs());
        assertEquals(3, o.size());
        assertEquals(new Pos(-64, 201, -66), o.get(0).min);
        assertEquals(new Pos(63, 205, 63), o.get(0).max);
        assertEquals(new Pos(-64, 215, -66), o.get(1).min);
        assertEquals(new Pos(63, 219, 63), o.get(1).max);
        assertEquals("path", o.get(2).name);
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        assertTrue(SiteRules.suppressionOpenings(moss).isEmpty());
        assertTrue(SiteRules.suppressionOpenings(null).isEmpty());
    }

    /** Live 2026-11-13: the leftovers chest lies in the machinery's protected region and was refused. */
    @Test
    void thePlatformsReturnChestOpensItsOwnCellOnly() {
        MapArtConfig.PlatformCfg p = cs();
        Pos chest = new Pos(5, 214, -71);
        p.returnChests.add(chest);
        List<MapArtConfig.RegionCfg> o = SiteRules.suppressionOpenings(p);
        assertEquals(4, o.size());
        assertEquals(chest, o.get(3).min);
        assertEquals(chest, o.get(3).max);
    }

    @Test
    void openingsLiftZonesAndProtectedRegionsOnlyWhereTheyAre() {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        site.noGoZones.add(box(-74, 198, -74, 80, 245, 71, "layers"));
        site.protectedRegions.add(box(-74, 195, -74, 80, 245, 71, "machinery"));
        Pos art = new Pos(0, 201, 0), machinery = new Pos(0, 208, 0), walkway = new Pos(-60, 202, -68);
        assertNotNull(SiteRules.noGoZoneAt(site, art));
        assertNotNull(SiteRules.protectedRegionAt(site, art));
        site.openings = SiteRules.suppressionOpenings(cs());
        assertNull(SiteRules.noGoZoneAt(site, art));
        assertNull(SiteRules.protectedRegionAt(site, art));
        assertNull(SiteRules.noGoZoneAt(site, walkway));
        assertNotNull(SiteRules.noGoZoneAt(site, machinery), "the machinery between the layers stays closed");
        assertNotNull(SiteRules.protectedRegionAt(site, machinery));
        site.openings = List.of();
        assertNotNull(SiteRules.noGoZoneAt(site, art));
    }

    @Test
    void subtractCoversExactlyTheRest() {
        Random r = new Random(7);
        for (int round = 0; round < 200; round++) {
            int[] a = randomBox(r);
            List<int[]> holes = List.of(randomBox(r), randomBox(r));
            List<int[]> parts = SiteRules.subtract(a, holes);
            Set<String> seen = new HashSet<>();
            for (int[] p : parts)
                for (int x = p[0]; x <= p[3]; x++)
                    for (int y = p[1]; y <= p[4]; y++)
                        for (int z = p[2]; z <= p[5]; z++) {
                            assertTrue(seen.add(x + "," + y + "," + z), "parts overlap");
                            assertTrue(in(a, x, y, z));
                            for (int[] h : holes) assertFalse(in(h, x, y, z), "a hole cell kept");
                        }
            for (int x = a[0]; x <= a[3]; x++)
                for (int y = a[1]; y <= a[4]; y++)
                    for (int z = a[2]; z <= a[5]; z++) {
                        boolean hole = false;
                        for (int[] h : holes) hole |= in(h, x, y, z);
                        assertEquals(!hole, seen.contains(x + "," + y + "," + z));
                    }
        }
    }

    private static int[] randomBox(Random r) {
        int x0 = r.nextInt(8), y0 = r.nextInt(8), z0 = r.nextInt(8);
        return new int[]{x0, y0, z0, x0 + r.nextInt(5), y0 + r.nextInt(5), z0 + r.nextInt(5)};
    }

    private static boolean in(int[] b, int x, int y, int z) {
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    @Test
    void openingsAreNeverSaved() {
        MapArtConfig c = new MapArtConfig();
        c.site.openings = SiteRules.suppressionOpenings(cs());
        String json = new com.google.gson.Gson().toJson(c);
        assertFalse(json.contains("openings"), json);
    }

    /** The colour-suppression restock switches are on by default (each false = the previous behaviour). */
    @Test
    void suppressionRestockSwitchesDefaultOn() {
        MapArtConfig.BuildCfg b = new MapArtConfig().build;
        assertTrue(b.suppressionLaneEndFallback);
        assertTrue(b.suppressionDropLeftovers);
        assertTrue(b.suppressionMendBeforeRide);
        assertTrue(b.suppressionFreshLanes);
    }

    /** {@code site.edgeSneakOff} is empty by default (the sneak applies everywhere) and a box covers its cells only. */
    @Test
    void edgeSneakOffDefaultsEmptyAndCoversItsBox() {
        assertTrue(new MapArtConfig().site.edgeSneakOff.isEmpty());
        List<MapArtConfig.RegionCfg> off = List.of(box(-81, 174, -90, -76, 194, -68, "stair"));
        assertNotNull(SiteRules.regionAt(off, -78, 180, -80));
        assertNull(SiteRules.regionAt(off, -78, 180, -67));
        assertNull(SiteRules.regionAt(off, -60, 180, -80));
    }

    /** {@code foot} (restock from the walkway) is off by default and survives a config round trip. */
    @Test
    void footDefaultsOffAndRoundTrips() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        assertNull(new MapArtConfig.PlatformCfg().foot);
        MapArtConfig.PlatformCfg p = cs();
        p.foot = new Pos(-78, 174, -67);
        MapArtConfig.PlatformCfg back = gson.fromJson(gson.toJson(p), MapArtConfig.PlatformCfg.class);
        assertEquals(new Pos(-78, 174, -67), back.foot);
    }
}
