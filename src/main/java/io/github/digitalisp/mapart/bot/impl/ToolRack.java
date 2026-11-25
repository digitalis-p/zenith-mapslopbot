package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;

/**
 * Tools resident in the hotbar while {@link BreakBlocksTask} breaks, and the order it breaks in. Pure (no ZenithProxy
 * calls), so it is unit-tested.
 *
 * <p>Every tool the targets need gets its own hotbar slot once, while the bot stands still before the first break
 * ({@link #arrange}); switching between them is then a hotbar select, not an inventory click. Before, every tool went to
 * hotbar slot 8: a mixed nuke (terracotta, mushroom stem, carpets) swapped the pickaxe and the axe through that slot
 * about 25 times in 1.6 s, each one a container click (a Grim risk, and slow; 10x test, 2026-10-11).
 *
 * <p>The order ({@link #next}) is nearest first, except that among the targets in reach of where the bot stands, one
 * that wants what is already in the hand comes first: all pickaxe blocks around the bot, then the axe ones, without
 * walking further.
 */
final class ToolRack {
    private ToolRack() { }

    /** Hotbar indexes a tool may go to, best first: 8 (the tool slot), then 7, 6, ...; never 0, the food slot. */
    static final int[] RACK = {8, 7, 6, 5, 4, 3, 2, 1};
    /** {@link #next} group of a block broken with the empty hand or a non-tool item (carpets). */
    static final String HAND = "hand";

    /** One inventory click: the stack in player slot {@code from} (main inventory) swapped into hotbar {@code toHotbar}. */
    record Move(int from, int toHotbar) { }

    /** What a hotbar index holds, for choosing where a tool goes. */
    enum Holds {
        EMPTY,
        /** Anything that may move to the main inventory (materials, map, panes, bottles, a sword). */
        OTHER,
        /** Better kept in the hotbar: food (eating would need a click to bring it back). */
        KEEP,
        /** A pickaxe, axe or shovel: never moved out for another tool. */
        TOOL
    }

    /**
     * Moves that put every tool of {@code wanted} (player slots 9..44) into the hotbar without moving one tool out for
     * another: tools already in the hotbar stay where they are, the others go to rack slots ({@link #RACK}) that are
     * empty, else hold something else, else hold food. At most one move (click) per tool.
     */
    static List<Move> arrange(Collection<Integer> wanted, IntFunction<Holds> hotbar) {
        Set<Integer> taken = new HashSet<>();
        for (int s : wanted) {
            int h = ItemIds.hotbarIndex(s);
            if (h >= 0) taken.add(h);
        }
        List<Move> out = new ArrayList<>();
        for (int s : new java.util.LinkedHashSet<>(wanted)) {
            if (s < ItemIds.MAIN_START || ItemIds.hotbarIndex(s) >= 0 || s > ItemIds.HOTBAR_END) continue;
            int t = target(hotbar, taken);
            if (t < 0) break;
            taken.add(t);
            out.add(new Move(s, t));
        }
        return out;
    }

    /**
     * Hotbar index for one more tool: the first rack slot (never one in {@code taken}) that is empty, else holds
     * something that may move, else holds food; -1 when every rack slot holds a tool.
     */
    static int target(IntFunction<Holds> hotbar, Set<Integer> taken) {
        for (Holds want : List.of(Holds.EMPTY, Holds.OTHER, Holds.KEEP)) {
            for (int i : RACK) if (!taken.contains(i) && hotbar.apply(i) == want) return i;
        }
        return -1;
    }

    /**
     * The next target: the nearest one, unless a target within {@code reach} of the eye wants {@code held} (a tool kind,
     * or {@link #HAND}); then the nearest such one. {@code group} is asked only for targets in reach; null or a
     * different value never matches.
     */
    static <T> @Nullable T next(Collection<T> remaining, ToDoubleFunction<T> distSq, Function<T, @Nullable String> group,
                                @Nullable String held, double reach) {
        T nearest = null;
        double nearestD = Double.MAX_VALUE;
        T same = null;
        double sameD = Double.MAX_VALUE;
        double r2 = reach * reach;
        for (T t : remaining) {
            double d = distSq.applyAsDouble(t);
            if (d < nearestD) {
                nearestD = d;
                nearest = t;
            }
            if (held != null && d <= r2 && d < sameD && held.equals(group.apply(t))) {
                sameD = d;
                same = t;
            }
        }
        return same != null ? same : nearest;
    }
}
