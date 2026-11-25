package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.impl.Valuables;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NukeDrops}: what breaking an art block gives back instead of the block. */
class NukeDropsTest {
    @Test
    void clayGivesClayBallsAndAPotBricks() {
        assertEquals(Set.of("minecraft:clay_ball"), NukeDrops.byProducts("minecraft:clay"));
        assertEquals(Set.of("minecraft:clay_ball"), NukeDrops.byProducts("clay"), "namespace optional");
        assertEquals(Set.of("minecraft:brick"), NukeDrops.byProducts("minecraft:decorated_pot"));
        assertEquals(Set.of("minecraft:clay"), NukeDrops.sources("minecraft:clay_ball"));
        assertEquals(Set.of("minecraft:decorated_pot"), NukeDrops.sources("brick"));
    }

    @Test
    void blocksThatDropThemselvesHaveNone() {
        for (String b : new String[] {"minecraft:white_carpet", "minecraft:raw_iron_block", "minecraft:pointed_dripstone",
            "minecraft:cobbled_deepslate", "minecraft:crimson_hyphae", "minecraft:white_candle", null})
            assertTrue(NukeDrops.byProducts(b).isEmpty(), String.valueOf(b));
        assertTrue(NukeDrops.sources("minecraft:white_carpet").isEmpty());
        assertTrue(NukeDrops.sources(null).isEmpty());
    }

    @Test
    void onlyTheDropsOfAMaterialCount() {
        assertTrue(NukeDrops.fromMaterial("minecraft:clay_ball", "minecraft:clay"::equals));
        assertFalse(NukeDrops.fromMaterial("minecraft:clay_ball", "minecraft:decorated_pot"::equals),
            "clay is no material here: the clay balls are the owner's");
        assertFalse(NukeDrops.fromMaterial("minecraft:diamond", id -> true), "no block of the table drops it");
        assertTrue(NukeDrops.fromMaterial("minecraft:stick", "minecraft:birch_leaves"::equals));
        assertTrue(NukeDrops.fromMaterial("minecraft:stick", "minecraft:dead_bush"::equals));
    }

    @Test
    void noDropIsAValuableOrAPlacedArtBlock() {
        for (String b : new String[] {"clay", "decorated_pot", "glowstone", "melon", "bookshelf", "snow_block", "snow",
            "sea_lantern", "gravel", "cobweb", "dead_bush", "short_grass", "fern", "oak_leaves", "dark_oak_leaves",
            "birch_leaves", "cherry_leaves", "pale_oak_leaves", "mangrove_leaves", "azalea_leaves"}) {
            Set<String> drops = NukeDrops.byProducts(b);
            assertFalse(drops.isEmpty(), b);
            for (String d : drops) {
                assertFalse(Valuables.isValuable(d), d);
                assertFalse(d.equals("minecraft:" + b), b + " drops itself");
            }
        }
    }
}
