package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The moss sorter's 44 materials with the counts the local test world's chests held (2026-10-11), and a site with
 * one enabled moss platform using the default clear lists.
 */
public final class MossStock {
    private MossStock() { }

    public static Map<String, Integer> items() {
        Object[] kv = {
            "birch_leaves", 3392, "birch_pressure_plate", 3456, "black_carpet", 15675, "black_terracotta", 3456,
            "blue_carpet", 3447, "brown_carpet", 2820, "brown_terracotta", 3456, "cherry_pressure_plate", 3456,
            "crimson_pressure_plate", 3456, "crimson_roots", 3392, "cyan_carpet", 3349, "dripstone_block", 3392,
            "glow_lichen", 3392, "gray_carpet", 16545, "gray_terracotta", 3456, "green_carpet", 3264,
            "heavy_weighted_pressure_plate", 3392, "jungle_pressure_plate", 3456, "light_blue_carpet", 17284,
            "light_gray_carpet", 16708, "light_gray_terracotta", 3456, "light_weighted_pressure_plate", 3456,
            "lime_carpet", 17332, "magenta_carpet", 3329, "mushroom_stem", 3392, "oak_pressure_plate", 3392,
            "open_eyeblossom", 3264, "orange_carpet", 2702, "orange_terracotta", 3388, "pale_oak_pressure_plate", 3456,
            "pink_carpet", 1958, "pink_petals", 3392, "purple_carpet", 3264, "red_carpet", 964, "red_terracotta", 3392,
            "resin_clump", 3392, "spruce_pressure_plate", 3456, "stone_pressure_plate", 3392, "terracotta", 3456,
            "warped_pressure_plate", 3456, "white_carpet", 269, "white_terracotta", 3386, "yellow_carpet", 362,
            "yellow_terracotta", 3392};
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put("minecraft:" + kv[i], (Integer) kv[i + 1]);
        return m;
    }

    /** A stock index with every material in one scanned chest. */
    public static StockIndex index() {
        StockIndex s = new StockIndex();
        s.update(new Pos(0, 64, 0), items(), 0, 27, 1);
        return s;
    }

    public static MapArtConfig.SiteCfg site() {
        MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        moss.kind = "MOSS";
        site.platforms.add(moss);
        return site;
    }
}
