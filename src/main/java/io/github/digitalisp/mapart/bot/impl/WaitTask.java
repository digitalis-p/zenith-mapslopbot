package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.bot.TaskStatus;

/** Idles for a number of ticks; keeps the input owned (no movement, rotation unchanged). */
final class WaitTask extends ZenithTask {
    private final int ticks;

    WaitTask(ZenithBotActions ctx, int ticks) {
        super(ctx, 0);
        this.ticks = Math.max(0, ticks);
    }

    @Override
    public String name() {
        return "wait " + ticks;
    }

    @Override
    protected TaskStatus step() {
        if (now > ticks) return TaskStatus.SUCCESS;
        holdStill();
        return now >= ticks ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }
}
