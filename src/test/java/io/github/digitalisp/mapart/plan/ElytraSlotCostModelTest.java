package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.Fixtures;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.RestockModel.Config;
import io.github.digitalisp.mapart.testutil.RestockModel.Result;
import io.github.digitalisp.mapart.testutil.SyntheticArt;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.RestockModel.Sweep.NEAREST_END;

/**
 * What the elytra kit's slots cost the loads ({@code build.elytraLegs}):
 * {@link RestockModel} with today's engine (greedy, whole stacks) at 28 material slots (the
 * kit of 5.19: three tools, food, XP bottles, anvils, map, pane), 26 (plus the elytra and one rocket stack) and 25
 * (5.25's elytra and two stacks), over the model's three tiles in 9-wide lanes (airplace). Prints one table
 * (stdout): {@code ./gradlew test --offline --tests 'io.github.digitalisp.mapart.plan.ElytraSlotCostModelTest' -i}.
 */
class ElytraSlotCostModelTest {
    private static final int AIRPLACE = 4;
    private static final String MIXED = "sizedreisen/0006_sizedreisen_1_1.nbt";

    private final FakeCatalog catalog = new FakeCatalog();

    private static Config main(int slots) {
        return new Config("main@" + slots, GreedyRestockPlanner::new, slots, 0, false, NEAREST_END, false);
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void twoSlotsForTheKitCostAboutOneRestockAMap() throws Exception {
        TileArt mixed = new DefaultSubmissionService().parse(List.of(Fixtures.renamed(MIXED, "sizedreisen-1-1.nbt")))
            .tiles().values().iterator().next();
        assertEquals(128 * 128, mixed.nonEmptyCount());
        List<Object[]> tiles = List.of(new Object[] {"carpet 16", SyntheticArt.carpet16(16)},
            new Object[] {"dithered 6", SyntheticArt.dithered6(42)}, new Object[] {"mixed (sizedreisen 1_1)", mixed});
        StringBuilder sb = new StringBuilder("| tile | trips @28 / @26 / @25 | restock s @28 / @26 / @25 (model) |\n"
            + "|---|---:|---:|\n");
        for (Object[] t : tiles) {
            RestockModel model = new RestockModel((TileArt) t[1], AIRPLACE, catalog);
            Result r28 = model.run(main(28)), r26 = model.run(main(26)), r25 = model.run(main(25));
            sb.append(String.format(Locale.ROOT, "| %s | %d / %d / %d | %.0f / %.0f / %.0f |%n", t[0], r28.trips(),
                r26.trips(), r25.trips(), r28.restockSeconds(), r26.restockSeconds(), r25.restockSeconds()));
            assertEquals(0, r28.shortCells + r26.shortCells + r25.shortCells, t[0] + ": a load short of its plan");
            assertTrue(r26.trips() >= r28.trips() && r25.trips() >= r26.trips(), t[0] + ": fewer slots, fewer trips?");
            assertTrue(r26.trips() <= r28.trips() + 2, t[0] + ": two slots cost " + (r26.trips() - r28.trips()) + " trips");
        }
        System.out.println(sb);
    }
}
