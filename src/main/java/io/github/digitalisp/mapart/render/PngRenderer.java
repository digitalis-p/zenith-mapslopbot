package io.github.digitalisp.mapart.render;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Headless PNG renderer (BufferedImage + ImageIO). {@code scale} is clamped to 1..16 and further reduced so
 * that no image side exceeds {@link #MAX_SIDE} pixels. Previews draw a 1px separator between tiles.
 */
public final class PngRenderer implements Renderer {
    public static final int MAX_SIDE = 4096;
    public static final int MAGENTA = 0xFFFF00FF;
    /** Tile separator colour (semi-transparent grey). */
    public static final int SEPARATOR = 0x80808080;

    private static final int SIZE = TileArt.SIZE;
    private static final int PIXELS = SIZE * SIZE;

    @Override
    public byte[] preview(Map<TileCoord, TileArt> tiles, int tilesX, int tilesZ, BlockCatalog catalog,
                          ColorResolver colors, int scale) {
        if (tilesX <= 0 || tilesZ <= 0) throw new IllegalArgumentException("empty grid " + tilesX + "x" + tilesZ);
        int sep = tilesX * tilesZ > 1 ? 1 : 0;
        int s = clampScale(scale, Math.max(tilesX, tilesZ) * SIZE, Math.max(tilesX, tilesZ) - 1);
        int w = tilesX * SIZE * s + (tilesX - 1) * sep;
        int h = tilesZ * SIZE * s + (tilesZ - 1) * sep;
        int[] px = new int[w * h];
        if (sep > 0) {
            for (int i = 1; i < tilesX; i++) {
                int x = i * (SIZE * s + 1) - 1;
                for (int y = 0; y < h; y++) px[y * w + x] = SEPARATOR;
            }
            for (int j = 1; j < tilesZ; j++) {
                int y = j * (SIZE * s + 1) - 1;
                for (int x = 0; x < w; x++) px[y * w + x] = SEPARATOR;
            }
        }
        Map<BlockSpec, Integer> cache = new HashMap<>();
        for (var e : tiles.entrySet()) {
            TileCoord c = e.getKey();
            if (c.x() < 0 || c.z() < 0 || c.x() >= tilesX || c.z() >= tilesZ || e.getValue() == null) continue;
            int ox = c.x() * (SIZE * s + sep);
            int oy = c.z() * (SIZE * s + sep);
            TileArt art = e.getValue();
            for (int pz = 0; pz < SIZE; pz++)
                for (int x = 0; x < SIZE; x++) {
                    BlockSpec b = art.get(x, pz);
                    if (b == null) continue;
                    int argb = cache.computeIfAbsent(b, k -> {
                        int id = catalog.mapColorId(k);
                        return id <= 0 ? 0 : colors.argb(id, 1);
                    });
                    fill(px, w, ox + x * s, oy + pz * s, s, argb);
                }
        }
        return png(px, w, h);
    }

    @Override
    public byte[] mapData(byte[] packedColors, ColorResolver colors, int scale) {
        checkMap(packedColors, "packedColors");
        int s = clampScale(scale, SIZE, 0);
        int w = SIZE * s;
        int[] px = new int[w * w];
        int[] lut = palette(colors);
        for (int i = 0; i < PIXELS; i++)
            fill(px, w, (i % SIZE) * s, (i / SIZE) * s, s, lut[packedColors[i] & 0xFF]);
        return png(px, w, w);
    }

    /**
     * Mismatch = {@code actual[i] != expected[i]} where the expected colour id is non-zero; expected bytes 0..3
     * mean "no expectation" (empty pixels show the floor). Mismatches are magenta, with the actual colour kept
     * inside a magenta outline when {@code scale ≥ 3}.
     */
    @Override
    public byte[] mapDiff(byte[] actualPacked, byte[] expectedPacked, ColorResolver colors, int scale) {
        checkMap(actualPacked, "actualPacked");
        checkMap(expectedPacked, "expectedPacked");
        int s = clampScale(scale, SIZE, 0);
        int w = SIZE * s;
        int[] px = new int[w * w];
        int[] lut = palette(colors);
        for (int i = 0; i < PIXELS; i++) {
            int x = (i % SIZE) * s, y = (i / SIZE) * s;
            int actual = lut[actualPacked[i] & 0xFF];
            boolean bad = (expectedPacked[i] & 0xFF) >> 2 != 0 && actualPacked[i] != expectedPacked[i];
            if (!bad) {
                fill(px, w, x, y, s, actual);
            } else if (s < 3) {
                fill(px, w, x, y, s, MAGENTA);
            } else {
                fill(px, w, x, y, s, MAGENTA);
                fill(px, w, x + 1, y + 1, s - 2, actual == 0 ? MAGENTA : actual);
            }
        }
        return png(px, w, w);
    }

    /** Number of mismatching pixels under the {@link #mapDiff} rule. */
    public static int countMismatches(byte[] actualPacked, byte[] expectedPacked) {
        checkMap(actualPacked, "actualPacked");
        checkMap(expectedPacked, "expectedPacked");
        int n = 0;
        for (int i = 0; i < PIXELS; i++)
            if ((expectedPacked[i] & 0xFF) >> 2 != 0 && actualPacked[i] != expectedPacked[i]) n++;
        return n;
    }

    private static int[] palette(ColorResolver colors) {
        int[] lut = new int[256];
        for (int v = 4; v < 256; v++) lut[v] = colors.argbPacked((byte) v);
        return lut;
    }

    private static int clampScale(int scale, int side, int seps) {
        int s = Math.max(1, Math.min(16, scale));
        while (s > 1 && (long) side * s + seps > MAX_SIDE) s--;
        return s;
    }

    private static void fill(int[] px, int w, int x0, int y0, int s, int argb) {
        if (argb == 0) return;
        for (int y = y0; y < y0 + s; y++) {
            int row = y * w;
            for (int x = x0; x < x0 + s; x++) px[row + x] = argb;
        }
    }

    private static void checkMap(byte[] data, String what) {
        if (data == null || data.length != PIXELS)
            throw new IllegalArgumentException(what + " must hold " + PIXELS + " bytes, got "
                + (data == null ? "null" : data.length));
    }

    private static byte[] png(int[] px, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG writer available");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
