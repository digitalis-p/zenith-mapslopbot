package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;

import static com.zenith.Globals.BOT;

/**
 * Right-clicks a block once: a button, a lever, a note block. The decisions
 * are {@link UseRun}'s: an {@link Approach} to a face centre in reach and in sight, {@code standAt} (from the config)
 * first, look on one tick and click on the next; success when the block's state changes within 20 ticks; one retry.
 * Stand spots never lie on a platform's art layer or in its flying machines' sweep
 * ({@link StandSpots#awayFromClearSweeps}), except {@code standAt} itself, nor in a no-go zone. A block in a protected
 * region ({@code site.protectedRegions}) is never used: the task fails at once.
 *
 * <p>{@linkplain #pressButton Button mode} ({@code BotActions.pressButton}, the map counter): {@link UseRun#pressButton}
 * (only a button, no click while it is still pressed, success when it shows pressed after the click), and the stand
 * spots only stay off the art layers ({@link UseRun#buttonStandCells}): the press runs outside any clear, and the
 * counter may sit in the sweep beside the tile. No-go zones and protected regions as above.
 *
 * <p>Parks (the engine eats or stands still) only while Baritone walks it to a stand spot, before anything is aimed
 * or clicked; otherwise it runs to its end (a few ticks). So a LEVER's flip back, clicked from where the flip on was,
 * never waits for a meal while the lever is held.
 */
final class UseBlockTask extends ZenithTask {
    private final Pos pos;
    private final @Nullable Pos standAt;
    private final boolean button;
    private @Nullable UseRun<Aiming.Shot> run;
    private boolean parkRequested;
    private boolean parked;

    UseBlockTask(ZenithBotActions ctx, Pos pos, @Nullable Pos standAt) {
        this(ctx, pos, standAt, false);
    }

    private UseBlockTask(ZenithBotActions ctx, Pos pos, @Nullable Pos standAt, boolean button) {
        super(ctx, (standAt == null ? WalkTask.walkTimeout(pos) : WalkTask.walkTimeout(standAt)) + 600
            + 400L * (Approach.MAX_SPOTS - 1));
        this.pos = pos;
        this.standAt = standAt;
        this.button = button;
    }

    /** One press of the button at {@code pos} (the map counter), {@code standAt} first if non-null. */
    static UseBlockTask pressButton(ZenithBotActions ctx, Pos pos, @Nullable Pos standAt) {
        return new UseBlockTask(ctx, pos, standAt, true);
    }

    @Override
    public String name() {
        return (button ? "pressButton " : "useBlock ") + pos;
    }

    @Override
    protected String progress() {
        return run == null ? "" : run.progress();
    }

    /** The walk to the button or lever; the click waits for the quiet window. */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.BUTTON;
    }

    @Override
    protected TaskStatus step() {
        String refusal = protectedRegionRefusal(pos, button ? "press" : "use");
        if (refusal != null) return fail(refusal);
        if (parkRequested || parked) {
            walker.stop();
            holdStill();
            if (BOT.isOnGround()) {
                parked = true;
                parkRequested = false;
            }
            return TaskStatus.RUNNING;
        }
        if (!ctx.world().isLoaded(pos)) {
            TaskStatus s = walkNear(standAt != null ? standAt : pos, 3, 3);
            if (s == TaskStatus.FAILED) return fail("walking to " + pos + ": " + walker.error());
            if (s == TaskStatus.SUCCESS && !ctx.world().isLoaded(pos)) return fail("chunk of " + pos + " not loaded");
            return TaskStatus.RUNNING;
        }
        if (run == null) {
            walker.stop(); // the walk that loaded the chunk ends here
            List<Pos> first = standAt == null ? List.of() : List.of(standAt);
            run = button
                ? UseRun.pressButton(pos, first, ctx.standSpot(pos), reach(),
                    new ApproachIo(() -> List.of(pos), UseRun.buttonStandCells(platforms(ctx.config()))))
                : new UseRun<>(pos, first, ctx.standSpot(pos), reach(),
                    new ApproachIo(() -> List.of(pos), awayFromClearSweeps(ctx.config())));
            run.brake(ctx.config().build.standSpotBrake);
        }
        TaskStatus s = run.tick(now);
        if (s == TaskStatus.FAILED) return fail(run.failure());
        if (s == TaskStatus.SUCCESS) ctx.rememberStandSpot(pos, run.standingCell());
        return s;
    }

    /** Where the bot may stand to press something: off every platform's art layer and flying-machine sweep. */
    static StandSpots.CellRule awayFromClearSweeps(MapArtConfig config) {
        return StandSpots.awayFromClearSweeps(platforms(config));
    }

    /** Every configured platform's tile. */
    static List<PlatformGeometry> platforms(MapArtConfig config) {
        List<PlatformGeometry> gs = new ArrayList<>();
        for (MapArtConfig.PlatformCfg p : config.site.platforms) gs.add(p.geometry());
        return gs;
    }

    @Override
    public boolean requestPark() {
        if (parked) return true;
        if (run == null || !run.canPark()) return false; // not walking: a few ticks to the end
        parkRequested = true;
        run.approach().pauseWalk();
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
