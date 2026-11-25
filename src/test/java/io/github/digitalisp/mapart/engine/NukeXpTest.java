package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.InventoryView.Tool;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NukeXp}: the wear a nuke costs per tool id, and the XP bottles carried into it. */
class NukeXpTest {
    private static final String NETH = "minecraft:netherite_pickaxe";
    private static final String AXE = "minecraft:diamond_axe";
    private static final ToolCare CARE = new ToolCare(20, 50, 95);

    @Test
    void beforeAnyNukeAPickaxeCountsAUseInFourBreaks() {
        NukeXp x = new NukeXp();
        assertEquals(0.25, x.wearPerBlock(NETH));
        assertEquals(0.0, x.wearPerBlock(AXE));
        assertEquals(0.0, x.wearPerBlock("minecraft:netherite_shovel"));
    }

    @Test
    void anEllenMapWithANetheritePickaxeAt95PercentTakesTwoStacks() {
        // 9,879 × 0.25 = 2,470 uses; 1,929 − 1,015 = 914 of them before it is due; (2,470 − 914) × 1.15 / 14 = 127.8
        NukeXp x = new NukeXp();
        assertEquals(128, x.bottles(9_879, List.of(new Tool(NETH, 1929, 2031, true)), CARE));
        // at the threshold already: the whole wear, (2,470) × 1.15 / 14 = 202.9, capped at three stacks
        assertEquals(NukeXp.MAX_BOTTLES, x.bottles(9_879, List.of(new Tool(NETH, 1015, 2031, true)), CARE));
        // a small nuke within what the pickaxe has above its threshold: none
        assertEquals(0, x.bottles(3_000, List.of(new Tool(NETH, 1929, 2031, true)), CARE));
    }

    @Test
    void carpetArtToolsWithoutMendingAndUnusedToolsNeedNone() {
        NukeXp x = new NukeXp();
        assertEquals(0, x.bottles(0, List.of(new Tool(NETH, 1015, 2031, true)), CARE), "nothing to nuke");
        assertEquals(0, x.bottles(9_879, List.of(new Tool(NETH, 1015, 2031, false)), CARE), "no Mending: swapped");
        assertEquals(0, x.bottles(9_879, List.of(new Tool(AXE, 200, 1561, true)), CARE), "an axe the nuke does not use");
    }

    @Test
    void theMeasuredWearReplacesTheDefault() {
        NukeXp x = new NukeXp();
        // a pickaxe without Unbreaking: a use per break; the axe untouched
        x.observe(Map.of(NETH, 2000, AXE, 1500), Map.of(NETH, 1900, AXE, 1500), 100);
        assertEquals(0.25, x.wearPerBlock(NETH), "100 blocks are not enough to go by");
        x.observe(Map.of(NETH, 1900, AXE, 1500), Map.of(NETH, 1700, AXE, 1500), 200);
        assertEquals(1.0, x.wearPerBlock(NETH), 1e-9);
        assertEquals(0.0, x.wearPerBlock(AXE), 1e-9);
        // mended (more uses afterwards) or swapped away (gone): not measured
        x.observe(Map.of(NETH, 500, AXE, 1500), Map.of(NETH, 1500), 400);
        assertEquals(1.0, x.wearPerBlock(NETH), 1e-9);
        assertEquals(0.0, x.wearPerBlock(AXE), 1e-9);
        assertTrue(x.describe(List.of(new Tool(NETH, 1, 2031, true))).contains("1.00/block (measured)"));
    }

    @Test
    void twoPickaxesOfOneIdShareTheWear() {
        NukeXp x = new NukeXp();
        List<Tool> two = List.of(new Tool(NETH, 1929, 2031, true), new Tool(NETH, 1929, 2031, true));
        // 2 × 914 = 1,828 above the thresholds covers 2,470 less 642: 642 × 1.15 / 14 = 52.7
        assertEquals(53, x.bottles(9_879, two, CARE));
        assertEquals(Map.of(NETH, 3858), NukeXp.usesById(two));
    }

    @Test
    void mendingDuringTheClearTakesTheBottlesBackToTheTarget() {
        NukeXp x = new NukeXp();
        // at 95 % (target 1,930): a 3,000-block nuke's 750 uses and the one use it lacks: 751 × 1.15 / 14 = 61.7
        assertEquals(0, x.bottles(3_000, List.of(new Tool(NETH, 1929, 2031, true)), CARE));
        assertEquals(62, x.bottles(3_000, List.of(new Tool(NETH, 1929, 2031, true)), CARE, true));
        // at 54 %: 640 uses of wear plus the 830 it lacks: 1,470 × 1.15 / 14 = 120.75; to the threshold only 46
        assertEquals(46, x.bottles(2_560, List.of(new Tool(NETH, 1100, 2031, true)), CARE));
        assertEquals(121, x.bottles(2_560, List.of(new Tool(NETH, 1100, 2031, true)), CARE, true));
        // nothing to nuke, nothing taken (no clear wait to fill either way on a carpet map's NUKE)
        assertEquals(0, x.bottles(0, List.of(new Tool(NETH, 1100, 2031, true)), CARE, true));
        assertEquals(NukeXp.MAX_BOTTLES, x.bottles(9_879, List.of(new Tool(NETH, 1929, 2031, true)), CARE, true));
    }

    @Test
    void theDeferralGoesOnlyByMeasuredWear() {
        NukeXp x = new NukeXp();
        assertTrue(Double.isNaN(x.measuredWear(NETH, 0, 0)), "nothing measured: no guess");
        assertTrue(Double.isNaN(x.measuredWear(NETH, 10, 100)), "100 blocks are not enough");
        assertEquals(0.25, x.measuredWear(NETH, 150, 600), 1e-9);
        x.observe(Map.of(NETH, 2000), Map.of(NETH, 1900), 400);
        assertEquals(0.3, x.measuredWear(NETH, 50, 100), 1e-9, "stored and live samples together: 150 / 500");
        assertEquals(0.25, x.measuredWear(NETH, 0, 0), 1e-9);
    }

    @Test
    void aDueToolWaitsForTheClearOnlyWhenTheBoundLeavesTheReserve() {
        Tool due = new Tool(NETH, 950, 2031, true);
        // 1,960 blocks at 0.25: 490 × 1.15 + 3 √490 = 629.9; 950 − 629.9 = 320 ≥ 20 + 204 (10 % of 2,031)
        assertEquals(629.9, NukeXp.wearBound(1_960, 0.25), 0.05);
        assertTrue(NukeXp.lastsTheNuke(due, 1_960, 0.25, CARE));
        // 2,600 blocks: 747.5 + 76.5 = 824; 126 left is less than the reserve
        assertFalse(NukeXp.lastsTheNuke(due, 2_600, 0.25, CARE));
        // unmeasured: never
        assertFalse(NukeXp.lastsTheNuke(due, 10, Double.NaN, CARE));
        // no blocks left: three uses of spread at most
        assertEquals(3.0, NukeXp.wearBound(0, 0.25), 1e-9);
        assertTrue(NukeXp.lastsTheNuke(new Tool(NETH, 230, 2031, true), 0, 0.25, CARE));
        assertFalse(NukeXp.lastsTheNuke(new Tool(NETH, 226, 2031, true), 0, 0.25, CARE));
    }
}
