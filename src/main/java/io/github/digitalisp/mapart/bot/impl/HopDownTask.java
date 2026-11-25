package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;

/**
 * {@code hopDown}: walk off a raised stand and drop to {@code landing} (soul sand: from the button cross down onto the
 * empty floor after the clear, the owner 2026-10-11: "it can simply hop down from where it is onto the soulsand"). Baritone
 * plans it with {@link FallGuard} holding the drop limit at {@code maxDrop} for this walk only; walk-only settings as
 * for every walk. SUCCESS once the feet are within 1 block horizontally and 1 vertically of {@code landing} and the
 * bot is on the ground. Logs the health before and after (the fall costs about 4 HP before armour from feet + 6).
 */
final class HopDownTask extends ZenithTask {
    private final Pos landing;
    private final int maxDrop;
    private RefCountedOverride.@Nullable Handle fall;
    private float healthBefore = -1;

    HopDownTask(ZenithBotActions ctx, Pos landing, int maxDrop) {
        super(ctx, WalkTask.walkTimeout(landing));
        this.landing = landing;
        this.maxDrop = maxDrop;
    }

    @Override
    public String name() {
        return "hopDown to " + landing + " (drop up to " + maxDrop + ")";
    }

    /** A drop off a ledge: never sprints (it would carry the bot past its landing). */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.HOP_DOWN;
    }

    @Override
    protected TaskStatus step() {
        if (healthBefore < 0) healthBefore = CACHE.getPlayerCache().getThePlayer().getHealth();
        if (fall == null) fall = FallGuard.acquire(maxDrop);
        else FallGuard.enforce();
        TaskStatus s = walkNear(landing, 1, 1);
        if (s == TaskStatus.FAILED) return fail(walker.error());
        if (s == TaskStatus.SUCCESS) {
            if (!BOT.isOnGround()) return TaskStatus.RUNNING;
            LOG.info("hopped down to {}: health {} -> {}", landing.key(), healthBefore, CACHE.getPlayerCache().getThePlayer().getHealth());
        }
        return s;
    }

    @Override
    protected void onRelease() {
        if (fall != null) fall.release();
        fall = null;
    }
}
