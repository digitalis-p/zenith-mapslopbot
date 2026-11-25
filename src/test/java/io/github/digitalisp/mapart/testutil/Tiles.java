package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.Objects;
import java.util.function.BiFunction;

/** TileArt helpers for tests. */
public final class Tiles {
    private Tiles() { }

    public static boolean equal(TileArt a, TileArt b) {
        for (int pz = 0; pz < TileArt.SIZE; pz++)
            for (int px = 0; px < TileArt.SIZE; px++)
                if (!Objects.equals(a.get(px, pz), b.get(px, pz))) return false;
        return true;
    }

    /** A tile filled from {@code f(px, pz)} (a state string, or null for empty). */
    public static TileArt of(BiFunction<Integer, Integer, String> f) {
        TileArt t = new TileArt();
        for (int pz = 0; pz < TileArt.SIZE; pz++)
            for (int px = 0; px < TileArt.SIZE; px++) {
                String s = f.apply(px, pz);
                if (s != null) t.set(px, pz, BlockSpec.parse(s));
            }
        return t;
    }

    public static TileArt filled(String state) {
        return of((x, z) -> state);
    }
}
