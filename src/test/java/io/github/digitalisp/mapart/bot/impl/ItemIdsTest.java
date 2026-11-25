package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemIdsTest {
    @Test
    void namespacing() {
        assertEquals("minecraft:white_carpet", ItemIds.ns("white_carpet"));
        assertEquals("minecraft:white_carpet", ItemIds.ns(" Minecraft:White_Carpet "));
        assertEquals("other:thing", ItemIds.ns("other:thing"));
        assertEquals("white_carpet", ItemIds.path("minecraft:white_carpet"));
        assertEquals("white_carpet", ItemIds.path("white_carpet"));
        assertNull(ItemIds.ns(null));
    }

    @Test
    void eyeblossomsAreOneMaterial() {
        assertEquals("minecraft:open_eyeblossom", ItemIds.material("minecraft:closed_eyeblossom"));
        assertEquals("minecraft:open_eyeblossom", ItemIds.material("closed_eyeblossom"));
        assertEquals("minecraft:open_eyeblossom", ItemIds.material("minecraft:open_eyeblossom"));
        assertEquals("minecraft:white_carpet", ItemIds.material("white_carpet"));
        assertNull(ItemIds.material(null));
    }

    @Test
    void inventoryStacksOfEitherEyeblossomReadAsTheMaterial() {
        var closed = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack(
            com.zenith.mc.item.ItemRegistry.CLOSED_EYEBLOSSOM.id(), 51);
        var open = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack(
            com.zenith.mc.item.ItemRegistry.OPEN_EYEBLOSSOM.id(), 3);
        assertEquals("minecraft:open_eyeblossom", Inv.id(closed));
        assertEquals("minecraft:open_eyeblossom", Inv.id(open));
        assertEquals(true, Inv.is("minecraft:open_eyeblossom").test(closed), "hotbar search and counts find closed stacks");
        assertEquals(true, Inv.is("minecraft:closed_eyeblossom").test(open));
        var carpet = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack(
            com.zenith.mc.item.ItemRegistry.WHITE_CARPET.id(), 1);
        assertEquals("minecraft:white_carpet", Inv.id(carpet));
        assertEquals(false, Inv.is("minecraft:open_eyeblossom").test(carpet));
    }

    @Test
    void armorSlots() {
        assertEquals(8, ItemIds.armorSlot("minecraft:netherite_boots"));
        assertEquals(8, ItemIds.armorSlot("diamond_boots"));
        assertEquals(7, ItemIds.armorSlot("minecraft:iron_leggings"));
        assertEquals(6, ItemIds.armorSlot("minecraft:elytra"));
        assertEquals(5, ItemIds.armorSlot("minecraft:turtle_helmet"));
        assertEquals(-1, ItemIds.armorSlot("minecraft:diamond_pickaxe"));
    }

    @Test
    void containerSlotMapping() {
        // single chest: 27 own slots; main inventory starts at 27, hotbar at 54
        assertEquals(27, ItemIds.toContainerSlot(9, 27));
        assertEquals(53, ItemIds.toContainerSlot(35, 27));
        assertEquals(54, ItemIds.toContainerSlot(36, 27));
        assertEquals(62, ItemIds.toContainerSlot(44, 27));
        // double chest
        assertEquals(81, ItemIds.toContainerSlot(36, 54));
        // anvil / cartography table: 3 own slots
        assertEquals(3, ItemIds.toContainerSlot(9, 3));
        assertEquals(30, ItemIds.toContainerSlot(36, 3));
        // player inventory itself
        assertEquals(36, ItemIds.toContainerSlot(36, 0));
        for (int top : new int[]{3, 27, 54}) {
            for (int slot = 9; slot <= 44; slot++) {
                assertEquals(slot, ItemIds.toPlayerSlot(ItemIds.toContainerSlot(slot, top), top));
            }
            assertEquals(-1, ItemIds.toPlayerSlot(0, top));
            assertEquals(-1, ItemIds.toPlayerSlot(top + 36, top));
        }
        assertThrows(IllegalArgumentException.class, () -> ItemIds.toContainerSlot(45, 27));
    }

    @Test
    void hotbarIndex() {
        assertEquals(0, ItemIds.hotbarIndex(36));
        assertEquals(8, ItemIds.hotbarIndex(44));
        assertEquals(-1, ItemIds.hotbarIndex(35));
        assertEquals(-1, ItemIds.hotbarIndex(45));
    }
}
