package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The material rule: only materials may be deposited or dropped; everything else holds its slots. */
class LeftoversTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final String RED = "minecraft:red_carpet";
    private static final String TERRACOTTA = "minecraft:white_terracotta";
    private static final Set<String> KEEP = Set.of("minecraft:diamond_pickaxe", "minecraft:golden_carrot",
        "minecraft:filled_map", "minecraft:glass_pane");

    /** Withdrawn: red; jobs: white; sorter chests: terracotta and open eyeblossoms. */
    private static Leftovers rule() {
        return new Leftovers(KEEP::contains, List.of(RED, WHITE),
            id -> id.equals(TERRACOTTA) || id.equals("minecraft:open_eyeblossom"));
    }

    @Test
    void materialsAreWithdrawnJobOrSorterItems() {
        Leftovers lo = rule();
        assertTrue(lo.material(WHITE));
        assertTrue(lo.material(RED));
        assertTrue(lo.material(TERRACOTTA));
        assertTrue(lo.material("minecraft:closed_eyeblossom"), "one material with the open ones");
        for (String id : List.of("minecraft:totem_of_undying", "minecraft:diamond_sword", "minecraft:shulker_box",
            "minecraft:nautilus_shell", "minecraft:diamond_pickaxe", "minecraft:filled_map")) {
            assertFalse(lo.material(id), id);
        }
    }

    /**
     * The elytra legs' kit ({@code build.elytraLegs}): withdrawn, held by an indexed chest, listed as junk: never a
     * material, never junk, untouchable (its slots count as taken when a load is planned, never as material room).
     */
    @Test
    void theElytraAndTheRocketsAreNeverMaterialOrJunk() {
        Set<String> kit = Set.of("minecraft:elytra", "minecraft:firework_rocket");
        Leftovers lo = new Leftovers(KEEP::contains, kit, id -> true, kit::contains);
        for (String id : kit) {
            assertFalse(lo.material(id), id);
            assertFalse(lo.isJunk(id), id);
            assertTrue(lo.untouchable(id), id);
        }
        Map<String, Integer> contents = Map.of("minecraft:elytra", 1, "minecraft:firework_rocket", 64, WHITE, 640);
        assertEquals(Map.of(WHITE, 640), lo.junk(contents, Map.of(), id -> 64), "only the material goes");
        assertEquals(2, lo.untouchableSlots(Map.of("minecraft:elytra", 1, "minecraft:firework_rocket", 1, WHITE, 10)));
    }

    @Test
    void valuablesAreNeverMaterialsEvenWhenASorterChestHeldThem() {
        Leftovers lo = new Leftovers(KEEP::contains, List.of("minecraft:totem_of_undying"), id -> true);
        assertFalse(lo.material("minecraft:totem_of_undying"));
        assertFalse(lo.material("minecraft:red_shulker_box"));
        assertFalse(lo.material("minecraft:netherite_chestplate"));
        assertTrue(lo.material("minecraft:cobblestone"));
    }

    @Test
    void junkIsDisposableAndItsSlotsAreNotUntouchableButValuablesNeverAre() {
        Leftovers lo = new Leftovers(KEEP::contains, List.of(RED, WHITE), id -> false,
            id -> id.equals("minecraft:rotten_flesh") || id.equals("minecraft:lead") || id.equals("minecraft:crossbow")
                || id.equals("minecraft:trident") || id.equals("minecraft:golden_carrot"));
        assertTrue(lo.material("minecraft:rotten_flesh"));
        assertTrue(lo.material("minecraft:lead"));
        // a pillager's crossbow listed as junk goes (the owner, 2026-10-15); an enchanted one never (DropItemsTask)
        assertTrue(lo.material("minecraft:crossbow"), "a plain bow or crossbow on the junk list is junk");
        assertTrue(lo.isJunk("minecraft:crossbow"));
        assertFalse(lo.material("minecraft:trident"), "any other valuable, whatever the junk rule says");
        assertFalse(lo.isJunk("minecraft:trident"));
        assertFalse(lo.material("minecraft:golden_carrot"), "a working item (food)");
        assertFalse(lo.material("minecraft:emerald"), "not in the junk rule");
        assertFalse(lo.untouchable("minecraft:rotten_flesh"));
        Map<String, Integer> contents = Map.of(RED, 10, "minecraft:rotten_flesh", 40, "minecraft:lead", 3,
            "minecraft:emerald", 5, "minecraft:totem_of_undying", 1);
        // the slot budget: junk is no untouchable item, its slots are free for the load once it is dropped
        assertEquals(Map.of("minecraft:emerald", 5, "minecraft:totem_of_undying", 1), lo.untouchable(contents));
        assertEquals(Map.of(RED, 10, "minecraft:rotten_flesh", 40, "minecraft:lead", 3),
            lo.junk(contents, Map.of(), id -> 64).entrySet().stream().filter(e -> !e.getKey().equals("x"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        assertEquals(Map.of("minecraft:rotten_flesh", 40, "minecraft:lead", 3),
            lo.junk(contents, Map.of(RED, 10), id -> 64));
        assertEquals(2, lo.untouchableSlots(Map.of("minecraft:rotten_flesh", 1, "minecraft:lead", 1,
            "minecraft:emerald", 1, "minecraft:totem_of_undying", 1)), "emerald + totem only");
    }

    @Test
    void untouchableItemsAreNeitherMaterialsNorWorkingItems() {
        Leftovers lo = rule();
        assertTrue(lo.untouchable("minecraft:totem_of_undying"));
        assertTrue(lo.untouchable("minecraft:nautilus_shell"));
        assertFalse(lo.untouchable(WHITE));
        assertFalse(lo.untouchable("minecraft:golden_carrot"), "a working item, kept as before");
        Map<String, Integer> contents = Map.of(WHITE, 64, "minecraft:golden_carrot", 32, "minecraft:diamond_sword", 1,
            "minecraft:nautilus_shell", 100);
        assertEquals(Map.of("minecraft:diamond_sword", 1, "minecraft:nautilus_shell", 100), lo.untouchable(contents));
        assertEquals(3, lo.untouchableSlots(Map.of(WHITE, 1, "minecraft:golden_carrot", 1, "minecraft:diamond_sword", 1,
            "minecraft:nautilus_shell", 2)), "sword 1 + shell 2 slots");
    }

    @Test
    void junkIsSurplusMaterialInWholeStacks() {
        Leftovers lo = rule();
        Map<String, Integer> contents = Map.of(WHITE, 100, RED, 64, TERRACOTTA, 10, "minecraft:totem_of_undying", 1,
            "minecraft:nautilus_shell", 64, "minecraft:golden_carrot", 32);
        Map<String, Integer> junk = lo.junk(contents, Map.of(WHITE, 90), id -> 64);
        // white: 10 over the need, less than a stack: stays; red and terracotta: not needed, all of it goes
        assertEquals(Map.of(RED, 64, TERRACOTTA, 10), junk);
    }

    @Test
    void describeListsTheOffhandFirst() {
        assertEquals("minecraft:totem_of_undying (offhand), minecraft:diamond_sword, minecraft:nautilus_shell ×3",
            Leftovers.describe(Map.of("minecraft:totem_of_undying", 1),
                new java.util.TreeMap<>(Map.of("minecraft:diamond_sword", 1, "minecraft:nautilus_shell", 3))));
        assertEquals(3, Leftovers.count(Map.of("a", 1), Map.of("b", 1, "c", 2)));
    }
}
