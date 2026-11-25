package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.DisconnectDrop;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.bot.WorldView;
import com.zenith.feature.player.Input;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.zenith.Globals.BOT;

/**
 * {@code BotActions.recoverDrops}: after a reconnect, picks up the
 * stacks the disconnect dropped ({@link DropWatch}). A drop the inventory holds again ({@link DropWatch#back}: the click
 * that took it never reached the server, or the server picked it up at the rejoin) is done at once, and so is one the
 * bot stands more than {@code radius} blocks from. Otherwise: the stacks of its item on the ground within
 * {@code radius} of where it dropped (ZenithProxy's entity cache, {@code WorldView.itemsNear}), nearest first, looked
 * for again until the chunk there is loaded and {@link #LOOK_TICKS} after (a reconnect's world and entities come in
 * after the login), the inventory checked again each time; a plain walk next to each (never breaking anything, never
 * flying), then sneaking without a turn until the bot's centre is within {@link DropWatch#REACH} of it on both axes (at
 * most {@link #CLOSE_TICKS}; a walk ends as soon as the feet enter the cell, often too far for the server's pickup), a
 * stand of {@link #STAND_TICKS} for the server to hand it over, then once onto its block. What came into the inventory
 * counts as picked up. Never fails for what it cannot find: the outcomes say it, at most {@link #BUDGET_TICKS} after the
 * start.
 */
final class DropRecoveryTask extends ZenithTask {
    /** Ticks the whole pickup may take; what is left then counts as not found. */
    static final int BUDGET_TICKS = 600;
    /** Ticks standing beside a stack for the server to hand it over (what is left of its pickup delay may still run). */
    static final int STAND_TICKS = 40;
    /**
     * Ticks to keep looking for a drop's stack once the chunk where it dropped is loaded: after a reconnect the server
     * sends the item entities with or after their chunk (local drill 2026-10-25 at 10x: the look 0.2 s after the
     * login found nothing, the chunk not loaded yet, and the map on the ground was given up).
     */
    static final int LOOK_TICKS = 60;
    /**
     * Ticks to sneak from where the walk next to a stack ended until the bot is within {@link DropWatch#REACH} of it
     * (local drill 2026-10-25 at 10x: the map the anvil dropped lay a block above the bot's floor beside the anvil; the
     * walk to a cell next to it ended 1.7 blocks off, out of the server's pickup, and no walk onto its block exists, so
     * it was given up).
     */
    static final int CLOSE_TICKS = 30;

    private enum Step { NEXT, WALK, CLOSE, STAND }

    private final List<DisconnectDrop> drops;
    private final double radius;
    private final Consumer<List<DisconnectDrop.Outcome>> result;
    private final List<DisconnectDrop.Outcome> outcomes = new ArrayList<>();
    /** Entities given up on (no way there, or still there after both stands). */
    private final Set<Integer> given = new HashSet<>();
    private int index = -1;
    private int baseline;
    private Step step = Step.NEXT;
    private WorldView.@Nullable ItemInFlight target;
    private @Nullable GoalNearXZ goal;
    /** 1: the walk next to the stack; 0: onto its block. */
    private int closeness;
    private long standSince;
    private long closeSince;
    /** The tick the current drop's chunk was first seen loaded; -1: not yet. */
    private long loadedAt = -1;
    /** Why a stack of the current drop seen on the ground was given up, or null (none seen). */
    private @Nullable String seenWhy;

    DropRecoveryTask(ZenithBotActions ctx, List<DisconnectDrop> drops, double radius,
                     Consumer<List<DisconnectDrop.Outcome>> result) {
        super(ctx, BUDGET_TICKS + 200);
        this.drops = List.copyOf(drops);
        this.radius = radius;
        this.result = result;
        artRecoveryAllowed = false; // a tidy step: never breaks its way to a stack
    }

    @Override
    public String name() {
        return "recoverDrops " + drops.size();
    }

