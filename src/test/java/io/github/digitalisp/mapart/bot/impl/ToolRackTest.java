package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tools resident in the hotbar during a nuke. Live bug (10x test, 2026-10-11): a mixed nuke (terracotta, mushroom stem,
 * carpets) made about 25 hotbar swaps in 1.6 s, because every tool went to hotbar slot 8 and the pickaxe and the axe
 * were swapped through it for every change of block kind; each swap is an inventory click (a Grim risk, and slow).
 */
class ToolRackTest {
    private static final String PICK = "minecraft:diamond_pickaxe";
    private static final String AXE = "minecraft:diamond_axe";
    private static final String SHOVEL = "minecraft:diamond_shovel";
    private static final String FOOD = "minecraft:golden_carrot";
    private static final double REACH = 4.3;

    /** Player inventory slots 9..44 (hotbar 36..44) and the selected hotbar index; clicks and selects counted. */
    private static final class Inv {
        final String[] slot = new String[46];
        int held;
        int clicks;
        int selects;

        ToolRack.Holds holds(int hotbarIndex) {
            String id = slot[ItemIds.HOTBAR_START + hotbarIndex];
            if (id == null) return ToolRack.Holds.EMPTY;
            if (InventoryView.toolKind(id) != null) return ToolRack.Holds.TOOL;
            if (id.equals(FOOD)) return ToolRack.Holds.KEEP;
            return ToolRack.Holds.OTHER;
        }

        /** One inventory click: swap a main-inventory stack with a hotbar slot. */
        void move(int from, int hotbarIndex) {
            int to = ItemIds.HOTBAR_START + hotbarIndex;
            String t = slot[to];
            slot[to] = slot[from];
            slot[from] = t;
            clicks++;
        }

        void select(int hotbarIndex) {
            if (hotbarIndex != held) selects++;
            held = hotbarIndex;
        }

        int find(String id) {
            for (int i = ItemIds.HOTBAR_START; i <= ItemIds.HOTBAR_END; i++) if (id.equals(slot[i])) return i;
            for (int i = ItemIds.MAIN_START; i < ItemIds.HOTBAR_START; i++) if (id.equals(slot[i])) return i;
            return -1;
        }

        String heldGroup() {
            String k = InventoryView.toolKind(slot[ItemIds.HOTBAR_START + held]);
            return k == null ? ToolRack.HAND : k;
        }

        /** BreakBlocksTask's hand for blocks broken without a tool: the held slot if it holds no tool, else another. */
        int bareHand() {
            if (holds(held) != ToolRack.Holds.TOOL) return held;
            for (int i = 0; i < 9; i++) if (holds(i) == ToolRack.Holds.EMPTY) return i;
            for (int i = 0; i < 9; i++) if (holds(i) != ToolRack.Holds.TOOL) return i;
            return -1;
        }
    }

    /** Hotbar: food, five materials, the empty map; slot 8 empty. Tools in the main inventory, as after a restock. */
    private static Inv inventory() {
        Inv inv = new Inv();
        inv.slot[36] = FOOD;
        for (int i = 1; i <= 5; i++) inv.slot[36 + i] = "minecraft:white_terracotta";
        inv.slot[42] = "minecraft:mushroom_stem";
        inv.slot[43] = "minecraft:map";
        inv.slot[20] = PICK;
        inv.slot[27] = AXE;
        inv.slot[31] = SHOVEL;
        inv.held = 7;
        return inv;
    }

    /** A mixed nuke: terracotta (pickaxe), mushroom stem (axe), mud (shovel), carpet (hand), shuffled over 12×10. */
    private static Map<Pos, String> mixedNuke(long seed) {
        Random r = new Random(seed);
        String[] groups = {"pickaxe", "pickaxe", "axe", "shovel", ToolRack.HAND};
        Map<Pos, String> m = new HashMap<>();
        for (int x = 0; x < 12; x++) for (int z = 0; z < 10; z++) m.put(new Pos(x, 64, z), groups[r.nextInt(groups.length)]);
        return m;
    }

    private static final Map<String, String> TOOL_OF = Map.of("pickaxe", PICK, "axe", AXE, "shovel", SHOVEL);

    /**
     * Breaks every target the way BreakBlocksTask does: arrange once, then per block the next target, then the item
     * for it (select, or a move from the main inventory into a free rack slot). {@code rack} false = the old
     * behaviour: no arrangement, every tool to slot 8, nearest first. {@code group} false: nearest first.
     */
    private static Inv nuke(Map<Pos, String> targets, boolean rack) {
        return nuke(targets, rack, rack);
    }

