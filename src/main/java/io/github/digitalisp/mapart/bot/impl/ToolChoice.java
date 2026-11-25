package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.InventoryView;

import java.util.List;

/**
 * What {@link BreakBlocksTask} holds to break one block. Pure (no ZenithProxy calls), so it is unit-tested.
 *
 * <ol>
 *   <li>A block that breaks by hand within {@link #NEAR_INSTANT_TICKS} ticks (carpets, hardness ≤ 0.1, instant
 *       blocks) is broken with an empty hand or a non-tool item. A tool loses a use on every block whose hardness is
 *       above 0 and is no faster on these, so using it would only wear it.</li>
 *   <li>A tool only where it is faster than the hand: terracotta and stone-like blocks → pickaxe, logs → axe,
 *       dirt-like → shovel (the speeds come from ZenithProxy's vanilla break-speed formula).</li>
 *   <li>Never a tool at or below the safety floor ({@code build.toolSafetyFloor} uses left): the fastest tool above
 *       the floor that still beats the hand (another of the same kind when the best one is worn). If every tool that
 *       beats the hand is worn, the answer is {@link Kind#WORN}: the task stops instead of breaking with it.</li>
 * </ol>
 */
final class ToolChoice {
    private ToolChoice() { }

    /** Hand break time (ticks) up to which a block is never broken with a tool. Carpets take 3. */
    static final int NEAR_INSTANT_TICKS = 4;
    private static final double EPS = 1e-4;

    /**
     * A damageable tool in the hotbar or main inventory.
     *
     * @param slot      player inventory slot (9..44)
     * @param speed     destroy progress per tick on the block with this item in the main hand
     * @param remaining uses left (max − damage); max for an unbreakable tool
     * @param held      it is in the main hand now
     */
    record Candidate(int slot, String itemId, double speed, int remaining, int max, boolean mending,
                     boolean inHotbar, boolean held) {
        Candidate(int slot, String itemId, double speed, int remaining, int max, boolean mending, boolean inHotbar) {
            this(slot, itemId, speed, remaining, max, mending, inHotbar, false);
        }

        String kind() {
            String k = InventoryView.toolKind(itemId);
            return k != null ? k : itemId;
        }
    }

    enum Kind {
        /** Empty hand or a non-tool item. */
        HAND,
        /** {@link Choice#tool()} in the main hand. */
        TOOL,
        /** A tool would be needed, but every one that beats the hand is at or below the floor. */
        WORN
    }

    record Choice(Kind kind, @Nullable Candidate tool) {
        /** Human-readable reason for {@link Kind#WORN}. */
        String wornReason(int floor) {
            if (tool == null) return "no usable tool";
            return "no " + tool.kind() + " above the safety floor (" + floor + " uses): " + tool.itemId() + " at "
                + tool.remaining() + "/" + tool.max();
        }
    }

    /** Ticks to break a block at {@code speed} destroy progress per tick (vanilla: done when the sum reaches 1). */
    static int ticks(double speed) {
        if (!(speed > 0)) return Integer.MAX_VALUE;
        if (speed >= 1.0) return 1;
        return (int) Math.ceil(1.0 / speed - 1e-6);
    }

    static Choice choose(double handSpeed, List<Candidate> tools, int floor) {
        if (ticks(handSpeed) <= NEAR_INSTANT_TICKS) return new Choice(Kind.HAND, null);
        Candidate usable = null;
        Candidate worn = null;
        for (Candidate c : tools) {
            if (!(c.speed() > handSpeed * (1 + EPS))) continue;
            if (c.remaining() > floor) {
                if (usable == null || better(c, usable)) usable = c;
            } else if (worn == null || c.speed() > worn.speed() * (1 + EPS)) {
                worn = c;
            }
        }
        if (usable != null) return new Choice(Kind.TOOL, usable);
        if (worn != null) return new Choice(Kind.WORN, worn);
        return new Choice(Kind.HAND, null);
    }

    /**
     * Faster first; at the same speed a Mending tool (it can be repaired), then the one already held (no switch every
     * block between two equal tools), then one in the hotbar, then more uses left.
     */
    private static boolean better(Candidate a, Candidate b) {
        if (a.speed() > b.speed() * (1 + EPS)) return true;
        if (b.speed() > a.speed() * (1 + EPS)) return false;
        if (a.mending() != b.mending()) return a.mending();
        if (a.held() != b.held()) return a.held();
        if (a.inHotbar() != b.inHotbar()) return a.inHotbar();
        return a.remaining() > b.remaining();
    }
}
