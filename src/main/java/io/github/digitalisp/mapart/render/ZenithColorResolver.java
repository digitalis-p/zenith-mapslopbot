package io.github.digitalisp.mapart.render;

import com.zenith.feature.map.Brightness;

import static com.zenith.Globals.MAP_BLOCK_COLOR;

/**
 * {@link ColorResolver} over ZenithProxy's MapBlockColorManager (vanilla map colour table, 1.21.4).
 * Colour id 0 (and unknown ids) are transparent (ARGB 0). Thread-safe (read-only table).
 */
public final class ZenithColorResolver implements ColorResolver {
    private static final Brightness[] BRIGHTNESS = Brightness.values(); // LOW, NORMAL, HIGH, LOWEST = ids 0..3

    @Override
    public int argb(int mapColorId, int brightness) {
        if (mapColorId <= 0) return 0;
        int rgb = MAP_BLOCK_COLOR.getColor(mapColorId);
        if (rgb == 0) return 0;
        return MAP_BLOCK_COLOR.calculateRGBColorI(rgb, BRIGHTNESS[brightness & 3]);
    }
}