    private static Inv nuke(Map<Pos, String> targets, boolean rack, boolean group) {
        Inv inv = inventory();
        if (rack) {
            List<Integer> wanted = new ArrayList<>();
            for (String g : Set.copyOf(targets.values())) if (TOOL_OF.containsKey(g)) wanted.add(inv.find(TOOL_OF.get(g)));
            for (ToolRack.Move m : ToolRack.arrange(wanted, inv::holds)) inv.move(m.from(), m.toHotbar());
        }
        List<Pos> remaining = new ArrayList<>(targets.keySet());
        remaining.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        double[] eye = {0.5, 65.62, -1.5};
        while (!remaining.isEmpty()) {
            Pos next = ToolRack.next(remaining, p -> p.distSq(eye[0], eye[1], eye[2]), targets::get,
                group ? inv.heldGroup() : null, REACH);
            if (Math.sqrt(next.distSq(eye[0], eye[1], eye[2])) > REACH) {
                eye[0] = next.x() + 0.5; // walk next to it
                eye[2] = next.z() - 1.5;
            }
            String g = targets.get(next);
            if (g.equals(ToolRack.HAND)) {
                inv.select(inv.bareHand());
            } else {
                int s = inv.find(TOOL_OF.get(g));
                int h = ItemIds.hotbarIndex(s);
                if (h < 0) {
                    int t = rack ? ToolRack.target(inv::holds, Set.of()) : 8;
                    inv.move(s, t < 0 ? 8 : t);
                    h = t < 0 ? 8 : t;
                }
                inv.select(h);
            }
            assertEquals(g.equals(ToolRack.HAND) ? ToolRack.HAND : g, inv.heldGroup(), "holding the right item");
            remaining.remove(next);
        }
        return inv;
    }

    @Test
    void mixedNukeClicksAtMostOncePerTool() {
        int grouped = 0;
        int nearest = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Map<Pos, String> targets = mixedNuke(seed);
            Inv now = nuke(targets, true);
            assertTrue(now.clicks <= 3, "seed " + seed + ": " + now.clicks + " inventory clicks for 3 tools");
            Inv before = nuke(targets, false);
            assertTrue(before.clicks > 10, "the old slot-8 swapping (" + before.clicks + " clicks) is what this fixes");
            grouped += now.selects;
            nearest += nuke(targets, true, false).selects;
        }
        assertTrue(grouped < nearest * 0.8, "grouping by tool within reach saves selects: " + grouped + " vs " + nearest);
    }

    @Test
    void arrangeKeepsToolsInTheHotbarAndFillsEmptySlotsFirst() {
        Inv inv = inventory();
        List<ToolRack.Move> moves = ToolRack.arrange(List.of(20, 27, 31), inv::holds);
        assertEquals(List.of(new ToolRack.Move(20, 8), new ToolRack.Move(27, 7), new ToolRack.Move(31, 6)), moves,
            "slot 8 (empty) first, then 7 (the map) and 6 (a material); never 0 (food)");

        inv.slot[44] = PICK; // a pickaxe already in the hotbar stays where it is
        inv.slot[20] = null;
        inv.slot[38] = null; // hotbar 2 empty
        moves = ToolRack.arrange(List.of(44, 27), inv::holds);
        assertEquals(List.of(new ToolRack.Move(27, 2)), moves, "the empty slot before one that holds something");
        assertTrue(ToolRack.arrange(List.of(44), inv::holds).isEmpty(), "nothing to move");
    }

    @Test
    void neverMovesOneToolOutForAnother() {
        Inv inv = inventory();
        for (int i = 1; i <= 8; i++) inv.slot[36 + i] = i % 2 == 0 ? PICK : SHOVEL;
        assertEquals(-1, ToolRack.target(inv::holds, Set.of()), "every rack slot holds a tool");
        assertTrue(ToolRack.arrange(List.of(27), inv::holds).isEmpty());
        inv.slot[40] = FOOD;
        assertEquals(4, ToolRack.target(inv::holds, Set.of()), "food only when nothing else is left");
    }

    @Test
    void nextPrefersTheHeldToolWithinReach() {
        Map<Pos, String> g = Map.of(new Pos(0, 64, 0), "axe", new Pos(2, 64, 0), "pickaxe", new Pos(9, 64, 0), "pickaxe");
        List<Pos> remaining = new ArrayList<>(g.keySet());
        double ex = 0.5, ey = 65.62, ez = -0.5;
        assertEquals(new Pos(2, 64, 0), ToolRack.next(remaining, p -> p.distSq(ex, ey, ez), g::get, "pickaxe", REACH),
            "the pickaxe block in reach before the nearer axe block");
        assertEquals(new Pos(0, 64, 0), ToolRack.next(remaining, p -> p.distSq(ex, ey, ez), g::get, "shovel", REACH),
            "nothing in reach wants the held tool: nearest");
        assertEquals(new Pos(0, 64, 0), ToolRack.next(remaining, p -> p.distSq(ex, ey, ez), g::get, null, REACH));
        remaining.remove(new Pos(2, 64, 0));
        assertEquals(new Pos(0, 64, 0), ToolRack.next(remaining, p -> p.distSq(ex, ey, ez), g::get, "pickaxe", REACH),
            "the far pickaxe block is not worth a walk");
    }
}
