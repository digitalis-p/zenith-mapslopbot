package io.github.digitalisp.mapart.render;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.plan.DefaultSubmissionService;
import io.github.digitalisp.mapart.testutil.Fixtures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The finished-map renderer against PNG-to-NBT's own intent: {@code full1214.grid.txt.gz} is the colour grid the
 * generator turned into {@code full1214-suppress_crubtech.nbt} (one line per row, "colourId:shade" per column, shade
 * 0 dark, 1 flat, 2 light). Colours come from the real 1.21.4 registry.
 */
class SuppressionMapsTest {
    static SuppressionArt full() throws Exception {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = "cs";
        p.kind = "COLOR_SUPPRESSION";
        p.glassY = List.of(200, 214);
        site.platforms.add(p);
        return new DefaultSubmissionService().parse(List.of(Fixtures.file("png2nbt/full1214-suppress_crubtech.nbt")),
            site).suppression();
    }

    static String[][] grid() throws IOException {
        byte[] gz = Fixtures.bytes("png2nbt/full1214.grid.txt.gz");
        String text;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = text.strip().split("\n");
        String[][] g = new String[128][];
        for (int z = 0; z < 128; z++) g[z] = lines[z].split(" ");
        return g;
    }

    @Test
    void finishedMapIsTheImagePngToNbtWasGiven() throws Exception {
        byte[] map = SuppressionMaps.expected(full(), new ZenithBlockCatalog());
        String[][] g = grid();
        int wrong = 0;
        StringBuilder first = new StringBuilder();
        for (int row = 0; row < 128; row++)
            for (int col = 0; col < 128; col++) {
                String[] want = g[row][col].split(":");
                int id = Integer.parseInt(want[0]), shade = Integer.parseInt(want[1]);
                int v = map[row * 128 + col] & 0xFF;
                if (v >> 2 != id || (v & 3) != shade) {
                    if (wrong++ < 5) first.append(' ').append(col).append(',').append(row).append(" want ")
                        .append(id).append(':').append(shade).append(" got ").append(v >> 2).append(':').append(v & 3);
                }
            }
        assertEquals(0, wrong, "pixels differing from the source image:" + first);
    }

    @Test
    void afterTheFillDominantPixelsUnderAFillerShowIt() throws Exception {
        SuppressionArt a = full();
        ZenithBlockCatalog c = new ZenithBlockCatalog();
        byte[] fill = SuppressionMaps.afterFill(a, c);
        byte[] done = SuppressionMaps.expected(a, c);
        int moss = c.mapColorId(BlockSpec.of("minecraft:moss_block"));
        int resin = c.mapColorId(BlockSpec.of("minecraft:resin_block"));
        int differ = 0, covered = 0;
        for (int row = 0; row < 128; row++)
            for (int col = 0; col < 128; col++) {
                int i = row * 128 + col;
                if (!SuppressionArt.dominant(col, row)) {
                    assertEquals(done[i], fill[i], "recessive pixels are final after the fill: " + col + "," + row);
                    continue;
                }
                if (fill[i] != done[i]) differ++;
                if (a.top(SuppressionArt.TOP, col, row) != null) {
                    covered++;
                    int id = (fill[i] & 0xFF) >> 2;
                    assertTrue(id == moss || id == resin, "green or orange filler at " + col + "," + row);
                }
            }
        assertTrue(covered > 4000, "covered " + covered);
        assertTrue(differ >= covered, differ + " < " + covered);
    }

    @Test
    void vanillaShadingRoundsLikeJava() {
        // one block up is HIGH and one block down LOW on both parities (0.6000000000000001 > 0.6)
        for (int parity = 0; parity < 2; parity++) {
            assertEquals(2, SuppressionMaps.brightness(1, 0, parity, 0));
            assertEquals(0, SuppressionMaps.brightness(0, 1, parity, 0));
            assertEquals(1, SuppressionMaps.brightness(5, 5, parity, 0));
        }
    }

    @Test
    void previewHasThreePanels() throws Exception {
        byte[] png = SuppressionMaps.previewPng(full(), new ZenithBlockCatalog(), (id, b) -> 0xFF000000 | id << 8 | b, 2);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(3 * 256 + 2 * 8, img.getWidth());
        assertEquals(256, img.getHeight());
    }

    @Test
    void smallArtLeavesTransparentPixelsEmpty() throws Exception {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.kind = "COLOR_SUPPRESSION";
        p.glassY = List.of(200, 214);
        p.allowTransparent = true;
        site.platforms.add(p);
        SuppressionArt a = new DefaultSubmissionService().parse(List.of(new NamedFile("s.nbt",
            Fixtures.bytes("png2nbt/small1214-suppress_crubtech.nbt"))), site).suppression();
        byte[] map = SuppressionMaps.expected(a, new ZenithBlockCatalog());
        int set = 0;
        for (int row = 0; row < 128; row++)
            for (int col = 0; col < 128; col++)
                if (map[row * 128 + col] != 0) {
                    set++;
                    assertTrue(col >= 47 && col < 81 && row < 17, col + "," + row);
                }
        assertTrue(set >= 32 * 16, "set " + set);
    }
}
