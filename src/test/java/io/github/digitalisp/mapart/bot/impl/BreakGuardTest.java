package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PathingGuard} keeps the pathfinder from breaking while the plugin walks, except {@link BreakGuard}'s seal
 * blocks while it holds: live 2026-10-13 (remu [2,2]) PathingGuard turned BreakGuard's allowBreak off again on every
 * walk tick, and every last-resort walk out of the sealed area failed; live 2026-10-13 23:43 a breaking walk with
 * allowBreak on broke storage chests.
 */
class BreakGuardTest {
    @Test
    void pathingBreaksOnlySealBlocksAndOnlyWhileBreakGuardHolds() {
        assertTrue(PathingGuard.breakAnyway(0).isEmpty());
        assertEquals(BreakGuard.SEAL_BLOCKS, PathingGuard.breakAnyway(1));
        assertEquals(BreakGuard.SEAL_BLOCKS, PathingGuard.breakAnyway(2));
    }

    @Test
    void noContainerMachinePartOrFullBlockIsEverASealBlock() {
        for (String id : List.of("chest", "trapped_chest", "barrel", "ender_chest", "shulker_box", "white_shulker_box",
            "hopper", "dropper", "dispenser", "piston", "sticky_piston", "observer", "redstone_wire", "stone_button",
            "lever", "anvil", "cartography_table", "crafting_table", "soul_sand", "soul_soil", "moss_block",
            "netherrack", "stone", "white_carpet", "white_wool", "glass", "obsidian"))
            assertFalse(BreakGuard.SEAL_BLOCKS.contains(id), id);
        for (String id : BreakGuard.SEAL_BLOCKS) assertFalse(id.contains(":"), "registry names carry no namespace: " + id);
        assertTrue(BreakGuard.SEAL_BLOCKS.contains("pointed_dripstone"));
    }

    @Test
    void aBreakIsWorthAFewBlocksOfWalkingNotAShortcutThroughAChest() {
        assertTrue(BreakGuard.BREAK_COST > 2.0, "costlier than ZenithProxy's default");
        assertTrue(BreakGuard.BREAK_COST <= 50, "cheap enough for the search to end within its time limit");
    }
}
