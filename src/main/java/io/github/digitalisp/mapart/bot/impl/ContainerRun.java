package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;

/**
 * A container use once its menu is open: wait for a still tick, submit the clicks
 * as ONE inventory request, wait for it, close the menu, let the inventory cache settle. Pure decisions, tick by tick,
 * like {@link Approach}: {@link ContainerTask} runs it on ZenithProxy, the tests on a simulated Bot that drops every
 * input request while a menu is open, as ZenithProxy 3.7.2 does.
 *
 * <p><b>Still tick in the menu.</b> The clicks go out only after a tick without movement keys (Grim MultiActionsC).
 * Outside menus that is "our request of the previous tick was executed and pressed no key" ({@link Clicker#stillLastTick}).
 * In an open menu ZenithProxy's Bot completes every input request false and resets the movement input, so that never
 * holds; {@link InputGate#stillInMenu} asks instead that our request pressed no key, was not rejected, and the bot did
 * not move during that tick.
 *
 * <p><b>Look only (a scan).</b> Nothing is clicked, so neither the still tick nor a free inventory manager matters, and
 * the player inventory does not change: the menu is closed on the run's first tick (the tick after the menu was seen
 * open, so its contents have arrived, as for a withdrawal's plan), and the run ends on the tick the close is seen,
 * without the settle ticks. Per container that is 3 ticks less than the full sequence (gate B2 scan: about 9 ticks per
 * container at 20x).
 */
public final class ContainerRun {
    /** Ticks to wait for a still tick, a free inventory manager or an accepted request before giving up. */
    public static final int STILL_TIMEOUT = 200;
    /** Ticks the request may take beyond its own clicks. */
    static final int REQUEST_SLACK = 100;
    /** Ticks to wait for the menu to close. */
    static final int CLOSE_TIMEOUT = 100;
    /** Ticks between close packets. */
    static final int CLOSE_RESEND = 20;
    /** Ticks after the menu closed before the result is read (the inventory cache settles). */
    static final int SETTLE_TICKS = 2;

    public enum Step { ACT, WAIT, ANSWERS, CLOSE, SETTLE }

    /** The game while the menu is open. Client tick thread. */
    public interface Io {
        /** Id of the menu open now, 0 when none. */
        int openMenuId();

        /** This tick's input: no movement keys, rotation unchanged. */
        void holdStill();

        /** The task's input request of the tick before {@code now}, or null ({@link Clicker#previous}). */
        InputGate.@Nullable Sent previousInput(long now);

        double x();

        double y();

        double z();

        /** An inventory request (ours or another module's) is executing. */
        boolean inventoryBusy();

        /**
         * Submits the clicks for the open menu {@code menuId} as one inventory request. Returns the ticks it takes
         * (clicks times the delay between them), 0 when there is nothing to click, -1 when the request was rejected.
         */
        int submitClicks(int menuId);

        /** The submitted request: null while it runs, true when it completed, false when another request replaced it. */
        @Nullable Boolean clicksDone();

        /** Sends a close for the open menu. */
        void closeMenu();

        /**
         * Ticks the run may wait, after its request completed, for the server's answers to the clicks before it closes
         * the menu ({@link #answersIn}); 0 = close at once (the request itself ended with its fixed wait).
         */
        default int answerWait() {
            return 0;
        }

        /** The server's answers to the clicks are in the cache (the transfer shows in the player inventory). */
        default boolean answersIn() {
            return true;
        }

        /**
         * Every tick while the request runs, before {@link #clicksDone}: the answer gate of {@code build.containerAnswerWaits}
         * and the one-click-per-tick pacer of {@code build.containerClickEveryTick} ({@link MenuClicks}). Nothing by default.
         */
        default void duringRequest(long now) { }

        /**
         * On the tick the run sees its close went through: the settle ticks may be skipped ({@code build.containerSettleFast}:
         * the answers were in and nothing is on the cursor). False by default: settle as before.
         */
        default boolean settleFast() {
            return false;
        }
    }

    private final int menuId;
    private final Io io;
    private final boolean lookOnly;
    private boolean coasting;
    private boolean answersFast;
    private boolean settledFast;
    private Step step = Step.ACT;
    private long stepAt = Long.MIN_VALUE;
    private long deadline;
    private long closeSentAt = Long.MIN_VALUE;
    private int stillWaits;
    private int answerTicks = -1;
    private @Nullable String problem;
    private String failure = "";

    public ContainerRun(int menuId, Io io) {
        this(menuId, io, false);
    }

    /** @param lookOnly nothing to click (a scan): close at once, no still tick, no settle ticks */
    public ContainerRun(int menuId, Io io, boolean lookOnly) {
        this.menuId = menuId;
        this.io = io;
        this.lookOnly = lookOnly;
    }

    /** Coast-click: the clicks need no movement keys, but not a bot at rest ({@link InputGate#noKeysInMenu}). */
    public ContainerRun coasting(boolean on) {
        this.coasting = on;
        return this;
    }

    /**
     * {@code build.containerAnswerWaits}: the steps after the request follow in the tick they are reached (request
     * done, answers in, close), so the close goes out on the first tick that sees the last click's answer. Off: one
     * step a tick, as before.
     */
    public ContainerRun answersFast(boolean on) {
        this.answersFast = on;
        return this;
    }

