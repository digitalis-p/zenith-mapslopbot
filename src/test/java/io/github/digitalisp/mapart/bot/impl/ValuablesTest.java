package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuablesTest {
    @Test
    void personalValuablesAreNeverDisposed() {
        for (String id : List.of("minecraft:totem_of_undying", "totem_of_undying", "minecraft:shulker_box",
            "minecraft:light_blue_shulker_box", "minecraft:diamond_sword", "minecraft:netherite_helmet",
            "minecraft:elytra", "minecraft:enchanted_golden_apple", "minecraft:netherite_pickaxe", "minecraft:shears",
            "minecraft:filled_map", "minecraft:experience_bottle", "minecraft:trident")) {
            assertTrue(Valuables.isValuable(id), id);
        }
    }

    /**
     * The elytra legs' kit ({@code build.elytraLegs}): never deposited, dropped or thrown (the deposit and drop tasks'
     * backstop), never junk even when the owner lists it, whatever the switch.
     */
    @Test
    void theFlightKitIsValuableAndNeverJunk() {
        for (String id : List.of("minecraft:elytra", "minecraft:firework_rocket", "firework_rocket")) {
            assertTrue(Valuables.isValuable(id), id);
            assertFalse(Valuables.junkWhenPlain(id), id);
        }
    }

    @Test
    void artMaterialsAreNotValuables() {
        for (String id : List.of("minecraft:white_carpet", "minecraft:white_terracotta", "minecraft:diamond_block",
            "minecraft:netherite_block", "minecraft:cobblestone", "minecraft:open_eyeblossom", "minecraft:pumpkin")) {
            assertFalse(Valuables.isValuable(id), id);
        }
        assertFalse(Valuables.isValuable(null));
    }
}
