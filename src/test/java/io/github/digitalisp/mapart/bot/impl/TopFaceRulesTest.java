package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopFaceRulesTest {
    private static boolean ok(String state) {
        return TopFaceRules.placeableOnTopFace(BlockSpec.parse(state));
    }

    @Test
    void plainBlocksAndAllowedPropertyValues() {
        assertTrue(ok("minecraft:white_carpet"));
        assertTrue(ok("minecraft:white_terracotta"));
        assertTrue(ok("minecraft:oak_log[axis=y]"));
        assertTrue(ok("minecraft:stone_slab[type=bottom,waterlogged=false]"));
        assertTrue(ok("minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]"));
        assertTrue(ok("minecraft:white_candle[candles=1,lit=false,waterlogged=false]"));
        assertTrue(ok("minecraft:glow_lichen[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]"));
        assertTrue(ok("minecraft:resin_clump[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]"));
        assertTrue(ok("minecraft:grass_block[snowy=false]"));
        assertTrue(ok("minecraft:stone_pressure_plate[powered=false]"));
        assertTrue(ok("minecraft:light_weighted_pressure_plate[power=0]"));
        assertTrue(ok("minecraft:pointed_dripstone[thickness=tip,vertical_direction=up,waterlogged=false]"));
        assertTrue(ok("minecraft:pale_moss_carpet[bottom=true,east=none,north=none,south=none,west=none]"));
    }

    @Test
    void statesAClickOnTheTopFaceCannotProduce() {
        assertFalse(ok("minecraft:oak_log[axis=x]"));
        assertFalse(ok("minecraft:stone_slab[type=top,waterlogged=false]"));
        assertFalse(ok("minecraft:stone_slab[type=double,waterlogged=false]"));
        assertFalse(ok("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]"));
        assertFalse(ok("minecraft:white_candle[candles=2,lit=false,waterlogged=false]"));
        assertFalse(ok("minecraft:white_candle[candles=1,lit=true,waterlogged=false]"));
        assertFalse(ok("minecraft:glow_lichen[down=false,east=false,north=false,south=false,up=true,waterlogged=false,west=false]"));
        assertFalse(ok("minecraft:glow_lichen[down=true,east=true,north=false,south=false,up=false,waterlogged=false,west=false]"));
        assertFalse(ok("minecraft:stone_slab[type=bottom,waterlogged=true]"));
        assertFalse(ok("minecraft:pale_moss_carpet[bottom=true,east=low,north=none,south=none,west=none]"));
        assertFalse(ok("minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"));
        assertFalse(ok("minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"));
    }

    @Test
    void orientationDependentStatesAreRejected() {
        assertFalse(ok("minecraft:decorated_pot[cracked=false,facing=north,waterlogged=false]"));
        assertFalse(ok("minecraft:pink_petals[facing=north,flower_amount=1]"));
        assertFalse(ok("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
        assertFalse(ok("minecraft:oak_sign[rotation=0,waterlogged=false]"));
        assertFalse(ok("minecraft:wheat[age=0]"));
    }

    @Test
    void mapArtCraftStatesOfTheStockedFoliageAndBlocksArePlaceable() {
        // MapArtCraft 1.21.4 palette entries (coloursJSON NBTName/NBTArgs) for the moss sorter's non-carpet stock
        assertTrue(ok("minecraft:pink_petals"));
        assertTrue(ok("minecraft:birch_leaves[persistent=true]"));
        assertTrue(ok("minecraft:glow_lichen[down=true]"));
        assertTrue(ok("minecraft:resin_clump[down=true]"));
        assertTrue(ok("minecraft:mushroom_stem"));
        assertTrue(ok("minecraft:crimson_roots"));
        assertTrue(ok("minecraft:open_eyeblossom"));
        // MapArtCraft's "Water" entry: waterlogged leaves cannot be built on a dry platform
        assertFalse(ok("minecraft:birch_leaves[persistent=true,waterlogged=true]"));
    }

    @Test
    void fullStatesOfStructureBlockSavesWithNeighbourDrivenProperties() {
        // a vanilla save lists every property; these are recomputed by the server, so any value is fine
        assertTrue(ok("minecraft:birch_leaves[distance=3,persistent=true,waterlogged=false]"), "leaf distance");
        assertTrue(ok("minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]"));
        assertTrue(ok("minecraft:mushroom_stem[down=false,east=true,north=false,south=true,up=true,west=true]"),
            "faces touching another stem are false");
        assertTrue(ok("minecraft:red_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]"));
        assertTrue(ok("minecraft:pink_petals[flower_amount=1]"), "one petal per click; facing not given");
        assertTrue(ok("minecraft:tnt[unstable=false]"));
        assertFalse(ok("minecraft:pink_petals[flower_amount=3]"));
        assertTrue(TopFaceRules.equivalent(
            BlockSpec.parse("minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]"),
            BlockSpec.parse("minecraft:mushroom_stem[down=true,east=false,north=true,south=true,up=true,west=false]")));
    }

    @Test
    void problemNamesTheOffendingProperty() {
        assertEquals(null, TopFaceRules.problem(BlockSpec.parse("minecraft:pink_petals")));
        assertEquals("facing=north: facing follows the direction the bot looks, which v1 does not control",
            TopFaceRules.problem(BlockSpec.parse("minecraft:pink_petals[facing=north,flower_amount=1]")));
        assertEquals("flower_amount=2: needs several clicks on the same block; v1 clicks once",
            TopFaceRules.problem(BlockSpec.parse("minecraft:pink_petals[flower_amount=2]")));
        assertEquals("waterlogged=true: needs water; the platform is dry",
            TopFaceRules.problem(BlockSpec.parse("minecraft:birch_leaves[persistent=true,waterlogged=true]")));
        assertEquals("persistent=false: placed leaves are always persistent=true (non-persistent leaves decay)",
            TopFaceRules.problem(BlockSpec.parse("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]")));
        assertEquals("axis=x: a top-face click gives axis=y", TopFaceRules.problem(BlockSpec.parse("minecraft:oak_log[axis=x]")));
        assertEquals("down=false: a top-face click attaches it to the floor (down=true)",
            TopFaceRules.problem(BlockSpec.parse("minecraft:glow_lichen[down=false,up=true]")));
        assertEquals("age=0: a top-face click cannot set age", TopFaceRules.problem(BlockSpec.parse("minecraft:wheat[age=0]")));
    }

    @Test
    void equivalenceIgnoresMissingAndNeighbourDependentProperties() {
        BlockSpec leavesTarget = BlockSpec.parse("minecraft:oak_leaves[persistent=true]");
        assertTrue(TopFaceRules.equivalent(leavesTarget,
            BlockSpec.parse("minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]")));
        assertTrue(TopFaceRules.equivalent(BlockSpec.parse("minecraft:oak_leaves[distance=7,persistent=true]"),
            BlockSpec.parse("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")), "leaf distance");
        assertFalse(TopFaceRules.equivalent(leavesTarget,
            BlockSpec.parse("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]")));
        assertTrue(TopFaceRules.equivalent(
            BlockSpec.parse("minecraft:pale_moss_carpet[bottom=true,east=none,north=none,south=none,west=none]"),
            BlockSpec.parse("minecraft:pale_moss_carpet[bottom=true,east=low,north=none,south=none,west=none]")));
        assertFalse(TopFaceRules.equivalent(BlockSpec.parse("minecraft:oak_log[axis=y]"), BlockSpec.parse("minecraft:oak_log[axis=x]")));
        assertFalse(TopFaceRules.equivalent(BlockSpec.parse("minecraft:white_carpet"), BlockSpec.parse("minecraft:black_carpet")));
        assertTrue(TopFaceRules.equivalent(BlockSpec.AIR, BlockSpec.parse("minecraft:cave_air")));
        assertFalse(TopFaceRules.equivalent(BlockSpec.parse("minecraft:white_carpet"), BlockSpec.AIR));
    }

    @Test
    void equivalenceIgnoresEntityDrivenPropertiesAndTreatsNullAsAir() {
        assertTrue(TopFaceRules.equivalent(BlockSpec.parse("minecraft:stone_pressure_plate[powered=false]"),
            BlockSpec.parse("minecraft:stone_pressure_plate[powered=true]")), "a player standing on a plate");
        assertTrue(TopFaceRules.equivalent(BlockSpec.parse("minecraft:light_weighted_pressure_plate[power=0]"),
            BlockSpec.parse("minecraft:light_weighted_pressure_plate[power=3]")), "items on a weighted plate");
        assertFalse(TopFaceRules.equivalent(BlockSpec.parse("minecraft:stone_pressure_plate[powered=false]"),
            BlockSpec.parse("minecraft:oak_pressure_plate[powered=false]")));
        assertTrue(TopFaceRules.equivalent(null, BlockSpec.AIR));
        assertFalse(TopFaceRules.equivalent(null, BlockSpec.parse("minecraft:white_carpet")));
        assertFalse(TopFaceRules.equivalent(BlockSpec.parse("minecraft:white_carpet"), null));
    }

    /** Placed by airplace in AIRPLACE mode: the state does not depend on the look. */
    private static final List<String> AIRPLACE_STATES = List.of(
        "minecraft:white_carpet",
        "minecraft:moss_carpet",
        "minecraft:stone_pressure_plate",
        "minecraft:heavy_weighted_pressure_plate[power=0]",
        "minecraft:white_terracotta",
        "minecraft:birch_leaves[distance=7,persistent=true]",
        "minecraft:birch_leaves",
        "minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]",
        "minecraft:oak_log[axis=y]",
        "minecraft:stone_slab[type=bottom]",
        "minecraft:snow[layers=1]",
        "minecraft:pale_moss_carpet[bottom=true,east=none,north=none,south=none,west=none]",
        "minecraft:crimson_roots",
        "minecraft:open_eyeblossom",
        "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]");

    /** Placed by LOOK even in AIRPLACE mode: an airplace would follow the look (or a wall next to the cell). */
    private static final List<String> LOOK_STATES = List.of(
        "minecraft:glow_lichen[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]",
        "minecraft:glow_lichen",
        "minecraft:resin_clump[down=true]",
        "minecraft:sculk_vein",
        "minecraft:lantern[hanging=false]",
        "minecraft:soul_lantern",
        "minecraft:pointed_dripstone[thickness=tip,vertical_direction=up]",
        "minecraft:tube_coral_fan",
        "minecraft:dead_brain_coral_fan",
        "minecraft:torch",
        "minecraft:redstone_torch",
        "minecraft:copper_lantern[hanging=false]");

    @Test
    void lookModePlacesEverythingByLook() {
        for (String s : AIRPLACE_STATES) {
            assertEquals(PlaceMethod.LOOK, TopFaceRules.placeMethod(BlockSpec.parse(s), PlaceMode.LOOK), s);
        }
        for (String s : LOOK_STATES) {
            assertEquals(PlaceMethod.LOOK, TopFaceRules.placeMethod(BlockSpec.parse(s), PlaceMode.LOOK), s);
        }
    }

    @Test
    void airplaceModeAirplacesLookIndependentBlocks() {
        for (String s : AIRPLACE_STATES) {
            BlockSpec spec = BlockSpec.parse(s);
            assertEquals(PlaceMethod.AIRPLACE, TopFaceRules.placeMethod(spec, PlaceMode.AIRPLACE), s);
            assertNull(TopFaceRules.airPlaceProblem(spec), s);
        }
    }

    @Test
    void airplaceModeKeepsLookDependentBlocksOnLook() {
        for (String s : LOOK_STATES) {
            BlockSpec spec = BlockSpec.parse(s);
            assertEquals(PlaceMethod.LOOK, TopFaceRules.placeMethod(spec, PlaceMode.AIRPLACE), s);
            String why = TopFaceRules.airPlaceProblem(spec);
            assertNotNull(why, s);
            assertTrue(why.startsWith(ItemIds.path(spec.id()) + ": "), why);
            assertTrue(why.contains("look"), why);
        }
    }

    @Test
    void airPlaceProblemNamesTheReason() {
        assertEquals("glow_lichen: attaches to the first face in look order it can stick to (a wall next to it, not the floor)",
            TopFaceRules.airPlaceProblem(BlockSpec.parse("minecraft:glow_lichen[down=true]")));
        assertEquals("tube_coral_fan: standing or wall/hanging form follows the look",
            TopFaceRules.airPlaceProblem(BlockSpec.parse("minecraft:tube_coral_fan")));
        assertEquals("copper_lantern: hanging follows the look",
            TopFaceRules.airPlaceProblem(BlockSpec.parse("minecraft:copper_lantern[hanging=false]")));
    }

    /**
     * Side-face LOOK clicks (build.lookSideFaces): only blocks whose state comes from the look alone, never from the
     * clicked face (vanilla 1.21.4 getStateForPlacement, checked one by one).
     */
    @Test
    void sideFacePlaceableBlocks() {
        for (String s : List.of(
            "minecraft:pointed_dripstone[thickness=tip,vertical_direction=up,waterlogged=false]", // pitch: nearest vertical
            "minecraft:lantern[hanging=false,waterlogged=false]",       // first vertical direction in look order
            "minecraft:soul_lantern[hanging=false,waterlogged=false]",
            "minecraft:copper_lantern[hanging=false,waterlogged=false]",
            "minecraft:white_candle[candles=1,lit=false,waterlogged=false]", // neither look nor face
            "minecraft:candle[candles=1,lit=false,waterlogged=false]")) {
            assertTrue(TopFaceRules.sideFacePlaceable(BlockSpec.parse(s)), s);
        }
        for (String s : List.of(
            // multiface: the clicked face's opposite comes first, so it sticks to the wall
            "minecraft:glow_lichen[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]",
            "minecraft:resin_clump[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]",
            "minecraft:sculk_vein[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]",
            // StandingAndWallBlockItem: a side click gives the wall form
            "minecraft:torch", "minecraft:soul_torch", "minecraft:redstone_torch[lit=true]",
            "minecraft:tube_coral_fan[waterlogged=false]",
            // the face sets the state (slab half, pillar axis), or no LOOK block at all
            "minecraft:stone_slab[type=bottom,waterlogged=false]", "minecraft:oak_log[axis=y]",
            "minecraft:jack_o_lantern[facing=north]", "minecraft:sea_lantern", "minecraft:candle_cake[lit=false]")) {
            assertFalse(TopFaceRules.sideFacePlaceable(BlockSpec.parse(s)), s);
        }
    }

    /** Full blocks that act on a right-click with a block in hand are never clicked on a side face. */
    @Test
    void sideClickableNeighbours() {
        assertTrue(TopFaceRules.sideClickable("minecraft:gray_terracotta"));
        assertTrue(TopFaceRules.sideClickable("cyan_terracotta"));
        assertTrue(TopFaceRules.sideClickable("minecraft:moss_block"));
        for (String id : List.of("minecraft:note_block", "minecraft:crafting_table", "minecraft:cartography_table",
            "minecraft:smithing_table", "minecraft:loom", "minecraft:respawn_anchor", "minecraft:redstone_ore",
            "minecraft:tnt")) {
            assertFalse(TopFaceRules.sideClickable(id), id);
        }
    }
}
