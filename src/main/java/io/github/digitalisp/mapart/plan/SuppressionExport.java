package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Schematic;
import io.github.digitalisp.mapart.model.SuppressionArt;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a PNG-to-NBT "Suppress (2-Layer)" export made with the CrubTech option (docs/COLOR_SUPPRESSION.md) into a
 * {@link SuppressionArt}. Pure. The layout, checked against PNG-to-NBT's generator: 128 x 15..16 x 129; z 0 the
 * noobline, z 1..128 map rows 0..127; a plain-glass floor G covering every map cell (y 13 in a file without water);
 * the bottom layer at G-13 and G-12, the top layer at G+1 and G+2, so the layers sit 14 apart as on the platform.
 * Anything else is refused with the reason: water pixels (panes below the bottom layer), late pixels (blocks above
 * the top layer), "Support: Steps" (a support under a raised block, moss left on the bottom layer), blocks between
 * the layers, and transparent pixels unless allowed.
 */
public final class SuppressionExport {
    static final String GLASS = "minecraft:glass";
    /** PNG-to-NBT's shading fillers; the CrubTech preset uses neither as a colour block. */
    static final Set<String> FILLERS = Set.of("minecraft:moss_block", "minecraft:resin_block");
    private static final int MAP = TileArt.SIZE;
    /** How to export a map this platform takes; refusals end with it. */
    public static final String HOW = "Export from PNG-to-NBT (evmodder.net/PNG-to-NBT): CrubTech preset with the CrubTech "
        + "option, build mode Suppress (2-Layer), Support None, an opaque image without water.";

    private SuppressionExport() { }

    /**
     * @param art      the map, or null when refused
     * @param problem  why it is refused, or null
     * @param empty    pixels with no colour block of their own (transparent in the image)
     */
    public record Result(SuppressionArt art, String problem, List<String> warnings, int glassLevel, int empty) { }

    /**
     * The map in {@code s}, or a refusal; null when {@code s} has no plain-glass floor under every map cell, i.e. is
     * not a CrubTech two-layer export at all (then the normal checks apply).
     */
    public static Result read(Schematic s, boolean allowTransparent) {
        if (s.sizeX() != MAP || s.sizeZ() != MAP + 1) return null;
        List<BlockSpec> palette = s.palette();
        boolean[] glass = new boolean[palette.size()];
        boolean[] air = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            glass[i] = palette.get(i).id().equals(GLASS) && palette.get(i).props().isEmpty();
            air[i] = palette.get(i).isAir();
        }
        int[] glassAt = new int[Math.max(1, s.sizeY())];
        for (Schematic.Block b : s.blocks())
            if (b.z() >= 1 && glass[b.state()] && b.y() >= 0 && b.y() < glassAt.length) glassAt[b.y()]++;
        int g = -1;
        for (int y = 0; y < glassAt.length; y++)
            if (glassAt[y] == MAP * MAP) {
                if (g >= 0) return refused("two full glass floors (heights " + g + " and " + y + "); a CrubTech "
                    + "2-layer export has one", g);
                g = y;
            }
        if (g < 0) return null;

        SuppressionArt art = new SuppressionArt();
        int water = 0, late = 0, between = 0, steps = 0, odd = 0;
        boolean[] seenGlass = new boolean[MAP * MAP];
        for (Schematic.Block b : s.blocks()) {
            if (air[b.state()]) continue;
            BlockSpec spec = palette.get(b.state());
            int y = b.y(), row = b.z() - 1, col = b.x();
            if (isWater(spec)) {
                water++;
                continue;
            }
            if (y == g) {
                if (glass[b.state()] && row >= 0) seenGlass[row * MAP + col] = true;
                else odd++;
                continue;
            }
            int layer, level;
            if (y == g - 13 || y == g - 12) {
                layer = SuppressionArt.BOTTOM;
                level = y - (g - 13);
            } else if (y == g + 1 || y == g + 2) {
                layer = SuppressionArt.TOP;
                level = y - (g + 1);
            } else if (y < g - 13) {
                water++;
                continue;
            } else if (y > g + 2) {
                late++;
                continue;
            } else {
                between++;
                continue;
            }
            art.set(layer, level, col, row, spec);
        }
        for (int layer = 0; layer < SuppressionArt.LAYERS; layer++)
            for (int row = SuppressionArt.NOOBLINE; row < MAP; row++)
                for (int col = 0; col < MAP; col++)
                    if (art.get(layer, 0, col, row) != null && art.get(layer, 1, col, row) != null) steps++;

        List<String> problems = new ArrayList<>();
        if (water > 0) problems.add(count(water, "water block") + " below the bottom layer (water pixels): the platform "
            + "has no water layers under its glass");
        if (late > 0) problems.add(count(late, "block") + " above the top layer (a Late-Fillers or Late-Pairs build): "
            + "the platform has no late floor");
        if (between > 0) problems.add(count(between, "block") + " between the two layers (not a Suppress (2-Layer) "
            + "CrubTech layout)");
        if (odd > 0) problems.add(count(odd, "block") + " in the glass floor that are not plain glass");
        if (steps > 0) problems.add(count(steps, "column") + " with a support under a raised block (\"Support: Steps\"): "
            + "the platform does not clear supports left on the bottom layer");
        if (!problems.isEmpty()) return refused(String.join("; ", problems), g);

        int empty = 0;
        for (int row = 0; row < MAP; row++)
            for (int col = 0; col < MAP; col++)
                if (!hasColour(art, col, row)) empty++;
        List<String> warnings = new ArrayList<>();
        if (empty > 0) {
            if (!allowTransparent)
                return refused(count(empty, "transparent pixel") + ": they would show what lies under the glass, and "
                    + "the platform cannot shade the pixels south of them (late pixels)", g);
            warnings.add(count(empty, "pixel") + " transparent (allowed by the platform's allowTransparent): they show "
                + "whatever lies under the glass.");
        }
        return new Result(art, null, List.copyOf(warnings), g, empty);
    }

    /** The pixel has a colour block of its own: dominant on the bottom layer, recessive on either layer. */
    static boolean hasColour(SuppressionArt art, int col, int row) {
        if (SuppressionArt.dominant(col, row)) return colour(art.top(SuppressionArt.BOTTOM, col, row));
        return colour(art.top(SuppressionArt.TOP, col, row)) || colour(art.top(SuppressionArt.BOTTOM, col, row));
    }

    private static boolean colour(BlockSpec b) {
        return b != null && !FILLERS.contains(b.id());
    }

    private static boolean isWater(BlockSpec b) {
        return b.id().equals("minecraft:water") || "true".equals(b.props().get("waterlogged"))
            || b.id().endsWith("glass_pane");
    }

    private static Result refused(String problem, int g) {
        return new Result(null, problem, List.of(), g, 0);
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    /** The upload's map name from a PNG-to-NBT file stem: without its "-suppress_crubtech" mark. */
    static String stem(String stem) {
        return stem.replaceFirst("(?i)-suppress_[a-z0-9_]*$", "");
    }
}
