package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.render.ColorResolver;

/** Deterministic opaque colour per (id, brightness); id 0 is transparent. */
public final class FakeColors implements ColorResolver {
    @Override
    public int argb(int mapColorId, int brightness) {
        if (mapColorId == 0) return 0;
        return 0xFF000000 | (mapColorId * 4 + brightness) * 0x010203;
    }
}
