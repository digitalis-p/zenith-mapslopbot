package io.github.digitalisp.mapart.nbt;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Schematic;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.NbtWriter;
import io.github.digitalisp.mapart.testutil.StructureBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureNbtParserTest {
    @Test
    void parsesMapArtCraftExport() throws NbtException {
        Schematic s = StructureNbtParser.parse(Fixtures.bytes(Fixtures.A));
        assertEquals(128, s.sizeX());
        assertEquals(3, s.sizeY());
        assertEquals(129, s.sizeZ());
        assertEquals(4325, s.dataVersion());
        assertEquals(9, s.palette().size());
        assertEquals(BlockSpec.of("minecraft:cobblestone"), s.palette().getLast());
        assertEquals(BlockSpec.of("minecraft:light_blue_carpet"), s.palette().getFirst());
        assertEquals(32896, s.blocks().size());
        assertEquals(new Schematic.Block(0, 2, 0, 8), s.blocks().getFirst());
        assertEquals(new Schematic.Block(0, 2, 1, 3), s.blocks().get(1));
        assertEquals(new Schematic.Block(0, 1, 1, 8), s.blocks().get(2));
    }

    @Test
    void keepsBlockProperties() throws NbtException {
        Schematic s = StructureNbtParser.parse(Fixtures.bytes(Fixtures.B));
        assertTrue(s.palette().contains(BlockSpec.parse("minecraft:oak_log[axis=y]")));
        assertTrue(s.palette().contains(BlockSpec.parse("minecraft:glow_lichen[down=true]")));
        assertEquals(26, s.palette().size());
        assertEquals(81, s.sizeY());
    }

    @Test
    void readsUncompressedFilesToo() throws NbtException {
        StructureBuilder b = new StructureBuilder(2, 1, 2);
        int stone = b.palette("stone");
        b.block(1, 0, 1, stone);
        Schematic s = StructureNbtParser.parse(NbtWriter.raw(b.root()));
        assertEquals(List.of(new Schematic.Block(1, 0, 1, 0)), s.blocks());
        assertEquals(BlockSpec.of("minecraft:stone"), s.palette().getFirst());
    }

    @Test
    void duplicatePaletteNamesStayDistinctByIndex() throws NbtException {
        StructureBuilder b = new StructureBuilder(1, 2, 1);
        int a = b.palette("minecraft:cobblestone");
        int c = b.palette("minecraft:cobblestone");
        b.block(0, 1, 0, a).block(0, 0, 0, c);
        Schematic s = StructureNbtParser.parse(b.gzip());
        assertEquals(2, s.palette().size());
        assertEquals(0, s.blocks().get(0).state());
        assertEquals(1, s.blocks().get(1).state());
    }

    @Test
    void skipsUnknownTagsAndBlockNbt() throws NbtException {
        StructureBuilder b = new StructureBuilder(1, 1, 1);
        b.palette("minecraft:chest[facing=north]");
        Map<String, Object> root = b.root();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("nbt", new LinkedHashMap<>(Map.of("Items", List.of(), "id", "minecraft:chest")));
        block.put("pos", new int[] {0, 0, 0});
        block.put("state", 0);
        root.put("blocks", List.of(block));
        root.put("extra", new LinkedHashMap<>(Map.of("l", new long[] {1}, "d", 2.0, "f", 1f, "s", (short) 1, "b", (byte) 1)));
        root.put("entities", List.of(new LinkedHashMap<>(Map.of("pos", List.of(0.5, 0.5, 0.5)))));
        Schematic s = StructureNbtParser.parse(NbtWriter.gzip(root));
        assertEquals(List.of(new Schematic.Block(0, 0, 0, 0)), s.blocks());
        assertEquals("north", s.palette().getFirst().props().get("facing"));
    }

    @Test
    void acceptsMultiPaletteStructures() throws NbtException {
        StructureBuilder b = new StructureBuilder(1, 1, 1);
        b.palette("stone");
        b.block(0, 0, 0, 0);
        Map<String, Object> root = b.root();
        Object palette = root.remove("palette");
        root.put("palettes", List.of(palette));
        assertEquals(BlockSpec.of("stone"), StructureNbtParser.parse(NbtWriter.gzip(root)).palette().getFirst());
    }

    @Test
    void rejectsStateIndexOutsidePalette() {
        StructureBuilder b = new StructureBuilder(1, 1, 1);
        b.palette("stone");
        b.block(0, 0, 0, 3);
        NbtException e = assertThrows(NbtException.class, () -> StructureNbtParser.parse(b.gzip()));
        assertTrue(e.getMessage().contains("palette index 3"), e.getMessage());
    }

    @Test
    void rejectsBlocksOutsideTheSize() {
        StructureBuilder b = new StructureBuilder(1, 1, 1);
        b.palette("stone");
        b.block(0, 1, 0, 0);
        NbtException e = assertThrows(NbtException.class, () -> StructureNbtParser.parse(b.gzip()));
        assertTrue(e.getMessage().contains("outside"), e.getMessage());
    }

    @Test
    void readsTheAuthor() throws NbtException {
        assertEquals(StructureBuilder.MAPARTCRAFT, StructureNbtParser.parse(Fixtures.bytes(Fixtures.A)).author());
        StructureBuilder b = new StructureBuilder(1, 1, 1).author(StructureBuilder.PNG_TO_NBT).pngToNbtOrder();
        b.block(0, 0, 0, b.palette("stone"));
        assertEquals(StructureBuilder.PNG_TO_NBT, StructureNbtParser.parse(b.gzip()).author());
        b.author(null);
        assertEquals("", StructureNbtParser.parse(b.gzip()).author(), "no author tag");
        Map<String, Object> root = b.root();
        root.put("author", 7);
        assertEquals("", StructureNbtParser.parse(NbtWriter.gzip(root)).author(), "a non-string author is skipped");
    }

    @Test
    void idKeyedPaletteIsTheNewerFormatWhereverDataVersionAndAuthorSit() {
        for (boolean pngToNbtOrder : List.of(true, false)) {
            StructureBuilder b = new StructureBuilder(2, 1, 2).dataVersion(5023).author(StructureBuilder.PNG_TO_NBT).idKeys();
            if (pngToNbtOrder) b.pngToNbtOrder(); // DataVersion, size, author before the palette
            b.block(0, 0, 1, b.palette("minecraft:oak_log[axis=y]")).block(1, 0, 1, b.palette("stone"));
            if (!pngToNbtOrder) {
                List<String> keys = List.copyOf(b.root().keySet());
                assertTrue(keys.indexOf("DataVersion") > keys.indexOf("palette") && keys.indexOf("author") > keys.indexOf("palette"));
            }
            StructureNbtParser.NewerFormatException e = assertThrows(StructureNbtParser.NewerFormatException.class,
                () -> StructureNbtParser.parse(b.gzip()));
            assertEquals(5023, e.dataVersion());
            assertEquals(StructureBuilder.PNG_TO_NBT, e.author());
            assertEquals("palette uses the 'id' keys of newer Minecraft structure files (DataVersion 5023); "
                + "this plugin reads the 1.21.4 format", e.getMessage());
            assertFalse(e.getMessage().contains("has no Name"));
        }
    }

    @Test
    void idKeyedPaletteWithoutDataVersionOrAuthor() {
        StructureBuilder b = new StructureBuilder(1, 1, 1).author(null).idKeys();
        b.block(0, 0, 0, b.palette("stone"));
        Map<String, Object> root = b.root();
        root.remove("DataVersion");
        StructureNbtParser.NewerFormatException e = assertThrows(StructureNbtParser.NewerFormatException.class,
            () -> StructureNbtParser.parse(NbtWriter.gzip(root)));
        assertEquals(0, e.dataVersion());
        assertEquals("", e.author());
        assertTrue(e.getMessage().contains("(no DataVersion)"), e.getMessage());
    }

    @Test
    void idKeyedFirstPaletteOfPalettesIsTheNewerFormatToo() {
        StructureBuilder b = new StructureBuilder(1, 1, 1).dataVersion(4790).idKeys();
        b.block(0, 0, 0, b.palette("stone"));
        Map<String, Object> root = b.root();
        root.put("palettes", List.of(root.remove("palette")));
        StructureNbtParser.NewerFormatException e = assertThrows(StructureNbtParser.NewerFormatException.class,
            () -> StructureNbtParser.parse(NbtWriter.gzip(root)));
        assertEquals(4790, e.dataVersion());
        assertEquals("test", e.author());
    }

    @Test
    void paletteEntryWithNeitherNameNorIdHasNoName() {
        StructureBuilder b = new StructureBuilder(1, 1, 1);
        b.block(0, 0, 0, b.palette("stone"));
        Map<String, Object> root = b.root();
        root.put("palette", List.of(new LinkedHashMap<>(Map.of("Properties", Map.of("axis", "y")))));
        NbtException e = assertThrows(NbtException.class, () -> StructureNbtParser.parse(NbtWriter.gzip(root)));
        assertFalse(e instanceof StructureNbtParser.NewerFormatException);
        assertEquals("palette entry 0 has no Name", e.getMessage());
    }

    @Test
    void rejectsNonStructures() {
        NbtException e = assertThrows(NbtException.class,
            () -> StructureNbtParser.parse(NbtWriter.gzip(Map.of("Data", Map.of("x", 1)))));
        assertTrue(e.getMessage().contains("missing 'size'"), e.getMessage());
        assertThrows(NbtException.class, () -> StructureNbtParser.parse("not nbt at all".getBytes()));
    }
}
