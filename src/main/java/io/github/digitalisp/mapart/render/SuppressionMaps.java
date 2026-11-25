package io.github.digitalisp.mapart.render;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * What a colour-suppression map shows (docs/COLOR_SUPPRESSION.md), as packed map bytes ({@code colourId << 2 |
 * brightness}, 0 = no expectation), and the upload preview. Pure.
 * <p>
 * Vanilla 1.21.4 {@code MapItem.update} at scale 0: a pixel shows the highest block of its column whose map colour is
 * not NONE (plain glass is NONE), shaded by {@code d = (h - hNorth) * 4 / 5 + (((col + row) & 1) - 0.5) * 0.4} in Java
 * doubles: HIGH above 0.6, LOW below -0.6, else NORMAL. So one block up is HIGH and one down LOW on both parities
 * (0.6000000000000001 > 0.6). Row 0 is shaded against the noobline row. Below the bottom glass there is nothing of the
 * map: any block there is lower than both layers, so a pixel with an empty column north of it is HIGH.
 * <ul>
 *   <li>{@link #afterFill}: both layers built, every pixel written (the map filled before the ride).</li>
 *   <li>{@link #expected}: the finished map. Recessive pixels keep their value from the fill; dominant pixels are
 *       written again during the ride, after the top layer of their column pair is gone, from the bottom layer
 *       alone. The north neighbour is in the same column, so only that column's layers matter.</li>
 * </ul>
 */
public final class SuppressionMaps {
    private static final int SIZE = TileArt.SIZE;
    /** Height of anything under the bottom glass, relative to the bottom layer's level 0. */
    static final int BELOW = -2;
    private static final int GAP = 8;

    private SuppressionMaps() { }

    /** The finished map, after the ride (and before locking). */
    public static byte[] expected(SuppressionArt art, BlockCatalog catalog) {
        return render(art, catalog, true);
    }

    /** The map right after the fill, both layers in place (dominant pixels show the top layer's fillers). */
    public static byte[] afterFill(SuppressionArt art, BlockCatalog catalog) {
        return render(art, catalog, false);
    }

    /** One layer seen from above: its highest block per cell, NORMAL on the glass, HIGH one block up. */
    public static byte[] layer(SuppressionArt art, int layer, BlockCatalog catalog) {
        byte[] out = new byte[SIZE * SIZE];
        Map<BlockSpec, Integer> ids = new HashMap<>();
        for (int row = 0; row < SIZE; row++)
            for (int col = 0; col < SIZE; col++) {
                int level = art.topLevel(layer, col, row);
                if (level < 0) continue;
                int id = ids.computeIfAbsent(art.get(layer, level, col, row), catalog::mapColorId);
                if (id > 0) out[row * SIZE + col] = (byte) (id << 2 | (level == 1 ? 2 : 1));
            }
        return out;
    }

    /**
     * Vanilla brightness (0 LOW, 1 NORMAL, 2 HIGH) of a non-water pixel at height {@code h} whose north neighbour is at
     * {@code hNorth}.
     */
    public static int brightness(int h, int hNorth, int col, int row) {
        double d = (h - hNorth) * 4.0 / 5.0 + ((double) ((col + row) & 1) - 0.5) * 0.4;
        return d > 0.6 ? 2 : d < -0.6 ? 0 : 1;
    }

    private static byte[] render(SuppressionArt art, BlockCatalog catalog, boolean afterRide) {
        byte[] out = new byte[SIZE * SIZE];
        Map<BlockSpec, Integer> ids = new HashMap<>();
        for (int row = 0; row < SIZE; row++)
            for (int col = 0; col < SIZE; col++) {
                boolean bottomOnly = afterRide && SuppressionArt.dominant(col, row);
                int h = height(art, col, row, bottomOnly);
                if (h == BELOW) continue;
                BlockSpec b = topBlock(art, col, row, bottomOnly);
                int id = ids.computeIfAbsent(b, catalog::mapColorId);
                if (id <= 0) continue;
                int hn = height(art, col, row - 1, bottomOnly);
                out[row * SIZE + col] = (byte) (id << 2 | brightness(h, hn, col, row));
            }
        return out;
    }

    private static int height(SuppressionArt art, int col, int row, boolean bottomOnly) {
        if (!bottomOnly) {
            int t = art.topLevel(SuppressionArt.TOP, col, row);
            if (t >= 0) return SuppressionArt.LAYER_GAP + t;
        }
        int b = art.topLevel(SuppressionArt.BOTTOM, col, row);
        return b >= 0 ? b : BELOW;
    }

    private static BlockSpec topBlock(SuppressionArt art, int col, int row, boolean bottomOnly) {
        if (!bottomOnly) {
            BlockSpec t = art.top(SuppressionArt.TOP, col, row);
            if (t != null) return t;
        }
        return art.top(SuppressionArt.BOTTOM, col, row);
    }

    /**
     * The upload preview: bottom layer, top layer and the finished map side by side, {@code scale} pixels per map
     * pixel (clamped to 1..8), 8 transparent pixels between them.
     */
    public static byte[] previewPng(SuppressionArt art, BlockCatalog catalog, ColorResolver colors, int scale) {
        int s = Math.max(1, Math.min(8, scale));
        byte[][] panels = {layer(art, SuppressionArt.BOTTOM, catalog), layer(art, SuppressionArt.TOP, catalog),
            expected(art, catalog)};
        int side = SIZE * s;
        int w = panels.length * side + (panels.length - 1) * GAP;
        int[] px = new int[w * side];
        int[] lut = new int[256];
        for (int v = 4; v < 256; v++) lut[v] = colors.argbPacked((byte) v);
        for (int p = 0; p < panels.length; p++) {
            int ox = p * (side + GAP);
            for (int i = 0; i < SIZE * SIZE; i++) {
                int argb = lut[panels[p][i] & 0xFF];
                if (argb == 0) continue;
                int x0 = ox + (i % SIZE) * s, y0 = (i / SIZE) * s;
                for (int y = y0; y < y0 + s; y++)
                    for (int x = x0; x < x0 + s; x++) px[y * w + x] = argb;
            }
        }
        BufferedImage img = new BufferedImage(w, side, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, side, px, 0, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG writer available");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
