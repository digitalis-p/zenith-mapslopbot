package io.github.digitalisp.mapart.config;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRulesTest {
    private static MapArtConfig.RegionCfg box(String name, Pos a, Pos b) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.name = name;
        r.min = a;
        r.max = b;
        return r;
    }

    @Test
    void protectedIdsJoinTheListTheMachineryAndEveryFloor() {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        site.protectedBlocks = new ArrayList<>(List.of(" OBSIDIAN ", "minecraft:chest[type=left]"));
        MapArtConfig.PlatformCfg soul = new MapArtConfig.PlatformCfg();
        soul.floorBlock = "soul_sand";
        site.platforms.add(soul);
        Set<String> ids = SiteRules.protectedIds(site);
        assertTrue(ids.contains("minecraft:obsidian"), ids.toString());
        assertTrue(ids.contains("minecraft:chest"), ids.toString());
        assertTrue(ids.contains("minecraft:soul_sand"), ids.toString());
        for (String m : Defaults.MACHINERY_BLOCKS) assertTrue(ids.contains(m), m);
        assertFalse(ids.contains("minecraft:redstone_block"), "an art block: protected by position only");
        assertFalse(ids.contains("minecraft:decorated_pot"), "an art block: protected by position only");
    }

    @Test
    void machineryIsInTheDefaultsAndNeverAnArtBlock() {
        assertTrue(Defaults.PROTECTED_BLOCKS.containsAll(Defaults.MACHINERY_BLOCKS));
        for (String m : Defaults.MACHINERY_BLOCKS) {
            assertFalse(Defaults.PISTON_CLEARABLE.contains(m), m);
            assertFalse(Defaults.NUKE_BLOCKS.contains(m), m);
        }
        assertTrue(Defaults.MACHINERY_BLOCKS.containsAll(List.of("minecraft:tripwire", "minecraft:note_block",
            "minecraft:spruce_button", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
            "minecraft:redstone_wire", "minecraft:repeater", "minecraft:wither_skeleton_skull")));
    }

    @Test
    void regionsMatchCellsAndBoxesWithCornersInAnyOrder() {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.RegionCfg muncher = box("muncher", new Pos(10, 80, 5), new Pos(-3, 70, 9));
        MapArtConfig.RegionCfg incomplete = box("broken", new Pos(0, 0, 0), null);
        site.noGoZones.add(incomplete);
        site.noGoZones.add(muncher);
        assertSame(muncher, SiteRules.noGoZoneAt(site, new Pos(0, 75, 7)));
        assertNull(SiteRules.noGoZoneAt(site, new Pos(0, 0, 0)), "an incomplete box matches nothing");
        assertNull(SiteRules.protectedRegionAt(site, new Pos(0, 75, 7)));
        assertSame(muncher, SiteRules.regionOverlapping(site.noGoZones, new Pos(-10, 60, 0), new Pos(-3, 70, 5)));
        assertNull(SiteRules.regionOverlapping(site.noGoZones, new Pos(-10, 60, 0), new Pos(-4, 70, 5)));
        assertEquals("#2 \"muncher\" (-3,70,5 to 10,80,9)", SiteRules.describe(site.noGoZones, muncher));
        assertEquals("#1 \"broken\" (incomplete)", SiteRules.describe(site.noGoZones, incomplete));
    }
}
