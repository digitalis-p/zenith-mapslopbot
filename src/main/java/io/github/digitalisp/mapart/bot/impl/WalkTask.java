package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import static com.zenith.Globals.BOT;

/**
 * {@code walkTo}: Baritone to within {@code range} blocks (horizontal) of target, walk-only settings. Parks (the engine
 * eats or stands still) as soon as the bot is on the ground; a resumed walk paths again from where the bot is.
 */
final class WalkTask extends ZenithTask {
    private final Pos target;
    private final int range;
    private boolean parkRequested;
    private boolean parked;
    /** A retreat from mobs: never an elytra flight ({@code build.elytraWalkLegs} flies the other walks). */
    private boolean noFlight;

    WalkTask(ZenithBotActions ctx, Pos target, int range) {
        super(ctx, walkTimeout(target));
        this.target = target;
        this.range = Math.max(0, range);
    }

    /** This walk never flies (a retreat from mobs). */
    WalkTask noFlight() {
        noFlight = true;
        return this;
    }

    @Override
    protected boolean flightAllowed() {
        return !noFlight;
    }

    static long walkTimeout(Pos target) {
        double dx = BOT.getX() - target.x();
        double dz = BOT.getZ() - target.z();
        return walkTimeout(Math.sqrt(dx * dx + dz * dz));
    }

    /**
     * A near target can be a long way round: the soul sand cross is 6 blocks above the floor under it but about 280
     * blocks of walkway away (local 10x run 2026-10-11: 660 ticks timed out on that walk three times). So the straight
     * distance counts as at least {@link #MIN_TIMEOUT_DISTANCE}; the walker's own stall check (200 ticks without
     * moving) still ends a stuck walk early. Pure.
     */
    static long walkTimeout(double straight) {
        return 600 + (long) (Math.max(straight, MIN_TIMEOUT_DISTANCE) * 20);
    }

    static final double MIN_TIMEOUT_DISTANCE = 96;

    @Override
    public String name() {
        return "walkTo " + target + " r=" + range;
    }

    @Override
    protected TaskStatus step() {
        if (parkRequested || parked) {
            walker.stop();
            holdStill();
            if (BOT.isOnGround()) {
                parked = true;
                parkRequested = false;
            }
            return TaskStatus.RUNNING;
        }
        TaskStatus s = walkNear(target, range, 2);
        if (s == TaskStatus.FAILED) return fail(walker.error());
        return s;
    }

    @Override
    public boolean requestPark() {
        if (!parked) parkRequested = true;
        return true;
    }

    @Override
    public boolean parked() {
        return parked;
    }

    @Override
    public void resume() {
        parked = false;
        parkRequested = false;
        clicker.reset();
    }
}
