package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.Defaults;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 44 materials of the moss sorter, in the block state MapArtCraft (mike2b2t, 1.21.4) exports for each, checked
 * against the real 1.21.4 registry: known, placed by its own item, buildable by a top-face click, the map colour
 * MapArtCraft assumes (its mapdatId), and clearable on the moss platform with the default lists.
 * open_eyeblossom is not in MapArtCraft (it offers closed_eyeblossom); its plain state stands in.
 */
class StockedMaterialsTest {
    private enum Clear { PISTON, NUKE }

    private record Material(String state, int mapColor, Clear clear) { }

    private static final List<Material> STOCK = new ArrayList<>();

    private static void add(String state, int mapColor, Clear clear) {
        STOCK.add(new Material(state, mapColor, clear));
    }

    static {
        String[] carpets = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (int i = 0; i < carpets.length; i++)
            add("minecraft:" + carpets[i] + "_carpet", i == 0 ? 8 : 14 + i, Clear.PISTON);
        add("minecraft:terracotta", 15, Clear.NUKE);
        add("minecraft:white_terracotta", 36, Clear.NUKE);
        add("minecraft:orange_terracotta", 37, Clear.NUKE);
        add("minecraft:yellow_terracotta", 40, Clear.NUKE);
        add("minecraft:gray_terracotta", 43, Clear.NUKE);
        add("minecraft:light_gray_terracotta", 44, Clear.NUKE);
        add("minecraft:brown_terracotta", 48, Clear.NUKE);
        add("minecraft:red_terracotta", 50, Clear.NUKE);
        add("minecraft:black_terracotta", 51, Clear.NUKE);
        add("minecraft:birch_pressure_plate", 2, Clear.PISTON);
        add("minecraft:heavy_weighted_pressure_plate", 6, Clear.PISTON);
        add("minecraft:jungle_pressure_plate", 10, Clear.PISTON);
        add("minecraft:stone_pressure_plate", 11, Clear.PISTON);
        add("minecraft:oak_pressure_plate", 13, Clear.PISTON);
        add("minecraft:pale_oak_pressure_plate", 14, Clear.PISTON);
        add("minecraft:light_weighted_pressure_plate", 30, Clear.PISTON);
        add("minecraft:spruce_pressure_plate", 34, Clear.PISTON);
        add("minecraft:cherry_pressure_plate", 36, Clear.PISTON);
        add("minecraft:crimson_pressure_plate", 53, Clear.PISTON);
        add("minecraft:warped_pressure_plate", 56, Clear.PISTON);
        add("minecraft:birch_leaves[persistent=true]", 7, Clear.PISTON);
        add("minecraft:pink_petals", 7, Clear.PISTON);
        add("minecraft:mushroom_stem", 3, Clear.NUKE);
        add("minecraft:open_eyeblossom", 15, Clear.PISTON);
        add("minecraft:crimson_roots", 35, Clear.PISTON);
        add("minecraft:resin_clump[down=true]", 37, Clear.PISTON);
        add("minecraft:dripstone_block", 48, Clear.NUKE);
        add("minecraft:glow_lichen[down=true]", 61, Clear.PISTON);
    }

    private final ZenithBlockCatalog catalog = new ZenithBlockCatalog();

    @Test
    void everyStockedMaterialIsBuildableAndShowsMapArtCraftsColour() {
        assertEquals(44, STOCK.size());
        assertEquals(44, new TreeSet<>(STOCK.stream().map(m -> BlockSpec.parse(m.state()).id()).toList()).size());
        for (Material m : STOCK) {
            BlockSpec s = BlockSpec.parse(m.state());
            assertTrue(catalog.isKnownBlock(s.id()), m.state());
            assertEquals(Optional.of(s.id()), catalog.placementItem(s), m.state());
            assertTrue(catalog.placeableOnTopFace(s), m.state());
            assertEquals(m.mapColor(), catalog.mapColorId(s), m.state());
            boolean piston = Defaults.PISTON_CLEARABLE.contains(s.id());
            boolean nuke = Defaults.NUKE_BLOCKS.contains(s.id());
            assertEquals(m.clear() == Clear.PISTON, piston, m.state() + " piston-clearable");
            assertEquals(m.clear() == Clear.NUKE, nuke, m.state() + " nuke");
        }
    }

    @Test
    void artUsingAllOfThemPassesUploadValidationForTheMossPlatform() {
        TileArt art = Tiles.of((x, z) -> STOCK.get((x / 3 + z) % STOCK.size()).state());
        ParsedSubmission sub = new ParsedSubmission("all44", 1, 1, DefaultSubmissionService.DATA_VERSION_1_21_4,
            new TreeMap<>(Map.of(new TileCoord(0, 0), art)), List.of());
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        moss.kind = "MOSS";
        moss.enabled = true;
        site.platforms.add(moss);
        ValidationResult v = new DefaultSubmissionService().validate(sub, catalog, site);
        assertEquals(List.of(), v.errors());
        assertEquals(List.of(), v.warnings());
        assertEquals(List.of("moss"), v.platformIds());
        Map<String, Integer> items = new LinkedHashMap<>(v.materials());
        assertEquals(44, items.size(), items.toString());
        assertEquals(16384, items.values().stream().mapToInt(Integer::intValue).sum());
    }
}
