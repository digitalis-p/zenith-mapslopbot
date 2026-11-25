package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hotbar slots for a lane's materials (gate B, 2026-10-11: 110 inventory moves in 1280 placements with 11 materials,
 * because food sat in 0, the pickaxe in 6, maps in 7 and the axe in 8, and materials only went to 1-6). A lane now
 * moves its materials into every slot but the food slot, a tool or map there first (it goes to the main inventory in
 * the same swap click), the selected material last.
 */
class HotbarTargetTest {
    private static final String FOOD = "minecraft:golden_carrot";
    private static final String PICK = "minecraft:diamond_pickaxe";
    private static final String AXE = "minecraft:diamond_axe";
    private static final String MAP = "minecraft:map";

    /** Hotbar 0..8 and the selected index; a move swaps a material in (the old stack goes to the main inventory). */
    private static final class Bar {
        final String[] slot = new String[9];
        final Set<String> lane;
        int held;
        int start;
        int moves;

        Bar(Set<String> lane) {
            this.lane = lane;
        }

        Hotbar.Holds holds(int i) {
            String id = slot[i];
            if (id == null) return Hotbar.Holds.EMPTY;
            if (!lane.contains(id)) return Hotbar.Holds.OTHER;
            return i == held ? Hotbar.Holds.HELD : Hotbar.Holds.KEPT;
        }

        int indexOf(String id) {
            for (int i = 0; i < 9; i++) if (id.equals(slot[i])) return i;
            return -1;
        }

        /** Like Hotbar.ensureHeld + pickTarget with the lane's material slots. */
        void hold(String id) {
            int i = indexOf(id);
            if (i < 0) {
                int[] targets = Hotbar.MATERIAL_SLOTS;
                i = Hotbar.chooseTarget(targets, this::holds, start);
                for (int k = 0; k < targets.length; k++) if (targets[k] == i) start = k + 1;
                slot[i] = id;
                moves++;
            }
            held = i;
        }
    }

    private static Bar gateBLayout(Set<String> lane) {
        Bar b = new Bar(lane);
        b.slot[0] = FOOD;
        b.slot[6] = PICK;
        b.slot[7] = MAP;
        b.slot[8] = AXE;
        return b;
    }

    private static List<String> materials(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("minecraft:mat_" + i);
        return out;
    }

    @Test
    void laneSlotsAreEveryHotbarSlotButTheFoodSlot() {
        assertEquals(8, Hotbar.MATERIAL_SLOTS.length);
        assertFalse(Arrays.stream(Hotbar.MATERIAL_SLOTS).anyMatch(s -> s == Hotbar.FOOD_SLOT));
        assertTrue(Arrays.stream(Hotbar.MATERIAL_SLOTS).allMatch(s -> s >= 0 && s <= 8));
    }

