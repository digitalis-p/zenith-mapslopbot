package io.github.digitalisp.mapart.render;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.FakeColors;
import io.github.digitalisp.mapart.testutil.Tiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PngRendererTest {
    private final PngRenderer r = new PngRenderer();
    private final FakeCatalog catalog = new FakeCatalog();
    private final FakeColors colors = new FakeColors();

    private static BufferedImage decode(byte[] png) throws IOException {
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("not a PNG");
        return img;
    }

    private int expected(String state) {
        return colors.argb(catalog.mapColorId(BlockSpec.parse(state)), 1);
    }

    @Test
    void previewOfOneTile() throws IOException {
        TileArt t = Tiles.of((x, z) -> x < 32 && z < 32 ? null : x < 64 ? "minecraft:white_wool" : "minecraft:black_wool");
        BufferedImage img = decode(r.preview(Map.of(new TileCoord(0, 0), t), 1, 1, catalog, colors, 1));
        assertEquals(128, img.getWidth());
        assertEquals(128, img.getHeight());
        assertEquals(0, img.getRGB(0, 0) >>> 24, "empty pixels are transparent");
        assertEquals(expected("minecraft:white_wool"), img.getRGB(40, 0));
        assertEquals(expected("minecraft:black_wool"), img.getRGB(127, 127));
    }

    @Test
    void previewGridHasSeparatorsAndScales() throws IOException {
        Map<TileCoord, TileArt> tiles = new HashMap<>();
        for (int z = 0; z < 2; z++)
            for (int x = 0; x < 4; x++)
                if (!(x == 3 && z == 1)) tiles.put(new TileCoord(x, z), Tiles.filled("minecraft:red_carpet"));
        BufferedImage img = decode(r.preview(tiles, 4, 2, catalog, colors, 2));
        assertEquals(4 * 256 + 3, img.getWidth());
        assertEquals(2 * 256 + 1, img.getHeight());
        assertEquals(PngRenderer.SEPARATOR, img.getRGB(256, 10));
        assertEquals(PngRenderer.SEPARATOR, img.getRGB(10, 256));
        assertEquals(expected("minecraft:red_carpet"), img.getRGB(255, 255));
        assertEquals(expected("minecraft:red_carpet"), img.getRGB(257, 257));
        assertEquals(0, img.getRGB(3 * 257 + 5, 257 + 5) >>> 24, "missing tile stays transparent");
    }

    @Test
    void previewScaleIsCappedForHugeArts() throws IOException {
        Map<TileCoord, TileArt> tiles = Map.of(new TileCoord(0, 0), Tiles.filled("minecraft:stone"));
        BufferedImage img = decode(r.preview(tiles, 8, 8, catalog, colors, 16));
        assertEquals(8 * 128 * 3 + 7, img.getWidth(), "largest scale keeping the side within 4096");
        BufferedImage tiny = decode(r.preview(tiles, 1, 1, catalog, colors, 0));
        assertEquals(128, tiny.getWidth());
    }

    @Test
    void mapDataUsesPackedColours() throws IOException {
        byte[] data = new byte[16384];
        data[0] = (byte) (5 << 2 | 1);
        data[129] = (byte) (61 << 2 | 3);
        data[200] = 2;
        BufferedImage img = decode(r.mapData(data, colors, 2));
        assertEquals(256, img.getWidth());
        assertEquals(colors.argb(5, 1), img.getRGB(0, 0));
        assertEquals(colors.argb(5, 1), img.getRGB(1, 1));
        assertEquals(colors.argb(61, 3), img.getRGB(2, 2));
        assertEquals(0, img.getRGB(2 * (200 % 128), 2 * (200 / 128)) >>> 24, "color id 0 is transparent");
    }

    @Test
    void mapDiffHighlightsMismatchesInMagenta() throws IOException {
        byte[] expected = new byte[16384];
        byte[] actual = new byte[16384];
        Arrays.fill(expected, (byte) (10 << 2 | 1));
        Arrays.fill(actual, (byte) (10 << 2 | 1));
        actual[5] = (byte) (11 << 2 | 1);
        expected[7] = 0;
        actual[7] = (byte) (12 << 2 | 1);
        assertEquals(1, PngRenderer.countMismatches(actual, expected));

        BufferedImage s1 = decode(r.mapDiff(actual, expected, colors, 1));
        assertEquals(PngRenderer.MAGENTA, s1.getRGB(5, 0));
        assertEquals(colors.argb(10, 1), s1.getRGB(4, 0));
        assertEquals(colors.argb(12, 1), s1.getRGB(7, 0), "no expectation = no highlight");

        BufferedImage s4 = decode(r.mapDiff(actual, expected, colors, 4));
        assertEquals(512, s4.getWidth());
        assertEquals(PngRenderer.MAGENTA, s4.getRGB(20, 0), "outline");
        assertEquals(colors.argb(11, 1), s4.getRGB(21, 1), "actual color inside the outline");
    }

    @Test
    void wrongLengthsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> r.mapData(new byte[10], colors, 1));
        assertThrows(IllegalArgumentException.class, () -> r.mapDiff(new byte[16384], new byte[1], colors, 1));
    }
}
