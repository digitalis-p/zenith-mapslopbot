package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.WithdrawMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure planning of transfers between a container and the player inventory, as click steps in the open menu's slot
 * numbers (container slots {@code 0..top-1}, then the player's main inventory and hotbar).
 *
 * <p>Whole stacks go by shift-click ({@link Kind#QUICK_MOVE}), so a withdrawal may exceed the wanted count by less
 * than one stack; the planner prefers stacks that minimise that overshoot. Room is accounted for conservatively (a
 * stack that does not fit completely is not moved). A withdrawal may also use the cursor ({@link WithdrawMode}):
 * <ul>
 *   <li>{@link WithdrawMode#TOP_UP}: {@code LEFT(S) LEFT(P) LEFT(S)} tops a held partial stack P up from a FULL
 *       container stack S (cursor ← S; P filled to the max, the cursor keeps P's old count; that goes back into S).
 *       ZenithProxy's {@code ClickItem} predicts a left click as a swap of cursor and slot, which for a full cursor
 *       stack onto a partial stack of the same item is exactly vanilla's merge, so the cache stays right.</li>
 *   <li>{@link WithdrawMode#EXACT}: {@code LEFT(S) RIGHT(E)×w LEFT(S)} puts exactly {@code w} into E (a partial stack
 *       of the item or an empty slot) and the rest back into S.</li>
 * </ul>
 * Every cursor sequence of a request comes before all its shift-clicks: ZenithProxy's {@code ShiftClick} refuses to
 * run with an item on the cursor, and a shift-click could fill an empty slot an exact sequence is aimed at. Each
 * sequence leaves the cursor empty.
 */
public final class TransferPlanner {
    private TransferPlanner() { }

    /** {@link WithdrawMode#EXACT} counts above this go as whole stacks. */
    public static final int EXACT_MAX_COUNT = 8;

    /**
     * Ticks the server's answer to a click may take (2b2t ack round trip: p50 about 3.3 ticks, p95 about 7), plus one:
     * how long {@link #waitsAfter} keeps the next click back after a cursor click whose prediction may be corrected.
     */
    public static final int ACK_TICKS = 8;

    /** One occupied slot. {@code slot} is in whatever numbering the caller uses for clicks. */
    public record Stack(int slot, String itemId, int count, int maxStack) { }

    /** Vanilla click types a plan uses. */
    public enum Kind {
        /** Shift-click (QUICK_MOVE): the slot's stack to the other side, merged into partial stacks first. */
        QUICK_MOVE,
        /** Left click (PICKUP): pick up a whole stack; with a cursor: put it down, merge what fits, or swap. */
        LEFT,
        /** Right click (PICKUP) with an item on the cursor: put one of it into the slot (empty or same item). */
        RIGHT
    }

    /** One click: what and where (menu slot number). */
    public record Step(Kind kind, int slot) {
        static Step quickMove(int slot) {
            return new Step(Kind.QUICK_MOVE, slot);
        }

        static Step left(int slot) {
            return new Step(Kind.LEFT, slot);
        }

        static Step right(int slot) {
            return new Step(Kind.RIGHT, slot);
        }
    }

    /**
     * A planned transfer.
     *
     * @param clicks   the stacks that move whole by shift-click, in order. Deposits and {@link WithdrawMode#WHOLE_STACKS}:
     *                 every stack moved. {@link WithdrawMode#TOP_UP}: the whole stacks after the top-ups (a topped-up
     *                 source stack stays in the container with the rest). {@link WithdrawMode#EXACT}: stacks of exactly
     *                 the wanted count and the whole-stack fallbacks (a source stack of an exact sequence stays)
     * @param expected item id → count that moves (a withdrawal: what the player gains, top-ups and exact counts
     *                 included)
     * @param problem  a short reason when the request cannot be fully satisfied, null if it can
     * @param steps    the clicks to send in order: the cursor sequences first, then one {@link Kind#QUICK_MOVE} per
     *                 {@code clicks} stack, in the same order
     */
    public record Plan(List<Stack> clicks, Map<String, Integer> expected, @Nullable String problem, List<Step> steps) {
        public Plan {
            steps = List.copyOf(steps);
        }

        /** Whole stacks only: one shift-click per stack of {@code clicks}. */
        public Plan(List<Stack> clicks, Map<String, Integer> expected, @Nullable String problem) {
            this(clicks, expected, problem, shiftClicks(clicks));
        }
    }

    private static List<Step> shiftClicks(List<Stack> clicks) {
        List<Step> out = new ArrayList<>(clicks.size());
        for (Stack s : clicks) out.add(Step.quickMove(s.slot()));
        return out;
    }

    /**
     * {@link #planWithdraw(List, Map, List, int, WithdrawMode, List)} with whole stacks ({@link WithdrawMode#WHOLE_STACKS}).
     *
     * @param container   container's own occupied slots
     * @param wanted      item id → count to take
     * @param player      player's occupied hotbar+main slots (for merge room)
     * @param playerFree  empty hotbar+main slots
     */
    public static Plan planWithdraw(List<Stack> container, Map<String, Integer> wanted, List<Stack> player,
                                    int playerFree) {
        return planWithdraw(container, wanted, player, playerFree, WithdrawMode.WHOLE_STACKS, List.of());
    }

    /**
     * The clicks that take {@code wanted} out of a container with the click pattern of {@code mode}. Items are planned
     * in request order; an item a mode cannot serve goes as whole stacks.
     *
     * @param container        container's own occupied slots
     * @param wanted           item id → count to take
     * @param player           player's occupied hotbar+main slots (merge room, partial stacks to top up)
     * @param playerFree       empty hotbar+main slots
     * @param mode             the click pattern
     * @param emptyPlayerSlots the empty hotbar+main slots in the click numbering, in the order an exact count may
     *                         take them (not needed for whole stacks)
     */
    public static Plan planWithdraw(List<Stack> container, Map<String, Integer> wanted, List<Stack> player,
                                    int playerFree, WithdrawMode mode, List<Integer> emptyPlayerSlots) {
        Room room = new Room(partialRoom(player), playerFree, emptyPlayerSlots);
        List<Stack> clicks = new ArrayList<>();
        List<Step> cursor = new ArrayList<>();
        Map<String, Integer> expected = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (var want : wanted.entrySet()) {
            String id = want.getKey();
            int remaining = want.getValue() == null ? 0 : want.getValue();
            if (remaining <= 0) continue;
            List<Stack> pool = new ArrayList<>();
            for (Stack s : container) if (s.itemId().equals(id) && s.count() > 0) pool.add(s);
            pool.sort(Comparator.comparingInt(Stack::count).reversed().thenComparingInt(Stack::slot));
            if (mode == WithdrawMode.TOP_UP) {
                remaining -= topUp(id, remaining, pool, player, room, cursor, expected);
                if (remaining <= 0) continue; // done; the overshoot stays carried
            } else if (mode == WithdrawMode.EXACT && !pool.isEmpty() && remaining < Math.max(1, pool.getFirst().maxStack())
                && remaining <= EXACT_MAX_COUNT) {
                String why = exact(id, remaining, pool, player, room, clicks, cursor, expected);
                if (why == null) continue;
                if (!why.isEmpty()) {
                    problems.add(id + " short by " + remaining + why);
                    continue;
                }
                // no stack holds the count: whole stacks
            }
            boolean full = false;
            while (remaining > 0 && !pool.isEmpty()) {
                Stack pick = pickWithdraw(pool, remaining);
                if (!room.take(id, pick.count(), pick.maxStack())) {
                    full = true;
                    break;
                }
                clicks.add(pick);
                pool.remove(pick);
                remaining -= pick.count();
                expected.merge(id, pick.count(), Integer::sum);
            }
            if (remaining > 0) problems.add(id + " short by " + remaining + (full ? " (inventory full)" : " (container)"));
        }
        List<Step> steps = new ArrayList<>(cursor);
        steps.addAll(shiftClicks(clicks));
        return new Plan(clicks, expected, problems.isEmpty() ? null : String.join(", ", problems), steps);
    }

    /**
     * {@link WithdrawMode#TOP_UP} for one item: the held partial stacks P are filled, fullest first, from full container
     * stacks S ({@code LEFT(S) LEFT(P) LEFT(S)}; S then holds P's old count and leaves the pool, P is full), as many of
     * them as leave the item in the fewest slots afterwards (ties: fewer clicks, so none when topping up saves no
     * slot). Returns the count moved.
     *
     * <p>Slots after topping up k partials (fullest first) and taking the rest of {@code wanted} in whole stacks: with
     * {@code h} held in {@code f} full and {@code p} partial stacks, total {@code T = h + moved + max·ceil(rest / max)}
     * and {@code max(f + p, ceil(T / max))} slots (a shift-clicked stack fills the partial stacks left before it takes
     * an empty slot). One partial: top up iff {@code ceil((w + r) / max) <= ceil(w / max)}, as before.
     *
     * <p>More than the fullest (local 10x regression of bb505fb): the lane's hotbar moves often leave a colour in two
     * partial stacks. Filling only one left the other to overshoot the whole stacks after it, so the item took one slot
     * more than the restock planner counted ({@code max(slots held, ceil(target / max))}) and the load's last withdrawal
     * came up short with "inventory full" (5 of 15 loads on a carpet tile).
     */
    private static int topUp(String id, int wanted, List<Stack> pool, List<Stack> player, Room room, List<Step> cursor,
                             Map<String, Integer> expected) {
        List<Stack> partials = new ArrayList<>();
        int held = 0;
        int fulls = 0;
        int max = 0;
        for (Stack s : player) {
            if (!s.itemId().equals(id) || s.count() <= 0) continue;
            held += s.count();
            max = Math.max(max, s.maxStack());
            if (s.count() < s.maxStack()) partials.add(s);
            else fulls++;
        }
        if (partials.isEmpty() || max <= 0) return 0;
        partials.sort(Comparator.comparingInt(Stack::count).reversed().thenComparingInt(Stack::slot));
        List<Stack> sources = new ArrayList<>();
        for (Stack s : pool) if (s.count() == max && s.maxStack() == max) sources.add(s);
        sources.sort(Comparator.comparingInt(Stack::slot));
        int most = Math.min(partials.size(), sources.size());
        int bestK = 0;
        int bestSlots = Integer.MAX_VALUE;
        int bestClicks = Integer.MAX_VALUE;
        int fill = 0;
        for (int k = 0; k <= most; k++) {
            if (k > 0) {
                if (fill >= wanted) break; // the request is served: more top-ups only overshoot
                fill += max - partials.get(k - 1).count();
            }
            int rest = Math.max(0, wanted - fill);
            int stacks = ceilDiv(rest, max);
            long total = (long) held + fill + (long) stacks * max;
            int slots = (int) Math.max(fulls + partials.size(), (total + max - 1) / max);
            int clicks = 3 * k + stacks;
            if (slots < bestSlots || (slots == bestSlots && clicks < bestClicks)) {
                bestK = k;
                bestSlots = slots;
                bestClicks = clicks;
            }
        }
        int moved = 0;
        for (int k = 0; k < bestK; k++) {
            Stack p = partials.get(k);
            Stack full = sources.get(k);
            cursor.add(Step.left(full.slot()));
            cursor.add(Step.left(p.slot()));
            cursor.add(Step.left(full.slot()));
            pool.remove(full);
            int f = max - p.count();
            room.partial.merge(id, -f, Integer::sum);
            moved += f;
        }
        if (moved > 0) expected.merge(id, moved, Integer::sum);
        return moved;
    }

    /**
     * {@link WithdrawMode#EXACT} for one item, {@code 0 < w < max}: a stack of exactly {@code w} is shift-clicked;
     * else the smallest stack above {@code w} is picked up, {@code w} right-clicked into a held partial stack with room
     * for them (else the first empty slot) and the rest put back. Returns null when planned, "" when no stack holds
     * {@code w} (whole stacks then), else the reason it is short (" (inventory full)").
     */
    private static @Nullable String exact(String id, int w, List<Stack> pool, List<Stack> player, Room room,
                                          List<Stack> clicks, List<Step> cursor, Map<String, Integer> expected) {
        Stack exact = null;
        Stack above = null;
        for (Stack s : pool) {
            if (s.count() == w && (exact == null || s.slot() < exact.slot())) exact = s;
            if (s.count() > w && (above == null || s.count() < above.count()
                || (s.count() == above.count() && s.slot() < above.slot()))) above = s;
        }
        if (exact != null) {
            if (!room.take(id, w, exact.maxStack())) return " (inventory full)";
            clicks.add(exact);
            pool.remove(exact);
            expected.merge(id, w, Integer::sum);
            return null;
        }
        if (above == null) return "";
        int max = above.maxStack();
        Stack into = null;
        for (Stack s : player) {
            if (!s.itemId().equals(id) || s.count() <= 0 || s.maxStack() - s.count() < w) continue;
            if (into == null || s.count() > into.count() || (s.count() == into.count() && s.slot() < into.slot())) into = s;
        }
        int target;
        if (into != null) {
            target = into.slot();
            room.partial.merge(id, -w, Integer::sum);
        } else {
            Integer empty = room.takeEmpty();
            if (empty == null) return " (inventory full)";
            target = empty;
            room.partial.merge(id, max - w, Integer::sum);
        }
        cursor.add(Step.left(above.slot()));
        for (int i = 0; i < w; i++) cursor.add(Step.right(target));
        cursor.add(Step.left(above.slot()));
        pool.remove(above);
        expected.merge(id, w, Integer::sum);
        return null;
    }

    /**
     * WaitActions to put after each step (same index) when the steps go out one per {@code delayTicks}, so the server's
     * corrections of ZenithProxy's cursor-click predictions are in the cache before they matter. Each wait lasts one
     * delay, so the next action runs at least {@link #ACK_TICKS} after the click:
     * <ul>
     *   <li>after the last {@link Kind#LEFT}/{@link Kind#RIGHT}: the shift-clicks after it read the cursor (they do not
     *       run with an item on it), and without shift-clicks the menu closes soon after (a correction that arrives
     *       after the close is lost to the cache);</li>
     *   <li>after a {@link Kind#RIGHT} onto a slot that was empty ({@code emptyPlayerSlots}) when another RIGHT onto it
     *       follows: ZenithProxy predicts {@code amount - 1} there (vanilla puts 1), and while that is uncorrected a
     *       further right click can read the slot as full and is not sent at all.</li>
     * </ul>
     */
    public static int[] waitsAfter(List<Step> steps, Collection<Integer> emptyPlayerSlots, int delayTicks) {
        return waitsAfter(steps, emptyPlayerSlots, delayTicks, ACK_TICKS);
    }

    /** {@link #waitsAfter(List, Collection, int)} with an answer time of {@code ackTicks} instead of {@link #ACK_TICKS}. */
    public static int[] waitsAfter(List<Step> steps, Collection<Integer> emptyPlayerSlots, int delayTicks, int ackTicks) {
        int d = Math.max(1, delayTicks);
        int waits = Math.max(0, ceilDiv(Math.max(1, ackTicks), d) - 1);
        int[] out = new int[steps.size()];
        Set<Integer> touched = new HashSet<>();
        int lastCursor = -1;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.kind() == Kind.QUICK_MOVE) continue;
            lastCursor = i;
            boolean ontoEmpty = s.kind() == Kind.RIGHT && emptyPlayerSlots.contains(s.slot()) && !touched.contains(s.slot());
            touched.add(s.slot());
            if (ontoEmpty && i + 1 < steps.size() && steps.get(i + 1).kind() == Kind.RIGHT
                && steps.get(i + 1).slot() == s.slot()) {
                out[i] = waits;
            }
        }
        if (lastCursor >= 0) out[lastCursor] = waits;
        return out;
    }

    /**
     * WaitActions to add after a request's last click (behind which {@code trailing} waits already stand) so that the
     * action after them, the snapshot that reads the container before the close, runs at least {@code ackTicks} after
     * that click: the server's answers to the clicks still in flight are in the cache first. At one action per
     * {@code delayTicks}: {@code max(0, ceil(ackTicks / delay) - 1 - trailing)}.
     */
    public static int tailWaits(int trailing, int delayTicks, int ackTicks) {
        int d = Math.max(1, delayTicks);
        return Math.max(0, ceilDiv(Math.max(1, ackTicks), d) - 1 - Math.max(0, trailing));
    }

    /**
     * @param player         player's occupied hotbar+main slots
     * @param which          item ids to deposit
     * @param keep           item id → count to keep in the inventory
     * @param neverDeposit   ids that are never deposited (config keepItems)
     * @param container      container's own occupied slots (for merge room)
     * @param containerFree  empty container slots
     */
    public static Plan planDeposit(List<Stack> player, Predicate<String> which, Map<String, Integer> keep,
                                   Set<String> neverDeposit, List<Stack> container, int containerFree) {
        Map<String, List<Stack>> byId = new LinkedHashMap<>();
        for (Stack s : player) {
            if (s.count() <= 0 || neverDeposit.contains(s.itemId()) || !which.test(s.itemId())) continue;
            byId.computeIfAbsent(s.itemId(), k -> new ArrayList<>()).add(s);
        }
        Map<String, Integer> partialRoom = partialRoom(container);
        int free = containerFree;
        List<Stack> clicks = new ArrayList<>();
        Map<String, Integer> expected = new LinkedHashMap<>();
        boolean full = false;
        for (var e : byId.entrySet()) {
            String id = e.getKey();
            int held = 0;
            for (Stack s : e.getValue()) held += s.count();
            int toDeposit = held - Math.max(0, keep.getOrDefault(id, 0));
            if (toDeposit <= 0) continue;
            List<Stack> stacks = new ArrayList<>(e.getValue());
            stacks.sort(Comparator.comparingInt(Stack::count).reversed().thenComparingInt(Stack::slot));
            for (Stack s : stacks) {
                if (s.count() > toDeposit) continue;
                int room = partialRoom.getOrDefault(id, 0);
                int rest = Math.max(0, s.count() - room);
                int max = Math.max(1, s.maxStack());
                int slotsNeeded = (rest + max - 1) / max;
                if (slotsNeeded > free) {
                    full = true;
                    continue;
                }
                free -= slotsNeeded;
                int usedPartial = s.count() - rest;
                partialRoom.put(id, room - usedPartial + (rest % max == 0 ? 0 : max - rest % max));
                clicks.add(s);
                toDeposit -= s.count();
                expected.merge(id, s.count(), Integer::sum);
            }
        }
        return new Plan(clicks, expected, full ? "container full" : null);
    }

    /**
     * Next stack to take (pool sorted largest first): an exact fit; else the smallest stack that finishes the
     * request if its overshoot is smaller than what is still missing; else the largest stack below the need.
     */
    static Stack pickWithdraw(List<Stack> pool, int remaining) {
        Stack above = null;
        Stack below = null;
        for (Stack s : pool) {
            if (s.count() == remaining) return s;
            if (s.count() > remaining) above = s; // keeps the smallest one above (descending order)
            else if (below == null) below = s;    // first one below is the largest below
        }
        if (below == null) return above;
        if (above == null) return below;
        return above.count() - remaining < remaining ? above : below;
    }

    /**
     * The player's room during a withdrawal plan: merge room per item in partial stacks, the empty slots left, and the
     * empty slots an exact count may still be aimed at.
     */
    private static final class Room {
        final Map<String, Integer> partial;
        int free;
        final Deque<Integer> empties;

        Room(Map<String, Integer> partial, int free, List<Integer> empties) {
            this.partial = partial;
            this.free = free;
            this.empties = new ArrayDeque<>(empties);
        }

        /** A shift-click of {@code count} (whole stack): merged into partial stacks, the rest into empty slots. */
        boolean take(String id, int count, int maxStack) {
            int room = partial.getOrDefault(id, 0);
            int rest = Math.max(0, count - room);
            int max = Math.max(1, maxStack);
            int slotsNeeded = (rest + max - 1) / max;
            if (slotsNeeded > free) return false;
            free -= slotsNeeded;
            int usedPartial = count - rest;
            partial.put(id, room - usedPartial + (rest % max == 0 ? 0 : max - rest % max));
            return true;
        }

        /**
         * An empty slot for an exact count, or null when none is left. The cursor sequences run before the
         * shift-clicks, so the slot is still empty then; it counts against the free slots of the shift-clicks.
         */
        @Nullable Integer takeEmpty() {
            if (free <= 0 || empties.isEmpty()) return null;
            free--;
            return empties.poll();
        }
    }

    private static int ceilDiv(int a, int b) {
        return Math.floorDiv(a + b - 1, b);
    }

    private static Map<String, Integer> partialRoom(List<Stack> stacks) {
        Map<String, Integer> room = new HashMap<>();
        for (Stack s : stacks) {
            if (s.count() > 0 && s.count() < s.maxStack()) room.merge(s.itemId(), s.maxStack() - s.count(), Integer::sum);
        }
        return room;
    }
}
