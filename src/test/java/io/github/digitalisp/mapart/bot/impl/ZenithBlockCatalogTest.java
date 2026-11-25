package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against ZenithProxy 3.7.2's generated 1.21.4 registries (static data from the jar; Globals is never
 * touched by these code paths).
 */
class ZenithBlockCatalogTest {
    private final ZenithBlockCatalog catalog = new ZenithBlockCatalog();

    /** Side-face LOOK clicks: full cubes without a block entity that do not act on the click (ZenithProxy's data). */
    @Test
    void sideClickableStates() {
        for (String ok : java.util.List.of("minecraft:gray_terracotta", "minecraft:cyan_terracotta", "minecraft:moss_block",
            "minecraft:white_wool", "minecraft:raw_iron_block")) {
            assertTrue(BlockStates.sideClickable(BlockStates.stateId(BlockSpec.parse(ok))), ok);
        }
        for (String no : java.util.List.of("minecraft:air", "minecraft:chest[facing=north,type=single,waterlogged=false]",
            "minecraft:barrel[facing=up,open=false]", "minecraft:decorated_pot",
            "minecraft:note_block[instrument=harp,note=0,powered=false]", "minecraft:crafting_table",
            "minecraft:pointed_dripstone[thickness=tip,vertical_direction=up,waterlogged=false]",
            "minecraft:white_carpet", "minecraft:stone_slab[type=bottom,waterlogged=false]", "minecraft:short_grass")) {
            assertFalse(BlockStates.sideClickable(BlockStates.stateId(BlockSpec.parse(no))), no);
        }
    }

    @Test
    void registryLookups() {
        assertTrue(catalog.isKnownBlock("minecraft:white_carpet"));
        assertTrue(catalog.isKnownBlock("white_carpet"));
        assertTrue(catalog.isKnownBlock("minecraft:pale_moss_carpet"));
        assertFalse(catalog.isKnownBlock("minecraft:not_a_block"));
        assertTrue(catalog.isKnownItem("minecraft:filled_map"));
        assertFalse(catalog.isKnownItem("minecraft:water"));
    }

    @Test
    void placementItems() {
        assertEquals(Optional.of("minecraft:white_carpet"), catalog.placementItem(BlockSpec.of("white_carpet")));
        assertEquals(Optional.of("minecraft:oak_log"), catalog.placementItem(BlockSpec.parse("minecraft:oak_log[axis=y]")));
        assertEquals(Optional.of("minecraft:redstone"), catalog.placementItem(BlockSpec.of("minecraft:redstone_wire")));
        assertEquals(Optional.empty(), catalog.placementItem(BlockSpec.of("minecraft:water")));
        assertEquals(Optional.empty(), catalog.placementItem(BlockSpec.AIR));
        // one material: MapArtCraft emits closed_eyeblossom, the moss sorter stocks open_eyeblossom
        assertEquals(Optional.of("minecraft:open_eyeblossom"), catalog.placementItem(BlockSpec.of("minecraft:closed_eyeblossom")));
        assertEquals(Optional.of("minecraft:open_eyeblossom"), catalog.placementItem(BlockSpec.of("minecraft:open_eyeblossom")));
    }

    @Test
    void eyeblossomsMatchEachOtherButNotTheirMapColour() {
        BlockSpec open = BlockSpec.of("minecraft:open_eyeblossom");
        BlockSpec closed = BlockSpec.of("minecraft:closed_eyeblossom");
        assertTrue(catalog.matches(open, closed));
        assertTrue(catalog.matches(closed, open));
        assertFalse(catalog.matches(open, BlockSpec.of("minecraft:poppy")));
        // 1.21.4: open = COLOR_ORANGE (15, as orange carpet), closed = TERRACOTTA_GREEN (49): the map pixel
        // depends on the time of day when the map is filled
        assertEquals(15, catalog.mapColorId(open));
        assertEquals(49, catalog.mapColorId(closed));
    }

