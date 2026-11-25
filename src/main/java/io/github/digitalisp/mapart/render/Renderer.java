package io.github.digitalisp.mapart.render;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;

import java.util.Map;

/**
 * PNG rendering for Discord reports. Pure (java.awt.image + ImageIO, headless). CONTRACT (lead-owned).
 */
public interface Renderer {
    /**
     * Whole-art preview: tiles laid out on a tilesX x tilesZ grid, flat NORMAL shading, empty pixels
     * transparent, 1px tile separators optional. {@code scale} = output pixels per map pixel.
     */
    byte[] preview(Map<TileCoord, TileArt> tiles, int tilesX, int tilesZ, BlockCatalog catalog,
                   ColorResolver colors, int scale);

    /** Renders 16384 packed map colour bytes (in-game map data) to a 128*scale square PNG. */
    byte[] mapData(byte[] packedColors, ColorResolver colors, int scale);

    /**
     * Diff image for verification failures: actual map data, with mismatching pixels (vs the expected
     * packed colours) outlined/highlighted in magenta. Expected bytes with colour id 0 mean "no
     * expectation" (empty pixel) and are never flagged.
     */
    byte[] mapDiff(byte[] actualPacked, byte[] expectedPacked, ColorResolver colors, int scale);
}
