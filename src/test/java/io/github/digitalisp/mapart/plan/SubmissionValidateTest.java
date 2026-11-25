package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.Defaults;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.plan.SubmissionService.SubmissionException;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionValidateTest {
    private static final List<String> FOLIAGE =
        List.of("minecraft:pink_petals", "minecraft:crimson_roots", "minecraft:closed_eyeblossom",
            "minecraft:open_eyeblossom");

    private final DefaultSubmissionService svc = new DefaultSubmissionService();
    private final FakeCatalog catalog = new FakeCatalog();

    private static MapArtConfig.PlatformCfg platform(String id, String kind, boolean enabled) {
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = id;
        p.kind = kind;
        p.enabled = enabled;
        return p;
    }

    /** Moss with the default lists, soul sand with the defaults minus foliage. */
    static MapArtConfig.SiteCfg site(boolean moss, boolean soulSand) {
        MapArtConfig.SiteCfg s = new MapArtConfig.SiteCfg();
        s.platforms.add(platform("moss", "MOSS", moss));
        MapArtConfig.PlatformCfg ss = platform("soul_sand", "SOUL_SAND", soulSand);
        ss.floorBlock = "minecraft:soul_sand";
        ss.clearableBlocks.removeAll(FOLIAGE);
        s.platforms.add(ss);
        return s;
    }

    private static ParsedSubmission sub(int dataVersion, TileArt... tiles) {
        SortedMap<TileCoord, TileArt> m = new TreeMap<>();
        for (int i = 0; i < tiles.length; i++) m.put(new TileCoord(i, 0), tiles[i]);
        return new ParsedSubmission("t", tiles.length, 1, dataVersion, m, List.of());
    }

    private static TileArt mix(String a, int countA, String b) {
        return Tiles.of((x, z) -> z * 128 + x < countA ? a : b);
    }

    @Test
    void carpetArtFitsBothPlatforms() throws SubmissionException {
        ParsedSubmission p = svc.parse(List.of(Fixtures.file(Fixtures.A)));
        ValidationResult v = svc.validate(p, catalog, site(true, true));
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(List.of("moss", "soul_sand"), v.platformIds());
        assertEquals(16384, v.materials().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(8080, v.materials().get("minecraft:light_gray_carpet"));
        assertEquals("minecraft:light_gray_carpet", v.materials().keySet().iterator().next(), "most used first");
        assertEquals(1, v.warnings().size());
        assertTrue(v.warnings().getFirst().contains("DataVersion 4325"), v.warnings().getFirst());
    }

    @Test
    void foliageRestrictsTheJobToMoss() {
        ParsedSubmission p = sub(4189, mix("minecraft:pink_petals", 100, "minecraft:white_carpet"));
        ValidationResult v = svc.validate(p, catalog, site(true, true));
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(List.of("moss"), v.platformIds());
        assertEquals(List.of(), v.warnings());
        assertEquals(Map.of("minecraft:white_carpet", 16284, "minecraft:pink_petals", 100), v.materials());
    }

    @Test
    void foliageIsRejectedWhenOnlySoulSandIsEnabled() {
        ParsedSubmission p = sub(4189, mix("minecraft:pink_petals", 100, "minecraft:white_carpet"));
        ValidationResult v = svc.validate(p, catalog, site(false, true));
        assertFalse(v.ok());
        assertEquals(1, v.errors().size());
        String e = v.errors().getFirst();
        assertTrue(e.contains("Not clearable on any enabled platform (soul_sand;"), e);
        assertTrue(e.contains("minecraft:pink_petals ×100"), e);
        assertFalse(e.contains("white_carpet"), e);
        assertEquals(List.of(), v.platformIds());
    }

    @Test
    void fullBlocksNotOnTheNukeListAreRejectedWithCounts() throws SubmissionException {
        ParsedSubmission p = svc.parse(List.of(Fixtures.file(Fixtures.G)));
        ValidationResult v = svc.validate(p, catalog, site(true, true));
        assertEquals(1, v.errors().size(), v.errors().toString());
        String e = v.errors().getFirst();
        assertTrue(e.contains("minecraft:black_wool ×8001, minecraft:white_wool ×7104, minecraft:tnt ×128"), e);
        assertFalse(e.contains("lapis"), "lapis_block is nukeable: " + e);
        assertTrue(e.contains("(moss, soul_sand;"), e);
    }

    @Test
    void nukeBlocksAreAllowed() {
        ValidationResult v = svc.validate(sub(4189, mix("minecraft:lapis_block", 5000, "minecraft:red_terracotta")),
            catalog, site(true, true));
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(List.of("moss", "soul_sand"), v.platformIds());
    }

    @Test
    void noSinglePlatformAcceptingEverythingIsAnError() {
        MapArtConfig.SiteCfg s = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg a = platform("a", "MOSS", true);
        a.clearableBlocks = new ArrayList<>(List.of("minecraft:white_carpet"));
        a.nukeBlocks = new ArrayList<>();
        MapArtConfig.PlatformCfg b = platform("b", "soul_sand", true);
        b.clearableBlocks = new ArrayList<>(List.of("red_carpet"));
        b.nukeBlocks = new ArrayList<>();
        s.platforms.addAll(List.of(a, b));
        ValidationResult v = svc.validate(sub(4189, mix("minecraft:red_carpet", 10, "minecraft:white_carpet")), catalog, s);
        assertEquals(List.of("No single platform accepts every block: a cannot clear minecraft:red_carpet ×10; "
            + "b cannot clear minecraft:white_carpet ×16374."), v.errors());
        assertEquals(List.of(), v.platformIds());
    }

    @Test
    void disabledAndUnsupportedPlatformsAreIgnored() {
        MapArtConfig.SiteCfg s = site(false, false);
        s.platforms.add(platform("cs", "COLOR_SUPPRESSION", true));
        ValidationResult v = svc.validate(sub(4189, Tiles.filled("minecraft:white_carpet")), catalog, s);
        assertEquals(List.of("No enabled moss or soul sand platform is configured (site.platforms)."), v.errors());
        assertEquals(List.of(), svc.validate(sub(4189, Tiles.filled("minecraft:white_carpet")), catalog, null).warnings());
    }

    @Test
    void unknownBlocksAreRejected() {
        ParsedSubmission p = sub(4325, mix("minecraft:leaf_litter", 3, "minecraft:white_carpet"));
        ValidationResult v = svc.validate(p, catalog, site(true, true));
        assertEquals(1, v.errors().size(), v.errors().toString());
        assertTrue(v.errors().getFirst().startsWith("Not in Minecraft 1.21.4"), v.errors().getFirst());
        assertTrue(v.errors().getFirst().contains("minecraft:leaf_litter ×3"), v.errors().getFirst());
        assertEquals(Map.of("minecraft:white_carpet", 16381), v.materials());
        assertEquals(List.of("moss", "soul_sand"), v.platformIds(), "unknown blocks do not drive the platform check");
    }

    @Test
    void sideFacePlacementsAreRejected() {
        TileArt t = Tiles.of((x, z) -> x == 0 && z < 2 ? "minecraft:oak_log[axis=x]"
            : x == 1 && z == 0 ? "minecraft:oak_slab[type=top]"
            : x == 2 ? "minecraft:oak_log[axis=y]" : null);
        MapArtConfig.SiteCfg s = site(true, false);
        s.platforms.getFirst().nukeBlocks.addAll(List.of("minecraft:oak_log", "minecraft:oak_slab"));
        ValidationResult v = svc.validate(sub(4189, t), catalog, s);
        assertEquals(List.of("A click on the top face of the block below cannot produce these states (v1 builds only that way): "
            + "minecraft:oak_log[axis=x] ×2 (axis=x: a top-face click gives axis=y), "
            + "minecraft:oak_slab[type=top] ×1 (type=top: a top-face click gives type=bottom)."), v.errors());
    }

    @Test
    void orientedPinkPetalsAreRejectedWithTheReason() {
        // MapArtCraft exports plain pink_petals (accepted); a structure-block save lists facing, which the bot does not control
        ValidationResult plain = svc.validate(sub(4189, mix("minecraft:pink_petals", 100, "minecraft:white_carpet")),
            catalog, site(true, false));
        assertTrue(plain.ok(), plain.errors().toString());
        ValidationResult v = svc.validate(sub(4189, mix("minecraft:pink_petals[facing=north,flower_amount=1]", 100,
            "minecraft:white_carpet")), new ZenithBlockCatalog(), site(true, false));
        assertEquals(List.of("A click on the top face of the block below cannot produce these states (v1 builds only that way): "
            + "minecraft:pink_petals[facing=north,flower_amount=1] ×100 (facing=north: facing follows the direction the bot "
            + "looks, which v1 does not control)."), v.errors());
    }

    @Test
    void blocksWithoutItemsAreRejected() {
        catalog.noItem.add("minecraft:white_carpet");
        ValidationResult v = svc.validate(sub(4189, Tiles.filled("minecraft:white_carpet")), catalog, site(true, true));
        assertEquals(List.of("No item can place: minecraft:white_carpet ×16384."), v.errors());
        assertEquals(Map.of(), v.materials());
    }

    @Test
    void materialsAreKeyedByPlacementItem() {
        MapArtConfig.SiteCfg s = site(true, true);
        s.platforms.forEach(p -> p.clearableBlocks.add("redstone_wire"));
        ValidationResult v = svc.validate(sub(4189, Tiles.of((x, z) -> z == 0 ? "minecraft:redstone_wire" : null)), catalog, s);
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(Map.of("minecraft:redstone", 128), v.materials());
    }

    @Test
    void gravityAndLargeJobWarnings() {
        MapArtConfig.SiteCfg s = site(true, true);
        s.platforms.forEach(p -> p.nukeBlocks.add("minecraft:sand"));
        TileArt[] tiles = new TileArt[17];
        for (int i = 0; i < tiles.length; i++) tiles[i] = mix("minecraft:sand", 10, "minecraft:white_carpet");
        ValidationResult v = svc.validate(sub(4189, tiles), catalog, s);
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(2, v.warnings().size(), v.warnings().toString());
        assertEquals("Gravity blocks (fine on the platform floor): minecraft:sand ×170.", v.warnings().get(0));
        assertTrue(v.warnings().get(1).startsWith("Large job: 17 maps, 278528 blocks."), v.warnings().get(1));
    }

    @Test
    void longListsAreTruncated() {
        MapArtConfig.SiteCfg s = site(true, true);
        s.platforms.forEach(p -> {
            p.clearableBlocks.clear();
            p.nukeBlocks.clear();
        });
        String[] colours = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        ValidationResult v = svc.validate(sub(4189, Tiles.of((x, z) -> "minecraft:" + colours[x % 16] + "_wool")), catalog, s);
        String e = v.errors().getFirst();
        assertTrue(e.endsWith(" and 4 more."), e);
    }

    @Test
    void defaultListsMatchTheSpec() {
        assertTrue(Defaults.PISTON_CLEARABLE.containsAll(FOLIAGE));
        assertTrue(Defaults.PISTON_CLEARABLE.contains("minecraft:birch_leaves"));
        assertTrue(Defaults.NUKE_BLOCKS.containsAll(List.of("minecraft:terracotta", "minecraft:dripstone_block",
            "minecraft:mushroom_stem")));
        assertTrue(Defaults.NUKE_BLOCKS.contains("minecraft:lapis_block"));
        assertFalse(Defaults.NUKE_BLOCKS.contains("minecraft:white_wool"));
    }
}
