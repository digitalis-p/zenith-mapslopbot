package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.MapView;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.catalog.Eyeblossoms;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * One tile's target art layer placed on a platform, plus the world-vs-plan comparisons the engine
 * needs (inspect, stock check, verify, nuke, map check). Immutable; the comparisons read the world
 * every time, so progress is never remembered, only observed.
 *
 * <p>A lane band's plan ({@link #band}) is the tile's plan filtered to the band's
 * cells: every cell outside the band is left out of every check, as a fixture is (never built, checked or broken; other
 * bots build it), so restock, verify and progress (placed / total = the band's cells) follow from it, and the planner's
 * lanes are clipped to the band ({@link #doneIn}, {@link #notBuildableIn}: a cell outside counts as done).
 */
final class TilePlan {
    static final int SIZE = TileArt.SIZE;
    static final int CELLS = SIZE * SIZE;
    private static final Set<String> FLUIDS = Set.of("minecraft:water", "minecraft:lava", "minecraft:bubble_column");

    final TileArt art;
    final PlatformGeometry geo;
    private final BlockCatalog catalog;
    private final BlockSpec[] target = new BlockSpec[CELLS];
    private final String[] item = new String[CELLS];
    private final Pos[] cells = new Pos[CELLS];
    /**
     * Cells left out of every check: platform fixtures (an actuator on the art layer) and, for a band, the cells outside
     * it. Never built, checked or broken.
     */
    private final boolean[] fixture = new boolean[CELLS];
    /** The lane band this plan is filtered to, or null (the whole tile). */
    final io.github.digitalisp.mapart.coord.TileUnitSpec.@org.jspecify.annotations.Nullable Band band;
    final int total;
    /** Block states in the art that have no placing item (validation should have rejected them). */
    final Map<String, Integer> unplaceable = new TreeMap<>();
    /** Cell indices whose target is an eyeblossom ({@link Eyeblossoms}: the map colour follows the time of day). */
    private final int[] blossomCells;
    /** Some eyeblossom pixel wants the open state (night) / the closed state (day). */
    final boolean blossomsWantOpen;
    final boolean blossomsWantClosed;

    TilePlan(TileArt art, PlatformGeometry geo, BlockCatalog catalog) {
        this(art, geo, catalog, Set.of());
    }

    TilePlan(TileArt art, PlatformGeometry geo, BlockCatalog catalog, Collection<Pos> fixtures) {
        this(art, geo, catalog, fixtures, null);
    }

    /** {@code band}: the plan filtered to that lane band's cells (null: the whole tile). */
    TilePlan(TileArt art, PlatformGeometry geo, BlockCatalog catalog, Collection<Pos> fixtures,
             io.github.digitalisp.mapart.coord.TileUnitSpec.@org.jspecify.annotations.Nullable Band band) {
        this.art = art;
        this.geo = geo;
        this.catalog = catalog;
        this.band = band;
        int n = 0;
        List<Integer> blossoms = new ArrayList<>();
        boolean open = false, closed = false;
        for (int pz = 0; pz < SIZE; pz++) {
            for (int px = 0; px < SIZE; px++) {
                int i = pz * SIZE + px;
                cells[i] = geo.cell(px, pz);
                if (fixtures.contains(cells[i]) || (band != null && !band.contains(px, pz))) {
                    fixture[i] = true;
                    continue;
                }
                BlockSpec spec = art.get(px, pz);
                if (spec == null) continue;
                target[i] = spec;
                n++;
                item[i] = catalog.placementItem(spec).orElse(null);
                if (item[i] == null) unplaceable.merge(spec.toString(), 1, Integer::sum);
                if (Eyeblossoms.is(spec)) {
                    blossoms.add(i);
                    if (spec.id().equals(Eyeblossoms.OPEN)) open = true;
                    else closed = true;
                }
            }
        }
        total = n;
        blossomCells = blossoms.stream().mapToInt(Integer::intValue).toArray();
        blossomsWantOpen = open;
        blossomsWantClosed = closed;
    }

    /** Eyeblossom pixels of this tile. */
    int blossomCount() {
        return blossomCells.length;
    }

    /**
     * Eyeblossom pixels whose world block is the other eyeblossom state: the world has not flipped them to the state
     * the art wants yet. The wanted state is the target's, whose map colour is the expected one (1.21.4: open 15, at
     * night; closed 49, by day). Missing or wrong blocks are not counted here (VERIFY handles those).
     */
    int blossomsNotFlipped(WorldView w) {
        int n = 0;
        for (int i : blossomCells) {
            BlockSpec b = w.block(cells[i]);
            if (Eyeblossoms.is(b) && !b.id().equals(target[i].id())) n++;
        }
        return n;
    }

    /** Mismatching map pixels ({@link ExpectedMap#mismatches}) on eyeblossom cells. */
    int blossomMismatches(ExpectedMap exp, byte[] actual) {
        int n = 0;
        for (int i : blossomCells) if (i < actual.length && exp.check()[i] && actual[i] != exp.packed()[i]) n++;
        return n;
    }

    Pos pos(int i) {
        return cells[i];
    }

    /**
     * The art's placement at {@code p} (its target and placing item), or null: no target there, a fixture, no placing
     * item, or not on this tile's art layer (VERIFY's break-in places the blocks it broke again from this).
     */
    @org.jspecify.annotations.Nullable Placement placement(Pos p) {
        if (p.y() != geo.artY()) return null;
        int px = p.x() - geo.minX();
        int pz = p.z() - geo.minZ();
        if (px < 0 || pz < 0 || px >= SIZE || pz >= SIZE) return null;
        int i = pz * SIZE + px;
        if (fixture[i] || target[i] == null || item[i] == null) return null;
        return new Placement(cells[i], target[i], item[i]);
    }

    BlockSpec target(int i) {
        return target[i];
    }

    /** This plan wants a block at {@code p} (a cell of its art layer with a target, not a fixture). */
    boolean wants(Pos p) {
        if (p.y() != geo.artY()) return false;
        int px = p.x() - geo.minX();
        int pz = p.z() - geo.minZ();
        if (px < 0 || pz < 0 || px >= SIZE || pz >= SIZE) return false;
        int i = pz * SIZE + px;
        return !fixture[i] && target[i] != null;
    }

    /** {@code p} is a cell of this plan's art layer, with or without a target, and not a fixture. */
    boolean covers(Pos p) {
        if (p.y() != geo.artY()) return false;
        int px = p.x() - geo.minX();
        int pz = p.z() - geo.minZ();
        return px >= 0 && pz >= 0 && px < SIZE && pz < SIZE && !fixture[pz * SIZE + px];
    }

    /** True if the world state {@code actual} satisfies {@code target} (null = must be air); {@link BlockCatalog#matches}. */
    boolean matches(BlockSpec target, BlockSpec actual) {
        return catalog.matches(target, actual);
    }

    static boolean isFluidBlock(BlockSpec b) {
        return FLUIDS.contains(b.id());
    }

    boolean allLoaded(WorldView w) {
        for (int i = 0; i < CELLS; i++) if ((band == null || !fixture[i]) && !w.isLoaded(cells[i])) return false;
        return true;
    }

    /** Placement predicate for {@code BuildPlanner.plan}: already correct in the world (or outside the band). */
    Predicate<Placement> doneIn(WorldView w) {
        return p -> outside(p.pos()) || matches(p.block(), w.block(p.pos()));
    }

    /** A cell of the tile outside this plan's band (never for a whole tile's plan). */
    boolean outside(Pos p) {
        if (band == null) return false;
        return !band.contains(p.x() - geo.minX(), p.z() - geo.minZ());
    }

    /**
     * Placement predicate for the BUILD lanes: correct, or occupied by something else. A wrong block cannot be placed
     * over, so it is left to VERIFY (break, then a repair lane) instead of sending the bot back every restock round.
     */
    Predicate<Placement> notBuildableIn(WorldView w) {
        return p -> {
            if (outside(p.pos())) return true;
            BlockSpec b = w.block(p.pos());
            return !b.isAir() || matches(p.block(), b);
        };
    }

    Survey survey(WorldView w) {
        int nonAir = 0;
        int correct = 0;
        boolean fluid = false;
        List<Pos> wrong = new ArrayList<>();
        List<Pos> missing = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) {
            if (fixture[i]) continue;
            Pos p = cells[i];
            BlockSpec b = w.block(p);
            if (w.hasFluid(p)) fluid = true;
            if (b.isAir()) {
                if (target[i] != null) missing.add(p);
                continue;
            }
            nonAir++;
            if (target[i] != null && matches(target[i], b)) correct++;
            else wrong.add(p);
        }
        return new Survey(nonAir, correct, fluid, wrong, missing, total);
    }

    /** Item id → count for every target pixel not yet correct in the world (wrong or missing). */
    Map<String, Integer> remainingNeeds(WorldView w) {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < CELLS; i++) {
            if (target[i] == null || item[i] == null) continue;
            if (!matches(target[i], w.block(cells[i]))) m.merge(item[i], 1, Integer::sum);
        }
        return m;
    }

    /** Item id → count for every target pixel, placed or not: what the whole tile needs. */
    Map<String, Integer> allNeeds() {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < CELLS; i++) if (target[i] != null && item[i] != null) m.merge(item[i], 1, Integer::sum);
        return m;
    }

    int correctCount(WorldView w) {
        int n = 0;
        for (int i = 0; i < CELLS; i++) if (target[i] != null && matches(target[i], w.block(cells[i]))) n++;
        return n;
    }

    /**
     * The art's cells the NUKE breaks once the tile is built: targets the piston clear cannot remove (no fixture, no
     * fluid). From the plan alone (the world may not be loaded where it is asked: STORE, by the chests).
     */
    int nukeCount(Collection<String> clearable) {
        int n = 0;
        for (int i = 0; i < CELLS; i++) {
            BlockSpec b = target[i];
            if (fixture[i] || b == null || b.isAir() || isFluidBlock(b) || clearable.contains(b.id())) continue;
            n++;
        }
        return n;
    }

    /** Non-air blocks on the art layer the piston clear cannot remove (fluids excluded: they cannot be broken). */
    List<Pos> nukeTargets(WorldView w, Collection<String> clearable) {
        List<Pos> out = new ArrayList<>();
        for (int i = 0; i < CELLS; i++) {
            if (fixture[i]) continue;
            Pos p = cells[i];
            BlockSpec b = w.block(p);
            if (b.isAir() || isFluidBlock(b) || clearable.contains(b.id())) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * True if the finished art itself holds a block the piston clear cannot remove (a target not in {@code clearable},
     * fluids excluded as in {@link #nukeTargets}): the NUKE after this tile breaks something. Needs no loaded chunks.
     */
    boolean artNeedsNuke(Collection<String> clearable) {
        for (int i = 0; i < CELLS; i++) {
            BlockSpec b = target[i];
            if (fixture[i] || b == null || b.isAir() || isFluidBlock(b) || clearable.contains(b.id())) continue;
            return true;
        }
        return false;
    }

    /** Vanilla map shading ids (MapColor.Brightness). */
    static final int LOW = 0, NORMAL = 1, HIGH = 2;

    /**
     * Expected packed map colours; {@code check[i]} false for empty pixels and blocks invisible on maps.
     *
     * <p>Shading follows vanilla: a pixel is compared with the block north of it (for the first row, the noobline
     * row of the world). On a flat build both sit on the art layer, so the pixel is NORMAL. When the art-layer cell
     * to the north shows nothing on a map (an empty pixel showing the floor one block lower, or a missing noobline
     * block) the pixel is one block higher than its neighbour and renders HIGH.
     */
    ExpectedMap expectedMap(BlockCatalog catalog, MapView maps, WorldView w) {
        byte[] packed = new byte[CELLS];
        boolean[] check = new boolean[CELLS];
        for (int i = 0; i < CELLS; i++) {
            if (target[i] == null) continue;
            int c = catalog.mapColorId(target[i]);
            if (c <= 0) continue;
            Pos north = cells[i].offset(0, 0, -1);
            int shade = catalog.mapColorId(w.block(north)) > 0 ? NORMAL : HIGH;
            packed[i] = (byte) ((maps.expectedPacked(c) & 0xFC) | shade);
            check[i] = true;
        }
        return new ExpectedMap(packed, check);
    }

    record ExpectedMap(byte[] packed, boolean[] check) {
        int mismatches(byte[] actual) {
            int n = 0;
            for (int i = 0; i < CELLS && i < actual.length; i++) if (check[i] && actual[i] != packed[i]) n++;
            return n;
        }

        /** Expected colours for {@code Renderer.mapDiff}: unchecked pixels copy the actual value. */
        /** Up to {@code max} mismatching pixels as "x,z exp E got G" (tile-relative; map colour ids). */
        String sample(byte[] actual, int max) {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < CELLS && n < max; i++) {
                if (!check[i] || packed[i] == actual[i]) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(i % 128).append(',').append(i / 128).append(" exp ").append(packed[i] & 0xff).append(" got ")
                    .append(actual[i] & 0xff);
                n++;
            }
            return sb.toString();
        }

        byte[] forDiff(byte[] actual) {
            byte[] out = new byte[CELLS];
            for (int i = 0; i < CELLS; i++) out[i] = check[i] ? packed[i] : (i < actual.length ? actual[i] : 0);
            return out;
        }
    }

    /**
     * @param nonAir  non-air blocks on the art layer
     * @param correct non-air blocks equal to this tile's target
     * @param wrong   non-air blocks that are not the target (including anything on an empty pixel)
     * @param missing air where the tile wants a block
     */
    record Survey(int nonAir, int correct, boolean fluid, List<Pos> wrong, List<Pos> missing, int total) {
        boolean clear() {
            return nonAir == 0 && !fluid;
        }

        double matchRatio() {
            return nonAir == 0 ? 0 : (double) correct / nonAir;
        }

        boolean complete() {
            return wrong.isEmpty() && missing.isEmpty() && !fluid;
        }
    }
}
