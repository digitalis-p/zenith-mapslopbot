package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Schematic;
import io.github.digitalisp.mapart.nbt.NbtException;
import io.github.digitalisp.mapart.nbt.StructureNbtParser;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.StructureBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ExportCheck} on parsed structures: what counts as colour suppression, PNG-to-NBT or too new. */
class ExportCheckTest {
    private static Schematic parse(byte[] nbt) throws NbtException {
        return StructureNbtParser.parse(nbt);
    }

    private static String check(String name, byte[] nbt) throws NbtException {
        return ExportCheck.check(name, null, parse(nbt));
    }

    @Test
    void everyCommittedMapArtCraftFixturePasses() throws NbtException {
        for (String f : List.of(Fixtures.A, Fixtures.B, Fixtures.D, Fixtures.E, Fixtures.F, Fixtures.G, Fixtures.H))
            assertNull(check(f, Fixtures.bytes(f)), f);
        for (String f : Fixtures.SIZEDREISEN) assertNull(check(f, Fixtures.bytes(f)), f);
        assertEquals(List.of(), ExportCheck.structureEvidence(parse(Fixtures.bytes(Fixtures.B))), "valley staircase");
    }

    @Test
    void denseCrubTechShowsTwoLayersAndTheGlassFloor() throws NbtException {
        String m = check("cat.nbt", SuppressionExports.crubtech(true, null));
        assertEquals("'cat.nbt' is a color-suppression (two-layer) map: two art layers: blocks at heights 0..1 and "
            + "13..15 with empty levels between (16384 of 16384 columns); a plain glass floor at height 13 with art "
            + "above and below it. Color suppression maps are not supported yet: the bot builds normal flat maps on "
            + "the moss platform only. " + ExportCheck.HOW, m);
    }

    @Test
    void sparseCrubTechIsCaughtByItsGlassFloorAlone() throws NbtException {
        Schematic s = parse(SuppressionExports.crubtech(false, b -> b.author("test")));
        assertEquals(List.of("a plain glass floor at height 13 with art above and below it"), ExportCheck.structureEvidence(s));
    }

    @Test
    void twoLayersWithoutGlass() throws NbtException {
        List<String> e = ExportCheck.structureEvidence(parse(SuppressionExports.twoLayerGap5()));
        assertEquals(List.of("two art layers: blocks at heights 0 and 5 with empty levels between (12288 of 16384 columns)"), e);
    }

    /** All 16384 columns hold art at y 2; {@code gapped} of them also hold a block at y 0 (y 1 empty). */
    private static Schematic withGappedColumns(int gapped) throws NbtException {
        StructureBuilder b = new StructureBuilder(128, 3, 129);
        int stone = b.palette("stone");
        int dirt = b.palette("dirt");
        for (int i = 0; i < 128 * 128; i++) {
            int x = i % 128, z = i / 128 + 1;
            b.block(x, 2, z, stone);
            if (i < gapped) b.block(x, 0, z, dirt);
        }
        return parse(b.gzip());
    }

    @Test
    void twoLayerThresholdIsOnePercentOfNonEmptyColumns() throws NbtException {
        assertEquals(List.of(), ExportCheck.structureEvidence(withGappedColumns(163)), "163 / 16384 < 1%");
        assertEquals(List.of("two art layers: blocks at heights 0 and 2 with empty levels between (164 of 16384 columns)"),
            ExportCheck.structureEvidence(withGappedColumns(164)));
    }

    @Test
    void airAndTheNooblineDoNotMakeLayers() throws NbtException {
        StructureBuilder b = new StructureBuilder(4, 8, 3);
        int stone = b.palette("stone");
        int air = b.palette("air");
        for (int x = 0; x < 4; x++) {
            b.block(x, 7, 0, stone).block(x, 0, 0, stone); // noobline row: ignored
            b.block(x, 2, 1, stone).block(x, 6, 1, air).block(x, 0, 2, air).block(x, 3, 2, stone);
        }
        assertEquals(List.of(), ExportCheck.structureEvidence(parse(b.gzip())));
    }

