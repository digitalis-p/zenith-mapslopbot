package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot side of colour suppression (docs/COLOR_SUPPRESSION.md): zones cut by the openings, the lane's stand area. */
class SuppressionBotTest {
    static MapArtConfig.RegionCfg box(int x0, int y0, int z0, int x1, int y1, int z1) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = new Pos(x0, y0, z0);
        r.max = new Pos(x1, y1, z1);
        r.name = "layers";
        return r;
    }

    static MapArtConfig.PlatformCfg cs() {
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = "cs";
        p.kind = "COLOR_SUPPRESSION";
        p.minX = -64;
        p.minZ = -64;
        p.glassY = List.of(200, 214);
        p.access.add(box(-76, 201, -70, -56, 204, -66));
        return p;
    }

    @Test
    void openingsCutTheZonesUnderTheirLabels() {
        List<MapArtConfig.RegionCfg> zones = List.of(box(-74, 198, -74, 80, 245, 71));
        NoGoZones closed = NoGoZones.of(zones);
        NoGoZones open = NoGoZones.of(zones, SiteRules.suppressionOpenings(cs()));
        assertNotNull(closed.at(0, 201, 0));
        assertNull(open.at(0, 201, 0), "feet on the bottom glass, head above: open");
        assertNull(open.at(0, 215, 0));
        assertNull(open.at(-60, 202, -68), "the access path");
        assertNotNull(open.at(0, 208, 0), "between the layers: still closed");
        assertNull(open.at(0, 204, 0), "feet y204, head y205: still in the art volume");
        assertNotNull(open.at(0, 205, 0), "head in the machinery at y206: closed");
        assertNotNull(open.at(70, 201, 0), "east of the tile: closed");
        assertEquals("#1 \"layers\"", open.at(0, 208, 0).label());
        assertNull(open.onMove(new Pos(0, 201, 0), new Pos(1, 201, 0)));
        assertNotNull(open.onMove(new Pos(63, 201, 0), new Pos(64, 201, 0)), "off the east edge");
        assertTrue(NoGoZones.of(zones, List.of()).zones().size() == 1);
        assertTrue(NoGoZones.of(zones, null).zones().size() == 1);
    }

    @Test
    void nearTheLayersAPathMayNotDropFurtherThanTheLimitNotEvenIntoWater() {
        NoGoZones none = NoGoZones.NONE.withMaxDrop(3);
        assertTrue(none.checks());
        assertNull(none.pathViolation(List.of(new Pos(0, 201, 0), new Pos(1, 201, 0), new Pos(2, 198, 0)), null));
        String v = none.pathViolation(List.of(new Pos(0, 201, 0), new Pos(1, 201, 0), new Pos(1, 174, 0)), null);
        assertNotNull(v);
        assertTrue(v.startsWith("drops 27 blocks at 1,174,0"), v);
        assertNull(NoGoZones.NONE.pathViolation(List.of(new Pos(0, 201, 0), new Pos(1, 174, 0)), null), "no limit");
        assertTrue(!NoGoZones.NONE.checks());
    }

    @Test
    void lanesOnTheSuppressionLayersStandOnTheirTileAndNoobline() {
        MapArtConfig config = new MapArtConfig();
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.minX = -64;
        moss.minZ = -64;
        moss.artY = 173;
        config.site.platforms.add(moss);
        config.site.platforms.add(cs());
        for (int y : new int[]{201, 202, 215, 216}) {
            Lane l = new Lane(0, new Pos(4, y, -64), new Pos(4, y, 63),
                List.of(new Placement(new Pos(4, y, 10), BlockSpec.of("minecraft:white_concrete"), "minecraft:white_concrete")));
            assertEquals(new LaneRun.Area(-64, -65, 63, 63), PlaceLaneTask.standArea(config, l), "y" + y);
        }
        Lane noob = new Lane(0, new Pos(4, 215, -65), new Pos(4, 215, -65),
            List.of(new Placement(new Pos(4, 215, -65), BlockSpec.of("minecraft:moss_block"), "minecraft:moss_block")));
        assertEquals(new LaneRun.Area(-64, -65, 63, 63), PlaceLaneTask.standArea(config, noob));
        Lane mossLane = new Lane(0, new Pos(4, 173, -64), new Pos(4, 173, 63),
            List.of(new Placement(new Pos(4, 173, 10), BlockSpec.of("minecraft:white_carpet"), "minecraft:white_carpet")));
        assertEquals(new LaneRun.Area(-64, -64, 63, 63), PlaceLaneTask.standArea(config, mossLane));
    }
}
