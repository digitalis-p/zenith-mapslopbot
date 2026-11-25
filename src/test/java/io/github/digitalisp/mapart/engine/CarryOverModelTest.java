package io.github.digitalisp.mapart.engine;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.Defaults;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.plan.DefaultSubmissionService;
import io.github.digitalisp.mapart.store.TileCodec;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much carry-over saves on real art: consecutive tiles of real
 * maps, the first built on the platform, the second next. Per pair the share of cells that are the same block, and both
 * estimates of {@link CarryOver} with the platform's clearable list ({@link Defaults#PISTON_CLEARABLE}). The table goes
 * to {@code build/carry-over-model.md}.
 *
 * <p>Tiles: sizedreisen (MapArtCraft, 4 x 2, mixed carpets and full blocks; build order row by row), ellen [0,6] →
 * [1,6] (live 2b2t, dense), and lapis-background pairs (synthetic: the recent soul sand maps are mostly
 * lapis_block, whose tiles are not in the repository).
 */
class CarryOverModelTest {
    private static final PlatformGeometry GEO = new PlatformGeometry(0, 0, 64);
    private final SimFakes.FakeCatalog catalog = new SimFakes.FakeCatalog();

    private record Pair(String name, TileArt now, TileArt next) { }

    private static TileArt ellen(String name) throws Exception {
        try (var in = new InputStreamReader(CarryOverModelTest.class.getResourceAsStream("/fixtures/art/" + name + ".json"),
            StandardCharsets.UTF_8)) {
            return TileCodec.decode(new Gson().fromJson(in, TileCodec.Stored.class));
        }
    }

    /** {@code share} of the cells lapis (the field's low end), the rest {@code fg} in bands. */
    private static TileArt lapis(long seed, double share, List<String> fg) {
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:lapis_block");
        palette.addAll(fg);
        TileArt banded = SyntheticArt.banded(seed, palette, 6, 16, 64, 0.30);
        // re-weight: the lapis share of the cells, the bands of the others kept
        TileArt out = new TileArt();
        Random r = new Random(seed * 31 + 7);
        for (int z = 0; z < 128; z++)
            for (int x = 0; x < 128; x++) {
                BlockSpec b = banded.get(x, z);
                out.set(x, z, r.nextDouble() < share || b == null ? BlockSpec.of("minecraft:lapis_block") : b);
            }
        return out;
    }

    private static final class Random extends java.util.Random {
        Random(long seed) {
            super(seed);
        }
    }

    private static int same(TileArt a, TileArt b) {
        int n = 0;
        for (int z = 0; z < 128; z++)
            for (int x = 0; x < 128; x++) {
                BlockSpec p = a.get(x, z), q = b.get(x, z);
                if (p != null && p.equals(q)) n++;
            }
        return n;
    }

    @Test
    void consecutiveRealTilesAndTheirSavings() throws Exception {
        List<Pair> pairs = new ArrayList<>();
        Map<TileCoord, TileArt> sr = new DefaultSubmissionService().parse(Fixtures.files(Fixtures.SIZEDREISEN)).tiles();
        List<TileArt> order = new ArrayList<>();
        for (int z = 0; z < 2; z++) for (int x = 0; x < 4; x++) order.add(sr.get(new TileCoord(x, z)));
        for (int i = 0; i + 1 < order.size(); i++)
            pairs.add(new Pair("sizedreisen " + (i % 4) + "," + (i / 4) + " → " + ((i + 1) % 4) + "," + ((i + 1) / 4),
                order.get(i), order.get(i + 1)));
        pairs.add(new Pair("ellen 0,6 → 1,6 (live)", ellen("ellen_0_6"), ellen("ellen_1_6")));
        List<String> fg = List.of("minecraft:white_carpet", "minecraft:light_blue_carpet", "minecraft:blue_carpet",
            "minecraft:cyan_terracotta", "minecraft:black_carpet");
        for (double share : new double[] {0.6, 0.8, 0.9})
            pairs.add(new Pair(String.format(Locale.ROOT, "lapis %.0f %% + bands (synthetic)", share * 100),
                lapis(11, share, fg), lapis(12, share, fg)));

        List<String> lines = new ArrayList<>();
        lines.add("| Pair | Same block | Kept | Break (by hand) | Place | Carry-over min | Normal min | Saved min | Decision (margin 10 %) |");
        lines.add("|---|---:|---:|---:|---:|---:|---:|---:|---|");
        double savedAll = 0;
        int taken = 0;
        for (Pair p : pairs) {
            SimFakes.FakeWorld w = new SimFakes.FakeWorld();
            for (int z = 0; z < 128; z++)
                for (int x = 0; x < 128; x++) {
                    BlockSpec b = p.now().get(x, z);
                    if (b != null) w.set(GEO.cell(x, z), b);
                }
            TilePlan now = new TilePlan(p.now(), GEO, catalog), next = new TilePlan(p.next(), GEO, catalog);
            CarryOver.Estimate e = CarryOver.estimate(now, next, w, Defaults.PISTON_CLEARABLE);
            boolean carry = e.carry(0.1);
            if (carry) {
                taken++;
                savedAll += e.savedSeconds();
            }
            lines.add(String.format(Locale.ROOT, "| %s | %.1f %% | %,d | %,d (%,d) | %,d | %.1f | %.1f | %.1f | %s |", p.name(),
                100.0 * same(p.now(), p.next()) / 16_384, e.kept(), e.breakFull() + e.breakHand(), e.breakHand(),
                e.placeCarry(), e.carrySeconds() / 60, e.normalSeconds() / 60, e.savedSeconds() / 60,
                carry ? "carry over" : "nuke and clear"));
        }
        lines.add("");
        lines.add(String.format(Locale.ROOT, "%d of %d pairs carry over; %.1f min saved over them (%.1f min a pair).", taken,
            pairs.size(), savedAll / 60, taken == 0 ? 0 : savedAll / 60 / taken));
        String table = String.join("\n", lines);
        System.out.println(table);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/carry-over-model.md"), table + "\n");
        assertTrue(taken > 0, table);
    }
}
