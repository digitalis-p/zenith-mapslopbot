package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LaneHotbarTest {
    private static final String A = "minecraft:white_carpet";
    private static final String B = "minecraft:red_carpet";
    private static final String C = "minecraft:blue_carpet";

    @Test
    void nextUsesComeBeforeDuplicatesAndBusiestGetsTheSpareSlot() {
        List<String> cells = new ArrayList<>(List.of(A, B, C));
        for (int i = 0; i < 100; i++) cells.add(B);
        for (int i = 0; i < 70; i++) cells.add(A);
        List<LaneHotbar.Stack> slots = new ArrayList<>();
        for (int slot = 1; slot <= 4; slot++) slots.add(new LaneHotbar.Stack(slot, null, 0));
        List<LaneHotbar.Move> moves = new ArrayList<>();
        for (LaneHotbar.Move move; (move = LaneHotbar.next(cells, slots, id -> 256)) != null;) {
            moves.add(move);
            slots.set(move.slot() - 1, new LaneHotbar.Stack(move.slot(), move.item(), 64));
            assertTrue(moves.size() <= 4, "layout converges");
        }
        assertEquals(List.of(new LaneHotbar.Move(A, 1), new LaneHotbar.Move(B, 2),
            new LaneHotbar.Move(C, 3), new LaneHotbar.Move(B, 4)), moves);
    }

    @Test
    void evictsFarthestUseAndKeepsExistingUsefulStacks() {
        var slots = List.of(new LaneHotbar.Stack(1, B, 64), new LaneHotbar.Stack(2, C, 64));
        assertEquals(new LaneHotbar.Move(A, 2), LaneHotbar.next(List.of(A, B, C), slots, id -> 64));
        assertNull(LaneHotbar.next(List.of(B, C), slots, id -> 64));
        assertNull(LaneHotbar.next(List.of(A, B), slots, id -> 0), "unavailable material cannot trigger a move");
    }

    @Test
    void restockUsesOnlyRemainingDemandAndDoesNotReserveUnneededDuplicates() {
        var slots = List.of(new LaneHotbar.Stack(1, A, 64), new LaneHotbar.Stack(2, B, 64),
            new LaneHotbar.Stack(3, null, 0));
        assertNull(LaneHotbar.next(List.of(A, B, B), slots, id -> 256));
        assertEquals(new LaneHotbar.Move(B, 3), LaneHotbar.next(java.util.Collections.nCopies(100, B), slots, id -> 64));
        assertNull(LaneHotbar.next(List.of(), slots, id -> 64));
    }

    // ------------------------------------------------------------------------------- layout cycles (2026-10-21)

    /** A model inventory as {@link Hotbar#layoutMove} changes it: the item's first main stack swaps with the slot. */
    private static final class Inventory {
        final String[] item = new String[7];
        final int[] count = new int[7];
        final List<String> mainItem = new ArrayList<>();
        final List<Integer> mainCount = new ArrayList<>();

        List<LaneHotbar.Stack> slots() {
            List<LaneHotbar.Stack> out = new ArrayList<>();
            for (int s = 1; s <= 6; s++) out.add(new LaneHotbar.Stack(s, count[s] > 0 ? item[s] : null, count[s]));
            return out;
        }

        int main(String id) {
            int n = 0;
            for (int i = 0; i < mainItem.size(); i++) if (id.equals(mainItem.get(i))) n += mainCount.get(i);
            return n;
        }

        void apply(LaneHotbar.Move m) {
            int src = mainItem.indexOf(m.item());
            String oldItem = item[m.slot()];
            int oldCount = count[m.slot()];
            item[m.slot()] = mainItem.get(src);
            count[m.slot()] = mainCount.get(src);
            if (oldCount > 0) {
                mainItem.set(src, oldItem);
                mainCount.set(src, oldCount);
            } else {
                mainItem.remove(src);
                mainCount.remove(src);
            }
        }

        String state() {
            StringBuilder b = new StringBuilder();
            for (int s = 1; s <= 6; s++) b.append(item[s]).append('x').append(count[s]).append(' ');
            return b.append("| ").append(mainItem).append(mainCount).toString();
        }
    }

    /** Moves until the layout is ready (or {@code max} moves); with {@code pin}, as PlaceLaneTask pins its moves. */
    private static List<LaneHotbar.Move> layout(List<String> cells, Inventory inv, boolean pin, int max) {
        java.util.Set<Integer> pinned = new java.util.HashSet<>();
        List<LaneHotbar.Move> moves = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            LaneHotbar.Move m = pin ? LaneHotbar.next(cells, inv.slots(), inv::main, pinned::contains)
                : LaneHotbar.next(cells, inv.slots(), inv::main);
            if (m == null) break;
            inv.apply(m);
            pinned.add(m.slot());
            moves.add(m);
        }
        return moves;
    }

    /**
     * Two leftover stacks (1 of A, 2 of B) and a short lane: A wants a second stack and takes B's 64 (B has two), then
     * B has one stack of 2 and wants a second and takes A's 32 back (A has two now), and so on for ever. Each swap
     * changes the hotbar, so the lane watchdog never tripped (live 2026-10-21 00:55, ABD [9,5]: 40 minutes).
     */
    private static Inventory leftovers() {
        Inventory inv = new Inventory();
        String[] items = {B, C, A, C, C, B};
        int[] counts = {64, 10, 1, 32, 10, 2};
        for (int s = 1; s <= 6; s++) {
            inv.item[s] = items[s - 1];
            inv.count[s] = counts[s - 1];
        }
        inv.mainItem.add(A);
        inv.mainCount.add(32);
        return inv;
    }

    private static final List<String> SHORT_LANE = List.of(C, B, C, B, A, C, B, A);

    @Test
    void withoutPinsTheLayoutSwapsTwoStacksForEver() {
        Inventory inv = leftovers();
        String start = inv.state();
        List<LaneHotbar.Move> moves = layout(SHORT_LANE, inv, false, 40);
        assertEquals(40, moves.size(), "never ready: " + moves);
        assertEquals(new LaneHotbar.Move(A, 1), moves.get(0));
        assertEquals(new LaneHotbar.Move(B, 1), moves.get(1));
        assertEquals(start, inv.state(), "back where it started after every second move");
    }

    @Test
    void pinnedSlotsEndTheLayoutAfterOneMovePerSlot() {
        Inventory inv = leftovers();
        List<LaneHotbar.Move> moves = layout(SHORT_LANE, inv, true, 40);
        // A takes B's 64 as before; B's second stack then comes into A's single item (slot 1 is pinned), and it is ready
        assertEquals(List.of(new LaneHotbar.Move(A, 1), new LaneHotbar.Move(B, 3)), moves, inv.state());
        assertNull(LaneHotbar.next(SHORT_LANE, inv.slots(), inv::main, s -> s == 1 || s == 3), "ready");
        assertNull(LaneHotbar.next(SHORT_LANE, inv.slots(), inv::main), "a layout the old plan accepts as well");
        for (String item : List.of(A, B, C)) {
            boolean in = false;
            for (int s = 1; s <= 6; s++) in |= item.equals(inv.item[s]) && inv.count[s] > 0;
            assertTrue(in, item + " in the hotbar: " + inv.state());
        }
    }

    @Test
    void randomHotbarsCycleWithoutPinsAndAlwaysEndWithThem() {
        String[] ids = {A, B, C, "minecraft:gray_carpet", "minecraft:white_candle", "minecraft:stone_pressure_plate"};
        int[] sizes = {1, 2, 3, 10, 20, 32, 50, 64};
        java.util.Random rnd = new java.util.Random(11);
        int cycles = 0;
        int trials = 20_000;
        for (int t = 0; t < trials; t++) {
            int kinds = 2 + rnd.nextInt(ids.length - 1);
            Inventory a = new Inventory();
            Inventory b = new Inventory();
            for (int s = 1; s <= 6; s++) {
                if (rnd.nextInt(8) == 0) continue;
                String id = ids[rnd.nextInt(kinds)];
                int n = sizes[rnd.nextInt(sizes.length)];
                a.item[s] = b.item[s] = id;
                a.count[s] = b.count[s] = n;
            }
            for (int i = rnd.nextInt(6); i > 0; i--) {
                String id = ids[rnd.nextInt(kinds)];
                int n = sizes[rnd.nextInt(sizes.length)];
                a.mainItem.add(id);
                a.mainCount.add(n);
                b.mainItem.add(id);
                b.mainCount.add(n);
            }
            List<String> cells = new ArrayList<>();
            for (int i = 1 + rnd.nextInt(120); i > 0; i--) cells.add(ids[rnd.nextInt(kinds)]);
            if (layout(cells, a, false, 60).size() == 60) cycles++;
            int pinned = layout(cells, b, true, 60).size();
            assertTrue(pinned <= 6, "with pins at most one move per slot: " + pinned + " moves, trial " + t);
        }
        System.out.println("layout cycles without pins: " + cycles + " of " + trials + " random hotbars");
        assertTrue(cycles > 0, "the class the pins remove");
    }

    @Test
    void reservedSlotsAndToolsAreExcludedIncludingUnbreakableTools() {
        for (int slot : new int[]{0, 7, 8}) assertFalse(Hotbar.layoutSlot(slot, null));
        for (int slot = 1; slot <= 6; slot++) assertTrue(Hotbar.layoutSlot(slot, null));
        DataComponents components = new DataComponents(new java.util.HashMap<>());
        components.put(DataComponentTypes.MAX_DAMAGE, 100);
        ItemStack tool = new ItemStack(1, 1, components);
        for (int slot = 1; slot <= 6; slot++) assertFalse(Hotbar.layoutSlot(slot, tool));
        components.put(DataComponentTypes.UNBREAKABLE,
            new org.geysermc.mcprotocollib.protocol.data.game.item.component.Unbreakable(true));
        for (int slot = 1; slot <= 6; slot++) assertFalse(Hotbar.layoutSlot(slot, tool));
    }
}
