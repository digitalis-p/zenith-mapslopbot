package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.plan.SubmissionService.SubmissionException;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.StructureBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uploads the bot cannot build are refused with what they are and how to export a supported map. */
class UnsupportedExportTest {
    private static final String NOT_SUPPORTED = "Color suppression maps are not supported yet";

    private final DefaultSubmissionService svc = new DefaultSubmissionService();

    private String reject(NamedFile... files) {
        String m = assertThrows(SubmissionException.class, () -> svc.parse(List.of(files))).getMessage();
        assertTrue(m.contains(ExportCheck.HOW), "every refusal says how to export: " + m);
        assertTrue(m.contains("MapArtCraft") && m.contains("1.21.4"), m);
        return m;
    }

    private static byte[] flatStone(Consumer<StructureBuilder> setup) {
        return StructureBuilder.flat(1, 1, (x, z) -> (x + z) % 5 == 0 ? "minecraft:red_wool" : "minecraft:stone",
            StructureBuilder.SUPPORT, setup);
    }

    // ---------------------------------------------------------------- colour suppression

    @Test
    void pngToNbtCrubTechExportIsRefusedByNameAndStructure() {
        byte[] nbt = SuppressionExports.crubtech(false, null);
        String named = reject(new NamedFile("cat-suppress_crubtech.nbt", nbt));
        assertEquals("'cat-suppress_crubtech.nbt' is a color-suppression (two-layer) map: the file name has "
            + "'-suppress_crubtech', PNG-to-NBT's mark of a color-suppression build; a plain glass floor at height 13 "
            + "with art above and below it. " + NOT_SUPPORTED + ": the bot builds normal flat maps on the moss platform "
            + "only. " + ExportCheck.HOW, named);
        assertFalse(named.contains("staircased"), named);

        String renamed = reject(new NamedFile("cat.nbt", nbt));
        assertTrue(renamed.startsWith("'cat.nbt' is a color-suppression (two-layer) map: a plain glass floor at height 13"),
            renamed);
        assertTrue(renamed.contains(NOT_SUPPORTED), renamed);

        byte[] testAuthor = SuppressionExports.crubtech(false, b -> b.author("test"));
        String byStructure = reject(new NamedFile("cat.nbt", testAuthor));
        assertTrue(byStructure.contains("a plain glass floor at height 13") && byStructure.contains(NOT_SUPPORTED), byStructure);
    }

    @Test
    void denseCrubTechGivesEveryPieceOfEvidence() {
        String m = reject(new NamedFile("cat-suppress_crubtech.nbt", SuppressionExports.crubtech(true, null)));
        assertTrue(m.contains("the file name has '-suppress_crubtech', PNG-to-NBT's mark of a color-suppression build; "
            + "two art layers: blocks at heights 0..1 and 13..15 "
            + "with empty levels between (16384 of 16384 columns); a plain glass floor at height 13 with art above and "
            + "below it. " + NOT_SUPPORTED), m);
        String renamed = reject(new NamedFile("cat.nbt", SuppressionExports.crubtech(true, b -> b.author("test"))));
        assertTrue(renamed.contains("two art layers") && renamed.contains("glass floor"), renamed);
    }

    @Test
    void twoLayerMapWithoutGlassIsRefused() {
        String m = reject(new NamedFile("bird.nbt", SuppressionExports.twoLayerGap5()));
        assertTrue(m.startsWith("'bird.nbt' is a color-suppression (two-layer) map: two art layers: blocks at heights "
            + "0 and 5 with empty levels between"), m);
        assertTrue(m.contains(NOT_SUPPORTED), m);
    }

    @Test
    void flatSplitHalfIsRefusedByItsName() {
        String m = reject(new NamedFile("x-split_row.nbt", flatStone(b -> b.author(StructureBuilder.MAPARTCRAFT))));
        assertTrue(m.startsWith("'x-split_row.nbt' is a color-suppression (two-layer) map: the file name has "
            + "'-split_row', PNG-to-NBT's mark of one half of a split color-suppression build. " + NOT_SUPPORTED), m);
    }

