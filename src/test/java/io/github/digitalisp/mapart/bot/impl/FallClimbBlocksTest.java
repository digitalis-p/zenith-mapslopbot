package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FallClimbBlocks}: which carried block the fall rescue's climb places. */
class FallClimbBlocksTest {
    private static final List<String> PREFERRED = List.of("minecraft:netherrack", "minecraft:cobblestone");

    @Test
    void theFirstPreferredBlockCarriedComesFirst() {
        Map<String, Integer> carried = Map.of("minecraft:cobblestone", 3, "minecraft:white_wool", 200,
            "minecraft:netherrack", 1);
        assertEquals("minecraft:netherrack", FallClimbBlocks.choose(carried, PREFERRED, true, id -> true));
        assertEquals("minecraft:cobblestone",
            FallClimbBlocks.choose(Map.of("minecraft:cobblestone", 3), PREFERRED, true, id -> true));
    }

    @Test
    void elseTheSuitableBlockOfWhichThereIsMost() {
        Map<String, Integer> carried = Map.of("minecraft:white_wool", 20, "minecraft:red_terracotta", 64,
            "minecraft:diamond_pickaxe", 1, "minecraft:sand", 300);
        String id = FallClimbBlocks.choose(carried, PREFERRED, true, s -> !FallClimbBlocks.unsuitable(s)
            && !s.endsWith("pickaxe"));
        assertEquals("minecraft:red_terracotta", id, "sand falls; the most of the rest");
        assertNull(FallClimbBlocks.choose(carried, PREFERRED, false, s -> true), "only the preferred ones");
        assertNull(FallClimbBlocks.choose(Map.of(), PREFERRED, true, s -> true));
    }

    @Test
    void theBlocksAvailableCountEveryKindTheClimbMayTake() {
        Map<String, Integer> carried = Map.of("minecraft:netherrack", 1, "minecraft:cobblestone", 2,
            "minecraft:white_wool", 5, "minecraft:sand", 64, "minecraft:white_carpet", 300);
        java.util.function.Predicate<String> suits = s -> !FallClimbBlocks.unsuitable(s) && !s.endsWith("carpet");
        assertEquals(8, FallClimbBlocks.available(carried, PREFERRED, true, suits), "kit, cobblestone and wool");
        assertEquals(3, FallClimbBlocks.available(carried, PREFERRED, false, suits), "only the preferred");
        assertEquals(0, FallClimbBlocks.available(Map.of("minecraft:white_carpet", 300), PREFERRED, true, suits),
            "carpets are no climb");
    }

    @Test
    void blocksThatFallMeltPowerOrAreWorthKeepingAreNever() {
        for (String id : List.of("minecraft:sand", "minecraft:gravel", "minecraft:white_concrete_powder", "minecraft:ice",
            "minecraft:packed_ice", "minecraft:slime_block", "minecraft:honey_block", "minecraft:tnt",
            "minecraft:redstone_block", "minecraft:observer", "minecraft:diamond_block", "minecraft:diamond_ore",
            "minecraft:obsidian", "minecraft:glass", "minecraft:white_stained_glass", "minecraft:oak_leaves",
            "minecraft:soul_sand", "minecraft:magma_block", "minecraft:sea_lantern", "minecraft:shulker_box",
            "minecraft:copper_bulb", "minecraft:crafting_table", "minecraft:bookshelf")) {
            assertTrue(FallClimbBlocks.unsuitable(id), id);
        }
        for (String id : List.of("minecraft:netherrack", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:dirt", "minecraft:white_wool", "minecraft:red_terracotta", "minecraft:sandstone",
            "minecraft:stone_bricks", "minecraft:white_concrete")) {
            assertFalse(FallClimbBlocks.unsuitable(id), id);
        }
    }
}
