package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.testutil.StructureBuilder;

import java.util.function.Consumer;

/**
 * Synthetic colour-suppression exports, shaped like PNG-to-NBT's "Suppress (2-Layer)" output. Structure-local:
 * x 0..127 = map columns, z 0 = noobline, z 1..128 = map rows.
 */
final class SuppressionExports {
    private SuppressionExports() { }

    /**
     * CrubTech layout, 128 x 16 x 129: y 0 lower art (dominant pixels + resin fillers), y 1 raised lower fillers,
     * y 13 a full plain-glass floor, y 14 upper art (recessive pixels + moss/resin fillers), y 15 raised moss fillers.
     * {@code dense}: every column has lower and upper art; otherwise only a handful of columns have lower art, so
     * the glass floor is the only structural sign.
     */
    static byte[] crubtech(boolean dense, Consumer<StructureBuilder> setup) {
        StructureBuilder b = new StructureBuilder(128, 16, 129).author(StructureBuilder.PNG_TO_NBT);
        if (setup != null) setup.accept(b);
        int glass = b.palette("minecraft:glass");
        int resin = b.palette("minecraft:resin_block");
        int moss = b.palette("minecraft:moss_block");
        int black = b.palette("minecraft:black_concrete");
        int white = b.palette("minecraft:white_wool");
        int cobble = b.palette("minecraft:cobblestone");
        for (int x = 0; x < 128; x++) {
            b.block(x, 14, 0, cobble);
            for (int z = 1; z <= 128; z++) {
                boolean lower = dense || (x < 5 && z < 3);
                if (lower) {
                    b.block(x, 0, z, (x + z) % 3 == 0 ? black : resin);
                    if ((7 * x + z) % 5 == 0) b.block(x, 1, z, resin);
                }
                b.block(x, 13, z, glass);
                if (dense || (x + z) % 9 == 0) {
                    b.block(x, 14, z, (x + z) % 3 == 1 ? white : ((x + z) % 2 == 0 ? moss : resin));
                    if ((x + 3 * z) % 4 == 0) b.block(x, 15, z, moss);
                }
            }
        }
        return b.gzip();
    }

    /** Non-CrubTech two-layer map, 128 x 6 x 129, no glass: lower art at y 0, upper art at y 5 in most columns. */
    static byte[] twoLayerGap5() {
        StructureBuilder b = new StructureBuilder(128, 6, 129).author(StructureBuilder.PNG_TO_NBT);
        int black = b.palette("minecraft:black_concrete");
        int resin = b.palette("minecraft:resin_block");
        int white = b.palette("minecraft:white_wool");
        for (int x = 0; x < 128; x++)
            for (int z = 1; z <= 128; z++) {
                b.block(x, 0, z, (x * z) % 2 == 0 ? black : resin);
                if ((x + z) % 4 != 0) b.block(x, 5, z, white);
            }
        return b.gzip();
    }
}
