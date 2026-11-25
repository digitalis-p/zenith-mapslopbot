package io.github.digitalisp.mapart.render;

/**
 * Map colour → ARGB. Production impl wraps ZenithProxy's MapBlockColorManager.
 * Brightness follows vanilla: 0 = LOW (dark), 1 = NORMAL, 2 = HIGH (light), 3 = LOWEST.
 * Flat maps use NORMAL everywhere. CONTRACT (lead-owned).
 */
public interface ColorResolver {
    int argb(int mapColorId, int brightness);

    /** Decodes a packed map byte (colorId << 2 | brightness) as stored in map data. 0..3 = transparent. */
    default int argbPacked(byte packed) {
        int v = packed & 0xFF;
        return argb(v >> 2, v & 3);
    }
}
