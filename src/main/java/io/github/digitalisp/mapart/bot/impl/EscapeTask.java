package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.EscapeRequest;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Set;

/**
 * {@code escape}: one rung of the setback escape ladder, run by the engine
 * after a setback back-off and before the phase carries on.
 * <ol>
 *   <li>Tick 1: Baritone stopped, the bot held still, and its movement state resynced the way ZenithProxy does when
 *       bot ticks start ({@code Bot.syncFromCache(false)}: position and rotation from the cache, velocity 0, on the
 *       ground, sneak and sprint as the cache has them); any block prediction ZenithProxy's own interactions left open
 *       is ended ({@code endPredictionsUpTo}: the cache falls back to the server's states; our raw clicks leave none).
 *       Live 2026-10-11 the setbacks stopped once the owner's takeover made ZenithProxy resync Bot this way.</li>
 *   <li>{@link #SETTLE_TICKS} ticks held still: the anti-cheat's block resync after a setback arrives and is applied
 *       before the cells are judged.</li>
 *   <li>{@link EscapePlanner}: open floor cells in the rung's ring around the stuck cell, best first. None: FAILED.</li>
 *   <li>A walk with Baritone (place/break/inventory held off by {@link PathingGuard}) to the first of them, under a
 *       path check ({@link EscapePlanner#pathRefusal}: no step up onto a block, not back past a setback cell). A walk
 *       that fails: the next candidate, at most {@link #MAX_WALKS} walks, then FAILED.</li>
 *   <li>Arrived (feet cell = the target): held still {@link #ARRIVED_HOLD_TICKS} ticks, SUCCESS.</li>
 * </ol>
 * It never clicks, never touches the inventory, never places or breaks. Every decision is logged with the cells.
 */
final class EscapeTask extends ZenithTask {
    /** About 30 s: the settle, then three short walks (each within {@link EscapePlanner#maxRing} 6 of the bot). */
    static final long TIMEOUT_TICKS = 600;
    /** Held still after the resync, for the server's block resync. */
    static final int SETTLE_TICKS = 10;
    /** Held still in the cell reached before SUCCESS. */
    static final int ARRIVED_HOLD_TICKS = 5;
    /** Walks to candidates tried at most. */
    static final int MAX_WALKS = 3;

    /** Never stood in or on (mirrors {@link StandSpots}'s lists; Baritone avoids most of them too). */
    static final Set<String> HAZARDS = Set.of("minecraft:fire", "minecraft:soul_fire", "minecraft:cobweb",
        "minecraft:sweet_berry_bush", "minecraft:powder_snow", "minecraft:wither_rose", "minecraft:tripwire",
        "minecraft:nether_portal", "minecraft:end_portal", "minecraft:bubble_column", "minecraft:pointed_dripstone",
        "minecraft:magma_block", "minecraft:campfire", "minecraft:soul_campfire", "minecraft:cactus");

    private final EscapeRequest request;
    private final Pos stuck;
    private final String tag;
    private EscapePlanner.@Nullable Plan plan;
    /** The bot's feet cell when the plan was made. */
    private @Nullable Pos origin;
    /** Index of the candidate walked to. */
    private int next;
    private int walks;
    private int failedWalks;
    private String lastError = "";
    /** Tick the target cell was reached, or 0. */
    private long arrivedAt;

    EscapeTask(ZenithBotActions ctx, EscapeRequest request) {
        super(ctx, TIMEOUT_TICKS);
        this.request = request;
        this.stuck = request.cell();
        this.tag = "escape rung " + request.rung();
    }

    @Override
    public String name() {
        return tag;
    }

    @Override
    protected String progress() {
        Pos t = target();
        return " (bot at " + Walker.feet().key() + (t != null ? ", walking to " + t.key() + ", walk " + walks
            + " of " + MAX_WALKS : "") + ")";
    }

