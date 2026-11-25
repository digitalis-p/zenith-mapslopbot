package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Stack;
import io.github.digitalisp.mapart.bot.impl.TransferPlanner.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A chest menu ({@code top} container slots, then 27 main and 9 hotbar slots) that handles clicks as vanilla 1.21 does
 * ({@code AbstractContainerMenu.doClick} PICKUP and QUICK_MOVE, {@code ChestMenu.quickMoveStack}), for planner tests.
 * Items carry no components: the same id is the same item.
 * <ul>
 *   <li>LEFT: pick up the whole stack; with a cursor: put it all into an empty slot, merge as much as fits into the
 *       same item, or swap with a different one.</li>
 *   <li>RIGHT: with a cursor: one into an empty slot or the same item when not full, swap with a different one;
 *       without: pick up half (rounded up).</li>
 *   <li>QUICK_MOVE: container → player merges into the player's partial stacks of the item, then the rest into one
 *       empty slot, both scanning from the last slot (hotbar end) back; player → container the same from slot 0 on.
 *       With an item on the cursor it throws: ZenithProxy's ShiftClick sends nothing then, so a plan must never do it.</li>
 * </ul>
 * {@link #zenithPrediction} switches the model to what ZenithProxy 3.7.2's {@code ClickItem} predicts into its cache
 * for the cursor clicks (a left click swaps cursor and slot; a right click onto an empty slot leaves
 * {@code amount - 1} there; a click it would not send changes nothing).
 */
final class VanillaClickModel {
    record Item(String id, int count, int max) {
        Item with(int n) {
            return new Item(id, n, max);
        }
    }

    final int top;
    private final @Nullable Item[] slots;
    private @Nullable Item cursor;
    private boolean zenith;
    /** Clicks the prediction model would not have sent (ClickItem returns no packet). */
    int unsent;

    VanillaClickModel(int top) {
        this.top = top;
        this.slots = new Item[top + 36];
    }

    /** A copy with ZenithProxy's cache predictions instead of vanilla's results. */
    VanillaClickModel zenithPrediction() {
        VanillaClickModel m = new VanillaClickModel(top);
        System.arraycopy(slots, 0, m.slots, 0, slots.length);
        m.cursor = cursor;
        m.zenith = true;
        return m;
    }

    /** A copy of the state (slots and cursor), vanilla rules: the server's state at one moment. */
    VanillaClickModel copy() {
        VanillaClickModel m = new VanillaClickModel(top);
        System.arraycopy(slots, 0, m.slots, 0, slots.length);
        m.cursor = cursor;
        return m;
    }

    /** The server's full state arrives (container content with the cursor): every slot and the cursor take it. */
    void resync(VanillaClickModel state) {
        System.arraycopy(state.slots, 0, slots, 0, slots.length);
        cursor = state.cursor;
    }

    VanillaClickModel put(int slot, String id, int count, int max) {
        slots[slot] = new Item(id, count, max);
        return this;
    }

    @Nullable Item at(int slot) {
        return slots[slot];
    }

    /** The server's correction of one slot arrives: it takes {@code truth}'s contents. */
    void correct(int slot, VanillaClickModel truth) {
        slots[slot] = truth.slots[slot];
    }

    @Nullable Item cursor() {
        return cursor;
    }

    /** The server's cursor update arrives: the cursor takes {@code truth}'s. */
    void correctCursor(VanillaClickModel truth) {
        cursor = truth.cursor;
    }

    /** First player slot: the main inventory's top left. */
    int playerStart() {
        return top;
    }

    void apply(List<Step> steps) {
        for (Step s : steps) click(s);
    }

    void click(Step s) {
        switch (s.kind()) {
            case LEFT -> left(s.slot());
            case RIGHT -> right(s.slot());
            case QUICK_MOVE -> quickMove(s.slot());
        }
    }

    private void left(int slot) {
        Item s = slots[slot];
        if (zenith) {
            if (s == null && cursor == null) {
                unsent++;
                return;
            }
            slots[slot] = cursor;
            cursor = s;
            return;
        }
        if (s == null) {
            slots[slot] = cursor;
            cursor = null;
            return;
        }
        if (cursor == null) {
            cursor = s;
            slots[slot] = null;
            return;
        }
        if (s.id().equals(cursor.id())) {
            int moved = Math.min(cursor.count(), s.max() - s.count());
            slots[slot] = s.with(s.count() + moved);
            cursor = less(cursor, moved);
            return;
        }
        slots[slot] = cursor;
        cursor = s;
    }

    private void right(int slot) {
        Item s = slots[slot];
        if (cursor == null) {
            if (s == null) {
                if (zenith) unsent++;
                return;
            }
            int half = (s.count() + 1) / 2;
            cursor = s.with(half);
            slots[slot] = s.count() - half == 0 ? null : s.with(s.count() - half);
            return;
        }
        if (s == null) {
            if (zenith && cursor.count() > 1) {
                slots[slot] = cursor.with(cursor.count() - 1); // ClickItem's prediction; vanilla puts 1 there
            } else {
                slots[slot] = cursor.with(1);
            }
            cursor = less(cursor, 1);
            return;
        }
        if (s.id().equals(cursor.id())) {
            if (s.count() >= s.max()) {
                if (zenith) unsent++;
                return;
            }
            slots[slot] = s.with(s.count() + 1);
            cursor = less(cursor, 1);
            return;
        }
        slots[slot] = cursor;
        cursor = s;
    }

    private void quickMove(int slot) {
        if (cursor != null) {
            throw new IllegalStateException("shift-click on " + slot + " with " + cursor + " on the cursor");
        }
        Item s = slots[slot];
        if (s == null) return;
        if (zenith) return; // ShiftClick predicts nothing: the cache waits for the server's full state
        boolean fromContainer = slot < top;
        int[] order = fromContainer ? range(top + 35, top) : range(0, top - 1);
        int left = s.count();
        for (int i : order) {
            Item t = slots[i];
            if (t == null || !t.id().equals(s.id()) || t.count() >= t.max()) continue;
            int m = Math.min(left, t.max() - t.count());
            slots[i] = t.with(t.count() + m);
            left -= m;
            if (left == 0) break;
        }
        if (left > 0) {
            for (int i : order) {
                if (slots[i] == null) {
                    slots[i] = s.with(left);
                    left = 0;
                    break;
                }
            }
        }
        slots[slot] = left == 0 ? null : s.with(left);
    }

    private static int[] range(int from, int to) {
        int n = Math.abs(to - from) + 1;
        int[] out = new int[n];
        int d = to >= from ? 1 : -1;
        for (int i = 0; i < n; i++) out[i] = from + i * d;
        return out;
    }

    private static @Nullable Item less(Item it, int n) {
        return it.count() - n <= 0 ? null : it.with(it.count() - n);
    }

    // ------------------------------------------------------------------------------------------------ reading

    List<Stack> containerStacks() {
        return stacks(0, top);
    }

    List<Stack> playerStacks() {
        return stacks(top, top + 36);
    }

    private List<Stack> stacks(int from, int to) {
        List<Stack> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Item it = slots[i];
            if (it != null) out.add(new Stack(i, it.id(), it.count(), it.max()));
        }
        return out;
    }

    List<Integer> emptyPlayerSlots() {
        List<Integer> out = new ArrayList<>();
        for (int i = top; i < top + 36; i++) if (slots[i] == null) out.add(i);
        return out;
    }

    int playerFree() {
        return emptyPlayerSlots().size();
    }

    int playerCount(String id) {
        return count(id, top, top + 36);
    }

    int containerCount(String id) {
        return count(id, 0, top);
    }

    /** Player slots holding {@code id}. */
    int playerSlots(String id) {
        int n = 0;
        for (int i = top; i < top + 36; i++) if (slots[i] != null && slots[i].id().equals(id)) n++;
        return n;
    }

    private int count(String id, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) if (slots[i] != null && slots[i].id().equals(id)) n += slots[i].count();
        return n;
    }

    /** Slots whose contents differ from {@code o}'s (-1: the cursor). */
    List<Integer> differences(VanillaClickModel o) {
        List<Integer> out = new ArrayList<>();
        if (!Objects.equals(cursor, o.cursor)) out.add(-1);
        for (int i = 0; i < slots.length; i++) if (!Objects.equals(slots[i], o.slots[i])) out.add(i);
        return out;
    }

    @Override
    public String toString() {
        return "cursor " + cursor + ", slots " + Arrays.toString(slots);
    }
}
