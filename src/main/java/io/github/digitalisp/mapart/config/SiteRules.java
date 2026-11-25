package io.github.digitalisp.mapart.config;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.SiteCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the site config forbids, in one place for the bot, the engine and the site check: block ids never broken
 * ({@code site.protectedBlocks}, {@link Defaults#MACHINERY_BLOCKS} and every platform floor), positions never broken,
 * pressed, opened or placed into ({@code site.protectedRegions}), and cells never entered ({@code site.noGoZones}).
 * Pure; boxes without {@code min} or {@code max} count as absent (the site check reports them).
 */
public final class SiteRules {
    private SiteRules() { }

    /** Namespaced ids never broken: site.protectedBlocks, the machinery defaults, every platform floor block. */
    public static Set<String> protectedIds(SiteCfg site) {
        Set<String> ids = new LinkedHashSet<>();
        if (site.protectedBlocks != null) for (String s : site.protectedBlocks) addId(ids, s);
        for (String s : Defaults.MACHINERY_BLOCKS) addId(ids, s);
        if (site.platforms != null)
            for (MapArtConfig.PlatformCfg p : site.platforms)
                if (p != null) addId(ids, p.floorBlock);
        return ids;
    }

    /** The protected region holding {@code p}, or null; null inside the run-time {@code site.openings} too. */
    public static @Nullable RegionCfg protectedRegionAt(SiteCfg site, Pos p) {
        if (regionAt(site.openings, p.x(), p.y(), p.z()) != null) return null;
        return regionAt(site.protectedRegions, p.x(), p.y(), p.z());
    }

    /** The no-go zone holding cell {@code p}, or null; null inside the run-time {@code site.openings} too. */
    public static @Nullable RegionCfg noGoZoneAt(SiteCfg site, Pos p) {
        RegionCfg h = regionAt(site.hazards, p.x(), p.y(), p.z());
        if (h != null) return h;
        if (regionAt(site.openings, p.x(), p.y(), p.z()) != null) return null;
        return regionAt(site.noGoZones, p.x(), p.y(), p.z());
    }

    /**
     * A block of type {@code id} at {@code p} may be broken although its type is protected: {@code p} lies in the
     * run-time {@code site.repairable} art cells of the colour-suppression job being built and {@code id} is one of
     * the platform sorter's blocks ({@link Defaults#CS_BLOCKS}: moss_block). Glass, machinery and everything else stay
     * protected.
     */
    public static boolean repairable(SiteCfg site, Pos p, String id) {
        if (regionAt(site.repairable, p.x(), p.y(), p.z()) == null) return false;
        return Defaults.CS_BLOCKS.contains(normalizeId(id));
    }

    /** {@code id} is protected at {@code p}: in {@code ids} ({@link #protectedIds}) and not {@link #repairable}. */
    public static boolean protectedAt(SiteCfg site, Set<String> ids, Pos p, String id) {
        return ids.contains(id) && !repairable(site, p, id);
    }

    /** The art cells a colour-suppression job may repair ({@code site.repairable}): glass + 1 and + 2 of each floor. */
    public static List<RegionCfg> suppressionArtCells(MapArtConfig.@Nullable PlatformCfg cs) {
        List<RegionCfg> out = new java.util.ArrayList<>();
        if (cs == null || !cs.isColorSuppression() || cs.glassY == null) return out;
        for (Integer g : cs.glassY) {
            if (g == null) continue;
            RegionCfg r = new RegionCfg();
            r.min = new Pos(cs.minX, g + 1, cs.minZ - 1);
            r.max = new Pos(cs.minX + 127, g + 2, cs.minZ + 127);
            r.name = cs.id + " art cells";
            out.add(r);
        }
        return out;
    }

    /**
     * The boxes a colour-suppression job opens (docs/COLOR_SUPPRESSION.md): over each glass floor of {@code cs}, the
     * art volume from the row north of the noobline (where the bot stands to place the noobline) to the last map row,
     * glass + 1 to glass + 5 (the cells built, and the feet and head of a bot standing on them), plus the platform's
     * {@code access} boxes. Empty for any other platform.
     */
    public static List<RegionCfg> suppressionOpenings(MapArtConfig.@Nullable PlatformCfg cs) {
        List<RegionCfg> out = new java.util.ArrayList<>();
        if (cs == null || !cs.isColorSuppression()) return out;
        if (cs.glassY != null) {
            int layer = 0;
            for (Integer g : cs.glassY) {
                if (g == null) continue;
                RegionCfg r = new RegionCfg();
                r.min = new Pos(cs.minX, g + 1, cs.minZ - 2);
                r.max = new Pos(cs.minX + 127, g + 5, cs.minZ + 127);
                r.name = cs.id + " art volume " + (layer++ == 0 ? "bottom" : "top");
                out.add(r);
            }
        }
        if (cs.access != null) for (RegionCfg r : cs.access) if (r != null && r.min != null && r.max != null) out.add(r);
        // the platform's own leftovers chests sit in the machinery's protected region (live 2026-11-13: "refusing to
        // open ... inside protected region #2"): each chest's own cell opens, nothing around it
        if (cs.returnChests != null) {
            for (Pos c : cs.returnChests) {
                if (c == null) continue;
                RegionCfg r = new RegionCfg();
                r.min = c;
                r.max = c;
                r.name = cs.id + " return chest";
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Box {@code a} (inclusive, {x0, y0, z0, x1, y1, z1}, lower corner first) without the cells of {@code holes}: at
     * most six boxes per hole, disjoint, covering exactly the rest.
     */
    public static List<int[]> subtract(int[] a, List<int[]> holes) {
        List<int[]> parts = new java.util.ArrayList<>();
        parts.add(a);
        for (int[] h : holes) {
            List<int[]> next = new java.util.ArrayList<>();
            for (int[] p : parts) {
                if (h[0] > p[3] || h[3] < p[0] || h[1] > p[4] || h[4] < p[1] || h[2] > p[5] || h[5] < p[2]) {
                    next.add(p);
                    continue;
                }
                int x0 = p[0], x1 = p[3], y0 = p[1], y1 = p[4], z0 = p[2], z1 = p[5];
                if (h[0] > x0) next.add(new int[]{x0, y0, z0, h[0] - 1, y1, z1});
                if (h[3] < x1) next.add(new int[]{h[3] + 1, y0, z0, x1, y1, z1});
                int ix0 = Math.max(x0, h[0]), ix1 = Math.min(x1, h[3]);
                if (h[1] > y0) next.add(new int[]{ix0, y0, z0, ix1, h[1] - 1, z1});
                if (h[4] < y1) next.add(new int[]{ix0, h[4] + 1, z0, ix1, y1, z1});
                int iy0 = Math.max(y0, h[1]), iy1 = Math.min(y1, h[4]);
                if (h[2] > z0) next.add(new int[]{ix0, iy0, z0, ix1, iy1, h[2] - 1});
                if (h[5] < z1) next.add(new int[]{ix0, iy0, h[5] + 1, ix1, iy1, z1});
            }
            parts = next;
        }
        return parts;
    }

    /** {x0, y0, z0, x1, y1, z1} of a complete box, lower corner first; null when {@code min} or {@code max} is missing. */
    public static int @Nullable [] corners(RegionCfg r) {
        if (r == null || r.min == null || r.max == null) return null;
        return new int[]{Math.min(r.min.x(), r.max.x()), Math.min(r.min.y(), r.max.y()), Math.min(r.min.z(), r.max.z()),
            Math.max(r.min.x(), r.max.x()), Math.max(r.min.y(), r.max.y()), Math.max(r.min.z(), r.max.z())};
    }

    /** The first box of {@code regions} holding (x, y, z), or null. */
    public static @Nullable RegionCfg regionAt(@Nullable List<RegionCfg> regions, int x, int y, int z) {
        if (regions == null) return null;
        for (RegionCfg r : regions) if (r != null && r.contains(x, y, z)) return r;
        return null;
    }

    /** The first box of {@code regions} that shares a cell with box (a, b), or null. */
    public static @Nullable RegionCfg regionOverlapping(@Nullable List<RegionCfg> regions, Pos a, Pos b) {
        if (regions == null) return null;
        for (RegionCfg r : regions) {
            if (r == null || r.min == null || r.max == null) continue;
            if (overlap(r.min.x(), r.max.x(), a.x(), b.x()) && overlap(r.min.y(), r.max.y(), a.y(), b.y())
                && overlap(r.min.z(), r.max.z(), a.z(), b.z())) return r;
        }
        return null;
    }

    /** {@code #2 "muncher"} (1-based position in its list), or {@code #2} without a name. */
    public static String label(@Nullable List<RegionCfg> regions, RegionCfg r) {
        int i = regions == null ? -1 : regions.indexOf(r);
        String n = r.name == null || r.name.isBlank() ? "" : " \"" + r.name.trim() + "\"";
        return "#" + (i + 1) + n;
    }

    /** {@code #2 "muncher" (-10,60,5 to 20,70,9)}: label and the normalised corners. */
    public static String describe(@Nullable List<RegionCfg> regions, RegionCfg r) {
        if (r.min == null || r.max == null) return label(regions, r) + " (incomplete)";
        Pos lo = new Pos(Math.min(r.min.x(), r.max.x()), Math.min(r.min.y(), r.max.y()), Math.min(r.min.z(), r.max.z()));
        Pos hi = new Pos(Math.max(r.min.x(), r.max.x()), Math.max(r.min.y(), r.max.y()), Math.max(r.min.z(), r.max.z()));
        return label(regions, r) + " (" + lo.key() + " to " + hi.key() + ")";
    }

    /** "minecraft:x" for "x", "X", " minecraft:x[a=b] ". */
    public static String normalizeId(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        int b = s.indexOf('[');
        if (b >= 0) s = s.substring(0, b);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static void addId(Set<String> ids, @Nullable String id) {
        if (id != null && !id.isBlank()) ids.add(normalizeId(id));
    }

    private static boolean overlap(int a0, int a1, int b0, int b1) {
        return Math.max(Math.min(a0, a1), Math.min(b0, b1)) <= Math.min(Math.max(a0, a1), Math.max(b0, b1));
    }
}
