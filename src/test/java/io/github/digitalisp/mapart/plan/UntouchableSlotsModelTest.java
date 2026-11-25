package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.END_AWARE;

/**
 * What slots held by items the bot does not touch cost ({@code build.junkNukeDrops}):
 * live 2026-10-23 (j0015, gray and black carpet only) 404 clay balls of the j0014 nukes held 7 of the 23 material slots
 * a tile starts with (36 less tools, food, XP bottles, the anvil and the clay). {@link RestockModel} on two-colour
 * carpet tiles, 9-wide lanes, the live planner (AUTO) and the live leg fit: the loads with 23 and with 30 material slots.
 */
class UntouchableSlotsModelTest {
    private static final List<String> TWO = List.of("minecraft:gray_carpet", "minecraft:black_carpet");

    private final FakeCatalog catalog = new FakeCatalog();

    private static Config live(int slots) {
        return new Config("AUTO@" + slots, () -> new ConfiguredRestockPlanner(() -> "AUTO"), slots, 2, true, END_AWARE,
            true, RestockModel.Withdraw.STACKS, RestockModel.LIVE);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void sevenUntouchableSlotsCostAboutThreeRestocksATile() {
        StringBuilder out = new StringBuilder();
        int tripsCrowded = 0;
        int tripsFree = 0;
        double secondsCrowded = 0;
        double secondsFree = 0;
        for (long seed : new long[] {1, 2, 3}) {
            TileArt art = SyntheticArt.banded(seed, TWO, 6, 16, 64, 0.30);
            RestockModel model = new RestockModel(art, 4, catalog);
            Result crowded = model.run(live(23));
            Result free = model.run(live(30));
            out.append(String.format(Locale.ROOT, "seed %d: 23 slots %d trips %.0f s restock | 30 slots %d trips %.0f s"
                    + " restock%n", seed, crowded.trips(), crowded.restockSeconds(), free.trips(),
                free.restockSeconds()));
            tripsCrowded += crowded.trips();
            tripsFree += free.trips();
            secondsCrowded += crowded.restockSeconds();
            secondsFree += free.restockSeconds();
        }
        System.out.println(out);
        assertTrue(tripsFree < tripsCrowded, out.toString());
        assertTrue(secondsFree < secondsCrowded, out.toString());
    }
}
