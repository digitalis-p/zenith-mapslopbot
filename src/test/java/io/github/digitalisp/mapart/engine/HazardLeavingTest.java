package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.NoGoZones;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.SiteRules;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

/** A walk that starts in a lethal-edge margin may leave it (the bot was pushed or overshot into it). */
class HazardLeavingTest {
    @Test
    void aPathOutOfTheMarginItStartedInIsAllowed() {
        MapArtConfig.PlatformCfg p = EdgeHazardsTest.platform();
        p.glassY = List.of(200, 214);
        List<MapArtConfig.RegionCfg> hazards = EdgeHazards.compute(EdgeHazardsTest.platform(), EdgeHazardsTest.phiBottomLayer()).zones();
        MapArtConfig.RegionCfg layers = new MapArtConfig.RegionCfg();
        layers.min = new Pos(-74, 198, -74);
        layers.max = new Pos(80, 245, 71);
        NoGoZones z = NoGoZones.of(List.of(layers), SiteRules.suppressionOpenings(p), hazards);
        Pos start = new Pos(-42, 201, 63);
        NoGoZones.Zone leaving = z.at(start);
        List<Pos> path = List.of(start, new Pos(-42, 201, 62), new Pos(-42, 202, 61), new Pos(-42, 202, 60));
        assertNull(z.pathViolation(path, leaving), "leaving " + leaving);
    }
}
