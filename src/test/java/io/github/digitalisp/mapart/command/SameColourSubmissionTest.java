package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.discord.Card;
import io.github.digitalisp.mapart.discord.SubmissionCards;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.plan.DefaultSubmissionService;
import io.github.digitalisp.mapart.plan.SubmissionService;
import io.github.digitalisp.mapart.render.ColorResolver;
import io.github.digitalisp.mapart.render.Renderer;
import io.github.digitalisp.mapart.store.FileJobStore;
import io.github.digitalisp.mapart.testutil.MossStock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upload with same-colour substitution end to end: the real 1.21.4 registry and validation, a moss platform with the
 * default clear lists, the moss sorter's stock, and the file store (job.json and tile files as written to disk).
 */
class SameColourSubmissionTest {
    private static final String POT = "minecraft:decorated_pot";
    private static final String DRIP = "minecraft:pointed_dripstone";
    private static final String CANDLE = "minecraft:white_candle";
    private static final String OPEN = "minecraft:open_eyeblossom";
    private static final String CLOSED = "minecraft:closed_eyeblossom";

    @TempDir
    Path dir;
    MapArtConfig config;
    FileJobStore store;
    ParsedSubmission art;
    SubmissionPipeline pipeline;

    /** Parse hands back {@link #art}; validation is the real one. */
    final class GivenArt implements SubmissionService {
        final DefaultSubmissionService real = new DefaultSubmissionService();

        @Override
        public ParsedSubmission parse(List<NamedFile> files) {
            return art;
        }

        @Override
        public ValidationResult validate(ParsedSubmission s, BlockCatalog catalog, MapArtConfig.SiteCfg site) {
            return real.validate(s, catalog, site);
        }
    }

    static final class NoRender implements Renderer {
        @Override
        public byte[] preview(Map<TileCoord, TileArt> tiles, int tilesX, int tilesZ, BlockCatalog catalog,
                              ColorResolver colors, int scale) {
            return new byte[]{1};
        }

        @Override
        public byte[] mapData(byte[] packedColors, ColorResolver colors, int scale) {
            return new byte[0];
        }

        @Override
        public byte[] mapDiff(byte[] actualPacked, byte[] expectedPacked, ColorResolver colors, int scale) {
            return new byte[0];
        }
    }

    @BeforeEach
    void setUp() {
        config = new MapArtConfig();
        MapArtConfig.PlatformCfg moss = new MapArtConfig.PlatformCfg();
        moss.id = "moss";
        moss.kind = "MOSS";
        config.site.platforms.add(moss);
        store = new FileJobStore(dir, null, null);
        pipeline = newPipeline(store);
    }

    private SubmissionPipeline newPipeline(FileJobStore s) {
        return new SubmissionPipeline(new GivenArt(), new ZenithBlockCatalog(), config, s, new NoRender(),
            (id, b) -> 0xFF000000 | id, () -> null, NOPLogger.NOP_LOGGER);
    }

    /** The moss sorter's 44 materials (the local test world's counts). */
    private void stockMoss() {
        store.stock().update(new Pos(0, 64, 0), MossStock.items(), 0, 27, 1);
    }

    private static ParsedSubmission parsed(TileArt... tiles) {
        TreeMap<TileCoord, TileArt> m = new TreeMap<>();
        for (int i = 0; i < tiles.length; i++) m.put(new TileCoord(i, 0), tiles[i]);
        return new ParsedSubmission("verity", tiles.length, 1, DefaultSubmissionService.DATA_VERSION_1_21_4, m, List.of());
    }

    /** Rows of {@code ids[i]} on row i; the rest black carpet. */
    private static TileArt rows(String... ids) {
        TileArt t = new TileArt();
        for (int z = 0; z < 128; z++)
            for (int x = 0; x < 128; x++) t.set(x, z, BlockSpec.of(z < ids.length ? ids[z] : "minecraft:black_carpet"));
        return t;
    }

    private SubmissionPipeline.Outcome submit() {
        return pipeline.submit(new SubmissionPipeline.Request(List.of(new NamedFile("verity.nbt", new byte[]{1})),
            "verity", "42", "jared2013", "100", "200"));
    }

    @Test
    void swapsAreBuiltListedOnTheCardAndInTheTerminalAndKeptInTheJobFile() throws IOException {
        stockMoss();
        art = parsed(rows(POT, POT, DRIP, CANDLE, CLOSED));
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit());
        Job j = a.job();

        assertEquals(4, j.substitutions.size(), String.valueOf(j.substitutions.size()));
        assertEquals(256, j.materials.get("minecraft:red_terracotta"));
        assertEquals(128, j.materials.get("minecraft:brown_terracotta"));
        assertEquals(128, j.materials.get("minecraft:mushroom_stem"));
        assertEquals(128, j.materials.get(OPEN), "closed eyeblossoms stay (no color-49 block stocked)");
        assertFalse(j.materials.containsKey(POT));
        assertTrue(a.materials().stream().noneMatch(QueueOps.MaterialRow::unstocked), a.materials().toString());

