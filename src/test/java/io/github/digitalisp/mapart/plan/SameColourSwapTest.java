package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.Tiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Choice rules of {@link SameColourSwap} with controlled map colours (every stocked block gets an explicit one). */
class SameColourSwapTest {
    private static final String POT = "minecraft:decorated_pot";
    private static final String OPEN = "minecraft:open_eyeblossom";
    private static final String CLOSED = "minecraft:closed_eyeblossom";

    private final FakeCatalog catalog = new FakeCatalog();
    private final MapArtConfig.SiteCfg site = new MapArtConfig.SiteCfg();
    private MapArtConfig.PlatformCfg moss;

    @BeforeEach
    void setUp() {
        moss = platform("moss", "MOSS");
        site.platforms.add(moss);
        // colour 50 (TERRACOTTA_RED in 1.21.4) for the pot and every block that may stand in for it
        for (String id : List.of(POT, "minecraft:red_terracotta", "minecraft:black_terracotta", "minecraft:red_carpet",
            "minecraft:black_carpet", "minecraft:red_candle", "minecraft:crimson_roots", "minecraft:cobblestone",
            "minecraft:obsidian"))
            catalog.colors.put(id, 50);
        catalog.colors.put("minecraft:white_carpet", 8);
        catalog.colors.put("minecraft:orange_carpet", 15);
        catalog.colors.put("minecraft:terracotta", 15);
        catalog.colors.put("minecraft:green_carpet", 27);
    }

    private static MapArtConfig.PlatformCfg platform(String id, String kind) {
        MapArtConfig.PlatformCfg p = new MapArtConfig.PlatformCfg();
        p.id = id;
        p.kind = kind;
        return p;
    }

