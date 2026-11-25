package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What hides a tile's art on its map from above. A vanilla map pixel shows the topmost block of its column that has a
 * map colour, so a coloured block anywhere above the art layer replaces the art pixel. On 2b2t the colour-suppression
 * platform shares the moss tile: its plain glass layers (27 and 41 blocks above the art) are invisible on maps, but
 * whatever someone builds on them is not. No repair on the art layer can fix that.
 *
 * <p>Pure. Surveys every column of the map and of the row just north of it (vanilla shades the map's first row by
 * that row's height). Per column it finds the highest block above the art layer whose
 * {@link BlockCatalog#mapColorId} is not 0, scanning down from the top of the loaded column ({@link WorldView#topY});
 * air, plain glass and every other block without a map colour are transparent. Only loaded chunk data is read: a
 * column whose chunk is not loaded is counted in {@link #unloaded}, never guessed.
 *
 * @param covered      map pixels whose column holds a coloured block above the art layer
 * @param northCovered columns of the row north of the map covered the same way (they shade the map's first row)
 * @param unloaded     columns (map and north row) whose chunk is not loaded: unknown
 * @param levels       y → columns whose topmost coloured block is at that height, ascending
 * @param blocks       block id → columns whose topmost coloured block is that block, most first
 * @param strays       map pixels (not the north row) whose topmost coloured block sits right on the art layer
 *                     (art y + 1): a lost click's carpet on a full block, never the colour-suppression platform
 *                     (its glass is 27 and 41 above); the first {@link #STRAYS_KEPT} of them
 */
record Occlusion(int covered, int northCovered, int unloaded, SortedMap<Integer, Integer> levels,
                 Map<String, Integer> blocks, List<Pos> strays) {
    static final int SIZE = TileArt.SIZE;
    /** Levels and block ids named in {@link #describe()}. */
    static final int NAMED = 3;
    /** Stray positions kept ({@link #strays}); more strays than this are never {@link #onlyStrays()}. */
    static final int STRAYS_KEPT = 64;

    Occlusion {
        levels = Collections.unmodifiableSortedMap(new TreeMap<>(levels));
        blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
        strays = List.copyOf(strays);
    }

    static Occlusion survey(PlatformGeometry geo, BlockCatalog catalog, WorldView w) {
        int covered = 0;
        int north = 0;
        int unloaded = 0;
        TreeMap<Integer, Integer> levels = new TreeMap<>();
        Map<String, Integer> ids = new HashMap<>();
        Map<BlockSpec, Integer> colours = new HashMap<>();
        int artY = geo.artY();
        List<Pos> strays = new ArrayList<>();
        int strayCount = 0;
        for (int pz = -1; pz < SIZE; pz++) {
            int z = geo.minZ() + pz;
            for (int px = 0; px < SIZE; px++) {
                int x = geo.minX() + px;
                Pos column = new Pos(x, artY, z);
                if (!w.isLoaded(column)) {
                    unloaded++;
                    continue;
                }
                for (int y = w.topY(column); y > artY; y--) {
                    BlockSpec b = w.block(new Pos(x, y, z));
                    if (b.isAir() || colours.computeIfAbsent(b, catalog::mapColorId) <= 0) continue;
                    if (pz < 0) north++;
                    else covered++;
                    if (pz >= 0 && y == artY + 1 && strayCount++ < STRAYS_KEPT) strays.add(new Pos(x, y, z));
                    levels.merge(y, 1, Integer::sum);
                    ids.merge(b.id(), 1, Integer::sum);
                    break;
                }
            }
        }
        List<Map.Entry<String, Integer>> byCount = new ArrayList<>(ids.entrySet());
        byCount.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> blocks = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : byCount) blocks.put(e.getKey(), e.getValue());
        return new Occlusion(covered, north, unloaded, levels, blocks, strayCount > STRAYS_KEPT ? List.of() : strays);
    }

    /**
     * Everything that covers the map is a stray right on the art layer ({@link #strays}), at most
     * {@link #STRAYS_KEPT} of them, and nothing covers the row north of it: blocks to break, not a platform in use.
     */
    boolean onlyStrays() {
        return covered > 0 && northCovered == 0 && strays.size() == covered;
    }

    /** "pixel 124,21", "pixels 124,21, 3,7 and 2 more": map pixels (x east, z south of the north-west corner). */
    String strayPixels(PlatformGeometry geo) {
        List<String> out = new ArrayList<>();
        for (Pos p : strays) {
            if (out.size() == NAMED) break;
            out.add((p.x() - geo.minX()) + "," + (p.z() - geo.minZ()));
        }
        int more = strays.size() - out.size();
        String list = more > 0 ? String.join(", ", out) + " and " + more + " more"
            : out.size() == 1 ? out.getFirst() : String.join(", ", out.subList(0, out.size() - 1)) + " and " + out.getLast();
        return (strays.size() == 1 ? "pixel " : "pixels ") + list;
    }

    /** Something with a map colour sits above the art layer (over the map or the row north of it). */
    boolean any() {
        return covered + northCovered > 0;
    }

    /**
     * One line for Discord and the log, e.g. "3,200 pixels covered by blocks at y201 (e.g. white_concrete)", plus the
     * row north of the map and unloaded columns when there are any.
     */
    String describe() {
        StringBuilder sb = new StringBuilder();
        String at = levels.isEmpty() ? "" : " at " + levelsText() + " (e.g. " + blocksText() + ")";
        if (covered > 0 || northCovered == 0) {
            sb.append(count(covered, "pixel")).append(" covered");
            if (!at.isEmpty()) sb.append(" by blocks").append(at);
            if (northCovered > 0) sb.append("; ").append(count(northCovered, "more block"))
                .append(" above the row north of the map (it shades the first row)");
        } else {
            sb.append(count(northCovered, "block")).append(" above the row north of the map").append(at)
                .append(" (it shades the map's first row)");
        }
        if (unloaded > 0) sb.append("; ").append(count(unloaded, "column")).append(" not loaded, not checked");
        return sb.toString();
    }

    /** "y201", "y201 and y215", "y201, y215 and y230", "y201, y215, y230 and 2 more levels". */
    String levelsText() {
        List<String> ys = new ArrayList<>();
        for (int y : levels.keySet()) ys.add("y" + y);
        if (ys.size() > NAMED) {
            int more = ys.size() - NAMED;
            List<String> shown = new ArrayList<>(ys.subList(0, NAMED));
            shown.add(more + (more == 1 ? " more level" : " more levels"));
            ys = shown;
        }
        if (ys.size() == 1) return ys.getFirst();
        return String.join(", ", ys.subList(0, ys.size() - 1)) + " and " + ys.getLast();
    }

    /** The most common covering block ids, without the namespace: "white_concrete, red_wool". */
    String blocksText() {
        List<String> out = new ArrayList<>();
        for (String id : blocks.keySet()) {
            if (out.size() == NAMED) break;
            out.add(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
        }
        return String.join(", ", out);
    }

    /** "3,200 pixels", "1 pixel". */
    static String count(int n, String noun) {
        return String.format(Locale.ROOT, "%,d", n) + " " + noun + (n == 1 ? "" : "s");
    }
}