    /** The run ended on the tick after its close, without the settle ticks ({@link Io#settleFast}). */
    public boolean settledFast() {
        return settledFast;
    }

    public Step step() {
        return step;
    }

    /** What went wrong with the request although the menu closed fine (replaced, timed out), or null. */
    public @Nullable String problem() {
        return problem;
    }

    public String failure() {
        return failure;
    }

    /** Ticks the run waited in ACT for a still tick (for logs and tests). */
    public int stillWaits() {
        return stillWaits;
    }

    /** Ticks the run waited in ANSWERS (0 = the answers were in at once, the wait's limit = they were not), -1 = no wait. */
    public int answerTicks() {
        return answerTicks;
    }

    public String progress() {
        return " (menu " + step + ")";
    }

    /** RUNNING until the menu is closed and settled (SUCCESS), or FAILED with {@link #failure}. */
    public TaskStatus tick(long now) {
        if (stepAt == Long.MIN_VALUE) stepAt = now;
        io.holdStill();
        return switch (step) {
            case ACT -> act(now);
            case WAIT -> waitForClicks(now);
            case ANSWERS -> waitForAnswers(now);
            case CLOSE -> close(now);
            case SETTLE -> now - stepAt < SETTLE_TICKS ? TaskStatus.RUNNING : TaskStatus.SUCCESS;
        };
    }

    private TaskStatus act(long now) {
        if (lookOnly) {
            if (io.openMenuId() != menuId) return fail("container closed unexpectedly");
            enter(Step.CLOSE, now);
            return close(now);
        }
        boolean busy = io.inventoryBusy();
        boolean still = coasting ? InputGate.noKeysInMenu(now, io.previousInput(now))
            : InputGate.stillInMenu(now, io.previousInput(now), io.x(), io.y(), io.z());
        if (busy || !still) {
            stillWaits++;
            if (now - stepAt > STILL_TIMEOUT) {
                return fail(busy ? "inventory manager busy"
                    : "the bot did not hold still in the menu for " + STILL_TIMEOUT + " ticks (moving, or input owned elsewhere)");
            }
            return TaskStatus.RUNNING;
        }
        if (io.openMenuId() != menuId) return fail("container closed unexpectedly");
        int ticks = io.submitClicks(menuId);
        if (ticks < 0) {
            if (now - stepAt > STILL_TIMEOUT) return fail("inventory request rejected (higher priority module busy)");
            return TaskStatus.RUNNING;
        }
        if (ticks == 0) {
            enter(Step.CLOSE, now);
            return TaskStatus.RUNNING;
        }
        deadline = now + ticks + REQUEST_SLACK;
        enter(Step.WAIT, now);
        return TaskStatus.RUNNING;
    }

    private TaskStatus waitForClicks(long now) {
        io.duringRequest(now);
        Boolean done = io.clicksDone();
        if (done != null) {
            if (!done) problem = "inventory request was replaced";
            enter(io.answerWait() > 0 && done ? Step.ANSWERS : Step.CLOSE, now);
            if (answersFast) return step == Step.ANSWERS ? waitForAnswers(now) : close(now);
        } else if (now > deadline) {
            problem = "inventory request timed out";
            enter(Step.CLOSE, now);
        }
        return TaskStatus.RUNNING;
    }

    /**
     * The menu stays open until the server's answers are in the cache or {@link Io#answerWait} ticks have passed
     * (the fixed wait it replaces): a close sent earlier would lose the answers, the cache ignores them once the menu
     * is gone.
     */
    private TaskStatus waitForAnswers(long now) {
        if (io.openMenuId() != menuId) {
            enter(Step.CLOSE, now); // closed under us: the close step settles it
            return TaskStatus.RUNNING;
        }
        if (io.answersIn() || now - stepAt >= io.answerWait()) {
            answerTicks = (int) (now - stepAt);
            enter(Step.CLOSE, now);
            if (answersFast) return close(now);
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus close(long now) {
        if (io.openMenuId() != 0) {
            if (closeSentAt == Long.MIN_VALUE || now - closeSentAt > CLOSE_RESEND) {
                io.closeMenu();
                closeSentAt = now;
            }
            if (now - stepAt > CLOSE_TIMEOUT) return fail("container did not close");
            return TaskStatus.RUNNING;
        }
        if (lookOnly) return TaskStatus.SUCCESS; // nothing moved: no cache to settle
        // build.containerSettleFast: the answers were in and nothing is on the cursor, so nothing is left to settle. Only
        // after our own close and never in its tick: the next task's aim or walk comes after the close tick (Grim
        // MultiActionsD), and no key while the menu was open (every tick of the run held still: MultiActionsC)
        if (closeSentAt != Long.MIN_VALUE && now > closeSentAt && io.settleFast()) {
            settledFast = true;
            return TaskStatus.SUCCESS;
        }
        enter(Step.SETTLE, now);
        return TaskStatus.RUNNING;
    }

    private void enter(Step s, long now) {
        step = s;
        stepAt = now;
    }

    private TaskStatus fail(String why) {
        failure = why;
        return TaskStatus.FAILED;
    }
}