    /** Glass at y 1 in {@code glass} of the 16384 cells, art at y 2 above every cell, one block at y 0 below. */
    private static Schematic withGlassCells(int glass, boolean below) throws NbtException {
        StructureBuilder b = new StructureBuilder(128, 3, 129);
        int g = b.palette("minecraft:glass");
        int art = b.palette("minecraft:red_wool");
        int cobble = b.palette("minecraft:cobblestone");
        for (int i = 0; i < 128 * 128; i++) {
            int x = i % 128, z = i / 128 + 1;
            b.block(x, 2, z, art);
            b.block(x, 1, z, i < glass ? g : cobble);
        }
        if (below) b.block(0, 0, 1, cobble);
        return parse(b.gzip());
    }

    @Test
    void glassFloorNeedsHalfTheFootprintAndArtOnBothSides() throws NbtException {
        assertEquals(List.of(), ExportCheck.structureEvidence(withGlassCells(8191, true)), "below 50%");
        assertEquals(List.of("a plain glass floor at height 1 with art above and below it"),
            ExportCheck.structureEvidence(withGlassCells(8192, true)));
        assertEquals(List.of(), ExportCheck.structureEvidence(withGlassCells(16384, false)),
            "glass support under flat art has nothing below it");
    }

    @Test
    void stainedGlassIsNotAGlassFloor() throws NbtException {
        StructureBuilder b = new StructureBuilder(128, 3, 129);
        int g = b.palette("minecraft:white_stained_glass");
        int art = b.palette("minecraft:red_wool");
        for (int i = 0; i < 128 * 128; i++) b.block(i % 128, 2, i / 128 + 1, art).block(i % 128, 1, i / 128 + 1, g);
        b.block(0, 0, 1, art);
        assertEquals(List.of(), ExportCheck.structureEvidence(parse(b.gzip())));
    }

    @Test
    void mapArtCraftFlatWithGlassSupportPasses() throws NbtException {
        byte[] nbt = StructureBuilder.flat(1, 1, (x, z) -> "minecraft:red_carpet", "minecraft:glass",
            b -> b.author(StructureBuilder.MAPARTCRAFT).dataVersion(4325));
        assertNull(check("glass_support.nbt", nbt));
    }

    @Test
    void fileNameSignatures() {
        for (String n : List.of("cat-suppress_crubtech.nbt", "cat-suppress_2layer.nbt", "Cat-Suppress_Step_Pairs_north.NBT",
                "cat-suppress_step_checker_east.nbt", "cat-split_row.nbt", "cat-split_checker.nbt", "x-suppress_.nbt"))
            assertTrue(ExportCheck.byName(n, null) != null, n);
        for (String n : List.of("cat.nbt", "cat-incline_up.nbt", "cat-classic.nbt", "cat-valley.nbt", "cat_split_0_0.nbt",
                "C_carpet_flat_2x1_split__mapart.nbt", "suppress_cat.nbt", "mapart_0_0.nbt"))
            assertNull(ExportCheck.byName(n, null), n);
        assertEquals("'cat-suppress_crubtech_1_0.nbt' (in 'cat.zip') is a color-suppression (two-layer) map: the file "
            + "name has '-suppress_crubtech', PNG-to-NBT's mark of a color-suppression build. Color suppression maps "
            + "are not supported yet: the bot builds normal flat maps on the moss platform only. " + ExportCheck.HOW,
            ExportCheck.byName("cat-suppress_crubtech_1_0.nbt", "cat.zip"));
        assertTrue(ExportCheck.byName("cat-split_row.nbt", null)
            .contains("'-split_row', PNG-to-NBT's mark of one half of a split color-suppression build"));
    }

