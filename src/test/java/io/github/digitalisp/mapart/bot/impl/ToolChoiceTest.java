package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.ToolChoice.Candidate;
import io.github.digitalisp.mapart.bot.impl.ToolChoice.Choice;
import io.github.digitalisp.mapart.bot.impl.ToolChoice.Kind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Speeds are vanilla destroy progress per tick: tool speed / hardness / (correct tool ? 30 : 100). */
class ToolChoiceTest {
    private static final int FLOOR = 20;
    private static final String DIA = "minecraft:diamond_pickaxe";
    private static final String NETH = "minecraft:netherite_pickaxe";

    // carpet: hardness 0.1, no tool needed, no tool faster
    private static final double CARPET_HAND = 1 / 0.1 / 30;
    // white terracotta: hardness 1.25, needs a pickaxe for drops
    private static final double TERRACOTTA_HAND = 1 / 1.25 / 100;
    private static final double TERRACOTTA_DIAMOND = 8 / 1.25 / 30;
    private static final double TERRACOTTA_NETHERITE = 9 / 1.25 / 30;

    private static Candidate tool(int slot, String id, double speed, int remaining, boolean mending) {
        int max = id.contains("netherite") ? 2031 : 1561;
        return new Candidate(slot, id, speed, remaining, max, mending, slot >= ItemIds.HOTBAR_START);
    }

    @Test
    void carpetsAreBrokenByHandEvenWithAToolInTheInventory() {
        assertEquals(3, ToolChoice.ticks(CARPET_HAND));
        Choice c = ToolChoice.choose(CARPET_HAND, List.of(tool(44, DIA, CARPET_HAND, 1561, true)), FLOOR);
        assertEquals(Kind.HAND, c.kind());
        assertNull(c.tool());
    }

    @Test
    void nearInstantBlocksUseTheHandEvenWhenAToolWouldBeFaster() {
        // e.g. a hardness 0.1 block a tool speeds up: still 3 ticks by hand, so no use is spent
        assertEquals(Kind.HAND, ToolChoice.choose(CARPET_HAND, List.of(tool(44, DIA, 8 / 0.1 / 30, 1561, true)), FLOOR).kind());
        // hardness 0: instant
        assertEquals(Kind.HAND, ToolChoice.choose(Double.POSITIVE_INFINITY, List.of(tool(44, DIA, 1e9, 1561, true)), FLOOR).kind());
    }

    @Test
    void terracottaUsesThePickaxe() {
        Choice c = ToolChoice.choose(TERRACOTTA_HAND, List.of(tool(44, DIA, TERRACOTTA_DIAMOND, 900, true)), FLOOR);
        assertEquals(Kind.TOOL, c.kind());
        assertEquals(44, c.tool().slot());
    }

    @Test
    void theRightKindPerBlock() {
        double logHand = 1 / 2.0 / 30;
        Candidate pick = new Candidate(36, DIA, logHand, 1561, 1561, true, true);       // pickaxe: no faster on logs
        Candidate axe = new Candidate(37, "minecraft:diamond_axe", 8 / 2.0 / 30, 1561, 1561, true, true);
        Candidate shovel = new Candidate(38, "minecraft:diamond_shovel", logHand, 1561, 1561, true, true);
        assertEquals(37, ToolChoice.choose(logHand, List.of(pick, axe, shovel), FLOOR).tool().slot());

        double dirtHand = 1 / 0.5 / 30;
        Candidate shovelOnDirt = new Candidate(38, "minecraft:diamond_shovel", 8 / 0.5 / 30, 1561, 1561, true, true);
        Candidate pickOnDirt = new Candidate(36, DIA, dirtHand, 1561, 1561, true, true);
        assertEquals(38, ToolChoice.choose(dirtHand, List.of(pickOnDirt, shovelOnDirt), FLOOR).tool().slot());
    }

    @Test
    void noFasterToolMeansTheHand() {
        double woolHand = 1 / 0.8 / 30; // 24 ticks; pickaxe/axe/shovel are no faster on wool
        Choice c = ToolChoice.choose(woolHand, List.of(tool(44, DIA, woolHand, 1561, true)), FLOOR);
        assertEquals(Kind.HAND, c.kind());
    }

    @Test
    void neverAToolAtTheFloor() {
        // the faster netherite pickaxe is at the floor: the diamond one above it is used
        Choice c = ToolChoice.choose(TERRACOTTA_HAND, List.of(
            tool(44, NETH, TERRACOTTA_NETHERITE, FLOOR, true),
            tool(20, DIA, TERRACOTTA_DIAMOND, FLOOR + 1, false)), FLOOR);
        assertEquals(Kind.TOOL, c.kind());
        assertEquals(20, c.tool().slot());
    }

    @Test
    void everyPickaxeAtTheFloorStops() {
        Choice c = ToolChoice.choose(TERRACOTTA_HAND, List.of(
            tool(44, DIA, TERRACOTTA_DIAMOND, 12, true),
            tool(20, DIA, TERRACOTTA_DIAMOND, FLOOR, false)), FLOOR);
        assertEquals(Kind.WORN, c.kind());
        String why = c.wornReason(FLOOR);
        assertTrue(why.startsWith("no pickaxe above the safety floor (20 uses)"), why);
        assertTrue(why.contains("diamond_pickaxe at "), why);
    }

    @Test
    void aWornToolThatDoesNotMatterIsNoReasonToStop() {
        // a worn pickaxe on wool: the hand is as fast, so the hand breaks it
        double woolHand = 1 / 0.8 / 30;
        assertEquals(Kind.HAND, ToolChoice.choose(woolHand, List.of(tool(44, DIA, woolHand, 5, true)), FLOOR).kind());
    }

    @Test
    void atTheSameSpeedMendingThenHotbarThenMostUsesLeft() {
        Candidate plain = tool(44, DIA, TERRACOTTA_DIAMOND, 1500, false);
        Candidate mending = tool(20, DIA, TERRACOTTA_DIAMOND, 300, true);
        assertEquals(20, ToolChoice.choose(TERRACOTTA_HAND, List.of(plain, mending), FLOOR).tool().slot());
        Candidate hotbar = tool(40, DIA, TERRACOTTA_DIAMOND, 300, true);
        assertEquals(40, ToolChoice.choose(TERRACOTTA_HAND, List.of(mending, hotbar), FLOOR).tool().slot());
        Candidate more = tool(41, DIA, TERRACOTTA_DIAMOND, 900, true);
        assertEquals(41, ToolChoice.choose(TERRACOTTA_HAND, List.of(hotbar, more), FLOOR).tool().slot());
        // two equal pickaxes: the held one stays, no switch after every block
        Candidate heldA = new Candidate(36, DIA, TERRACOTTA_DIAMOND, 999, 1561, true, true, true);
        Candidate otherB = new Candidate(37, DIA, TERRACOTTA_DIAMOND, 1000, 1561, true, true, false);
        assertEquals(36, ToolChoice.choose(TERRACOTTA_HAND, List.of(otherB, heldA), FLOOR).tool().slot());
    }

    @Test
    void ticks() {
        assertEquals(1, ToolChoice.ticks(1.0));
        assertEquals(1, ToolChoice.ticks(Double.POSITIVE_INFINITY));
        assertEquals(5, ToolChoice.ticks(TERRACOTTA_DIAMOND));
        assertEquals(125, ToolChoice.ticks(TERRACOTTA_HAND));
        assertEquals(Integer.MAX_VALUE, ToolChoice.ticks(0));
        assertEquals(Integer.MAX_VALUE, ToolChoice.ticks(-1));
    }
}
