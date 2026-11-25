package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.InventoryView.Tool;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCareTest {
    private static final String DIA = "minecraft:diamond_pickaxe";
    private final ToolCare care = ToolCare.of(new MapArtConfig().build);

    @Test
    void defaults() {
        assertEquals(20, care.floor);
        assertEquals(50, care.belowPercent);
        assertEquals(95, care.targetPercent);
    }

    @Test
    void thresholds() {
        Tool half = new Tool(DIA, 780, 1561, true);
        Tool above = new Tool(DIA, 781, 1561, true);
        assertTrue(care.wantsMend(half), "50 % of 1561 is 780.5");
        assertFalse(care.wantsMend(above));
        assertFalse(care.wantsMend(new Tool(DIA, 100, 1561, false)), "no Mending: never mended");
        assertTrue(care.atFloor(new Tool(DIA, 20, 1561, false)));
        assertFalse(care.atFloor(new Tool(DIA, 21, 1561, false)));
        assertEquals(1483, care.targetUses(half));
        assertTrue(care.mended(new Tool(DIA, 1483, 1561, true)));
        assertFalse(care.mended(new Tool(DIA, 1482, 1561, true)));
    }

    @Test
    void bottlesAtSevenXpAndTwoDurabilityPerPoint() {
        // 1483 - 20 = 1463 missing / 14 per bottle = 104.5 -> 105
        assertEquals(105, care.bottlesToMend(new Tool(DIA, 20, 1561, true), 500));
        assertEquals(64, care.bottlesToMend(new Tool(DIA, 20, 1561, true), 64), "capped");
        assertEquals(1, care.bottlesToMend(new Tool(DIA, 1480, 1561, true), 500), "at least one below the target");
        assertEquals(0, care.bottlesToMend(new Tool(DIA, 1500, 1561, true), 500));
        // netherite from the floor: (1930 - 20) / 14 = 136.4 -> 137
        assertEquals(137, care.bottlesToMend(new Tool("minecraft:netherite_pickaxe", 20, 2031, true), 500));
    }

    @Test
    void sparesAreOfTheSameKindAndAboveTheFloor() {
        Tool worn = new Tool(DIA, 20, 1561, true);
        Tool axe = new Tool("minecraft:diamond_axe", 1561, 1561, true);
        Tool wornToo = new Tool("minecraft:netherite_pickaxe", 5, 2031, true);
        assertFalse(care.hasSpare(List.of(worn, axe, wornToo), worn));
        Tool spare = new Tool("minecraft:netherite_pickaxe", 21, 2031, false);
        assertTrue(care.hasSpare(List.of(worn, axe, spare), worn));
    }

    @Test
    void mostWornAndProgress() {
        Tool a = new Tool(DIA, 700, 1561, true);
        Tool b = new Tool("minecraft:netherite_pickaxe", 700, 2031, true);
        assertSame(b, ToolCare.mostWorn(List.of(a, b), t -> true));
        assertNull(ToolCare.mostWorn(List.of(a, b), t -> false));
        assertEquals(1400, ToolCare.mendingUses(List.of(a, new Tool(DIA, 700, 1561, true), new Tool(DIA, 9, 1561, false)), DIA));
        assertEquals("diamond_pickaxe at 12/1561 (no Mending)", ToolCare.describe(new Tool(DIA, 12, 1561, false)));
        assertEquals("diamond_pickaxe at 12/1561", ToolCare.describe(new Tool(DIA, 12, 1561, true)));
    }

    @Test
    void sanitisedSettings() {
        ToolCare odd = new ToolCare(-5, 150, 10);
        assertEquals(0, odd.floor);
        assertEquals(100, odd.belowPercent);
        assertEquals(100, odd.targetPercent, "the target is never below the mend threshold");
    }

    @Test
    void toolKinds() {
        assertEquals("pickaxe", InventoryView.toolKind("minecraft:netherite_pickaxe"));
        assertEquals("axe", InventoryView.toolKind("minecraft:diamond_axe"));
        assertEquals("shovel", InventoryView.toolKind("diamond_shovel"));
        assertEquals("hoe", InventoryView.toolKind("minecraft:iron_hoe"));
        assertEquals("shears", InventoryView.toolKind("minecraft:shears"));
        assertNull(InventoryView.toolKind("minecraft:diamond_sword"));
        assertNull(InventoryView.toolKind("minecraft:white_carpet"));
        assertNull(InventoryView.toolKind(null));
        assertEquals(0.5, new Tool(DIA, 780, 1560, true).fraction(), 1e-9);
    }
}