    @Override
    protected boolean flightAllowed() {
        return false;
    }

    @Override
    protected TaskStatus step() {
        if (index < 0) begin(0);
        if (now > BUDGET_TICKS) {
            while (index < drops.size()) end("ran out of time");
        }
        while (index < drops.size()) {
            if (work(drops.get(index)) == TaskStatus.RUNNING) return TaskStatus.RUNNING;
        }
        walker.stop();
        result.accept(List.copyOf(outcomes));
        return TaskStatus.SUCCESS;
    }

    /** Starts on drop {@code i}: the inventory's count of its item now, against which the pickups are counted. */
    private void begin(int i) {
        index = i;
        baseline = i < drops.size() ? Inv.count(Inv.is(drops.get(i).item())) : 0;
        step = Step.NEXT;
        target = null;
        goal = null;
        loadedAt = -1;
        seenWhy = null;
    }

    /** The current drop is done: its outcome, and on to the next. */
    private void end(@Nullable String why) {
        DisconnectDrop d = drops.get(index);
        int picked = Math.max(0, Inv.count(Inv.is(d.item())) - baseline);
        outcomes.add(new DisconnectDrop.Outcome(d, picked, picked >= d.count() ? null : why, false));
        begin(index + 1);
    }

    /** The current drop is in the inventory and was not picked up here ({@link DropWatch#back}): done, nothing lost. */
    private boolean backAlready(DisconnectDrop d) {
        int have = Inv.count(Inv.is(d.item()));
        if (have - baseline >= d.count() || !DropWatch.back(d, have)) return false;
        outcomes.add(new DisconnectDrop.Outcome(d, 0, null, true));
        begin(index + 1);
        return true;
    }

