package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.plan.SubmissionService.SubmissionException;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.StructureBuilder;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionParseTest {
    private final DefaultSubmissionService svc = new DefaultSubmissionService();

    private ParsedSubmission parse(NamedFile... files) throws SubmissionException {
        return svc.parse(List.of(files));
    }

    private String reject(DefaultSubmissionService s, List<NamedFile> files) {
        return assertThrows(SubmissionException.class, () -> s.parse(files)).getMessage();
    }

    private String reject(NamedFile... files) {
        return reject(svc, List.of(files));
    }

    private static BlockSpec b(String id) {
        return BlockSpec.of(id);
    }

    // ---------------------------------------------------------------- MapArtCraft fixtures

    @Test
    void flatSingleMapExportsParse() throws SubmissionException {
        for (String f : List.of(Fixtures.A, Fixtures.E)) {
            ParsedSubmission p = parse(Fixtures.file(f));
            assertEquals(1, p.tilesX());
            assertEquals(1, p.tilesZ());
            assertEquals(4325, p.dataVersion());
            assertTrue(p.baseName().matches("[AE]_carpet_flat_(important|none)_mapart"), p.baseName());
            TileArt t = p.tiles().get(new TileCoord(0, 0));
            assertEquals(16384, t.nonEmptyCount(), f);
            assertTrue(t.blockCounts().keySet().stream().allMatch(s -> s.endsWith("_carpet")), f + " has only carpets");
            assertEquals(8080, t.blockCounts().get("minecraft:light_gray_carpet"));
            assertEquals(List.of(), p.warnings());
        }
    }

    @Test
    void supportBlockOptionDoesNotChangeTheArt() throws SubmissionException {
        TileArt important = parse(Fixtures.file(Fixtures.A)).tiles().get(new TileCoord(0, 0));
        TileArt none = parse(Fixtures.file(Fixtures.E)).tiles().get(new TileCoord(0, 0));
        assertTrue(Tiles.equal(important, none));
    }

    @Test
    void orientationAndTransparencyFollowTheImage() throws SubmissionException {
        TileArt g = parse(Fixtures.file(Fixtures.G)).tiles().get(new TileCoord(0, 0));
        assertEquals(16384 - 1024, g.nonEmptyCount());
        for (int pz = 0; pz < 32; pz++)
            for (int px = 0; px < 32; px++) assertNull(g.get(px, pz), "transparent NW quadrant " + px + "," + pz);
        assertEquals(b("minecraft:white_wool"), g.get(32, 0));
        assertEquals(b("minecraft:white_wool"), g.get(0, 32));
        assertEquals(b("minecraft:white_wool"), g.get(63, 10));
        assertEquals(b("minecraft:black_wool"), g.get(64, 10));
        assertEquals(b("minecraft:black_wool"), g.get(64, 64));
        assertEquals(b("minecraft:black_wool"), g.get(126, 5));
        for (int pz = 0; pz < 127; pz++) assertEquals(b("minecraft:lapis_block"), g.get(127, pz), "east column");
        for (int px = 0; px < 128; px++) assertEquals(b("minecraft:tnt"), g.get(px, 127), "south row");
    }

    @Test
    void classicDoubleExportOfAFlatImageIsFlatAndIdenticalToG() throws SubmissionException {
        // H was exported with Classic staircasing, but the image only uses normal-tone colours, so every
        // top block sits at Y=1: it is flat by definition and must equal G.
        TileArt g = parse(Fixtures.file(Fixtures.G)).tiles().get(new TileCoord(0, 0));
        TileArt h = parse(Fixtures.file(Fixtures.H)).tiles().get(new TileCoord(0, 0));
        assertTrue(Tiles.equal(g, h));
    }

    @Test
    void staircasedExportsAreRejected() {
        String b = reject(Fixtures.file(Fixtures.B));
        assertTrue(b.contains("not flat: the top blocks sit at heights 0..80 (staircased or 3D art)"), b);
        assertTrue(b.contains("export from MapArtCraft with Staircasing OFF (flat)"), b);
        assertTrue(b.contains("B_everything_valley_allopt__mapart.nbt"), b);
        assertFalse(b.contains("color-suppression"), "a MapArtCraft staircase is one layer, not suppression: " + b);
        String d = reject(Fixtures.file(Fixtures.D));
        assertTrue(d.contains("not flat: the top blocks sit at heights 0..79"), d);
        assertFalse(d.contains("color-suppression"), d);
    }

    @Test
    void splitZipEqualsJoinedExport() throws SubmissionException {
        ParsedSubmission split = parse(Fixtures.file(Fixtures.C));
        ParsedSubmission joined = parse(Fixtures.file(Fixtures.F));
        assertEquals(2, split.tilesX());
        assertEquals(1, split.tilesZ());
        assertEquals(2, joined.tilesX());
        assertEquals(1, joined.tilesZ());
        assertEquals(split.tiles().keySet(), joined.tiles().keySet());
        for (TileCoord c : split.tiles().keySet())
            assertTrue(Tiles.equal(split.tiles().get(c), joined.tiles().get(c)), "tile " + c);
        assertEquals("C_carpet_flat_2x1_split_mapart", split.baseName(), "the zip's own name, not \"mapart\" from inside");
        assertEquals("F_carpet_flat_2x1_joined_mapart", joined.baseName());
        assertEquals(6934, split.tiles().get(new TileCoord(0, 0)).blockCounts().get("minecraft:light_gray_carpet"));
        assertEquals(6992, split.tiles().get(new TileCoord(1, 0)).blockCounts().get("minecraft:light_gray_carpet"));
    }

    @Test
    void sizedreisenSplitIsAFlat4x2Grid() throws SubmissionException {
        List<NamedFile> files = new ArrayList<>(Fixtures.files(Fixtures.SIZEDREISEN));
        Collections.shuffle(files, new Random(7));
        ParsedSubmission p = svc.parse(files);
        assertEquals(4, p.tilesX());
        assertEquals(2, p.tilesZ());
        assertEquals("sizedreisen", p.baseName());
        assertEquals(8, p.tiles().size());
        for (TileArt t : p.tiles().values()) assertEquals(16384, t.nonEmptyCount());
        assertEquals(Map.of("minecraft:magenta_terracotta", 16384), p.tiles().get(new TileCoord(0, 0)).blockCounts());
        assertEquals(5170, p.tiles().get(new TileCoord(3, 1)).blockCounts().get("minecraft:diorite"));
        assertEquals(List.of(), p.warnings());
    }

    @Test
    void zipWithNestedDirsAndJunkIsAccepted() throws SubmissionException {
        List<NamedFile> entries = new ArrayList<>();
        entries.add(new NamedFile("export/", new byte[0]));
        for (NamedFile f : Fixtures.files(Fixtures.SIZEDREISEN))
            entries.add(new NamedFile("export/maps/" + f.name(), f.data()));
        entries.add(new NamedFile("__MACOSX/export/maps/._0001_sizedreisen_0_0.nbt", new byte[] {1, 2}));
        entries.add(new NamedFile("export/.DS_Store", new byte[] {0}));
        entries.add(new NamedFile("export/readme.txt", "hi".getBytes()));
        ParsedSubmission p = parse(new NamedFile("sizedreisen.zip", Fixtures.zip(entries)));
        assertEquals(4, p.tilesX());
        assertEquals(2, p.tilesZ());
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().getFirst().contains("readme.txt"), p.warnings().getFirst());
    }

    @Test
    void looseFilesAndZipsCombine() throws SubmissionException {
        List<NamedFile> all = Fixtures.files(Fixtures.SIZEDREISEN);
        List<NamedFile> files = new ArrayList<>(all.subList(0, 3));
        files.add(new NamedFile("rest.zip", Fixtures.zip(all.subList(3, 8))));
        assertEquals(8, svc.parse(files).tiles().size());
    }

    // ---------------------------------------------------------------- rejections

    @Test
    void missingTilesAreListed() {
        List<NamedFile> files = new ArrayList<>(Fixtures.files(Fixtures.SIZEDREISEN));
        files.removeIf(f -> f.name().endsWith("_2_1.nbt") || f.name().endsWith("_0_1.nbt"));
        String m = reject(svc, files);
        assertTrue(m.contains("Missing 2 tiles of a 4x2 grid: 0,1, 2,1"), m);
    }

    @Test
    void duplicateCoordinatesAreRejected() {
        List<NamedFile> files = new ArrayList<>(Fixtures.files(Fixtures.SIZEDREISEN));
        files.add(Fixtures.renamed("sizedreisen/0005_sizedreisen_2_1.nbt", "0009_sizedreisen_2_1.nbt"));
        String m = reject(svc, files);
        assertTrue(m.contains("Duplicate tile 2,1: '0005_sizedreisen_2_1.nbt' and '0009_sizedreisen_2_1.nbt'"), m);
    }

    @Test
    void mixedAndWrongTileSizesAreRejected() {
        String mixed = reject(Fixtures.renamed(Fixtures.A, "x_0_0.nbt"), Fixtures.renamed(Fixtures.F, "x_1_0.nbt"));
        assertTrue(mixed.startsWith("Mixed tile sizes: 'x_1_0.nbt' is 256x129"), mixed);
        String wrong = reject(Fixtures.renamed(Fixtures.F, "x_0_0.nbt"));
        assertTrue(wrong.startsWith("Wrong tile dimensions: 'x_0_0.nbt' is 256x129"), wrong);
        assertTrue(wrong.contains("128 wide and 129 deep"), wrong);
    }

    @Test
    void joinedExportWithBadDimensionsIsRejected() {
        StructureBuilder b = new StructureBuilder(130, 3, 129);
        b.block(0, 2, 1, b.palette("stone"));
        String m = reject(new NamedFile("odd.nbt", b.gzip()));
        assertTrue(m.contains("'odd.nbt' is 130 wide and 129 deep"), m);
        StructureBuilder c = new StructureBuilder(128, 3, 128);
        c.block(0, 2, 1, c.palette("stone"));
        assertTrue(reject(new NamedFile("short.nbt", c.gzip())).contains("128·M+1 deep"));
    }

    @Test
    void severalUnnamedFilesAreAmbiguous() {
        String m = reject(Fixtures.file(Fixtures.A), Fixtures.file(Fixtures.E));
        assertTrue(m.startsWith("Cannot tell which map tile these files are"), m);
        String one = reject(Fixtures.file(Fixtures.A), Fixtures.renamed(Fixtures.E, "e_0_0.nbt"));
        assertTrue(one.contains("this file is: 'A_carpet_flat_important__mapart.nbt'"), one);
    }

    @Test
    void zipSlipIsRefused() {
        for (String evil : List.of("../evil_0_0.nbt", "/abs/evil_0_0.nbt", "a/../../evil_0_0.nbt", "C:/evil_0_0.nbt")) {
            byte[] zip = Fixtures.zip(List.of(new NamedFile(evil, Fixtures.bytes(Fixtures.A))));
            String m = reject(new NamedFile("evil.zip", zip));
            assertTrue(m.contains("unsafe path"), evil + " -> " + m);
        }
    }

    @Test
    void limitsAreEnforced() {
        DefaultSubmissionService fourMaps = new DefaultSubmissionService(4, 16 << 20, 128 << 20);
        String files = reject(fourMaps, Fixtures.files(Fixtures.SIZEDREISEN));
        assertTrue(files.contains("at most 4 maps"), files);
        byte[] joined = StructureBuilder.flat(3, 2, (x, z) -> "minecraft:stone");
        String grid = reject(fourMaps, List.of(new NamedFile("big.nbt", joined)));
        assertTrue(grid.contains("3x2 = 6 maps; at most 4"), grid);
        String tooBig = reject(new DefaultSubmissionService(64, 100_000, 1 << 20), List.of(Fixtures.file(Fixtures.A)));
        assertTrue(tooBig.contains("is larger than 100000 bytes"), tooBig);
        DefaultSubmissionService tight = new DefaultSubmissionService(64, 1 << 20, 300_000);
        String total = reject(tight, List.of(Fixtures.file(Fixtures.A), Fixtures.file(Fixtures.F), Fixtures.file(Fixtures.C)));
        assertTrue(total.contains("too large"), total);
        byte[] one = StructureBuilder.flat(1, 1, (x, z) -> "minecraft:stone");
        assertTrue(one.length < 1_000_000);
        String inflated = reject(new DefaultSubmissionService(64, 1 << 20, 1_000_000), List.of(new NamedFile("one.nbt", one)));
        assertTrue(inflated.contains("exceeds the limit"), inflated);
    }

    @Test
    void unsupportedAndEmptyInputs() {
        assertTrue(reject(new NamedFile("art.png", new byte[] {1})).contains("Unsupported file 'art.png'"));
        assertTrue(reject(svc, List.of()).startsWith("No files"));
        byte[] zip = Fixtures.zip(List.of(new NamedFile("readme.txt", "x".getBytes())));
        assertTrue(reject(new NamedFile("x.zip", zip)).contains("No .nbt files found in the zip"));
        String bad = reject(new NamedFile("bad_0_0.nbt", "garbage".getBytes()));
        assertTrue(bad.contains("'bad_0_0.nbt' is not a readable structure .nbt"), bad);
        String badZip = reject(new NamedFile("broken.zip", new byte[] {'P', 'K', 3, 4, 0, 0}));
        assertTrue(badZip.contains("zip"), badZip);
    }

    // ---------------------------------------------------------------- synthetic structures

    @Test
    void joinedExportIsSplitRowMajorWithTheNooblineSkipped() throws SubmissionException {
        byte[] joined = StructureBuilder.flat(2, 2, (x, z) -> (x + z) % 7 == 0 ? null : "minecraft:" + colour(x, z) + "_wool");
        ParsedSubmission p = parse(new NamedFile("0001_quad.nbt", joined));
        assertEquals(2, p.tilesX());
        assertEquals(2, p.tilesZ());
        assertEquals("quad", p.baseName());
        for (int j = 0; j < 2; j++)
            for (int i = 0; i < 2; i++) {
                TileArt t = p.tiles().get(new TileCoord(i, j));
                for (int pz = 0; pz < 128; pz += 3)
                    for (int px = 0; px < 128; px += 5) {
                        int x = 128 * i + px, z = 128 * j + pz;
                        BlockSpec want = (x + z) % 7 == 0 ? null : b("minecraft:" + colour(x, z) + "_wool");
                        assertEquals(want, t.get(px, pz), "tile " + i + "," + j + " pixel " + px + "," + pz);
                    }
            }
    }

    private static String colour(int x, int z) {
        return List.of("white", "red", "blue", "green").get((x / 128) + 2 * (z / 128));
    }

    @Test
    void topmostEntryDecidesAndSupportAndNooblineAreIgnored() throws SubmissionException {
        StructureBuilder b = new StructureBuilder(128, 4, 129);
        int carpet = b.palette("minecraft:red_carpet");
        int air = b.palette("minecraft:air");
        int lichen = b.palette("minecraft:glow_lichen[down=true]");
        int artCobble = b.palette("minecraft:cobblestone");
        int support = b.palette("minecraft:cobblestone");
        for (int x = 0; x < 128; x++) b.block(x, 3, 0, support);
        b.block(0, 2, 1, carpet).block(0, 1, 1, support);
        b.block(1, 2, 1, air).block(1, 1, 1, support);
        b.block(2, 2, 1, lichen);
        b.block(3, 2, 1, artCobble).block(3, 1, 1, support).block(3, 0, 1, support);
        b.block(4, 2, 2, support);
        ParsedSubmission p = parse(new NamedFile("synthetic.nbt", b.gzip()));
        TileArt t = p.tiles().get(new TileCoord(0, 0));
        assertEquals(b("minecraft:red_carpet"), t.get(0, 0));
        assertNull(t.get(1, 0), "air on top = transparent");
        assertEquals(BlockSpec.parse("minecraft:glow_lichen[down=true]"), t.get(2, 0));
        assertEquals(b("minecraft:cobblestone"), t.get(3, 0), "art may use the support block's id");
        assertEquals(b("minecraft:cobblestone"), t.get(4, 1), "a lone top block is art whatever its palette index");
        assertNull(t.get(5, 0), "columns without entries are empty");
        assertEquals(4, t.nonEmptyCount());
        assertEquals(4189, p.dataVersion());
    }

    @Test
    void airAboveAnotherHeightDoesNotCountAsStaircase() throws SubmissionException {
        StructureBuilder b = new StructureBuilder(128, 4, 129);
        int stone = b.palette("stone");
        int air = b.palette("air");
        b.block(0, 2, 1, stone).block(1, 3, 1, air).block(2, 0, 1, air);
        TileArt t = parse(new NamedFile("x.nbt", b.gzip())).tiles().get(new TileCoord(0, 0));
        assertEquals(1, t.nonEmptyCount());
    }

    @Test
    void staircaseIsDetectedOnSyntheticData() {
        StructureBuilder b = new StructureBuilder(128, 5, 129);
        int stone = b.palette("stone");
        b.block(0, 2, 1, stone).block(1, 4, 7, stone).block(2, 1, 9, stone);
        String m = reject(new NamedFile("stairs_0_0.nbt", b.gzip()));
        assertTrue(m.contains("'stairs_0_0.nbt': not flat: the top blocks sit at heights 1..4 (staircased or 3D art)"), m);
    }

    @Test
    void transparentTilesAreWarnedAbout() throws SubmissionException {
        byte[] joined = StructureBuilder.flat(2, 1, (x, z) -> x < 128 ? "minecraft:stone" : null);
        ParsedSubmission p = parse(new NamedFile("half.nbt", joined));
        assertEquals(List.of("Tile 1,0 is fully transparent; nothing will be built there."), p.warnings());
    }

    @Test
    void leadingZeroCoordinatesAndCaseInsensitiveExtension() throws SubmissionException {
        ParsedSubmission p = parse(Fixtures.renamed(Fixtures.A, "Art_00_00.NBT"), Fixtures.renamed(Fixtures.E, "Art_01_00.NBT"));
        assertEquals(2, p.tilesX());
        assertEquals("Art", p.baseName());
    }

    @Test
    void baseNameIsSanitised() {
        assertEquals("sizedreisen", DefaultSubmissionService.baseName(List.of("sizedreisen", "sizedreisen"), null));
        assertEquals("art", DefaultSubmissionService.baseName(List.of("art_a", "art_b"), null));
        assertEquals("My_Art_v2", DefaultSubmissionService.baseName(List.of("My Art?v2"), null).replace(' ', '_'));
        assertEquals("pack", DefaultSubmissionService.baseName(List.of("", ""), "0002_pack"));
        assertEquals("Sunset", DefaultSubmissionService.baseName(List.of("mapart_0_0", "mapart_1_0"), "Sunset"),
            "a zip's own name before the names inside it");
        assertEquals("mapart", DefaultSubmissionService.baseName(List.of("mapart_0_0", "mapart_1_0"), "§§"));
        assertEquals("mapart", DefaultSubmissionService.baseName(List.of("§§"), null));
        assertEquals(32, DefaultSubmissionService.baseName(List.of("a".repeat(80)), null).length());
    }
}
