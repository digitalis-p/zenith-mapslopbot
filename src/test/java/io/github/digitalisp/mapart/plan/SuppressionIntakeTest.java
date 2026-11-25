package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.plan.SubmissionService.SubmissionException;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.StructureBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour-suppression intake (docs/COLOR_SUPPRESSION.md) on real PNG-to-NBT exports in {@code fixtures/png2nbt}, generated
 * headlessly from PNG-to-NBT's own source (github.com/EvModder/PNG-to-NBT at a43dfb0) with the CrubTech preset and
 * option, build mode Suppress (2-Layer): {@code full1214} (random 12-colour image, all shades, Minecraft 1.21.4),
 * {@code full263} (the same image in the default 26.x format, palette keyed {@code id}), {@code fullsteps} (Support:
 * Steps) and {@code small1214} (32 x 16 pixels at the north edge, the rest transparent).
 */
class SuppressionIntakeTest {
    static final String FULL = "png2nbt/full1214-suppress_crubtech.nbt";
    static final String FULL_26 = "png2nbt/full263-suppress_crubtech.nbt";
    static final String STEPS = "png2nbt/fullsteps-suppress_crubtech.nbt";
    static final String SMALL = "png2nbt/small1214-suppress_crubtech.nbt";

    final DefaultSubmissionService service = new DefaultSubmissionService();

    static MapArtConfig.SiteCfg site(boolean cs, boolean allowTransparent) {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        site.platforms.add(moss);
        if (cs) {
            MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
            p.id = "cs";
            p.kind = "COLOR_SUPPRESSION";
            p.glassY = List.of(200, 214);
            p.allowTransparent = allowTransparent;
            site.platforms.add(p);
        }
        return site;
    }

    ParsedSubmission parse(String fixture, MapArtConfig.SiteCfg site) throws SubmissionException {
        return service.parse(List.of(Fixtures.file(fixture)), site);
    }

    String refusal(String fixture, MapArtConfig.SiteCfg site) {
        return assertThrows(SubmissionException.class, () -> parse(fixture, site)).getMessage();
    }

    @Test
    void withoutASuppressionPlatformTheExportIsRefusedAsInPhase0() {
        for (MapArtConfig.SiteCfg site : new MapArtConfig.SiteCfg[]{null, site(false, false)}) {
            String m = refusal(FULL, site);
            assertTrue(m.contains("Color suppression maps are not supported yet"), m);
        }
        MapArtConfig.SiteCfg off = site(true, false);
        off.platforms.get(1).enabled = false;
        assertTrue(refusal(FULL, off).contains("not supported yet"));
        assertTrue(refusal(FULL_26, site(false, false)).contains("PNG-to-NBT"));
    }

    @Test
    void crubTechExportBecomesTwoLayersInEightPasses() throws SubmissionException {
        ParsedSubmission p = parse(FULL, site(true, false));
        assertTrue(p.isSuppression());
        assertEquals("full1214", p.baseName());
        assertEquals(4189, p.dataVersion());
        SuppressionArt a = p.suppression();
        // counts from the export itself (y0 10986 + y1 2690; y14 8184 + y15 1848), glass excluded
        assertEquals(13676, a.count(SuppressionArt.BOTTOM));
        assertEquals(10032, a.count(SuppressionArt.TOP));
        List<SuppressionArt.Pass> passes = a.passes();
        assertEquals(8, passes.size());
        assertEquals(8, p.tilesX());
        assertEquals(1, p.tilesZ());
        // the top layer first (platform.topLayerFirst, the default; owner 2026-11-14)
        int[][] order = {{1, 0, 1}, {1, 0, 0}, {1, 1, 1}, {1, 1, 0}, {0, 0, 1}, {0, 0, 0}, {0, 1, 1}, {0, 1, 0}};
        // ... and the old order with the setting off
        MapArtConfig.SiteCfg old = site(true, false);
        old.platforms.get(1).topLayerFirst = false;
        List<SuppressionArt.Pass> oldPasses = parse(FULL, old).suppression().passes();
        for (int i = 0; i < 8; i++) {
            assertEquals(order[(i + 4) % 8][0], oldPasses.get(i).layer(), "old order, pass " + i);
            assertEquals(order[(i + 4) % 8][1], oldPasses.get(i).level(), "old order, pass " + i);
            assertEquals(order[(i + 4) % 8][2] == 1, oldPasses.get(i).noobline(), "old order, pass " + i);
        }
        for (int i = 0; i < 8; i++) {
            SuppressionArt.Pass s = passes.get(i);
            assertEquals(order[i][0], s.layer(), "pass " + i);
            assertEquals(order[i][1], s.level(), "pass " + i);
            assertEquals(order[i][2] == 1, s.noobline(), "pass " + i);
            assertEquals(s.art().nonEmptyCount(), p.tiles().get(new TileCoord(i, 0)).nonEmptyCount());
        }
        // every dominant pixel has its colour block on the bottom layer's glass; no column holds two blocks of a layer
        for (int row = 0; row < 128; row++)
            for (int col = 0; col < 128; col++) {
                if (SuppressionArt.dominant(col, row)) {
                    BlockSpec b = a.get(SuppressionArt.BOTTOM, 0, col, row);
                    assertNotNull(b, col + "," + row);
                    assertFalse(SuppressionExport.FILLERS.contains(b.id()), col + "," + row);
                }
                for (int layer = 0; layer < 2; layer++)
                    assertFalse(a.get(layer, 0, col, row) != null && a.get(layer, 1, col, row) != null);
            }
        // the noobline holds only moss fillers
        for (int layer = 0; layer < 2; layer++)
            for (int level = 0; level < 2; level++)
                for (int col = 0; col < 128; col++) {
                    BlockSpec b = a.get(layer, level, col, SuppressionArt.NOOBLINE);
                    if (b != null) assertEquals("minecraft:moss_block", b.id());
                }
        SuppressionArt back = SuppressionArt.fromPasses(passes);
        assertEquals(a.blockCounts(), back.blockCounts());
    }

