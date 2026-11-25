package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

/**
 * When an inventory click is safe: Grim (MultiActionsC) flags, and may cancel, a container click while the last input
 * packet shows movement keys or sprinting, and a vanilla client cannot click slots while walking. Pure: {@link Clicker}
 * feeds it ZenithProxy's input requests, the tests a simulated Bot.
 *
 * <ul>
 *   <li><b>Outside menus</b> ({@link #stillOutside}): our request of the previous tick was executed by ZenithProxy's
 *       Bot and pressed no movement key.</li>
 *   <li><b>In the task's own open menu</b> ({@link #stillInMenu}): ZenithProxy 3.7.2's Bot completes every input
 *       request FALSE while a container is open and resets the movement input ({@code Bot.tick}: "stop movement and
 *       interaction inputs while a container is open"), so "executed" never holds there and waiting for it fails every
 *       container use (gate B: all 310 scans, every lock and withdraw). ZenithProxy itself sends no movement keys
 *       then. Still = our request of the previous tick pressed no movement key and was not rejected (no other module
 *       owned the input), and the bot did not move during that tick (no slide left over from walking).</li>
 * </ul>
 */
public final class InputGate {
    private InputGate() { }

    /** Squared distance below which the bot did not move in a tick (1/1000 of a block). */
    static final double STILL_EPS_SQ = 1e-6;

    /** What ZenithProxy did with one input request. */
    public enum Outcome {
        /** Not completed yet (its tick has not run). */
        PENDING,
        /** Executed by the Bot (completed true). */
        EXECUTED,
        /** Completed false: replaced, the tick skipped, or dropped because a container was open. */
        DROPPED,
        /** Rejected at submit: a request of higher priority owned the input. */
        REJECTED
    }

    /**
     * One tick's input request of a task: the tick, whether it pressed a movement key (or jump / sprint), what became
     * of it, and the bot's feet position when it was submitted (before that tick's movement).
     */
    public record Sent(long tick, boolean moving, Outcome outcome, double x, double y, double z) { }

    /** Outside menus: the previous tick's request was executed and pressed no movement key. */
    public static boolean stillOutside(long now, @Nullable Sent prev) {
        return prev != null && prev.tick() == now - 1 && prev.outcome() == Outcome.EXECUTED && !prev.moving();
    }

    /**
     * In the task's own open menu: the previous tick's request pressed no movement key and was not rejected (its
     * outcome is DROPPED while the menu is open, EXECUTED if the menu opened after it ran), and the bot is where it
     * was when that request was submitted.
     */
    public static boolean stillInMenu(long now, @Nullable Sent prev, double x, double y, double z) {
        return noKeysInMenu(now, prev) && Aim.distSq(prev.x(), prev.y(), prev.z(), x, y, z) < STILL_EPS_SQ;
    }

    /**
     * {@link #stillInMenu} without the position: the bot may still slide (coast-click, {@code build.coastClick}). Grim's
     * MultiActionsC/D look at the keys and the sprint state of a container click or close, not at the motion.
     */
    public static boolean noKeysInMenu(long now, @Nullable Sent prev) {
        return prev != null && prev.tick() == now - 1 && !prev.moving()
            && (prev.outcome() == Outcome.EXECUTED || prev.outcome() == Outcome.DROPPED);
    }
}
