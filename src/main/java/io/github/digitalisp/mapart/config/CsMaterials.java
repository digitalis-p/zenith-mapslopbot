package io.github.digitalisp.mapart.config;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The colour-suppression platform's own materials (docs/COLOR_SUPPRESSION.md): its chest columns
 * ({@code platforms[cs].materialRegions}), the index row in front of them, whose floor block at each column names the
 * material the column holds ({@code materialRow}), and the palette that follows from it. Pure.
 */
public final class CsMaterials {
    private CsMaterials() { }

    /** The chest regions of every enabled colour-suppression platform of {@code site}. */
    public static List<RegionCfg> csRegions(MapArtConfig.@Nullable SiteCfg site) {
        List<RegionCfg> out = new ArrayList<>();
        if (site == null || site.platforms == null) return out;
        for (PlatformCfg p : site.platforms)
            if (p != null && p.enabled && p.isColorSuppression() && p.materialRegions != null)
                for (RegionCfg r : p.materialRegions) if (r != null && r.min != null && r.max != null) out.add(r);
        return out;
    }

    /** The site's own material regions, then the colour-suppression ones (discovery and scans see both). */
    public static List<RegionCfg> allRegions(MapArtConfig.@Nullable SiteCfg site) {
        List<RegionCfg> out = new ArrayList<>();
        if (site != null && site.storage != null && site.storage.materialRegions != null) out.addAll(site.storage.materialRegions);
        out.addAll(csRegions(site));
        return out;
    }

    /** True when {@code p} lies in one of {@code regions}. */
    public static boolean inAny(@Nullable List<RegionCfg> regions, Pos p) {
        if (regions == null) return false;
        for (RegionCfg r : regions) if (r != null && r.min != null && r.max != null && r.contains(p)) return true;
        return false;
    }

    /**
     * Whether a container at {@code p} is in the storage of a job: a colour-suppression job ({@code cs} with chest
     * regions) uses only those; any other job everything but them.
     */
    public static boolean inScope(MapArtConfig.@Nullable SiteCfg site, @Nullable PlatformCfg cs, Pos p) {
        if (cs != null && cs.materialRegions != null && !cs.materialRegions.isEmpty()) return inAny(cs.materialRegions, p);
        List<RegionCfg> csOnly = csRegions(site);
        if (csOnly.isEmpty() || !inAny(csOnly, p)) return true;
        return site != null && site.storage != null && inAny(site.storage.materialRegions, p);
    }

    /** {@code minecraft:name[state]} → {@code minecraft:name}. */
    public static String item(String block) {
        int i = block.indexOf('[');
        return i < 0 ? block : block.substring(0, i);
    }

    /**
     * The row's materials: item id → the x of its column (the first cell holding it), read with {@code blockAt}
     * (a block id, or null where the world is not loaded). Null when a cell of the row is not loaded, or there is no row.
     * Air and the cells outside the row's own floor level are not materials.
     */
    public static @Nullable Map<String, Integer> row(@Nullable RegionCfg row, Function<Pos, String> blockAt) {
        if (row == null || row.min == null || row.max == null) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        int y = Math.min(row.min.y(), row.max.y());
        for (int z = Math.min(row.min.z(), row.max.z()); z <= Math.max(row.min.z(), row.max.z()); z++)
            for (int x = Math.min(row.min.x(), row.max.x()); x <= Math.max(row.min.x(), row.max.x()); x++) {
                String b = blockAt.apply(new Pos(x, y, z));
                if (b == null) return null;
                String id = item(b);
                if (id.endsWith(":air") || id.endsWith(":cave_air") || id.endsWith(":void_air")) continue;
                out.putIfAbsent(id, x);
            }
        return out;
    }

    /**
     * The blocks a colour-suppression export may use: the row's materials when known, else the configured
     * {@code allowedBlocks}; null when neither is known (no restriction).
     */
    public static @Nullable Set<String> allowed(@Nullable PlatformCfg cs, @Nullable Collection<String> fromRow) {
        if (fromRow != null && !fromRow.isEmpty()) return new TreeSet<>(fromRow);
        if (cs != null && cs.allowedBlocks != null && !cs.allowedBlocks.isEmpty()) {
            Set<String> out = new TreeSet<>();
            for (String b : cs.allowedBlocks) if (b != null) out.add(item(b.strip()));
            return out;
        }
        return null;
    }

    /** The blocks of {@code used} that are not in {@code allowed} (items, states stripped), sorted; empty when none. */
    public static Set<String> refused(Collection<String> used, @Nullable Set<String> allowed) {
        Set<String> out = new TreeSet<>();
        if (allowed == null) return out;
        for (String b : used) {
            String id = item(b);
            if (id.endsWith(":glass") || id.endsWith(":air")) continue; // the floor and nothing: not materials
            if (!allowed.contains(id)) out.add(id);
        }
        return out;
    }

    /** The last palette read from the world (live), for the upload check; null before the first read. */
    private static volatile @Nullable Set<String> live;

    public static void setLive(@Nullable Set<String> palette) {
        live = palette == null ? null : Set.copyOf(palette);
    }

    public static @Nullable Set<String> live() {
        return live;
    }
}
