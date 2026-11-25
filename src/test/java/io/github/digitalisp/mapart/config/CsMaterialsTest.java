package io.github.digitalisp.mapart.config;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The colour-suppression platform's own material chests, index row and palette (docs/COLOR_SUPPRESSION.md). */
class CsMaterialsTest {
    static MapArtConfig.RegionCfg box(int x0, int y0, int z0, int x1, int y1, int z1) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = new Pos(x0, y0, z0);
        r.max = new Pos(x1, y1, z1);
        return r;
    }

    static MapArtConfig site() {
        MapArtConfig c = new MapArtConfig();
        c.site.storage.materialRegions.add(box(-10, 74, 30, 10, 78, 32)); // a sorter elsewhere
        MapArtConfig.PlatformCfg cs = new MapArtConfig.PlatformCfg();
        cs.id = "cs";
        cs.kind = "COLOR_SUPPRESSION";
        cs.materialRegions.add(box(-63, 216, -72, 0, 217, -71));
        cs.materialRow = box(-63, 215, -70, 0, 215, -70);
        c.site.platforms.add(cs);
        return c;
    }

    @Test
    void aColourSuppressionJobSeesOnlyItsChestsAndOthersNeverThem() {
        MapArtConfig c = site();
        MapArtConfig.PlatformCfg cs = c.site.platforms.getFirst();
        Pos csChest = new Pos(-40, 216, -71), sorter = new Pos(0, 75, 31);
        assertTrue(CsMaterials.inScope(c.site, cs, csChest));
        assertFalse(CsMaterials.inScope(c.site, cs, sorter), "a CS job never restocks from the sorters");
        assertFalse(CsMaterials.inScope(c.site, null, csChest), "other jobs never use the CS chests");
        assertTrue(CsMaterials.inScope(c.site, null, sorter));
        assertEquals(2, CsMaterials.allRegions(c.site).size(), "discovery covers both");
    }

    @Test
    void withoutOwnRegionsNothingChanges() {
        MapArtConfig c = site();
        c.site.platforms.getFirst().materialRegions.clear();
        assertTrue(CsMaterials.inScope(c.site, c.site.platforms.getFirst(), new Pos(0, 75, 31)));
        assertTrue(CsMaterials.inScope(c.site, null, new Pos(-40, 216, -71)));
    }

    @Test
    void theStockIndexQueriesFollowTheScope() {
        MapArtConfig c = site();
        MapArtConfig.PlatformCfg cs = c.site.platforms.getFirst();
        StockIndex st = new StockIndex();
        st.update(new Pos(-40, 216, -71), Map.of("minecraft:oak_planks", 100), 0, 27, 1L);
        st.update(new Pos(0, 75, 31), Map.of("minecraft:oak_planks", 5, "minecraft:white_carpet", 9), 0, 27, 1L);
        st.setScope(p -> CsMaterials.inScope(c.site, cs, p));
        assertEquals(100, st.total("minecraft:oak_planks"));
        assertEquals(List.of(new Pos(-40, 216, -71)), st.containersWith("minecraft:oak_planks"));
        assertTrue(st.unstocked(List.of("minecraft:white_carpet")).contains("minecraft:white_carpet"));
        st.setScope(p -> CsMaterials.inScope(c.site, null, p));
        assertEquals(5, st.total("minecraft:oak_planks"));
        assertEquals(Map.of("minecraft:oak_planks", 95), st.shortfall(Map.of("minecraft:oak_planks", 100)));
        st.setScope(null);
        assertEquals(105, st.total("minecraft:oak_planks"));
    }

    @Test
    void theIndexRowNamesThePaletteAndItsColumns() {
        MapArtConfig c = site();
        Map<Pos, String> w = new java.util.HashMap<>();
        String[] floor = {"minecraft:birch_planks", "minecraft:tnt[unstable=false]", "minecraft:bamboo_block[axis=x]"};
        for (int i = 0; i < floor.length; i++) w.put(new Pos(-63 + i, 215, -70), floor[i]);
        MapArtConfig.RegionCfg row = box(-63, 215, -70, -61, 215, -70);
        Map<String, Integer> m = CsMaterials.row(row, w::get);
        assertEquals(Map.of("minecraft:birch_planks", -63, "minecraft:tnt", -62, "minecraft:bamboo_block", -61), m);
        assertNull(CsMaterials.row(row, p -> null), "a row out of view is unknown");
        Set<String> allowed = CsMaterials.allowed(c.site.platforms.getFirst(), m.keySet());
        assertTrue(CsMaterials.refused(List.of("minecraft:tnt", "minecraft:bamboo_block[axis=x]"), allowed).isEmpty());
        assertEquals(Set.of("minecraft:diorite"), CsMaterials.refused(List.of("minecraft:tnt", "minecraft:diorite"), allowed));
    }

    @Test
    void theConfiguredListIsUsedWhenTheRowIsUnknownAndNothingMeansNoRestriction() {
        MapArtConfig.PlatformCfg cs = site().site.platforms.getFirst();
        assertNull(CsMaterials.allowed(cs, null));
        assertTrue(CsMaterials.refused(List.of("minecraft:diorite"), null).isEmpty());
        cs.allowedBlocks = List.of("minecraft:moss_block");
        assertEquals(Set.of("minecraft:moss_block"), CsMaterials.allowed(cs, null));
        assertEquals(Set.of("minecraft:diorite"), CsMaterials.refused(List.of("minecraft:diorite", "minecraft:moss_block"),
            CsMaterials.allowed(cs, Set.of())));
    }
}