    @Test
    void pngToNbtAuthorIsRefusedAfterTheSuppressionChecks() throws NbtException {
        byte[] nbt = StructureBuilder.flat(1, 1, (x, z) -> "minecraft:stone", StructureBuilder.SUPPORT,
            b -> b.author("evmodder.net/PNG-to-NBT"));
        assertEquals("'cat.nbt' was made with PNG-to-NBT (evmodder.net). The bot reads MapArtCraft exports only; "
            + "PNG-to-NBT's suppression, split and step modes are color-suppression builds, which are not supported "
            + "yet. " + ExportCheck.HOW, check("cat.nbt", nbt));
        byte[] lower = StructureBuilder.flat(1, 1, (x, z) -> "minecraft:stone", StructureBuilder.SUPPORT,
            b -> b.author("EVMODDER.NET/png-to-nbt v2"));
        assertTrue(check("cat.nbt", lower).contains("PNG-to-NBT (evmodder.net)"), "case-insensitive");
    }

    @Test
    void dataVersionLimitIsMapArtCraftsNewest() throws NbtException {
        assertEquals(4325, ExportCheck.MAX_DATA_VERSION);
        for (int dv : List.of(0, 3953, 4189, 4325))
            assertNull(check("x.nbt", StructureBuilder.flat(1, 1, (x, z) -> "minecraft:stone", StructureBuilder.SUPPORT,
                b -> b.dataVersion(dv).author(StructureBuilder.MAPARTCRAFT))), "DataVersion " + dv);
        String m = check("x.nbt", StructureBuilder.flat(1, 1, (x, z) -> "minecraft:stone", StructureBuilder.SUPPORT,
            b -> b.dataVersion(4440).author(StructureBuilder.MAPARTCRAFT)));
        assertEquals("'x.nbt' was exported for a newer Minecraft (DataVersion 4440, 1.21.6 or newer); the bot builds "
            + "1.21.4 maps and reads exports up to DataVersion 4325 (MapArtCraft 1.21.5). " + ExportCheck.HOW, m);
    }

    @Test
    void versionNames() {
        assertEquals("older than 1.21.4", ExportCheck.versionName(3953));
        assertEquals("1.21.4", ExportCheck.versionName(4189));
        assertEquals("1.21.5", ExportCheck.versionName(4190));
        assertEquals("1.21.5", ExportCheck.versionName(4325));
        assertEquals("1.21.6 or newer", ExportCheck.versionName(4326));
        assertEquals("1.21.6 or newer", ExportCheck.versionName(4790));
        assertEquals("26.x", ExportCheck.versionName(5000));
        assertEquals("26.3", ExportCheck.versionName(5023));
        assertEquals("26.x", ExportCheck.versionName(4903 + 200));
    }

    @Test
    void newerFormatMessages() {
        String suppression = ExportCheck.newerFormat("cat-suppress_crubtech.nbt", null, 5023, StructureBuilder.PNG_TO_NBT);
        assertTrue(suppression.startsWith("'cat-suppress_crubtech.nbt' is a color-suppression (two-layer) map: the file name has"),
            suppression);
        assertEquals("'cat.nbt' was made with PNG-to-NBT (evmodder.net), in the 26.x structure format (DataVersion 5023; "
            + "PNG-to-NBT's Options -> Minecraft version selects 1.21.4). The bot reads MapArtCraft exports only; "
            + "PNG-to-NBT's suppression, split and step modes are color-suppression builds, which are not supported "
            + "yet. " + ExportCheck.HOW, ExportCheck.newerFormat("cat.nbt", null, 5023, StructureBuilder.PNG_TO_NBT));
        assertEquals("'cat.nbt' (in 'a.zip') was exported for a newer Minecraft (DataVersion 5023, 26.3; its palette uses "
            + "the 'id' keys of the 26.x structure format); the bot builds 1.21.4 maps and reads exports up to "
            + "DataVersion 4325 (MapArtCraft 1.21.5). " + ExportCheck.HOW, ExportCheck.newerFormat("cat.nbt", "a.zip", 5023, ""));
        String noVersion = ExportCheck.newerFormat("cat.nbt", null, 0, "");
        assertTrue(noVersion.startsWith("'cat.nbt' was exported for a newer Minecraft (its palette uses the 'id' keys"),
            noVersion);
    }
}
