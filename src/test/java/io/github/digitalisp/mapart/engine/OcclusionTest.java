package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.engine.SimFakes.FakeWorld;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Occlusion#survey} against the 1.21.4 catalog (ZenithProxy's generated registries): a map pixel shows the
 * topmost block with a map colour, so plain glass (the colour-suppression platform's layers) is transparent and
 * stained glass, concrete or wool above the art cover it. Synthetic coordinates: the art layer at y64, the platform's
 * glass 27 and 41 above it (y200 and y214 over the moss art at y173 on 2b2t).
 */
class OcclusionTest {
    private static final ZenithBlockCatalog CATALOG = new ZenithBlockCatalog();
    private static final PlatformGeometry GEO = new PlatformGeometry(-64, -64, 64);
    private static final int GLASS = 64 + 27;
    private static final int UPPER_GLASS = 64 + 41;

    private static Pos at(int px, int pz, int y) {
        return new Pos(GEO.minX() + px, y, GEO.minZ() + pz);
    }

    /** The art layer full of white carpet, and both plain glass layers over the map and the row north of it. */
    private static FakeWorld platform() {
        FakeWorld w = new FakeWorld();
        for (int pz = -1; pz < 128; pz++)
            for (int px = 0; px < 128; px++) {
                if (pz >= 0) w.set(at(px, pz, GEO.artY()), BlockSpec.of("minecraft:white_carpet"));
                w.set(at(px, pz, GLASS), BlockSpec.of("minecraft:glass"));
                w.set(at(px, pz, UPPER_GLASS), BlockSpec.of("minecraft:glass"));
            }
        return w;
    }

    private static Occlusion survey(FakeWorld w) {
        return Occlusion.survey(GEO, CATALOG, w);
    }

    @Test
    void theCatalogKnowsWhatIsInvisibleOnMaps() {
        for (String id : List.of("minecraft:glass", "minecraft:glass_pane", "minecraft:lever", "minecraft:stone_button",
            "minecraft:air", "minecraft:barrier"))
            assertEquals(0, CATALOG.mapColorId(BlockSpec.of(id)), id + " is MapColor NONE (the Sim catalog agrees)");
        for (String id : List.of("minecraft:white_stained_glass", "minecraft:blue_stained_glass",
            "minecraft:white_concrete", "minecraft:red_wool", "minecraft:water"))
            assertTrue(CATALOG.mapColorId(BlockSpec.of(id)) > 0, id + " shows on a map");
    }

    @Test
    void plainGlassLayersCoverNothing() {
        Occlusion o = survey(platform());
        assertFalse(o.any(), o.describe());
        assertEquals(0, o.covered());
        assertEquals(0, o.unloaded());
        assertTrue(o.levels().isEmpty());
        assertEquals("0 pixels covered", o.describe());
    }

    @Test
    void stainedGlassAndBlocksOnTheGlassCoverTheirPixels() {
        FakeWorld w = platform();
        for (int px = 0; px < 40; px++) w.set(at(px, 10, GLASS + 1), BlockSpec.of("minecraft:white_concrete"));
        for (int px = 0; px < 10; px++) w.set(at(px, 20, GLASS), BlockSpec.of("minecraft:white_stained_glass"));
        Occlusion o = survey(w);
        assertTrue(o.any());
        assertEquals(50, o.covered());
        assertEquals(0, o.northCovered());
        assertEquals(Map.of(GLASS, 10, GLASS + 1, 40), o.levels());
        assertEquals(List.of("minecraft:white_concrete", "minecraft:white_stained_glass"), List.copyOf(o.blocks().keySet()));
        assertEquals("50 pixels covered by blocks at y91 and y92 (e.g. white_concrete, white_stained_glass)", o.describe());
    }

    @Test
    void theTopmostColouredBlockOfAColumnCountsOnce() {
        FakeWorld w = platform();
        w.set(at(5, 5, GLASS + 1), BlockSpec.of("minecraft:red_wool"));
        w.set(at(5, 5, UPPER_GLASS), BlockSpec.of("minecraft:blue_stained_glass")); // replaces the upper glass
        w.set(at(5, 5, UPPER_GLASS + 3), BlockSpec.of("minecraft:glass"));         // transparent, above it
        Occlusion o = survey(w);
        assertEquals(1, o.covered());
        assertEquals(Map.of(UPPER_GLASS, 1), o.levels());
        assertEquals("1 pixel covered by blocks at y105 (e.g. blue_stained_glass)", o.describe());
    }

    @Test
    void theRowNorthOfTheMapShadesTheFirstRowSoItCountsApart() {
        FakeWorld w = platform();
        w.set(at(7, -1, GLASS + 1), BlockSpec.of("minecraft:red_wool"));
        Occlusion o = survey(w);
        assertTrue(o.any());
        assertEquals(0, o.covered());
        assertEquals(1, o.northCovered());
        assertEquals("1 block above the row north of the map at y92 (e.g. red_wool) (it shades the map's first row)",
            o.describe());
        w.set(at(7, 0, GLASS + 1), BlockSpec.of("minecraft:red_wool"));
        assertEquals("1 pixel covered by blocks at y92 (e.g. red_wool); 1 more block above the row north of the map"
            + " (it shades the first row)", survey(w).describe());
    }

    @Test
    void columnsNotLoadedAreCountedNotGuessed() {
        FakeWorld w = platform();
        w.set(at(100, 100, GLASS + 1), BlockSpec.of("minecraft:red_wool"));   // in a chunk that is not loaded
        w.set(at(10, 10, GLASS + 1), BlockSpec.of("minecraft:red_wool"));
        w.loaded = p -> p.x() < GEO.minX() + 64;                                 // the west half only
        Occlusion o = survey(w);
        assertEquals(1, o.covered(), "only what is loaded is read");
        assertEquals(64 * 129, o.unloaded());
        assertEquals("1 pixel covered by blocks at y92 (e.g. red_wool); 8,256 columns not loaded, not checked",
            o.describe());
    }

    @Test
    void manyLevelsAndBlocksAreSummarised() {
        FakeWorld w = platform();
        String[] ids = {"minecraft:red_wool", "minecraft:white_concrete", "minecraft:black_wool", "minecraft:dirt"};
        for (int i = 0; i < 5; i++)
            for (int px = 0; px <= i; px++) w.set(at(px, 30 + i, GLASS + 1 + i), BlockSpec.of(ids[i % ids.length]));
        Occlusion o = survey(w);
        assertEquals(15, o.covered());
        assertEquals("y92, y93, y94 and 2 more levels", o.levelsText());
        assertEquals("red_wool, dirt, black_wool", o.blocksText(), "most first, then by id");
        assertEquals("3,200 pixels", Occlusion.count(3200, "pixel"));
        assertEquals("1 column", Occlusion.count(1, "column"));
    }
}