    private static StockIndex stock(Object... idCount) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < idCount.length; i += 2) items.put((String) idCount[i], (Integer) idCount[i + 1]);
        StockIndex s = new StockIndex();
        s.update(new Pos(0, 64, 0), items, 0, 27, 1);
        return s;
    }

    /** One tile: {@code first} on the first {@code n} pixels, white carpet elsewhere. */
    private static ParsedSubmission art(String first, int n) {
        TileArt t = Tiles.of((x, z) -> z * 128 + x < n ? first : "minecraft:white_carpet");
        return new ParsedSubmission("t", 1, 1, 4189, new TreeMap<>(Map.of(new TileCoord(0, 0), t)), List.of());
    }

    private SameColourSwap.Result swap(ParsedSubmission sub, StockIndex stock, List<String> deny) {
        return SameColourSwap.apply(sub, catalog, site, stock, deny);
    }

    private String potBecomes(StockIndex stock) {
        List<Job.Substitution> l = swap(art(POT, 10), stock, List.of()).substitutions();
        if (l.isEmpty()) return "(no swap)";
        assertEquals(1, l.size(), l.toString());
        return l.getFirst().to;
    }

    @Test
    void carpetsFirstThenOtherPistonClearablesThenNukeBlocksByMostStock() {
        catalog.known.add("minecraft:red_candle");
        String all = potBecomes(stock("minecraft:white_carpet", 64, "minecraft:red_terracotta", 3000,
            "minecraft:red_candle", 10, "minecraft:red_carpet", 5));
        assertEquals("minecraft:red_carpet", all, "a carpet wins although it has the least stock");
        assertEquals("minecraft:red_candle", potBecomes(stock("minecraft:white_carpet", 64,
            "minecraft:red_terracotta", 3000, "minecraft:red_candle", 10)), "piston-clearable before a nuke block");
        assertEquals("minecraft:black_terracotta", potBecomes(stock("minecraft:white_carpet", 64,
            "minecraft:red_terracotta", 3000, "minecraft:black_terracotta", 4000)), "most stock");
        assertEquals("minecraft:black_carpet", potBecomes(stock("minecraft:white_carpet", 64,
            "minecraft:red_carpet", 100, "minecraft:black_carpet", 100)), "same stock: by id");
        assertEquals("minecraft:red_carpet", potBecomes(stock("minecraft:white_carpet", 64,
            "minecraft:red_carpet", 101, "minecraft:black_carpet", 100)));
    }

    @Test
    void anUnstockedBlockIsSwappedAndRecordedWithItsOriginalState() {
        String state = "minecraft:pointed_dripstone[thickness=tip,vertical_direction=up]";
        catalog.colors.put("minecraft:pointed_dripstone", 50);
        TileArt t = Tiles.of((x, z) -> z == 0 ? state : z == 1 ? "minecraft:pointed_dripstone" : z == 2 ? POT : null);
        ParsedSubmission sub = new ParsedSubmission("t", 1, 1, 4189, new TreeMap<>(Map.of(new TileCoord(0, 0), t)), List.of());
        StockIndex st = stock("minecraft:red_terracotta", 3000);
        SameColourSwap.Result r = swap(sub, st, List.of());

        List<String> got = new ArrayList<>();
        for (Job.Substitution s : r.substitutions())
            got.add(s.from + " -> " + s.to + " x" + s.count + " c" + s.mapColor + " " + s.reason);
        assertEquals(List.of(
            "minecraft:decorated_pot -> minecraft:red_terracotta x128 c50 no chest",
            "minecraft:pointed_dripstone -> minecraft:red_terracotta x128 c50 no chest",
            state + " -> minecraft:red_terracotta x128 c50 no chest"), got);
        TileArt out = r.submission().tiles().get(new TileCoord(0, 0));
        for (int x = 0; x < 128; x++)
            for (int z = 0; z < 3; z++) assertEquals(BlockSpec.of("minecraft:red_terracotta"), out.get(x, z));
        assertNull(out.get(0, 3), "empty pixels stay empty");
        assertEquals(BlockSpec.parse(state), t.get(5, 0), "the submitted tile is not changed");
        assertEquals("Swapped for identical colors: decorated_pot ×128 → red_terracotta, pointed_dripstone ×256 → "
            + "red_terracotta", SameColourSwap.line(r.substitutions(), 600), "states of one block are summed");
    }

    @Test
    void stockedBlocksStayAndNothingIsSwappedBeforeTheFirstScan() {
        ParsedSubmission sub = art(POT, 10);
        SameColourSwap.Result stocked = swap(sub, stock(POT, 1, "minecraft:red_carpet", 500), List.of());
        assertTrue(stocked.substitutions().isEmpty());
        assertSame(sub, stocked.submission());

        ParsedSubmission blossoms = art(OPEN, 10);
        SameColourSwap.Result unscanned = swap(blossoms, new StockIndex(), List.of());
        assertTrue(unscanned.substitutions().isEmpty(), "stock unknown: no swaps");
        assertSame(blossoms, unscanned.submission());
    }

    @Test
    void anEmptiedChestStillCountsAsStocked() {
        StockIndex st = stock(POT, 5, "minecraft:red_carpet", 500);
        st.update(new Pos(0, 64, 0), Map.of("minecraft:red_carpet", 500), 0, 27, 2); // the pot chest is empty now
        assertEquals("(no swap)", potBecomes(st), "the base has a chest for pots: a restock fixes it");
    }

    @Test
    void neverSwapsInABlockAnEnabledPlatformCannotClear() {
        catalog.known.add("minecraft:red_candle");
        // cobblestone is in no clear list, obsidian is protected: neither may stand in
        assertEquals("(no swap)", potBecomes(stock("minecraft:white_carpet", 64, "minecraft:cobblestone", 9000)));
        moss.nukeBlocks.add("minecraft:obsidian");
        assertEquals("(no swap)", potBecomes(stock("minecraft:white_carpet", 64, "minecraft:obsidian", 9000)));

        // crimson roots are foliage: piston-clearable on moss only. With a soul sand platform enabled as well, a stand-in
        // must be clearable on both, so the nuke block wins.
        StockIndex st = stock("minecraft:white_carpet", 64, "minecraft:crimson_roots", 50, "minecraft:red_terracotta", 30);
        assertEquals("minecraft:crimson_roots", potBecomes(st));
        MapArtConfig.PlatformCfg soul = platform("soul", "SOUL_SAND");
        soul.clearableBlocks.remove("minecraft:crimson_roots");
        site.platforms.add(soul);
        assertEquals("minecraft:red_terracotta", potBecomes(st));
        soul.enabled = false;
        assertEquals("minecraft:crimson_roots", potBecomes(st), "a disabled platform does not count");
    }

    @Test
    void denyListWorksBothWays() {
        StockIndex st = stock("minecraft:white_carpet", 64, "minecraft:red_carpet", 500, "minecraft:red_terracotta", 900);
        assertTrue(swap(art(POT, 10), st, List.of("decorated_pot")).substitutions().isEmpty(), "never swapped out");
        List<Job.Substitution> l = swap(art(POT, 10), st, List.of("minecraft:red_carpet")).substitutions();
        assertEquals("minecraft:red_terracotta", l.getFirst().to, "never swapped in");
    }

    @Test
    void openEyeblossomsBecomeOrangeCarpetAndClosedOnesAreKeptWhenNoColour49IsStocked() {
        // the moss sorter: open_eyeblossom, orange_carpet (15) and terracotta (15); nothing with colour 49
        StockIndex st = stock(OPEN, 3000, "minecraft:orange_carpet", 2700, "minecraft:terracotta", 3456,
            "minecraft:white_carpet", 64);
        TileArt t = Tiles.of((x, z) -> z == 0 ? OPEN : z == 1 ? CLOSED : "minecraft:white_carpet");
        ParsedSubmission sub = new ParsedSubmission("t", 1, 1, 4189, new TreeMap<>(Map.of(new TileCoord(0, 0), t)), List.of());
        SameColourSwap.Result r = swap(sub, st, List.of());

        assertEquals(2, r.substitutions().size(), r.substitutions().toString());
        Job.Substitution closed = r.substitutions().get(0);
        assertEquals(CLOSED, closed.from);
        assertNull(closed.to, "no stocked block shows color 49");
        assertEquals(49, closed.mapColor);
        assertEquals(SameColourSwap.TIME_OF_DAY, closed.reason);
        Job.Substitution open = r.substitutions().get(1);
        assertEquals(OPEN, open.from);
        assertEquals("minecraft:orange_carpet", open.to, "a carpet before terracotta; eyeblossoms never stand in");
        assertEquals(15, open.mapColor);
        assertEquals(SameColourSwap.TIME_OF_DAY, open.reason);

        TileArt out = r.submission().tiles().get(new TileCoord(0, 0));
        assertEquals(BlockSpec.of("minecraft:orange_carpet"), out.get(7, 0));
        assertEquals(BlockSpec.of(CLOSED), out.get(7, 1), "closed eyeblossoms stay: the engine fills the map by day");
        assertEquals("Swapped for identical colors: closed_eyeblossom ×128 → (none stocked), open_eyeblossom ×128 → "
            + "orange_carpet", SameColourSwap.line(r.substitutions(), 600));

        // closed eyeblossoms do swap when a colour-49 block is stocked
        catalog.colors.put("minecraft:green_terracotta", 49);
        StockIndex green = stock(OPEN, 3000, "minecraft:green_terracotta", 64, "minecraft:white_carpet", 64);
        Job.Substitution c = swap(sub, green, List.of()).substitutions().getFirst();
        assertEquals("minecraft:green_terracotta", c.to);
    }

    @Test
    void deterministic() {
        StockIndex st = stock("minecraft:white_carpet", 64, "minecraft:red_carpet", 100, "minecraft:black_carpet", 100,
            OPEN, 5, "minecraft:orange_carpet", 7);
        TileArt t = Tiles.of((x, z) -> z == 0 ? OPEN : z == 1 ? CLOSED : z == 2 ? POT : "minecraft:white_carpet");
        ParsedSubmission sub = new ParsedSubmission("t", 1, 1, 4189, new TreeMap<>(Map.of(new TileCoord(0, 0), t)), List.of());
        String first = SameColourSwap.line(swap(sub, st, List.of()).substitutions(), 600);
        for (int i = 0; i < 5; i++) assertEquals(first, SameColourSwap.line(swap(sub, st, List.of()).substitutions(), 600));
    }

    private static final String DRIP = "minecraft:pointed_dripstone";
    private static final SameColourSwap.Options STOCKED = new SameColourSwap.Options(true, c -> true);

    /** One tile: row z holds {@code ids[z]}, white carpet elsewhere. */
    private static ParsedSubmission rows(String... ids) {
        TileArt t = Tiles.of((x, z) -> z < ids.length ? ids[z] : "minecraft:white_carpet");
        return new ParsedSubmission("t", 1, 1, 4189, new TreeMap<>(Map.of(new TileCoord(0, 0), t)), List.of());
    }

    private static String[] repeat(String id, int n, String... then) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(id);
        l.addAll(List.of(then));
        return l.toArray(String[]::new);
    }

    private List<String> swaps(ParsedSubmission sub, StockIndex st, SameColourSwap.Options o) {
        List<String> got = new ArrayList<>();
        for (Job.Substitution s : SameColourSwap.apply(sub, catalog, site, st, List.of(), o).substitutions())
            got.add(s.from + " -> " + s.to + " x" + s.count + " c" + s.mapColor + " " + s.reason);
        return got;
    }

    @Test
    void withStockedOnARareBlockJoinsTheTilesCommonBlockOfTheSameColour() {
        catalog.known.add("minecraft:red_candle");
        StockIndex st = stock("minecraft:white_carpet", 9000, "minecraft:red_carpet", 9000, "minecraft:red_candle", 500);
        ParsedSubmission sub = rows(repeat("minecraft:red_carpet", 10, "minecraft:red_candle"));
        assertTrue(swaps(sub, st, SameColourSwap.Options.UPLOAD).isEmpty(), "off: stocked blocks stay");
        assertEquals(List.of("minecraft:red_candle -> minecraft:red_carpet x128 c50 fewer items"), swaps(sub, st, STOCKED));
        TileArt out = SameColourSwap.apply(sub, catalog, site, st, List.of(), STOCKED).submission().tiles()
            .get(new TileCoord(0, 0));
        assertEquals(2, out.blockCounts().size(), "one item fewer: " + out.blockCounts());
        // the common block never goes to the rare one
        ParsedSubmission flipped = rows(repeat("minecraft:red_candle", 10, "minecraft:red_carpet"));
        assertEquals(List.of("minecraft:red_carpet -> minecraft:red_candle x128 c50 fewer items"), swaps(flipped, st, STOCKED));
    }

    @Test
    void aSwapNeverMakesTheTileHarderToBuild() {
        catalog.colors.put(DRIP, 50);
        StockIndex st = stock("minecraft:white_carpet", 9000, "minecraft:red_carpet", 9000, DRIP, 9000);
        // the carpet row is rarer, but the dripstone needs a LOOK: the carpets never become dripstone, the dripstone
        // becomes carpet (easier, and one item fewer)
        assertEquals(List.of(DRIP + " -> minecraft:red_carpet x1280 c50 fewer items"),
            swaps(rows(repeat(DRIP, 10, "minecraft:red_carpet")), st, STOCKED));
        assertEquals(List.of(DRIP + " -> minecraft:red_carpet x128 c50 fewer items"),
            swaps(rows(repeat("minecraft:red_carpet", 10, DRIP)), st, STOCKED));
        // a second run finds nothing more to do
        ParsedSubmission once = SameColourSwap.apply(rows(repeat(DRIP, 10, "minecraft:red_carpet")), catalog, site, st,
            List.of(), STOCKED).submission();
        assertTrue(swaps(once, st, STOCKED).isEmpty());
    }

    @Test
    void anEasierBlockReplacesALookBlockOnlyWhereTheNukeRunsAnyway() {
        catalog.colors.put(DRIP, 50);
        StockIndex st = stock("minecraft:white_carpet", 9000, DRIP, 9000, "minecraft:red_terracotta", 9000,
            "minecraft:terracotta", 9000);
        // a piston-only tile: red terracotta would bring a NUKE; a LOOK against a NUKE is no clear win, so it stays
        assertTrue(swaps(rows(DRIP, DRIP), st, STOCKED).isEmpty());
        // terracotta (a nuke block) in the tile: the full block replaces the LOOK block
        assertEquals(List.of(DRIP + " -> minecraft:red_terracotta x256 c50 easier to build"),
            swaps(rows(DRIP, DRIP, "minecraft:terracotta"), st, STOCKED));
        assertTrue(swaps(rows(DRIP, DRIP, "minecraft:terracotta"), st, SameColourSwap.Options.UPLOAD).isEmpty(), "off");
    }

    @Test
    void aPistonBlockReplacesTheTilesOnlyNukeBlock() {
        StockIndex st = stock("minecraft:white_carpet", 9000, "minecraft:red_terracotta", 9000, "minecraft:red_carpet", 9000,
            "minecraft:terracotta", 9000);
        assertEquals(List.of("minecraft:red_terracotta -> minecraft:red_carpet x128 c50 easier to build"),
            swaps(rows("minecraft:red_terracotta"), st, STOCKED), "the tile needs no NUKE any more");
        assertTrue(swaps(rows("minecraft:red_terracotta", "minecraft:terracotta"), st, STOCKED).isEmpty(),
            "another nuke block keeps the NUKE: nothing to gain");
    }

    @Test
    void anUnstockedBlockJoinsTheSameColourBlockTheTileAlreadyUses() {
        StockIndex st = stock("minecraft:white_carpet", 9000, "minecraft:red_carpet", 9000, "minecraft:red_terracotta", 9000);
        assertEquals(List.of(POT + " -> minecraft:red_terracotta x128 c50 no chest"),
            swaps(rows(POT, "minecraft:red_terracotta", "minecraft:red_terracotta"), st, SameColourSwap.Options.UPLOAD),
            "no new item for the tile's loads, although a carpet ranks first otherwise");
        assertEquals(List.of(POT + " -> minecraft:red_carpet x128 c50 no chest"),
            swaps(rows(POT), st, SameColourSwap.Options.UPLOAD));
    }

    @Test
    void onlyTheGivenTilesChange() {
        StockIndex st = stock("minecraft:white_carpet", 9000, "minecraft:red_carpet", 9000);
        TileArt a = Tiles.of((x, z) -> z == 0 ? POT : "minecraft:white_carpet");
        TileArt b = Tiles.of((x, z) -> z == 0 ? POT : "minecraft:white_carpet");
        ParsedSubmission sub = new ParsedSubmission("t", 2, 1, 4189,
            new TreeMap<>(Map.of(new TileCoord(0, 0), a, new TileCoord(1, 0), b)), List.of());
        SameColourSwap.Result r = SameColourSwap.apply(sub, catalog, site, st, List.of(),
            new SameColourSwap.Options(false, c -> c.x() == 1));
        assertSame(a, r.submission().tiles().get(new TileCoord(0, 0)), "tile 0,0 is not touched");
        assertEquals(java.util.Set.of(new TileCoord(1, 0)), r.changedTiles(sub));
        assertEquals(128, r.substitutions().getFirst().count, "only the chosen tile's pixels");
        assertEquals(BlockSpec.of(POT), r.submission().tiles().get(new TileCoord(0, 0)).get(0, 0));
        assertEquals(BlockSpec.of("minecraft:red_carpet"), r.submission().tiles().get(new TileCoord(1, 0)).get(0, 0));
    }

    @Test
    void lineIsCappedAndEmptyListsGiveNull() {
        assertNull(SameColourSwap.line(List.of(), 100));
        assertNull(SameColourSwap.line(null, 100));
        List<Job.Substitution> many = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            many.add(new Job.Substitution("minecraft:block_" + (char) ('a' + i % 26) + i, "minecraft:red_carpet", 1000 + i,
                28, SameColourSwap.NO_CHEST));
        String l = SameColourSwap.line(many, 200);
        assertTrue(l.length() <= 200, l);
        assertTrue(l.startsWith("Swapped for identical colors: block_a0 ×1,000 → red_carpet, "), l);
        assertTrue(l.matches(".* and \\d+ more"), l);
    }
}
