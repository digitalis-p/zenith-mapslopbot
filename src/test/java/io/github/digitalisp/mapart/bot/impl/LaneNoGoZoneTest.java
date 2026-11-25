package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lane placer's stand spots ({@link LaneRun}, SPOT) are never in a no-go zone ({@link LaneRun.Io#standAllowed}). */
class LaneNoGoZoneTest {
    /** VERIFY of a checkerboard whose full blocks are all placed: every carpet is a hole placed from a stand spot. */
    private static TileRunner holes(TileArt art, NoGoZones zones) {
        TileRunner r = new TileRunner(12);
        for (int x = 30; x < 60; x++)
            for (int z = 20; z < 50; z++) {
                BlockSpec b = art.get(x, z);
                if (b.id().endsWith("terracotta")) r.world.put(r.geo.cell(x, z), b);
            }
        r.sim.standCells = p -> zones.at(p) == null;
        assertTrue(r.build(art, 1), "tile not built\n" + r.report(art));
        return r;
    }

    @Test
    void standSpotsAvoidAZoneAcrossTheTile() {
        TileArt art = TileRunner.checkerboard(30, 20, 60, 50);
        // art columns 40-41 (world x -24..-23) over the whole checkerboard, from the floor to a head on a full block
        MapArtConfig.RegionCfg box = new MapArtConfig.RegionCfg();
        box.name = "strip";
        box.min = new Pos(-24, 64, -44);
        box.max = new Pos(-23, 66, -15);
        NoGoZones zones = NoGoZones.of(List.of(box));

        TileRunner free = holes(art, NoGoZones.NONE);
        long usedBefore = free.sim.spotGoals.stream().filter(p -> zones.at(p) != null).count();
        assertTrue(usedBefore > 0, "without the zone some stand spots lie in the strip: " + free.sim.spotGoals.size());

        TileRunner r = holes(art, zones);
        assertEquals(List.of(), r.mismatches(art), r.report(art));
        assertEquals(0, r.failures, r.report(art));
        assertTrue(r.sim.skipReasons.keySet().stream().noneMatch(k -> k.contains("not reachable")), r.report(art));
        assertTrue(r.sim.spotGoals.size() > 0, r.report(art));
        for (Pos p : r.sim.spotGoals) assertEquals(null, zones.at(p), "stand spot in the no-go zone: " + p);
    }
}