    /** One tick on drop {@code d}: RUNNING while it goes on; anything else when it ended ({@link #end}). */
    private TaskStatus work(DisconnectDrop d) {
        switch (step) {
            case NEXT -> {
                if (backAlready(d)) return TaskStatus.SUCCESS;
                int away = DropWatch.distance(Walker.feet(), d.at());
                if (away > radius) {
                    end("the bot is " + away + " blocks from where it dropped");
                    return TaskStatus.SUCCESS;
                }
                List<WorldView.ItemInFlight> near = ctx.world().itemsNear(d.at(), radius + 1);
                target = DropWatch.next(near, d.item(), d.at(), radius, BOT.getX(), BOT.getY(), BOT.getZ(), given);
                if (target == null) {
                    boolean loaded = ctx.world().isLoaded(d.at());
                    if (loaded && loadedAt < 0) loadedAt = now;
                    boolean unread = near.stream().anyMatch(e -> e.item() == null && !given.contains(e.entityId()));
                    if (DropWatch.keepLooking(loaded, loaded ? now - loadedAt : 0, unread, LOOK_TICKS)) {
                        return TaskStatus.RUNNING; // the world or the entities not in yet (BUDGET_TICKS bounds it)
                    }
                    end(seenWhy != null ? seenWhy : DisconnectDrop.NONE_ON_THE_GROUND + (int) radius + " blocks");
                    return TaskStatus.SUCCESS;
                }
                LOG.debug("Disconnect drop: walking to {} {} at {}", target.count(), d.item(),
                    String.format("%.1f %.1f %.1f", target.x(), target.y(), target.z()));
                closeness = 1;
                goal = goalFor(target, closeness);
                step = Step.WALK;
                return TaskStatus.RUNNING;
            }
            case WALK -> {
                WorldView.ItemInFlight t = target;
                GoalNearXZ g = goal;
                if (t == null || g == null || gone(t, d)) {
                    step = Step.NEXT;
                    return TaskStatus.RUNNING;
                }
                TaskStatus w = walkUntil(List.of(g), () -> feetIn(g) || gone(t, d) || inReach(t), 1);
                if (w == TaskStatus.RUNNING) return w;
                if (w == TaskStatus.FAILED) {
                    LOG.debug("Disconnect drop: no way to the {} at {}: {}", d.item(),
                        String.format("%.1f %.1f %.1f", t.x(), t.y(), t.z()), walker.error());
                    seenWhy = "seen on the ground " + DropWatch.whereFrom(d.at(), t.x(), t.y(), t.z())
                        + ", no way to it";
                    given.add(t.entityId());
                    step = Step.NEXT;
                    return TaskStatus.RUNNING;
                }
                walker.stop();
                if (!inReach(t) && zones.deepAt(t.x(), t.y(), t.z(), NoGoZones.FEET_MARGIN) == null) {
                    step = Step.CLOSE;
                    closeSince = now;
                } else {
                    step = Step.STAND;
                    standSince = now;
                }
                return TaskStatus.RUNNING;
            }
            case CLOSE -> {
                WorldView.ItemInFlight t = target;
                if (t == null || gone(t, d)) {
                    step = Step.NEXT;
                    return TaskStatus.RUNNING;
                }
                if (inReach(t) || now - closeSince >= CLOSE_TICKS) {
                    if (!inReach(t)) {
                        LOG.debug("Disconnect drop: not within reach of the {} after {} ticks sneaking ({} {} off)",
                            d.item(), CLOSE_TICKS, String.format("%.2f", t.x() - BOT.getX()),
                            String.format("%.2f", t.z() - BOT.getZ()));
                    }
                    clicker.submit(now, null, null, Input.builder().sneaking(true).build());
                    step = Step.STAND;
                    standSince = now;
                    return TaskStatus.RUNNING;
                }
                // sneaking (no fall off an edge), the keys toward it, no turn
                int[] keys = Aim.movementKeys(BOT.getYaw(), t.x() - BOT.getX(), t.z() - BOT.getZ());
                clicker.submit(now, null, null, Input.builder().sneaking(true).pressingForward(keys[0] > 0)
                    .pressingBack(keys[0] < 0).pressingLeft(keys[1] > 0).pressingRight(keys[1] < 0).build());
                return TaskStatus.RUNNING;
            }
            case STAND -> {
                holdStill();
                WorldView.ItemInFlight t = target;
                if (t == null || gone(t, d)) {
                    step = Step.NEXT;
                    return TaskStatus.RUNNING;
                }
                if (now - standSince < STAND_TICKS) return TaskStatus.RUNNING;
                if (closeness > 0) {
                    closeness = 0;
                    goal = goalFor(t, closeness);
                    step = Step.WALK;
                    return TaskStatus.RUNNING;
                }
                seenWhy = "seen on the ground " + DropWatch.whereFrom(d.at(), t.x(), t.y(), t.z())
                    + ", not handed over beside it";
                given.add(t.entityId());
                step = Step.NEXT;
                return TaskStatus.RUNNING;
            }
        }
        return TaskStatus.RUNNING;
    }

    /** The feet within {@code range} blocks of the stack's block, a level up or down. */
    private static GoalNearXZ goalFor(WorldView.ItemInFlight t, int range) {
        return new GoalNearXZ((int) Math.floor(t.x()), (int) Math.floor(t.y()), (int) Math.floor(t.z()), range, 1);
    }

    /** The bot's centre within {@link DropWatch#REACH} of the stack. */
    private static boolean inReach(WorldView.ItemInFlight t) {
        return DropWatch.inReach(BOT.getX(), BOT.getY(), BOT.getZ(), t.x(), t.y(), t.z());
    }

    /** The stack is no longer on the ground (picked up, by the bot or anyone, or gone). */
    private boolean gone(WorldView.ItemInFlight t, DisconnectDrop d) {
        for (WorldView.ItemInFlight e : ctx.world().itemsNear(d.at(), radius + 2)) if (e.entityId() == t.entityId()) return false;
        return true;
    }

    @Override
    protected String progress() {
        return " (drop " + (index + 1) + " of " + drops.size() + ", " + step + ")";
    }
}
