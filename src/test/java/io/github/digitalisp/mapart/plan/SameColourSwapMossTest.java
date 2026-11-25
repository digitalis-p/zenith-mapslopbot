package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.testutil.MossStock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same-colour swaps with the real 1.21.4 registry, the default moss clear lists and the 44 materials of the moss
 * sorter (counts as in the local test world's chests). j0005 ("queue verity") needed three materials the base has no
 * chest for: decorated_pot ×235, pointed_dripstone ×1143 and white_candle ×271.
 */
class SameColourSwapMossTest {
    private final ZenithBlockCatalog catalog = new ZenithBlockCatalog();

    static StockIndex mossStock() {
        assertEquals(44, MossStock.items().size());
        return MossStock.index();
    }

    static MapArtConfig.SiteCfg mossSite() {
        return MossStock.site();
    }

    /** j0005's three unstocked materials in their counts, then carpets the moss sorter stocks. */
    static TileArt j0005() {
        TileArt t = new TileArt();
        int i = 0;
        for (var e : List.of(Map.entry("minecraft:decorated_pot", 235), Map.entry("minecraft:pointed_dripstone", 1143),
            Map.entry("minecraft:white_candle", 271))) {
            for (int n = 0; n < e.getValue(); n++, i++) t.set(i % 128, i / 128, BlockSpec.of(e.getKey()));
        }
        for (; i < 128 * 128; i++) t.set(i % 128, i / 128, BlockSpec.of(i % 2 == 0 ? "minecraft:black_carpet" : "minecraft:white_carpet"));
        return t;
    }

    private static ParsedSubmission sub(TileArt... tiles) {
        TreeMap<TileCoord, TileArt> m = new TreeMap<>();
        for (int i = 0; i < tiles.length; i++) m.put(new TileCoord(i, 0), tiles[i]);
        return new ParsedSubmission("t", tiles.length, 1, DefaultSubmissionService.DATA_VERSION_1_21_4, m, List.of());
    }

    @Test
    void mapColoursAre1214() {
        assertEquals(50, catalog.mapColorId(BlockSpec.of("minecraft:decorated_pot")), "TERRACOTTA_RED");
        assertEquals(50, catalog.mapColorId(BlockSpec.of("minecraft:red_terracotta")));
        assertEquals(48, catalog.mapColorId(BlockSpec.of("minecraft:pointed_dripstone")), "TERRACOTTA_BROWN");
        assertEquals(48, catalog.mapColorId(BlockSpec.of("minecraft:brown_terracotta")));
        assertEquals(48, catalog.mapColorId(BlockSpec.of("minecraft:dripstone_block")));
        assertEquals(3, catalog.mapColorId(BlockSpec.of("minecraft:white_candle")), "WOOL");
        assertEquals(3, catalog.mapColorId(BlockSpec.of("minecraft:mushroom_stem")));
        assertEquals(15, catalog.mapColorId(BlockSpec.of("minecraft:open_eyeblossom")), "COLOR_ORANGE");
        assertEquals(15, catalog.mapColorId(BlockSpec.of("minecraft:orange_carpet")));
        assertEquals(49, catalog.mapColorId(BlockSpec.of("minecraft:closed_eyeblossom")), "TERRACOTTA_GREEN, not 6 (1.21.11)");
    }

    @Test
    void j0005MaterialsBecomeStockedBlocksOfTheSameColour() {
        SameColourSwap.Result r = SameColourSwap.apply(sub(j0005()), catalog, mossSite(), mossStock(), List.of());
        List<String> got = new ArrayList<>();
        for (Job.Substitution s : r.substitutions()) got.add(s.from + " ×" + s.count + " → " + s.to + " (" + s.mapColor + ")");
        assertEquals(List.of(
            "minecraft:decorated_pot ×235 → minecraft:red_terracotta (50)",
            // brown_terracotta (3,456) and dripstone_block (3,392) are both nuke blocks: the most stock wins
            "minecraft:pointed_dripstone ×1143 → minecraft:brown_terracotta (48)",
            "minecraft:white_candle ×271 → minecraft:mushroom_stem (3)"), got);
        assertEquals("Swapped for identical colors: decorated_pot ×235 → red_terracotta, pointed_dripstone ×1,143 → "
            + "brown_terracotta, white_candle ×271 → mushroom_stem", SameColourSwap.line(r.substitutions(), 600));

        ValidationResult v = new DefaultSubmissionService().validate(r.submission(), catalog, mossSite());
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(List.of("moss"), v.platformIds());
        assertEquals(235, v.materials().get("minecraft:red_terracotta"));
        assertEquals(1143, v.materials().get("minecraft:brown_terracotta"));
        assertEquals(271, v.materials().get("minecraft:mushroom_stem"));
        assertFalse(v.materials().containsKey("minecraft:decorated_pot"));
        assertTrue(mossStock().unstocked(v.materials().keySet()).isEmpty(), "every material has a chest now");
    }

    @Test
    void pistonClearablesWinAndUnclearableOriginalsAreRescued() {
        // calcite (TERRACOTTA_WHITE, 36) is in no clear list and has no chest: the cherry pressure plate (piston) beats
        // white terracotta (nuke), and the swapped art passes the platform check
        assertEquals(36, catalog.mapColorId(BlockSpec.of("minecraft:calcite")));
        TileArt t = new TileArt();
        for (int x = 0; x < 128; x++) t.set(x, 0, BlockSpec.of("minecraft:calcite"));
        MapArtConfig.SiteCfg site = mossSite();
        assertFalse(new DefaultSubmissionService().validate(sub(t), catalog, site).ok());
        SameColourSwap.Result r = SameColourSwap.apply(sub(t), catalog, site, mossStock(), List.of());
        assertEquals("minecraft:cherry_pressure_plate", r.substitutions().getFirst().to);
        assertTrue(new DefaultSubmissionService().validate(r.submission(), catalog, site).ok());
    }

    @Test
    void eyeblossomsWithTheMossStock() {
        TileArt open = new TileArt();
        TileArt closed = new TileArt();
        for (int x = 0; x < 128; x++) {
            open.set(x, 0, BlockSpec.of("minecraft:open_eyeblossom"));
            closed.set(x, 0, BlockSpec.of("minecraft:closed_eyeblossom"));
        }
        SameColourSwap.Result r = SameColourSwap.apply(sub(open, closed), catalog, mossSite(), mossStock(), List.of());
        assertEquals("Swapped for identical colors: closed_eyeblossom ×128 → (none stocked), open_eyeblossom ×128 → "
            + "orange_carpet", SameColourSwap.line(r.substitutions(), 600));
        assertEquals(BlockSpec.of("minecraft:orange_carpet"), r.submission().tiles().get(new TileCoord(0, 0)).get(3, 0));
        assertEquals(BlockSpec.of("minecraft:closed_eyeblossom"), r.submission().tiles().get(new TileCoord(1, 0)).get(3, 0));
    }
}