    /** The walk to open floor. */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.ESCAPE;
    }

    @Override
    protected TaskStatus step() {
        if (now == 1) {
            stopAndResync();
            holdStill();
            return TaskStatus.RUNNING;
        }
        if (now <= 1 + SETTLE_TICKS) {
            holdStill();
            return TaskStatus.RUNNING;
        }
        if (plan == null) {
            TaskStatus s = makePlan();
            if (s != TaskStatus.RUNNING) return s;
        }
        Pos t = target();
        if (t == null) return fail("no escape target left"); // not reached: walkFailed ends the task first
        if (arrivedAt > 0) {
            holdStill();
            if (now - arrivedAt < ARRIVED_HOLD_TICKS) return TaskStatus.RUNNING;
            LOG.info(tag + ": standing at " + Walker.feet().key() + " (target " + t.key() + "), " + stuck.key()
                + " left behind");
            return TaskStatus.SUCCESS;
        }
        TaskStatus s = walkUntil(List.of(new GoalNearXZ(t.x(), t.y(), t.z(), 0, 0)), () -> Walker.feet().equals(t));
        if (s == TaskStatus.SUCCESS) {
            arrivedAt = now;
            walker.pathCheck(null);
            LOG.info(tag + ": reached " + t.key() + "; holding still " + ARRIVED_HOLD_TICKS + " ticks");
            holdStill();
            return TaskStatus.RUNNING;
        }
        if (s == TaskStatus.FAILED) return walkFailed(plan.candidates(), t);
        return TaskStatus.RUNNING;
    }

    /** Baritone stopped, Bot's movement state and the block predictions resynced from the cache; each step on its own. */
    private void stopAndResync() {
        ResyncTask.resyncLocal(walker, tag);
        Pos feet = Walker.feet();
        LOG.info(tag + " at " + stuck.key() + ": stopped and resynced the movement state"
            + (feet.equals(stuck) ? "" : " (bot at " + feet.key() + ")") + "; holding still " + SETTLE_TICKS
            + " ticks");
    }

    /** Plans the candidates and sets the walker's path check. RUNNING when there is one, else the failure. */
    private TaskStatus makePlan() {
        origin = Walker.feet();
        Set<Pos> avoid = request.avoid();
        plan = EscapePlanner.plan(new WorldCells(), stuck, origin, request.rung(), avoid);
        if (plan.candidates().isEmpty()) {
            String why = plan.none(stuck);
            LOG.warn(tag + ": " + why + "; bot at " + origin.key());
            return fail(why);
        }
        walker.pathCheck(cells -> EscapePlanner.pathRefusal(cells, stuck, avoid));
        walks = 1;
        EscapePlanner.Candidate c = plan.candidates().getFirst();
        LOG.info(tag + ": " + plan.candidates().size() + " open floor cells; walking to " + c.feet().key() + " ("
            + c.tallSides() + " tall neighbors)");
        return TaskStatus.RUNNING;
    }

    /** The walk to {@code t} failed: the next candidate, or FAILED after {@link #MAX_WALKS} walks or none left. */
    private TaskStatus walkFailed(List<EscapePlanner.Candidate> all, Pos t) {
        failedWalks++;
        lastError = walker.error();
        LOG.warn(tag + ": walk to " + t.key() + " failed: " + lastError + "; bot at " + Walker.feet().key());
        next++;
        if (walks >= MAX_WALKS || next >= all.size()) {
            Pos from = origin != null ? origin : stuck;
            return fail("no open floor cell reachable from " + from.key() + " (" + all.size() + " candidates, "
                + failedWalks + " walks failed: " + lastError + "); bot at " + Walker.feet().key());
        }
        walks++;
        EscapePlanner.Candidate c = all.get(next);
        LOG.info(tag + ": walking to " + c.feet().key() + " (" + c.tallSides() + " tall neighbors), candidate "
            + (next + 1) + " of " + all.size() + ", walk " + walks + " of " + MAX_WALKS);
        return TaskStatus.RUNNING;
    }

    private @Nullable Pos target() {
        return plan == null || next >= plan.candidates().size() ? null : plan.candidates().get(next).feet();
    }

    @Override
    protected void onRelease() {
        walker.pathCheck(null);
    }

    /** {@link EscapePlanner.Cells} over the cached world (server-confirmed blocks) and the task's no-go zones. */
    private final class WorldCells implements EscapePlanner.Cells {
        @Override
        public boolean isLoaded(Pos p) {
            return ctx.world().isLoaded(p);
        }

        @Override
        public double collisionTop(Pos p) {
            BlockSpec b = ctx.world().block(p);
            return b.isAir() ? 0 : BlockStates.collisionTop(b);
        }

        @Override
        public boolean hasFluid(Pos p) {
            return ctx.world().hasFluid(p);
        }

        @Override
        public boolean hazard(Pos p) {
            return HAZARDS.contains(ctx.world().block(p).id());
        }

        @Override
        public boolean allowed(Pos feet) {
            return zones.at(feet) == null;
        }
    }
}