    @Test
    void emptySlotFirstThenToolsAndMapsThenMaterials() {
        List<String> mats = materials(11);
        Bar b = gateBLayout(Set.copyOf(mats));
        for (int i = 1; i <= 5; i++) b.slot[i] = mats.get(i - 1);
        b.slot[3] = null;
        assertEquals(3, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0), "the empty slot");
        b.slot[3] = mats.get(2);
        assertEquals(6, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0), "the pickaxe moves out first");
        b.slot[6] = mats.get(5);
        assertEquals(7, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0), "then the maps");
        b.slot[7] = mats.get(6);
        assertEquals(8, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0), "then the axe");
    }

    @Test
    void foodInTheFoodSlotStays() {
        List<String> mats = materials(12);
        Bar b = gateBLayout(Set.copyOf(mats));
        for (String m : mats) b.hold(m);
        assertEquals(FOOD, b.slot[0]);
    }

    @Test
    void selectedMaterialIsReplacedLastAndTheRestRoundRobin() {
        List<String> mats = materials(8);
        Bar b = new Bar(Set.copyOf(mats));
        for (int i = 1; i <= 8; i++) b.slot[i] = mats.get(i - 1);
        b.held = 4;
        assertEquals(5, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 3), "position 3 is slot 4, held");
        assertEquals(1, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 8));
        int[] only = {4};
        assertEquals(4, Hotbar.chooseTarget(only, b::holds, 0), "a single target is always taken");
    }

    @Test
    void eightMaterialsMoveOnceEachWithToolsAndMapsInTheHotbar() {
        List<String> mats = materials(8);
        Bar b = gateBLayout(Set.copyOf(mats));
        Random rnd = new Random(7);
        for (int i = 0; i < 1280; i++) b.hold(mats.get(rnd.nextInt(mats.size())));
        assertEquals(8, b.moves, "each material moves into the hotbar once; tools and maps went to the main inventory");
        assertEquals(FOOD, b.slot[0]);
    }

    /** Lane-like order: of the next few targets in reach, one whose item is in the hotbar goes first (PlacePipeline). */
    private static int laneMoves(Bar b, List<String> mats, long seed) {
        Random rnd = new Random(seed);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 1280; i++) order.add(mats.get(rnd.nextInt(mats.size())));
        while (!order.isEmpty()) {
            int pick = 0;
            for (int k = 0; k < Math.min(6, order.size()); k++) {
                if (b.indexOf(order.get(k)) >= 0) {
                    pick = k;
                    break;
                }
            }
            b.hold(order.remove(pick));
        }
        return b.moves;
    }

    @Test
    void beladyReplacesTheKeptStackNeededLast() {
        List<String> mats = materials(8);
        Bar b = new Bar(Set.copyOf(mats));
        for (int i = 1; i <= 8; i++) b.slot[i] = mats.get(i - 1);
        b.held = 4;
        int[] next = {0, 5, 9, 2, 99, 7, Integer.MAX_VALUE, 3, 40}; // slot -> when its stack is needed next
        assertEquals(6, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0, s -> next[s]), "not needed again");
        next[6] = 1;
        assertEquals(8, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0, s -> next[s]),
            "needed last; the held slot (99) stays");
        next[2] = 40;
        assertEquals(2, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0, s -> next[s]),
            "a tie (slots 2 and 8): the first in round robin order");
        assertEquals(8, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 2, s -> next[s]), "from another start");
        b.slot[3] = null;
        assertEquals(3, Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, 0, s -> next[s]), "an empty slot first");
    }

    @Test
    void beladyNeedsFewerMovesThanRoundRobin() {
        List<String> mats = materials(14);
        int robin = 0;
        int belady = 0;
        for (long seed = 1; seed <= 5; seed++) {
            Random rnd = new Random(seed);
            List<String> order = new ArrayList<>();
            for (int i = 0; i < 1280; i++) order.add(mats.get(rnd.nextInt(mats.size())));
            for (boolean far : new boolean[]{false, true}) {
                Bar b = new Bar(Set.copyOf(mats));
                for (int i = 0; i < order.size(); i++) {
                    String id = order.get(i);
                    if (b.indexOf(id) < 0) {
                        int from = i;
                        java.util.function.IntUnaryOperator next = s -> {
                            int k = order.subList(from, order.size()).indexOf(b.slot[s]);
                            return k < 0 ? Integer.MAX_VALUE : k;
                        };
                        int t = Hotbar.chooseTarget(Hotbar.MATERIAL_SLOTS, b::holds, b.start, far ? next : null);
                        for (int k = 0; k < Hotbar.MATERIAL_SLOTS.length; k++) {
                            if (Hotbar.MATERIAL_SLOTS[k] == t) b.start = k + 1;
                        }
                        b.slot[t] = id;
                        b.moves++;
                        b.held = t;
                    } else {
                        b.held = b.indexOf(id);
                    }
                }
                if (far) belady += b.moves;
                else robin += b.moves;
            }
        }
        System.out.println("14 materials, 8 slots, 5 x 1280 cells: round robin " + robin + " moves, Belady " + belady);
        assertTrue(belady * 10 <= robin * 8, "at least a fifth fewer moves: " + belady + " vs " + robin);
    }

    @Test
    void elevenMaterialsNeedFewerMovesThanWithSixSlots() {
        List<String> mats = materials(11);
        int now = laneMoves(gateBLayout(Set.copyOf(mats)), mats, 11);
        // the old layout: materials only in 1-6 (round robin), so 6 slots for 11 materials
        int[] old = {1, 2, 3, 4, 5, 6};
        Bar before = gateBLayout(Set.of());
        int moves = 0;
        int rr = 0;
        Random rnd = new Random(11);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 1280; i++) order.add(mats.get(rnd.nextInt(mats.size())));
        while (!order.isEmpty()) {
            int pick = 0;
            for (int k = 0; k < Math.min(6, order.size()); k++) {
                if (before.indexOf(order.get(k)) >= 0) {
                    pick = k;
                    break;
                }
            }
            String id = order.remove(pick);
            if (before.indexOf(id) >= 0) continue;
            int t = -1;
            for (int s : old) if (before.slot[s] == null) { t = s; break; }
            if (t < 0) t = old[Math.floorMod(rr++, old.length)];
            before.slot[t] = id;
            moves++;
        }
        assertTrue(now < moves, "8 slots: " + now + " moves, 6 slots: " + moves);
        assertTrue(now * 10 <= moves * 8, "at least a fifth fewer moves: " + now + " vs " + moves);
    }
}