    @Test
    void splitTileNamesAreCheckedToo() {
        byte[] nbt = SuppressionExports.crubtech(false, null);
        String m = reject(new NamedFile("cat-suppress_crubtech_0_0.nbt", nbt), new NamedFile("cat-suppress_crubtech_1_0.nbt", nbt));
        assertTrue(m.startsWith("'cat-suppress_crubtech_0_0.nbt' is a color-suppression (two-layer) map: the file name "
            + "has '-suppress_crubtech',"), m);
        String byStructure = reject(new NamedFile("cat_0_0.nbt", nbt));
        assertTrue(byStructure.contains("glass floor at height 13"), byStructure);
    }

    @Test
    void twoUnnamedSuppressionFilesAreNotAmbiguous() {
        byte[] nbt = SuppressionExports.crubtech(false, null);
        String m = reject(new NamedFile("cat-suppress_crubtech.nbt", nbt), new NamedFile("dog-suppress_crubtech.nbt", nbt));
        assertTrue(m.startsWith("'cat-suppress_crubtech.nbt' is a color-suppression (two-layer) map"), m);
        assertFalse(m.contains("Cannot tell which map tile"), m);

        String split = reject(new NamedFile("x-split_row.nbt", flatStone(null)), new NamedFile("x-split_checker.nbt", flatStone(null)));
        assertTrue(split.contains("'-split_row'"), split);

        String byStructure = reject(new NamedFile("a.nbt", Fixtures.bytes(Fixtures.A)), new NamedFile("cat.nbt", nbt));
        assertTrue(byStructure.startsWith("'cat.nbt' is a color-suppression (two-layer) map: a plain glass floor"), byStructure);
    }

    @Test
    void ambiguousUploadsNameOtherUnsupportedExports() {
        byte[] p2n = flatStone(b -> b.author(StructureBuilder.PNG_TO_NBT));
        String m = reject(new NamedFile("a.nbt", Fixtures.bytes(Fixtures.A)), new NamedFile("b.nbt", p2n));
        assertTrue(m.startsWith("'b.nbt' was made with PNG-to-NBT (evmodder.net)."), m);

        StructureBuilder newer = new StructureBuilder(1, 1, 1).dataVersion(5023).author(null).idKeys();
        newer.block(0, 0, 0, newer.palette("stone"));
        String n = reject(new NamedFile("a.nbt", newer.gzip()), new NamedFile("b.nbt", newer.gzip()));
        assertTrue(n.startsWith("'a.nbt' was exported for a newer Minecraft (DataVersion 5023, 26.3;"), n);
    }

    @Test
    void unreadableAmbiguousFilesKeepTheAmbiguityMessage() {
        String m = assertThrows(SubmissionException.class, () -> svc.parse(List.of(
            new NamedFile("a.nbt", "garbage".getBytes()), new NamedFile("b.nbt", Fixtures.bytes(Fixtures.E))))).getMessage();
        assertTrue(m.startsWith("Cannot tell which map tile these files are"), m);
    }

    @Test
    void suppressionFileInAZipNamesTheZip() {
        byte[] zip = Fixtures.zip(List.of(new NamedFile("export/cat-suppress_crubtech.nbt", SuppressionExports.crubtech(false, null))));
        String m = reject(new NamedFile("cat.zip", zip));
        assertTrue(m.startsWith("'cat-suppress_crubtech.nbt' (in 'cat.zip') is a color-suppression (two-layer) map"), m);

        byte[] structural = Fixtures.zip(List.of(new NamedFile("cat_0_0.nbt", SuppressionExports.crubtech(false, null))));
        String s = reject(new NamedFile("maps.zip", structural));
        assertTrue(s.startsWith("'cat_0_0.nbt' (in 'maps.zip') is a color-suppression (two-layer) map: a plain glass floor"), s);
    }

    // ---------------------------------------------------------------- PNG-to-NBT and newer Minecraft

