package io.github.digitalisp.mapart.bot.impl;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LanePrefetchTest {
    private static final String A = "minecraft:white_carpet";
    private static final String B = "minecraft:red_carpet";
    private static final String C = "minecraft:blue_carpet";

    @Test
    void refillsBeforeExhaustionWithoutMovingOrReplacingTheHeldStack() {
        var slots = List.of(new LanePrefetch.Stack(1, A, 3, false),
            new LanePrefetch.Stack(2, null, 0, true));
        assertEquals(new LanePrefetch.Move(A, 2), LanePrefetch.next(Collections.nCopies(10, A), slots, 1, id -> 64, false));
        assertNull(LanePrefetch.next(Collections.nCopies(3, A), slots, 1, id -> 64, true));
        assertNull(LanePrefetch.next(Collections.nCopies(10, A), slots, 1, id -> 0, true));
        assertNull(LanePrefetch.next(List.of(A), List.of(new LanePrefetch.Stack(1, B, 64, true)), 1, id -> 64, true));
    }

    @Test
    void shortageUsesCombinedAvailableCountsAndFortyCellHorizon() {
        var slots = List.of(new LanePrefetch.Stack(1, A, 20, false), new LanePrefetch.Stack(2, A, 20, false),
            new LanePrefetch.Stack(3, null, 0, true));
        assertNull(LanePrefetch.next(Collections.nCopies(100, A), slots, 1, id -> 64, true));
        var cells = new ArrayList<>(Collections.nCopies(40, A));
        cells.add(B);
        assertNull(LanePrefetch.next(cells, slots, 1, id -> 64, true), "outside lookahead");
        cells.set(39, B);
        assertEquals(new LanePrefetch.Move(B, 3), LanePrefetch.next(cells, slots, 1, id -> 64, true));
    }

    @Test
    void futureEvictionUsesBeladyAndHasAnIndependentKillSwitch() {
        var cells = new ArrayList<>(Collections.nCopies(40, A));
        cells.add(B);
        cells.add(C);
        var slots = List.of(new LanePrefetch.Stack(1, A, 1, false), new LanePrefetch.Stack(2, B, 64, true),
            new LanePrefetch.Stack(3, C, 64, true));
        assertNull(LanePrefetch.next(cells, slots, 1, id -> 64, false));
        assertEquals(new LanePrefetch.Move(A, 3), LanePrefetch.next(cells, slots, 1, id -> 64, true));
        cells.add(1, C); // nearer use, even though not reachable yet
        assertEquals(new LanePrefetch.Move(A, 2), LanePrefetch.next(cells, slots, 1, id -> 64, true));
    }

    @Test
    void neverEvictsHorizonOrProtectedStacksOrOverwritesTheSameMaterial() {
        var slots = List.of(new LanePrefetch.Stack(1, A, 1, false), new LanePrefetch.Stack(2, B, 64, true),
            new LanePrefetch.Stack(3, C, 64, false), new LanePrefetch.Stack(4, A, 0, true));
        var cells = new ArrayList<>(Collections.nCopies(10, A));
        cells.add(B);
        assertNull(LanePrefetch.next(cells, slots, 1, id -> 64, true));
    }

    @Test
    void speculativeTargetsKeepFoodToolsTotemsAndMapsProtected() {
        assertFalse(Hotbar.prefetchSlot(0, null));
        assertTrue(Hotbar.prefetchSlot(8, null));
        for (String id : List.of("golden_carrot", "diamond_pickaxe", "diamond_axe", "totem_of_undying", "map")) {
            var item = ItemRegistry.REGISTRY.get(id);
            assertNotNull(item, id);
            assertFalse(Hotbar.prefetchSlot(2, new ItemStack(item.id(), 1)), id);
        }
    }
}
