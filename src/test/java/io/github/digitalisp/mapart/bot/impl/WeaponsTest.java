package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link Weapons#pick}: a sword, then an axe, the best material, never one at or below the durability floor. */
class WeaponsTest {
    private static Weapons.Item i(int slot, String id, int remaining) {
        return new Weapons.Item(slot, "minecraft:" + id, remaining, true);
    }

    @Test
    void swordBeforeAxeBestMaterialFirst() {
        assertEquals(12, Weapons.pick(List.of(i(10, "netherite_axe", 2000), i(12, "iron_sword", 200),
            i(13, "diamond_pickaxe", 1500)), 20));
        assertEquals(11, Weapons.pick(List.of(i(10, "iron_sword", 200), i(11, "diamond_sword", 1500)), 20));
        assertEquals(10, Weapons.pick(List.of(i(10, "diamond_axe", 1500), i(13, "diamond_pickaxe", 1500)), 20),
            "an axe, not a pickaxe");
    }

    @Test
    void neverAWornWeaponBareHandInstead() {
        assertEquals(10, Weapons.pick(List.of(i(10, "diamond_axe", 900), i(11, "netherite_sword", 20)), 20),
            "the sword is at the floor");
        assertEquals(-1, Weapons.pick(List.of(i(11, "netherite_sword", 15), i(13, "diamond_pickaxe", 1500)), 20));
        assertEquals(-1, Weapons.pick(List.of(), 20));
        assertEquals(14, Weapons.pick(List.of(new Weapons.Item(14, "minecraft:stone_sword", Integer.MAX_VALUE, false)), 20),
            "an unbreakable one is fine");
    }

    @Test
    void cooldownTicksByWeapon() {
        assertEquals(14, Weapons.cooldownTicks("minecraft:diamond_sword"), "1.6 per second: 12.5 ticks, one more");
        assertEquals(21, Weapons.cooldownTicks("minecraft:diamond_axe"));
        assertEquals(24, Weapons.cooldownTicks("minecraft:iron_axe"));
        assertEquals(26, Weapons.cooldownTicks("minecraft:stone_axe"));
        assertEquals(6, Weapons.cooldownTicks(null), "a bare hand");
    }
}
