package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.NoGoZones;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lethal edges of a colour-suppression floor (docs/COLOR_SUPPRESSION.md), on a model of the owner's bottom layer: glass at y200
 * over the tile and its noobline row, snow 27 blocks down to the south (lethal), stained glass one block down to the
 * east (safe), the walkway's edge blocks at the art level to the north (a wall), and to the west the clearing units'
 * walls on some rows, water two blocks down on others and nothing on the rest (lethal).
 */
class EdgeHazardsTest {
    static final BlockSpec GLASS = BlockSpec.of("minecraft:glass");

    static MapArtConfig.PlatformCfg platform() {
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = "cs";
        p.kind = "COLOR_SUPPRESSION";
        p.minX = -64;
        p.minZ = -64;
        p.glassY = List.of(200);
        return p;
    }

    static final class World implements EdgeHazards.Blocks {
        final Map<Pos, BlockSpec> blocks = new HashMap<>();
        final Set<Long> unloadedColumns = new java.util.HashSet<>();

        @Override
        public boolean loaded(int x, int y, int z) {
            return !unloadedColumns.contains(((long) x << 32) ^ (z & 0xffffffffL));
        }

        @Override
        public BlockSpec block(int x, int y, int z) {
            return blocks.getOrDefault(new Pos(x, y, z), BlockSpec.AIR);
        }
    }

    static World phiBottomLayer() {
        World w = new World();
        for (int z = -65; z < 64; z++)
            for (int x = -64; x < 64; x++) w.blocks.put(new Pos(x, 200, z), GLASS);
        for (int x = -66; x < 66; x++) {
            w.blocks.put(new Pos(x, 173, 64), BlockSpec.of("minecraft:snow_block"));               // south: 27 down
            w.blocks.put(new Pos(x, 201, -66), BlockSpec.of("minecraft:white_glazed_terracotta")); // north: walkway edge
            for (int z = -71; z <= -67; z++) w.blocks.put(new Pos(x, 201, z), BlockSpec.of("minecraft:white_stained_glass"));
        }
        for (int z = -66; z < 66; z++) w.blocks.put(new Pos(64, 199, z), BlockSpec.of("minecraft:white_stained_glass"));
        for (int z = -65; z < 64; z++) {
            if (z % 4 == 0) {
                w.blocks.put(new Pos(-65, 201, z), BlockSpec.of("minecraft:piston"));
                w.blocks.put(new Pos(-65, 202, z), BlockSpec.of("minecraft:piston"));
            } else if (z < 0) w.blocks.put(new Pos(-65, 198, z), BlockSpec.of("minecraft:water"));
            else if (z < 20) w.blocks.put(new Pos(-65, 198, z), BlockSpec.of("minecraft:stone"));
        }
        return w;
    }

    @Test
    void onlyTheLethalEdgesAreClosedOneBlockEachSide() {
        MapArtConfig.PlatformCfg p = platform();
        EdgeHazards.Result r = EdgeHazards.compute(p, phiBottomLayer());
        assertTrue(r.complete());
        NoGoZones z = NoGoZones.of(r.zones());
        // south: the two edge rows of the tile, the cell beyond, and the two after
        assertNotNull(z.at(0, 201, 63));
        assertNotNull(z.at(0, 201, 62));
        assertNotNull(z.at(0, 201, 64));
        assertNull(z.at(0, 201, 61));
        assertNotNull(z.at(0, 203, 63), "standing on a raised block at the edge too");
        // east: a one-block drop is safe
        assertNull(z.at(63, 201, 0));
        // north: the walkway's edge is a wall, the noobline row stays open
        assertNull(z.at(0, 201, -65));
        assertNull(z.at(0, 201, -64));
        assertNull(z.at(0, 201, -63));
        // west: rows with nothing below are closed, rows with a wall, a two-block drop or water two down... water is not
        // a safe landing, so those are closed too; rows with stone two down are open
        assertNotNull(z.at(-64, 201, 30), "nothing under the west edge");
        assertNotNull(z.at(-63, 201, 30), "two cells in");
        assertNull(z.at(-62, 201, 30));
        assertNull(z.at(-64, 201, 10), "stone two blocks down");
        assertNotNull(z.at(-64, 201, -10), "water is not a safe landing");
        assertNull(z.at(-63, 201, 10));
        assertTrue(r.zones().size() < 40, "merged into few boxes: " + r.zones().size());
    }

    @Test
    void anUnloadedColumnCountsAsLethalAndTheResultIsIncomplete() {
        World w = phiBottomLayer();
        w.unloadedColumns.add(((long) 64 << 32) ^ (0 & 0xffffffffL));
        EdgeHazards.Result r = EdgeHazards.compute(platform(), w);
        assertFalse(r.complete());
        assertNotNull(NoGoZones.of(r.zones()).at(63, 201, 0));
    }

    @Test
    void hazardsAreNeverOpenedByTheOpenings() {
        MapArtConfig.PlatformCfg p = platform();
        p.glassY = List.of(200, 214);
        List<MapArtConfig.RegionCfg> hazards = EdgeHazards.compute(platform(), phiBottomLayer()).zones();
        MapArtConfig.RegionCfg layers = new MapArtConfig.RegionCfg();
        layers.min = new Pos(-74, 198, -74);
        layers.max = new Pos(80, 245, 71);
        NoGoZones z = NoGoZones.of(List.of(layers), SiteRules.suppressionOpenings(p), hazards);
        assertNull(z.at(0, 201, 0));
        assertNotNull(z.at(0, 201, 63), "the south edge row stays closed inside the art volume");
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        site.noGoZones.add(layers);
        site.openings = SiteRules.suppressionOpenings(p);
        site.hazards = hazards;
        assertNotNull(SiteRules.noGoZoneAt(site, new Pos(0, 201, 63)));
        assertNull(SiteRules.noGoZoneAt(site, new Pos(0, 201, 0)));
    }

    @Test
    void repairsLiftTheTypeProtectionOnlyForTheSortersBlocksInTheArtCells() {
        MapArtConfig.PlatformCfg p = platform();
        p.glassY = List.of(200, 214);
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        Set<String> ids = SiteRules.protectedIds(site);
        assertTrue(ids.contains("minecraft:moss_block"));
        Pos art = new Pos(0, 216, 5);
        assertTrue(SiteRules.protectedAt(site, ids, art, "minecraft:moss_block"));
        site.repairable = SiteRules.suppressionArtCells(p);
        assertFalse(SiteRules.protectedAt(site, ids, art, "minecraft:moss_block"));
        assertFalse(SiteRules.protectedAt(site, ids, new Pos(0, 201, -65), "minecraft:moss_block"), "noobline row");
        assertTrue(SiteRules.protectedAt(site, ids, new Pos(0, 214, 5), "minecraft:glass"), "never the glass");
        assertTrue(SiteRules.protectedAt(site, ids, new Pos(0, 217, 5), "minecraft:moss_block"), "above the art cells");
        assertTrue(SiteRules.protectedAt(site, ids, art, "minecraft:tripwire"), "machinery types stay protected");
        assertEquals(2, site.repairable.size());
    }
}
