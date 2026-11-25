package io.github.digitalisp.mapart.catalog;

import io.github.digitalisp.mapart.model.BlockSpec;

/**
 * Eyeblossoms: the only blocks whose map colour changes by itself. The server flips every eyeblossom with the time
 * of day (random ticks, and a flip spreads to the eyeblossoms nearby): open at night, closed by day. In 1.21.4 (2b2t)
 * an open one shows map colour 15 (COLOR_ORANGE, like orange carpet and plain terracotta) and a closed one 49
 * (TERRACOTTA_GREEN). Newer servers changed the closed colour (the 1.21.11 test server shows 6, METAL); the plugin
 * always expects the 1.21.4 colours. For placement and verification both states are one block and one material
 * ({@code ItemIds.material}); only the map fill cares which state the world shows. Pure.
 */
public final class Eyeblossoms {
    private Eyeblossoms() { }

    public static final String OPEN = "minecraft:open_eyeblossom";
    public static final String CLOSED = "minecraft:closed_eyeblossom";

    /** True for either eyeblossom state (namespaced id). */
    public static boolean is(String id) {
        return OPEN.equals(id) || CLOSED.equals(id);
    }

    public static boolean is(BlockSpec spec) {
        return spec != null && is(spec.id());
    }

    /** "close (day)" / "open (night)": what the world must do before a map shows the wanted state. */
    public static String change(boolean wantOpen) {
        return wantOpen ? "open (night)" : "close (day)";
    }
}