    @Test
    void flatPngToNbtExportIsRefused() {
        String m = reject(new NamedFile("cat.nbt", flatStone(b -> b.author(StructureBuilder.PNG_TO_NBT))));
        assertEquals("'cat.nbt' was made with PNG-to-NBT (evmodder.net). The bot reads MapArtCraft exports only; "
            + "PNG-to-NBT's suppression, split and step modes are color-suppression builds, which are not supported "
            + "yet. " + ExportCheck.HOW, m);
    }

    /** As PNG-to-NBT 26.3 writes it: DataVersion 5023, id/properties palette keys, its root tag order. */
    private static StructureBuilder pngToNbt263(String author) {
        StructureBuilder b = new StructureBuilder(128, 3, 129).dataVersion(5023).author(author).idKeys().pngToNbtOrder();
        int stone = b.palette("minecraft:stone");
        int log = b.palette("minecraft:oak_log[axis=y]");
        for (int x = 0; x < 128; x++)
            for (int z = 1; z <= 128; z++) b.block(x, 1, z, (x + z) % 2 == 0 ? stone : log);
        return b;
    }

    @Test
    void pngToNbt26FormatIsNeverAMissingName() {
        String m = reject(new NamedFile("cat.nbt", pngToNbt263(StructureBuilder.PNG_TO_NBT).gzip()));
        assertTrue(m.startsWith("'cat.nbt' was made with PNG-to-NBT (evmodder.net), in the 26.x structure format "
            + "(DataVersion 5023; PNG-to-NBT's Options -> Minecraft version selects 1.21.4)."), m);
        assertFalse(m.contains("has no Name"), m);
        assertFalse(m.contains("not a readable"), m);

        String noAuthor = reject(new NamedFile("cat.nbt", pngToNbt263(null).gzip()));
        assertTrue(noAuthor.startsWith("'cat.nbt' was exported for a newer Minecraft (DataVersion 5023, 26.3; its palette "
            + "uses the 'id' keys of the 26.x structure format)"), noAuthor);
        assertFalse(noAuthor.contains("has no Name"), noAuthor);

        String suppression = reject(new NamedFile("cat-suppress_2layer.nbt", pngToNbt263(StructureBuilder.PNG_TO_NBT).gzip()));
        assertTrue(suppression.startsWith("'cat-suppress_2layer.nbt' is a color-suppression (two-layer) map: the file "
            + "name has '-suppress_2layer'"), suppression);
    }

    @Test
    void newerMinecraftThanMapArtCraftsNewestIsRefused() {
        String m = reject(new NamedFile("x.nbt", flatStone(b -> b.dataVersion(4440).author(StructureBuilder.MAPARTCRAFT))));
        assertTrue(m.startsWith("'x.nbt' was exported for a newer Minecraft (DataVersion 4440, 1.21.6 or newer); the bot "
            + "builds 1.21.4 maps and reads exports up to DataVersion 4325 (MapArtCraft 1.21.5)."), m);
    }

    // ---------------------------------------------------------------- still accepted

    @Test
    void mapArtCraft1215ExportsAreStillAccepted() throws SubmissionException {
        ParsedSubmission p = svc.parse(List.of(new NamedFile("x.nbt",
            flatStone(b -> b.dataVersion(4325).author(StructureBuilder.MAPARTCRAFT)))));
        assertEquals(4325, p.dataVersion());
        List<NamedFile> all = new ArrayList<>(Fixtures.files(Fixtures.SIZEDREISEN));
        assertEquals(8, svc.parse(all).tiles().size());
    }

    @Test
    void glassSupportUnderFlatArtIsAccepted() throws SubmissionException {
        byte[] nbt = StructureBuilder.flat(1, 1, (x, z) -> "minecraft:red_carpet", "minecraft:glass",
            b -> b.dataVersion(4325).author(StructureBuilder.MAPARTCRAFT));
        ParsedSubmission p = svc.parse(List.of(new NamedFile("glass_supported.nbt", nbt)));
        assertEquals(16384, p.tiles().values().iterator().next().nonEmptyCount());
    }
}