        String line = "Swapped for identical colors: closed\\_eyeblossom ×128 → (none stocked), decorated\\_pot ×256 → "
            + "red\\_terracotta, pointed\\_dripstone ×128 → brown\\_terracotta, white\\_candle ×128 → mushroom\\_stem";
        Card c = SubmissionCards.of(a, List.of());
        assertTrue(c.description().startsWith("**verity** is #1 in the queue.\n" + line + "\n"), c.description());
        assertFalse(c.description().contains("No chest"), c.description());
        String warnings = c.fields().stream().filter(f -> f.name().equals("Warnings")).findFirst().orElseThrow().value();
        assertTrue(warnings.contains("maps with them are filled by day, when they are closed"), warnings);
        assertTrue(warnings.contains("up to 12 min"), warnings);
        assertTrue(MapArtCommand.localSummary(a).contains("\n" + line + "\n"), MapArtCommand.localSummary(a));

        // on disk: the swaps with the original states, and tiles holding what is built
        String json = Files.readString(dir.resolve("jobs").resolve(j.id).resolve("job.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"from\": \"minecraft:decorated_pot\""), json);
        assertTrue(json.contains("\"to\": \"minecraft:red_terracotta\""), json);
        FileJobStore reopened = new FileJobStore(dir, null, null);
        Job back = reopened.get(j.id).orElseThrow();
        assertEquals(4, back.substitutions.size());
        Job.Substitution closed = back.substitutions.getFirst();
        assertEquals(CLOSED, closed.from);
        assertNull(closed.to);
        assertEquals(128, closed.count);
        assertEquals(49, closed.mapColor);
        assertEquals("time of day", closed.reason);
        Job.Substitution pot = back.substitutions.get(1);
        assertEquals(POT, pot.from);
        assertEquals("minecraft:red_terracotta", pot.to);
        assertEquals(256, pot.count);
        assertEquals(50, pot.mapColor);
        assertEquals("no chest", pot.reason);
        TileArt tile = reopened.loadTile(j.id, new TileCoord(0, 0));
        assertEquals(BlockSpec.of("minecraft:red_terracotta"), tile.get(10, 1));
        assertEquals(BlockSpec.of("minecraft:mushroom_stem"), tile.get(10, 3));
        assertEquals(BlockSpec.of(CLOSED), tile.get(10, 4));
    }

    @Test
    void settingOffKeepsTheArtAndTheNoChestMark() throws IOException {
        stockMoss();
        config.map.substituteSameColour = false;
        art = parsed(rows(POT, DRIP, CANDLE));
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit());
        Job j = a.job();
        assertTrue(j.substitutions.isEmpty());
        assertEquals(128, j.materials.get(POT));
        Card c = SubmissionCards.of(a, List.of());
        assertFalse(c.description().contains("Swapped"), c.description());
        assertTrue(c.description().contains("No chest at this base for: decorated\\_pot, pointed\\_dripstone, white\\_candle."),
            c.description());
        assertEquals(BlockSpec.of(POT), store.loadTile(j.id, new TileCoord(0, 0)).get(0, 0));
        assertFalse(MapArtCommand.localSummary(a).contains("Swapped"));
    }

    @Test
    void unstockedWithoutAStandInStaysMarkedNoChest() {
        stockMoss();
        // lapis_block (LAPIS, 32) is a default nuke block; nothing the moss sorter stocks shows colour 32
        art = parsed(rows("minecraft:lapis_block"));
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit());
        assertTrue(a.job().substitutions.isEmpty());
        Card c = SubmissionCards.of(a, List.of());
        assertTrue(c.description().contains("No chest at this base for: lapis\\_block."), c.description());
        assertFalse(c.description().contains("Swapped"), c.description());
    }

    @Test
    void mixedEyeblossomsAreRejectedUnlessOneSideIsSwapped() {
        stockMoss();
        art = parsed(rows(OPEN, CLOSED));
        // with orange carpet stocked (moss stock), open ones become orange carpet and only closed ones remain
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit());
        assertEquals("minecraft:orange_carpet", a.job().substitutions.get(1).to);

        // without a stocked colour-15 block (or with the setting off) the map would need both states: rejected
        config.map.substituteSameColour = false;
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit());
        assertTrue(r.reasons().getFirst().startsWith("Map 0,0 uses both open and closed eyeblossoms."), r.reasons().toString());

        config.map.substituteSameColour = true;
        Map<String, Integer> noOrange = new LinkedHashMap<>(store.stock().totals());
        noOrange.remove("minecraft:orange_carpet");
        noOrange.remove("minecraft:terracotta");
        FileJobStore other = new FileJobStore(dir.resolve("other"), null, null);
        other.stock().update(new Pos(0, 64, 0), noOrange, 0, 27, 1);
        pipeline = newPipeline(other);
        r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit());
        assertTrue(r.reasons().getFirst().contains("one map cannot show both"), r.reasons().toString());
    }
}
