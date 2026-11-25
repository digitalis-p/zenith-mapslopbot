package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.END_AWARE;

/**
 * The leftovers drop and the crowded-load return ({@code build.lazyDrop},
 * {@code build.crowdKeepReused}) in the restock model, on tiles with a long tail of rarely used items, the shape of the
 * live remu tiles (carpet bulk, a dozen details of a few dozen cells to a few hundred): the AUTO bundle as it is
 * against the bundle with the rules. Live (8 maps): 52 of 98 restocks dropped, 12-26 s each.
 */
class LazyDropModelTest {
    private static final List<String> DETAILS = List.of("minecraft:cherry_pressure_plate",
        "minecraft:pale_oak_pressure_plate", "minecraft:stone_pressure_plate", "minecraft:heavy_weighted_pressure_plate",
        "minecraft:oak_pressure_plate", "minecraft:white_candle", "minecraft:pointed_dripstone",
        "minecraft:decorated_pot", "minecraft:pink_petals", "minecraft:resin_clump");

    private final FakeCatalog catalog = new FakeCatalog();

    /** The base tile with details scattered: detail i holds about (i + 1) * 0.15 % of the cells. */
    private static TileArt withDetails(TileArt base, long seed) {
        Random r = new Random(seed);
        TileArt t = new TileArt();
        for (int z = 0; z < TileArt.SIZE; z++)
            for (int x = 0; x < TileArt.SIZE; x++) {
                BlockSpec b = base.get(x, z);
                double u = r.nextDouble();
                double acc = 0;
                for (int i = 0; i < DETAILS.size(); i++) {
                    acc += (i + 1) * 0.0015;
                    if (u < acc) {
                        b = BlockSpec.of(DETAILS.get(i));
                        break;
                    }
                }
                if (b != null) t.set(x, z, b);
            }
        return t;
    }

    private static Config bundle() {
        return new Config("bundle", () -> new ConfiguredRestockPlanner(() -> "AUTO"), 31, 2, true, END_AWARE);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void leftoversAndReturnsAreFewerAndNeverSlower() {
        StringBuilder out = new StringBuilder();
        double sumBefore = 0;
        double sumAfter = 0;
        int dropsBefore = 0;
        int dropsAfter = 0;
        for (long seed : new long[] {1, 2, 3}) {
            TileArt art = withDetails(SyntheticArt.carpet16(seed), seed);
            for (int half : new int[] {4, 2}) {
                RestockModel model = new RestockModel(art, half, catalog);
                Result before = model.run(bundle());
                Result lazy = model.run(bundle().restockRules("lazy", 6, false));
                Result both = model.run(bundle().restockRules("both", 6, true));
                out.append(String.format(Locale.ROOT, "seed %d, %d-wide: bundle %d trips %d drops %.0f s (drop %.0f s) | lazy "
                        + "%d trips %d drops (%d skipped) %.0f s | both %d trips %d drops %.0f s%n", seed, 2 * half + 1,
                    before.trips(), before.drops, before.totalSeconds(), before.dropSeconds, lazy.trips(), lazy.drops,
                    lazy.lazySkips, lazy.totalSeconds(), both.trips(), both.drops, both.totalSeconds()));
                sumBefore += before.totalSeconds();
                sumAfter += both.totalSeconds();
                dropsBefore += before.drops;
                dropsAfter += both.drops;
            }
        }
        System.out.println(out);
        // a skipped drop can move a cut and cost a lane change on a tile; over the six runs the rules do not lose
        assertTrue(dropsAfter < dropsBefore, out.toString());
        assertTrue(sumAfter <= sumBefore, out.toString());
    }
}
