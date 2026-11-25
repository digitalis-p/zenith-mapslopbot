package io.github.digitalisp.mapart.bot.impl;

import java.util.function.LongSupplier;

/**
 * Decision logic of {@link EatTask}: one call per tick, no ZenithProxy calls, so it is unit-tested.
 *
 * <p>Each step starts only once the previous one holds:
 * <ol>
 *   <li>Stand still: {@link #SETTLE_TICKS} ticks in a row whose input was ours and pressed no movement key.</li>
 *   <li>{@link Step#HOLD}: select the food's hotbar slot, or, only if it is not in the hotbar, one inventory click
 *       moving it there. The still ticks before it keep Grim MultiActionsC (click while moving) quiet.</li>
 *   <li>{@link Step#USE}: one use-item packet without an arm swing. A vanilla client does not swing when it starts
 *       eating, and Grim MultiActionsE flags a swing while an item is in use.</li>
 *   <li>Keep still and send nothing else until the server consumed the item (its count dropped or the hunger bar
 *       rose), then {@link #AFTER_TICKS} more ticks so the server's "no longer using" state arrives before the bot
 *       walks again (Grim NoSlow and SprintC check movement and sprinting while an item is in use).</li>
 * </ol>
 * If the input is lost or shows movement while eating, or the server does not consume the item within
 * {@link #USE_TIMEOUT} ticks and {@link #USE_TIMEOUT_MS} wall time, the use is released and tried again, at most
 * {@link #MAX_USES} times. Both must pass: the server needs 32 of its own ticks, and it runs slower than the bot when
 * it lags (2b2t) or when a local speed-mode server falls behind the bot's 10x ticks (holes2 drill 2026-10-28: three
 * uses in 1.1 s, none consumed, 119 failures and BLOCKED).
 */
final class EatSequence {
    /** Still ticks before the first slot change, click or use. */
    static final int SETTLE_TICKS = 3;
    /** Ticks after the use to see the item consumed (vanilla food takes 32 server ticks, dried kelp 16). */
    static final int USE_TIMEOUT = 100;
    /** Wall time after the use to see the item consumed: 32 server ticks at 6.4 tps. */
    static final long USE_TIMEOUT_MS = 5000;
    /** Still ticks after the item was consumed. */
    static final int AFTER_TICKS = 5;
    static final int MAX_USES = 3;

    enum Step {
        /** Hold still, send nothing. */
        WAIT,
        /** Put the food in the main hand (hotbar select, or one click from the main inventory). */
        HOLD,
        /** Send use-item (main hand, no swing). */
        USE,
        /** Send release-use-item: the current use is abandoned. */
        RELEASE,
        DONE,
        FAIL
    }

    /**
     * What the task sees this tick.
     *
     * @param still       last tick's input was ours, executed, and pressed no movement key
     * @param canUse      a use may be sent this tick (input owned last tick and this tick, no container open)
     * @param foodPresent the chosen food is in the inventory
     * @param holding     the chosen food is in the main hand
     * @param count       count of the chosen food in the inventory
     * @param hunger      hunger bar 0..20
     */
    record Seen(boolean still, boolean canUse, boolean foodPresent, boolean holding, int count, int hunger) { }

    private final LongSupplier clockMs;
    private int stillTicks;
    private long usedAt = -1;
    private long usedAtMs;
    private int countAtUse;
    private int hungerAtUse;
    private int uses;
    private long doneAt = -1;
    private String reason = "";

    EatSequence() {
        this(System::currentTimeMillis);
    }

    EatSequence(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    Step next(long now, Seen s) {
        stillTicks = s.still() ? stillTicks + 1 : 0;
        if (doneAt >= 0) return now - doneAt >= AFTER_TICKS ? Step.DONE : Step.WAIT;
        if (usedAt >= 0) {
            if (s.count() < countAtUse || s.hunger() > hungerAtUse) {
                usedAt = -1;
                doneAt = now;
                return Step.WAIT;
            }
            if (!s.still() || (now - usedAt >= USE_TIMEOUT && clockMs.getAsLong() - usedAtMs >= USE_TIMEOUT_MS)) {
                usedAt = -1;
                return Step.RELEASE;
            }
            return Step.WAIT;
        }
        if (s.hunger() >= 20) return Step.DONE; // nothing to eat; the server refuses food at a full bar
        if (uses >= MAX_USES) {
            reason = "the server did not consume the food after " + uses + " uses";
            return Step.FAIL;
        }
        if (!s.foodPresent()) {
            reason = "no food in the inventory";
            return Step.FAIL;
        }
        if (stillTicks < SETTLE_TICKS) return Step.WAIT;
        if (!s.holding()) return Step.HOLD;
        if (!s.canUse()) return Step.WAIT;
        usedAt = now;
        usedAtMs = clockMs.getAsLong();
        countAtUse = s.count();
        hungerAtUse = s.hunger();
        uses++;
        return Step.USE;
    }

    /** A use was sent and is neither consumed nor released: cancelling must release it. */
    boolean using() {
        return usedAt >= 0;
    }

    int uses() {
        return uses;
    }

    String reason() {
        return reason;
    }
}