    @Test
    void theDefault26FormatReadsTheSameMap() throws SubmissionException {
        SuppressionArt a = parse(FULL, site(true, false)).suppression();
        ParsedSubmission p = parse(FULL_26, site(true, false));
        assertEquals(5023, p.dataVersion());
        SuppressionArt b = p.suppression();
        for (int layer = 0; layer < 2; layer++)
            for (int level = 0; level < 2; level++)
                for (int row = -1; row < 128; row++)
                    for (int col = 0; col < 128; col++)
                        assertEquals(a.get(layer, level, col, row), b.get(layer, level, col, row));
        ValidationResult v = service.validate(p, new ZenithBlockCatalog(), site(true, false));
        assertTrue(v.ok(), String.valueOf(v.errors()));
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains("DataVersion 5023")), String.valueOf(v.warnings()));
    }

    @Test
    void validationUsesTheSuppressionPlatformAndTheSortersBlocks() throws SubmissionException {
        ParsedSubmission p = parse(FULL, site(true, false));
        ValidationResult v = service.validate(p, new ZenithBlockCatalog(), site(true, false));
        assertTrue(v.ok(), String.valueOf(v.errors()));
        assertEquals(List.of("cs"), v.platformIds());
        int total = v.materials().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(13676 + 10032, total);
        assertTrue(v.materials().containsKey("minecraft:moss_block"));
        assertTrue(v.materials().containsKey("minecraft:resin_block"));

        MapArtConfig.SiteCfg bad = site(true, false);
        bad.platforms.get(1).glassY = List.of(200, 205);
        ValidationResult w = service.validate(p, new ZenithBlockCatalog(), bad);
        assertFalse(w.ok());
        assertTrue(w.errors().getFirst().contains("glassY"), w.errors().getFirst());

        ValidationResult none = service.validate(p, new ZenithBlockCatalog(), site(false, false));
        assertFalse(none.ok());
        assertTrue(none.platformIds().isEmpty());
    }

    @Test
    void blocksOutsideTheSortersListAreRefused() throws SubmissionException {
        ParsedSubmission p = parse(FULL, site(true, false));
        p.suppression().set(SuppressionArt.TOP, 0, 0, 0, BlockSpec.of("minecraft:white_wool"));
        ValidationResult v = service.validate(p, new ZenithBlockCatalog(), site(true, false));
        assertFalse(v.ok());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("white_wool")), String.valueOf(v.errors()));
    }

    @Test
    void sidewaysBambooIsAcceptedOtherOrientedStatesAreNot() throws SubmissionException {
        ParsedSubmission p = parse(FULL, site(true, false));
        p.suppression().set(SuppressionArt.TOP, 0, 0, 0, BlockSpec.parse("minecraft:bamboo_block[axis=x]"));
        assertTrue(service.validate(p, new ZenithBlockCatalog(), site(true, false)).ok());
        p.suppression().set(SuppressionArt.TOP, 0, 0, 0, BlockSpec.parse("minecraft:oak_planks"));
        p.suppression().set(SuppressionArt.TOP, 0, 2, 0, BlockSpec.parse("minecraft:white_terracotta[facing=north]"));
        assertFalse(service.validate(p, new ZenithBlockCatalog(), site(true, false)).ok());
    }

    @Test
    void supportStepsIsRefusedWithItsReason() {
        String m = refusal(STEPS, site(true, false));
        assertTrue(m.contains("Support: Steps"), m);
        assertTrue(m.contains("cannot build"), m);
    }

    @Test
    void transparentPixelsAreRefusedUnlessThePlatformAllowsThem() throws SubmissionException {
        String m = refusal(SMALL, site(true, false));
        assertTrue(m.contains("16384") || m.contains("transparent pixels"), m);
        ParsedSubmission p = parse(SMALL, site(true, true));
        assertEquals(128 * 128 - 32 * 16, emptyPixels(p.suppression()));
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("transparent")), String.valueOf(p.warnings()));
        assertEquals(334 + 88, p.suppression().count(SuppressionArt.BOTTOM));
        assertEquals(261 + 71, p.suppression().count(SuppressionArt.TOP));
    }

    @Test
    void waterLatePixelsAndBlocksBetweenTheLayersAreRefused() {
        assertTrue(refuse(synthetic(b -> b.block(5, 0, 0, b.palette("minecraft:glass_pane[waterlogged=true]")), 17))
            .contains("water"));
        assertTrue(refuse(synthetic(b -> b.block(5, 16, 5, b.palette("minecraft:resin_block")), 17)).contains("late"));
        assertTrue(refuse(synthetic(b -> b.block(5, 6, 5, b.palette("minecraft:stone")), 17))
            .contains("between the two layers"));
        assertTrue(refuse(synthetic(b -> b.block(5, 13, 0, b.palette("minecraft:stone")), 17)).contains("glass floor"));
    }

    @Test
    void flatMapArtCraftExportsAreUntouchedWithTheSuppressionPlatformOn() throws SubmissionException {
        ParsedSubmission p = service.parse(List.of(Fixtures.file(Fixtures.A)), site(true, false));
        assertFalse(p.isSuppression());
        assertNull(p.suppression());
    }

    @Test
    void severalFilesAreNeverAColourSuppressionMap() {
        String m = assertThrows(SubmissionException.class, () -> service.parse(List.of(
            Fixtures.renamed(FULL, "a-suppress_crubtech_0_0.nbt"), Fixtures.renamed(FULL, "a-suppress_crubtech_1_0.nbt")),
            site(true, false))).getMessage();
        assertTrue(m.contains("not supported yet"), m);
    }

    // ---- several colour-suppression files in one upload: one map each (SubmissionService.split)

    static List<String> labels(SubmissionService.Split s) {
        return s.parts().stream().map(SubmissionService.Part::label).toList();
    }

    /**
     * Live 2026-11-15: 'Mars_Curiosity_Rover.zip' with 'IMG_5465-suppress_crubtech-1_1.nbt' and its neighbours was
     * refused as "not supported yet", although each file alone is taken.
     */
    @Test
    void aZipOfColourSuppressionMapsIsOneMapPerFileInTileOrder() throws SubmissionException {
        byte[] zip = Fixtures.zip(List.of(Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-1_1.nbt"),
            Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-0_1.nbt"),
            Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-1_0.nbt"),
            Fixtures.renamed(FULL, "IMG_5465-suppress_crubtech-0_0.nbt")));
        SubmissionService.Split s = service.split(List.of(new NamedFile("Mars_Curiosity_Rover.zip", zip)),
            site(true, false));
        assertNotNull(s);
        assertEquals("Mars_Curiosity_Rover", s.base());
        assertEquals(List.of("0,0", "0,1", "1,0", "1,1"), labels(s));
        assertEquals("IMG_5465-suppress_crubtech-0_1.nbt", s.parts().get(1).file().name());
        // each part is a single-file upload, which the parser takes as before
        for (SubmissionService.Part p : s.parts())
            assertTrue(service.parse(List.of(p.file()), site(true, false)).isSuppression());
    }

    @Test
    void looseFilesWithAnUnderscoreSuffixTakeTheirSharedNameAsBase() throws SubmissionException {
        SubmissionService.Split s = service.split(List.of(Fixtures.renamed(FULL, "a-suppress_crubtech_1_0.nbt"),
            Fixtures.renamed(FULL, "a-suppress_crubtech_0_0.nbt")), site(true, false));
        assertEquals("a", s.base());
        assertEquals(List.of("0,0", "1,0"), labels(s));
    }

    @Test
    void filesWithoutATileSuffixAreNamedByThemselves() throws SubmissionException {
        SubmissionService.Split s = service.split(List.of(Fixtures.renamed(FULL, "left-suppress_crubtech.nbt"),
            Fixtures.renamed(FULL, "right-suppress_crubtech.nbt"), Fixtures.renamed(FULL, "left-suppress_crubtech.nbt")),
            site(true, false));
        assertEquals("", s.base());
        assertEquals(List.of("left", "right", "left 2"), labels(s));
        // no name mark at all: the first file's content tells
        SubmissionService.Split plain = service.split(List.of(Fixtures.renamed(FULL, "one.nbt"),
            Fixtures.renamed(FULL, "two.nbt")), site(true, false));
        assertEquals(List.of("one", "two"), labels(plain));
    }

    @Test
    void aMixOfColourSuppressionAndFlatFilesIsRefusedNamingTheFlatOnes() {
        String m = assertThrows(SubmissionException.class, () -> service.split(List.of(
            Fixtures.renamed(FULL, "a-suppress_crubtech-0_0.nbt"), Fixtures.file(Fixtures.A)), site(true, false)))
            .getMessage();
        assertTrue(m.startsWith("1 of the 2 files is a color-suppression (two-layer) map and "
            + "'A_carpet_flat_important__mapart.nbt' is not."), m);
    }

    @Test
    void uploadsThatAreOneJobAreNotSplit() throws SubmissionException {
        // one file; a flat split zip; no colour-suppression platform; the mark on files that are no two-layer export;
        // a file the parser refuses anyway
        assertNull(service.split(List.of(Fixtures.file(FULL)), site(true, false)));
        assertNull(service.split(List.of(Fixtures.file(Fixtures.C)), site(true, false)));
        assertNull(service.split(List.of(Fixtures.renamed(FULL, "a-suppress_crubtech-0_0.nbt"),
            Fixtures.renamed(FULL, "a-suppress_crubtech-0_1.nbt")), site(false, false)));
        assertNull(service.split(List.of(Fixtures.renamed(Fixtures.A, "a-suppress_crubtech-0_0.nbt"),
            Fixtures.renamed(Fixtures.A, "a-suppress_crubtech-0_1.nbt")), site(true, false)));
        assertNull(service.split(List.of(new NamedFile("notes.txt", new byte[]{1})), site(true, false)));
    }

    static int emptyPixels(SuppressionArt a) {
        int n = 0;
        for (int row = 0; row < 128; row++)
            for (int col = 0; col < 128; col++) if (!SuppressionExport.hasColour(a, col, row)) n++;
        return n;
    }

    /**
     * A minimal CrubTech-shaped file of {@code height} levels: glass at y13, each dominant pixel's block at y0, each
     * recessive pixel's at y14, plus {@code extra}.
     */
    static byte[] synthetic(java.util.function.Consumer<StructureBuilder> extra, int height) {
        StructureBuilder b = new StructureBuilder(128, height, 129).author(StructureBuilder.PNG_TO_NBT);
        int glass = b.palette("minecraft:glass");
        int black = b.palette("minecraft:black_concrete");
        int white = b.palette("minecraft:white_concrete");
        for (int x = 0; x < 128; x++)
            for (int z = 1; z <= 128; z++) {
                b.block(x, 13, z, glass);
                if (((x + z - 1) & 1) == 1) b.block(x, 0, z, black);
                else b.block(x, 14, z, white);
            }
        extra.accept(b);
        return b.gzip();
    }

    String refuse(byte[] nbt) {
        return assertThrows(SubmissionException.class,
            () -> service.parse(List.of(new NamedFile("x-suppress_crubtech.nbt", nbt)), site(true, false))).getMessage();
    }

    @Test
    void theSyntheticBaseFileIsAccepted() throws SubmissionException {
        ParsedSubmission p = service.parse(List.of(new NamedFile("x.nbt", synthetic(b -> { }, 15))), site(true, false));
        assertEquals(8192, p.suppression().count(SuppressionArt.BOTTOM));
        assertEquals(8192, p.suppression().count(SuppressionArt.TOP));
        assertEquals(2, p.tilesX());
    }
}