    /** Live 2026-11-14: bamboo_block[axis=x] fills plant (7), not the yellow (18) of the upright block. */
    @Test
    void aPillarOnItsSideHasItsSideColour() {
        assertEquals(18, catalog.mapColorId(BlockSpec.parse("minecraft:bamboo_block")));
        assertEquals(18, catalog.mapColorId(BlockSpec.parse("minecraft:bamboo_block[axis=y]")));
        assertEquals(7, catalog.mapColorId(BlockSpec.parse("minecraft:bamboo_block[axis=x]")));
        assertEquals(7, catalog.mapColorId(BlockSpec.parse("minecraft:bamboo_block[axis=z]")));
        assertEquals(34, catalog.mapColorId(BlockSpec.parse("minecraft:oak_log[axis=x]")));
        assertEquals(13, catalog.mapColorId(BlockSpec.parse("minecraft:oak_log[axis=y]")));
        int stripped = catalog.mapColorId(BlockSpec.parse("minecraft:stripped_oak_log"));
        assertEquals(stripped, catalog.mapColorId(BlockSpec.parse("minecraft:stripped_oak_log[axis=x]")));
    }

    @Test
    void mapColours() {
        int white = catalog.mapColorId(BlockSpec.of("minecraft:white_carpet"));
        assertEquals(8, white, "white wool/carpet use MapColor.SNOW (8)");
        assertEquals(white, catalog.mapColorId(BlockSpec.of("minecraft:white_wool")));
        assertTrue(catalog.mapColorId(BlockSpec.of("minecraft:black_terracotta")) > 0);
        assertEquals(0, catalog.mapColorId(BlockSpec.AIR));
        assertEquals(0, catalog.mapColorId(BlockSpec.of("minecraft:glass")), "glass is invisible on maps");
    }

    @Test
    void topFacePlacement() {
        assertTrue(catalog.placeableOnTopFace(BlockSpec.of("minecraft:white_carpet")));
        assertTrue(catalog.placeableOnTopFace(BlockSpec.parse("minecraft:oak_log[axis=y]")));
        assertFalse(catalog.placeableOnTopFace(BlockSpec.parse("minecraft:oak_log[axis=x]")));
        assertTrue(catalog.placeableOnTopFace(BlockSpec.parse("minecraft:smooth_stone_slab[type=bottom,waterlogged=false]")));
        assertFalse(catalog.placeableOnTopFace(BlockSpec.parse("minecraft:smooth_stone_slab[type=top,waterlogged=false]")));
        assertTrue(catalog.placeableOnTopFace(BlockSpec.parse(
            "minecraft:glow_lichen[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]")));
        assertFalse(catalog.placeableOnTopFace(BlockSpec.parse("minecraft:white_carpet[foo=bar]")), "unknown property");
        assertFalse(catalog.placeableOnTopFace(BlockSpec.of("minecraft:water")), "no item");
        assertFalse(catalog.placeableOnTopFace(BlockSpec.AIR));
    }

    @Test
    void gravityAndStacks() {
        assertTrue(catalog.isGravityBlock("minecraft:sand"));
        assertTrue(catalog.isGravityBlock("minecraft:white_concrete_powder"));
        assertFalse(catalog.isGravityBlock("minecraft:white_carpet"));
        assertEquals(64, catalog.maxStackSize("minecraft:white_carpet"));
        assertEquals(1, catalog.maxStackSize("minecraft:diamond_pickaxe"));
        assertEquals(16, catalog.maxStackSize("minecraft:oak_sign"));
    }

    @Test
    void stateIdsRoundTrip() {
        int log = BlockStates.stateId(BlockSpec.parse("minecraft:oak_log[axis=z]"));
        assertTrue(log > 0);
        int logY = BlockStates.stateId(BlockSpec.parse("minecraft:oak_log[axis=y]"));
        assertTrue(logY > 0 && logY != log);
        assertEquals(-1, BlockStates.stateId(BlockSpec.parse("minecraft:oak_log[axis=w]")));
        assertEquals(-1, BlockStates.stateId(BlockSpec.of("minecraft:nope")));
    }
}
